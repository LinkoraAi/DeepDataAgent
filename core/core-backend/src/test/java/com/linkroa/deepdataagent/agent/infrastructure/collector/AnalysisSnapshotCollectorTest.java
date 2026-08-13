package com.linkroa.deepdataagent.agent.infrastructure.collector;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AnalysisSnapshotCollector 单元测试
 * <p>覆盖收集器保留的核心行为：思考文本累积/flush、工具调用记录、入参与结果累积。</p>
 */
class AnalysisSnapshotCollectorTest {

    // ==================== flushThinkingStep ====================

    @Test
    void should_flushThinkingText_when_flushThinkingStep_given_accumulatedDeltas() {
        // given
        AnalysisSnapshotCollector collector = new AnalysisSnapshotCollector();

        // when
        collector.addThinkingStep("步骤1");
        collector.addThinkingStep("步骤2");
        String result = collector.flushThinkingStep();

        // then
        assertEquals("步骤1步骤2", result);
    }

    @Test
    void should_returnNull_when_flushThinkingStep_given_noAccumulatedContent() {
        // given
        AnalysisSnapshotCollector collector = new AnalysisSnapshotCollector();

        // when
        String result = collector.flushThinkingStep();

        // then
        assertNull(result);
    }

    @Test
    void should_returnNull_when_flushThinkingStep_given_nullDelta() {
        // given
        AnalysisSnapshotCollector collector = new AnalysisSnapshotCollector();

        // when
        collector.addThinkingStep(null);
        String result = collector.flushThinkingStep();

        // then
        assertNull(result);
    }

    @Test
    void should_clearBuffer_when_flushThinkingStep_given_secondCall() {
        // given
        AnalysisSnapshotCollector collector = new AnalysisSnapshotCollector();
        collector.addThinkingStep("第一次");
        collector.flushThinkingStep();

        // when
        collector.addThinkingStep("第二次");
        String result = collector.flushThinkingStep();

        // then
        assertEquals("第二次", result);
    }

    // ==================== getThinkingBuffer ====================

    @Test
    void should_returnAccumulatedText_when_getThinkingBuffer_given_accumulatedDeltas() {
        // given
        AnalysisSnapshotCollector collector = new AnalysisSnapshotCollector();

        // when
        collector.addThinkingStep("步骤1");
        collector.addThinkingStep("步骤2");
        String result = collector.getThinkingBuffer();

        // then
        assertEquals("步骤1步骤2", result);
    }

    @Test
    void should_keepBuffer_when_getThinkingBuffer_given_accumulatedDeltas() {
        // given
        AnalysisSnapshotCollector collector = new AnalysisSnapshotCollector();

        // when
        collector.addThinkingStep("步骤1");
        collector.getThinkingBuffer();
        collector.addThinkingStep("步骤2");
        String result = collector.getThinkingBuffer();

        // then
        // getThinkingBuffer 不清空缓冲，后续 delta 继续累积
        assertEquals("步骤1步骤2", result);
    }

    @Test
    void should_returnNull_when_getThinkingBuffer_given_noAccumulatedContent() {
        // given
        AnalysisSnapshotCollector collector = new AnalysisSnapshotCollector();

        // when
        String result = collector.getThinkingBuffer();

        // then
        assertNull(result);
    }

    // ==================== addAssistantStep / getAssistantBuffer / flushAssistantStep ====================

    @Test
    void should_returnAccumulatedText_when_getAssistantBuffer_given_accumulatedDeltas() {
        // given
        AnalysisSnapshotCollector collector = new AnalysisSnapshotCollector();

        // when
        collector.addAssistantStep("第一段");
        collector.addAssistantStep("第二段");
        String result = collector.getAssistantBuffer();

        // then
        assertEquals("第一段第二段", result);
    }

    @Test
    void should_returnNull_when_getAssistantBuffer_given_noAccumulatedContent() {
        // given
        AnalysisSnapshotCollector collector = new AnalysisSnapshotCollector();

        // when
        String result = collector.getAssistantBuffer();

        // then
        assertNull(result);
    }

    @Test
    void should_flushAssistantText_when_flushAssistantStep_given_accumulatedDeltas() {
        // given
        AnalysisSnapshotCollector collector = new AnalysisSnapshotCollector();

        // when
        collector.addAssistantStep("第一段");
        collector.addAssistantStep("第二段");
        String result = collector.flushAssistantStep();

        // then
        assertEquals("第一段第二段", result);
    }

    @Test
    void should_returnNullAndClear_when_flushAssistantStep_given_secondCall() {
        // given
        AnalysisSnapshotCollector collector = new AnalysisSnapshotCollector();
        collector.addAssistantStep("第一段");
        collector.flushAssistantStep();

        // when
        collector.addAssistantStep("第二段");
        String result = collector.flushAssistantStep();

        // then
        assertEquals("第二段", result);
    }

    @Test
    void should_returnNull_when_flushAssistantStep_given_noAccumulatedContent() {
        // given
        AnalysisSnapshotCollector collector = new AnalysisSnapshotCollector();

        // when
        String result = collector.flushAssistantStep();

        // then
        assertNull(result);
    }

    // ==================== addToolCall / getToolCallById ====================

    @Test
    void should_returnToolCall_when_getToolCallById_given_existingCall() {
        // given
        AnalysisSnapshotCollector collector = new AnalysisSnapshotCollector();
        collector.addToolCall(new ToolCallItem("sql_executor", "{}", 1000L, "call-1"));

        // when
        ToolCallItem result = collector.getToolCallById("call-1");

        // then
        assertEquals("sql_executor", result.name());
        assertEquals("call-1", result.toolCallId());
    }

