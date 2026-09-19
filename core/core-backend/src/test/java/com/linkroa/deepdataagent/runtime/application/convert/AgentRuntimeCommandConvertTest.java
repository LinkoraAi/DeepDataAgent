package com.linkroa.deepdataagent.runtime.application.convert;

import com.linkroa.deepdataagent.runtime.application.command.AgentReference;
import com.linkroa.deepdataagent.runtime.application.command.CreateSessionCommand;
import com.linkroa.deepdataagent.runtime.application.command.ResolveHumanConfirmationCommand;
import com.linkroa.deepdataagent.runtime.application.command.SendMessageCommand;
import com.linkroa.deepdataagent.runtime.application.command.SessionResourceItem;
import com.linkroa.deepdataagent.runtime.application.command.UpdateSessionCommand;
import com.linkroa.deepdataagent.runtime.application.query.ListEventsQuery;
import com.linkroa.deepdataagent.runtime.application.query.ListSessionsQuery;
import com.linkroa.deepdataagent.runtime.application.query.ReplayQuery;
import com.linkroa.deepdataagent.runtime.domain.model.SessionResource;
import com.linkroa.deepdataagent.runtime.domain.model.enums.AgentSessionStatus;
import com.linkroa.deepdataagent.runtime.domain.model.enums.ChatEventType;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AgentRuntimeCommandConvert} 装配器单测（纯映射：字段搬运与按类型分派）。
 * <p>入站 payload 结构校验矩阵见 {@code InboundEventValidatorTest}；
 * 追加挂载请求体形态判别（JsonNode → 已归一资源项）见 {@code AgentSessionControllerTest}。</p>
 */
class AgentRuntimeCommandConvertTest {

    private final AgentRuntimeCommandConvert assembler = AgentRuntimeCommandConvert.INSTANCE;

    @Test
    void should_buildCreateCommand_when_toCreateCommand_given_validRequest() {
        // given（无挂载资源、无保管库的裸创建）
        // when
        CreateSessionCommand command = assembler.toCreateCommand("u-1", "agent-a", "1", "会话标题",
                "{\"biz\":\"1\"}", null, null, "env-1", null);

        // then
        assertEquals("u-1", command.userId());
        assertEquals("agent-a", command.agentId());
        assertEquals("1", command.agentVersion());
        assertEquals("会话标题", command.title());
        assertEquals("{\"biz\":\"1\"}", command.metadata());
        // 未携带挂载资源时收敛为空列表
        assertEquals(List.of(), command.resources());
        // 未携带保管库时收敛为空列表；环境变量缺省为 null（由领域模型空白归一为 "{}"）
        assertEquals(List.of(), command.vaultIds());
        assertEquals(null, command.environmentVariables());
    }

    @Test
    void should_mapResources_when_toCreateCommand_given_mountedFiles() {
        // given（挂载两个文件：一个带 mount_path、一个省略）
        List<SessionResourceItem> resources = List.of(
                newFileResource("file_1", "mounts/a.txt"),
                newFileResource("file_2", null));

        // when
        CreateSessionCommand command = assembler.toCreateCommand("u-1", "agent-a", "1", null,
                "{}", resources, null, "env-1", null);

        // then（file_id / mount_path 映射为领域值对象，type 固定 file；省略 mount_path 缺省自动补全）
        assertEquals(2, command.resources().size());
        assertEquals(SessionResource.FILE_TYPE, command.resources().get(0).type());
        assertEquals("file_1", command.resources().get(0).fileId());
        assertEquals("mounts/a.txt", command.resources().get(0).mountPath());
        assertEquals("file_2", command.resources().get(1).fileId());
        assertEquals(SessionResource.DEFAULT_MOUNT_PATH_PREFIX + "file_2", command.resources().get(1).mountPath());
    }

