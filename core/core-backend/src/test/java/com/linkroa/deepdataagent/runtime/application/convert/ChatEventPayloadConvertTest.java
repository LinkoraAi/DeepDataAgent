package com.linkroa.deepdataagent.runtime.application.convert;

import com.linkroa.deepdataagent.runtime.domain.model.enums.ChatEventType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ChatEventPayloadConvert} 应用层 payload 转换单测：content-blocks 结构与 is_last 标记。
 */
class ChatEventPayloadConvertTest {

    private final ChatEventPayloadConvert converter = ChatEventPayloadConvert.INSTANCE;

    @Test
    void should_assembleThinkingDelta_when_thinkingDelta_given_textAndBlockId() {
        // when
        ChatEventPayloadConvert.AssembledEvent event = converter.thinkingDelta("th-1", "思考中");

        // then（content-blocks 结构：type/thinking + text + block_id + is_last=false）
        assertEquals(ChatEventType.THINKING, event.type());
        assertTrue(event.payloadJson().contains("\"type\":\"thinking\""));
        assertTrue(event.payloadJson().contains("\"text\":\"思考中\""));
        assertTrue(event.payloadJson().contains("\"block_id\":\"th-1\""));
        assertTrue(event.payloadJson().contains("\"is_last\":false"));
    }

    @Test
    void should_assembleTextEnd_when_textEnd_given_blockId() {
        // when
        ChatEventPayloadConvert.AssembledEvent event = converter.textEnd("blk-1");

        // then（空 delta + is_last=true 收尾）
        assertEquals(ChatEventType.MESSAGE, event.type());
        assertTrue(event.payloadJson().contains("\"type\":\"text\""));
        assertTrue(event.payloadJson().contains("\"block_id\":\"blk-1\""));
        assertTrue(event.payloadJson().contains("\"is_last\":true"));
    }

    @Test
    void should_assembleUserMessage_when_userMessage_given_text() {
        // when
        ChatEventPayloadConvert.AssembledEvent event = converter.userMessage("你好");

        // then（USER_MESSAGE 类型 + 文本内容块 + is_last=true 单块收尾）
        assertEquals(ChatEventType.USER_MESSAGE, event.type());
        assertTrue(event.payloadJson().contains("\"type\":\"text\""));
        assertTrue(event.payloadJson().contains("\"text\":\"你好\""));
        assertTrue(event.payloadJson().contains("\"is_last\":true"));
    }

    @Test
    void should_assembleToolCallEnd_when_toolCallEnd_given_validArgsJson() {
        // when
        ChatEventPayloadConvert.AssembledEvent event =
                converter.toolCallEnd("tc-1", "search", "{\"q\":\"x\"}");

        // then（聚合入参作为嵌套对象一并下发）
        assertEquals(ChatEventType.TOOL_CALL, event.type());
        assertTrue(event.payloadJson().contains("\"type\":\"tool_call\""));
        assertTrue(event.payloadJson().contains("\"tool_call_id\":\"tc-1\""));
        assertTrue(event.payloadJson().contains("\"name\":\"search\""));
        assertTrue(event.payloadJson().contains("\"q\":\"x\""));
        assertTrue(event.payloadJson().contains("\"is_last\":true"));
    }

    @Test
    void should_convergeInvalidInputToEmpty_when_toolCallEnd_given_malformedArgsJson() {
        // when（非法 JSON 与空白入参均收敛为空对象，不阻断事件流）
        ChatEventPayloadConvert.AssembledEvent malformed =
                converter.toolCallEnd("tc-1", "search", "{非法 json");
        ChatEventPayloadConvert.AssembledEvent blank =
                converter.toolCallEnd("tc-2", "search", null);

        // then：tool_call 仍发出（携带调用关联 + is_last），但入参不落地
        assertTrue(malformed.payloadJson().contains("\"type\":\"tool_call\""));
        assertTrue(malformed.payloadJson().contains("\"tool_call_id\":\"tc-1\""));
        assertTrue(malformed.payloadJson().contains("\"is_last\":true"));
        assertTrue(!malformed.payloadJson().contains("\"q\":\""));
        assertTrue(blank.payloadJson().contains("\"type\":\"tool_call\""));
        assertTrue(!blank.payloadJson().contains("\"q\":\""));
    }

    @Test
    void should_assembleToolResultEnd_when_toolResultEnd_given_truncatedOutput() {
        // when
        ChatEventPayloadConvert.AssembledEvent event =
                converter.toolResultEnd("tc-1", "search", "尾窗补发...", true);

        // then（tool_result + output + truncated 标记 + is_last=true）
        assertEquals(ChatEventType.TOOL_CALL_OUTPUT, event.type());
        assertTrue(event.payloadJson().contains("\"type\":\"tool_result\""));
        assertTrue(event.payloadJson().contains("\"output\":\"尾窗补发...\""));
        assertTrue(event.payloadJson().contains("\"truncated\":true"));
        assertTrue(event.payloadJson().contains("\"is_last\":true"));
    }
}