package com.linkroa.deepdataagent.runtime.application.service;

import com.linkroa.deepdataagent.runtime.application.port.CoordinationLeaseStore;
import com.linkroa.deepdataagent.runtime.domain.model.enums.CoordLeaseType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link CoordinationLeaseService} 单测：两类租约（turn / fire）经 {@link CoordinationLeaseStore}
 * 端口的编排（键形态、TTL、owner 条件、异常透传二分类语义）。
 */
@ExtendWith(MockitoExtension.class)
class CoordinationLeaseServiceTest {

    @Mock private CoordinationLeaseStore leaseStore;

    private CoordinationLeaseService service;

    @BeforeEach
    void setUp() {
        service = new CoordinationLeaseService();
        ReflectionTestUtils.setField(service, "leaseStore", leaseStore);
    }

    @Test
    void should_grantTurnLeaseWithTtl_when_tryAcquireTurnLease_given_storeAccepts() {
        // given
        when(leaseStore.tryAcquire(eq(CoordLeaseType.TURN), eq("turn:session:s-1"), any(), any()))
                .thenReturn(true);

        // when
        boolean acquired = service.tryAcquireTurnLease("s-1");

        // then（turn 键 + 本实例持有者 + turn TTL）
        assertTrue(acquired);
        ArgumentCaptor<String> ownerCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(leaseStore).tryAcquire(eq(CoordLeaseType.TURN), eq("turn:session:s-1"),
                ownerCaptor.capture(), ttlCaptor.capture());
        assertTrue(ownerCaptor.getValue().startsWith("instance-"));
        assertEquals(Duration.ofMinutes(10), ttlCaptor.getValue());
    }

    @Test
    void should_returnFalse_when_tryAcquireTurnLease_given_leaseHeld() {
        // given（会话已有未过期运行中的租约）
        when(leaseStore.tryAcquire(eq(CoordLeaseType.TURN), eq("turn:session:s-1"), any(), any()))
                .thenReturn(false);

        // when // then
        assertFalse(service.tryAcquireTurnLease("s-1"));
    }

    @Test
    void should_delegateOwnerAndTtl_when_renewTurnLease_given_leaseStillOwned() {
        // given
        when(leaseStore.renew(eq(CoordLeaseType.TURN), eq("turn:session:s-1"), any(), any())).thenReturn(true);

        // when
        boolean renewed = service.renewTurnLease("s-1");

        // then（续约携带本实例持有者标识 + turn TTL）
        assertTrue(renewed);
        ArgumentCaptor<String> ownerCaptor = ArgumentCaptor.forClass(String.class);
        verify(leaseStore).renew(eq(CoordLeaseType.TURN), eq("turn:session:s-1"),
                ownerCaptor.capture(), eq(Duration.ofMinutes(10)));
        assertEquals(service.instanceId(), ownerCaptor.getValue());
    }

    @Test
    void should_returnFalse_when_renewTurnLease_given_leaseTakenOverByOtherInstance() {
        // given（owner 收口：租约已易主，端口返回 false 即确证失权）
        when(leaseStore.renew(eq(CoordLeaseType.TURN), eq("turn:session:s-1"), any(), any())).thenReturn(false);

        // when // then（调用方据此 fail-closed，不再续期 / 写终态）
        assertFalse(service.renewTurnLease("s-1"));
    }

    @Test
    void should_propagateStoreFault_when_renewTurnLease_given_connectionError() {
        // given（design D5①：连接 / 命令异常以运行时异常表达，MUST NOT 被吞成 false）
        when(leaseStore.renew(eq(CoordLeaseType.TURN), eq("turn:session:s-1"), any(), any()))
                .thenThrow(new IllegalStateException("redis unavailable"));

        // when // then（异常原样透传，调用方据此区分瞬断容忍）
        assertThrows(IllegalStateException.class, () -> service.renewTurnLease("s-1"));
    }

    @Test
    void should_releaseTurnLeaseByOwner_when_releaseTurnLease_given_sessionId() {
        // given // when
        service.releaseTurnLease("s-1");

        // then（F11：仅释放本实例持有的租约，接管者的有效租约不受迟到释放影响）
        verify(leaseStore).releaseOwned(CoordLeaseType.TURN, "turn:session:s-1", service.instanceId());
    }

