package com.linkroa.deepdataagent.agent.infrastructure.convert;

import com.linkroa.deepdataagent.agent.domain.model.Deployment;
import com.linkroa.deepdataagent.agent.domain.model.DeploymentSchedule;
import com.linkroa.deepdataagent.agent.domain.model.enums.DeploymentStatus;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.DeploymentEntity;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link DeploymentPersistenceConvert} 持久化转换器单测（调度器 ⇄ 实体）：
 * schedule 值对象 ⇄ JSONB 文本、status 枚举 ⇄ 小写值、vaultIds 列表 ⇄ JSON 数组文本、
 * JSON 透传载荷原样搬运。
 */
class DeploymentPersistenceConvertTest {

    private final DeploymentPersistenceConvert convert = DeploymentPersistenceConvert.INSTANCE;

    private final OffsetDateTime now = OffsetDateTime.parse("2026-09-04T10:00:00+08:00");

    private Deployment buildDeployment() {
        return new Deployment(1L, "dep-1", "每日报表", "报表调度", "agent-a", 3, "env-1",
                "{\"TZ\":\"Asia/Shanghai\"}", "[{\"type\":\"file\",\"file_id\":\"file_1\"}]",
                List.of("vault-a", "vault-b"), "[{\"type\":\"user_message\",\"text\":\"开工\"}]",
                "{\"biz\":\"report\"}", new DeploymentSchedule("0 0 9 * * *", "Asia/Shanghai"),
                now, "tok-123", DeploymentStatus.PAUSED, "维护", now, "sess-1", "running",
                2L, now, now, now, "u-1", "u-1");
    }

    private DeploymentEntity buildEntity() {
        DeploymentEntity entity = new DeploymentEntity();
        entity.setId(5L);
        entity.setDeploymentId("dep-2");
        entity.setName("回调触发");
        entity.setAgentId("agent-b");
        entity.setAgentVersion(1);
        entity.setEnvironmentVariables("{}");
        entity.setResources("[]");
        entity.setVaultIds("[\"vault-c\"]");
        entity.setInitialEvents("[]");
        entity.setMetadata("{}");
        entity.setWebhookToken("tok-456");
        entity.setStatus("ACTIVE");
        entity.setOwnerId(3L);
        return entity;
    }

    @Test
    void should_mapAllFields_when_toEntity_given_fullScheduler() {
        // given
        Deployment deployment = buildDeployment();

        // when
        DeploymentEntity entity = convert.toEntity(deployment);

        // then（schedule 序列化为 JSONB 文本、status 小写值、vaultIds JSON 数组文本）
        assertEquals(1L, entity.getId());
        assertEquals("dep-1", entity.getDeploymentId());
        assertEquals("每日报表", entity.getName());
        assertEquals("报表调度", entity.getDescription());
        assertEquals("agent-a", entity.getAgentId());
        assertEquals(3, entity.getAgentVersion());
        assertEquals("env-1", entity.getEnvironmentId());
        assertEquals("{\"TZ\":\"Asia/Shanghai\"}", entity.getEnvironmentVariables());
        assertEquals("[{\"type\":\"file\",\"file_id\":\"file_1\"}]", entity.getResources());
        assertEquals("[\"vault-a\",\"vault-b\"]", entity.getVaultIds());
        assertEquals("[{\"type\":\"user_message\",\"text\":\"开工\"}]", entity.getInitialEvents());
        assertEquals("{\"biz\":\"report\"}", entity.getMetadata());
        assertEquals("{\"cron\":\"0 0 9 * * *\",\"timezone\":\"Asia/Shanghai\"}", entity.getSchedule());
        assertEquals(now, entity.getNextRunAt());
        assertEquals("tok-123", entity.getWebhookToken());
        assertEquals("paused", entity.getStatus());
        assertEquals("维护", entity.getPausedReason());
        assertEquals(now, entity.getLastRunAt());
        assertEquals("sess-1", entity.getLastSessionId());
        assertEquals("running", entity.getLastStatus());
        assertEquals(2L, entity.getOwnerId());
        assertEquals(now, entity.getArchivedAt());
    }

