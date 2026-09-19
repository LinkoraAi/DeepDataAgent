package com.linkroa.deepdataagent.runtime.controller.rest;

import com.linkroa.deepdataagent.runtime.application.command.AgentReference;
import com.linkroa.deepdataagent.runtime.application.command.SessionResourceItem;
import com.linkroa.deepdataagent.runtime.application.convert.AgentRuntimeCommandConvert;
import com.linkroa.deepdataagent.runtime.application.service.AgentRuntimeQueryService;
import com.linkroa.deepdataagent.runtime.application.service.session.SessionLifecycleService;
import com.linkroa.deepdataagent.runtime.controller.request.CreateSessionRequest;
import com.linkroa.deepdataagent.runtime.controller.request.UpdateSessionRequest;
import com.linkroa.deepdataagent.runtime.controller.request.UpdateSessionResourceRequest;
import com.linkroa.deepdataagent.runtime.controller.convert.AgentRuntimeResponseConvert;
import com.linkroa.deepdataagent.runtime.controller.response.SessionCancelResponse;
import com.linkroa.deepdataagent.runtime.controller.response.SessionDeletedResponse;
import com.linkroa.deepdataagent.runtime.controller.response.SessionResponse;
import com.linkroa.deepdataagent.runtime.controller.response.SessionResourceResponse;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSession;
import com.linkroa.deepdataagent.runtime.domain.model.SessionResource;
import com.linkroa.deepdataagent.shared.constant.api.ApiVersionConstants;
import com.linkroa.deepdataagent.shared.result.ApiResponse;
import com.linkroa.deepdataagent.shared.result.CursorPage;
import com.linkroa.deepdataagent.shared.result.CursorPageParams;
import com.linkroa.deepdataagent.shared.security.AuthContext;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Agent 会话管理 REST 控制器（前缀 {@code /api/v1/cloud/sessions}，Managed Agents Session 契约接口）。
 * <p>会话身份 userId 由 JWT {@code sub} 注入（{@link AuthContext}，会话经 Agent 间接关联工作区、
 * 无独立 workspace_id 归属）。列表按创建时间降序游标分页（shared/api-conventions Cursor 约定，
 * 支持 {@code agent_id / agent_version / deployment_id / memory_store_id / statuses[] /
 * include_archived / created_at[gt|gte|lt|lte] / order} 过滤）；
 * 生命周期端点覆盖 update（可变属性）/ archive / cancel / delete（物理删除链）；
 * 资源管理端点覆盖 list / get / update（GitHub 令牌轮换）/ delete（仅 file 可移除）。</p>
 */
@RestController
@RequestMapping(path = "/cloud/sessions", version = ApiVersionConstants.CURRENT_API_VERSION)
public class AgentSessionController {

    /** 会话生命周期入口服务（decompose-command-facade 4.4 改线：原门面一行委托直连目标服务）。 */
    @Resource
    private SessionLifecycleService sessionLifecycleService;
    @Resource
    private AgentRuntimeQueryService queryService;
    @Resource
    private ObjectMapper objectMapper;

    /**
     * 创建会话（对齐 {@code POST /sessions}）：绑定 agent 指定版本（省略 / {@code 0} = 激活版本）与运行环境。
     * <p>控制器只做字段映射（挂载资源项以 {@link SessionResourceItem} 原样承载后交装配器按类型分派），
     * 环境 / 保管库 / 挂载的存在性与归属校验在应用服务。
     * {@code agent} 接受字符串 ID 或 {@code {id, type:"agent", version?}} 对象两种形态；
     * 已废止遗留字段（{@code environment} / {@code vaults} / {@code memory_store_ids} /
     * {@code delta_flush_interval_ms}）任一非空提交即 400，先于其余装配执行。</p>
     */
    @PostMapping
    public ApiResponse<SessionResponse> createSession(@Valid @RequestBody CreateSessionRequest request) {
        // 遗留字段守卫先行：非空提交即 400（不得静默忽略，否则「创建成功但挂载丢失」）
        AgentRuntimeCommandConvert.INSTANCE.rejectLegacyCreateFields(request.environment(), request.vaults(),
                request.memory_store_ids(), request.delta_flush_interval_ms());
        // 对齐 Managed Agents：agent 支持字符串 ID 或 {id, type:"agent", version?} 对象，
        // 省略版本号由服务端解析激活版本（active_version）物化；
        // userId 由 JWT sub 注入（无状态认证过滤器已保障受保护接口必带有效凭证）
        AgentReference reference = AgentRuntimeCommandConvert.INSTANCE.toAgentReference(request.agent());
        AgentSession session = sessionLifecycleService.createSession(AgentRuntimeCommandConvert.INSTANCE.toCreateCommand(
                currentUserId(), reference.agentId(), reference.agentVersion(), request.title(),
                toJson(request.metadata()), request.resources(), request.vault_ids(), request.environment_id(),
                request.environment_variables()));
        return ApiResponse.success(toSessionResponse(session));
    }

