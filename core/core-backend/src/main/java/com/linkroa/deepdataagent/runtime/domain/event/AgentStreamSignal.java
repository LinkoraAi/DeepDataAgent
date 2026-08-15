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
        String modelName
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
        return new AgentStreamSignal(type, text, blockId, null, null, null, null, null, null, null);
    }

    /**
     * 便捷构造：工具调用相关事件。
     */
    public static AgentStreamSignal tool(AgentStreamSignalType type, String toolCallId, String toolName,
                                         String text, String toolState) {
        return new AgentStreamSignal(type, text, null, toolCallId, toolName, toolState, null, null, null, null);
    }

    /**
     * 便捷构造：信号是否携带最终结果文本（AGENT_RESULT）。
     */
    public AgentStreamSignal withResultText(String resultText) {
        return new AgentStreamSignal(type, text, blockId, toolCallId, toolName, toolState, resultText,
                inputTokens, outputTokens, modelName);
    }
}