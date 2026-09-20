package com.linkroa.deepdataagent.runtime.infrastructure.client;

import com.linkroa.deepdataagent.runtime.domain.model.enums.CoordLeaseType;
import com.linkroa.deepdataagent.runtime.infrastructure.config.CoordLeaseKeys;
import org.junit.jupiter.api.Test;
import org.redisson.client.codec.StringCodec;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 协调租约 Lua 语义集成测试（move-coordination-leases-to-redis tasks 2.2，真连 Redis）。
 *
 * <p>逐条验证五段 owner-scoped Lua 与原 PG CAS 的语义等价面：抢占单赢家、非持有者续约 / 释放
 * 确证失败、TTL 服务端自过期、迟到释放不误摘接管者（审查修复 F11）、fire lease 窗口防重。</p>
 *
 * <p><b>owner 二级索引侧不变量</b>（2026-09 原子性审查补充）：抢占脚本「SET NX 成功才 SADD」的
 * 同脚本原子面是启动恢复「按本实例槽位枚举崩溃残留」成立的前提；并发拒绝分支 MUST NOT 污染
 * 败者索引；迟到释放（F11）MUST NOT 误摘接管者索引成员。三向各有一条固化断言守护脚本回归。</p>
 */
class RedisCoordLeaseLuaTest extends RedisRepositoryTestSupport {

    /** 持有者实例标识（it- 前缀键由基类统一清理）。 */
    private static final String OWNER_A = "it-owner-a";
    private static final String OWNER_B = "it-owner-b";

    /** 领域租约键（turn:session:*），与生产侧同一派生入口。 */
    private static String turnKey(String sessionId) {
        return CoordLeaseType.TURN.keyOf(sessionId);
    }

    @Test
    void should_onlyOneWinner_when_tryAcquire_given_concurrentSameSession() throws Exception {
        // given
        String sessionId = "it-sess-race";
        int competitors = 8;
        CountDownLatch ready = new CountDownLatch(competitors);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger acquired = new AtomicInteger();

        // when
        try (ExecutorService pool = Executors.newFixedThreadPool(competitors)) {
            for (int i = 0; i < competitors; i++) {
                String owner = "it-owner-" + i;
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        if (leaseStore.tryAcquire(CoordLeaseType.TURN, turnKey(sessionId),
                                owner, Duration.ofMinutes(10))) {
                            acquired.incrementAndGet();
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS), "竞争线程未就绪");
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "竞争未在时限内结束");
        }