    /** 会话响应装配：嵌入完整 Agent 快照（应用层经快照契约裁剪，台账缺行降级摘要）。 */
    private SessionResponse toSessionResponse(AgentSession session) {
        return AgentRuntimeResponseConvert.INSTANCE.toSessionResponse(session, queryService.agentSnapshot(session));
    }

    /**
     * 会话详情（对齐 {@code GET /sessions/{session_id}}）。
     */
    @GetMapping("/{sessionId}")
    public ApiResponse<SessionResponse> getSession(@PathVariable String sessionId) {
        return ApiResponse.success(toSessionResponse(queryService.getSession(sessionId)));
    }

    /**
     * 游标分页列出会话（对齐 {@code GET /sessions}，创建时间降序缺省）。
     * <p>过滤集合严格对齐公开契约：{@code agent_id / agent_version / deployment_id /
     * memory_store_id / statuses[] / include_archived / created_at[gt|gte|lt|lte] / order}
     * （{@code environment_id} 与 {@code metadata} 非受支持参数）；
     * {@code limit / page / after_id / before_id} 为统一 Cursor 约定
     * （{@code page} 为响应 {@code next_page} 回传的不透明游标，与 after_id / before_id 互斥
     * → 同传 400；游标会话不存在 / 非本人 → 404）。</p>
     */
    @GetMapping
    public ApiResponse<CursorPage<SessionResponse>> listSessions(
            @RequestParam(name = "agent_id", required = false) String agentId,
            @RequestParam(name = "agent_version", required = false) String agentVersion,
            @RequestParam(name = "deployment_id", required = false) String deploymentId,
            @RequestParam(name = "memory_store_id", required = false) String memoryStoreId,
            @RequestParam(name = "statuses[]", required = false) List<String> statuses,
            @RequestParam(name = "include_archived", required = false) Boolean includeArchived,
            @RequestParam(name = "created_at[gt]", required = false) String createdAtGt,
            @RequestParam(name = "created_at[gte]", required = false) String createdAtGte,
            @RequestParam(name = "created_at[lt]", required = false) String createdAtLt,
            @RequestParam(name = "created_at[lte]", required = false) String createdAtLte,
            @RequestParam(name = "order", required = false) String order,
            @RequestParam(name = "limit", required = false) String limit,
            @RequestParam(name = "page", required = false) String page,
            @RequestParam(name = "after_id", required = false) String afterId,
            @RequestParam(name = "before_id", required = false) String beforeId
    ) {
        CursorPage<AgentSession> result = queryService.listSessions(
                AgentRuntimeCommandConvert.INSTANCE.toListQuery(currentUserId(), agentId, agentVersion,
                        deploymentId, memoryStoreId, statuses, includeArchived,
                        createdAtGt, createdAtGte, createdAtLt, createdAtLte,
                        order, limit, page, afterId, beforeId));
        return ApiResponse.success(result.map(this::toSessionResponse));
    }

    /**
     * 更新会话（对齐 {@code POST /sessions/{session_id}}）：title 省略不改 / 传 {@code null} 清空、
     * metadata 为 patch（值为 {@code null} 的键删除，顶层缺省为 no-op）、environment_variables
     * 整体替换；显式提交 {@code agent / environment_id / status} 非空值 → 400；
     * 已归档会话 → 404、已终止会话 → 409。
     * <p>以原始 JSON 节点接收入参：仅凭 {@code title} 键<b>是否存在</b>区分「省略不改」与
     * 「传 null 清空」（强类型反序列化无法区分二者）。</p>
     */
    @PostMapping("/{sessionId}")
    public ApiResponse<SessionResponse> updateSession(@PathVariable String sessionId,
                                                      @RequestBody JsonNode body) {
        UpdateSessionRequest request = toUpdateRequest(body);
        boolean titlePresent = body != null && body.isObject() && body.has("title");
        AgentSession session = sessionLifecycleService.updateSession(AgentRuntimeCommandConvert.INSTANCE
                .toUpdateCommand(sessionId, titlePresent, request.title(), request.agent(),
                        request.environment_id(), request.status(),
                        toJsonNullable(request.metadata()),
                        toJsonNullable(request.environment_variables())));
        return ApiResponse.success(toSessionResponse(session));
    }

