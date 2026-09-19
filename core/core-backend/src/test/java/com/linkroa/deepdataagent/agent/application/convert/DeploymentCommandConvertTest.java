package com.linkroa.deepdataagent.agent.application.convert;

import com.linkroa.deepdataagent.agent.application.command.UpdateDeploymentCommand;
import com.linkroa.deepdataagent.agent.application.query.ListDeploymentRunsQuery;
import com.linkroa.deepdataagent.agent.application.query.ListDeploymentsQuery;
import com.linkroa.deepdataagent.agent.controller.request.UpdateDeploymentRequest;
import com.linkroa.deepdataagent.agent.domain.model.enums.DeploymentStatus;
import com.linkroa.deepdataagent.shared.result.CursorPageParams;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DeploymentCommandConvert} 单测：游标列表查询装配（状态 / 时间区间 / 归档开关 /
 * 非法值拒绝）与 merge-patch 更新命令三态裁决（缺省不改 / 显式 null 清空 / 类型校验 /
 * 未知键拒绝 / 元数据 null 值键保留）。
 */
class DeploymentCommandConvertTest {

    private final DeploymentCommandConvert convert = DeploymentCommandConvert.INSTANCE;

    private static UpdateDeploymentRequest patch(Map<String, Object> fields) {
        // LinkedHashMap 允许 null 值（模拟 Jackson 承载的显式 null 键）
        return new UpdateDeploymentRequest(new LinkedHashMap<>(fields));
    }

    // ==================== toListQuery ====================

    @Test
    void should_assembleTypedQuery_when_toListQuery_given_allParams() {
        // given // when
        ListDeploymentsQuery query = convert.toListQuery(1L, "paused", "agent-1",
                "2026-09-01T00:00:00+08:00", "2026-09-02T00:00:00+08:00", "true",
                "30", "dep_a", null);

        // then
        assertEquals(1L, query.ownerId());
        assertEquals(DeploymentStatus.PAUSED, query.status());
        assertEquals("agent-1", query.agentId());
        assertEquals(OffsetDateTime.parse("2026-09-01T00:00:00+08:00"), query.createdAfter());
        assertEquals(OffsetDateTime.parse("2026-09-02T00:00:00+08:00"), query.createdBefore());
        assertTrue(query.includeArchived());
        assertEquals(new CursorPageParams(30, "dep_a", null), query.cursor());
    }

    @Test
    void should_defaultFilters_when_toListQuery_given_blankParams() {
        // given // when
        ListDeploymentsQuery query = convert.toListQuery(1L, " ", null, null, null, null, null, null, null);

        // then（空白状态 / 缺省归档开关收敛为不过滤 + 缺省游标 20 条）
        assertNull(query.status());
        assertFalse(query.includeArchived());
        assertEquals(CursorPageParams.DEFAULT_LIMIT, query.cursor().limit());
    }

