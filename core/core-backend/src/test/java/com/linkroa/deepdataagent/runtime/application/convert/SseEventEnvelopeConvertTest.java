package com.linkroa.deepdataagent.runtime.application.convert;

import com.linkroa.deepdataagent.runtime.application.contract.SseEventEnvelope;
import com.linkroa.deepdataagent.runtime.domain.model.ChatEvent;
import com.linkroa.deepdataagent.runtime.domain.model.enums.ChatEventType;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SseEventEnvelopeConvert} 扁平 Event 转换单测。
 * <p>契约：领域 {@link ChatEvent} → 扁平 Event {@code {id, type, processed_at?, @JsonAnyGetter 类型自有字段}}。
 * 固定字段仅取事件 ID（evt_）、事件类型与可空处理时间；payload 顶层字段原样透传——
 * content 块数组直接成为顶层 {@code content}（不包装固定槽、不派生 role），未知字段亦原样透传；
 * 领域内部字段（sessionId / seq / 审计 / DB 主键）一律不进入事件对象。</p>
 */
class SseEventEnvelopeConvertTest {

    private final SseEventEnvelopeConvert converter = SseEventEnvelopeConvert.INSTANCE;

    @Test
    void should_promoteContentBlocksToTopLevel_when_toEnvelope_given_agentMessageEvent() {
        // given（消息类事件 payload.content 为 content block 数组）
        ChatEvent event = ChatEvent.create("s-1", ChatEventType.AGENT_MESSAGE,
                "{\"content\":[{\"type\":\"text\",\"text\":\"你好\"}]}", 3L);

        // when
        SseEventEnvelope envelope = converter.toEnvelope(event);

        // then（固定字段 + content 顶层透传，不二次包装、不派生 role）
        assertEquals(event.eventId(), envelope.id());
        assertEquals("agent.message", envelope.type());
        assertEquals(event.processedAt(), envelope.processedAt());
        List<?> content = (List<?>) envelope.attributes().get("content");
        assertEquals(1, content.size());
        assertEquals(Map.of("type", "text", "text", "你好"), content.getFirst());
    }

    @Test
    void should_expandTypedFields_when_toEnvelope_given_sessionStatusEvent() {
        // given（状态事件无 content；类型自有字段直接位于顶层）
        ChatEvent event = ChatEvent.create("s-1", ChatEventType.SESSION_STATUS_IDLE,
                "{\"stop_reason\":{\"type\":\"end_turn\"}}", 5L);

        // when
        SseEventEnvelope envelope = converter.toEnvelope(event);

        // then
        assertEquals("session.status_idle", envelope.type());
        assertFalse(envelope.attributes().containsKey("content"));
        assertEquals(Map.of("type", "end_turn"), envelope.attributes().get("stop_reason"));
    }

    @Test
    void should_passThroughUnknownFields_when_toEnvelope_given_payloadWithExtraKeys() {
        // given（payload 含类型未声明的字段：原样透传，不做白名单裁剪）
        ChatEvent event = ChatEvent.create("s-1", ChatEventType.AGENT_TOOL_USE,
                "{\"tool_use_id\":\"tc-1\",\"future_field\":{\"nested\":true}}", 6L);

        // when
        SseEventEnvelope envelope = converter.toEnvelope(event);

        // then
        assertEquals("tc-1", envelope.attributes().get("tool_use_id"));
        assertEquals(Map.of("nested", true), envelope.attributes().get("future_field"));
    }

