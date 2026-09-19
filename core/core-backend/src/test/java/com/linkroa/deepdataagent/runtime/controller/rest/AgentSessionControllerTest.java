package com.linkroa.deepdataagent.runtime.controller.rest;

import com.linkroa.deepdataagent.runtime.application.command.UpdateSessionCommand;
import com.linkroa.deepdataagent.runtime.application.query.ListSessionsQuery;
import com.linkroa.deepdataagent.runtime.application.service.AgentRuntimeQueryService;
import com.linkroa.deepdataagent.runtime.application.service.session.SessionLifecycleService;
import com.linkroa.deepdataagent.runtime.controller.request.CreateSessionRequest;
import com.linkroa.deepdataagent.runtime.controller.response.SessionCancelResponse;
import com.linkroa.deepdataagent.runtime.controller.response.SessionDeletedResponse;
import com.linkroa.deepdataagent.runtime.controller.response.SessionResourceResponse;
import com.linkroa.deepdataagent.runtime.controller.response.SessionResponse;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSession;
import com.linkroa.deepdataagent.runtime.domain.model.SessionResource;
import com.linkroa.deepdataagent.runtime.domain.model.enums.AgentSessionStatus;
import com.linkroa.deepdataagent.runtime.domain.model.enums.TurnPhase;
import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import com.linkroa.deepdataagent.shared.result.ApiResponse;
import com.linkroa.deepdataagent.shared.result.CursorPage;
import com.linkroa.deepdataagent.shared.security.AuthContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link AgentSessionController} 会话接口单测：锁定「过滤 / 游标参数透传 + Cursor 信封
 * {@code data / first_id / last_id / has_more} stable」契约、生命周期端点委派，
 * 以及追加挂载请求体的形态判别与归一（数组 / 单对象）、载荷类 400 先于会话查询的优先序。
 * <p>会话 userId 由 JWT {@code sub} 注入 {@link AuthContext}（数字 id 字符串化），
 * 受保护接口在未认证时返回 401。</p>
 */
@ExtendWith(MockitoExtension.class)
class AgentSessionControllerTest {

    /** 会话生命周期入口 mock（decompose-command-facade 4.4 改线：原门面 mock 拆分为目标服务 mock）。 */
    @Mock
    private SessionLifecycleService sessionLifecycleService;
    @Mock
    private AgentRuntimeQueryService queryService;

    @InjectMocks
    private AgentSessionController controller;

    @BeforeEach
    void setUpAuth() {
        AuthContext.setUserId(1L);
        // 真实序列化器：控制器侧请求体形态归一需要可用的 ObjectMapper（@Resource 字段不参与 @InjectMocks）
        ReflectionTestUtils.setField(controller, "objectMapper", new ObjectMapper());
    }

    @AfterEach
    void clearAuth() {
        AuthContext.clear();
    }

    // ==================== 会话列表：过滤 / 游标透传与信封直通 ====================

    @Test
    void should_returnCursorEnvelope_when_listSessions_given_filters() {
        // given（真实装配器构建查询：statuses 小写枚举化、limit 字符串归一）
        AgentSession session = sessionWithId(1L, "s-1");
        when(queryService.listSessions(any(ListSessionsQuery.class)))
                .thenReturn(CursorPage.of(List.of(session), true, AgentSession::sessionId));

        // when（agent_id + statuses[] 过滤；其余维度缺省）
        ApiResponse<CursorPage<SessionResponse>> response = controller.listSessions(
                "agent-a", null, null, null, List.of("IDLE"), null,
                null, null, null, null, null, null, null, null, null);

        // then
        assertTrue(response.success());
        assertEquals(1, response.data().data().size());
        assertEquals("s-1", response.data().firstId());
        assertEquals("s-1", response.data().lastId());
        assertTrue(response.data().hasMore());
        ListSessionsQuery query = captureSessionQuery();
        assertEquals("1", query.userId());
        assertEquals("agent-a", query.agentId());
        assertEquals(List.of(AgentSessionStatus.IDLE), query.statuses());
    }