    @Test
    void should_returnNull_when_getToolCallById_given_unknownId() {
        // given
        AnalysisSnapshotCollector collector = new AnalysisSnapshotCollector();
        collector.addToolCall(new ToolCallItem("sql_executor", "{}", 1000L, "call-1"));

        // when
        ToolCallItem result = collector.getToolCallById("call-999");

        // then
        assertNull(result);
    }

    @Test
    void should_returnNull_when_getToolCallById_given_nullId() {
        // given
        AnalysisSnapshotCollector collector = new AnalysisSnapshotCollector();

        // when
        ToolCallItem result = collector.getToolCallById(null);

        // then
        assertNull(result);
    }

    // ==================== appendToolCallInput ====================

    @Test
    void should_appendInput_when_appendToolCallInput_given_existingCall() {
        // given
        AnalysisSnapshotCollector collector = new AnalysisSnapshotCollector();
        collector.addToolCall(new ToolCallItem("sql_executor", "{\"sql\":\"", 1000L, "call-1"));

        // when
        collector.appendToolCallInput("call-1", "select 1}");
        ToolCallItem result = collector.getToolCallById("call-1");

        // then
        assertEquals("{\"sql\":\"select 1}", result.input());
    }

    @Test
    void should_ignoreInput_when_appendToolCallInput_given_unknownCall() {
        // given
        AnalysisSnapshotCollector collector = new AnalysisSnapshotCollector();

        // when
        collector.appendToolCallInput("call-unknown", "增量");

        // then
        assertNull(collector.getToolCallById("call-unknown"));
    }

    @Test
    void should_ignoreNullDelta_when_appendToolCallInput_given_nullDelta() {
        // given
        AnalysisSnapshotCollector collector = new AnalysisSnapshotCollector();
        collector.addToolCall(new ToolCallItem("sql_executor", "{}", 1000L, "call-1"));

        // when
        collector.appendToolCallInput("call-1", null);
        ToolCallItem result = collector.getToolCallById("call-1");

        // then
        assertEquals("{}", result.input());
    }

    // ==================== appendToolResultDelta / getToolResult ====================

    @Test
    void should_accumulateResult_when_appendToolResultDelta_given_deltas() {
        // given
        AnalysisSnapshotCollector collector = new AnalysisSnapshotCollector();

        // when
        collector.appendToolResultDelta("call-1", "第一段");
        collector.appendToolResultDelta("call-1", "第二段");
        String result = collector.getToolResult("call-1");

        // then
        assertEquals("第一段第二段", result);
    }

    @Test
    void should_returnNull_when_getToolResult_given_unknownId() {
        // given
        AnalysisSnapshotCollector collector = new AnalysisSnapshotCollector();

        // when
        String result = collector.getToolResult("call-unknown");

        // then
        assertNull(result);
    }

    @Test
    void should_returnNull_when_getToolResult_given_nullId() {
        // given
        AnalysisSnapshotCollector collector = new AnalysisSnapshotCollector();

        // when
        String result = collector.getToolResult(null);

        // then
        assertNull(result);
    }

    @Test
    void should_ignoreNullDelta_when_appendToolResultDelta_given_nullDelta() {
        // given
        AnalysisSnapshotCollector collector = new AnalysisSnapshotCollector();

        // when
        collector.appendToolResultDelta("call-1", null);
        String result = collector.getToolResult("call-1");

        // then
        assertNull(result);
    }

    // ==================== setToolCallResult ====================

    @Test
    void should_setSuccessResult_when_setToolCallResult_given_success() {
        // given
        AnalysisSnapshotCollector collector = new AnalysisSnapshotCollector();
        collector.addToolCall(new ToolCallItem("sql_executor", "{}", 1000L, "call-1"));

        // when
        collector.setToolCallResult("call-1", "查询结果", true);
        ToolCallItem result = collector.getToolCallById("call-1");

        // then
        assertEquals("查询结果", result.result());
        assertEquals("success", result.status());
        assertTrue(result.endTime() >= result.startTime());
    }

    @Test
    void should_setErrorResult_when_setToolCallResult_given_failure() {
        // given
        AnalysisSnapshotCollector collector = new AnalysisSnapshotCollector();
        collector.addToolCall(new ToolCallItem("sql_executor", "{}", 1000L, "call-1"));

        // when
        collector.setToolCallResult("call-1", "异常信息", false);
        ToolCallItem result = collector.getToolCallById("call-1");

        // then
        assertEquals("异常信息", result.result());
        assertEquals("error", result.status());
    }

    @Test
    void should_keepOriginalResult_when_setToolCallResult_given_emptyResultContent() {
        // given
        AnalysisSnapshotCollector collector = new AnalysisSnapshotCollector();
        collector.addToolCall(new ToolCallItem("sql_executor", "{}", 1000L, "call-1"));
        collector.setToolCallResult("call-1", "已有结果", true);

        // when
        collector.setToolCallResult("call-1", "", true);
        ToolCallItem result = collector.getToolCallById("call-1");

        // then
        assertEquals("已有结果", result.result());
    }

    @Test
    void should_ignoreResult_when_setToolCallResult_given_unknownCall() {
        // given
        AnalysisSnapshotCollector collector = new AnalysisSnapshotCollector();

        // when
        collector.setToolCallResult("call-unknown", "结果", true);

        // then
        assertNull(collector.getToolCallById("call-unknown"));
    }
}