    /** 更新请求体 JSON 节点 → 类型化请求对象（非对象形态 / 字段类型不符 → 400）。 */
    private UpdateSessionRequest toUpdateRequest(JsonNode body) {
        if (body == null || body.isNull() || !body.isObject()) {
            throw new IllegalArgumentException("更新请求体必须为 JSON 对象");
        }
        try {
            return objectMapper.convertValue(body, UpdateSessionRequest.class);
        } catch (IllegalArgumentException | JacksonException ex) {
            throw new IllegalArgumentException("更新请求体解析失败: " + ex.getMessage(), ex);
        }
    }

    /**
     * 归档会话（对齐 {@code POST /sessions/{session_id}/archive}）：仅写入 {@code archived_at}
     * 时间戳（{@code status} 保持原值，归档为正交维度、不产生归档状态事件），
     * 并中断在跑执行、释放订阅与租约。
     */
    @PostMapping("/{sessionId}/archive")
    public ApiResponse<SessionResponse> archiveSession(@PathVariable String sessionId) {
        sessionLifecycleService.archiveSession(sessionId);
        return ApiResponse.success(toSessionResponse(queryService.getSession(sessionId)));
    }

    /**
     * 取消当前执行（对齐 {@code POST /sessions/{session_id}/cancel}）：存在活跃 turn 时投递取消并
     * 返回 <b>HTTP 202</b>，idle / terminated（无活跃 turn）为幂等空操作返回 <b>HTTP 200</b>，
     * 两种 2xx 响应体相同（固定回执 {@code {id, type:"session", status:"canceling"}}）；
     * 会话不存在或已归档返回 404。取消过程零状态事件。
     */
    @PostMapping("/{sessionId}/cancel")
    public ResponseEntity<ApiResponse<SessionCancelResponse>> cancelSession(@PathVariable String sessionId) {
        boolean active = sessionLifecycleService.cancelSession(sessionId);
        HttpStatus status = active ? HttpStatus.ACCEPTED : HttpStatus.OK;
        return ResponseEntity.status(status).body(ApiResponse.success(new SessionCancelResponse(sessionId)));
    }

    /**
     * 创建后追加挂载文件（对齐 {@code POST /sessions/{session_id}/resources}，本期仅 file 类型）。
     * <p>请求体<b>仅接受</b> {@code {"resources":[...]}} 数组结构（长度 ≥1，D17 收紧：单资源对象形态
     * 不再兜底）——形态判别与归一在本控制器完成（产出 {@link SessionResourceItem} 列表），
     * 装配器只做按类型分派，应用服务入口首行做批次非空校验；单个非法条目整批 400 无部分变更
     * （且不触达会话、不留下已物化的宿主副本），
     * 重复挂载 / 文件未就绪 / 会话已归档或终止 → 409，挂载总量超 500MB → 400。</p>
     */
    @PostMapping("/{sessionId}/resources")
    public ApiResponse<List<SessionResourceResponse>> appendResources(
            @PathVariable String sessionId,
            @RequestBody JsonNode body) {
        List<SessionResource> appended = sessionLifecycleService.appendResources(sessionId,
                AgentRuntimeCommandConvert.INSTANCE.toSessionResources(toResourceItems(body)));
        AgentSession session = queryService.getSession(sessionId);
        return ApiResponse.success(AgentRuntimeResponseConvert.INSTANCE
                .toResourceResponses(appended, session.updatedAt()));
    }

    /**
     * 追加挂载请求体 → 已归一的资源项列表（形态判别：仅接受 {@code {"resources":[...]}} 数组结构）。
     * <p>{@code resources} 缺失 / 非数组 / 空数组，以及顶层即单资源对象的形态，一律 400 拒绝，
     * MUST NOT 兜底成长度 1 的批次（会话资源零变更、不触达物化）；数组内混入非对象元素同样整批拒绝。</p>
     *
     * @param body 请求体原始 JSON 节点
     * @return 已归一的资源项列表（保序，非空）
     */
    private List<SessionResourceItem> toResourceItems(JsonNode body) {
        JsonNode resourcesNode = body == null || !body.isObject() ? null : body.get("resources");
        if (resourcesNode == null || resourcesNode.isNull() || !resourcesNode.isArray() || resourcesNode.size() == 0) {
            throw new IllegalArgumentException("追加挂载请求体必须为 {\"resources\":[...]} 数组结构（至少一项）");
        }
        List<SessionResourceItem> items = new ArrayList<>();
        resourcesNode.forEach(node -> items.add(toResourceItem(node)));
        return items;
    }

