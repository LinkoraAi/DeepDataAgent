package com.linkroa.deepdataagent.agent.controller.rest;

import com.linkroa.deepdataagent.agent.application.dto.MessageDTO;
import com.linkroa.deepdataagent.agent.application.dto.SessionDTO;
import com.linkroa.deepdataagent.agent.application.dto.SessionListItemDTO;
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
 * <p>控制器负责将应用层 DTO 转换为控制器层响应对象。</p>
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
            SessionDTO dto = sessionApplicationService.createSession(
                    request.userId(), request.datasourceId(), request.modelConfigId(), request.userQuestion());
            return ApiResponse.success(toSessionResponse(dto));
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
        Integer limit = request != null ? request.limit() : null;
        Integer offset = request != null ? request.offset() : null;
        List<SessionListItemDTO> dtos = sessionApplicationService.listSessions(limit, offset);
        return ApiResponse.success(dtos.stream().map(this::toSessionListItem).toList());
    }

    /**
     * 获取单个会话详情
     */
    @PostMapping("/get")
    public ApiResponse<SessionResponse> getSession(@Valid @RequestBody GetSessionRequest request) {
        try {
            SessionDTO dto = sessionApplicationService.getSession(request.sessionId());
            return ApiResponse.success(toSessionResponse(dto));
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
     * <p>limit 为轮次数（可选，默认 5），beforeDialogueId 为轮次游标（可选，null 表示取最新轮次）。</p>
     */
    @PostMapping("/messages")
    public ApiResponse<List<MessageResponse>> getMessages(@Valid @RequestBody GetMessagesRequest request) {
        try {
            List<MessageDTO> dtos = sessionApplicationService.getMessages(
                    request.sessionId(), request.limit(), request.beforeDialogueId());
            return ApiResponse.success(dtos.stream().map(this::toMessageResponse).toList());
        } catch (IllegalArgumentException e) {
            return ApiResponse.error("404", e.getMessage());
        }
    }

    /**
     * 将会话 DTO 转换为会话响应对象
     */
    private SessionResponse toSessionResponse(SessionDTO dto) {
        return new SessionResponse(
                dto.id(), dto.title(), dto.datasourceId(), dto.modelConfigId(),
                dto.status(), dto.lastMessageAt(), dto.createdAt());
    }

    /**
     * 将会话列表项 DTO 转换为列表项响应对象
     */
    private SessionListItem toSessionListItem(SessionListItemDTO dto) {
        return new SessionListItem(
                dto.id(), dto.title(), dto.datasourceId(), dto.modelConfigId(),
                dto.status(), dto.lastMessageAt(), dto.createdAt(), dto.running());
    }

    /**
     * 将消息 DTO 转换为消息响应对象
     */
    private MessageResponse toMessageResponse(MessageDTO dto) {
        return new MessageResponse(
                dto.id(), dto.sessionId(), dto.dialogueId(), dto.role(), dto.content(),
                dto.toolCalls(), dto.toolResult(), dto.createdAt());
    }
}
