package com.linkroa.deepdataagent.runtime.infrastructure.sse;

import com.linkroa.deepdataagent.runtime.application.contract.SseEventEnvelope;
import com.linkroa.deepdataagent.runtime.domain.model.StreamFrame;
import com.linkroa.deepdataagent.shared.util.OffsetDateTimeUtcSerializer;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.node.ObjectNode;

import java.time.OffsetDateTime;

/**
 * {@link SseEventEnvelope} / {@link StreamFrame} → SSE（name + id + data）编解码。
 * <p>buffered 事件：SSE {@code event} 字段 = 事件 {@code type}（如 {@code agent.message}），
 * {@code data} = 扁平 Event JSON（id / type / 可选 processed_at / 类型自有顶层字段）；
 * SSE 帧 {@code id} 取 {@code data.id}（evt_ 前缀，与流式帧 {@code event_start.event.id} /
 * {@code event_delta.event_id} 完全一致），供客户端断线重连时经 {@code Last-Event-ID}
 * 请求头回传续推（服务端按事件 ID 换算回放游标，见三段重连语义）。</p>
 * <p>流式增量帧（event_start / event_delta）：{@code data} = {@code {"type":…, …payload 顶层字段}}，
 * 去除额外的 {@code payload} 包装层，MUST NOT 含顶层 {@code id} / {@code processed_at}；
 * SSE {@code id} 携带关联目标事件 ID。时间统一 RFC 3339 UTC（{@code Z} 后缀，
 * shared/api-conventions，与 REST 响应口径一致）。</p>
 */
public final class ChatEventCodec {

    private static final ObjectMapper OBJECT_MAPPER = buildObjectMapper();

    private ChatEventCodec() {
    }

    private static ObjectMapper buildObjectMapper() {
        SimpleModule module = new SimpleModule();
        module.addSerializer(OffsetDateTime.class, new OffsetDateTimeUtcSerializer());
        return JsonMapper.builder().addModule(module).build();
    }

    /**
     * buffered 事件 → SSE 事件构造器（event name = type，SSE id = evt_ 事件 ID 断点游标）。
     */
    public static SseEmitter.SseEventBuilder toSseEvent(SseEventEnvelope envelope) {
        return SseEmitter.event()
                .name(envelope.type())
                .id(envelope.id())
                .data(toJson(envelope));
    }

    /**
     * 流式增量帧（event_start / event_delta）→ SSE 事件构造器。
     * <p>SSE id 携带关联目标事件 ID（evt_，与最终 buffered 事件同 ID）；data 为
     * {@code {"type":…, …payload 顶层字段}}，无额外 payload 包装层、无顶层 id / processed_at。</p>
     */
    public static SseEmitter.SseEventBuilder toStreamFrameEvent(StreamFrame frame) {
        return SseEmitter.event()
                .name(frame.frameType().value())
                .id(frame.eventId())
                .data(toFrameJson(frame));
    }

    /**
     * buffered 事件 → SSE data JSON（扁平 Event 对象，时间统一 RFC 3339 UTC）。
     *
     * @param envelope 事件信封
     * @return 序列化后的 JSON 字符串
     * @throws IllegalStateException 序列化失败（SSE 通道无法继续推送该帧）
     */
    public static String toJson(SseEventEnvelope envelope) {
        try {
            return OBJECT_MAPPER.writeValueAsString(envelope);
        } catch (JacksonException e) {
            throw new IllegalStateException("SseEventEnvelope SSE 序列化失败", e);
        }
    }

    /** 流式帧 data JSON 装配（{@code type} + payload 顶层字段直接展开，无 payload 包装层）。 */
    public static String toFrameJson(StreamFrame frame) {
        try {
            ObjectNode node = OBJECT_MAPPER.createObjectNode();
            node.put("type", frame.frameType().value());
            if (OBJECT_MAPPER.readTree(frame.payloadJson()) instanceof ObjectNode payloadObject) {
                node.setAll(payloadObject);
            }
            return node.toString();
        } catch (JacksonException e) {
            throw new IllegalStateException("流式帧 SSE 序列化失败", e);
        }
    }
}