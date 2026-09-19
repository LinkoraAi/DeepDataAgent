package com.linkroa.deepdataagent.runtime.application.service.execution;

import com.linkroa.deepdataagent.runtime.domain.event.AgentStreamSignal;
import com.linkroa.deepdataagent.runtime.domain.event.AgentStreamSignalType;
import com.linkroa.deepdataagent.runtime.domain.model.AgentAssemblySpec;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSessionContext;
import com.linkroa.deepdataagent.runtime.domain.model.enums.ChatEventType;
import com.linkroa.deepdataagent.runtime.domain.model.runstate.TurnRunState;
import com.linkroa.deepdataagent.runtime.domain.port.ConnectionHandle;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 信号策略族单测（变更 R11 task 4.3）：以 {@link RecordingRoundSink} + 真实 {@link TurnRunState}
 * 断言每个策略「信号 → 累积 / 落库 / 推帧 / 终态 / HITL 挂起」映射，覆盖拆分前 {@code handleSignal}
 * 各 case 的可观测副作用，逐字对照原巨型 switch 语义。
 */
class SignalHandlersTest {

    /** 大刷写间隔：首个 delta 立即刷、其余聚合（TextBlockAccumulator 既定契约）。 */
    private static final long LARGE_FLUSH_INTERVAL_MS = 60_000L;

    private static SignalContext ctx(AgentStreamSignal signal, TurnRunState runState,
                                     RecordingRoundSink sink, long flushIntervalMs) {
        AgentSessionContext sessionContext = mock(AgentSessionContext.class);
        ConnectionHandle connection = mock(ConnectionHandle.class);
        when(sessionContext.sessionId()).thenReturn("sess-1");
        when(sessionContext.currentSequence()).thenReturn(7L);
        when(sessionContext.connection()).thenReturn(connection);
        when(connection.deltaFlushIntervalMs()).thenReturn(flushIntervalMs);
        return new SignalContext(signal, sessionContext, runState, sink);
    }

    @Nested
    class Text {
        private final TextSignalHandler handler = new TextSignalHandler();

        @Test
        void should_pushEventStartOnceAndFlushFirstDelta_when_handle_given_textDelta() {
            // given
            TurnRunState runState = new TurnRunState();
            RecordingRoundSink sink = new RecordingRoundSink();
            SignalContext ctx = ctx(
                    AgentStreamSignal.of(AgentStreamSignalType.TEXT_DELTA, "你好", "blk-1"),
                    runState, sink, LARGE_FLUSH_INTERVAL_MS);

            // when
            handler.handle(ctx);

            // then：首个 delta 推一条 event_start（目标 agent.message）+ 立即刷一次增量，无落库
            assertEquals(1, sink.frames.size());
            assertEquals(ChatEventType.EVENT_START, sink.frames.get(0).type());
            assertEquals(ChatEventType.AGENT_MESSAGE, sink.frameTargets.get(0));
            assertEquals(1, sink.flushTextDeltaCalls);
            assertTrue(sink.persisted.isEmpty());
        }

        @Test
        void should_notPushEventStartAgain_when_handle_given_secondTextDeltaOfSameBlock() {
            // given
            TurnRunState runState = new TurnRunState();
            RecordingRoundSink sink = new RecordingRoundSink();
            handler.handle(ctx(AgentStreamSignal.of(AgentStreamSignalType.TEXT_DELTA, "第一段", "blk-1"),
                    runState, sink, LARGE_FLUSH_INTERVAL_MS));

            // when：同块第二个 delta
            handler.handle(ctx(AgentStreamSignal.of(AgentStreamSignalType.TEXT_DELTA, "第二段", "blk-1"),
                    runState, sink, LARGE_FLUSH_INTERVAL_MS));

            // then：event_start 仍只一条，第二段聚合不重复刷
            assertEquals(1, sink.frames.size());
            assertEquals(1, sink.flushTextDeltaCalls);
        }

