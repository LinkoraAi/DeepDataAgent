package com.linkroa.deepdataagent.agent.domain.model;

/**
 * 对话内容值对象
 * <p>
 * 封装单条消息的内容，根据 MessageRole 不同有不同含义：
 * - TOOL_CALL: title=工具名, input=入参JSON, result=返回结果JSON, toolCallId=工具调用ID
 * - TOOL_RESULT: title=工具名, result=返回结果JSON, toolCallId=工具调用ID（与对应 TOOL_CALL 一致，用于配对）
 * - USER/SYSTEM/ASSISTANT: 只使用 result 字段存储文本内容
 * - THINKING: result 存储思考过程文本
 * </p>
 */
public record DialogueContent(
        String title,
        String input,
        String result,
        String toolCallId
) {
    public DialogueContent {
        // result 不可为 null
        if (result == null) {
            result = "";
        }
    }

    /** 创建纯文本内容（USER/ASSISTANT/SYSTEM/THINKING/ERROR 场景） */
    public static DialogueContent text(String text) {
        return new DialogueContent(null, null, text, null);
    }

    /**
     * 创建工具调用内容（TOOL_CALL 场景）
     *
     * @param toolName    工具名
     * @param inputJson   入参 JSON
     * @param resultJson  返回结果 JSON
     * @param toolCallId  工具调用 ID（与对应 TOOL_RESULT 消息一致，用于调用与结果配对）
     * @return 工具调用内容
     */
    public static DialogueContent toolCall(String toolName, String inputJson, String resultJson, String toolCallId) {
        return new DialogueContent(toolName, inputJson, resultJson, toolCallId);
    }

    /** 创建工具返回结果内容（TOOL_RESULT 场景，无工具名） */
    public static DialogueContent toolResult(String resultJson) {
        return new DialogueContent(null, null, resultJson, null);
    }

    /**
     * 创建携带工具名的工具返回结果内容（TOOL_RESULT 场景）
     *
     * @param toolName   工具名
     * @param resultJson 返回结果 JSON
     * @param toolCallId 工具调用 ID（与对应 TOOL_CALL 消息一致，用于调用与结果配对）
     * @return 工具结果内容
     */
    public static DialogueContent toolResult(String toolName, String resultJson, String toolCallId) {
        return new DialogueContent(toolName, null, resultJson, toolCallId);
    }
}
