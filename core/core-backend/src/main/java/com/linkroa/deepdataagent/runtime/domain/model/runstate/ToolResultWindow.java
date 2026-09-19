package com.linkroa.deepdataagent.runtime.domain.model.runstate;

import java.util.HashMap;
import java.util.Map;

/**
 * 工具结果 head+tail 截断窗口组件（单轮运行态五件套之一，由 {@link TurnRunState} 门面组合）。
 * <p>吸收原 {@code AgentRunState}「工具结果 head+tail 截断」整节：head 16KB 实时窗口 +
 * tail 16KB 环形缓冲，中间 delta 直接丢弃，避免超大工具输出拖慢轮次。截断算法为纯算法，
 * 单侧保留窗口常量化为 {@link #TOOL_RESULT_KEEP_CHARS}。</p>
 * <p>并发契约随字段搬迁：{@code toolResultBuffers} 为按 {@code toolCallId} 隔离的普通
 * {@link HashMap}，<b>仅由 Reactor 流线程独占访问</b>（交错下发按 tool_call_id 隔离互不串味），
 * 非线程安全、禁止并发调用。</p>
 * <p>本组件不带日志（领域运行态组件无 slf4j）。</p>
 */
public final class ToolResultWindow {

    /** 工具结果 head+tail 截断窗口（单侧 16KB，避免超大工具输出拖慢轮次处理） */
    private static final int TOOL_RESULT_KEEP_CHARS = 16 * 1024;

    /** 工具结果缓冲（tool_call_id → head/tail/total/active，交错下发互不串味；仅 Reactor 流线程独占访问） */
    private final Map<String, ToolResultBuffer> toolResultBuffers = new HashMap<>();

    /** 单个工具结果缓冲：head + tail 环形缓冲 + 总量 + 接收中标记。 */
    private static final class ToolResultBuffer {

        private final StringBuilder head = new StringBuilder();
        private final StringBuilder tail = new StringBuilder();
        private long total = 0;
        private boolean active = false;
    }

    /**
     * 追加工具结果 delta（返回 head 窗口内应实时处理的部分）。
     * <p>head 未满时写入 head 并返回对应文本；head 已满后仅写 tail 环形缓冲并返回空串
     * （中间 delta 直接丢弃，不落库、不发布）。状态按 {@code toolCallId} 隔离，
     * 首个 delta 自动进入「接收中」状态。</p>
     *
     * @param toolCallId 工具调用 ID（交错并发下发时按此隔离 head/tail 窗口）
     * @param delta      工具结果增量文本
     * @return head 窗口内文本；无需处理时返回空串
     */
    public String appendToolResult(String toolCallId, String delta) {
        if (toolCallId == null || delta == null || delta.isEmpty()) {
            return "";
        }
        ToolResultBuffer buffer = toolResultBuffers.computeIfAbsent(toolCallId, k -> new ToolResultBuffer());
        if (!buffer.active) {
            buffer.active = true;
            buffer.head.setLength(0);
            buffer.tail.setLength(0);
            buffer.total = 0;
        }
        int len = delta.length();
        buffer.total += len;
        int headLen = buffer.head.length();
        if (headLen >= TOOL_RESULT_KEEP_CHARS) {
            appendTail(buffer, delta);
            return "";
        }
        int remaining = TOOL_RESULT_KEEP_CHARS - headLen;
        int take = Math.min(remaining, len);
        buffer.head.append(delta, 0, take);
        if (take < len) {
            appendTail(buffer, delta.substring(take));
        }
        return delta.substring(0, take);
    }

    /**
     * 结束工具结果接收（TOOL_RESULT_END 时调用）。
     * <p>单条结果未超出截断窗口时返回 {@code null}；已截断时返回补发的 tail
     * （省略标记 + tail + 截断通知），由调用方决定是否补发事件与 span 输出。</p>
     *
     * @param toolCallId 工具调用 ID
     * @return 截断补发文本；未截断时返回 {@code null}
     */
    public String endToolResult(String toolCallId) {
        ToolResultBuffer buffer = toolResultBuffers.get(toolCallId);
        if (buffer == null) {
            return null;
        }
        buffer.active = false;
        if (buffer.total <= TOOL_RESULT_KEEP_CHARS) {
            return null;
        }
        long omitted = buffer.total - buffer.head.length() - buffer.tail.length();
        return "\n...[中间省略 " + omitted + " 字符]...\n" + buffer.tail
                + "\n...[输出已截断，共 " + buffer.total + " 字符，仅保留首尾各 "
                + TOOL_RESULT_KEEP_CHARS + " 字符]...\n";
    }

    /**
     * 指定工具调用累积的 head 文本（工具失败时起始即错误文本，供错误分类）。
     */
    public String toolResultHeadText(String toolCallId) {
        ToolResultBuffer buffer = toolResultBuffers.get(toolCallId);
        return buffer == null ? "" : buffer.head.toString();
    }

    /**
     * 指定工具调用是否发生截断（total &gt; 16KB）。
     */
    public boolean toolResultTruncated(String toolCallId) {
        ToolResultBuffer buffer = toolResultBuffers.get(toolCallId);
        return buffer != null && buffer.total > TOOL_RESULT_KEEP_CHARS;
    }

    /** 向 tail 环形缓冲追加文本，超出窗口时丢弃最旧部分。 */
    private static void appendTail(ToolResultBuffer buffer, String s) {
        if (s.isEmpty()) {
            return;
        }
        buffer.tail.append(s);
        if (buffer.tail.length() > TOOL_RESULT_KEEP_CHARS) {
            buffer.tail.delete(0, buffer.tail.length() - TOOL_RESULT_KEEP_CHARS);
        }
    }
}