        @Test
        void should_persistAgentMessage_when_handle_given_textEnd() {
            // given：先流式再收尾
            TurnRunState runState = new TurnRunState();
            RecordingRoundSink sink = new RecordingRoundSink();
            handler.handle(ctx(AgentStreamSignal.of(AgentStreamSignalType.TEXT_DELTA, "全文", "blk-1"),
                    runState, sink, LARGE_FLUSH_INTERVAL_MS));

            // when
            handler.handle(ctx(AgentStreamSignal.of(AgentStreamSignalType.TEXT_END, null, "blk-1"),
                    runState, sink, LARGE_FLUSH_INTERVAL_MS));

            // then：块结束落一条 agent.message，清空进行中流
            assertEquals(1, sink.persisted.size());
            assertEquals(ChatEventType.AGENT_MESSAGE, sink.persisted.get(0).type());
        }
    }

    @Nested
    class Thinking {
        private final ThinkingSignalHandler handler = new ThinkingSignalHandler();

        @Test
        void should_pushEventStartWithoutDeltaFlush_when_handle_given_thinkingDelta() {
            // given
            TurnRunState runState = new TurnRunState();
            RecordingRoundSink sink = new RecordingRoundSink();
            SignalContext ctx = ctx(
                    AgentStreamSignal.of(AgentStreamSignalType.THINKING_DELTA, "推理", "blk-1"),
                    runState, sink, LARGE_FLUSH_INTERVAL_MS);

            // when
            handler.handle(ctx);

            // then：thinking 暴露 event_start 但无 delta 帧（MUST NOT 暴露推理内容）
            assertEquals(1, sink.frames.size());
            assertEquals(ChatEventType.EVENT_START, sink.frames.get(0).type());
            assertEquals(ChatEventType.AGENT_THINKING, sink.frameTargets.get(0));
            assertEquals(0, sink.flushTextDeltaCalls);
        }

        @Test
        void should_persistAgentThinking_when_handle_given_thinkingEnd() {
            // given
            TurnRunState runState = new TurnRunState();
            RecordingRoundSink sink = new RecordingRoundSink();
            handler.handle(ctx(AgentStreamSignal.of(AgentStreamSignalType.THINKING_DELTA, "想", "blk-1"),
                    runState, sink, LARGE_FLUSH_INTERVAL_MS));

            // when
            handler.handle(ctx(AgentStreamSignal.of(AgentStreamSignalType.THINKING_END, null, "blk-1"),
                    runState, sink, LARGE_FLUSH_INTERVAL_MS));

            // then
            assertEquals(1, sink.persisted.size());
            assertEquals(ChatEventType.AGENT_THINKING, sink.persisted.get(0).type());
        }
    }

    @Nested
    class ToolCall {
        private final ToolCallSignalHandler handler = new ToolCallSignalHandler();

        @Test
        void should_notTouchSink_when_handle_given_startAndDelta() {
            // given
            TurnRunState runState = new TurnRunState();
            RecordingRoundSink sink = new RecordingRoundSink();

            // when：工具调用开始 + 入参增量（工具域不推流式帧）
            handler.handle(ctx(AgentStreamSignal.tool(AgentStreamSignalType.TOOL_CALL_START, "tc-1",
                    "search", null, null), runState, sink, LARGE_FLUSH_INTERVAL_MS));
            handler.handle(ctx(AgentStreamSignal.tool(AgentStreamSignalType.TOOL_CALL_DELTA, "tc-1",
                    "search", "{\"q\":\"x\"}", null), runState, sink, LARGE_FLUSH_INTERVAL_MS));

            // then
            assertTrue(sink.persisted.isEmpty());
            assertTrue(sink.frames.isEmpty());
        }

