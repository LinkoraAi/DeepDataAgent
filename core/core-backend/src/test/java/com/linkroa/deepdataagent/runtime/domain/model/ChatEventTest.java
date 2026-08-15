package com.linkroa.deepdataagent.runtime.domain.model;

import com.linkroa.deepdataagent.runtime.domain.model.enums.ChatEventType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link ChatEvent} 领域模型不变量单测。
 */
class ChatEventTest {

    @Test
    void should_createEvent_when_create_given_validInputs() {
        // given
        String sessionId = "s-1";
        String roundId = "r-1";

        // when
        ChatEvent event = ChatEvent.create(sessionId, roundId, ChatEventType.MESSAGE, "{\"delta\":\"你好\"}", 1L);

        // then
        assertNotNull(event.eventId());
        assertEquals(sessionId, event.sessionId());
        assertEquals(roundId, event.roundId());
        assertEquals(ChatEventType.MESSAGE, event.eventType());
        assertEquals(1L, event.sequenceNum());
        assertNotNull(event.createdAt());
    }

    @Test
    void should_defaultPayload_when_create_given_nullPayload() {
        // given
        // when
        ChatEvent event = ChatEvent.create("s-1", "r-1", ChatEventType.RUN_START, null, 1L);

        // then
        assertEquals("{}", event.payload());
    }

    @Test
    void should_throw_when_construct_given_blankEventId() {
        // given
        ChatEvent factory = ChatEvent.create("s-1", "r-1", ChatEventType.RUN_START, "{}", 1L);

        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> new ChatEvent(factory.id(), "", factory.sessionId(), factory.roundId(),
                        factory.eventType(), factory.payload(), factory.sequenceNum(),
                        factory.createdAt(), factory.updatedAt(), factory.createdBy(), factory.updatedBy()));
    }

    @Test
    void should_throw_when_construct_given_blankSessionId() {
        // given
        ChatEvent factory = ChatEvent.create("s-1", "r-1", ChatEventType.RUN_START, "{}", 1L);

        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> new ChatEvent(factory.id(), factory.eventId(), "", factory.roundId(),
                        factory.eventType(), factory.payload(), factory.sequenceNum(),
                        factory.createdAt(), factory.updatedAt(), factory.createdBy(), factory.updatedBy()));
    }

    @Test
    void should_throw_when_construct_given_blankRoundId() {
        // given
        ChatEvent factory = ChatEvent.create("s-1", "r-1", ChatEventType.RUN_START, "{}", 1L);

        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> new ChatEvent(factory.id(), factory.eventId(), factory.sessionId(), "",
                        factory.eventType(), factory.payload(), factory.sequenceNum(),
                        factory.createdAt(), factory.updatedAt(), factory.createdBy(), factory.updatedBy()));
    }

    @Test
    void should_throw_when_construct_given_nullType() {
        // given
        ChatEvent factory = ChatEvent.create("s-1", "r-1", ChatEventType.RUN_START, "{}", 1L);

        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> new ChatEvent(factory.id(), factory.eventId(), factory.sessionId(), factory.roundId(),
                        null, factory.payload(), factory.sequenceNum(),
                        factory.createdAt(), factory.updatedAt(), factory.createdBy(), factory.updatedBy()));
    }

    @Test
    void should_throw_when_construct_given_zeroSequenceNum() {
        // given
        ChatEvent factory = ChatEvent.create("s-1", "r-1", ChatEventType.RUN_START, "{}", 1L);

        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> new ChatEvent(factory.id(), factory.eventId(), factory.sessionId(), factory.roundId(),
                        factory.eventType(), factory.payload(), 0L,
                        factory.createdAt(), factory.updatedAt(), factory.createdBy(), factory.updatedBy()));
    }

    @Test
    void should_restoreEvent_when_restore_given_fullFields() {
        // given
        ChatEvent origin = ChatEvent.create("s-1", "r-1", ChatEventType.THINKING, "{\"delta\":\"推理\"}", 2L);

        // when
        ChatEvent restored = ChatEvent.restore(
                5L, origin.eventId(), origin.sessionId(), origin.roundId(), origin.eventType(),
                origin.payload(), origin.sequenceNum(), origin.createdAt(), origin.updatedAt(),
                origin.createdBy(), origin.updatedBy());

        // then
        assertEquals(5L, restored.id());
        assertEquals(ChatEventType.THINKING, restored.eventType());
        assertEquals(2L, restored.sequenceNum());
    }
}