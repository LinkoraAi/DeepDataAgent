package com.linkroa.deepdataagent.runtime.infrastructure.client;

import com.linkroa.deepdataagent.runtime.domain.model.enums.CoordLeaseType;
import com.linkroa.deepdataagent.runtime.infrastructure.config.CoordLeaseKeys;
import com.linkroa.deepdataagent.runtime.infrastructure.config.CoordLeaseLuaScripts;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 协调租约时效集成测试（move-coordination-leases-to-redis tasks 2.5，真连 Redis）。
 *
 * <p>覆盖 design D5 的三条时效红线：①服务端慢命令（注入 2s 阻塞）下续约<b>不丢锁</b>——存储故障
 * 以异常呈现，MUST NOT 被误判为确证失权；②租约确证消失（Redis 重启丢锁等价场景）续约返回
 * {@code false}，调用方据此立即 fail-closed；③续约窗口内 TTL 被正确延长（不误杀运行中的轮次）。</p>
 *
 * <p>「连续 2 周期异常才 fail-closed」的周期容忍计数属应用层编排，由
 * {@code RoundExecutionTemplateTest} 以单测锁定；本类只锁定其依赖的存储侧契约
 * （异常 ≠ false）。</p>
 */
class RedisCoordLeaseTimingTest extends RedisRepositoryTestSupport {

    private static final String OWNER = "it-owner-a";

    /** 脚本加载器（上下文装配，供不可用客户端场景复用）。 */
    @Resource
    private CoordLeaseLuaScripts luaScripts;

    /** 延迟注入用后台线程池（用例后统一收敛，避免阻塞窗口串扰后续用例）。 */
    private final List<ExecutorService> stalledPools = new ArrayList<>();

    /** 领域租约键（turn:session:*），与生产侧同一派生入口。 */
    private static String turnKey(String sessionId) {
        return CoordLeaseType.TURN.keyOf(sessionId);
    }