        @Test
        void should_recordCandidateAndPersistToolUse_when_handle_given_toolCallEnd() {
            // given：先登记现场所需 start/delta
            TurnRunState runState = new TurnRunState();
            RecordingRoundSink sink = new RecordingRoundSink();
            handler.handle(ctx(AgentStreamSignal.tool(AgentStreamSignalType.TOOL_CALL_START, "tc-1",
                    "search", null, null), runState, sink, LARGE_FLUSH_INTERVAL_MS));
            handler.handle(ctx(AgentStreamSignal.tool(AgentStreamSignalType.TOOL_CALL_DELTA, "tc-1",
                    "search", "{\"q\":\"x\"}", null), runState, sink, LARGE_FLUSH_INTERVAL_MS));

            // when
            handler.handle(ctx(AgentStreamSignal.tool(AgentStreamSignalType.TOOL_CALL_END, "tc-1",
                    "search", null, null), runState, sink, LARGE_FLUSH_INTERVAL_MS));

            // then：落 agent.tool_use + 解析入参一次 + 登记一个 HITL 待确认候选
            assertEquals(1, sink.persisted.size());
            assertEquals(ChatEventType.AGENT_TOOL_USE, sink.persisted.get(0).type());
            assertEquals(1, sink.parsePayloadCalls);
            assertEquals(1, runState.confirmCandidates().registeredCount());
        }

        @Test
        void should_persistMcpToolUseWithServerNameAndPolicy_when_handle_given_mcpToolCallEnd() {
            // given：MCP 工具运行时实名（mcp__{server}__{tool}）+ 该工具的 always_ask 策略
            TurnRunState runState = new TurnRunState();
            runState.registerMcpToolPolicies(List.of(
                    new AgentAssemblySpec.ToolExecutionPolicy("get_weather", "always_ask", "weather")));
            RecordingRoundSink sink = new RecordingRoundSink();
            handler.handle(ctx(AgentStreamSignal.tool(AgentStreamSignalType.TOOL_CALL_START, "tc-1",
                    "mcp__weather__get_weather", null, null), runState, sink, LARGE_FLUSH_INTERVAL_MS));
            handler.handle(ctx(AgentStreamSignal.tool(AgentStreamSignalType.TOOL_CALL_DELTA, "tc-1",
                    "mcp__weather__get_weather", "{\"city\":\"SH\"}", null),
                    runState, sink, LARGE_FLUSH_INTERVAL_MS));

            // when
            handler.handle(ctx(AgentStreamSignal.tool(AgentStreamSignalType.TOOL_CALL_END, "tc-1",
                    "mcp__weather__get_weather", null, null), runState, sink, LARGE_FLUSH_INTERVAL_MS));

            // then：类型为 agent.mcp_tool_use，载荷含 mcp_server_name 与 evaluated_permission
            assertEquals(1, sink.persisted.size());
            assertEquals(ChatEventType.AGENT_MCP_TOOL_USE, sink.persisted.get(0).type());
            String payload = sink.persisted.get(0).payloadJson();
            assertTrue(payload.contains("\"mcp_server_name\":\"weather\""), payload);
            assertTrue(payload.contains("\"evaluated_permission\":\"ask\""), payload);
            assertTrue(payload.contains("\"name\":\"mcp__weather__get_weather\""), payload);
        }

        @Test
        void should_defaultMcpPermissionToAllow_when_handle_given_mcpToolCallEndWithoutPolicy() {
            // given：MCP 工具未配置任何权限策略（走框架默认放行路径）
            TurnRunState runState = new TurnRunState();
            RecordingRoundSink sink = new RecordingRoundSink();
            handler.handle(ctx(AgentStreamSignal.tool(AgentStreamSignalType.TOOL_CALL_START, "tc-1",
                    "mcp__public__lookup", null, null), runState, sink, LARGE_FLUSH_INTERVAL_MS));

            // when
            handler.handle(ctx(AgentStreamSignal.tool(AgentStreamSignalType.TOOL_CALL_END, "tc-1",
                    "mcp__public__lookup", null, null), runState, sink, LARGE_FLUSH_INTERVAL_MS));

            // then：仍投影为 MCP 事件（前缀判定不依赖策略存在），求值缺省 allow
            assertEquals(ChatEventType.AGENT_MCP_TOOL_USE, sink.persisted.get(0).type());
            assertTrue(sink.persisted.get(0).payloadJson().contains("\"evaluated_permission\":\"allow\""),
                    sink.persisted.get(0).payloadJson());
        }
    }

