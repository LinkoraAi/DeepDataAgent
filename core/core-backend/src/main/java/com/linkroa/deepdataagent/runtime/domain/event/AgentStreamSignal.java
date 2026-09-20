package com.linkroa.deepdataagent.runtime.domain.event;

/**
 * Agent 流信号（领域中性事件）。
 * <p>由基础设施层经 {@code AgentEvent → AgentStreamSignal} 映射产生，作为应用层
 * {@code doOnNext} 编排的输入；语义等价于 SDK 原始事件但零框架依赖：</p>
 * <ul>
 *   <li>{@code text / blockId}：TEXT / THINKING 增量与块结束标记；</li>
 *   <li>{@code toolCallId / toolName / toolState}：工具调用与结果关联；</li>
 *   <li>{@code resultText}：AGENT_RESULT 最终文本；</li>
 *   <li>{@code inputTokens / outputTokens / modelName}：MODEL_CALL_END 的 llm.call span 数据。</li>
 * </ul>
 */
public record AgentStreamSignal(
        AgentStreamSignalType type,
        String text,
        String blockId,
        String toolCallId,
        String toolName,
        String toolState,
        String resultText,
        Integer inputTokens,
        Integer outputTokens,
        String modelName,
        String replyId,
        java.util.List<String> toolCallIds
) {

    public AgentStreamSignal {
        if (type == null) {
            throw new IllegalArgumentException("Agent 流信号类型不能为空");
        }
    }

    /**
     * 便捷构造：纯文本增量事件（thinking / message / 工具增量）。
     */
    public static AgentStreamSignal of(AgentStreamSignalType type, String text, String blockId) {
        return new AgentStreamSignal(type, text, blockId, null, null, null, null, null, null, null, null, null);
    }

    /**
     * 便捷构造：工具调用相关事件。
     */
    public static AgentStreamSignal tool(AgentStreamSignalType type, String toolCallId, String toolName,
                                         String text, String toolState) {
        return new AgentStreamSignal(type, text, null, toolCallId, toolName, toolState, null, null, null, null,
                null, null);
    }

    /**
     * 便捷构造：HITL 人工介入事件（关联 reply_id，无批次明细）。
     */
    public static AgentStreamSignal hitl(AgentStreamSignalType type, String replyId) {
        return new AgentStreamSignal(type, null, null, null, null, null, null, null, null, null, replyId, null);
    }

    /**
     * 便捷构造：HITL 挂起事件（关联 reply_id + 待确认工具调用批次 id 列表）。
     * <p>SDK {@code REQUIRE_*} 事件按 reply 整批携带待确认工具调用，批次 id 透传给
     * 应用层装配 {@code session.requires_action} 明细（事件表锚点即由此建立），
     * durable 确认解析据此重建整批现场。</p>
     */
    public static AgentStreamSignal hitl(AgentStreamSignalType type, String replyId,
                                         java.util.List<String> toolCallIds) {
        return new AgentStreamSignal(type, null, null, null, null, null, null, null, null, null, replyId,
                toolCallIds == null || toolCallIds.isEmpty() ? null : java.util.List.copyOf(toolCallIds));
    }

    /**
     * 便捷构造：信号是否携带最终结果文本（AGENT_RESULT）。
     */
    public AgentStreamSignal withResultText(String resultText) {
        return new AgentStreamSignal(type, text, blockId, toolCallId, toolName, toolState, resultText,
                inputTokens, outputTokens, modelName, replyId, toolCallIds);
    }
}