package com.linkroa.deepdataagent.agent.infrastructure.convert;

import com.linkroa.deepdataagent.agent.domain.model.DeploymentRun;
import com.linkroa.deepdataagent.agent.domain.model.enums.DeploymentRunStatus;
import com.linkroa.deepdataagent.agent.domain.model.enums.DeploymentTriggerType;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.DeploymentRunEntity;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link DeploymentRunPersistenceConvert} 持久化转换器单测（运行记录 ⇄ 实体）：
 * triggerKind / status 领域枚举 ⇄ 小写值字符串映射。
 */
class DeploymentRunPersistenceConvertTest {

    private final DeploymentRunPersistenceConvert convert = DeploymentRunPersistenceConvert.INSTANCE;

    private final OffsetDateTime now = OffsetDateTime.parse("2026-09-04T10:00:00+08:00");

    @Test
    void should_mapEnumsToLowerCase_when_toEntity_given_runningRecord() {
        // given
        DeploymentRun run = new DeploymentRun(7L, "drun_abc", "dep-1", "sess-1",
                DeploymentTriggerType.CRON, DeploymentRunStatus.RUNNING, now, null, now, now);

        // when
        DeploymentRunEntity entity = convert.toEntity(run);

        // then
        assertEquals(7L, entity.getId());
        assertEquals("drun_abc", entity.getRunId());
        assertEquals("dep-1", entity.getDeploymentId());
        assertEquals("sess-1", entity.getSessionId());
        assertEquals("cron", entity.getTriggerKind());
        assertEquals("running", entity.getStatus());
        assertEquals(now, entity.getStartedAt());
        assertNull(entity.getFinishedAt());
    }

    @Test
    void should_parseEnumsCaseInsensitively_when_toDomain_given_upperCasedColumns() {
        // given
        DeploymentRunEntity entity = new DeploymentRunEntity();
        entity.setId(9L);
        entity.setRunId("drun_xyz");
        entity.setDeploymentId("dep-2");
        entity.setSessionId("sess-2");
        entity.setTriggerKind("WEBHOOK");
        entity.setStatus("Succeeded");
        entity.setStartedAt(now);
        entity.setFinishedAt(now);

        // when
        DeploymentRun run = convert.toDomain(entity);

        // then
        assertEquals(9L, run.id());
        assertEquals("drun_xyz", run.runId());
        assertEquals(DeploymentTriggerType.WEBHOOK, run.triggerKind());
        assertEquals(DeploymentRunStatus.SUCCEEDED, run.status());
        assertEquals(now, run.finishedAt());
    }

    @Test
    void should_returnNull_when_toEntityOrToDomain_given_null() {
        // given // when // then
        assertNull(convert.toEntity(null));
        assertNull(convert.toDomain(null));
    }
}
