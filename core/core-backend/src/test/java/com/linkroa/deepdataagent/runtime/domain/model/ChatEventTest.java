package com.linkroa.deepdataagent.runtime.domain.model;

import com.linkroa.deepdataagent.runtime.domain.model.enums.ChatEventType;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ChatEvent} 领域模型不变量单测（事件溯源信封：id / sessionId / seq / type / payload /
 * processedAt / createdAt，事件 ID 统一 {@code evt_} 前缀；线程归属 {@code sessionThreadId} 可空、
 * 非空时统一 {@code sthr_} 前缀）。
 */
class ChatEventTest {

    @Test
    void should_createEvent_when_create_given_validInputs() {
        // given
        String sessionId = "s-1";

        // when（持久化事件（默认自动生成 evt_ 事件 ID，无线程归属））
        ChatEvent event = ChatEvent.create(sessionId, ChatEventType.AGENT_MESSAGE, "{\"text\":\"你好\"}", 1L);

        // then
        assertNotNull(event.eventId());
        assertTrue(event.eventId().startsWith(ChatEvent.EVENT_ID_PREFIX),
                "事件 ID 必须带 evt_ 前缀");
        assertEquals(sessionId, event.sessionId());
        assertEquals(ChatEventType.AGENT_MESSAGE, event.type());
        assertEquals("{\"text\":\"你好\"}", event.payload());
        assertEquals(1L, event.seq());
        assertNotNull(event.processedAt());
        assertNotNull(event.createdAt());
        assertNull(event.sessionThreadId(), "旧签名默认无线程归属");
    }

    @Test
    void should_keepCustomEventId_when_create_given_eventId() {
        // when（流式块最终事件复用帧的 evt_ ID，保证实时帧与落库事件关联 / 去重）
        ChatEvent event = ChatEvent.create("s-1", ChatEventType.AGENT_MESSAGE, "{}", 2L, "evt_abc123");

        // then
        assertEquals("evt_abc123", event.eventId());
        assertEquals(2L, event.seq());
    }

    @Test
    void should_defaultPayload_when_create_given_nullPayload() {
        // when（payload 空白收敛为空对象）
        ChatEvent event = ChatEvent.create("s-1", ChatEventType.SESSION_STATUS_IDLE, null, 1L);

        // then
        assertEquals("{}", event.payload());
    }

    @Test
    void should_attachThreadId_when_create_given_sessionThreadId() {
        // when（权威工厂带线程归属，同时留空 eventId 自动生成）
        ChatEvent event = ChatEvent.create("s-1", ChatEventType.AGENT_MESSAGE, "{}", 1L, null, "sthr_main");

        // then
        assertEquals("sthr_main", event.sessionThreadId());
        assertTrue(event.eventId().startsWith(ChatEvent.EVENT_ID_PREFIX),
                "eventId 空白时自动生成 evt_ 前缀 ID");
    }

    @Test
    void should_keepEventId_when_create_given_eventIdAndThreadId() {
        // when
        ChatEvent event = ChatEvent.create("s-1", ChatEventType.AGENT_MESSAGE, "{}", 1L,
                "evt_fixed", "sthr_main");

        // then
        assertEquals("evt_fixed", event.eventId());
        assertEquals("sthr_main", event.sessionThreadId());
    }

    @Test
    void should_throw_when_construct_given_blankEventId() {
        // given
        ChatEvent factory = ChatEvent.create("s-1", ChatEventType.AGENT_MESSAGE, "{}", 1L);

        // when & then（事件 ID 为空 → 不变量失败）
        assertThrows(IllegalArgumentException.class,
                () -> new ChatEvent(factory.id(), "", factory.sessionId(), factory.seq(),
                        factory.type(), factory.payload(), factory.processedAt(),
                        factory.createdAt(), factory.updatedAt(), factory.createdBy(),
                        factory.updatedBy(), factory.sessionThreadId()));
    }

    @Test
    void should_throw_when_construct_given_eventIdWithoutPrefix() {
        // given
        ChatEvent factory = ChatEvent.create("s-1", ChatEventType.AGENT_MESSAGE, "{}", 1L);

        // when & then（事件 ID 非 evt_ 前缀 → 不变量失败）
        assertThrows(IllegalArgumentException.class,
                () -> new ChatEvent(factory.id(), "abc", factory.sessionId(), factory.seq(),
                        factory.type(), factory.payload(), factory.processedAt(),
                        factory.createdAt(), factory.updatedAt(), factory.createdBy(),
                        factory.updatedBy(), factory.sessionThreadId()));
    }

