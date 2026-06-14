package com.linkroa.deepdataagent.agent.controller.rest;

import com.linkroa.deepdataagent.agent.application.service.SessionApplicationService;
import com.linkroa.deepdataagent.agent.controller.request.CloseSessionRequest;
import com.linkroa.deepdataagent.agent.controller.request.CreateSessionRequest;
import com.linkroa.deepdataagent.agent.controller.request.GetMessagesRequest;
import com.linkroa.deepdataagent.agent.controller.request.GetSessionRequest;
import com.linkroa.deepdataagent.agent.controller.request.ListSessionsRequest;
import com.linkroa.deepdataagent.agent.controller.response.MessageResponse;
import com.linkroa.deepdataagent.agent.controller.response.SessionListItem;
import com.linkroa.deepdataagent.agent.controller.response.SessionResponse;
import com.linkroa.deepdataagent.shared.result.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 会话管理 REST 控制器
 * <p>提供会话生命周期管理接口：创建、列表、详情、关闭及消息查询。</p>
 * <p>所有接口统一使用 POST + body 传参方式。</p>
 */
@RestController
@RequestMapping("/agent/sessions")
public class SessionController {

    private final SessionApplicationService sessionApplicationService;

    public SessionController(SessionApplicationService sessionApplicationService) {
        this.sessionApplicationService = sessionApplicationService;
    }

    /**
     * 创建新会话
     */
    @PostMapping("/create")
    public ApiResponse<SessionResponse> createSession(@Valid @RequestBody CreateSessionRequest request) {
        try {
            SessionResponse response = sessionApplicationService.createSession(
                    request.datasourceId(), request.modelConfigId());
            return ApiResponse.success(response);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error("400", e.getMessage());
        } catch (IllegalStateException e) {
            return ApiResponse.error("429", e.getMessage());
        }
    }

    /**
     * 获取所有活跃会话列表
     */
    @PostMapping("/list")
    public ApiResponse<List<SessionListItem>> listSessions(@RequestBody(required = false) ListSessionsRequest request) {
        return ApiResponse.success(sessionApplicationService.listSessions());
    }

    /**
     * 获取单个会话详情
     */
    @PostMapping("/get")
    public ApiResponse<SessionResponse> getSession(@Valid @RequestBody GetSessionRequest request) {
        try {
            SessionResponse response = sessionApplicationService.getSession(request.sessionId());
            return ApiResponse.success(response);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error("404", e.getMessage());
        }
    }

    /**
     * 关闭会话
     */
    @PostMapping("/close")
    public ApiResponse<Void> closeSession(@Valid @RequestBody CloseSessionRequest request) {
        try {
            sessionApplicationService.closeSession(request.sessionId());
            return ApiResponse.success(null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error("404", e.getMessage());
        } catch (IllegalStateException e) {
            return ApiResponse.error("400", e.getMessage());
        }
    }

    /**
     * 获取会话消息列表
     */
    @PostMapping("/messages")
    public ApiResponse<List<MessageResponse>> getMessages(@Valid @RequestBody GetMessagesRequest request) {
        try {
            List<MessageResponse> messages = sessionApplicationService.getMessages(
                    request.sessionId(), request.limit(), request.offset());
            return ApiResponse.success(messages);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error("404", e.getMessage());
        }
    }
}