    @Nested
    class ToolResult {
        private final ToolResultSignalHandler handler = new ToolResultSignalHandler();

        @Test
        void should_accumulateWithoutSink_when_handle_given_toolResultTextDelta() {
            // given
            TurnRunState runState = new TurnRunState();
            RecordingRoundSink sink = new RecordingRoundSink();

            // when
            handler.handle(ctx(AgentStreamSignal.tool(AgentStreamSignalType.TOOL_RESULT_TEXT_DELTA, "tc-1",
                    "search", "结果片段", null), runState, sink, LARGE_FLUSH_INTERVAL_MS));

            // then：head 窗口累积，不落库不推帧
            assertTrue(sink.persisted.isEmpty());
            assertTrue(sink.frames.isEmpty());
        }

        @Test
        void should_persistToolResult_when_handle_given_toolResultEnd() {
            // given：先累积再结束
            TurnRunState runState = new TurnRunState();
            RecordingRoundSink sink = new RecordingRoundSink();
            handler.handle(ctx(AgentStreamSignal.tool(AgentStreamSignalType.TOOL_RESULT_TEXT_DELTA, "tc-1",
                    "search", "结果", null), runState, sink, LARGE_FLUSH_INTERVAL_MS));

            // when
            handler.handle(ctx(AgentStreamSignal.tool(AgentStreamSignalType.TOOL_RESULT_END, "tc-1",
                    "search", null, "OK"), runState, sink, LARGE_FLUSH_INTERVAL_MS));

            // then
            assertEquals(1, sink.persisted.size());
            assertEquals(ChatEventType.AGENT_TOOL_RESULT, sink.persisted.get(0).type());
        }

        @Test
        void should_maskMountedCredentialPlaintext_when_handle_given_toolResultEchoingCredential() {
            // given（工具回显本轮挂载凭据明文：裸 JWT 形态——形态正则既非 sk- 亦无 Bearer 前缀，
            //        只有按已知明文精确掩码能拦住，见 SecretMasker.maskExactValues）
            String jwt = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJ1c2VyLTEifQ.c2lnbmF0dXJlLWJ5dGVz";
            TurnRunState runState = new TurnRunState();
            runState.registerMountedVaultSecrets(List.of(new AgentAssemblySpec.VaultCredentialRef(
                    "vault_1", "cr_1", "static_bearer", "https://mcp.example.com/mcp", jwt)));
            RecordingRoundSink sink = new RecordingRoundSink();
            handler.handle(ctx(AgentStreamSignal.tool(AgentStreamSignalType.TOOL_RESULT_TEXT_DELTA, "tc-1",
                    "mcp__weather__get_weather", "head 回显 " + jwt, null),
                    runState, sink, LARGE_FLUSH_INTERVAL_MS));

            // when（尾部同样回显明文：head 与 tail 两段都须在出站前被掩码）
            handler.handle(ctx(AgentStreamSignal.tool(AgentStreamSignalType.TOOL_RESULT_END, "tc-1",
                    "mcp__weather__get_weather", null, "tail 回显 " + jwt),
                    runState, sink, LARGE_FLUSH_INTERVAL_MS));

            // then（落库与 SSE 广播共用同一载荷串，故此处断言即两处出口对凭证明文零命中）
            assertEquals(1, sink.persisted.size());
            String payload = sink.persisted.get(0).payloadJson();
            assertFalse(payload.contains(jwt), payload);
            assertTrue(payload.contains("****"), payload);
        }

