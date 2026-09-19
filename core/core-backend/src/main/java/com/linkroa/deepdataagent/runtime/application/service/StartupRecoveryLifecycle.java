package com.linkroa.deepdataagent.runtime.application.service;

import com.linkroa.deepdataagent.runtime.domain.model.Transition;
import com.linkroa.deepdataagent.runtime.domain.model.enums.CoordLeaseType;
import com.linkroa.deepdataagent.runtime.domain.repository.AgentSessionRepository;
import com.linkroa.deepdataagent.runtime.infrastructure.config.AgentRuntimeProperties;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 启动恢复：进程重启后按协调层租约回收崩溃残留的执行状态（相位选择性复位）。
 *
 * <p>事件溯源模型下轮次表已删除，启动恢复不再基于轮次表，改由 turn 租约承载
 * （单轨语义：租约即执行权）。租约存储为 Redis（owner 二级索引 + key TTL，
 * 见 {@code CoordinationLeaseStore} 端口的 Redis 实现），恢复编排两步：</p>
 * <ol>
 *   <li><b>释放本实例残留租约</b>：枚举本实例 owner 索引中的 turn 租约键并 owner-scoped 释放
 *       （本实例槽位上一进程崩溃前未归还的执行权）；<b>其他存活实例持有的租约不枚举、不释放</b>
 *       （多实例滚动重启不互踢在途轮次，审查修复 F13）；</li>
 *   <li><b>复位孤儿执行</b>：仅对「对外 {@code status ∈ {running, rescheduling}} 且内部
 *       {@code turn_phase ∈ {running, cancelling}} 且不存在任何存活实例持有的有效 turn 租约」
 *       的会话，CAS 复位为 {@code idle / idle}。相位选择性由
 *       {@link Transition#ABANDON_ORPHAN_EXECUTION} 的相位前置守卫在 SQL 层强制：
 *       {@code turn_phase=awaiting_confirmation}（durable HITL 合法可恢复等待）<b>MUST NOT
 *       被复位</b>——其现场完全由事件账本承载，任意实例可经 {@code user.tool_confirmation}
 *       续跑。存量 {@code rescheduling} 行（本期无生产者）按孤儿执行一并复位 idle，
 *       且不补发任何 {@code *.rescheduled} 事件。</li>
 * </ol>
 * <p>幂等执行：复位仅改会话双列，不产生新事件（崩溃轮次的事实以事件账本既有事件为准）。</p>
 *
 * <p><b>不包 DB 事务（Redis 化收尾，2026-09 迭代）</b>：编排半数为 Redis 动作
 * （枚举 / 释放 / 探活租约），不受 PG 事务管辖、不随回滚撤销——外层事务对「释放租约 +
 * 复位状态」这对跨存储操作只提供假原子性；PG 侧写入全部经 {@code transition} CAS 条件更新
 * 单语句自足并与并发执行互斥，恢复本身幂等可重跑（部分完成由下次启动收敛），故不设事务包装。
 * 原 PG 租约时代的整体事务壳已随之退役。</p>
 *
 * <p><b>时序承载（SmartLifecycle，phase = {@code Integer.MIN_VALUE}，2026-09 迭代）</b>：
 * 原 {@code ApplicationRunner} 形态在 <b>HTTP 端口已绑定、流量已可入站之后</b>才执行，
 * 启动恢复窗口内本实例刚开的新轮（租约值 = 本实例）会被第 1 步按「本实例 owner 索引」
 * 误判为崩溃残留而释放租约 + 复位状态击杀——Redis 租约键不携带获取时间，新旧租约在索引里
 * 无法区分。改为低 phase 的 {@link SmartLifecycle} 后，本恢复先于
 * {@code WebServerStartStopLifecycle}（phase = {@code Integer.MAX_VALUE - 1}，端口实际
 * 开始受理于此）与 {@code @Scheduled} 调度轮询（phase = {@code Integer.MAX_VALUE}）完成，
 * 「恢复与自身入站流量的毫秒级竞态」窗口即被时序封死。{@code start()} 抛异常将使
 * context refresh 失败（进程启动失败），与 runner 时代 fail-fast 语义一致：恢复未完成
 * 不放行流量。</p>
 */
@Component
public class StartupRecoveryLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(StartupRecoveryLifecycle.class);

    /** 必须早于 web 服务器（phase = {@code Integer.MAX_VALUE - 1}）与调度器绑定，取最低 phase。 */
    private static final int RECOVERY_PHASE = Integer.MIN_VALUE;

    /** 运行时全局配置（启动恢复开关）：配置类直注，不为其增设端口包装（迭代裁决 2026-09-13） */
    @Resource
    private AgentRuntimeProperties runtimeProperties;
    @Resource
    private AgentSessionRepository sessionRepository;
    @Resource
    private CoordinationLeaseService coordinationLeaseService;

    /** 生命周期运行态：恢复为一次性动作，成功 start 后保持 true 直至 context 关闭。 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Override
    public void start() {
        recoverOnce();
        running.set(true);
    }

    @Override
    public void stop() {
        // 恢复无运行态资源需要拆除（一次性动作），仅翻转生命周期标记
        running.set(false);
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return RECOVERY_PHASE;
    }

    /**
     * 启动恢复一次性入口：lifecycle 自动触发；集成测试在关闭恢复的上下文里显式打开
     * 开关后手动补跑一次（{@code start()} 与手动调用共用本方法，幂等）。
     */
    public void recoverOnce() {
        if (!runtimeProperties.isStartupRecoveryEnabled()) {
            log.info("启动恢复已禁用，跳过残留租约释放与孤儿执行复位");
            return;
        }
        // 1) 释放本实例 owner 索引中残留的 turn 租约（过期租约回收已随 Redis 化退役：
        //    租约 key 由服务端 TTL 自过期，无需清扫）
        int released = releaseOwnResidualTurnLeases();
        // 2) 相位选择性复位孤儿执行（无任何存活实例持租约者）
        int reset = resetOrphanExecutions();
        log.info("启动恢复完成: 释放本实例残留租约={}, 孤儿执行复位={}", released, reset);
    }

    /**
     * 释放<b>本实例</b> owner 索引中的全部 turn 租约（owner-scoped：已被他实例接管的租约
     * 不误摘），返回释放条数。
     */
    private int releaseOwnResidualTurnLeases() {
        List<String> turnKeys = coordinationLeaseService.listOwnActiveTurnKeys();
        int released = 0;
        for (String turnKey : turnKeys) {
            String sessionId = CoordLeaseType.TURN.bizIdFromKey(turnKey);
            coordinationLeaseService.releaseTurnLease(sessionId);
            released++;
        }
        return released;
    }

    /**
     * 相位选择性复位孤儿执行：候选来自「对外 running/rescheduling 且内部相位 running/cancelling」
     * 的会话（{@code awaiting_confirmation} 不在候选内），逐条以
     * {@link CoordinationLeaseService#hasActiveTurnLease} 排除任何存活实例持有的在跑会话后，
     * 经 {@link Transition#ABANDON_ORPHAN_EXECUTION} 原子 CAS 复位 {@code idle / idle}
     * （相位守卫在 SQL 层二次兜底，复位不补发事件）。
     *
     * @return 实际复位的会话数
     */
    private int resetOrphanExecutions() {
        List<String> candidates = sessionRepository.findActiveExecutionSessionIds();
        int reset = 0;
        for (String sessionId : candidates) {
            if (coordinationLeaseService.hasActiveTurnLease(sessionId)) {
                // 仍有存活实例持有执行权（他实例接管 / 启动窗口内新起）的会话不复位
                continue;
            }
            reset += sessionRepository.transition(sessionId, Transition.ABANDON_ORPHAN_EXECUTION);
        }
        return reset;
    }
}