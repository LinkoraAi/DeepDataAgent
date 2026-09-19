package com.linkroa.deepdataagent.runtime.infrastructure.sse;

import com.linkroa.deepdataagent.runtime.application.contract.SseEventEnvelope;
import com.linkroa.deepdataagent.runtime.domain.model.StreamFrame;
import com.linkroa.deepdataagent.runtime.domain.model.enums.ChatEventType;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ChatEventCodec} SSE 编解码单测（扁平 Event 契约）。
 * <p>buffered 事件：SSE {@code event} = 事件 {@code type}、{@code id} = evt_ 事件 ID
 * （断点游标）、{@code data} = 扁平 Event JSON（id / type / 可选 processed_at / 类型自有
 * 顶层字段），MUST NOT 出现 object / role / created_at / payload 等信封字段。增量帧
 * （event_start / event_delta）：{@code data} 为 {@code {"type":…, …类型自有字段}}，
 * 无 payload 包装层、无顶层 id / processed_at。时间统一 RFC 3339 UTC（{@code Z} 后缀）。</p>
 */
class ChatEventCodecTest {

    @Test
    void should_serializeFlatEvent_when_toJson_given_envelope() {
        // given（fixture 用 LinkedHashMap 固定键序，序列化断言不依赖 Map.of 实现序）
        OffsetDateTime now = OffsetDateTime.of(2026, 8, 27, 10, 30, 0, 0, ZoneOffset.ofHours(8));
        SseEventEnvelope envelope = new SseEventEnvelope(
                "evt_abc", "agent.tool_use", now,
                orderedMap("tool_use_id", "tc-1", "name", "search"));

        // when
        String json = ChatEventCodec.toJson(envelope);

        // then（固定三字段 + 类型自有字段顶层展开）
        assertTrue(json.contains("\"id\":\"evt_abc\""));
        assertTrue(json.contains("\"type\":\"agent.tool_use\""));
        assertTrue(json.contains("\"processed_at\":"));
        assertTrue(json.contains("\"tool_use_id\":\"tc-1\""));
        assertTrue(json.contains("\"name\":\"search\""));
        assertFalse(json.contains("\"object\""));
        assertFalse(json.contains("\"role\""));
        assertFalse(json.contains("\"created_at\""));
        assertFalse(json.contains("\"payload\""));
        assertFalse(json.contains("attributes"));
    }

    @Test
    void should_serializeContentBlocksAtTopLevel_when_toJson_given_messageEnvelope() {
        // given（消息类事件：content 块数组直接位于顶层）
        SseEventEnvelope envelope = new SseEventEnvelope(
                "evt_abc", "agent.message", null,
                orderedMap("content", java.util.List.of(orderedMap("type", "text", "text", "你好"))));

        // when
        String json = ChatEventCodec.toJson(envelope);

        // then（content 为数组、无二次 JSON 解析；processed_at 为空时整体省略）
        assertTrue(json.contains("\"content\":[{\"type\":\"text\",\"text\":\"你好\"}]"));
        assertFalse(json.contains("processed_at"));
    }

    @Test
    void should_formatRfc3339UtcZ_when_toJson_given_shanghaiTimestamp() {
        // given（+08:00 偏移时间应被归一化打印为 RFC 3339 UTC Z 形态）
        OffsetDateTime shanghai = OffsetDateTime.of(2026, 8, 27, 10, 30, 0, 0, ZoneOffset.ofHours(8));
        SseEventEnvelope envelope = new SseEventEnvelope(
                "evt_abc", "session.status_idle", shanghai, Map.of("stop_reason", Map.of("type", "end_turn")));

        // when
        String json = ChatEventCodec.toJson(envelope);

        // then（UTC 归零偏移，不输出 +08:00 本地偏移）
        assertTrue(json.contains("2026-08-27T02:30:00Z"));
        assertFalse(json.contains("+08:00"));
    }

    @Test
    void should_buildTypeNameAndEvtIdFrame_when_toSseEvent_given_envelope() {
        // given（断点游标语义：SSE id = evt_ 事件 ID，与流式帧 / buffered 共用）
        SseEventEnvelope envelope = new SseEventEnvelope(
                "evt_abc", "agent.message", null,
                orderedMap("content", java.util.List.of(orderedMap("type", "text", "text", "你好"))));

        // when
        String frame = render(ChatEventCodec.toSseEvent(envelope));

        // then（event name = 事件类型、id = evt_ 事件 ID、data = 扁平 Event）
        assertTrue(frame.contains("event:agent.message"));
        assertTrue(frame.contains("id:evt_abc"));
        assertTrue(frame.contains("\"content\":[{\"type\":\"text\",\"text\":\"你好\"}]"));
        assertFalse(frame.contains("\"role\""));
    }