        @Test
        void should_persistMcpToolResultPairedWithCall_when_handle_given_mcpToolResultEnd() {
            // given：MCP 工具结果流（与 agent.mcp_tool_use 按 tool_use_id 配对，不悬空）
            TurnRunState runState = new TurnRunState();
            RecordingRoundSink sink = new RecordingRoundSink();
            handler.handle(ctx(AgentStreamSignal.tool(AgentStreamSignalType.TOOL_RESULT_TEXT_DELTA, "tc-1",
                    "mcp__weather__get_weather", "25C", null), runState, sink, LARGE_FLUSH_INTERVAL_MS));

            // when
            handler.handle(ctx(AgentStreamSignal.tool(AgentStreamSignalType.TOOL_RESULT_END, "tc-1",
                    "mcp__weather__get_weather", null, "OK"), runState, sink, LARGE_FLUSH_INTERVAL_MS));

            // then：类型为 agent.mcp_tool_result，载荷含 mcp_server_name 与配对键 tool_use_id
            assertEquals(1, sink.persisted.size());
            assertEquals(ChatEventType.AGENT_MCP_TOOL_RESULT, sink.persisted.get(0).type());
            String payload = sink.persisted.get(0).payloadJson();
            assertTrue(payload.contains("\"mcp_server_name\":\"weather\""), payload);
            assertTrue(payload.contains("\"tool_use_id\":\"tc-1\""), payload);
        }
    }

    @Nested
    class ModelSpan {
        private final ModelSpanSignalHandler handler = new ModelSpanSignalHandler();

        @Test
        void should_persistModelRequestStart_when_handle_given_modelCallStart() {
            // given
            TurnRunState runState = new TurnRunState();
            RecordingRoundSink sink = new RecordingRoundSink();

            // when
            handler.handle(ctx(AgentStreamSignal.of(AgentStreamSignalType.MODEL_CALL_START, null, null),
                    runState, sink, LARGE_FLUSH_INTERVAL_MS));

            // then
            assertEquals(1, sink.persisted.size());
            assertEquals(ChatEventType.SPAN_MODEL_REQUEST_START, sink.persisted.get(0).type());
        }

        @Test
        void should_persistModelRequestEndAndRememberModel_when_handle_given_modelCallEnd() {
            // given：MODEL_CALL_END 携带 token 与模型名（无便捷工厂，用规范构造器）
            TurnRunState runState = new TurnRunState();
            RecordingRoundSink sink = new RecordingRoundSink();
            AgentStreamSignal end = new AgentStreamSignal(AgentStreamSignalType.MODEL_CALL_END,
                    null, null, null, null, null, null, 10, 20, "gpt-4", null, null);

            // when
            handler.handle(ctx(end, runState, sink, LARGE_FLUSH_INTERVAL_MS));

            // then：落 span.model_request_end + 记忆模型名（供下次 START 兜底）
            assertEquals(1, sink.persisted.size());
            assertEquals(ChatEventType.SPAN_MODEL_REQUEST_END, sink.persisted.get(0).type());
            assertEquals("gpt-4", runState.lastModelName());
        }
    }

    @Nested
    class Lifecycle {
        private final LifecycleSignalHandler handler = new LifecycleSignalHandler();

        @Test
        void should_notTouchSink_when_handle_given_agentResult() {
            // given
            TurnRunState runState = new TurnRunState();
            RecordingRoundSink sink = new RecordingRoundSink();

            // when
            handler.handle(ctx(AgentStreamSignal.of(AgentStreamSignalType.AGENT_RESULT, null, null)
                    .withResultText("最终答复"), runState, sink, LARGE_FLUSH_INTERVAL_MS));

            // then：仅记录最终文本，无落库 / 推帧 / 终态
            assertEquals(0, sink.finalizeNormalCalls);
            assertTrue(sink.persisted.isEmpty());
        }

