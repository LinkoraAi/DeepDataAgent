package com.linkroa.deepdataagent.runtime.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AgentRunState} 单轮事件流状态聚合单测：工具入参聚合、
 * 工具结果 head+tail 截断、终态提示（事件序号分配已移至会话级
 * {@link AgentSessionContext}，见 AgentSessionContextTest）。
 */
class AgentRunStateTest {

    @Test
    void should_aggregateToolArgsAcrossDeltas_when_takeToolArgs_given_startedToolCall() {
        // given
        AgentRunState state = new AgentRunState();
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
        AgentRunState state = new AgentRunState();
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

    @Test
    void should_accumulateOutputAndFallbackToFinalResult_when_output_given_textAndAgentResult() {
        // given
        AgentRunState state = new AgentRunState();
        state.appendOutput("你好");
        state.appendOutput("，");
        state.setFinalResultText("不该被使用");

        // when
        String output = state.output();

        // then（增量累积优先）
        assertEquals("你好，", output);
    }

    @Test
    void should_fallbackToAgentResult_when_output_given_noTextDeltas() {
        // given
        AgentRunState state = new AgentRunState();
        state.setFinalResultText("最终答案");

        // when
        String output = state.output();

        // then
        assertEquals("最终答案", output);
    }

    @Test
    void should_truncateToolResultHeadTail_when_endToolResult_given_oversizedOutput() throws Exception {
        // given：单条工具结果远超 16KB 窗口
        AgentRunState state = new AgentRunState();
        String tailChunk = "AAA";
        state.appendToolResult("H".repeat(16 * 1024));
        // 中间 delta：head 满后实时窗口应返回空串（丢弃，不落库不发布）
        String dropped = state.appendToolResult("M".repeat(16 * 1024));
        state.appendToolResult(tailChunk);

        // when
        String tail = state.endToolResult();

        // then：head 窗口返回完整增量、中间 delta 丢弃、tail 环形保留最近内容并带截断通知
        assertTrue(dropped.isEmpty());
        assertTrue(state.toolResultTruncated());
        assertTrue(state.toolResultHeadText().length() <= 16 * 1024);
        assertTrue(tail != null && tail.contains("AAA"));
        assertTrue(tail.contains("截断"));
    }

    @Test
    void should_returnNoTruncation_when_endToolResult_given_smallOutput() {
        // given
        AgentRunState state = new AgentRunState();
        String head = state.appendToolResult("小而美");

        // when
        String tail = state.endToolResult();

        // then（16KB 内不截断：tail 为 null、head 即时返回）
        assertEquals("小而美", head);
        assertNull(tail);
        assertFalse(state.toolResultTruncated());
    }

    @Test
    void should_markExceedMaxIters_when_markExceedMaxIters_given_default() {
        // given
        AgentRunState state = new AgentRunState();

        // when & then（迭代上限标记仅作 stop_reason 派生输入，不产出终态）
        assertFalse(state.exceededMaxIters());
        state.markExceedMaxIters();
        assertTrue(state.exceededMaxIters());
    }
}