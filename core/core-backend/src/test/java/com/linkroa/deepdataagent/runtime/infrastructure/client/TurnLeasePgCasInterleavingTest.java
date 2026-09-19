package com.linkroa.deepdataagent.runtime.infrastructure.client;

import com.linkroa.deepdataagent.runtime.application.port.CoordinationLeaseStore;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSession;
import com.linkroa.deepdataagent.runtime.domain.model.Transition;
import com.linkroa.deepdataagent.runtime.domain.model.enums.CoordLeaseType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PG 状态 CAS 与 Redis 租约交错集成测试（move-coordination-leases-to-redis tasks 2.4 与
 * tasks 2.5 的 HITL 段，真连 Redis + PostgreSQL）。
 *
 * <p>复现开跑时序（design D4）：<b>turn 租约 NX 先于 PG {@code BEGIN_TURN} CAS</b>，CAS 失败 /
 * 异常经 owner-scoped 释放归还租约；互斥权威仍落在 DB CAS（Redis 重启丢锁只造成误杀，不造成双跑）。</p>
 */
class TurnLeasePgCasInterleavingTest extends RedisFullContextTestSupport {

    private static final String OWNER_A = "it-instance-a";
    private static final String OWNER_B = "it-instance-b";

    /** 领域租约键（turn:session:*），与生产侧同一派生入口。 */
    private static String turnKey(String sessionId) {
        return CoordLeaseType.TURN.keyOf(sessionId);
    }

    @Test
    void should_runExactlyOneTurn_when_leaseThenCas_given_twoInstancesRacingSameSession() throws Exception {
        // given
        String sessionId = newSession();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger turnsStarted = new AtomicInteger();

        // when：两实例按 D4 时序（Redis NX → PG BEGIN_TURN CAS）并发开跑同一会话
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            for (String owner : List.of(OWNER_A, OWNER_B)) {
                pool.submit(() -> {
                    ready.countDown();
                    awaitQuietly(start);
                    if (startTurn(owner, sessionId)) {
                        turnsStarted.incrementAndGet();
                    }
                });
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS));
        }

        // then：双 turn 无双跑——租约与 DB CAS 合起来只放行一轮，会话落在 processing
        assertEquals(1, turnsStarted.get(), "同一会话并发开跑必须只有一轮进入执行");
        assertEquals(1, countSessionsIn(sessionId, "processing"));
        assertEquals(1, countTurnKeyOwners(sessionId), "同一 turn key 全库只应有唯一持有者");
    }

    @Test
    void should_releaseLease_when_startTurn_given_casFailsAfterNxAcquired() {
        // given：会话已被占（非 idle），但租约空闲——NX 会成功、CAS 必然失败
        String sessionId = newSession();
        assertTrue(sessionRepository.transition(sessionId, Transition.BEGIN_TURN) > 0);

        // when：模拟 executeRound 时序（NX 成功 → CAS 失败 → finally owner 释放）
        assertTrue(leaseStore.tryAcquire(CoordLeaseType.TURN, turnKey(sessionId),
                OWNER_A, Duration.ofMinutes(10)));
        int casRows = transaction().execute(status ->
                sessionRepository.transition(sessionId, Transition.BEGIN_TURN));
        if (casRows <= 0) {
            leaseStore.releaseOwned(CoordLeaseType.TURN, turnKey(sessionId), OWNER_A);
        }

        // then：CAS 失败即归还，租约不悬挂（免等 TTL）
        assertEquals(0, casRows, "非 idle 会话的 BEGIN_TURN CAS 必须 0 行");
        assertFalse(leaseStore.findActive(CoordLeaseType.TURN, turnKey(sessionId)),
                "CAS 失败后租约应已被 owner-scoped 释放");
    }

    @Test
    void should_requireReAcquire_when_hitlSuspendThenResume_given_turnLeaseLifecycle() {
        // given：一轮开跑（NX + CAS）
        String sessionId = newSession();
        assertTrue(startTurn(OWNER_A, sessionId));

        // when：HITL 挂起——CAS 到 waiting_confirmation 并立即释放 turn key（design D5④）
        assertTrue(transaction().execute(status ->
                sessionRepository.transition(sessionId, Transition.PHASE_AWAIT)) > 0);
        assertTrue(leaseStore.releaseOwned(CoordLeaseType.TURN, turnKey(sessionId), OWNER_A));

        // then①：挂起期间无租约悬挂，其他实例可抢占
        assertFalse(leaseStore.findActive(CoordLeaseType.TURN, turnKey(sessionId)));
        assertTrue(leaseStore.tryAcquire(CoordLeaseType.TURN, turnKey(sessionId),
                OWNER_B, Duration.ofMinutes(10)), "确认领取须重新 NX");

        // then②：领取事务内 CAS 恢复 processing（waiting_confirmation → processing）
        assertTrue(transaction().execute(status ->
                sessionRepository.transition(sessionId, Transition.PHASE_RESUME)) > 0);
        assertTrue(leaseStore.releaseOwned(CoordLeaseType.TURN, turnKey(sessionId), OWNER_B));
    }

    /**
     * 按 design D4 的开跑时序抢占一轮执行权：先 Redis NX，后 PG CAS；CAS 失败归还租约。
     *
     * @return true=本轮开跑成功
     */
    private boolean startTurn(String owner, String sessionId) {
        if (!leaseStore.tryAcquire(CoordLeaseType.TURN, turnKey(sessionId),
                owner, Duration.ofMinutes(10))) {
            return false;
        }
        Integer rows = transaction().execute(status ->
                sessionRepository.transition(sessionId, Transition.BEGIN_TURN));
        if (rows == null || rows <= 0) {
            leaseStore.releaseOwned(CoordLeaseType.TURN, turnKey(sessionId), owner);
            return false;
        }
        return true;
    }

    /** 新建 idle 会话（全量上下文不随用例回滚，登记后物理清理）。 */
    private String newSession() {
        AgentSession session = sessionRepository.save(
                AgentSession.create("it-user", "it-agent", "1", "{}", "集成测试交错会话"));
        trackSession(session.sessionId());
        return session.sessionId();
    }

    private int countSessionsIn(String sessionId, String status) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM agent_session WHERE session_id = ? AND status = ? AND is_deleted = 0",
                Integer.class, sessionId, status);
        return count == null ? 0 : count;
    }

    /** turn key 持有者数（Redis 单 key 语义下恒为 0 或 1，用于替代旧「表行数」零写入断言）。 */
    private int countTurnKeyOwners(String sessionId) {
        String owner = turnLeaseOwner(sessionId);
        return owner == null ? 0 : 1;
    }

    /** 装配交叉断言：端口实现唯一且为 Redis 实现（PR-C4 起 PG 实现与开关已下线，双写通道不可能复活）。 */
    @Test
    void should_wireOnlyRedisStore_when_coordinationLease_given_fullContext() {
        // given / when
        Map<String, CoordinationLeaseStore> stores =
                applicationContext.getBeansOfType(CoordinationLeaseStore.class);

        // then
        assertEquals(1, stores.size(), "端口实现必须唯一，实际: " + stores.keySet());
        assertTrue(stores.values().iterator().next() instanceof RedisCoordLeaseStore,
                "协调租约必须且只能装配 Redis 实现，实际: " + stores.keySet());
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
