package com.linkroa.deepdataagent.agent.domain.model;

/**
 * 对话内容值对象
 * <p>
 * 封装单条消息的内容，根据 MessageRole 不同有不同含义：
 * - TOOL_CALL: title=工具名, input=入参JSON, result=返回结果JSON
 * - USER/SYSTEM/ASSISTANT: 只使用 result 字段存储文本内容
 * - THINKING: result 存储思考过程文本
 * </p>
 */
public record DialogueContent(
        String title,
        String input,
        String result
) {
    public DialogueContent {
        // result 不可为 null
        if (result == null) {
            result = "";
        }
    }

    /** 创建纯文本内容（USER/ASSISTANT/SYSTEM/THINKING/ERROR 场景） */
    public static DialogueContent text(String text) {
        return new DialogueContent(null, null, text);
    }

    /** 创建工具调用内容（TOOL_CALL 场景） */
    public static DialogueContent toolCall(String toolName, String inputJson, String resultJson) {
        return new DialogueContent(toolName, inputJson, resultJson);
    }

    /** 创建工具返回结果内容（TOOL_RESULT 场景） */
    public static DialogueContent toolResult(String resultJson) {
        return new DialogueContent(null, null, resultJson);
    }
}
