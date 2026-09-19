package com.linkroa.deepdataagent.runtime.domain.model.runstate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link ToolCallAggregator} 单测（原 {@code AgentRunStateTest} 的工具入参聚合、
 * span 入参快照断言原样平移）。
 */
class ToolCallAggregatorTest {

    @Test
    void should_aggregateToolArgsAcrossDeltas_when_takeToolArgs_given_startedToolCall() {
        // given
        ToolCallAggregator state = new ToolCallAggregator();
        state.startToolCall("tc-1", "search");
        state.appendToolArgs("tc-1", "{\"q\"");
        state.appendToolArgs("tc-1", ":\"x\"}");

        // when
        String args = state.takeToolArgs("tc-1");

        // then
        assertEquals("{\"q\":\"x\"}", args);
        assertEquals("search", state.toolName("tc-1", "fallback"));
        // 消费后再次取为空；span 入参快照可独立留存
        assertNull(state.takeToolArgs("tc-1"));
    }

    @Test
    void should_keepInputSnapshotForToolSpan_when_takeToolArgs_given_toolCallEnd() {
        // given
        ToolCallAggregator state = new ToolCallAggregator();
        state.appendToolArgs("tc-1", "{\"api_key\":\"***\"}");

        // when
        String args = state.takeToolArgs("tc-1");
        String input = state.takeToolInput("tc-1");

        // then
        assertEquals("{\"api_key\":\"***\"}", args);
        // takeToolArgs 已留存原始快照供 tool.call span 使用
        assertEquals("{\"api_key\":\"***\"}", input);
        assertNull(state.takeToolInput("tc-1"));
    }
}
