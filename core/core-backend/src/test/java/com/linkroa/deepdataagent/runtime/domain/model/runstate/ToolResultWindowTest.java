package com.linkroa.deepdataagent.runtime.domain.model.runstate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ToolResultWindow} 单测（原 {@code AgentRunStateTest} 的 head+tail 截断、缓冲隔离断言原样平移，
 * 补充变更 decompose-turn-pipeline task 2.3 要求的未满 / 恰好 / 溢出 / 多字节边界用例）。
 */
class ToolResultWindowTest {

    private static final int WINDOW = 16 * 1024;

    @Test
    void should_truncateToolResultHeadTail_when_endToolResult_given_oversizedOutput() {
        // given：单条工具结果远超 16KB 窗口
        ToolResultWindow state = new ToolResultWindow();
        String toolCallId = "tool-call-1";
        String tailChunk = "AAA";
        state.appendToolResult(toolCallId, "H".repeat(WINDOW));
        // 中间 delta：head 满后实时窗口应返回空串（丢弃，不落库不发布）
        String dropped = state.appendToolResult(toolCallId, "M".repeat(WINDOW));
        state.appendToolResult(toolCallId, tailChunk);

        // when
        String tail = state.endToolResult(toolCallId);

        // then：head 窗口返回完整增量、中间 delta 丢弃、tail 环形保留最近内容并带截断通知
        assertTrue(dropped.isEmpty());
        assertTrue(state.toolResultTruncated(toolCallId));
        assertTrue(state.toolResultHeadText(toolCallId).length() <= WINDOW);
        assertTrue(tail != null && tail.contains("AAA"));
        assertTrue(tail.contains("截断"));
    }

    @Test
    void should_keepToolResultBuffersIsolated_when_appendToolResult_given_interleavedToolCalls() {
        // given：两个工具结果交错下发（同一个 head/tail 单例会串味，隔离后各自独立）
        ToolResultWindow state = new ToolResultWindow();
        state.appendToolResult("tool-a", "AAAA");
        state.appendToolResult("tool-b", "BBBB");

        // when
        String tailB = state.endToolResult("tool-b");
        String headA = state.toolResultHeadText("tool-a");

        // then（互不影响：B 结束不影响 A 的累积；A 未截断、B 未截断）
        assertEquals("AAAA", headA);
        assertNull(tailB);
        assertFalse(state.toolResultTruncated("tool-a"));
    }

    @Test
    void should_returnNoTruncation_when_endToolResult_given_smallOutput() {
        // given
        ToolResultWindow state = new ToolResultWindow();
        String toolCallId = "tool-call-1";
        String head = state.appendToolResult(toolCallId, "小而美");

        // when
        String tail = state.endToolResult(toolCallId);

        // then（16KB 内不截断：tail 为 null、head 即时返回）
        assertEquals("小而美", head);
        assertNull(tail);
        assertFalse(state.toolResultTruncated(toolCallId));
    }

    @Test
    void should_notTruncate_when_endToolResult_given_outputExactlyAtWindow() {
        // given：结果长度恰好等于窗口（total == 16KB）
        ToolResultWindow state = new ToolResultWindow();
        String toolCallId = "tool-exact";
        String head = state.appendToolResult(toolCallId, "X".repeat(WINDOW));

        // when
        String tail = state.endToolResult(toolCallId);

        // then（恰好满窗：全量入 head、不截断、无补发 tail）
        assertEquals(WINDOW, head.length());
        assertNull(tail);
        assertFalse(state.toolResultTruncated(toolCallId));
    }

    @Test
    void should_splitHeadAcrossWindowBoundary_when_appendToolResult_given_deltaStraddlingWindow() {
        // given：单段 delta 跨越 head 窗口边界（head 仅剩少量空间）
        ToolResultWindow state = new ToolResultWindow();
        String toolCallId = "tool-straddle";
        state.appendToolResult(toolCallId, "H".repeat(WINDOW - 3));

        // when：再来一段 10 字符，前 3 入 head、后 7 进 tail
        String returned = state.appendToolResult(toolCallId, "0123456789");

        // then：返回值仅为 head 内填充部分（3 字符），溢出部分丢弃不进实时窗口
        assertEquals("012", returned);
        assertEquals(WINDOW, state.toolResultHeadText(toolCallId).length());
        assertTrue(state.toolResultTruncated(toolCallId));
    }

    @Test
    void should_treatMultiByteCharsByUtf16Unit_when_appendToolResult_given_cjkOutput() {
        // given：CJK 字符（每字符 1 UTF-16 code unit）填满 head 窗口
        ToolResultWindow state = new ToolResultWindow();
        String toolCallId = "tool-cjk";
        String cjk = "汉".repeat(WINDOW);
        String head = state.appendToolResult(toolCallId, cjk);

        // when
        String tail = state.endToolResult(toolCallId);

        // then：按 char（UTF-16 单元）计长，全量入 head、恰好不截断（语义与原实现逐字一致）
        assertEquals(WINDOW, head.length());
        assertNull(tail);
        assertFalse(state.toolResultTruncated(toolCallId));
    }

    @Test
    void should_dropEmptyBuffer_when_endToolResult_given_unknownToolCall() {
        // given：从未追加工具结果
        ToolResultWindow state = new ToolResultWindow();

        // when & then
        assertNull(state.endToolResult("absent"));
        assertEquals("", state.toolResultHeadText("absent"));
        assertFalse(state.toolResultTruncated("absent"));
    }
}
