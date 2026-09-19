package com.linkroa.deepdataagent.runtime.infrastructure.client;

import com.linkroa.deepdataagent.runtime.application.service.StartupRecoveryLifecycle;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSession;
import com.linkroa.deepdataagent.runtime.domain.model.Transition;
import com.linkroa.deepdataagent.runtime.domain.model.enums.CoordLeaseType;
import com.linkroa.deepdataagent.runtime.infrastructure.config.AgentRuntimeProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 启动恢复读面改由 owner set 承载的集成测试（move-coordination-leases-to-redis tasks 3.1 / design D6，
 * 真连 Redis + PostgreSQL）。
 *
 * <p>验证点（租约存储已切 Redis 后，恢复语义与 PG 时代逐条等价）：</p>
 * <ul>
 *   <li><b>本实例残留</b>：owner set 枚举出的残留 turn 租约 → 释放租约 + 会话
 *       {@code processing → idle}（F13 只回收本实例槽位）；</li>
 *   <li><b>他实例在跑</b>：租约由其他实例持有时既不释放也不复位会话；</li>
 *   <li><b>过期残留成员</b>：索引成员对应的 key 已 TTL 过期 → 扫描时惰性 SREM；</li>
 *   <li><b>durable 等待</b>：{@code waiting_confirmation} 仅释放租约、状态不动。</li>
 * </ul>
 *
 * <p>基类关闭了启动恢复（避免回写本机残留会话），故本用例显式打开开关并手工触发一次
 * {@link StartupRecoveryLifecycle#recoverOnce}（lifecycle 自动触发在低 phase 早于测试执行，
 * 开关关闭下已空跑过，恢复每次读取开关故补跑生效），用例后复原开关。</p>
 */
class StartupRecoveryRedisLeaseTest extends RedisFullContextTestSupport {

    private static final String OTHER_INSTANCE = "it-instance-other";

    /** 打开启动恢复开关跑一次恢复，跑完复原（开关由配置属性 bean 承载，恢复器每次读取）。 */
    private void runRecovery() {
        AgentRuntimeProperties properties = applicationContext.getBean(AgentRuntimeProperties.class);
        StartupRecoveryLifecycle lifecycle = applicationContext.getBean(StartupRecoveryLifecycle.class);
        boolean previous = properties.isStartupRecoveryEnabled();
        properties.setStartupRecoveryEnabled(true);
        try {
            lifecycle.recoverOnce();
        } finally {
            properties.setStartupRecoveryEnabled(previous);
        }
    }

    @Test
    void should_releaseLeaseAndResetSession_when_startupRecovery_given_residualOwnLeaseOnProcessingSession() {
        // given：本实例残留一轮崩溃前未释放的执行权（租约 + 索引成员俱在，会话停在 processing）
        String sessionId = newSession();
        assertTrue(leaseService.tryAcquireTurnLease(sessionId));
        assertTrue(sessionRepository.transition(sessionId, Transition.BEGIN_TURN) > 0);
        assertEquals(1, countSessionsIn(sessionId, "processing"));
        assertTrue(ownerIndexContains(leaseService.instanceId(), sessionId), "抢占应同写 owner 二级索引");

        // when
        runRecovery();

        // then：租约归还 + 索引成员摘除 + 会话复位 idle（执行现场已随进程消失，轨迹由事件流回放）
        assertFalse(leaseService.hasActiveTurnLease(sessionId), "残留租约应已被释放，免等 TTL");
        assertFalse(ownerIndexContains(leaseService.instanceId(), sessionId), "释放应同写 SREM");
        assertEquals(1, countSessionsIn(sessionId, "idle"), "processing 会话应被复位 idle");
    }

    @Test
    void should_notTouchSession_when_startupRecovery_given_leaseHeldByOtherInstance() {
        // given：会话在跑，但执行权由<b>其他实例</b>持有（滚动发布期间的存活实例）
        String sessionId = newSession();
        assertTrue(leaseStore.tryAcquire(CoordLeaseType.TURN, CoordLeaseType.TURN.keyOf(sessionId),
                OTHER_INSTANCE, Duration.ofMinutes(10)));
        assertTrue(sessionRepository.transition(sessionId, Transition.BEGIN_TURN) > 0);

        // when
        runRecovery();

        // then（F13：既不摘他实例租约，也不兜底复位其正在执行的会话）
        assertTrue(leaseService.hasActiveTurnLease(sessionId), "他实例租约 MUST NOT 被本实例回收");
        assertEquals(OTHER_INSTANCE, turnLeaseOwner(sessionId));
        assertEquals(1, countSessionsIn(sessionId, "processing"), "他实例在跑的会话不得被复位");
    }

    @Test
    void should_pruneExpiredMemberQuietly_when_startupRecovery_given_staleOwnerIndexMember() {
        // given：索引成员对应的租约 key 已 TTL 过期（key 短 TTL 自然失效，成员未随动摘除）
        String sessionId = newSession();
        String owner = leaseService.instanceId();
        assertTrue(leaseStore.tryAcquire(CoordLeaseType.TURN, CoordLeaseType.TURN.keyOf(sessionId),
                owner, Duration.ofMillis(300)));
        assertTrue(ownerIndexContains(owner, sessionId));
        waitUntilTurnKeyGone(sessionId);

        // when
        runRecovery();

        // then：扫描即对账——过期成员被惰性 SREM，会话（idle）不受影响
        assertFalse(ownerIndexContains(owner, sessionId), "过期成员应在扫描时被惰性清理");
        assertEquals(1, countSessionsIn(sessionId, "idle"));
    }

    @Test
    void should_keepWaitingStatus_when_startupRecovery_given_residualOwnLeaseOnWaitingSession() {
        // given：durable HITL 驻留态——本实例残留租约，会话停在 waiting_confirmation
        String sessionId = newSession();
        assertTrue(leaseService.tryAcquireTurnLease(sessionId));
        assertTrue(sessionRepository.transition(sessionId, Transition.BEGIN_TURN) > 0);
        assertTrue(sessionRepository.transition(sessionId, Transition.PHASE_AWAIT) > 0);

        // when
        runRecovery();

        // then：只归还执行权，等待事实存事件账本，状态不作废（确认 / 拒绝可重启后续跑）
        assertFalse(leaseService.hasActiveTurnLease(sessionId));
        assertEquals(1, countSessionsIn(sessionId, "waiting_confirmation"),
                "waiting_confirmation 为 durable 等待驻留态，重启不复位");
    }

    /** 新建 idle 会话（全量上下文不随用例回滚，登记后由基类物理清理）。 */
    private String newSession() {
        AgentSession session = sessionRepository.save(
                AgentSession.create("it-user", "it-agent", "1", "{}", "集成测试恢复会话"));
        trackSession(session.sessionId());
        return session.sessionId();
    }

    private int countSessionsIn(String sessionId, String status) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM agent_session WHERE session_id = ? AND status = ? AND is_deleted = 0",
                Integer.class, sessionId, status);
        return count == null ? 0 : count;
    }

    /** 等待 turn key 因服务端 TTL 自然过期（最长 3s）。 */
    private void waitUntilTurnKeyGone(String sessionId) {
        long deadline = System.currentTimeMillis() + 3_000L;
        while (System.currentTimeMillis() < deadline) {
            if (!leaseService.hasActiveTurnLease(sessionId)) {
                return;
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        assertFalse(leaseService.hasActiveTurnLease(sessionId), "turn key 未在预期时间内过期");
    }
}