        @Test
        void should_setExceededGuard_when_handle_given_exceedMaxIters() {
            // given
            TurnRunState runState = new TurnRunState();
            RecordingRoundSink sink = new RecordingRoundSink();

            // when
            handler.handle(ctx(AgentStreamSignal.of(AgentStreamSignalType.EXCEED_MAX_ITERS, null, null),
                    runState, sink, LARGE_FLUSH_INTERVAL_MS));

            // then：置迭代上限守卫，不即时终态（defer 到 onComplete）
            assertTrue(runState.exceededMaxIters());
            assertEquals(0, sink.finalizeNormalCalls);
        }

        @Test
        void should_finalizeNormal_when_handle_given_agentEnd() {
            // given：无上限 / 无等待守卫
            TurnRunState runState = new TurnRunState();
            RecordingRoundSink sink = new RecordingRoundSink();

            // when
            handler.handle(ctx(AgentStreamSignal.of(AgentStreamSignalType.AGENT_END, null, null),
                    runState, sink, LARGE_FLUSH_INTERVAL_MS));

            // then：SDK 终态触发提前正常终态
            assertEquals(1, sink.finalizeNormalCalls);
        }

        @Test
        void should_notFinalize_when_handle_given_agentEndAfterExceed() {
            // given：先置迭代上限
            TurnRunState runState = new TurnRunState();
            RecordingRoundSink sink = new RecordingRoundSink();
            handler.handle(ctx(AgentStreamSignal.of(AgentStreamSignalType.EXCEED_MAX_ITERS, null, null),
                    runState, sink, LARGE_FLUSH_INTERVAL_MS));

            // when
            handler.handle(ctx(AgentStreamSignal.of(AgentStreamSignalType.AGENT_END, null, null),
                    runState, sink, LARGE_FLUSH_INTERVAL_MS));

            // then：迭代上限后 defer，AGENT_END 不即时终态
            assertEquals(0, sink.finalizeNormalCalls);
        }

        @Test
        void should_notFinalize_when_handle_given_agentEndWhileConfirmationPending() {
            // given：先置 HITL 等待守卫
            TurnRunState runState = new TurnRunState();
            RecordingRoundSink sink = new RecordingRoundSink();
            runState.markConfirmationPending();

            // when
            handler.handle(ctx(AgentStreamSignal.of(AgentStreamSignalType.AGENT_END, null, null),
                    runState, sink, LARGE_FLUSH_INTERVAL_MS));

            // then：等待态后 defer，AGENT_END 不即时终态
            assertFalse(sink.finalizeNormalCalls > 0);
        }
    }

    @Nested
    class Hitl {
        private final HitlSignalHandler handler = new HitlSignalHandler();

        @Test
        void should_delegateEnterWaiting_when_handle_given_humanConfirmRequired() {
            // given
            TurnRunState runState = new TurnRunState();
            RecordingRoundSink sink = new RecordingRoundSink();

            // when
            handler.handle(ctx(AgentStreamSignal.hitl(AgentStreamSignalType.HUMAN_CONFIRM_REQUIRED,
                    "reply-1", List.of("tc-1")), runState, sink, LARGE_FLUSH_INTERVAL_MS));

            // then：委托 hitl 子域编排（enterWaitingConfirm）
            assertEquals(1, sink.enterWaitingConfirmCalls);
        }

        @Test
        void should_onlyLogWithoutSink_when_handle_given_humanConfirmResult() {
            // given
            TurnRunState runState = new TurnRunState();
            RecordingRoundSink sink = new RecordingRoundSink();

            // when
            handler.handle(ctx(AgentStreamSignal.hitl(AgentStreamSignalType.HUMAN_CONFIRM_RESULT, "reply-1"),
                    runState, sink, LARGE_FLUSH_INTERVAL_MS));

            // then：确认结果仅日志，无任何副作用出口调用
            assertEquals(0, sink.enterWaitingConfirmCalls);
            assertTrue(sink.persisted.isEmpty());
        }
    }
}