    @Test
    void should_keepNestedErrorShape_when_toEnvelope_given_sessionErrorEvent() {
        // given（session.error 的 error 为嵌套对象）
        ChatEvent event = ChatEvent.create("s-1", ChatEventType.SESSION_ERROR,
                "{\"error\":{\"type\":\"run_error\",\"message\":\"执行超时\","
                        + "\"retry_status\":{\"type\":\"terminal\"},\"error_code\":\"DEEP_AGENT_RUN_ERROR\"}}", 7L);

        // when
        SseEventEnvelope envelope = converter.toEnvelope(event);

        // then
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) envelope.attributes().get("error");
        assertEquals("run_error", error.get("type"));
        assertEquals(Map.of("type", "terminal"), error.get("retry_status"));
        assertEquals("DEEP_AGENT_RUN_ERROR", error.get("error_code"));
    }

    @Test
    void should_convertNullProcessedAt_when_toEnvelope_given_unprocessedEvent() {
        // given（未处理事件 processed_at 可空）
        ChatEvent event = ChatEvent.restore(1L, "evt_abc", "s-1", 8L, ChatEventType.USER_MESSAGE,
                "{\"content\":[]}", null, OffsetDateTime.now(), OffsetDateTime.now(), null, null, null);

        // when
        SseEventEnvelope envelope = converter.toEnvelope(event);

        // then
        assertNull(envelope.processedAt());
        assertEquals("user.message", envelope.type());
    }

    @Test
    void should_defaultAttributesToEmptyMap_when_toEnvelope_given_blankOrMalformedPayload() {
        // given（空白 payload 与非法 JSON 均收敛为空对象，不阻断事件流）
        ChatEvent blank = ChatEvent.create("s-1", ChatEventType.SESSION_DELETED, " ", 9L);
        ChatEvent malformed = ChatEvent.create("s-1", ChatEventType.SESSION_ERROR, "{非法 json", 10L);

        // when
        SseEventEnvelope blankEnvelope = converter.toEnvelope(blank);
        SseEventEnvelope malformedEnvelope = converter.toEnvelope(malformed);

        // then
        assertEquals(Map.of(), blankEnvelope.attributes());
        assertEquals(Map.of(), malformedEnvelope.attributes());
    }

    @Test
    void should_notExposeInternalFields_when_toEnvelope_given_persistedEvent() {
        // given（持久化事件携带领域内部字段）
        ChatEvent event = ChatEvent.create("s-1", ChatEventType.AGENT_MESSAGE,
                "{\"content\":[{\"type\":\"text\",\"text\":\"你好\"}]}", 11L);

        // when
        SseEventEnvelope envelope = converter.toEnvelope(event);

        // then（扁平 Event 仅 id/type/processed_at + 类型自有字段；无 object / role / created_at）
        assertNotNull(envelope.id());
        assertFalse(envelope.attributes().containsKey("object"));
        assertFalse(envelope.attributes().containsKey("role"));
        assertFalse(envelope.attributes().containsKey("created_at"));
        assertFalse(envelope.attributes().containsKey("sessionId"));
        assertFalse(envelope.attributes().containsKey("seq"));
    }

    @Test
    void should_throw_when_toEnvelope_given_nullEvent() {
        // when & then（null 事件直接抛参错，不产生空信封）
        assertThrows(NullPointerException.class, () -> converter.toEnvelope(null));
    }

    @Test
    void should_rejectInvalidEvent_when_constructEvent_given_nonPositiveSeq() {
        // when & then（seq 必须为正：不满足不变量的领域事件构造期即拒绝）
        assertThrows(IllegalArgumentException.class,
                () -> ChatEvent.create("s-1", ChatEventType.AGENT_MESSAGE, "{}", 0L));
    }

    @Test
    void should_rejectEnvelope_when_constructEnvelope_given_invalidIdOrBlankType() {
        // given
        OffsetDateTime now = OffsetDateTime.now();

        // when & then（信封不变量：id 必须 evt_ 前缀、type 非空白）
        assertThrows(IllegalArgumentException.class, () -> new SseEventEnvelope(
                "abc_no_prefix", "agent.message", now, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new SseEventEnvelope(
                "evt_1", " ", now, Map.of()));
    }

    @Test
    void should_defaultAttributesToEmptyMap_when_constructEnvelope_given_nullAttributes() {
        // given（attributes 传 null：紧凑构造器归一为空 Map，树遍历免判空）
        // when
        SseEventEnvelope envelope = new SseEventEnvelope("evt_1", "agent.thinking", null, null);

        // then
        assertTrue(envelope.attributes().isEmpty());
        assertNull(envelope.processedAt());
    }
}