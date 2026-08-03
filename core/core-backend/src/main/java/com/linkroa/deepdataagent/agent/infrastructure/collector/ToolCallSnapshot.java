package com.linkroa.deepdataagent.agent.infrastructure.collector;

/**
 * 工具调用快照
 * <p>字段名与前端 {@code ToolCallTimelineItem} 接口对齐。</p>
 */
public record ToolCallSnapshot(
        String toolName,
        String input,
        String result,
        long startTime,
        long endTime,
        String status,
        String toolCallId
) {}