    @Test
    void should_reportLeasePresence_when_hasActiveTurnLease_given_activeLookup() {
        // given（任意持有者的有效租约：启动恢复兜底据此跳过存活实例运行中的会话）
        when(leaseStore.findActive(CoordLeaseType.TURN, "turn:session:s-1")).thenReturn(true);

        // when // then
        assertTrue(service.hasActiveTurnLease("s-1"));
    }

    @Test
    void should_delegateOwnOwnerQuery_when_listOwnActiveTurnKeys_given_instanceId() {
        // given（F13：仅枚举本实例持有的有效 turn 租约键）
        when(leaseStore.listOwnedTurnKeys(any())).thenReturn(List.of("turn:session:s-1"));

        // when
        List<String> turnKeys = service.listOwnActiveTurnKeys();

        // then
        assertEquals(List.of("turn:session:s-1"), turnKeys);
        verify(leaseStore).listOwnedTurnKeys(service.instanceId());
    }

    @Test
    void should_acquireFireLease_when_tryAcquireFireLease_given_freeWindow() {
        // given
        when(leaseStore.fireTryAcquire("dep-1", service.instanceId())).thenReturn(true);

        // when // then（fire 走专用通道，30s 窗口为端口实现语义常量）
        assertTrue(service.tryAcquireFireLease("dep-1"));
    }

    @Test
    void should_rejectDuplicateFire_when_tryAcquireFireLease_given_windowOccupied() {
        // given（窗口内已有触发进行中）
        when(leaseStore.fireTryAcquire("dep-1", service.instanceId())).thenReturn(false);

        // when // then
        assertFalse(service.tryAcquireFireLease("dep-1"));
    }

    @Test
    void should_releaseFireLeaseByOwner_when_releaseFireLease_given_schedulerId() {
        // given // when
        service.releaseFireLease("dep-1");

        // then（F11：owner 条件释放，超 TTL 被接管后不误摘接管者租约造成双触发窗口）
        verify(leaseStore).fireReleaseOwned("dep-1", service.instanceId());
    }

    // ==================== 实例标识解析与优雅停机（move-coordination-leases-to-redis D5⑤ / D6） ====================

    /** 以固定实例标识装配服务（不读环境变量），复用同一 mock 存储。 */
    private CoordinationLeaseService serviceWith(String instanceId,
                                                 CoordinationLeaseService.InstanceIdSource source) {
        CoordinationLeaseService scoped = new CoordinationLeaseService(
                new CoordinationLeaseService.ResolvedInstance(instanceId, source));
        ReflectionTestUtils.setField(scoped, "leaseStore", leaseStore);
        return scoped;
    }

    @Test
    void should_useEnvSource_when_resolveInstanceId_given_appInstanceIdPresent() {
        // given（APP_INSTANCE_ID 显式配置：容器编排指定实例槽位，含首尾空白）
        Map<String, String> env = Map.of(CoordinationLeaseService.ENV_INSTANCE_ID, " backend-01 ",
                "HOSTNAME", "ignored-host");

        // when
        CoordinationLeaseService.ResolvedInstance resolved =
                CoordinationLeaseService.resolveInstanceId(env::get);

        // then（标识去空白并加前缀，来源标为 ENV——启动不告警）
        assertEquals("instance-backend-01", resolved.instanceId());
        assertEquals(CoordinationLeaseService.InstanceIdSource.ENV, resolved.source());
    }

    @Test
    void should_useHostNameSource_when_resolveInstanceId_given_appInstanceIdMissing() {
        // given（APP_INSTANCE_ID 缺失，回落主机名）
        Map<String, String> env = Map.of("HOSTNAME", "pod-abc");

        // when
        CoordinationLeaseService.ResolvedInstance resolved =
                CoordinationLeaseService.resolveInstanceId(env::get);

        // then（来源 HOST_NAME：滚动重建槽位漂移，启动须 WARN）
        assertEquals("instance-pod-abc", resolved.instanceId());
        assertEquals(CoordinationLeaseService.InstanceIdSource.HOST_NAME, resolved.source());
    }

