package com.linkroa.deepdataagent.runtime.domain.model;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 单轮 Agent 事件流处理状态（本轮状态聚合，应用层在 {@code doOnNext} 中编排持久化所需的状态累积）。
 * <p>本类只承载<b>单轮</b>内可变状态，会话级跨轮状态（身份 / 事件序列号计数器 / 在跑执行句柄）
 * 收敛于 {@link AgentSessionContext}（进程内「逻辑线程组」），每轮经
 * {@code AgentSessionContext.beginRound} 新建本状态：</p>
 * <ul>
 *   <li><b>输出累积</b>：TEXT 增量与 AGENT_RESULT 最终文本拼接为轮次最终 output；</li>
 *   <li><b>工具入参聚合</b>：跨 TOOL_CALL_START/DELTA/END 聚合 arguments JSON（span 脱敏入口）；</li>
 *   <li><b>工具结果 head+tail 截断</b>：保留 head 16KB 实时窗口 + tail 16KB 环形缓冲，
 *       中间 delta 直接丢弃，避免超大工具输出拖慢轮次；</li>
 *   <li><b>终态提示</b>：EXCEED_MAX_ITERS 标记与 stop_reason 推导、防重复终态化的 finalized 原子位。</li>
 * </ul>
 * <p>流事件经 Reactor 串行处理保证聚合字段单线程安全；跨线程可见位（finalized / stopReason /
 * exceedMaxIters）使用 {@link AtomicReference}/{@link volatile} 承载以兼容中断路径并发读。</p>
 */
public final class AgentRunState {

    /** 工具结果 head+tail 截断窗口（单侧 16KB，避免超大工具输出拖慢轮次处理） */
    private static final int TOOL_RESULT_KEEP_CHARS = 16 * 1024;

    /** 累积文本输出（轮次最终 output） */
    private final StringBuilder output = new StringBuilder();
    /** AGENT_RESULT 携带的最终结果文本（兜底回填 output） */
    private final AtomicReference<String> finalResultText = new AtomicReference<>("");
    /** 工具调用与工具名的映射（tool_call_id → 工具名，span 落库用） */
    private final Map<String, String> toolNames = new HashMap<>();
    /** 工具调用入参 delta 聚合（tool_call_id → JSON 片段流） */
    private final Map<String, StringBuilder> toolCallArgs = new HashMap<>();
    /** 工具调用入参快照（tool_call_id → 原始 JSON，takeToolArgs 时留存供 TOOL_RESULT_END 的 span 使用） */
    private final Map<String, String> toolInputs = new HashMap<>();
    /** 工具调用开始时间（tool_call_id → span 起点） */
    private final Map<String, OffsetDateTime> toolSpanStarts = new HashMap<>();
    /** 模型调用开始时间（llm.call span 起点，单模型并发场景取最近一次） */
    private OffsetDateTime modelSpanStart;
    /** 防重复终态化（AGENT_END 提前终态与 onComplete 兜底之间的唯一出口守卫） */
    private final java.util.concurrent.atomic.AtomicBoolean finalized = new java.util.concurrent.atomic.AtomicBoolean(false);
    /** 流内终态提示：是否已见 EXCEED_MAX_ITERS */
    private final java.util.concurrent.atomic.AtomicBoolean exceedMaxIters = new java.util.concurrent.atomic.AtomicBoolean(false);
    /** 工具结果 head 缓冲（保留前 16KB） */
    private final StringBuilder toolResultHead = new StringBuilder();
    /** 工具结果 tail 环形缓冲（保留最近 16KB） */
    private final StringBuilder toolResultTail = new StringBuilder();
    /** 当前工具结果累积总字符数（用于截断通知） */
    private long toolResultTotal = 0;
    /** 当前是否处于工具结果接收中（首次 delta 自动进入） */
    private boolean toolResultActive = false;

    public AgentRunState() {
    }

    // ==================== 文本输出累积 ====================

    /**
     * 追加文本输出（TEXT 增量）。
     * <p>仅由 Reactor 串行事件流在单线程内独占写；本字段为普通 {@code StringBuilder}，
     * 非线程安全，禁止并发调用。</p>
     */
    public void appendOutput(String delta) {
        if (delta != null && !delta.isEmpty()) {
            output.append(delta);
        }
    }

    /**
     * 记录 AGENT_RESULT 最终文本（与增量累积互相兜底）。
     */
    public void setFinalResultText(String text) {
        if (text != null && !text.isEmpty()) {
            finalResultText.set(text);
        }
    }

    /**
     * 轮次最终输出（增量累积优先，缺失时回退 AGENT_RESULT 文本）。
     */
    public String output() {
        if (output.length() > 0) {
            return output.toString();
        }
        return finalResultText.get();
    }

    // ==================== 工具调用聚合 ====================

    /**
     * 记录工具调用开始（工具名 + span 起点）。
     */
    public void startToolCall(String toolCallId, String toolName) {
        if (toolCallId != null) {
            toolSpanStarts.put(toolCallId, now());
            if (toolName != null) {
                toolNames.put(toolCallId, toolName);
            }
        }
    }

    /**
     * 追加工具调用入参 delta（JSON 片段）。
     */
    public void appendToolArgs(String toolCallId, String delta) {
        if (toolCallId == null || delta == null) {
            return;
        }
        toolCallArgs.computeIfAbsent(toolCallId, k -> new StringBuilder()).append(delta);
    }

