package com.linkroa.deepdataagent.runtime.application.convert;

import com.linkroa.deepdataagent.runtime.application.contract.SseEventEnvelope;
import com.linkroa.deepdataagent.runtime.domain.model.ChatEvent;
import com.linkroa.deepdataagent.runtime.domain.model.enums.ChatEventType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link SseEventEnvelopeConvert} 应用层信封转换单测：Message 信封结构 / role 派生 /
 * content 直取与 data 块包装 / session_status 归一化。
 */
class SseEventEnvelopeConvertTest {

    private final SseEventEnvelopeConvert converter = SseEventEnvelopeConvert.INSTANCE;

    @Test
    void should_buildMessageEnvelope_when_toEnvelope_given_textBlockEvent() {
        // given
        ChatEvent event = ChatEvent.create("s-1", "r-1", ChatEventType.MESSAGE,
                "{\"content\":[{\"type\":\"text\",\"text\":\"你好\",\"block_id\":\"blk-1\"}],\"is_last\":false}", 3L);

        // when
        SseEventEnvelope envelope = converter.toEnvelope(event);

        // then（信封固定字段 + content 直取 + metadata 承载 session_id/round_id/is_last）
        assertEquals("message", envelope.object());
        assertEquals(event.eventId(), envelope.id());
        assertEquals("assistant", envelope.role());
        assertEquals("message", envelope.type());
        assertEquals("completed", envelope.status());
        assertEquals(3L, envelope.sequence_number());
        assertEquals("s-1", envelope.metadata().get("session_id"));
        assertEquals("r-1", envelope.metadata().get("round_id"));
        assertEquals(false, envelope.metadata().get("is_last"));
        assertEquals(1, envelope.content().size());
        assertEquals("text", envelope.content().get(0).get("type"));
        assertEquals("你好", envelope.content().get(0).get("text"));
    }

    @Test
    void should_deriveToolRole_when_toEnvelope_given_toolCallEvent() {
        // given
        ChatEvent event = ChatEvent.create("s-1", "r-1", ChatEventType.TOOL_CALL,
                "{\"content\":[{\"type\":\"tool_call\",\"tool_call_id\":\"tc-1\",\"name\":\"search\"}],\"is_last\":true}", 4L);

        // when
        SseEventEnvelope envelope = converter.toEnvelope(event);

        // then（工具调用类事件 role=tool，type 小写）
        assertEquals("tool", envelope.role());
        assertEquals("tool_call", envelope.type());
    }

    @Test
    void should_deriveUserRole_when_toEnvelope_given_userMessageEvent() {
        // given
        ChatEvent event = ChatEvent.create("s-1", "r-1", ChatEventType.USER_MESSAGE,
                "{\"content\":[{\"type\":\"text\",\"text\":\"你好\"}],\"is_last\":true}", 4L);

        // when
        SseEventEnvelope envelope = converter.toEnvelope(event);

        // then（用户消息回显 role=user，type 小写，content 直取文本块）
        assertEquals("user", envelope.role());
        assertEquals("user_message", envelope.type());
        assertEquals("text", envelope.content().get(0).get("type"));
        assertEquals("你好", envelope.content().get(0).get("text"));
    }

    @Test
    void should_wrapFlatPayloadAsDataBlock_when_toEnvelope_given_runStartEvent() {
        // given
        ChatEvent event = ChatEvent.create("s-1", "r-1", ChatEventType.RUN_START,
                "{\"round_id\":\"r-1\",\"run_id\":\"run-42\"}", 1L);

        // when
        SseEventEnvelope envelope = converter.toEnvelope(event);

        // then（扁平合成事件包装为 data 块，run_id 保留在 content[0].data）
        assertEquals("run_start", envelope.type());
        assertEquals(1, envelope.content().size());
        assertEquals("data", envelope.content().get(0).get("type"));
        assertEquals("run-42", dataOf(envelope, 0).get("run_id"));
    }

    @Test
    void should_normalizeSessionStatus_when_toEnvelope_given_sessionStatusEvent() {
        // given
        ChatEvent event = ChatEvent.create("s-1", "r-1", ChatEventType.SESSION_STATUS,
                "{\"status\":\"IDLE\",\"stop_reason\":\"stop\"}", 5L);

        // when
        SseEventEnvelope envelope = converter.toEnvelope(event);

        // then（status→session_status 更名、值保持枚举名，stop_reason 保留字符串）
        assertEquals("session_status", envelope.type());
        assertEquals(1, envelope.content().size());
        Map<String, Object> data = dataOf(envelope, 0);
        assertEquals("IDLE", data.get("session_status"));
        assertEquals("stop", data.get("stop_reason"));
    }

    @Test
    void should_useEmptyDataBlock_when_toEnvelope_given_malformedPayload() {
        // given（非法 payload 收敛空对象，不阻断事件流）
        ChatEvent event = ChatEvent.create("s-1", "r-1", ChatEventType.RUN_START, "{非法 json", 2L);

        // when
        SseEventEnvelope envelope = converter.toEnvelope(event);

        // then（数据块仍产出，data 为空）
        assertEquals("run_start", envelope.type());
        assertEquals(1, envelope.content().size());
        assertEquals("data", envelope.content().get(0).get("type"));
    }

    @Test
    void should_mapHumanConfirmTypes_when_toEnvelope_given_hitlEvents() {
        // given（HITL 事件为扁平载荷，携带 reply_id 关联待确认项 / 确认结果）
        ChatEvent required = ChatEvent.create("s-1", "r-1", ChatEventType.HUMAN_CONFIRM_REQUIRED,
                "{\"reply_id\":\"reply-1\"}", 6L);
        ChatEvent result = ChatEvent.create("s-1", "r-1", ChatEventType.HUMAN_CONFIRM_RESULT,
                "{\"reply_id\":\"reply-1\"}", 7L);

        // when
        SseEventEnvelope requiredEnv = converter.toEnvelope(required);
        SseEventEnvelope resultEnv = converter.toEnvelope(result);

        // then（type 小写 + role=assistant（非工具类）+ 扁平 payload 包装为 data 块）
        assertEquals("human_confirm_required", requiredEnv.type());
        assertEquals("assistant", requiredEnv.role());
        assertEquals("data", requiredEnv.content().get(0).get("type"));
        assertEquals("reply-1", dataOf(requiredEnv, 0).get("reply_id"));
        assertEquals("human_confirm_result", resultEnv.type());
        assertEquals("assistant", resultEnv.role());
        assertEquals("reply-1", dataOf(resultEnv, 0).get("reply_id"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataOf(SseEventEnvelope envelope, int index) {
        return (Map<String, Object>) envelope.content().get(index).get("data");
    }
}