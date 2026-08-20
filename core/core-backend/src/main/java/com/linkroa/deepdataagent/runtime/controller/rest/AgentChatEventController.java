package com.linkroa.deepdataagent.runtime.controller.rest;

import com.linkroa.deepdataagent.runtime.application.assembler.AgentRuntimeCommandAssembler;
import com.linkroa.deepdataagent.runtime.application.assembler.SseEventEnvelopeAssembler;
import com.linkroa.deepdataagent.runtime.application.contract.SseEventEnvelope;
import com.linkroa.deepdataagent.runtime.application.service.AgentRuntimeCommandService;
import com.linkroa.deepdataagent.runtime.application.service.AgentRuntimeQueryService;
import com.linkroa.deepdataagent.runtime.controller.request.SendEventRequest;
import com.linkroa.deepdataagent.runtime.controller.response.EventListResponse;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSession;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSessionContext;
import com.linkroa.deepdataagent.runtime.domain.model.ChatEvent;
import com.linkroa.deepdataagent.runtime.domain.repository.SessionRegistry;
import com.linkroa.deepdataagent.runtime.infrastructure.config.AgentRuntimeProperties;
import com.linkroa.deepdataagent.runtime.infrastructure.sse.ChatEventCodec;
import com.linkroa.deepdataagent.runtime.infrastructure.sse.SseConnectionHandle;
import com.linkroa.deepdataagent.runtime.infrastructure.sse.SseEmitterRegistry;
import com.linkroa.deepdataagent.shared.constant.api.ApiVersionConstants;
import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import com.linkroa.deepdataagent.shared.result.ApiResponse;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Agent 聊天事件 REST 控制器（前缀 {@code /api/v1/agent/sessions/{sessionId}/events}，对齐 Managed Agents Event 接口）。
 * <ul>
 *   <li>{@code POST .../events}：向会话写入事件（{@code input} 数组），返回写入事件回显；</li>
 *   <li>{@code GET .../events}：分页列出会话事件历史；</li>
 *   <li>{@code GET .../events/stream}：SSE 订阅，下发 {@code : connected} 后按 {@code Last-Event-ID} 续推 + 实时订阅。</li>
 * </ul>
 */
@RestController
@RequestMapping(path = "/agent/sessions/{sessionId}/events", version = ApiVersionConstants.CURRENT_API_VERSION)
public class AgentChatEventController {

    private static final String LAST_EVENT_ID_HEADER = "Last-Event-ID";

    @Resource
    private AgentRuntimeCommandService commandService;
    @Resource
    private AgentRuntimeQueryService queryService;
    @Resource
    private AgentRuntimeCommandAssembler commandAssembler;
    @Resource
    private SseEventEnvelopeAssembler sseEventEnvelopeAssembler;
    @Resource
    private SseEmitterRegistry emitterRegistry;
    @Resource
    private AgentRuntimeProperties properties;
    @Resource
    private SessionRegistry sessionRegistry;

    /**
     * 发送事件（对齐 {@code POST /sessions/{session_id}/events}）。
     * <p>首版仅支持 {@code type=message}；用户消息回显暂未落库（后续与事件历史对齐）。</p>
     */
    @PostMapping
    public ApiResponse<List<SseEventEnvelope>> sendEvent(@PathVariable String sessionId,
                                                         @Valid @RequestBody SendEventRequest request) {
        String message = extractMessage(request);
        String runId = UUID.randomUUID().toString().replace("-", "");
        commandService.sendMessageAsync(commandAssembler.toSendCommand(sessionId, message, runId));
        return ApiResponse.success(List.of(userMessageEcho(sessionId, message)));
    }

    /**
     * 列出会话事件历史（对齐 {@code GET /sessions/{session_id}/events}）。
     */
    @GetMapping
    public ApiResponse<EventListResponse> listEvents(@PathVariable String sessionId) {
        List<ChatEvent> events = queryService.replayEvents(commandAssembler.toReplayQuery(sessionId, 0));
        List<SseEventEnvelope> data = events.stream()
                .map(sseEventEnvelopeAssembler::toEnvelope)
                .toList();
        return ApiResponse.success(new EventListResponse(data, null));
    }

