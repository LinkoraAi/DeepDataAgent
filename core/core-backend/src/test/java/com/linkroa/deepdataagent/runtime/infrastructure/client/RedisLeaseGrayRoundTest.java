package com.linkroa.deepdataagent.runtime.infrastructure.client;

import com.linkroa.deepdataagent.runtime.domain.model.AgentSession;
import com.linkroa.deepdataagent.runtime.domain.model.Transition;
import com.linkroa.deepdataagent.runtime.domain.model.enums.CoordLeaseType;
import com.linkroa.deepdataagent.runtime.infrastructure.config.CoordLeaseKeys;
import org.junit.jupiter.api.Test;
import org.redisson.client.codec.StringCodec;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 测试环境灰度冒烟：一轮完整 turn 的协调生命周期（move-coordination-leases-to-redis tasks 2.6）。
 *
 * <p>以真实生产 Bean 图（{@code CoordinationLeaseService} → {@code RedisCoordLeaseStore} →
 * Redisson → Redis，配合真实 PG 会话状态机）跑完一轮 turn 的<b>协调生命周期全链路</b>：
 * 抢占 → 续约 → 恢复枚举 → 释放 → 重新抢占，并覆盖调度 fire lease 的窗口防重与
 * owner-scoped 释放；端口实现唯一性（旧 PG 双写通道未复活）由
 * {@code TurnLeasePgCasInterleavingTest} 的装配交叉断言锁定。</p>
 *
 * <p>带模型调用的端到端一轮（真实供应商凭证 + Docker 沙箱）不属自动化范围：其协调面已由本类
 * 与 {@code TurnLeasePgCasInterleavingTest} 覆盖，剩余差异仅在 AgentScope 执行体本身。</p>
 */
class RedisLeaseGrayRoundTest extends RedisFullContextTestSupport {

    /** 领域租约键（turn:session:*），与生产侧同一派生入口。 */
    private static String turnKey(String sessionId) {
        return CoordLeaseType.TURN.keyOf(sessionId);
    }

    @Test
    void should_completeTurnLeaseLifecycle_when_grayRound_given_redisStoreAssembled() {
        // given：真实服务 Bean（实例标识由 APP_INSTANCE_ID / 主机名回落）
        String sessionId = newSession();
        String owner = leaseService.instanceId();

        // when：开跑抢占（D4：NX 先于 PG CAS）
        assertTrue(leaseService.tryAcquireTurnLease(sessionId));
        assertTrue(sessionRepository.transition(sessionId, Transition.BEGIN_TURN) > 0);

        // then①：租约落 Redis（值=实例标识、owner 索引同写），会话进入 processing
        assertEquals(owner, turnLeaseOwner(sessionId));
        assertTrue(ownerIndexContains(owner, sessionId), "acquire 应同写 owner 二级索引");
        assertEquals(1, countSessionsIn(sessionId, "processing"));

        // when：轮内续约（TTL/3 周期由 RoundExecutionTemplate 调度，此处验证续约生效面）
        assertTrue(leaseService.renewTurnLease(sessionId));

        // then②：TTL 重置为 10min 量级，且本实例恢复枚举可见
        assertTrue(turnLeaseTtlMillis(sessionId) > 9 * 60 * 1000,
                "续约后 TTL 应接近 10min，实际: " + turnLeaseTtlMillis(sessionId));
        List<String> ownKeys = leaseService.listOwnActiveTurnKeys();
        assertTrue(ownKeys.contains(turnKey(sessionId)),
                "恢复枚举应包含在跑租约，实际: " + ownKeys);
        assertTrue(leaseService.hasActiveTurnLease(sessionId));

        // when：终态释放（轮终局唯一出口）
        leaseService.releaseTurnLease(sessionId);
        assertTrue(sessionRepository.transition(sessionId, Transition.FINISH_TURN) > 0);

        // then③：key 与索引同步摘除，会话回 idle
        assertFalse(leaseService.hasActiveTurnLease(sessionId));
        assertFalse(ownerIndexContains(owner, sessionId), "release 应同摘 owner 索引成员");
        assertEquals(1, countSessionsIn(sessionId, "idle"));

        // when：下一轮重新抢占（释放后键位可复用）
        assertTrue(leaseService.tryAcquireTurnLease(sessionId));
        assertTrue(leaseService.renewTurnLease(sessionId));

        // then④：反例——租约在途时他实例既抢不到也续不动（fail-closed 判据不失真）
        assertFalse(leaseStore.tryAcquire(CoordLeaseType.TURN, turnKey(sessionId),
                "it-instance-late", Duration.ofMinutes(10)), "在途租约不得被他实例抢占");
        assertFalse(leaseStore.renew(CoordLeaseType.TURN, turnKey(sessionId),
                "it-instance-late", Duration.ofMinutes(10)), "非持有者续约必须确证失败");
        assertEquals(owner, turnLeaseOwner(sessionId), "他实例的尝试不得改动持有者");
        leaseService.releaseTurnLease(sessionId);
    }

    @Test
    void should_deduplicateSchedulerFires_when_fireLease_given_windowNotElapsed() {
        // given
        String deploymentId = "it-dep-gray";

        // when：同窗口两次触发
        boolean first = leaseService.tryAcquireFireLease(deploymentId);
        boolean second = leaseService.tryAcquireFireLease(deploymentId);

        // then：窗口内单赢家（fire lease 无 owner 索引，只落 Redis），值为首次触发的持有者实例
        assertTrue(first);
        assertFalse(second);
        assertEquals(leaseService.instanceId(), fireLeaseValue(deploymentId),
                "fire lease 值应为首个触发的实例标识");

        // when：触发流程结束显式释放
        leaseService.releaseFireLease(deploymentId);

        // then：窗口提前结束，后续触发可再抢占
        assertTrue(leaseService.tryAcquireFireLease(deploymentId));
        leaseService.releaseFireLease(deploymentId);
    }

    /** 新建 idle 会话（全量上下文不随用例回滚，登记后物理清理）。 */
    private String newSession() {
        AgentSession session = sessionRepository.save(
                AgentSession.create("it-user", "it-agent", "1", "{}", "集成测试灰度会话"));
        trackSession(session.sessionId());
        return session.sessionId();
    }

    private int countSessionsIn(String sessionId, String status) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM agent_session WHERE session_id = ? AND status = ? AND is_deleted = 0",
                Integer.class, sessionId, status);
        return count == null ? 0 : count;
    }

    /** fire key 当前值（持有者实例标识；fire lease 无 owner 二级索引）。 */
    private String fireLeaseValue(String deploymentId) {
        return redissonClient.<String>getBucket(CoordLeaseKeys.fire(deploymentId), StringCodec.INSTANCE).get();
    }
}