    @Test
    void should_mapGithubAndMemoryStore_when_toCreateCommand_given_mixedResourceTypes() {
        // given（file + github_repository + memory_store 三类混合挂载）
        List<SessionResourceItem> resources = List.of(
                newFileResource("file_1", null),
                new SessionResourceItem("github_repository", null, null,
                        "https://github.com/acme/repo.git", "ghp_token", null, "main", null, null, null),
                new SessionResourceItem("memory_store", null, null,
                        null, null, null, null, "ms_store1", "read_only", "只读引用"));

        // when
        CreateSessionCommand command = assembler.toCreateCommand("u-1", "agent-a", "1", null,
                "{}", resources, null, "env-1", null);

        // then（按类型分派三工厂，字段各归其位）
        assertEquals(3, command.resources().size());
        assertEquals(SessionResource.FILE_TYPE, command.resources().get(0).type());
        SessionResource github = command.resources().get(1);
        assertEquals(SessionResource.GITHUB_REPO_TYPE, github.type());
        assertEquals("https://github.com/acme/repo.git", github.url());
        assertEquals("ghp_token", github.authorizationToken());
        assertEquals("main", github.checkout());
        SessionResource store = command.resources().get(2);
        assertEquals(SessionResource.MEMORY_STORE_TYPE, store.type());
        assertEquals("ms_store1", store.memoryStoreId());
        assertEquals("read_only", store.access());
        assertEquals("只读引用", store.instructions());
    }

    @Test
    void should_passEnvironmentAndVaultsAndSerializeEnvVars_when_toCreateCommand_given_fullMountRequest() {
        // given（携带环境 / 保管库 / 环境变量全量挂载入参）
        // when
        CreateSessionCommand command = assembler.toCreateCommand("u-1", "agent-a", "1", null,
                "{}", null, List.of("vault_1", "vault_2"), "env-9", Map.of("LOG_LEVEL", "debug"));

        // then（environmentId / vaultIds 透传；环境变量序列化为 JSON 文本）
        assertEquals("env-9", command.environmentId());
        assertEquals(List.of("vault_1", "vault_2"), command.vaultIds());
        assertEquals("{\"LOG_LEVEL\":\"debug\"}", command.environmentVariables());
    }

    @Test
    void should_keepEnvVarsNull_when_toCreateCommand_given_emptyEnvVarsMap() {
        // given（环境变量为空 Map：收敛为 null，由领域模型空白归一为 "{}"）
        // when
        CreateSessionCommand command = assembler.toCreateCommand("u-1", "agent-a", "1", null,
                "{}", null, null, "env-1", Map.of());

        // then
        assertEquals(null, command.environmentVariables());
    }

    @Test
    void should_throw_when_toCreateCommand_given_blankFileIdInResource() {
        // given（挂载文件 ID 为空 → 领域值对象不变量校验拒绝，接口层返回 400）
        List<SessionResourceItem> resources = List.of(newFileResource("", "mounts/a.txt"));

        // when & then
        assertThrows(IllegalArgumentException.class, () -> assembler.toCreateCommand("u-1", "agent-a", "1", null,
                "{}", resources, null, "env-1", null));
    }

    @Test
    void should_throw_when_toCreateCommand_given_invalidMountPath() {
        // given（挂载路径缺 mounts/ 前缀 → SessionResource.file 工厂不变量校验拒绝）
        List<SessionResourceItem> resources = List.of(newFileResource("file_1", "uploads/a.txt"));

        // when & then
        assertThrows(IllegalArgumentException.class, () -> assembler.toCreateCommand("u-1", "agent-a", "1", null,
                "{}", resources, null, "env-1", null));
    }

    @Test
    void should_throw_when_toCreateCommand_given_unsupportedResourceType() {
        // given（挂载资源类型非 file / github_repository / memory_store：显式 400 拒绝，不得静默按 file 处理）
        List<SessionResourceItem> resources = List.of(new SessionResourceItem("dataset", "file_1", null,
                null, null, null, null, null, null, null));

        // when & then（领域构造器类型不变量不得被架空）
        assertThrows(IllegalArgumentException.class, () -> assembler.toCreateCommand("u-1", "agent-a", "1", null,
                "{}", resources, null, "env-1", null));
    }

    @Test
    void should_resolveAgentReference_when_toAgentReference_given_bareStringId() {
        // given & when（字符串形态：无显式版本，由服务侧解析激活版本）
        AgentReference reference = assembler.toAgentReference("agent-a");

        // then
        assertEquals("agent-a", reference.agentId());
        assertEquals(null, reference.agentVersion());
    }

    @Test
    void should_resolveExplicitVersion_when_toAgentReference_given_objectForm() {
        // given & when（对象形态：{id, type:"agent", version} 显式版本原样归一为十进制文本）
        AgentReference reference = assembler.toAgentReference(
                Map.of("id", "agent-a", "type", "agent", "version", 3));

        // then
        assertEquals("agent-a", reference.agentId());
        assertEquals("3", reference.agentVersion());
    }