    /**
     * 事件订阅端点：先绑定连接（注册实时订阅，消除事件丢失窗口），随后下发 {@code : connected}
     * 注释行并回放 {@code Last-Event-ID} 之后的历史事件，回放完成后保持连接实时订阅。
     * <p>对齐 Managed Agents SSE 设计：{@code event} 恒为 {@code message}，事件类型由
     * {@code data.type} 区分；断点游标为 {@code data.sequence_number}，客户端断线重连时
     * 经 SSE 标准 {@code Last-Event-ID} 请求头回传，服务端据此从断点续推。</p>
     */
    @GetMapping("/stream")
    public SseEmitter stream(@PathVariable String sessionId,
                             @RequestHeader(name = LAST_EVENT_ID_HEADER, required = false) String lastEventId) {
        // 先校验会话存在（快速失败，避免绑定后查询 404）
        AgentSession session = queryService.getSession(sessionId);

        SseEmitter emitter = bindAndRegister(session);
        try {
            // 连接建立后立即下发注释行，随后回放历史事件（对齐 Managed Agents : connected）
            emitter.send(SseEmitter.event().comment("connected"));
            // 绑定完成后再回放：绑定到回放之间的实时广播与该 emitter 同步，
            // 客户端按 event_id / sequence_number 幂等去重，杜绝「回放先于绑定」的丢失窗口
            List<ChatEvent> history = queryService.replayEvents(
                    commandAssembler.toReplayQuery(sessionId, parseLastEventId(lastEventId)));
            for (ChatEvent event : history) {
                emitter.send(ChatEventCodec.toSseEvent(sseEventEnvelopeAssembler.toEnvelope(event)));
            }
        } catch (Exception ex) {
            emitter.completeWithError(ex);
            return emitter;
        }
        return emitter;
    }

    // ==================== 私有方法 ====================

    /** 从 input 数组中提取首个 message 事件的文本内容。 */
    private String extractMessage(SendEventRequest request) {
        for (SendEventRequest.EventInput event : request.input()) {
            if ("message".equalsIgnoreCase(event.type())) {
                String text = textOf(event.content());
                if (text != null && !text.isBlank()) {
                    return text.trim();
                }
            }
        }
        throw new DeepDataAgentException("DEEP_AGENT_EVENT_TYPE_UNSUPPORTED: 仅支持 message 类型");
    }

    /** 拼接 ContentBlock 中的 text 文本。 */
    private String textOf(List<Map<String, Object>> content) {
        if (content == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> block : content) {
            if ("text".equals(block.get("type")) && block.get("text") != null) {
                sb.append(block.get("text"));
            }
        }
        return sb.toString();
    }

    /** 合成用户消息 SSE 回显信封（未落库，后续与事件历史对齐）。 */
    private SseEventEnvelope userMessageEcho(String sessionId, String message) {
        return new SseEventEnvelope(
                "message",
                UUID.randomUUID().toString().replace("-", ""),
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")),
                "user",
                "message",
                List.of(Map.of("type", "text", "text", message)),
                Map.of("session_id", sessionId),
                "completed",
                0L
        );
    }

    /**
     * 将会话绑定到连接层并注册一个订阅者：复用会话级连接句柄（多订阅者 fan-out）、
     * 注册「全部断连 → 取消在跑执行」回调，再创建受超时 / 断连保护的 emitter。
     */
    private SseEmitter bindAndRegister(AgentSession session) {
        AgentSessionContext context = sessionRegistry.getOrCreate(session);
        SseConnectionHandle handle = emitterRegistry.getOrCreate(session.sessionId());
        context.bindConnection(handle);
        handle.onDisconnect(context::cancel);
        return emitterRegistry.register(handle, properties.getSseTimeout());
    }

    /**
     * 解析断点游标：SSE 客户端断线重连时经 {@code Last-Event-ID} 回传最后一次事件 {@code id}
     * （即服务端下发的 {@code sequence_number}）；非法或缺省时收敛为 0（全量回放）。
     */
    private long parseLastEventId(String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) {
            return 0;
        }
        try {
            return Math.max(Long.parseLong(lastEventId.trim()), 0);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}