    @Test
    void should_delegateFiltersAndCursor_when_listSessions_given_queryParams() {
        // given（agent_version / deployment_id / memory_store_id / include_archived / 时间边界 / order 全维度透传）
        when(queryService.listSessions(any(ListSessionsQuery.class)))
                .thenReturn(CursorPage.of(List.of(), false, AgentSession::sessionId));

        // when
        controller.listSessions("agent-a", "3", "dep_1", "ms_1", List.of("running"), true,
                "2026-09-01T00:00:00Z", "2026-09-02T00:00:00Z", "2026-09-03T00:00:00Z", "2026-09-04T00:00:00Z",
                "asc", "50", null, "sess_a", null);

        // then（过滤集合严格对齐公开契约，无 environment_id / metadata）
        ListSessionsQuery query = captureSessionQuery();
        assertEquals("1", query.userId());
        assertEquals("agent-a", query.agentId());
        assertEquals("3", query.agentVersion());
        assertEquals("dep_1", query.deploymentId());
        assertEquals("ms_1", query.memoryStoreId());
        assertEquals(List.of(AgentSessionStatus.RUNNING), query.statuses());
        assertTrue(query.includeArchived());
        assertTrue(query.ascending());
        assertEquals(OffsetDateTime.parse("2026-09-01T00:00:00Z"), query.createdAtGt());
        assertEquals(OffsetDateTime.parse("2026-09-02T00:00:00Z"), query.createdAtGte());
        assertEquals(OffsetDateTime.parse("2026-09-03T00:00:00Z"), query.createdAtLt());
        assertEquals(OffsetDateTime.parse("2026-09-04T00:00:00Z"), query.createdAtLte());
        assertEquals(50, query.cursor().limit());
        assertEquals("sess_a", query.cursor().afterId());
        assertNull(query.cursor().beforeId());
    }

    @Test
    void should_returnEmptyCursorEnvelope_when_listSessions_given_lastPage() {
        // given（无过滤：userId 由认证上下文注入；游标缺省断点）
        when(queryService.listSessions(any(ListSessionsQuery.class)))
                .thenReturn(CursorPage.of(List.of(), false, AgentSession::sessionId));

        // when
        ApiResponse<CursorPage<SessionResponse>> response = controller.listSessions(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

        // then（空页：first/last 为 null；limit 缺省 20）
        assertTrue(response.success());
        assertEquals(0, response.data().data().size());
        assertNull(response.data().lastId());
        ListSessionsQuery query = captureSessionQuery();
        assertEquals(20, query.cursor().limit());
        assertNull(query.cursor().afterId());
        assertNull(query.cursor().beforeId());
    }

    // ==================== 更新会话 ====================

    @Test
    void should_delegateUpdateCommand_when_updateSession_given_titleOnlyRequest() {
        // given（title-only：metadata / 环境变量缺省即 null=不改，不进 JSON 序列化）
        JsonNode body = json("{\"title\":\"新标题\"}");
        AgentSession session = sessionWithId(1L, "s-1");
        when(sessionLifecycleService.updateSession(any(UpdateSessionCommand.class))).thenReturn(session);

        // when
        ApiResponse<SessionResponse> response = controller.updateSession("s-1", body);

        // then（titlePresent=true 且 metadata / 环境变量保持 null=不改）
        ArgumentCaptor<UpdateSessionCommand> captor = ArgumentCaptor.forClass(UpdateSessionCommand.class);
        verify(sessionLifecycleService).updateSession(captor.capture());
        UpdateSessionCommand command = captor.getValue();
        assertEquals("s-1", command.sessionId());
        assertTrue(command.titlePresent());
        assertEquals("新标题", command.title());
        assertNull(command.metadataJson());
        assertNull(command.environmentVariablesJson());
        assertTrue(response.success());
    }

    @Test
    void should_throwBadRequest_when_updateSession_given_nonUpdatableField() {
        // given（agent / environment_id / status 为不可更新属性，装配器守卫 400）
        JsonNode body = json("{\"status\":\"running\"}");

        // when & then（非空不可更新属性 → 400，先于任何应用服务调用）
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> controller.updateSession("s-1", body));
        assertTrue(ex.getMessage().contains("不可更新"), ex.getMessage());
        verifyNoInteractions(sessionLifecycleService);
    }

    // ==================== 取消 / 删除 ====================

    @Test
    void should_returnAcceptedReceipt_when_cancelSession_given_activeTurn() {
        // given（存在活跃 turn：取消已投递）
        when(sessionLifecycleService.cancelSession("s-1")).thenReturn(true);

        // when
        ResponseEntity<ApiResponse<SessionCancelResponse>> response = controller.cancelSession("s-1");

        // then（HTTP 202 + 固定回执 {id, type:session, status:canceling}）
        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertEquals("s-1", response.getBody().data().id());
        assertEquals("session", response.getBody().data().type());
        assertEquals("canceling", response.getBody().data().status());
        verify(sessionLifecycleService).cancelSession("s-1");
    }

