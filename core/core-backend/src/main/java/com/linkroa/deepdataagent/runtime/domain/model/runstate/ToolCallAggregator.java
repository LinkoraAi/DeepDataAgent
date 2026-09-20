package com.linkroa.deepdataagent.runtime.domain.model.runstate;

import com.linkroa.deepdataagent.runtime.domain.model.ChatEvent;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 工具调用聚合组件（单轮运行态五件套之一，由 {@link TurnRunState} 门面组合）。
 * <p>吸收原 {@code AgentRunState}「工具调用聚合 + 工具调用事件 ID」两节：跨
 * TOOL_CALL_START/DELTA/END 聚合 arguments JSON（span 脱敏入口），并按 tool_call_id
 * 惰性分配确定性 {@code evt_} 事件 ID（agent.tool_use / agent.tool_result 共用）。</p>
 * <p>并发契约随字段搬迁：入参聚合 builder（{@code toolCallArgs} 的 StringBuilder）与各映射表
 * 均仅由 Reactor 串行事件流在单线程内独占读写，非线程安全、禁止并发调用；
 * span 起点取 {@code Asia/Shanghai} 时刻。</p>
 * <p>本组件不带日志（领域运行态组件无 slf4j）。</p>
 */
public final class ToolCallAggregator {

    /** 工具调用与工具名的映射（tool_call_id → 工具名，span 落库用） */
    private final Map<String, String> toolNames = new HashMap<>();
    /** 工具调用入参 delta 聚合（tool_call_id → JSON 片段流；builder 单线程独占追加） */
    private final Map<String, StringBuilder> toolCallArgs = new HashMap<>();
    /** 工具调用入参快照（tool_call_id → 原始 JSON，takeToolArgs 时留存供 TOOL_RESULT_END 的 span 使用） */
    private final Map<String, String> toolInputs = new HashMap<>();
    /** 工具调用开始时间（tool_call_id → span 起点） */
    private final Map<String, OffsetDateTime> toolSpanStarts = new HashMap<>();
    /** 工具调用事件 ID（tool_call_id → evt_，agent.tool_use / agent.tool_result 共用） */
    private final Map<String, String> toolEventIds = new HashMap<>();
    /**
     * 执行中的工具调用（已落库 {@code agent.tool_use} / {@code agent.mcp_tool_use} 但尚未落库配对结果；
     * tool_call_id → 工具名）。TOOL_CALL_END 登记、TOOL_RESULT_END 摘除；中断收流时据此补合成
     * 错误结果，保证事件表无悬空 tool_use（见 runtime/events 规格「中断不留下悬空 tool_use」）。
     */
    private final Map<String, String> pendingToolUses = new HashMap<>();

    /** 执行中的工具调用快照（tool_call_id + 工具名，顺序为登记顺序）。 */
    public record PendingToolUse(String toolCallId, String toolName) {
    }

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

    /**
     * 惰性分配工具调用事件 ID（首次调用生成，流式帧与 agent.tool_use / agent.tool_result 共用）。
     */
    public String ensureToolEventId(String toolCallId) {
        return toolEventIds.computeIfAbsent(toolCallId, k -> newEventId());
    }

    /**
     * 取出并移除工具调用事件 ID（agent.tool_use 落库用）。
     */
    public String takeToolEventId(String toolCallId) {
        return toolEventIds.remove(toolCallId);
    }

    /**
     * 登记执行中的工具调用（TOOL_CALL_END 落库 {@code tool_use} 后调用一次）。
     */
    public void registerPendingToolUse(String toolCallId, String toolName) {
        if (toolCallId != null) {
            pendingToolUses.put(toolCallId, toolName);
        }
    }

    /**
     * 摘除执行中的工具调用（TOOL_RESULT_END 落库配对结果后调用一次）。
     */
    public void removePendingToolUse(String toolCallId) {
        if (toolCallId != null) {
            pendingToolUses.remove(toolCallId);
        }
    }

    /**
     * 执行中的工具调用快照（登记顺序）：中断收流时据此补合成错误结果。
     */
    public List<PendingToolUse> pendingToolUses() {
        List<PendingToolUse> snapshot = new ArrayList<>(pendingToolUses.size());
        pendingToolUses.forEach((toolCallId, toolName) -> snapshot.add(new PendingToolUse(toolCallId, toolName)));
        return List.copyOf(snapshot);
    }

    /** 生成流式事件 ID（evt_ 前缀，与最终事件共用）。 */
    private static String newEventId() {
        return ChatEvent.EVENT_ID_PREFIX + UUID.randomUUID().toString().replace("-", "");
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
    }
}