    /** JSON 节点 → 单资源项（非对象节点拒绝；snake_case 字段直映射）。 */
    private SessionResourceItem toResourceItem(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) {
            throw new IllegalArgumentException("挂载资源项必须为 JSON 对象");
        }
        try {
            return objectMapper.convertValue(node, SessionResourceItem.class);
        } catch (IllegalArgumentException | JacksonException ex) {
            throw new IllegalArgumentException("挂载资源项解析失败: " + ex.getMessage(), ex);
        }
    }

    /**
     * 游标分页列出挂载资源（对齐资源管理 {@code GET /sessions/{session_id}/resources}）。
     * <p>资源按挂载顺序内存切片；GitHub 令牌只写不读，回显不含 authorization_token。</p>
     */
    @GetMapping("/{sessionId}/resources")
    public ApiResponse<CursorPage<Map<String, Object>>> listResources(
            @PathVariable String sessionId,
            @RequestParam(name = "limit", required = false) String limit,
            @RequestParam(name = "after_id", required = false) String afterId,
            @RequestParam(name = "before_id", required = false) String beforeId
    ) {
        AgentSession session = queryService.getSession(sessionId);
        CursorPage<SessionResource> page = queryService.listResources(
                sessionId, CursorPageParams.parse(limit, afterId, beforeId));
        return ApiResponse.success(page.map(resource ->
                AgentRuntimeResponseConvert.INSTANCE.toResourceDetail(resource, session.updatedAt())));
    }

    /**
     * 查询单条挂载资源（对齐资源管理 {@code GET /sessions/{session_id}/resources/{resource_id}}，
     * 含 GitHub 仓库资源的只读元数据；未命中 → 404）。
     */
    @GetMapping("/{sessionId}/resources/{resourceId}")
    public ApiResponse<Map<String, Object>> getResource(@PathVariable String sessionId,
                                                        @PathVariable String resourceId) {
        AgentSession session = queryService.getSession(sessionId);
        SessionResource resource = queryService.getResource(sessionId, resourceId);
        return ApiResponse.success(AgentRuntimeResponseConvert.INSTANCE
                .toResourceDetail(resource, session.updatedAt()));
    }

    /**
     * 轮换 GitHub 仓库挂载令牌（对齐资源管理 {@code PATCH /sessions/{session_id}/resources/{resource_id}}）：
     * 仅 {@code github_repository} 资源支持、新令牌必须非空；响应与日志不泄露新旧令牌。
     */
    @PatchMapping("/{sessionId}/resources/{resourceId}")
    public ApiResponse<Map<String, Object>> updateResource(@PathVariable String sessionId,
                                                           @PathVariable String resourceId,
                                                           @RequestBody UpdateSessionResourceRequest request) {
        sessionLifecycleService.rotateResourceToken(sessionId, resourceId, request.authorization_token());
        AgentSession session = queryService.getSession(sessionId);
        SessionResource rotated = queryService.getResource(sessionId, resourceId);
        return ApiResponse.success(AgentRuntimeResponseConvert.INSTANCE
                .toResourceDetail(rotated, session.updatedAt()));
    }

    /**
     * 移除挂载资源（对齐资源管理 {@code DELETE /sessions/{session_id}/resources/{resource_id}}）：
     * 仅 {@code file} 资源可移除（文件脱离挂载）；{@code github_repository / memory_store}
     * 创建后不可摘除 → 409；未命中 → 404。
     */
    @DeleteMapping("/{sessionId}/resources/{resourceId}")
    public ApiResponse<Void> removeResource(@PathVariable String sessionId,
                                            @PathVariable String resourceId) {
        sessionLifecycleService.removeResource(sessionId, resourceId);
        return ApiResponse.success(null);
    }

    /**
     * 删除会话（对齐 {@code DELETE /sessions/{session_id}}）：会话与其历史事件流一并清理
     * （逻辑删除，列表 / 详情即刻不可见），仍活跃的执行先取消；不存在 → 404。
     */
    @DeleteMapping("/{sessionId}")
    public ApiResponse<SessionDeletedResponse> deleteSession(@PathVariable String sessionId) {
        sessionLifecycleService.deleteSession(sessionId);
        return ApiResponse.success(new SessionDeletedResponse(sessionId, "session_deleted"));
    }

    /** 对象 → JSON 文本（metadata 对象序列化为领域 String，null 收敛为空对象）。 */
    private String toJson(Object value) {
        if (value == null) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "{}";
        }
    }

    /** 对象 → JSON 文本（更新语义：null 保持 null=本列不改，序列化失败抛 400）。 */
    private String toJsonNullable(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalArgumentException("请求字段序列化失败", ex);
        }
    }

    /** 当前认证用户数字 ID（无状态认证过滤器已写入 {@link AuthContext}；Session 侧字符串化存储）。 */
    private String currentUserId() {
        return String.valueOf(AuthContext.requireUserId());
    }
}