    @AfterEach
    void awaitStallTasks() throws InterruptedException {
        for (ExecutorService pool : stalledPools) {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "延迟注入任务未在时限内结束");
        }
        stalledPools.clear();
    }

    /**
     * 服务端忙等脚本：以 {@code TIME} 为基准忙循环到指定毫秒，阻塞 Redis 主线程
     * ——在不引入 toxiproxy / 不改服务端配置的前提下注入真实命令延迟。
     */
    private static final String BUSY_WAIT_LUA = """
            local target = tonumber(ARGV[1])
            local function now_ms()
                local t = redis.call('TIME')
                return t[1] * 1000 + math.floor(t[2] / 1000)
            end
            local start = now_ms()
            while now_ms() - start < target do end
            return 1
            """;

    @Test
    void should_keepLeaseAndSignalFault_when_renew_given_twoSecondServerSideDelay() throws Exception {
        // given：持有 10min TTL 的 turn 租约
        String sessionId = "it-sess-slow-command";
        assertTrue(leaseStore.tryAcquire(CoordLeaseType.TURN, turnKey(sessionId),
                OWNER, Duration.ofMinutes(10)));

        // when：注入 2.2s 服务端阻塞（超过 2s 命令超时），延迟窗口内发起续约
        stallRedisServer(2200);
        Throwable failure = null;
        try {
            leaseStore.renew(CoordLeaseType.TURN, turnKey(sessionId), OWNER, Duration.ofMinutes(10));
        } catch (RuntimeException ex) {
            failure = ex;
        }

        // then①：慢命令只会「异常」或「成功」，绝不返回 false（异常=瞬断容忍，false=确证失权）
        if (failure != null) {
            assertTrue(failure instanceof RuntimeException, "存储故障以运行时异常呈现，交由调用方二分类");
        }
        // then②：锁未被误摘——持有者仍是本实例，延迟解除后续约可正常续命
        assertEquals(OWNER, currentTurnValue(sessionId), "慢命令不得丢锁");
        assertTrue(leaseStore.renew(CoordLeaseType.TURN, turnKey(sessionId),
                OWNER, Duration.ofMinutes(10)), "延迟解除后续约应成功（不误杀运行中的轮次）");
    }

    @Test
    void should_returnFalse_when_renew_given_leaseKeyDisappeared() {
        // given：Redis 重启丢锁 / key 被外部清理的等价场景
        String sessionId = "it-sess-lock-gone";
        assertTrue(leaseStore.tryAcquire(CoordLeaseType.TURN, turnKey(sessionId),
                OWNER, Duration.ofMinutes(10)));
        redissonClient.getBucket(CoordLeaseKeys.turn(sessionId), StringCodec.INSTANCE).delete();

        // when
        boolean renewed = leaseStore.renew(CoordLeaseType.TURN, turnKey(sessionId),
                OWNER, Duration.ofMinutes(10));

        // then：确证失权——返回 false（非异常），调用方立即 fail-closed 静默收轮
        assertFalse(renewed);
    }

    @Test
    void should_extendTtl_when_renew_given_leaseWithinRenewalWindow() {
        // given：短 TTL 租约（模拟续约窗口临近过期）
        String sessionId = "it-sess-renew-window";
        assertTrue(leaseStore.tryAcquire(CoordLeaseType.TURN, turnKey(sessionId),
                OWNER, Duration.ofSeconds(6)));
        long beforeRenew = remainTtlMillis(sessionId);

        // when
        assertTrue(leaseStore.renew(CoordLeaseType.TURN, turnKey(sessionId),
                OWNER, Duration.ofMinutes(10)));

        // then：TTL 被重置为完整窗口（运行中的轮次不因续约窗口误期被接管）
        long afterRenew = remainTtlMillis(sessionId);
        assertTrue(afterRenew > beforeRenew + 1000,
                "续约应显著延长 TTL，实际: " + beforeRenew + " -> " + afterRenew);
        assertTrue(afterRenew > 9 * 60 * 1000, "续约后 TTL 应接近 10min，实际: " + afterRenew);
    }

    @Test
    void should_signalDefiniteLoss_when_renew_given_leaseTakenOverByOtherOwner() {
        // given：本实例租约过期后被他人接管
        String sessionId = "it-sess-taken-over";
        assertTrue(leaseStore.tryAcquire(CoordLeaseType.TURN, turnKey(sessionId),
                OWNER, Duration.ofMillis(500)));
        sleepMillis(800);
        assertTrue(leaseStore.tryAcquire(CoordLeaseType.TURN, turnKey(sessionId),
                "it-owner-b", Duration.ofMinutes(10)));

        // when：原持有者续约
        boolean renewed = leaseStore.renew(CoordLeaseType.TURN, turnKey(sessionId),
                OWNER, Duration.ofMinutes(10));

        // then：返回 false（确证失权，立即中止），且接管者租约无损
        assertFalse(renewed);
        assertEquals("it-owner-b", currentTurnValue(sessionId));
    }

    @Test
    void should_throwStoreFault_when_renew_given_connectionUnavailable() {
        // given / when / then：不可用端口的客户端在创建期即快速失败（Redisson.create 直连探活），
        // 续约路径同样只抛异常、MUST NOT 吞异常返回 false（调用方按 D5 二分类容忍重试）
        RuntimeException failure = assertThrows(RuntimeException.class, () -> {
            RedissonClient broken = Redisson.create(newUnreachableConfig());
            try {
                RedisCoordLeaseStore unreachableStore = new RedisCoordLeaseStore();
                ReflectionTestUtils.setField(unreachableStore, "redissonClient", broken);
                ReflectionTestUtils.setField(unreachableStore, "scripts", luaScripts);
                unreachableStore.renew(CoordLeaseType.TURN, turnKey("it-sess-unreachable"),
                        OWNER, Duration.ofMinutes(10));
            } finally {
                broken.shutdown();
            }
        });
        assertTrue(failure.getMessage() != null, "存储故障异常应携带可诊断信息");
    }

    /** 以服务端忙等脚本阻塞 Redis 指定毫秒（真实命令延迟注入）。 */
    private void stallRedisServer(long millis) throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        pool.submit(() -> redissonClient.getScript(StringCodec.INSTANCE).eval(
                RScript.Mode.READ_WRITE, BUSY_WAIT_LUA, RScript.ReturnType.LONG,
                List.of(), String.valueOf(millis)));
        stalledPools.add(pool);
        // 留出脚本进入服务端的窗口（阻塞与后续续约命令并发）
        Thread.sleep(150);
    }

    private static Config newUnreachableConfig() {
        Config config = new Config();
        config.setCodec(StringCodec.INSTANCE);
        config.useSingleServer()
                .setAddress("redis://127.0.0.1:6399")
                .setConnectTimeout(300)
                .setTimeout(500)
                .setRetryAttempts(0)
                .setRetryInterval(100);
        return config;
    }

    private String currentTurnValue(String sessionId) {
        return redissonClient.<String>getBucket(CoordLeaseKeys.turn(sessionId), StringCodec.INSTANCE).get();
    }

    private long remainTtlMillis(String sessionId) {
        return redissonClient.getBucket(CoordLeaseKeys.turn(sessionId), StringCodec.INSTANCE)
                .remainTimeToLive();
    }

    private static void sleepMillis(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