    @Test
    void should_throw_when_construct_given_blankSessionId() {
        // given
        ChatEvent factory = ChatEvent.create("s-1", ChatEventType.AGENT_MESSAGE, "{}", 1L);

        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> new ChatEvent(factory.id(), factory.eventId(), "", factory.seq(),
                        factory.type(), factory.payload(), factory.processedAt(),
                        factory.createdAt(), factory.updatedAt(), factory.createdBy(),
                        factory.updatedBy(), factory.sessionThreadId()));
    }

    @Test
    void should_throw_when_construct_given_nullType() {
        // given
        ChatEvent factory = ChatEvent.create("s-1", ChatEventType.AGENT_MESSAGE, "{}", 1L);

        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> new ChatEvent(factory.id(), factory.eventId(), factory.sessionId(), factory.seq(),
                        null, factory.payload(), factory.processedAt(),
                        factory.createdAt(), factory.updatedAt(), factory.createdBy(),
                        factory.updatedBy(), factory.sessionThreadId()));
    }

    @Test
    void should_throw_when_construct_given_zeroSequenceNum() {
        // given
        ChatEvent factory = ChatEvent.create("s-1", ChatEventType.AGENT_MESSAGE, "{}", 1L);

        // when & then（seq 必须为正数）
        assertThrows(IllegalArgumentException.class,
                () -> new ChatEvent(factory.id(), factory.eventId(), factory.sessionId(), 0L,
                        factory.type(), factory.payload(), factory.processedAt(),
                        factory.createdAt(), factory.updatedAt(), factory.createdBy(),
                        factory.updatedBy(), factory.sessionThreadId()));
    }

    @Test
    void should_acceptNullProcessedAt_when_construct_given_unprocessedEvent() {
        // given（入站等尚未处理的事件：processedAt 由执行侧处理完成时回填）
        ChatEvent factory = ChatEvent.create("s-1", ChatEventType.AGENT_MESSAGE, "{}", 1L);

        // when
        ChatEvent event = new ChatEvent(factory.id(), factory.eventId(), factory.sessionId(), factory.seq(),
                factory.type(), factory.payload(), null,
                factory.createdAt(), factory.updatedAt(), factory.createdBy(),
                factory.updatedBy(), factory.sessionThreadId());

        // then（processedAt 可空且原样保留，未处理事件合法）
        assertNull(event.processedAt());
    }

    @Test
    void should_convergeThreadIdToNull_when_construct_given_blankThreadId() {
        // given
        ChatEvent factory = ChatEvent.create("s-1", ChatEventType.AGENT_MESSAGE, "{}", 1L);

        // when（线程归属空白串收敛为 null，视为合法会话级事件）
        ChatEvent event = new ChatEvent(factory.id(), factory.eventId(), factory.sessionId(), factory.seq(),
                factory.type(), factory.payload(), factory.processedAt(),
                factory.createdAt(), factory.updatedAt(), factory.createdBy(),
                factory.updatedBy(), "   ");

        // then
        assertNull(event.sessionThreadId());
    }

    @Test
    void should_throw_when_construct_given_threadIdWithoutPrefix() {
        // given
        ChatEvent factory = ChatEvent.create("s-1", ChatEventType.AGENT_MESSAGE, "{}", 1L);

        // when & then（线程归属非 sthr_ 前缀 → 不变量失败）
        assertThrows(IllegalArgumentException.class,
                () -> new ChatEvent(factory.id(), factory.eventId(), factory.sessionId(), factory.seq(),
                        factory.type(), factory.payload(), factory.processedAt(),
                        factory.createdAt(), factory.updatedAt(), factory.createdBy(),
                        factory.updatedBy(), "tid_wrong"));
    }

    @Test
    void should_restoreEvent_when_restore_given_fullFields() {
        // given（DB 恢复走 restore：含落库 ID 与审计字段与线程归属）
        ChatEvent origin = ChatEvent.create("s-1", ChatEventType.AGENT_THINKING, "{\"text\":\"推理\"}", 2L,
                null, "sthr_main");

        // when
        ChatEvent restored = ChatEvent.restore(
                5L, origin.eventId(), origin.sessionId(), origin.seq(), origin.type(),
                origin.payload(), origin.processedAt(), origin.createdAt(), origin.updatedAt(),
                origin.createdBy(), origin.updatedBy(), origin.sessionThreadId());

        // then
        assertEquals(5L, restored.id());
        assertEquals(ChatEventType.AGENT_THINKING, restored.type());
        assertEquals(2L, restored.seq());
        assertEquals(origin.eventId(), restored.eventId());
        assertEquals("sthr_main", restored.sessionThreadId());
        assertEquals(OffsetDateTime.class, restored.processedAt().getClass());
    }
}
