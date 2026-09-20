package com.linkroa.deepdataagent.runtime.application.service;

import com.linkroa.deepdataagent.runtime.application.port.CoordinationLeaseStore;
import com.linkroa.deepdataagent.runtime.domain.model.enums.CoordLeaseType;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * 协调层租约应用服务（D8：协调层收口防重）。
 *
 * <p>统一封装两类租约的获取 / 续期 / 释放编排（存储经 {@link CoordinationLeaseStore} 端口倒置，
 * 由 {@code move-coordination-leases-to-redis} PR-C4 起唯一实现为 Redis：owner-scoped Lua 原子裁决
 * + 服务端 TTL 自过期 + owner 二级索引枚举）：</p>
 * <ul>
 *   <li><b>turn 租约</b>：会话级独占，{@code executeRound} 启动事务内获取、终态唯一出口释放，
 *       进程崩溃后由启动恢复按租约承载恢复 / 回收（见 {@code StartupRecoveryLifecycle}）；</li>
 *   <li><b>fire lease</b>：调度器触发防重（窗口内拒绝重复触发），触发流程结束显式释放，
 *       异常退出由 TTL 兜底。</li>
 * </ul>
 *
 * <p><b>过期回收已随本变更退役</b>（move-coordination-leases-to-redis D3-1）：租约到期由 Redis
 * 服务端 TTL 自过期（{@code PEXPIRE}），无需任何清扫任务或启动回收语句，故本服务不设
 * {@code reapExpiredLeases()}；崩溃残留的 owner 索引成员由 {@code listOwnedTurnKeys} 惰性对账剔除。</p>
 */
@Service
public class CoordinationLeaseService {

    private static final Logger log = LoggerFactory.getLogger(CoordinationLeaseService.class);

    /** turn 租约存活时长（覆盖单轮最长执行，含 HITL 等待；过期后可被接管；续约周期 = TTL/3，供执行子包共享）。 */
    public static final Duration TURN_LEASE_TTL = Duration.ofMinutes(10);

    /** 调度 fire lease 存活时长（触发窗口防重；异常退出时的兜底过期上限）。 */
    static final Duration FIRE_LEASE_TTL = Duration.ofSeconds(30);

    /** 实例标识环境变量（容器编排显式指定实例槽位，多实例部署必填）。 */
    static final String ENV_INSTANCE_ID = "APP_INSTANCE_ID";

    /** 实例标识来源（决定启动日志级别与启动恢复面宽窄，move-coordination-leases-to-redis D6）。 */
    enum InstanceIdSource {
        /** 显式配置 {@code APP_INSTANCE_ID}（推荐：滚动发布槽位稳定，恢复面完整）。 */
        ENV,
        /** 回落主机名（{@code HOSTNAME} / {@code COMPUTERNAME}）：同机重启稳定，容器重建即漂移。 */
        HOST_NAME,
        /** 回落随机值：每次启动都是新实例，跨进程崩溃残留无法按槽位枚举。 */
        RANDOM
    }

    /** 实例标识解析结果（值 + 来源）。 */
    record ResolvedInstance(String instanceId, InstanceIdSource source) {
    }

    /** 持有者实例标识解析结果（进程级：区分租约由哪个实例获取；启动恢复据此只回收本实例槽位残留租约）。 */
    private final ResolvedInstance resolvedInstance;

    /** 持有者实例标识（ {@link #resolvedInstance} 的值投影，端口调用侧使用）。 */
    private final String instanceId;

    /**
     * 创建协调租约服务：按「{@code APP_INSTANCE_ID} → 主机名 → 随机值」解析进程级实例标识。
     */
    public CoordinationLeaseService() {
        this(resolveInstanceId(System::getenv));
    }

    /** 测试装配入口：显式给定实例标识解析结果（不读环境变量）。 */
    CoordinationLeaseService(ResolvedInstance resolvedInstance) {
        this.resolvedInstance = resolvedInstance;
        this.instanceId = resolvedInstance.instanceId();
    }