    @Test
    void should_returnOkReceipt_when_cancelSession_given_noActiveTurn() {
        // given（idle / terminated：幂等空操作）
        when(sessionLifecycleService.cancelSession("s-1")).thenReturn(false);

        // when
        ResponseEntity<ApiResponse<SessionCancelResponse>> response = controller.cancelSession("s-1");

        // then（HTTP 200，响应体与 202 逐字相同）
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("canceling", response.getBody().data().status());
    }

    @Test
    void should_returnDeletedEnvelope_when_deleteSession_given_sessionId() {
        // when
        ApiResponse<SessionDeletedResponse> response = controller.deleteSession("s-1");

        // then（删除走物理清理链而非终止）
        verify(sessionLifecycleService).deleteSession("s-1");
        assertEquals("s-1", response.data().id());
        assertEquals("session_deleted", response.data().type());
    }

    // ==================== 追加挂载：响应装配 ====================

    @Test
    void should_appendResourcesAndReturnItems_when_appendResources_given_envelopeBody() {
        // given（信封形态原始 JSON：控制器判别形态并归一为资源项后下传应用服务）
        JsonNode body = new ObjectMapper()
                .readTree("{\"resources\":[{\"type\":\"file\",\"file_id\":\"file_1\"}]}");
        SessionResource appended = SessionResource.file("file_1", null);
        when(sessionLifecycleService.appendResources(eq("s-1"), argThat(list ->
                list.size() == 1 && "file_1".equals(list.get(0).fileId())))).thenReturn(List.of(appended));
        AgentSession session = sessionWithId(1L, "s-1");
        when(queryService.getSession("s-1")).thenReturn(session);

        // when
        ApiResponse<List<SessionResourceResponse>> response = controller.appendResources("s-1", body);

        // then（响应项携带 sesr_ 资源 ID；挂载时刻取会话 updated_at）
        assertTrue(response.success());
        assertEquals(1, response.data().size());
        assertEquals(appended.id(), response.data().get(0).id());
        assertEquals("file_1", response.data().get(0).file_id());
        assertEquals(session.updatedAt(), response.data().get(0).updated_at());
    }

    // ==================== 追加挂载：请求体形态判别与归一（自装配器上移控制器） ====================

    @Test
    void should_appendAll_when_appendResources_given_resourcesArray() {
        // given（数组形态：snake_case 字段直映射，省略 mount_path 由领域工厂缺省补全）
        JsonNode body = json("""
                {"resources":[
                  {"type":"file","file_id":"file_1","mount_path":"mounts/a.txt"},
                  {"type":"file","file_id":"file_2"}
                ]}""");
        stubAppendAccepted();

        // when
        controller.appendResources("s-1", body);

        // then（保序装配为领域值对象，自动生成 sesr_ 资源 ID）
        List<SessionResource> resources = captureAppendBatch();
        assertEquals(2, resources.size());
        assertEquals("file_1", resources.get(0).fileId());
        assertEquals("mounts/a.txt", resources.get(0).mountPath());
        assertEquals(SessionResource.DEFAULT_MOUNT_PATH_PREFIX + "file_2", resources.get(1).mountPath());
        assertTrue(resources.get(0).id().startsWith(SessionResource.RESOURCE_ID_PREFIX));
    }