        // then：并发下单赢家，且租约值为赢家 owner
        assertEquals(1, acquired.get(), "turn 租约抢占必须只产生一个赢家");
        assertTrue(leaseStore.findActive(CoordLeaseType.TURN, turnKey(sessionId)));
    }

    @Test
    void should_returnFalse_when_renew_given_ownerMismatch() {
        // given
        String sessionId = "it-sess-renew-mismatch";
        assertTrue(leaseStore.tryAcquire(CoordLeaseType.TURN, turnKey(sessionId),
                OWNER_A, Duration.ofMinutes(10)));

        // when：非持有者续约
        boolean renewedByOther = leaseStore.renew(CoordLeaseType.TURN, turnKey(sessionId),
                OWNER_B, Duration.ofMinutes(10));

        // then：确证失权（false 而非抛错），持有者租约不受影响
        assertFalse(renewedByOther);
        assertTrue(leaseStore.renew(CoordLeaseType.TURN, turnKey(sessionId),
                OWNER_A, Duration.ofMinutes(10)), "持有者续约应成功");
        assertEquals(OWNER_A, currentTurnValue(sessionId));
    }

    @Test
    void should_returnFalse_when_releaseOwned_given_ownerMismatch() {
        // given
        String sessionId = "it-sess-release-mismatch";
        assertTrue(leaseStore.tryAcquire(CoordLeaseType.TURN, turnKey(sessionId),
                OWNER_A, Duration.ofMinutes(10)));

        // when：非持有者释放
        boolean releasedByOther = leaseStore.releaseOwned(CoordLeaseType.TURN,
                turnKey(sessionId), OWNER_B);

        // then：不误摘持有者租约
        assertFalse(releasedByOther);
        assertTrue(leaseStore.findActive(CoordLeaseType.TURN, turnKey(sessionId)));
    }

    @Test
    void should_expireByKey_when_tryAcquire_given_ttlElapsed() {
        // given：短 TTL 便于观察服务端自过期
        String sessionId = "it-sess-ttl";

        // when
        assertTrue(leaseStore.tryAcquire(CoordLeaseType.TURN, turnKey(sessionId),
                OWNER_A, Duration.ofMillis(800)));
        assertTrue(leaseStore.findActive(CoordLeaseType.TURN, turnKey(sessionId)));
        sleepMillis(1200);

        // then：TTL 到点即失效（无 reaper 依赖），且可被他实例接管
        assertFalse(leaseStore.findActive(CoordLeaseType.TURN, turnKey(sessionId)),
                "Redis TTL 自过期后 key 不应存在");
        assertTrue(leaseStore.tryAcquire(CoordLeaseType.TURN, turnKey(sessionId),
                OWNER_B, Duration.ofMinutes(10)), "过期租约应可被接管");
    }

    @Test
    void should_keepTakeoverLock_when_releaseOwned_given_leaseTakenOverBeforeLateRelease() {
        // given：A 持锁 → 过期 → B 接管
        String sessionId = "it-sess-late-release";
        assertTrue(leaseStore.tryAcquire(CoordLeaseType.TURN, turnKey(sessionId),
                OWNER_A, Duration.ofMillis(600)));
        sleepMillis(900);
        assertTrue(leaseStore.tryAcquire(CoordLeaseType.TURN, turnKey(sessionId),
                OWNER_B, Duration.ofMinutes(10)), "接管应成功");

        // when：A 的迟到释放（F11 场景）
        boolean lateReleased = leaseStore.releaseOwned(CoordLeaseType.TURN,
                turnKey(sessionId), OWNER_A);

        // then：不摘新锁，接管者仍持权；索引侧 F11 同形——接管者索引不被误摘，
        // 败者自己的残留成员保留（SREM 在值匹配分支内），由启动恢复惰性对账收敛
        assertFalse(lateReleased);
        assertEquals(OWNER_B, currentTurnValue(sessionId));
        assertTrue(leaseStore.findActive(CoordLeaseType.TURN, turnKey(sessionId)));
        assertTrue(ownerIndexContains(OWNER_B, sessionId), "迟到释放 MUST NOT 误摘接管者 owner 索引");
        assertTrue(ownerIndexContains(OWNER_A, sessionId),
                "失权方的残留成员应保留（等惰性对账剔除，脚本不得越权清除他人进行中事实之外的成员）");
    }

    @Test
    void should_writeOwnerIndexAtomically_when_tryAcquire_given_setNxSucceeds() {
        // given
        String sessionId = "it-sess-index-acquire";

        // when
        assertTrue(leaseStore.tryAcquire(CoordLeaseType.TURN, turnKey(sessionId),
                OWNER_A, Duration.ofMinutes(10)));

        // then：抢占成功必须同脚本写入 owner 二级索引——若 SADD 被挪出脚本或挪出成功分支，
        // 崩溃残留将无法按本实例槽位枚举（启动恢复漏回收，只能等 10min TTL），此处固化
        assertEquals(OWNER_A, currentTurnValue(sessionId));
        assertTrue(ownerIndexContains(OWNER_A, sessionId), "SET NX 与 SADD 必须单脚本原子完成");
    }

    @Test
    void should_notPolluteLoserIndex_when_tryAcquire_given_leaseHeldByOther() {
        // given：A 已持有该会话租约
        String sessionId = "it-sess-index-reject";
        assertTrue(leaseStore.tryAcquire(CoordLeaseType.TURN, turnKey(sessionId),
                OWNER_A, Duration.ofMinutes(10)));

        // when：B 并发抢占被拒（SET NX 失败分支）
        assertFalse(leaseStore.tryAcquire(CoordLeaseType.TURN, turnKey(sessionId),
                OWNER_B, Duration.ofMinutes(10)));

        // then：败者索引 MUST NOT 出现成员（SADD 若被移出 if 分支即在此回归）——
        // 否则 B 的启动恢复会枚举到从未持有的会话，徒增跨槽位噪声
        assertFalse(ownerIndexContains(OWNER_B, sessionId), "并发拒绝不得污染败者 owner 索引");
        assertTrue(ownerIndexContains(OWNER_A, sessionId), "持有者索引不受败方抢占影响");
    }

    @Test
    void should_rejectSecondAcquire_when_fireTryAcquire_given_withinThirtySecondWindow() {
        // given
        String deploymentId = "it-dep-fire";

        // when
        boolean first = leaseStore.fireTryAcquire(deploymentId, OWNER_A);
        boolean second = leaseStore.fireTryAcquire(deploymentId, OWNER_B);

        // then：窗口内单赢家，TTL 为 fire 语义常量 30s
        assertTrue(first);
        assertFalse(second);
        long remainMillis = redissonClient.getBucket(CoordLeaseKeys.fire(deploymentId),
                StringCodec.INSTANCE).remainTimeToLive();
        assertTrue(remainMillis > 25_000 && remainMillis <= 30_000,
                "fire lease TTL 应为 30s 量级，实际剩余: " + remainMillis);
    }

    @Test
    void should_keepFireLease_when_fireReleaseOwned_given_ownerMismatch() {
        // given
        String deploymentId = "it-dep-fire-owner";
        assertTrue(leaseStore.fireTryAcquire(deploymentId, OWNER_A));

        // when：非持有者释放（fire 释放 MUST owner-scoped）
        boolean releasedByOther = leaseStore.fireReleaseOwned(deploymentId, OWNER_B);

        // then
        assertFalse(releasedByOther);
        assertEquals(OWNER_A, redissonClient.<String>getBucket(CoordLeaseKeys.fire(deploymentId),
                StringCodec.INSTANCE).get());
        assertTrue(leaseStore.fireReleaseOwned(deploymentId, OWNER_A), "持有者释放应生效");
        assertTrue(leaseStore.fireTryAcquire(deploymentId, OWNER_B), "释放后窗口内应可再次抢占");
    }

    @Test
    void should_returnFalse_when_renew_given_leaseNeverAcquired() {
        // given：从未获取过租约的会话
        String sessionId = "it-sess-never-acquired";

        // when
        boolean renewed = leaseStore.renew(CoordLeaseType.TURN, turnKey(sessionId),
                OWNER_A, Duration.ofMinutes(10));

        // then：不存在亦为确证失权（false，不抛异常）
        assertFalse(renewed);
    }

    /** owner 二级索引成员判定（物理键布局经 {@link CoordLeaseKeys}，与脚本 KEYS[2] 同源）。 */
    private boolean ownerIndexContains(String owner, String sessionId) {
        return redissonClient.getSet(CoordLeaseKeys.turnOwners(owner), StringCodec.INSTANCE)
                .contains(sessionId);
    }

    /** 读取 turn key 当前值（持有者标识）。 */
    private String currentTurnValue(String sessionId) {
        String value = redissonClient.<String>getBucket(CoordLeaseKeys.turn(sessionId),
                StringCodec.INSTANCE).get();
        assertNotNull(value, "turn key 应存在");
        return value;
    }

    private static void sleepMillis(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