    @Test
    void should_treatVersionZeroAsActive_when_toAgentReference_given_objectForm() {
        // given & when（version=0 与省略等价：绑定激活版本；字符串数字同样接受）
        assertEquals(null, assembler.toAgentReference(
                Map.of("id", "agent-a", "type", "agent", "version", 0)).agentVersion());
        assertEquals(null, assembler.toAgentReference(
                Map.of("id", "agent-a", "type", "agent")).agentVersion());
        assertEquals("2", assembler.toAgentReference(
                Map.of("id", "agent-a", "type", "agent", "version", "2")).agentVersion());
    }

    @Test
    void should_throwBadRequest_when_toAgentReference_given_illegalForm() {
        // given & when & then（缺 type / 非字符串 id / 非法 version / 非法顶层形态均 400）
        assertThrows(IllegalArgumentException.class, () -> assembler.toAgentReference(null));
        assertThrows(IllegalArgumentException.class, () -> assembler.toAgentReference("   "));
        assertThrows(IllegalArgumentException.class, () -> assembler.toAgentReference(Map.of("id", "agent-a")));
        assertThrows(IllegalArgumentException.class, () -> assembler.toAgentReference(
                Map.of("id", 1, "type", "agent")));
        assertThrows(IllegalArgumentException.class, () -> assembler.toAgentReference(
                Map.of("id", "agent-a", "type", "agent", "version", -1)));
        assertThrows(IllegalArgumentException.class, () -> assembler.toAgentReference(
                Map.of("id", "agent-a", "type", "agent", "version", 1.5)));
        assertThrows(IllegalArgumentException.class, () -> assembler.toAgentReference(
                Map.of("id", "agent-a", "type", "agent", "version", "latest")));
        assertThrows(IllegalArgumentException.class, () -> assembler.toAgentReference(List.of("agent-a")));
    }

    @Test
    void should_passSilently_when_rejectLegacyCreateFields_given_allAbsent() {
        // given & when & then（全缺省即无遗留字段提交：不得抛错）
        assembler.rejectLegacyCreateFields(null, null, null, null);
    }

