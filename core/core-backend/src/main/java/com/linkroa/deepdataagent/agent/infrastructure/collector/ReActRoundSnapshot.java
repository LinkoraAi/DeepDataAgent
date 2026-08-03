package com.linkroa.deepdataagent.agent.infrastructure.collector;

import java.util.List;

/**
 * ReAct 轮次快照
 * <p>字段名与前端 {@code ReActRound} 接口完全对齐，
 * 确保 JSON 序列化后前端可直接解析还原时间线。</p>
 */
public record ReActRoundSnapshot(
        String id,
        long startTime,
        long endTime,
        ThinkingSnapshot thinking,
        List<ToolCallSnapshot> toolCalls,
        boolean isActive,
        boolean isCollapsed
) {}