    @Test
    void should_mapNullableColumns_when_toEntity_given_manualScheduler() {
        // given（无调度：schedule / nextRunAt 列留空，vaultIds 归一空数组文本）
        Deployment deployment = Deployment.create("dep-3", "仅手动", null, "agent-c", 1, null,
                null, null, null, null, null, null, null, 1L);

        // when
        DeploymentEntity entity = convert.toEntity(deployment);

        // then
        assertNull(entity.getSchedule());
        assertNull(entity.getNextRunAt());
        assertEquals("[]", entity.getVaultIds());
        assertEquals("{}", entity.getEnvironmentVariables());
        assertEquals("active", entity.getStatus());
        assertNull(entity.getPausedReason());
    }

    @Test
    void should_mapAllFields_when_toDomain_given_fullEntity() {
        // given（status 大写存储值：大小写不敏感解析）
        DeploymentEntity entity = buildEntity();
        entity.setSchedule("{\"cron\":\"0 30 8 * * *\",\"timezone\":\"UTC\"}");
        entity.setNextRunAt(now);

        // when
        Deployment deployment = convert.toDomain(entity);

        // then
        assertEquals(5L, deployment.id());
        assertEquals("dep-2", deployment.deploymentId());
        assertEquals("回调触发", deployment.name());
        assertEquals("agent-b", deployment.agentId());
        assertEquals(1, deployment.agentVersion());
        assertEquals(List.of("vault-c"), deployment.vaultIds());
        assertEquals(new DeploymentSchedule("0 30 8 * * *", "UTC"), deployment.schedule());
        assertEquals(now, deployment.nextRunAt());
        assertEquals("tok-456", deployment.webhookToken());
        assertEquals(DeploymentStatus.ACTIVE, deployment.status());
        assertNull(deployment.pausedReason());
        assertNull(deployment.archivedAt());
    }

    @Test
    void should_normalizeBlankColumns_when_toDomain_given_missingOptionalJson() {
        // given（schedule 空白列=仅手动语义；vaultIds 空白列=空列表）
        DeploymentEntity entity = buildEntity();
        entity.setSchedule(" ");
        entity.setVaultIds(null);
        entity.setEnvironmentVariables(null);

        // when
        Deployment deployment = convert.toDomain(entity);

        // then（JSON 透传载荷由领域紧凑构造器归一默认值）
        assertNull(deployment.schedule());
        assertNull(deployment.nextRunAt());
        assertEquals(List.of(), deployment.vaultIds());
        assertEquals("{}", deployment.environmentVariables());
    }

    @Test
    void should_returnNull_when_toEntityOrToDomain_given_null() {
        // given // when // then
        assertNull(convert.toEntity(null));
        assertNull(convert.toDomain(null));
    }

    @Test
    void should_roundTrip_when_toDomain_given_convertedEntity() {
        // given
        Deployment deployment = buildDeployment();

        // when
        Deployment restored = convert.toDomain(convert.toEntity(deployment));

        // then（关键字段往返一致）
        assertEquals(deployment.deploymentId(), restored.deploymentId());
        assertEquals(deployment.agentVersion(), restored.agentVersion());
        assertEquals(deployment.schedule(), restored.schedule());
        assertEquals(deployment.nextRunAt(), restored.nextRunAt());
        assertEquals(deployment.vaultIds(), restored.vaultIds());
        assertEquals(deployment.environmentVariables(), restored.environmentVariables());
        assertEquals(deployment.resources(), restored.resources());
        assertEquals(deployment.initialEvents(), restored.initialEvents());
        assertEquals(deployment.metadata(), restored.metadata());
        assertEquals(DeploymentStatus.PAUSED, restored.status());
        assertEquals("维护", restored.pausedReason());
    }

    @Test
    void should_throwException_when_toDomain_given_illegalScheduleJson() {
        // given
        DeploymentEntity entity = buildEntity();
        entity.setSchedule("{\"timezone\":\"UTC\"}");

        // when // then（缺 cron 字段 → 值对象拒绝）
        assertThrows(IllegalArgumentException.class, () -> convert.toDomain(entity));
    }
}