    @Test
    void should_throwIllegalArgument_when_toListQuery_given_invalidStatusOrTimeOrFlag() {
        // given // when // then（非法状态 / 非 ISO 时间 / 非布尔归档开关 → 400 语义）
        assertThrows(IllegalArgumentException.class,
                () -> convert.toListQuery(1L, "archived", null, null, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> convert.toListQuery(1L, null, null, "2026-09-01", null, null, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> convert.toListQuery(1L, null, null, null, null, "yes", null, null, null));
    }

    @Test
    void should_trimDeploymentId_when_toListRunsQuery_given_blankDeploymentId() {
        // given // when（全局作用域：deploymentId 空白归一 null）
        ListDeploymentRunsQuery query = convert.toListRunsQuery("  ", 1L,
                null, "2026-09-02T00:00:00+08:00", "5", null, "drun_x");

        // then
        assertNull(query.deploymentId());
        assertEquals(1L, query.ownerId());
        assertEquals(OffsetDateTime.parse("2026-09-02T00:00:00+08:00"), query.createdBefore());
        assertEquals("drun_x", query.cursor().beforeId());
    }

    // ==================== toUpdateCommand ====================

    @Test
    void should_markAbsentFieldsUnpresent_when_toUpdateCommand_given_nameOnly() {
        // given（补丁体仅含 name：其余字段全部「缺省不改」）
        UpdateDeploymentRequest request = patch(Map.of("name", "新名字"));

        // when
        UpdateDeploymentCommand command = convert.toUpdateCommand("dep-1", request);

        // then
        assertEquals("dep-1", command.deploymentId());
        assertEquals("新名字", command.name());
        assertFalse(command.descriptionPresent());
        assertFalse(command.environmentVariablesPresent());
        assertFalse(command.metadataPresent());
        assertFalse(command.schedulePresent());
    }

    @Test
    void should_markPresentWithNull_when_toUpdateCommand_given_explicitNullValues() {
        // given（显式 null：清空描述、清空调度、清空环境变量）
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("description", null);
        fields.put("schedule", null);
        fields.put("environment_variables", null);

        // when
        UpdateDeploymentCommand command = convert.toUpdateCommand("dep-1", patch(fields));

        // then（present=true 且值为 null = 清空信号）
        assertTrue(command.descriptionPresent());
        assertNull(command.description());
        assertTrue(command.schedulePresent());
        assertNull(command.schedule());
        assertTrue(command.environmentVariablesPresent());
        assertNull(command.environmentVariables());
    }

    @Test
    void should_serializePayloadsAndKeepNullKeys_when_toUpdateCommand_given_structuredValues() {
        // given（结构化载荷序列化透传；metadata 增量保留 null 值键 = 键级删除标记）
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("a", null);
        metadata.put("c", "3");
        Map<String, Object> resourceItem = new LinkedHashMap<>();
        resourceItem.put("type", "file");
        resourceItem.put("file_id", "file_1");
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("environment_variables", Map.of("KEY", "v1"));
        fields.put("resources", List.of(resourceItem));
        fields.put("vault_ids", List.of("vault-a"));
        fields.put("metadata", metadata);
        fields.put("schedule", Map.of("cron", "0 0 9 * * *", "timezone", "Asia/Shanghai"));

        // when
        UpdateDeploymentCommand command = convert.toUpdateCommand("dep-1", patch(fields));

        // then
        assertEquals("{\"KEY\":\"v1\"}", command.environmentVariables());
        assertEquals("[{\"type\":\"file\",\"file_id\":\"file_1\"}]", command.resources());
        assertEquals(List.of("vault-a"), command.vaultIds());
        assertEquals("{\"a\":null,\"c\":\"3\"}", command.metadataMerge());
        assertEquals("0 0 9 * * *", command.schedule().cron());
        assertEquals("Asia/Shanghai", command.schedule().timezone());
    }

    @Test
    void should_buildNoopCommand_when_toUpdateCommand_given_nullRequest() {
        // given // when（空补丁体 = 全字段不改的 no-op）
        UpdateDeploymentCommand command = convert.toUpdateCommand("dep-1", null);

        // then
        assertEquals("dep-1", command.deploymentId());
        assertNull(command.name());
        assertFalse(command.schedulePresent());
        assertFalse(command.metadataPresent());
    }

    @Test
    void should_throwIllegalArgument_when_toUpdateCommand_given_unknownFieldOrUnpatchableBinding() {
        // given // when // then（未知键与不可调绑定键（agent_id / webhook）拒绝）
        assertThrows(IllegalArgumentException.class,
                () -> convert.toUpdateCommand("dep-1", patch(Map.of("owner_id", "x"))));
        assertThrows(IllegalArgumentException.class,
                () -> convert.toUpdateCommand("dep-1", patch(Map.of("agent_version", 3))));
    }

    @Test
    void should_throwIllegalArgument_when_toUpdateCommand_given_invalidValueTypes() {
        // given // when // then（name 显式 null、类型错体、schedule 缺 cron → 400）
        Map<String, Object> nullName = new LinkedHashMap<>();
        nullName.put("name", null);
        assertThrows(IllegalArgumentException.class,
                () -> convert.toUpdateCommand("dep-1", patch(nullName)));
        assertThrows(IllegalArgumentException.class,
                () -> convert.toUpdateCommand("dep-1", patch(Map.of("description", 42))));
        assertThrows(IllegalArgumentException.class,
                () -> convert.toUpdateCommand("dep-1", patch(Map.of("vault_ids", "vault-a"))));
        assertThrows(IllegalArgumentException.class,
                () -> convert.toUpdateCommand("dep-1", patch(Map.of("schedule", Map.of("timezone", "UTC")))));
    }
}