    @Test
    void should_throwBadRequest_when_rejectLegacyCreateFields_given_anyLegacyFieldSubmitted() {
        // given & when & then（遗留字段任一非空提交即 400，不得静默忽略）
        assertThrows(IllegalArgumentException.class,
                () -> assembler.rejectLegacyCreateFields(Map.of("type", "cloud"), null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> assembler.rejectLegacyCreateFields(null, List.of("vault_1"), null, null));
        assertThrows(IllegalArgumentException.class,
                () -> assembler.rejectLegacyCreateFields(null, null, List.of("ms_1"), null));
        assertThrows(IllegalArgumentException.class,
                () -> assembler.rejectLegacyCreateFields(null, null, null, 200));
    }

    @Test
    void should_buildSendCommand_when_toSendCommand_given_sessionAndMessage() {
        // when（runId 已随轮次模型删除，命令仅承载 sessionId + message）
        SendMessageCommand command = assembler.toSendCommand("s-1", "你好");

        // then
        assertEquals("s-1", command.sessionId());
        assertEquals("你好", command.message());
    }

    @Test
    void should_buildUpdateCommand_when_toUpdateCommand_given_titleOnlyRequest() {
        // given（title-only 更新：titlePresent=true；metadata / 环境变量缺省即 null=不改）
        // when
        UpdateSessionCommand command = assembler.toUpdateCommand("s-1", true, "新标题",
                null, null, null, null, null);

        // then
        assertEquals("s-1", command.sessionId());
        assertTrue(command.titlePresent());
        assertEquals("新标题", command.title());
        assertEquals(null, command.metadataJson());
        assertEquals(null, command.environmentVariablesJson());
    }

    @Test
    void should_keepTitleAbsent_when_toUpdateCommand_given_titleOmitted() {
        // given（title 省略：titlePresent=false 且 title 归一为 null，语义为不改原标题）
        // when
        UpdateSessionCommand command = assembler.toUpdateCommand("s-1", false, "新标题",
                null, null, null, null, null);

        // then
        assertEquals(false, command.titlePresent());
        assertEquals(null, command.title());
    }

    @Test
    void should_trimTitleAndPassJson_when_toUpdateCommand_given_fullRequest() {
        // given（title 两端去空白；metadata / 环境变量 JSON 由接口层序列化后透传）
        // when
        UpdateSessionCommand command = assembler.toUpdateCommand("s-1", true, "  标题  ", null, null, null,
                "{\"k\":\"v\"}", "{\"LOG_LEVEL\":\"debug\"}");

        // then
        assertEquals("标题", command.title());
        assertEquals("{\"k\":\"v\"}", command.metadataJson());
        assertEquals("{\"LOG_LEVEL\":\"debug\"}", command.environmentVariablesJson());
    }

    @Test
    void should_throwBadRequest_when_toUpdateCommand_given_immutableFieldsSubmitted() {
        // given & when & then（agent / environment_id / status 显式提交即 400，不得静默忽略）
        assertThrows(IllegalArgumentException.class, () -> assembler.toUpdateCommand("s-1", true, null,
                Map.of("id", "agent-a"), null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> assembler.toUpdateCommand("s-1", true, null,
                null, "env_1", null, null, null));
        assertThrows(IllegalArgumentException.class, () -> assembler.toUpdateCommand("s-1", true, null,
                null, null, "idle", null, null));
    }

    @Test
    void should_buildConfirmCommand_when_toResolveHumanConfirmationCommand_given_confirmedTrue() {
        // given（可携 tool_use_id 定位待确认工具调用）
        // when
        ResolveHumanConfirmationCommand command = assembler.toResolveHumanConfirmationCommand("s-1", true, "tc-1");

        // then
        assertEquals("s-1", command.sessionId());
        assertEquals(true, command.confirmed());
        assertEquals("tc-1", command.toolUseId());
    }

    @Test
    void should_buildRejectCommand_when_toResolveHumanConfirmationCommand_given_confirmedFalse() {
        // given（无定位键即不限定位）
        // when
        ResolveHumanConfirmationCommand command = assembler.toResolveHumanConfirmationCommand("s-1", false, null);

        // then
        assertEquals("s-1", command.sessionId());
        assertEquals(false, command.confirmed());
        assertEquals(null, command.toolUseId());
    }

    @Test
    void should_buildListQuery_when_toListQuery_given_defaultPagination() {
        // when（全缺省：limit 缺省 20、归档缺省排除、降序、无游标）
        ListSessionsQuery query = assembler.toListQuery("u-1", null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null);

        // then
        assertEquals("u-1", query.userId());
        assertEquals(null, query.agentId());
        assertEquals(null, query.agentVersion());
        assertEquals(null, query.deploymentId());
        assertEquals(null, query.memoryStoreId());
        assertEquals(List.of(), query.statuses());
        assertEquals(false, query.includeArchived());
        assertEquals(false, query.ascending());
        assertEquals(20, query.cursor().limit());
        assertEquals(null, query.cursor().afterId());
    }

    @Test
    void should_buildListQuery_when_toListQuery_given_filtersAndCursor() {
        // when（状态大小写不敏感；deployment / memory_store / 时间边界纳入；order=asc；after_id 游标）
        ListSessionsQuery query = assembler.toListQuery("u-1", "agent-a", "2", "dep_1", "ms_1",
                List.of("RUNNING", "idle"), true,
                "2026-09-01T00:00:00Z", null, null, "2026-09-30T00:00:00Z",
                "ASC", "50", null, "sess_abc", null);

        // then
        assertEquals("agent-a", query.agentId());
        assertEquals("2", query.agentVersion());
        assertEquals("dep_1", query.deploymentId());
        assertEquals("ms_1", query.memoryStoreId());
        assertEquals(List.of(AgentSessionStatus.RUNNING, AgentSessionStatus.IDLE), query.statuses());
        assertTrue(query.includeArchived());
        assertTrue(query.ascending());
        assertEquals(OffsetDateTime.parse("2026-09-01T00:00:00Z"), query.createdAtGt());
        assertEquals(OffsetDateTime.parse("2026-09-30T00:00:00Z"), query.createdAtLte());
        assertEquals(50, query.cursor().limit());
        assertEquals("sess_abc", query.cursor().afterId());
    }

    @Test
    void should_throw_when_toListQuery_given_blankUserId() {
        // when & then
        assertThrows(IllegalArgumentException.class, () -> assembler.toListQuery(" ", null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null));
    }

    @Test
    void should_throw_when_toListQuery_given_invalidStatus() {
        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> assembler.toListQuery("u-1", null, null, null, null, List.of("BOGUS"), null,
                        null, null, null, null, null, null, null, null, null));
    }

    @Test
    void should_throw_when_toListQuery_given_invalidTimestampOrOrder() {
        // given & when & then（非法 RFC 3339 时间与非 asc/desc 的 order 均 400）
        assertThrows(IllegalArgumentException.class,
                () -> assembler.toListQuery("u-1", null, null, null, null, null, null,
                        "2026-09-01", null, null, null, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> assembler.toListQuery("u-1", null, null, null, null, null, null,
                        null, null, null, null, "upward", null, null, null, null));
    }

    @Test
    void should_throw_when_toListQuery_given_limitOutOfRangeOrCursorConflict() {
        // given & when & then（limit 越界、after_id/before_id 同时提交、page 与游标互斥均 400）
        assertThrows(IllegalArgumentException.class,
                () -> assembler.toListQuery("u-1", null, null, null, null, null, null,
                        null, null, null, null, null, "101", null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> assembler.toListQuery("u-1", null, null, null, null, null, null,
                        null, null, null, null, null, "20", null, "sess_a", "sess_b"));
        assertThrows(IllegalArgumentException.class,
                () -> assembler.toListQuery("u-1", null, null, null, null, null, null,
                        null, null, null, null, null, "20", "sess_a", "sess_b", null));
    }

    @Test
    void should_resolvePageToAfterId_when_toListQuery_given_opaqueNextPage() {
        // when（不透明 page 游标归一为 after_id：客户端回传 next_page 即可续页）
        ListSessionsQuery query = assembler.toListQuery("u-1", null, null, null, null, null, null,
                null, null, null, null, null, null, "sess_tail", null, null);

        // then
        assertEquals("sess_tail", query.cursor().afterId());
        assertEquals(null, query.cursor().beforeId());
    }

    @Test
    void should_buildListEventsQuery_when_toListEventsQuery_given_threadScopeAndFilters() {
        // when（线程作用域 + 已知类型集合 + created_at 边界 + 降序）
        ListEventsQuery query = assembler.toListEventsQuery("s-1", "sthr_1",
                List.of("agent.message", "bogus.type"), "2026-09-01T00:00:00Z", null, null, null,
                "desc", "10", null, null, null);

        // then（未知类型静默剔除；线程归属与方向透传）
        assertEquals("s-1", query.sessionId());
        assertEquals("sthr_1", query.sessionThreadId());
        assertEquals(List.of(ChatEventType.AGENT_MESSAGE), query.types());
        assertEquals(false, query.ascending());
        assertEquals(OffsetDateTime.parse("2026-09-01T00:00:00Z"), query.createdAtGt());
        assertEquals(10, query.cursor().limit());
    }

    @Test
    void should_buildReplayQuery_when_toReplayQuery_given_positiveSequence() {
        // when
        ReplayQuery query = assembler.toReplayQuery("s-1", 10L, null);

        // then
        assertEquals("s-1", query.sessionId());
        assertEquals(10L, query.afterSequenceNum());
        assertEquals(List.of(), query.types());
    }

    @Test
    void should_keepTypesFilter_when_toReplayQuery_given_types() {
        // when
        ReplayQuery query = assembler.toReplayQuery("s-1", 3L, List.of("agent.message", "session.status_idle"));

        // then
        assertEquals(List.of("agent.message", "session.status_idle"), query.types());
    }

    @Test
    void should_clampNegativeSequence_when_toReplayQuery_given_negativeSequence() {
        // when
        ReplayQuery query = assembler.toReplayQuery("s-1", -5L, null);

        // then
        assertEquals(0L, query.afterSequenceNum());
    }

    @Test
    void should_returnEmptyList_when_toSessionResources_given_nullOrEmptyItems() {
        // given & when & then（null / 空列表收敛为空列表，供创建路径缺省不改）
        assertEquals(List.of(), assembler.toSessionResources(null));
        assertEquals(List.of(), assembler.toSessionResources(List.of()));
    }

    /** 构造文件挂载资源项（仅 file 相关字段，其余类型字段置空）。 */
    private static SessionResourceItem newFileResource(String fileId, String mountPath) {
        return new SessionResourceItem(SessionResource.FILE_TYPE, fileId, mountPath,
                null, null, null, null, null, null, null);
    }
}