    /**
     * 解析稳定实例标识：优先环境变量 {@code APP_INSTANCE_ID}（容器编排显式指定实例槽位），
     * 次选主机名（{@code HOSTNAME} / {@code COMPUTERNAME}，同机重启保持不变），
     * 均缺失时回落随机值（此时启动恢复仅能回收本进程自身持有的租约，语义安全但恢复面收窄）。
     *
     * <p>来源随标识一并返回，供 {@link #reportInstanceIdSource()} 启动自检按来源分级告警
     * （design D6：{@code APP_INSTANCE_ID} 缺失必须启动 WARN，不得静默回落）。</p>
     */
    static ResolvedInstance resolveInstanceId(Function<String, String> env) {
        String explicit = env.apply(ENV_INSTANCE_ID);
        if (explicit != null && !explicit.isBlank()) {
            return new ResolvedInstance("instance-" + explicit.trim(), InstanceIdSource.ENV);
        }
        for (String candidate : List.of("HOSTNAME", "COMPUTERNAME")) {
            String value = env.apply(candidate);
            if (value != null && !value.isBlank()) {
                return new ResolvedInstance("instance-" + value.trim(), InstanceIdSource.HOST_NAME);
            }
        }
        return new ResolvedInstance("instance-" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 12), InstanceIdSource.RANDOM);
    }

    @Resource
    private CoordinationLeaseStore leaseStore;

    /** 本实例持有者标识（启动恢复 / 优雅停机据此定位本实例槽位租约）。 */
    public String instanceId() {
        return instanceId;
    }

    /**
     * 启动自检：实例标识来源分级告警（design D6）。
     *
     * <p>{@code APP_INSTANCE_ID} 缺失时租约持有者标识回落主机名 / 随机值——容器重建后实例槽位
     * 漂移，上一进程崩溃残留的租约无法按 owner set 枚举（只能等 TTL 自过期），故 MUST WARN
     * 而非静默；多实例部署（compose 滚动发布）必须显式配置稳定值。</p>
     */
    @PostConstruct
    void reportInstanceIdSource() {
        switch (resolvedInstance.source()) {
            case ENV -> log.info("协调租约持有者实例标识: instanceId={}（来源 {}）",
                    instanceId, ENV_INSTANCE_ID);
            case HOST_NAME -> log.warn("{} 未配置，协调租约实例标识回落主机名: instanceId={}"
                    + "——容器重建即漂移，跨进程崩溃残留租约将等 TTL（{}）自过期而非启动即时回收；"
                    + "多实例 / 滚动发布部署必须显式配置稳定值",
                    ENV_INSTANCE_ID, instanceId, TURN_LEASE_TTL);
            case RANDOM -> log.warn("{} 与主机名均缺失，协调租约实例标识回落随机值: instanceId={}"
                    + "——启动恢复无法枚举上一进程残留租约（仅能靠 TTL 兜底），生产部署禁止此形态",
                    ENV_INSTANCE_ID, instanceId);
        }
    }

    /**
     * 优雅停机：遍历<b>本实例</b> owner 索引主动释放全部 turn 租约（design D5⑤）。
     *
     * <p>滚动发布下不等 TTL 即归还执行权，新实例可立即领取会话；进程被 kill -9 时本方法不执行，
     * 由 TTL 自过期 + 启动恢复兜底（同 D6）。逐条 owner-scoped 释放（F11：已被接管的租约不误摘），
     * 任何存储异常均静默记日志——停机路径 MUST NOT 因释放失败而中断关闭流程。</p>
     */
    @PreDestroy
    void releaseOwnTurnLeasesOnShutdown() {
        List<String> turnKeys;
        try {
            turnKeys = listOwnActiveTurnKeys();
        } catch (RuntimeException ex) {
            log.warn("优雅停机枚举本实例 turn 租约失败（由 TTL 兜底过期）: instanceId={}", instanceId, ex);
            return;
        }
        if (turnKeys.isEmpty()) {
            return;
        }
        int released = 0;
        for (String turnKey : turnKeys) {
            String sessionId = CoordLeaseType.TURN.bizIdFromKey(turnKey);
            try {
                releaseTurnLease(sessionId);
                released++;
            } catch (RuntimeException ex) {
                log.warn("优雅停机释放 turn 租约失败（由 TTL 兜底过期）: sessionId={}", sessionId, ex);
            }
        }
        log.info("优雅停机释放本实例 turn 租约: instanceId={}, 待释放={}, 已释放={}",
                instanceId, turnKeys.size(), released);
    }

    // ==================== turn 租约 ====================

    /**
     * 尝试获取会话的 turn 租约（会话级独占）。
     *
     * @return true=获取成功；false=会话已有未过期的运行中的租约（并发拒绝）
     */
    public boolean tryAcquireTurnLease(String sessionId) {
        boolean acquired = leaseStore.tryAcquire(CoordLeaseType.TURN, CoordLeaseType.TURN.keyOf(sessionId),
                instanceId, TURN_LEASE_TTL);
        if (!acquired) {
            log.info("turn 租约获取失败（会话已有运行中的租约）: sessionId={}", sessionId);
        }
        return acquired;
    }

    /**
     * 续期会话的 turn 租约（长执行 / HITL 等待场景由轮次定时续约任务周期调用）。
     *
     * <p>续约以本实例 {@link #instanceId} 为持有者条件：仅当租约仍由本实例持有且未过期时
     * 成功；返回 false 即确证执行权丧失（租约过期被回收或已被他实例接管），调用方 MUST
     * 据此 fail-closed 中止进行中的执行，MUST NOT 再续期 / 写终态。存储连接 / 命令异常以运行时
     * 异常抛出（区别于确证失权，调用方按 design D5 二分类容忍重试）。</p>
     *
     * @return true=续期成功；false=执行权已失效（不存在 / 已过期 / 已易主）
     */
    public boolean renewTurnLease(String sessionId) {
        return leaseStore.renew(CoordLeaseType.TURN, CoordLeaseType.TURN.keyOf(sessionId), instanceId, TURN_LEASE_TTL);
    }

    /**
     * 释放会话的 turn 租约（幂等；仅释放仍由本实例持有的租约——租约过期被其他实例
     * 接管后，本实例的迟到释放不会误摘接管者的有效租约，审查修复 F11）。
     */
    public void releaseTurnLease(String sessionId) {
        leaseStore.releaseOwned(CoordLeaseType.TURN, CoordLeaseType.TURN.keyOf(sessionId), instanceId);
    }

    /**
     * 会话是否存在有效 turn 租约（任意持有者；启动恢复兜底复位据此跳过存活实例运行中的会话）。
     */
    public boolean hasActiveTurnLease(String sessionId) {
        return leaseStore.findActive(CoordLeaseType.TURN, CoordLeaseType.TURN.keyOf(sessionId));
    }

    /**
     * 列出<b>本实例</b>持有的有效 turn 租约键（启动恢复专用：仅回收本实例槽位上一进程
     * 崩溃残留的执行权，不触碰其他存活实例正在执行的会话，审查修复 F13）。
     *
     * <p>返回租约键（{@code turn:session:<sessionId>}）而非租约对象：Redis 侧无
     * {@code expires_at} / {@code acquired_at} 可回填（TTL 由服务端自持），端口投影到键即可满足
     * 恢复枚举需求（move-coordination-leases-to-redis D3）。</p>
     */
    public List<String> listOwnActiveTurnKeys() {
        return leaseStore.listOwnedTurnKeys(instanceId);
    }

    // ==================== 调度 fire lease ====================

    /**
     * 尝试获取调度器的触发防重租约（窗口内仅一个触发可进入执行编排）。
     *
     * @return true=获取成功；false=窗口内已有触发进行中（重复触发被拒）
     */
    public boolean tryAcquireFireLease(String schedulerId) {
        boolean acquired = leaseStore.fireTryAcquire(schedulerId, instanceId);
        if (!acquired) {
            log.info("调度触发被防重租约拒绝（窗口内已有触发进行中）: schedulerId={}", schedulerId);
        }
        return acquired;
    }

    /**
     * 释放调度器的触发防重租约（触发流程结束调用，幂等；仅释放本实例持有的租约——
     * 触发超过 TTL 且租约被其他实例接管时，本实例的迟到释放不会误摘接管者租约
     * 造成双触发窗口，审查修复 F11）。
     */
    public void releaseFireLease(String schedulerId) {
        leaseStore.fireReleaseOwned(schedulerId, instanceId);
    }
}
