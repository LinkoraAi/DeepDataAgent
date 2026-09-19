package com.linkroa.deepdataagent.agent.infrastructure.repository;

import com.linkroa.deepdataagent.agent.domain.model.Deployment;
import com.linkroa.deepdataagent.agent.domain.model.DeploymentListFilter;
import com.linkroa.deepdataagent.agent.domain.model.enums.DeploymentStatus;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.DeploymentEntity;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.mapper.DeploymentMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link JdbcDeploymentRepository} 单测：保存清主键回读、查询映射委托、
 * 到期粗筛委托与 CAS 领取（影响行数 ⇄ 布尔）语义。
 */
@ExtendWith(MockitoExtension.class)
class JdbcDeploymentRepositoryTest {

    @Mock
    private DeploymentMapper mapper;

    @InjectMocks
    private JdbcDeploymentRepository repository;

    private final OffsetDateTime now = OffsetDateTime.parse("2026-09-04T10:00:00+08:00");

    private DeploymentEntity buildEntity(String deploymentId) {
        DeploymentEntity entity = new DeploymentEntity();
        entity.setId(1L);
        entity.setDeploymentId(deploymentId);
        entity.setName("调度器");
        entity.setAgentId("agent-1");
        entity.setAgentVersion(1);
        entity.setStatus("active");
        entity.setOwnerId(1L);
        return entity;
    }

    private Deployment buildDeployment() {
        return new Deployment(null, "dep-1", "调度器", null, "agent-1", 1, null,
                "{}", "[]", List.of(), "[]", "{}", null, null, null,
                DeploymentStatus.ACTIVE, null, null, null, null, 1L, null, now, now, null, null);
    }

    @Test
    void should_insertWithClearedIdAndReadBack_when_save_given_newDeployment() {
        // given
        when(mapper.selectByDeploymentId("dep-1")).thenReturn(buildEntity("dep-1"));

        // when
        Deployment saved = repository.save(buildDeployment());

        // then（主键清空后插入，落库后回读数据库快照）
        ArgumentCaptor<DeploymentEntity> captor = ArgumentCaptor.forClass(DeploymentEntity.class);
        verify(mapper).insert(captor.capture());
        assertNull(captor.getValue().getId());
        assertEquals("dep-1", saved.deploymentId());
        assertEquals(1L, saved.id());
    }

    @Test
    void should_returnOriginal_when_save_given_readBackMiss() {
        // given（极端并发下回读缺失：兜底返回入参对象）
        when(mapper.selectByDeploymentId("dep-1")).thenReturn(null);

        // when
        Deployment saved = repository.save(buildDeployment());

        // then
        assertEquals("dep-1", saved.deploymentId());
        assertNull(saved.id());
    }

    @Test
    void should_returnPresent_when_findByDeploymentId_given_entityFound() {
        // given
        when(mapper.selectByDeploymentId("dep-1")).thenReturn(buildEntity("dep-1"));

        // when
        Optional<Deployment> found = repository.findByDeploymentId("dep-1");

        // then
        assertTrue(found.isPresent());
        assertEquals("dep-1", found.get().deploymentId());
        assertEquals(DeploymentStatus.ACTIVE, found.get().status());
    }

    @Test
    void should_returnEmpty_when_findByDeploymentId_given_notFound() {
        // given
        when(mapper.selectByDeploymentId("dep-9")).thenReturn(null);

        // when // then
        assertTrue(repository.findByDeploymentId("dep-9").isEmpty());
    }

    @Test
    void should_delegateActiveOnlyQuery_when_findByWebhookToken_given_token() {
        // given（可触发过滤在 Mapper 条件里收敛：仅 active 且未归档）
        DeploymentEntity entity = buildEntity("dep-2");
        entity.setWebhookToken("tok-1");
        when(mapper.selectActiveByWebhookToken("tok-1")).thenReturn(entity);

        // when
        Optional<Deployment> found = repository.findByWebhookToken("tok-1");

        // then
        assertTrue(found.isPresent());
        assertEquals("dep-2", found.get().deploymentId());
    }

