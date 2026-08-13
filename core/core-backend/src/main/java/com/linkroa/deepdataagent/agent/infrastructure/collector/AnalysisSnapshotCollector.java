package com.linkroa.deepdataagent.agent.infrastructure.collector;

import com.linkroa.deepdataagent.agent.domain.model.DialogueMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 分析状态收集器
 * <p>流式处理过程中的单一累积源：收集事件所需的中间状态，仅保留流式推送与落库快照所需的最小状态——
 * 思考文本缓冲、助手报告缓冲、工具调用记录（含入参累积）、工具结果缓冲。
 * 进行中 {@link DialogueMessage} 仅在收敛或落库快照时从此处一次性读取完整文本，避免逐 delta 重复持有。</p>
 * <p>注意：此类非线程安全，仅在单个流式处理过程中使用；并发追加由外部加锁保护。</p>
 */
public class AnalysisSnapshotCollector {

    /** 工具调用记录列表（含 toolCallId、工具名、入参累积） */
    private final List<ToolCallItem> toolCalls = Collections.synchronizedList(new ArrayList<>());
    /** 使用 StringBuilder 累积当前思考块增量，thinking_end 时 flush 为完整思考文本 */
    private final StringBuilder thinkingBuffer = new StringBuilder();
    /** 助手报告文本增量缓冲区，累积 TEXT_BLOCK_DELTA 事件，agent_end 时 flush 为完整报告 */
    private final StringBuilder assistantBuffer = new StringBuilder();
    /** 工具结果增量缓冲区，按 toolCallId 分组累积 TOOL_RESULT_TEXT_DELTA 事件 */
    private final Map<String, StringBuilder> toolResultBuffers = new HashMap<>();

    /**
     * 累积思考增量文本
     * <p>LLM 流式输出思考内容时，每个 delta 调用此方法累积到缓冲区，
     * 等待 {@link #flushThinkingStep()} 将完整内容取出。</p>
     *
     * @param delta 思考文本增量
     */
    public void addThinkingStep(String delta) {
        if (delta != null) {
            thinkingBuffer.append(delta);
        }
    }

    /**
     * 将当前累积的思考增量 flush 为一条完整思考文本
     * <p>在 THINKING_BLOCK_END 事件时调用，返回累积的完整思考内容并清空缓冲区。</p>
     *
     * @return 完整思考文本；若缓冲区为空返回 null
     */
    public String flushThinkingStep() {
        String text = thinkingBuffer.toString().trim();
        thinkingBuffer.setLength(0);
        return text.isEmpty() ? null : text;
    }

    /**
     * 获取当前思考缓冲文本（不清空）
     * <p>用于进行中 THINKING 消息的落库快照，返回累积的完整思考内容，不改变缓冲状态。</p>
     *
     * @return 当前累积的思考文本；缓冲区为空返回 null
     */
    public String getThinkingBuffer() {
        return thinkingBuffer.isEmpty() ? null : thinkingBuffer.toString();
    }

    /**
     * 累积助手报告文本增量
     * <p>LLM 流式输出最终报告时，每个 TEXT_BLOCK_DELTA 增量调用此方法累积到缓冲区。</p>
     *
     * @param delta 报告文本增量
     */
    public void addAssistantStep(String delta) {
        if (delta != null) {
            assistantBuffer.append(delta);
        }
    }

    /**
     * 获取当前助手报告缓冲文本（不清空）
     * <p>用于进行中 ASSISTANT 消息的落库快照，返回累积的完整报告内容，不改变缓冲状态。</p>
     *
     * @return 当前累积的报告文本；缓冲区为空返回 null
     */
    public String getAssistantBuffer() {
        return assistantBuffer.isEmpty() ? null : assistantBuffer.toString();
    }

    /**
     * 将当前累积的助手报告增量 flush 为完整报告文本
     * <p>在 AGENT_END 或 TOOL_CALL_START（叙述转思考）时调用，返回完整报告内容并清空缓冲区。</p>
     *
     * @return 完整报告文本；若缓冲区为空返回 null
     */
    public String flushAssistantStep() {
        String text = assistantBuffer.toString().trim();
        assistantBuffer.setLength(0);
        return text.isEmpty() ? null : text;
    }

    /**
     * 添加工具调用
     *
     * @param toolCall 工具调用项
     */
    public void addToolCall(ToolCallItem toolCall) {
        toolCalls.add(toolCall);
    }

    /**
     * 根据工具调用ID获取工具调用项
     *
     * @param toolCallId 工具调用ID
     * @return 工具调用项，如果不存在返回null
     */
    public ToolCallItem getToolCallById(String toolCallId) {
        if (toolCallId == null) {
            return null;
        }
        return toolCalls.stream()
                .filter(tc -> toolCallId.equals(tc.toolCallId()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 追加工具调用参数增量
     *
     * @param toolCallId 工具调用ID
     * @param delta      参数增量文本
     */
    public void appendToolCallInput(String toolCallId, String delta) {
        ToolCallItem toolCall = getToolCallById(toolCallId);
        if (toolCall != null && delta != null) {
            int index = toolCalls.indexOf(toolCall);
            if (index >= 0) {
                ToolCallItem updated = new ToolCallItem(
                        toolCall.name(),
                        toolCall.input() == null ? delta : toolCall.input() + delta,
                        toolCall.result(),
                        toolCall.startTime(),
                        toolCall.endTime(),
                        toolCall.status(),
                        toolCall.toolCallId()
                );
                toolCalls.set(index, updated);
            }
        }
    }

    /**
     * 追加工具结果文本增量
     * <p>AgentScope 的 TOOL_RESULT_TEXT_DELTA 事件携带工具返回值的增量文本，
     * 此方法按 toolCallId 分组累积到缓冲区，等待 TOOL_RESULT_END 时取回完整结果。</p>
     *
     * @param toolCallId 工具调用ID
     * @param delta      结果文本增量
     */
    public void appendToolResultDelta(String toolCallId, String delta) {
        if (toolCallId == null || delta == null) {
            return;
        }
        toolResultBuffers.computeIfAbsent(toolCallId, k -> new StringBuilder()).append(delta);
    }

    /**
     * 获取累积的工具结果内容
     *
     * @param toolCallId 工具调用ID
     * @return 累积的完整结果文本，如果无累积则返回 null
     */
    public String getToolResult(String toolCallId) {
        if (toolCallId == null) {
            return null;
        }
        StringBuilder buffer = toolResultBuffers.get(toolCallId);
        if (buffer == null || buffer.isEmpty()) {
            return null;
        }
        return buffer.toString();
    }

    /**
     * 设置工具调用的完整结果
     * <p>更新 ToolCallItem 的 result、endTime、status 字段，用于 TOOL_RESULT_END 时记录工具执行状态。</p>
     *
     * @param toolCallId    工具调用ID
     * @param resultContent 工具结果内容
     * @param success       是否成功
     */
    public void setToolCallResult(String toolCallId, String resultContent, boolean success) {
        ToolCallItem toolCall = getToolCallById(toolCallId);
        if (toolCall != null) {
            int index = toolCalls.indexOf(toolCall);
            if (index >= 0) {
                String result = (resultContent != null && !resultContent.isEmpty())
                        ? resultContent
                        : toolCall.result();
                ToolCallItem updated = new ToolCallItem(
                        toolCall.name(),
                        toolCall.input(),
                        result,
                        toolCall.startTime(),
                        System.currentTimeMillis(),
                        success ? "success" : "error",
                        toolCall.toolCallId()
                );
                toolCalls.set(index, updated);
            }
        }
    }
}