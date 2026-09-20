package com.linkroa.deepdataagent.runtime.domain.model.runstate;

import com.linkroa.deepdataagent.runtime.domain.model.AgentAssemblySpec;
import com.linkroa.deepdataagent.runtime.domain.model.enums.ChatEventType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TurnRunState} 门面单测（原 {@code AgentRunStateTest} 中跨组件协调的断言原样平移：
 * 终态领取清理进行中流、工具事件 ID 随候选现场留存）。
 */
class TurnRunStateTest {

    @Test
    void should_clearInFlightAndPending_when_tryFinalize_given_activeStream() {
        // given（进行中流 + 未刷完缓冲）
        TurnRunState state = new TurnRunState();
        state.markInFlightStream("evt_1", ChatEventType.AGENT_THINKING, "blk-1", 3L);
        state.appendPendingDelta("残留");

        // when（终态化领取资格）
        assertTrue(state.tryFinalize());

        // then（终态即本轮生成结束：清空进行中流与聚合缓冲，重连不再回补失效帧）
        assertNull(state.inFlightStream());
        assertEquals("", state.drainPendingDelta());
        // 二次终态化幂等短路
        assertFalse(state.tryFinalize());
    }

    @Test
    void should_recordCandidateWithEventId_when_rememberConfirmCandidate_given_takenEventMapping() {
        // given：TOOL_CALL_END 时序——take 销毁映射前先把 evt_ 锚点记入候选现场
        TurnRunState state = new TurnRunState();
        state.ensureToolEventId("tc-1");
        String eventId = state.takeToolEventId("tc-1");

        // when
        state.rememberConfirmCandidate("tc-1", "search", "{\"q\":\"x\"}", eventId);

        // then：挂起时经批次定位候选，公开事件 id 锚点随现场留存（映射销毁不影响事件表锚点）
        List<ConfirmCandidateBatch.ConfirmCandidate> batch = state.confirmBatch(List.of("tc-1"));
        assertEquals(1, batch.size());
        assertEquals("tc-1", batch.get(0).toolCallId());
        assertEquals("search", batch.get(0).toolName());
        assertEquals("{\"q\":\"x\"}", batch.get(0).inputJson());
        assertEquals(eventId, batch.get(0).toolEventId());
    }

    @Test
    void should_resolvePolicyByRuntimeName_when_evaluatedPermissionOf_given_registeredMcpPolicy() {
        // given（mcp_toolset 策略：服务端原始工具名 + 服务器名经实名约定落表）
        TurnRunState state = new TurnRunState();
        state.registerMcpToolPolicies(List.of(
                new AgentAssemblySpec.ToolExecutionPolicy("get_weather", "always_ask", "weather"),
                new AgentAssemblySpec.ToolExecutionPolicy("delete_city", "always_deny", "weather")));

        // when/then：按运行时实名命中（按原始工具名查不到），发布值为求值结果词汇
        assertEquals("ask", state.evaluatedPermissionOf("mcp__weather__get_weather"));
        assertEquals("deny", state.evaluatedPermissionOf("mcp__weather__delete_city"));
        assertEquals("allow", state.evaluatedPermissionOf("get_weather"));
    }

    @Test
    void should_defaultToAllow_when_evaluatedPermissionOf_given_unregisteredOrNonMcpTool() {
        // given（仅有内置工具策略登记，无 MCP 策略）
        TurnRunState state = new TurnRunState();
        state.registerMcpToolPolicies(List.of(
                new AgentAssemblySpec.ToolExecutionPolicy("Bash", "always_ask")));

        // when/then：内置策略不参与 MCP 求值；未配置策略的工具走框架默认放行路径
        assertEquals("allow", state.evaluatedPermissionOf("mcp__weather__get_weather"));
        assertEquals("allow", state.evaluatedPermissionOf("execute"));
        assertEquals("allow", state.evaluatedPermissionOf(null));
    }

    @Test
    void should_mapAlwaysAllowPolicy_when_evaluatedPermissionOf_given_registeredAllowPolicy() {
        // given（显式 always_allow 策略：与未配置策略同判为 allow）
        TurnRunState state = new TurnRunState();
        state.registerMcpToolPolicies(List.of(
                new AgentAssemblySpec.ToolExecutionPolicy("get_weather", "always_allow", "weather")));

        // when/then
        assertEquals("allow", state.evaluatedPermissionOf("mcp__weather__get_weather"));
    }

    @Test
    void should_ignoreBlankServerName_when_registerMcpToolPolicies_given_nullOrBuiltinPolicies() {
        // given（null 策略列表与内置工具策略混合：均不产生 MCP 规则）
        TurnRunState state = new TurnRunState();

        // when
        state.registerMcpToolPolicies(null);
        state.registerMcpToolPolicies(java.util.Arrays.asList(
                null, new AgentAssemblySpec.ToolExecutionPolicy("Bash", "always_deny")));

        // then
        assertEquals("allow", state.evaluatedPermissionOf("mcp__weather__get_weather"));
    }
}