    @Test
    void should_mapList_when_findByCursor_given_entities() {
        // given（游标查询委托：owner 与过滤条件原样透传 Mapper，模型逐条映射）
        DeploymentListFilter filter = new DeploymentListFilter(
                DeploymentStatus.ACTIVE, null, null, null, false, null, null, false);
        when(mapper.selectByCursor(1L, filter, 21))
                .thenReturn(List.of(buildEntity("dep-a"), buildEntity("dep-b")));

        // when
        List<Deployment> page = repository.findByCursor(1L, filter, 21);

        // then
        assertEquals(List.of("dep-a", "dep-b"),
                page.stream().map(Deployment::deploymentId).toList());
    }

    @Test
    void should_delegateDueQuery_when_findDue_given_nowAndLimit() {
        // given
        when(mapper.selectDue(now, 20)).thenReturn(List.of(buildEntity("dep-due")));

        // when
        List<Deployment> due = repository.findDue(now, 20);

        // then（粗筛委托 + 模型映射）
        assertEquals(1, due.size());
        assertEquals("dep-due", due.get(0).deploymentId());
        verify(mapper).selectDue(eq(now), eq(20));
    }

    @Test
    void should_returnTrue_when_advanceNextRun_given_casAffectedOneRow() {
        // given（CAS 影响 1 行=本实例领取成功）
        OffsetDateTime next = now.plusDays(1);
        when(mapper.advanceNextRunCas("dep-1", now, next)).thenReturn(1);

        // when // then
        assertTrue(repository.advanceNextRun("dep-1", now, next));
    }

    @Test
    void should_returnFalse_when_advanceNextRun_given_casAffectedZeroRow() {
        // given（CAS 影响 0 行=已被其他实例领取或时间已推进）
        OffsetDateTime next = now.plusDays(1);
        when(mapper.advanceNextRunCas("dep-1", now, next)).thenReturn(0);

        // when // then
        assertFalse(repository.advanceNextRun("dep-1", now, next));
    }

    @Test
    void should_updateByIdAndReadBack_when_update_given_pausedDeployment() {
        // given
        Deployment paused = buildDeployment().pause("维护");
        DeploymentEntity updated = buildEntity("dep-1");
        updated.setStatus("paused");
        updated.setPausedReason("维护");
        when(mapper.selectByDeploymentId("dep-1")).thenReturn(updated);

        // when
        Deployment result = repository.update(paused);

        // then（updateById 全量更新后回读库内最新态）
        verify(mapper).updateById(any(DeploymentEntity.class));
        assertEquals(DeploymentStatus.PAUSED, result.status());
        assertEquals("维护", result.pausedReason());
    }

    @Test
    void should_returnTrue_when_updateLastRun_given_narrowColumnsUpdated() {
        // given（F08：窄列回写影响 1 行）
        when(mapper.updateLastRun("dep-1", "sess-1", "running", now)).thenReturn(1);

        // when // then
        assertTrue(repository.updateLastRun("dep-1", "sess-1", "running", now));
        verify(mapper).updateLastRun("dep-1", "sess-1", "running", now);
    }

    @Test
    void should_returnFalse_when_updateLastRun_given_deploymentMissing() {
        // given（调度器不存在 / 已逻辑删除：影响 0 行）
        when(mapper.updateLastRun("dep-ghost", null, "failed", now)).thenReturn(0);

        // when // then
        assertFalse(repository.updateLastRun("dep-ghost", null, "failed", now));
    }

    @Test
    void should_countActiveDeployments_when_countActiveByEnvironmentId_given_referenceExists() {
        // given（F10：环境删除反查，引用计数透传 Mapper）
        when(mapper.selectCountActiveByEnvironmentId("env-1")).thenReturn(3L);

        // when // then
        assertEquals(3L, repository.countActiveByEnvironmentId("env-1"));
    }

    @Test
    void should_returnZero_when_countActiveByEnvironmentId_given_nullCount() {
        // given（防御性收敛：Mapper 返回 null 时按 0 处理）
        when(mapper.selectCountActiveByEnvironmentId("env-1")).thenReturn(null);

        // when // then
        assertEquals(0L, repository.countActiveByEnvironmentId("env-1"));
    }
}