    @Test
    void should_useRandomSourceAndDistinctIds_when_resolveInstanceId_given_noEnvAtAll() {
        // given（APP_INSTANCE_ID 与主机名均缺失）
        Map<String, String> env = Map.of();

        // when
        CoordinationLeaseService.ResolvedInstance first = CoordinationLeaseService.resolveInstanceId(env::get);
        CoordinationLeaseService.ResolvedInstance second = CoordinationLeaseService.resolveInstanceId(env::get);

        // then（随机回落：每次启动都是新实例槽位，来源 RANDOM）
        assertEquals(CoordinationLeaseService.InstanceIdSource.RANDOM, first.source());
        assertTrue(first.instanceId().startsWith("instance-"));
        assertNotEquals(first.instanceId(), second.instanceId());
    }

    @Test
    void should_reportInstanceIdSourceQuietly_when_reportInstanceIdSource_given_eachSource() {
        // given（三种来源均需可安全自检：只记日志，不外抛异常、不触碰存储）
        for (CoordinationLeaseService.InstanceIdSource source : CoordinationLeaseService.InstanceIdSource.values()) {
            CoordinationLeaseService scoped = serviceWith("instance-x", source);

            // when // then
            assertDoesNotThrow(scoped::reportInstanceIdSource);
        }
        verifyNoInteractions(leaseStore);
    }

    @Test
    void should_releaseEveryOwnedTurnKey_when_shutdown_given_twoResidualLeases() {
        // given（本实例 owner set 内两条残留 turn 租约：优雅停机免等 TTL 归还执行权）
        CoordinationLeaseService scoped =
                serviceWith("instance-a", CoordinationLeaseService.InstanceIdSource.ENV);
        when(leaseStore.listOwnedTurnKeys("instance-a"))
                .thenReturn(List.of("turn:session:s-1", "turn:session:s-2"));

        // when
        scoped.releaseOwnTurnLeasesOnShutdown();

        // then（逐条 owner-scoped 释放：已被接管的租约不会被误摘，F11）
        verify(leaseStore).releaseOwned(CoordLeaseType.TURN, "turn:session:s-1", "instance-a");
        verify(leaseStore).releaseOwned(CoordLeaseType.TURN, "turn:session:s-2", "instance-a");
    }

    @Test
    void should_releaseNothing_when_shutdown_given_noOwnedLeases() {
        // given（空闲实例：owner set 为空）
        CoordinationLeaseService scoped =
                serviceWith("instance-a", CoordinationLeaseService.InstanceIdSource.ENV);
        when(leaseStore.listOwnedTurnKeys("instance-a")).thenReturn(List.of());

        // when
        scoped.releaseOwnTurnLeasesOnShutdown();

        // then
        verify(leaseStore, never()).releaseOwned(any(), any(), any());
    }

    @Test
    void should_swallowStoreFault_when_shutdown_given_enumerationThrows() {
        // given（停机路径存储不可用：MUST NOT 中断关闭流程，由 TTL 兜底过期）
        CoordinationLeaseService scoped =
                serviceWith("instance-a", CoordinationLeaseService.InstanceIdSource.ENV);
        when(leaseStore.listOwnedTurnKeys("instance-a")).thenThrow(new IllegalStateException("redis down"));

        // when // then
        assertDoesNotThrow(scoped::releaseOwnTurnLeasesOnShutdown);
        verify(leaseStore, never()).releaseOwned(any(), any(), any());
    }

    @Test
    void should_continueRemainingReleases_when_shutdown_given_singleReleaseThrows() {
        // given（单条释放异常不得中断其余租约归还）
        CoordinationLeaseService scoped =
                serviceWith("instance-a", CoordinationLeaseService.InstanceIdSource.ENV);
        when(leaseStore.listOwnedTurnKeys("instance-a"))
                .thenReturn(List.of("turn:session:s-1", "turn:session:s-2"));
        when(leaseStore.releaseOwned(CoordLeaseType.TURN, "turn:session:s-1", "instance-a"))
                .thenThrow(new IllegalStateException("command timeout"));

        // when
        scoped.releaseOwnTurnLeasesOnShutdown();

        // then（首条抛异常后仍尝试第二条）
        verify(leaseStore).releaseOwned(CoordLeaseType.TURN, "turn:session:s-2", "instance-a");
    }
}
