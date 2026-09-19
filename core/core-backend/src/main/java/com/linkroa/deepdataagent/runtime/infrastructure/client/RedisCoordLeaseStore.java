package com.linkroa.deepdataagent.runtime.infrastructure.client;

import com.linkroa.deepdataagent.runtime.application.port.CoordinationLeaseStore;
import com.linkroa.deepdataagent.runtime.domain.model.enums.CoordLeaseType;
import com.linkroa.deepdataagent.runtime.infrastructure.config.CoordLeaseKeys;
import com.linkroa.deepdataagent.runtime.infrastructure.config.CoordLeaseLuaScripts;
import jakarta.annotation.Resource;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 协调租约存储的 Redis 实现（move-coordination-leases-to-redis D1/D2）——PR-C4 起为<b>唯一</b>实现
 * （PG 侧实现与「二选一」装配开关随协调租约表一并下线，回滚只能整版本回退）。
 *
 * <p>原子性全部由 Redis 单脚本内完成（等价原 PG 单语句 CAS），经 {@code RScript} 执行
 * {@code resources/lua/runtime/} 下的五段 owner-scoped Lua。Redisson 的 {@code eval} 内部
 * 以脚本 SHA1 走 {@code EVALSHA}、命中 {@code NOSCRIPT} 时自动回源 {@code EVAL}，故无需自行维护
 * 脚本缓存。<b>禁用 RLock / getLock</b>——其线程绑定身份与本系统跨线程续约 / 释放模型冲突。</p>
 *
 * <p><b>与端口的二分类约定（design D5）</b>：Lua 返回 0 视为「确证失权」→ 返回 {@code false}；
 * 连接 / 命令异常由 Redisson 抛出运行时异常向上传播（调用方据此区分瞬断容忍），
 * 本实现 MUST NOT 吞异常返回 {@code false}。</p>
 *
 * <p>通用四方法仅承载 turn 租约（fire lease 无续约、无 owner 二级索引、TTL 为语义常量），
 * 非 {@link CoordLeaseType#TURN} 入参快速失败。租约键（{@code turn:session:<sessionId>}）的派生与
 * 反解属领域规则（{@link CoordLeaseType#keyOf} / {@link CoordLeaseType#bizIdFromKey}），
 * 本类据领域键再映射为 Redis 物理键布局（{@link CoordLeaseKeys}）。</p>
 */
@Component
public class RedisCoordLeaseStore implements CoordinationLeaseStore {

    /** fire lease 防重窗口（fire lease 语义常量，与 {@code CoordinationLeaseService.FIRE_LEASE_TTL} 同值）。 */
    private static final Duration FIRE_LEASE_TTL = Duration.ofSeconds(30);

    /** Lua 脚本返回值：1=生效，0=未生效（确证失权 / 并发拒绝 / 已易主）。 */
    private static final long SCRIPT_APPLIED = 1L;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private CoordLeaseLuaScripts scripts;

    @Override
    public boolean tryAcquire(CoordLeaseType leaseType, String leaseKey, String owner, Duration ttl) {
        requireTurnLease(leaseType, "tryAcquire");
        String sessionId = CoordLeaseType.TURN.bizIdFromKey(leaseKey);
        return isApplied(eval(scripts.turnAcquire(),
                List.of(CoordLeaseKeys.turn(sessionId), CoordLeaseKeys.turnOwners(owner)),
                owner, ttl.toMillis(), sessionId));
    }

    @Override
    public boolean renew(CoordLeaseType leaseType, String leaseKey, String owner, Duration ttl) {
        requireTurnLease(leaseType, "renew");
        return isApplied(eval(scripts.turnRenew(),
                List.of(CoordLeaseKeys.turn(CoordLeaseType.TURN.bizIdFromKey(leaseKey))),
                owner, ttl.toMillis()));
    }

    @Override
    public boolean releaseOwned(CoordLeaseType leaseType, String leaseKey, String owner) {
        requireTurnLease(leaseType, "releaseOwned");
        String sessionId = CoordLeaseType.TURN.bizIdFromKey(leaseKey);
        return isApplied(eval(scripts.turnReleaseOwned(),
                List.of(CoordLeaseKeys.turn(sessionId), CoordLeaseKeys.turnOwners(owner)),
                owner, sessionId));
    }

    @Override
    public boolean findActive(CoordLeaseType leaseType, String leaseKey) {
        requireTurnLease(leaseType, "findActive");
        // 服务端 TTL 自过期：key 存在即有效租约（无需比对过期时间）
        return redissonClient.getBucket(CoordLeaseKeys.turn(CoordLeaseType.TURN.bizIdFromKey(leaseKey)),
                StringCodec.INSTANCE).isExists();
    }

    @Override
    public List<String> listOwnedTurnKeys(String owner) {
        String ownersKey = CoordLeaseKeys.turnOwners(owner);
        Set<String> sessionIds = redissonClient.<String>getSet(ownersKey, StringCodec.INSTANCE).readAll();
        List<String> ownedKeys = new ArrayList<>(sessionIds.size());
        for (String sessionId : sessionIds) {
            String currentOwner = redissonClient.<String>getBucket(CoordLeaseKeys.turn(sessionId),
                    StringCodec.INSTANCE).get();
            if (!owner.equals(currentOwner)) {
                // 惰性对账：租约已 TTL 过期或被接管 → 清理本实例索引残留成员（design D6）
                redissonClient.getSet(ownersKey, StringCodec.INSTANCE).remove(sessionId);
                continue;
            }
            ownedKeys.add(CoordLeaseType.TURN.keyOf(sessionId));
        }
        return ownedKeys;
    }

    @Override
    public boolean fireTryAcquire(String deploymentId, String owner) {
        return isApplied(eval(scripts.fireAcquire(),
                List.of(CoordLeaseKeys.fire(deploymentId)), owner, FIRE_LEASE_TTL.toMillis()));
    }

    @Override
    public boolean fireReleaseOwned(String deploymentId, String owner) {
        return isApplied(eval(scripts.fireReleaseOwned(),
                List.of(CoordLeaseKeys.fire(deploymentId)), owner));
    }

    /**
     * 执行 Lua（Redisson 内部 EVALSHA 优先、NOSCRIPT 回源 EVAL）。READ_WRITE 模式：五段脚本均含写命令。
     * <p>返回类型 {@code LONG}：五段脚本一律 return 1 / 0。</p>
     */
    private Long eval(String script, List<Object> keys, Object... values) {
        return redissonClient.getScript(StringCodec.INSTANCE)
                .eval(RScript.Mode.READ_WRITE, script, RScript.ReturnType.LONG, keys, values);
    }

    private static boolean isApplied(Long result) {
        return result != null && result == SCRIPT_APPLIED;
    }

    /** fire 租约不经通用通道（防止绕过 fire 语义常量与无索引约定）。 */
    private static void requireTurnLease(CoordLeaseType leaseType, String method) {
        if (leaseType != CoordLeaseType.TURN) {
            throw new IllegalArgumentException("CoordinationLeaseStore." + method
                    + " 仅承载 TURN 租约，fire lease 请经 fireTryAcquire / fireReleaseOwned，实际类型: " + leaseType);
        }
    }
}
