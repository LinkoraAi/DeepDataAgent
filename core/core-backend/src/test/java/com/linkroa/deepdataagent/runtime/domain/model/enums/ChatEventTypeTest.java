package com.linkroa.deepdataagent.runtime.domain.model.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link ChatEventType} 反解单测。
 */
class ChatEventTypeTest {

    @Test
    void should_parseRunStart_when_fromValue_given_lowerCaseName() {
        // when
        ChatEventType type = ChatEventType.fromValue("run_start");

        // then
        assertEquals(ChatEventType.RUN_START, type);
    }

    @Test
    void should_parseThinking_when_fromValue_given_lowerCaseName() {
        // when
        ChatEventType type = ChatEventType.fromValue("thinking");

        // then
        assertEquals(ChatEventType.THINKING, type);
    }

    @Test
    void should_parseUpperCamel_when_fromValue_given_upperValue() {
        // when
        ChatEventType type = ChatEventType.fromValue("TOOL_CALL_OUTPUT");

        // then
        assertEquals(ChatEventType.TOOL_CALL_OUTPUT, type);
    }

    @Test
    void should_parseProgressAndMaxIter_when_fromValue_given_syntheticEventNames() {
        // when
        ChatEventType progress = ChatEventType.fromValue("agent_progress");
        ChatEventType maxIters = ChatEventType.fromValue("exceed_max_iters");

        // then
        assertEquals(ChatEventType.AGENT_PROGRESS, progress);
        assertEquals(ChatEventType.EXCEED_MAX_ITERS, maxIters);
    }

    @Test
    void should_throw_when_fromValue_given_null() {
        // when & then
        assertThrows(IllegalArgumentException.class, () -> ChatEventType.fromValue(null));
    }

    @Test
    void should_throw_when_fromValue_given_unknownValue() {
        // when & then
        assertThrows(IllegalArgumentException.class, () -> ChatEventType.fromValue("unknown_type"));
    }

    @Test
    void should_countTwelveSseEvents_when_fromValue_given_allSpecEvents() {
        // given（12 个 SSE 合同事件：含进度占位与迭代上限合成事件）
        String[] names = {"run_start", "thinking", "message", "tool_call", "tool_call_output",
                "summary", "run_end", "run_error", "session_status", "error",
                "agent_progress", "exceed_max_iters"};

        // when
        long resolvable = java.util.Arrays.stream(names)
                .map(ChatEventType::fromValue)
                .count();

        // then（12 个 SSE 合同事件全部可反解）
        assertEquals(12L, resolvable);
    }
}