    /**
     * 取出聚合完成的入参 JSON 并移除（工具调用 END 时调用一次）；
     * 同时留存原始入参快照供 TOOL_RESULT_END 的 tool.call span 使用（脱敏由应用层负责）。
     *
     * @param toolCallId 工具调用 ID
     * @return 聚合后的入参 JSON；未聚合任何 delta 时返回 {@code null}
     */
    public String takeToolArgs(String toolCallId) {
        StringBuilder builder = toolCallArgs.remove(toolCallId);
        if (builder == null) {
            return null;
        }
        String args = builder.toString();
        toolInputs.put(toolCallId, args);
        return args;
    }

    /**
     * 工具名（span 落库用；tool_call_id 缺失时以事件携带的工具名兜底——由调用方判断）。
     */
    public String toolName(String toolCallId, String fallback) {
        String name = toolNames.get(toolCallId);
        return name != null ? name : fallback;
    }

    /**
     * 取出工具入参快照并移除（TOOL_RESULT_END 时调用一次，span 入参落库前由应用层脱敏）。
     */
    public String takeToolInput(String toolCallId) {
        return toolInputs.remove(toolCallId);
    }

    /**
     * 工具调用开始时间（span 起点）并移除。
     */
    public OffsetDateTime takeToolSpanStart(String toolCallId) {
        return toolSpanStarts.remove(toolCallId);
    }

    // ==================== 模型调用 span ====================

    /**
     * 记录模型调用开始（llm.call span 起点）。
     */
    public void markModelCallStart() {
        modelSpanStart = now();
    }

    /**
     * 取出模型调用开始时间并重置（MODEL_CALL_END 时调用一次）。
     */
    public OffsetDateTime takeModelSpanStart() {
        OffsetDateTime start = modelSpanStart;
        modelSpanStart = null;
        return start;
    }

    // ==================== 工具结果 head+tail 截断 ====================

    /**
     * 追加工具结果 delta（返回 head 窗口内应实时处理的部分）。
     * <p>head 未满时写入 head 并返回对应文本；head 已满后仅写 tail 环形缓冲并返回空串
     * （中间 delta 直接丢弃，不落库、不发布）。首次调用时自动进入「接收中」状态。</p>
     *
     * @param delta 工具结果增量文本
     * @return head 窗口内文本；无需处理时返回空串
     */
    public String appendToolResult(String delta) {
        if (delta == null || delta.isEmpty()) {
            return "";
        }
        if (!toolResultActive) {
            toolResultActive = true;
            toolResultHead.setLength(0);
            toolResultTail.setLength(0);
            toolResultTotal = 0;
        }
        int len = delta.length();
        toolResultTotal += len;
        int headLen = toolResultHead.length();
        if (headLen >= TOOL_RESULT_KEEP_CHARS) {
            appendTail(delta);
            return "";
        }
        int remaining = TOOL_RESULT_KEEP_CHARS - headLen;
        int take = Math.min(remaining, len);
        toolResultHead.append(delta, 0, take);
        if (take < len) {
            appendTail(delta.substring(take));
        }
        return delta.substring(0, take);
    }

    /**
     * 结束工具结果接收（TOOL_RESULT_END 时调用）。
     * <p>单条结果未超出截断窗口时返回 {@code null}；已截断时返回补发的 tail
     * （省略标记 + tail + 截断通知），由调用方决定是否补发事件与 span 输出。</p>
     *
     * @return 截断补发文本；未截断时返回 {@code null}
     */
    public String endToolResult() {
        toolResultActive = false;
        if (toolResultTotal <= TOOL_RESULT_KEEP_CHARS) {
            return null;
        }
        long omitted = toolResultTotal - toolResultHead.length() - toolResultTail.length();
        return "\n...[中间省略 " + omitted + " 字符]...\n" + toolResultTail
                + "\n...[输出已截断，共 " + toolResultTotal + " 字符，仅保留首尾各 "
                + TOOL_RESULT_KEEP_CHARS + " 字符]...\n";
    }

    /**
     * 当前工具结果累积 head 文本（工具失败时起始即错误文本，供错误分类；免改数据结构）。
     */
    public String toolResultHeadText() {
        return toolResultHead.toString();
    }

    /**
     * 当前工具结果是否发生截断（total &gt; 16KB）。
     */
    public boolean toolResultTruncated() {
        return toolResultTotal > TOOL_RESULT_KEEP_CHARS;
    }

    /** 向 tail 环形缓冲追加文本，超出窗口时丢弃最旧部分。 */
    private void appendTail(String s) {
        if (s.isEmpty()) {
            return;
        }
        toolResultTail.append(s);
        if (toolResultTail.length() > TOOL_RESULT_KEEP_CHARS) {
            toolResultTail.delete(0, toolResultTail.length() - TOOL_RESULT_KEEP_CHARS);
        }
    }

    // ==================== 终态提示 ====================

    /**
     * 标记已见 EXCEED_MAX_ITERS（stop_reason 推导）。
     */
    public void markExceedMaxIters() {
        exceedMaxIters.set(true);
    }

    /**
     * 是否已见 EXCEED_MAX_ITERS。
     */
    public boolean exceededMaxIters() {
        return exceedMaxIters.get();
    }

    /**
     * 轮次最终 stop_reason（stop / max_iterations）。
     */
    public String stopReason() {
        return exceedMaxIters.get() ? "max_iterations" : "stop";
    }

    /**
     * 尝试标记本轮已终态化；仅首个调用方返回 {@code true}（终态唯一出口守卫）。
     */
    public boolean tryFinalized() {
        return finalized.compareAndSet(false, true);
    }

    /**
     * 是否已终态化。
     */
    public boolean isFinalized() {
        return finalized.get();
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
    }
}