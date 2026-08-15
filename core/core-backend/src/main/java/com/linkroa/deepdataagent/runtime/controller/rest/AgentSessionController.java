package com.linkroa.deepdataagent.runtime.controller.rest;

import com.linkroa.deepdataagent.runtime.application.assembler.AgentRuntimeCommandAssembler;
import com.linkroa.deepdataagent.runtime.application.service.AgentRuntimeCommandService;
import com.linkroa.deepdataagent.runtime.application.service.AgentRuntimeQueryService;
import com.linkroa.deepdataagent.runtime.controller.request.CreateSessionRequest;
import com.linkroa.deepdataagent.runtime.controller.response.AgentRuntimeResponseMapper;
import com.linkroa.deepdataagent.runtime.controller.response.PaginatedResponse;
import com.linkroa.deepdataagent.runtime.controller.response.RoundResponse;
import com.linkroa.deepdataagent.runtime.controller.response.RunTraceResponse;
import com.linkroa.deepdataagent.runtime.controller.response.SessionResponse;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSession;
import com.linkroa.deepdataagent.runtime.domain.model.ExecutionRound;
import com.linkroa.deepdataagent.runtime.domain.model.RunTrace;
import com.linkroa.deepdataagent.runtime.domain.model.ChatEvent;
import com.linkroa.deepdataagent.runtime.controller.response.ChatEventResponse;
import com.linkroa.deepdataagent.runtime.infrastructure.config.ApiVersioningConfig;
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

import java.util.List;

/**
 * Agent 会话管理 REST 控制器（统一前缀 {@code /api/v1/agent/sessions}，v1 版本）。
 * <p>会话生命周期（创建 / 查询 / 分页 / 终止）+ 轮次与事件回放 + 链路追踪查询。</p>
 */
@RestController
@RequestMapping(path = "/agent/sessions", version = ApiVersioningConfig.CURRENT_API_VERSION)
public class AgentSessionController {

    @Resource
    private AgentRuntimeCommandService commandService;
    @Resource
    private AgentRuntimeQueryService queryService;
    @Resource
    private AgentRuntimeResponseMapper responseMapper;
    @Resource
    private AgentRuntimeCommandAssembler commandAssembler;

    /**
     * 创建会话。
     */
    @PostMapping
    public ApiResponse<SessionResponse> createSession(@Valid @RequestBody CreateSessionRequest request) {
        AgentSession session = commandService.createSession(commandAssembler.toCreateCommand(request));
        return ApiResponse.success(responseMapper.toSessionResponse(session));
    }

    /**
     * 分页查询会话列表。
     */
    @GetMapping
    public ApiResponse<PaginatedResponse<SessionResponse>> listSessions(
            @RequestParam String userId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size
    ) {
        AgentRuntimeQueryService.PaginatedResult<AgentSession> result =
                queryService.listSessions(commandAssembler.toListQuery(userId, page, size));
        List<SessionResponse> responses = result.data().stream()
                .map(responseMapper::toSessionResponse)
                .toList();
        return ApiResponse.success(new PaginatedResponse<>(responses, result.total(), result.page(), result.size()));
    }

    /**
     * 会话详情。
     */
    @GetMapping("/{sessionId}")
    public ApiResponse<SessionResponse> getSession(@PathVariable String sessionId) {
        AgentSession session = queryService.getSession(sessionId);
        return ApiResponse.success(responseMapper.toSessionResponse(session));
    }

    /**
     * 终止会话。
     */
    @DeleteMapping("/{sessionId}")
    public ApiResponse<String> terminateSession(@PathVariable String sessionId) {
        commandService.terminateSession(commandAssembler.toTerminateCommand(sessionId));
        return ApiResponse.success("会话已终止");
    }

    /**
     * 会话内轮次列表。
     */
    @GetMapping("/{sessionId}/rounds")
    public ApiResponse<List<RoundResponse>> listRounds(@PathVariable String sessionId) {
        List<ExecutionRound> rounds = queryService.listRounds(sessionId);
        List<RoundResponse> responses = rounds.stream()
                .map(responseMapper::toRoundResponse)
                .toList();
        return ApiResponse.success(responses);
    }

    /**
     * 单轮事件回放。
     */
    @GetMapping("/{sessionId}/rounds/{roundId}/events")
    public ApiResponse<List<ChatEventResponse>> roundEvents(@PathVariable String sessionId,
                                                            @PathVariable String roundId) {
        List<ChatEvent> events = queryService.roundEvents(roundId);
        List<ChatEventResponse> responses = events.stream()
                .map(responseMapper::toChatEventResponse)
                .toList();
        return ApiResponse.success(responses);
    }

    /**
     * 轮次链路追踪（span 树）。
     */
    @GetMapping("/{sessionId}/rounds/{roundId}/trace")
    public ApiResponse<List<RunTraceResponse>> trace(@PathVariable String sessionId,
                                                     @PathVariable String roundId) {
        List<RunTrace> traces = queryService.getTrace(roundId);
        List<RunTraceResponse> responses = traces.stream()
                .map(responseMapper::toRunTraceResponse)
                .toList();
        return ApiResponse.success(responses);
    }
}