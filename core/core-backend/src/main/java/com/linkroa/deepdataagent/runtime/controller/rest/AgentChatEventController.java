package com.linkroa.deepdataagent.runtime.controller.rest;

import com.linkroa.deepdataagent.runtime.application.assembler.AgentRuntimeCommandAssembler;
import com.linkroa.deepdataagent.runtime.application.service.AgentRuntimeCommandService;
import com.linkroa.deepdataagent.runtime.application.service.AgentRuntimeQueryService;
import com.linkroa.deepdataagent.runtime.controller.request.SendEventRequest;
import com.linkroa.deepdataagent.runtime.controller.response.SendMessageResponse;
import com.linkroa.deepdataagent.runtime.domain.model.ChatEvent;
import com.linkroa.deepdataagent.runtime.infrastructure.config.AgentRuntimeProperties;
import com.linkroa.deepdataagent.shared.constant.api.ApiVersionConstants;
import com.linkroa.deepdataagent.runtime.infrastructure.sse.ChatEventCodec;
import com.linkroa.deepdataagent.runtime.infrastructure.sse.SseEmitterRegistry;
import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import com.linkroa.deepdataagent.shared.result.ApiResponse;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.UUID;

/**
 * Agent 聊天事件 REST 控制器（v1 版本，URL 前缀 {@code /api/v1/agent/sessions/{sessionId}/events}）。
 * <ul>
 *   <li>{@code POST /api/v1/agent/sessions/{sessionId}/events}：发送消息触发执行；
 *       请求头含 {@code Accept: text/event-stream} 时返回 SSE 流（立即返回 emitter，
 *       执行由虚拟线程托管，事件经注册表实时推送），否则返回 {@code 202 Accepted} + {@code run_id}；</li>
 *   <li>{@code GET /api/v1/agent/sessions/{sessionId}/events/stream}：按 {@code after_sequence_num}
 *       回放历史事件 + 实时订阅后续事件（先注册后回放，消除事件丢失窗口；潜在重复因
 *       event_id 幂等由客户端去重过滤）。</li>
 * </ul>
 */
@RestController
@RequestMapping(path = "/agent/sessions/{sessionId}/events", version = ApiVersionConstants.CURRENT_API_VERSION)
public class AgentChatEventController {

    private static final Logger log = LoggerFactory.getLogger(AgentChatEventController.class);

    private static final String EVENT_TYPE_MESSAGE = "message";

    @Resource
    private AgentRuntimeCommandService commandService;
    @Resource
    private AgentRuntimeQueryService queryService;
    @Resource
    private AgentRuntimeCommandAssembler commandAssembler;
    @Resource
    private SseEmitterRegistry emitterRegistry;
    @Resource
    private AgentRuntimeProperties properties;

    /**
     * 发送消息事件。
     *
     * @return SSE 直连时返回 {@link SseEmitter}（异步执行，事件实时推送），否则返回 202 + run_id
     */
    @PostMapping
    public Object sendEvent(@PathVariable String sessionId,
                            @Valid @RequestBody SendEventRequest request,
                            @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String accept) {
        if (!EVENT_TYPE_MESSAGE.equals(request.type())) {
            throw new DeepDataAgentException("DEEP_AGENT_EVENT_TYPE_UNSUPPORTED: 暂不支持事件类型: " + request.type());
        }
        boolean streamRequested = accept != null && accept.contains(MediaType.TEXT_EVENT_STREAM_VALUE);
        if (streamRequested) {
            return streamSend(sessionId, request);
        }
        return acceptedSend(sessionId, request);
    }

    /**
     * 事件订阅端点：先注册实时订阅（消除事件丢失窗口），随后回放
     * {@code after_sequence_num} 之后的历史事件，回放完成后保持连接实时订阅。
     */
    @GetMapping("/stream")
    public SseEmitter stream(@PathVariable String sessionId,
                             @RequestParam(name = "after_sequence_num", defaultValue = "0") long afterSequenceNum) {
        // 先校验会话存在（快速失败，避免注册后查询 404）
        queryService.getSession(sessionId);

        SseEmitter emitter = emitterRegistry.register(sessionId, properties.getSseTimeout());
        try {
            // 注册完成后再回放：注册到回放之间的实时广播与该 emitter 同步，
            // 客户端按 event_id / sequence_num 幂等去重，杜绝「回放先于注册」的丢失窗口
            List<ChatEvent> history = queryService.replayEvents(
                    commandAssembler.toReplayQuery(sessionId, afterSequenceNum));
            for (ChatEvent event : history) {
                emitter.send(ChatEventCodec.toSseEvent(event));
            }
        } catch (Exception ex) {
            emitter.completeWithError(ex);
            return emitter;
        }
        return emitter;
    }

    // ==================== 私有方法 ====================

    /**
     * SSE 直连模式：注册当前连接为订阅者，随即将执行提交虚拟线程池（立即返回 emitter，
     * 不阻塞 tomcat worker）；轮次终态（含 session_status 广播）完成后经回调关闭本连接。
     */
    private SseEmitter streamSend(String sessionId, SendEventRequest request) {
        // 复制（深 or 浅）不重要：先同步校验会话存在，快速失败
        queryService.getSession(sessionId);
        SseEmitter emitter = emitterRegistry.register(sessionId, properties.getSseTimeout());
        String runId = UUID.randomUUID().toString().replace("-", "");
        commandService.sendMessageAsync(
                commandAssembler.toSendCommand(sessionId, request, runId),
                emitter::complete);
        return emitter;
    }

    /**
     * 202 异步模式：预生成 run_id 返回，执行提交虚拟线程池，事件经 stream 端点订阅。
     */
    private ResponseEntity<ApiResponse<SendMessageResponse>> acceptedSend(String sessionId, SendEventRequest request) {
        String runId = UUID.randomUUID().toString().replace("-", "");
        commandService.sendMessageAsync(commandAssembler.toSendCommand(sessionId, request, runId));
        SendMessageResponse response = new SendMessageResponse(null, runId, null);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(response));
    }
}