    @Test
    void should_buildSseEvent_when_toSseEvent_given_statusEnvelope() {
        // given
        SseEventEnvelope envelope = new SseEventEnvelope(
                "evt_abc", "session.status_idle", null, Map.of("stop_reason", Map.of("type", "end_turn")));

        // when & then（SSE 帧可正常构建，不抛协议异常）
        SseEmitter.SseEventBuilder builder = ChatEventCodec.toSseEvent(envelope);
        assertNotNull(builder);
        assertNotNull(builder.build());
    }

    @Test
    void should_buildFrameEventWithSharedEvtId_when_toStreamFrameEvent_given_startFrame() {
        // given（帧 SSE id 与最终 buffered 事件同 ID）
        StreamFrame frame = new StreamFrame(ChatEventType.EVENT_START, "evt_1",
                ChatEventType.AGENT_MESSAGE, "{\"event\":{\"id\":\"evt_1\",\"type\":\"agent.message\"}}");

        // when
        String rendered = render(ChatEventCodec.toStreamFrameEvent(frame));

        // then（name = event_start、SSE id = 关联事件 ID）
        assertTrue(rendered.contains("event:event_start"));
        assertTrue(rendered.contains("id:evt_1"));
    }

    @Test
    void should_flattenPayload_when_toFrameJson_given_startFrame() {
        // given（event_start 帧载荷 = event{id,type}）
        StreamFrame frame = new StreamFrame(ChatEventType.EVENT_START, "evt_1",
                ChatEventType.AGENT_MESSAGE, "{\"event\":{\"id\":\"evt_1\",\"type\":\"agent.message\"}}");

        // when
        String json = ChatEventCodec.toFrameJson(frame);

        // then（data = type + 类型自有字段，无 payload 包装层、无顶层 id / processed_at）
        assertEquals("{\"type\":\"event_start\",\"event\":{\"id\":\"evt_1\",\"type\":\"agent.message\"}}", json);
    }

    @Test
    void should_flattenPayload_when_toFrameJson_given_deltaFrame() {
        // given（event_delta 帧载荷 = event_id + delta{type,index,content}）
        StreamFrame frame = new StreamFrame(ChatEventType.EVENT_DELTA, "evt_1",
                ChatEventType.AGENT_MESSAGE,
                "{\"event_id\":\"evt_1\",\"delta\":{\"type\":\"content_delta\",\"index\":0,"
                        + "\"content\":{\"type\":\"text\",\"text\":\"增量\"}}}");

        // when
        String json = ChatEventCodec.toFrameJson(frame);

        // then
        assertEquals("{\"type\":\"event_delta\",\"event_id\":\"evt_1\","
                + "\"delta\":{\"type\":\"content_delta\",\"index\":0,"
                + "\"content\":{\"type\":\"text\",\"text\":\"增量\"}}}", json);
        assertFalse(json.contains("\"payload\""));
        assertFalse(json.contains("processed"));
    }

    @Test
    void should_notExposeInternalFields_when_toJson_given_envelope() {
        // given（对外 JSON 不得出现任何领域内部 / 旧轮次字段名）
        SseEventEnvelope envelope = new SseEventEnvelope(
                "evt_abc", "session.error", null,
                Map.of("error", Map.of("type", "run_error", "retry_status", Map.of("type", "terminal"))));

        // when
        String json = ChatEventCodec.toJson(envelope);

        // then
        assertFalse(json.contains("sessionId"));
        assertFalse(json.contains("\"seq\""));
        assertFalse(json.contains("attributes"));
        assertFalse(json.contains("roundId"));
        assertFalse(json.contains("eventType"));
        assertFalse(json.contains("sequenceNum"));
        assertFalse(json.contains("eventId"));
        assertFalse(json.contains("createdAt"));
    }

    /** 渲染 SSE 帧构造器产物为纯文本（断言 event / id / data 字段）。 */
    private static String render(SseEmitter.SseEventBuilder builder) {
        return builder.build().stream()
                .map(item -> String.valueOf(item.getData()))
                .collect(Collectors.joining());
    }

    /** 构建保序 Map（varargs 键值对，序列化输出顺序与声明一致）。 */
    private static Map<String, Object> orderedMap(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return map;
    }
}