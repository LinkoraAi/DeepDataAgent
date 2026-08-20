package com.linkroa.deepdataagent.runtime.controller.rest;

import com.linkroa.deepdataagent.runtime.application.assembler.AgentRuntimeCommandAssembler;
import com.linkroa.deepdataagent.runtime.application.service.AgentRuntimeCommandService;
import com.linkroa.deepdataagent.runtime.application.service.AgentRuntimeQueryService;
import com.linkroa.deepdataagent.runtime.application.service.RuntimeAgentAssemblyResolver;
import com.linkroa.deepdataagent.runtime.controller.request.CreateSessionRequest;
import com.linkroa.deepdataagent.runtime.controller.request.UpdateSessionRequest;
import com.linkroa.deepdataagent.runtime.controller.response.AgentRuntimeResponseMapper;
import com.linkroa.deepdataagent.runtime.controller.response.SessionDeletedResponse;
import com.linkroa.deepdataagent.runtime.controller.response.SessionListResponse;
import com.linkroa.deepdataagent.runtime.controller.response.SessionResponse;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSession;
import com.linkroa.deepdataagent.shared.constant.api.ApiVersionConstants;
import com.linkroa.deepdataagent.shared.result.ApiResponse;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * Agent 会话管理 REST 控制器（前缀 {@code /api/v1/agent/sessions}，对齐 Managed Agents Session 接口）。
 * <p>会话身份字段 userId 对外隐藏、内部以默认身份保留；{@code agent_id / statuses[]} 过滤与
 * cursor 分页游标物化留待后续，当前列表采用 limit/page 近似对齐。</p>
 */
@RestController
@RequestMapping(path = "/agent/sessions", version = ApiVersionConstants.CURRENT_API_VERSION)
public class AgentSessionController {

    /** 对外隐藏的真实业务身份占位（后续接入 workspace 鉴权后替换）。 */
    private static final String DEFAULT_USER_ID = "demo-user";

    @Resource
    private AgentRuntimeCommandService commandService;
    @Resource
    private AgentRuntimeQueryService queryService;
    @Resource
    private AgentRuntimeResponseMapper responseMapper;
    @Resource
    private AgentRuntimeCommandAssembler commandAssembler;
    @Resource
    private RuntimeAgentAssemblyResolver runtimeAgentAssemblyResolver;
    @Resource
    private ObjectMapper objectMapper;

    /**
     * 创建会话（对齐 {@code POST /sessions}）：绑定 agent 最新版本快照与运行环境。
     */
    @PostMapping
    public ApiResponse<SessionResponse> createSession(@Valid @RequestBody CreateSessionRequest request) {
        // 对齐 Managed Agents：创建仅传 agent，服务端锁定其最新发布号
        String agentVersion = runtimeAgentAssemblyResolver.latestVersionNumber(request.agent());
        AgentSession session = commandService.createSession(
                commandAssembler.toCreateCommand(DEFAULT_USER_ID, request, agentVersion, toJson(request.metadata())));
        return ApiResponse.success(responseMapper.toSessionResponse(session));
    }

    /**
     * 会话详情（对齐 {@code GET /sessions/{session_id}}）。
     */
    @GetMapping("/{sessionId}")
    public ApiResponse<SessionResponse> getSession(@PathVariable String sessionId) {
        return ApiResponse.success(responseMapper.toSessionResponse(queryService.getSession(sessionId)));
    }

    /**
     * 分页列出会话（对齐 {@code GET /sessions}）。
     */
    @GetMapping
    public ApiResponse<SessionListResponse> listSessions(
            @RequestParam(name = "agent_id", required = false) String agentId,
            @RequestParam(name = "statuses[]", required = false) List<String> statuses,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "page", required = false) String page
    ) {
        int pageNum = parsePage(page);
        AgentRuntimeQueryService.PaginatedResult<AgentSession> result =
                queryService.listSessions(commandAssembler.toListQuery(DEFAULT_USER_ID, limit, pageNum));
        List<SessionResponse> data = result.data().stream()
                .map(responseMapper::toSessionResponse)
                .toList();
        int totalPages = (int) Math.ceil((double) result.total() / Math.max(result.size(), 1));
        String nextPage = pageNum < totalPages ? String.valueOf(pageNum + 1) : null;
        return ApiResponse.success(new SessionListResponse(data, nextPage));
    }

    /**
     * 更新会话（对齐 {@code POST /sessions/{session_id}}，仅 title/metadata）。
     */
    @PostMapping("/{sessionId}")
    public ApiResponse<SessionResponse> updateSession(@PathVariable String sessionId,
                                                      @Valid @RequestBody UpdateSessionRequest request) {
        AgentSession session = commandService.updateSession(sessionId, request.title(), toJson(request.metadata()));
        return ApiResponse.success(responseMapper.toSessionResponse(session));
    }

    /**
     * 归档会话（对齐 {@code POST /sessions/{session_id}/archive}）：置 terminated 终态。
     * <p>{@code archived_at} 尚未落库，当前以 terminated 状态表达归档语义，归档时间后续补充。</p>
     */
    @PostMapping("/{sessionId}/archive")
    public ApiResponse<SessionResponse> archiveSession(@PathVariable String sessionId) {
        commandService.terminateSession(commandAssembler.toTerminateCommand(sessionId));
        return ApiResponse.success(responseMapper.toSessionResponse(queryService.getSession(sessionId)));
    }

    /**
     * 删除会话（对齐 {@code DELETE /sessions/{session_id}}）。
     */
    @DeleteMapping("/{sessionId}")
    public ApiResponse<SessionDeletedResponse> deleteSession(@PathVariable String sessionId) {
        commandService.terminateSession(commandAssembler.toTerminateCommand(sessionId));
        return ApiResponse.success(new SessionDeletedResponse(sessionId, "session_deleted"));
    }

    /** 解析 offset 页码（cursor 化留待后续）。 */
    private int parsePage(String page) {
        if (page == null || page.isBlank()) {
            return 1;
        }
        try {
            return Math.max(Integer.parseInt(page.trim()), 1);
        } catch (NumberFormatException ex) {
            return 1;
        }
    }

    /** 对象 → JSON 文本（metadata 对象序列化为领域 String）。 */
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
}