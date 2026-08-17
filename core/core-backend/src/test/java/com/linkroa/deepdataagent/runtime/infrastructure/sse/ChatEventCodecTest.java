package com.linkroa.deepdataagent.runtime.infrastructure.sse;

import com.linkroa.deepdataagent.runtime.application.contract.SseEventEnvelope;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ChatEventCodec} SSE 编解码单测：信封序列化为 snake_case，不泄漏领域字段。
 */
class ChatEventCodecTest {

    @Test
    void should_serializeSnakeCase_when_toJson_given_envelope() {
        // given
        SseEventEnvelope envelope = new SseEventEnvelope(
                "message", "evt-1", OffsetDateTime.now(ZoneId.of("Asia/Shanghai")),
                "assistant", "message",
                List.of(Map.of("type", "text", "text", "你好")),
                Map.of("session_id", "s-1", "round_id", "r-1"),
                "completed", 3L);

        // when
        String json = ChatEventCodec.toJson(envelope);

        // then（snake_case 输出，不泄漏领域字段 / camelCase 变体）
        assertTrue(json.contains("\"object\":\"message\""));
        assertTrue(json.contains("\"created_at\":"));
        assertTrue(json.contains("\"sequence_number\":3"));
        assertTrue(json.contains("\"session_id\":\"s-1\""));
        assertFalse(json.contains("\"createdAt\""));
        assertFalse(json.contains("\"sequenceNum\""));
        assertFalse(json.contains("\"eventId\""));
        assertFalse(json.contains("\"payload\""));
    }
}