    @Test
    void should_returnBadRequest400_when_appendResources_given_singleObjectBodyWithoutResourcesField() {
        // given（D17 收紧：顶层即单资源对象，不再兜底成长度 1 的批次）
        JsonNode body = json("{\"type\":\"file\",\"file_id\":\"file_9\"}");

        // when & then（400 语义异常，消息明确指向数组结构要求，且不触达应用服务 ⇒ 会话零变更、不物化）
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> controller.appendResources("s-1", body));
        assertTrue(ex.getMessage().contains("请求体必须为 {\"resources\":[...]} 数组结构"));
        verifyNoInteractions(sessionLifecycleService);
    }

    @Test
    void should_throwBadRequest_when_appendResources_given_nonObjectBody() {
        // given（请求体为 JSON 数组 → 400）
        JsonNode body = json("[{\"type\":\"file\",\"file_id\":\"file_1\"}]");

        // when & then
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> controller.appendResources("s-1", body));
        assertTrue(ex.getMessage().contains("请求体必须为 {\"resources\":[...]} 数组结构"));
        verifyNoInteractions(sessionLifecycleService);
    }

    @Test
    void should_throwBadRequest_when_appendResources_given_nullBody() {
        // when & then（空请求体 → 400）
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> controller.appendResources("s-1", null));
        assertTrue(ex.getMessage().contains("请求体必须为 {\"resources\":[...]} 数组结构"));
        verifyNoInteractions(sessionLifecycleService);
    }

    @Test
    void should_throwBadRequest_when_appendResources_given_nonArrayResourcesField() {
        // given（resources 字段非数组 → 400）
        JsonNode body = json("{\"resources\":\"file_1\"}");

        // when & then
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> controller.appendResources("s-1", body));
        assertTrue(ex.getMessage().contains("请求体必须为 {\"resources\":[...]} 数组结构"));
        verifyNoInteractions(sessionLifecycleService);
    }

    @Test
    void should_returnBadRequest400_when_appendResources_given_emptyResourcesArray() {
        // given（D17 收紧：空数组在控制器形态归一阶段即拒绝，不再下传服务首行判空）
        JsonNode body = json("{\"resources\":[]}");

        // when & then（400 语义异常且不触达应用服务 ⇒ 与会话侧「空批次 400」守卫不冲突、不重复计数）
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> controller.appendResources("s-1", body));
        assertTrue(ex.getMessage().contains("请求体必须为 {\"resources\":[...]} 数组结构"));
        verifyNoInteractions(sessionLifecycleService);
    }

    @Test
    void should_throwBadRequest_when_appendResources_given_nonObjectResourceItem() {
        // given（数组项为字符串而非对象 → 400）
        JsonNode body = json("{\"resources\":[\"file_1\"]}");

        // when & then
        assertThrows(IllegalArgumentException.class, () -> controller.appendResources("s-1", body));
        verifyNoInteractions(sessionLifecycleService);
    }

    @Test
    void should_throwBadRequest_when_appendResources_given_unsupportedResourceType() {
        // given（不支持的资源类型：装配器按类型分派拒绝 → 400）
        JsonNode body = json("{\"resources\":[{\"type\":\"dataset\",\"file_id\":\"file_1\"}]}");

        // when & then
        assertThrows(IllegalArgumentException.class, () -> controller.appendResources("s-1", body));
        verifyNoInteractions(sessionLifecycleService);
    }

    // ==================== 400 / 404 优先序 characterization（tasks 2.4） ====================

    @Test
    void should_returnBadRequest400_when_appendResources_given_sessionNotFoundAndMalformedPayload() {
        // given（会话不存在场景下服务侧必抛会话类错误：载荷形态 400 在控制器归一阶段即先行拒绝）
        lenient().when(sessionLifecycleService.appendResources(eq("s-1"), anyList()))
                .thenThrow(new DeepDataAgentException("DEEP_AGENT_SESSION_NOT_FOUND: 会话不存在"));
        JsonNode body = json("{\"resources\":[\"file_1\"]}");

        // when & then（非 file 形态的非法载荷：400 语义异常，且会话查询不可达）
        assertThrows(IllegalArgumentException.class, () -> controller.appendResources("s-1", body));
        verifyNoInteractions(sessionLifecycleService);
    }

    // ==================== 创建会话（7.7 self_hosted 边界） ====================

    @Test
    void should_delegateCreateAndEchoSession_when_createSession_given_cloudEnvironmentRequest() {
        // given（environment_id 协议必填；执行平面守卫由应用服务经环境契约解析承担）
        AgentSession session = sessionWithId(1L, "s-1");
        when(sessionLifecycleService.createSession(argThat(command ->
                "1".equals(command.userId()) && "agent-a".equals(command.agentId())
                        && "env_1".equals(command.environmentId())
                        && "{}".equals(command.metadata())
                        && command.resources().isEmpty() && command.vaultIds().isEmpty())))
                .thenReturn(session);
        CreateSessionRequest request = new CreateSessionRequest("agent-a", "env_1", "会话",
                null, null, null, null, null, null, null, null);

        // when
        ApiResponse<SessionResponse> response = controller.createSession(request);

        // then（成功装配回显，Agent 快照经查询服务嵌入、台账缺行降级安全）
        assertTrue(response.success());
        assertNotNull(response.data());
        verify(queryService).agentSnapshot(session);
    }

    @Test
    void should_propagateBadRequest_when_createSession_given_selfHostedEnvironment() {
        // given（self_hosted 执行平面：应用服务 400 语义异常必须透传，不得吞落 200）
        when(sessionLifecycleService.createSession(any()))
                .thenThrow(new IllegalArgumentException("不支持的执行平面: 环境类型为 self_hosted"));
        CreateSessionRequest request = new CreateSessionRequest("agent-a", "env_sh", "会话",
                null, null, null, null, null, null, null, null);

        // when // then（GlobalExceptionHandler 统一转 400 invalid_request_error）
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> controller.createSession(request));
        assertTrue(ex.getMessage().contains("不支持的执行平面"));
        verify(queryService, never()).agentSnapshot(any());
    }

    @Test
    void should_passExplicitVersion_when_createSession_given_agentReferenceObject() {
        // given（agent 对象形态 + 显式 version：归一为十进制文本下发，不再硬编码 null）
        AgentSession session = sessionWithId(1L, "s-1");
        when(sessionLifecycleService.createSession(argThat(command ->
                "agent-a".equals(command.agentId()) && "3".equals(command.agentVersion()))))
                .thenReturn(session);
        CreateSessionRequest request = new CreateSessionRequest(
                Map.of("id", "agent-a", "type", "agent", "version", 3), "env_1", "会话",
                null, null, null, null, null, null, null, null);

        // when
        ApiResponse<SessionResponse> response = controller.createSession(request);

        // then
        assertTrue(response.success());
    }

    @Test
    void should_rejectBadRequest_when_createSession_given_legacyEnvironmentField() {
        // given（已废止 environment 遗留字段非空提交：400 且不触达会话服务）
        CreateSessionRequest request = new CreateSessionRequest("agent-a", "env_1", "会话",
                null, null, null, null, Map.of("type", "cloud"), null, null, null);

        // when & then
        assertThrows(IllegalArgumentException.class, () -> controller.createSession(request));
        verifyNoInteractions(sessionLifecycleService);
    }

    @Test
    void should_rejectBadRequest_when_createSession_given_legacyVaultsAndMemoryStoreFields() {
        // given（vaults / memory_store_ids 遗留字段：挂载语义已迁至 vault_ids[] 与 resources[].memory_store）
        CreateSessionRequest legacyVaults = new CreateSessionRequest("agent-a", "env_1", "会话",
                null, null, null, null, null, List.of("vault_1"), null, null);
        CreateSessionRequest legacyMemoryStores = new CreateSessionRequest("agent-a", "env_1", "会话",
                null, null, null, null, null, null, List.of("ms_1"), null);

        // when & then
        assertThrows(IllegalArgumentException.class, () -> controller.createSession(legacyVaults));
        assertThrows(IllegalArgumentException.class, () -> controller.createSession(legacyMemoryStores));
        verifyNoInteractions(sessionLifecycleService);
    }

    // ==================== 装配工具 ====================

    private AgentSession sessionWithId(Long id, String sessionId) {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-22T10:00:00+08:00");
        // 双字段状态机 22 参 restore：idle / idle，无挂载字段（sandboxId / workspaceId 组件已删除）
        return AgentSession.restore(id, sessionId, "1", "agent-a", "1.0.0",
                AgentSessionStatus.IDLE, TurnPhase.IDLE, "{}", null, null,
                List.of(), List.of(), "{}", List.of(), null, null,
                now, null, now, now, null, null);
    }

    /** 解析请求体原始 JSON（Jackson 3 非受检异常，直传）。 */
    private static JsonNode json(String raw) {
        return new ObjectMapper().readTree(raw);
    }

    /** 让追加挂载请求走通到响应装配（服务侧返回空批次、会话查询可用）。 */
    private void stubAppendAccepted() {
        when(sessionLifecycleService.appendResources(eq("s-1"), anyList())).thenReturn(List.of());
        when(queryService.getSession("s-1")).thenReturn(sessionWithId(1L, "s-1"));
    }

    /** 捕获下传应用服务的会话列表查询。 */
    private ListSessionsQuery captureSessionQuery() {
        ArgumentCaptor<ListSessionsQuery> captor = ArgumentCaptor.forClass(ListSessionsQuery.class);
        verify(queryService).listSessions(captor.capture());
        return captor.getValue();
    }

    /** 捕获下传应用服务的追加挂载批次。 */
    @SuppressWarnings("unchecked")
    private List<SessionResource> captureAppendBatch() {
        ArgumentCaptor<List<SessionResource>> captor = ArgumentCaptor.forClass(List.class);
        verify(sessionLifecycleService).appendResources(eq("s-1"), captor.capture());
        return captor.getValue();
    }
}