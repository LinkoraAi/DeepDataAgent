package com.linkroa.deepdataagent.runtime.domain.model;

import com.linkroa.deepdataagent.runtime.domain.model.enums.ChatEventType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link StreamFrame} 流式帧载体不变量单测：帧类型 / 目标类型 / 关联事件 ID / payload 非空校验。
 */
class StreamFrameTest {

    @Test
    void should_holdComponents_when_new_given_validFrame() {
        // when
        StreamFrame frame = new StreamFrame(ChatEventType.EVENT_DELTA, "evt_1",
                ChatEventType.AGENT_MESSAGE, "{\"event_id\":\"evt_1\"}");

        // then
        assertEquals(ChatEventType.EVENT_DELTA, frame.frameType());
        assertEquals("evt_1", frame.eventId());
        assertEquals(ChatEventType.AGENT_MESSAGE, frame.targetType());
        assertEquals("{\"event_id\":\"evt_1\"}", frame.payloadJson());
    }

    @Test
    void should_reject_when_new_given_missingComponents() {
        // when & then（帧类型 / 目标类型不可为空）
        assertThrows(IllegalArgumentException.class,
                () -> new StreamFrame(null, "evt_1", ChatEventType.AGENT_MESSAGE, "{}"));
        assertThrows(IllegalArgumentException.class,
                () -> new StreamFrame(ChatEventType.EVENT_START, "evt_1", null, "{}"));
        // 关联事件 ID 非空（SSE id 与 buffered id 一致性前提）
        assertThrows(IllegalArgumentException.class,
                () -> new StreamFrame(ChatEventType.EVENT_START, " ", ChatEventType.AGENT_MESSAGE, "{}"));
        // payload 非空
        assertThrows(IllegalArgumentException.class,
                () -> new StreamFrame(ChatEventType.EVENT_START, "evt_1", ChatEventType.AGENT_MESSAGE, null));
    }
}
