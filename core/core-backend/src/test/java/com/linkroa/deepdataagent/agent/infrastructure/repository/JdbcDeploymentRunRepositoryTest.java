package com.linkroa.deepdataagent.agent.infrastructure.repository;

import com.linkroa.deepdataagent.agent.domain.model.DeploymentRun;
import com.linkroa.deepdataagent.agent.domain.model.DeploymentRunsFilter;
import com.linkroa.deepdataagent.agent.domain.model.enums.DeploymentRunStatus;
import com.linkroa.deepdataagent.agent.domain.model.enums.DeploymentTriggerType;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.DeploymentRunEntity;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.mapper.DeploymentRunMapper;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * {@link JdbcDeploymentRunRepository} 单测：append-only 保存（清主键回读）与查询映射。
 */
@ExtendWith(MockitoExtension.class)
class JdbcDeploymentRunRepositoryTest {

    @Mock
    private DeploymentRunMapper mapper;

    @InjectMocks
    private JdbcDeploymentRunRepository repository;

    private final OffsetDateTime now = OffsetDateTime.parse("2026-09-04T10:00:00+08:00");

    private DeploymentRunEntity buildEntity(String runId) {
        DeploymentRunEntity entity = new DeploymentRunEntity();
        entity.setId(1L);
        entity.setRunId(runId);
        entity.setDeploymentId("dep-1");
        entity.setSessionId("sess-1");
        entity.setTriggerKind("cron");
        entity.setStatus("running");
        entity.setStartedAt(now);
        return entity;
    }

    private DeploymentRun buildRun() {
        return new DeploymentRun(null, "drun_abc", "dep-1", "sess-1",
                DeploymentTriggerType.CRON, DeploymentRunStatus.RUNNING, now, null, now, now);
    }

    @Test
    void should_insertWithClearedIdAndReadBack_when_save_given_newRun() {
        // given
        when(mapper.selectByRunId("drun_abc")).thenReturn(buildEntity("drun_abc"));

        // when
        DeploymentRun saved = repository.save(buildRun());

        // then（主键清空后插入，回读数据库快照）
        ArgumentCaptor<DeploymentRunEntity> captor = ArgumentCaptor.forClass(DeploymentRunEntity.class);
        org.mockito.Mockito.verify(mapper).insert(captor.capture());
        assertNull(captor.getValue().getId());
        assertEquals(1L, saved.id());
        assertEquals(DeploymentRunStatus.RUNNING, saved.status());
    }

    @Test
    void should_returnOriginal_when_save_given_readBackMiss() {
        // given
        when(mapper.selectByRunId("drun_abc")).thenReturn(null);

        // when
        DeploymentRun saved = repository.save(buildRun());

        // then（兜底返回入参）
        assertEquals("drun_abc", saved.runId());
        assertNull(saved.id());
    }

    @Test
    void should_returnPresent_when_findByRunId_given_entityFound() {
        // given
        when(mapper.selectByRunId("drun_abc")).thenReturn(buildEntity("drun_abc"));

        // when
        Optional<DeploymentRun> found = repository.findByRunId("drun_abc");

        // then（触发方式 / 状态字符串反解析为枚举）
        assertTrue(found.isPresent());
        assertEquals(DeploymentTriggerType.CRON, found.get().triggerKind());
        assertNull(found.get().finishedAt());
    }

    @Test
    void should_mapList_when_findByCursor_given_entities() {
        // given（单调度器作用域：deploymentId 置于过滤条件，游标参数原样透传 Mapper）
        DeploymentRunsFilter filter = new DeploymentRunsFilter(
                "dep-1", null, null, null, null, null, false);
        when(mapper.selectByCursor(filter, 21))
                .thenReturn(List.of(buildEntity("drun_1"), buildEntity("drun_2")));

        // when
        List<DeploymentRun> runs = repository.findByCursor(filter, 21);

        // then
        assertEquals(2, runs.size());
        assertEquals(List.of("drun_1", "drun_2"), runs.stream().map(DeploymentRun::runId).toList());
    }
}
