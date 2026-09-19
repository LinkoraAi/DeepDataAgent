package com.linkroa.deepdataagent.runtime.domain.model.enums;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ChatEventType} 权威事件目录单测。
 * <p>验证公开契约事件类型全集（{@code {域}.{动作}} 小写点分）的反解、入站白名单与
 * 已知类型判别，并钉死已废止词汇（六态旧状态事件 / requires_action / interrupted /
 * define_outcome / outcome_evaluation）不得被反解或标记为已知。</p>
 */
class ChatEventTypeTest {

    /** 权威事件目录全部取值（域小写点分；流式帧无域前缀）。 */
    private static final List<String> AUTHORITATIVE_VALUES = List.of(
            "user.message", "user.interrupt", "user.tool_confirmation",
            "user.tool_result", "user.custom_tool_result", "system.message",
            "agent.message", "agent.thinking", "agent.tool_use", "agent.tool_result",
            "agent.custom_tool_use", "agent.mcp_tool_use", "agent.mcp_tool_result",
            "agent.artifact_delivered", "agent.thread_context_compacted",
            "session.status_running", "session.status_idle", "session.status_rescheduled",
            "session.status_terminated", "session.error", "session.updated", "session.deleted",
            "session.thread_created", "session.thread_status_running", "session.thread_status_idle",
            "session.thread_status_rescheduled", "session.thread_status_terminated",
            "span.model_request_start", "span.model_request_end",
            "event_start", "event_delta");

    /** 已废止词汇（MUST NOT 产出或接受）。 */
    private static final List<String> REMOVED_VALUES = List.of(
            "session.requires_action", "session.interrupted",
            "session.status_processing", "session.status_canceling",
            "session.status_waiting_confirmation", "session.status_archived",
            "session.status_created",
            "user.define_outcome", "span.outcome_evaluation_start", "span.outcome_evaluation_end");

    @Test
    void should_parseInboundTypes_when_fromValue_given_inboundNames() {
        // when & then（入站白名单六类，含批尾注入的 system.message）
        assertEquals(ChatEventType.USER_MESSAGE, ChatEventType.fromValue("user.message"));
        assertEquals(ChatEventType.USER_INTERRUPT, ChatEventType.fromValue("user.interrupt"));
        assertEquals(ChatEventType.USER_TOOL_CONFIRMATION, ChatEventType.fromValue("user.tool_confirmation"));
        assertEquals(ChatEventType.USER_TOOL_RESULT, ChatEventType.fromValue("user.tool_result"));
        assertEquals(ChatEventType.USER_CUSTOM_TOOL_RESULT, ChatEventType.fromValue("user.custom_tool_result"));
        assertEquals(ChatEventType.SYSTEM_MESSAGE, ChatEventType.fromValue("system.message"));
    }

    @Test
    void should_parseAgentTypes_when_fromValue_given_agentNames() {
        // when & then（出站 Agent 域，含新增产物交付事件）
        assertEquals(ChatEventType.AGENT_MESSAGE, ChatEventType.fromValue("agent.message"));
        assertEquals(ChatEventType.AGENT_THINKING, ChatEventType.fromValue("agent.thinking"));
        assertEquals(ChatEventType.AGENT_TOOL_USE, ChatEventType.fromValue("agent.tool_use"));
        assertEquals(ChatEventType.AGENT_TOOL_RESULT, ChatEventType.fromValue("agent.tool_result"));
        assertEquals(ChatEventType.AGENT_CUSTOM_TOOL_USE, ChatEventType.fromValue("agent.custom_tool_use"));
        assertEquals(ChatEventType.AGENT_MCP_TOOL_USE, ChatEventType.fromValue("agent.mcp_tool_use"));
        assertEquals(ChatEventType.AGENT_MCP_TOOL_RESULT, ChatEventType.fromValue("agent.mcp_tool_result"));
        assertEquals(ChatEventType.AGENT_ARTIFACT_DELIVERED,
                ChatEventType.fromValue("agent.artifact_delivered"));
        assertEquals(ChatEventType.AGENT_THREAD_CONTEXT_COMPACTED,
                ChatEventType.fromValue("agent.thread_context_compacted"));
    }

    @Test
    void should_parseSessionStatusTypes_when_fromValue_given_fourStateNames() {
        // when & then（对外四态；rescheduling 词汇预留）
        assertEquals(ChatEventType.SESSION_STATUS_RUNNING, ChatEventType.fromValue("session.status_running"));
        assertEquals(ChatEventType.SESSION_STATUS_IDLE, ChatEventType.fromValue("session.status_idle"));
        assertEquals(ChatEventType.SESSION_STATUS_RESCHEDULED,
                ChatEventType.fromValue("session.status_rescheduled"));
        assertEquals(ChatEventType.SESSION_STATUS_TERMINATED,
                ChatEventType.fromValue("session.status_terminated"));
    }

    @Test
    void should_parseThreadStatusTypes_when_fromValue_given_fourStateNames() {
        // when & then（主线程状态镜像四态）
        assertEquals(ChatEventType.SESSION_THREAD_STATUS_RUNNING,
                ChatEventType.fromValue("session.thread_status_running"));
        assertEquals(ChatEventType.SESSION_THREAD_STATUS_IDLE,
                ChatEventType.fromValue("session.thread_status_idle"));
        assertEquals(ChatEventType.SESSION_THREAD_STATUS_RESCHEDULED,
                ChatEventType.fromValue("session.thread_status_rescheduled"));
        assertEquals(ChatEventType.SESSION_THREAD_STATUS_TERMINATED,
                ChatEventType.fromValue("session.thread_status_terminated"));
        assertEquals(ChatEventType.SESSION_THREAD_CREATED, ChatEventType.fromValue("session.thread_created"));
    }

    @Test
    void should_parseSessionLifecycleTypes_when_fromValue_given_errorUpdatedDeleted() {
        // when & then
        assertEquals(ChatEventType.SESSION_ERROR, ChatEventType.fromValue("session.error"));
        assertEquals(ChatEventType.SESSION_UPDATED, ChatEventType.fromValue("session.updated"));
        assertEquals(ChatEventType.SESSION_DELETED, ChatEventType.fromValue("session.deleted"));
    }

    @Test
    void should_parseSpanAndStreamTypes_when_fromValue_given_modelAndFrameNames() {
        // when & then（计量 span 与流式专用帧）
        assertEquals(ChatEventType.SPAN_MODEL_REQUEST_START,
                ChatEventType.fromValue("span.model_request_start"));
        assertEquals(ChatEventType.SPAN_MODEL_REQUEST_END, ChatEventType.fromValue("span.model_request_end"));
        assertEquals(ChatEventType.EVENT_START, ChatEventType.fromValue("event_start"));
        assertEquals(ChatEventType.EVENT_DELTA, ChatEventType.fromValue("event_delta"));
    }

    @Test
    void should_parseCaseInsensitive_when_fromValue_given_mixedCaseValue() {
        // when & then（大小写不敏感，规范值仍小写）
        assertEquals(ChatEventType.AGENT_MESSAGE, ChatEventType.fromValue("Agent.Message"));
        assertEquals(ChatEventType.AGENT_MESSAGE, ChatEventType.fromValue("  agent.message  "));
    }

    @Test
    void should_throw_when_fromValue_given_blankOrNull() {
        // when & then
        assertThrows(IllegalArgumentException.class, () -> ChatEventType.fromValue(null));
        assertThrows(IllegalArgumentException.class, () -> ChatEventType.fromValue("  "));
    }

    @Test
    void should_throw_when_fromValue_given_legacyEnumName() {
        // when & then（旧私有枚举名无域前缀，严格反解拒绝）
        assertThrows(IllegalArgumentException.class, () -> ChatEventType.fromValue("run_start"));
        assertThrows(IllegalArgumentException.class, () -> ChatEventType.fromValue("human_confirm_required"));
        assertThrows(IllegalArgumentException.class, () -> ChatEventType.fromValue("message"));
    }

    @Test
    void should_throw_when_fromValue_given_removedEventNames() {
        // when & then（已废止事件词汇一律不可反解——既不得产出也不得接受）
        for (String removed : REMOVED_VALUES) {
            assertThrows(IllegalArgumentException.class, () -> ChatEventType.fromValue(removed),
                    "已废止事件类型不应可反解: " + removed);
        }
    }

    @Test
    void should_throw_when_fromValue_given_unknownValue() {
        // when & then
        assertThrows(IllegalArgumentException.class, () -> ChatEventType.fromValue("unknown_type"));
    }

    @Test
    void should_markInbound_when_inbound_given_inboundWhitelist() {
        // given & when & then（入站白名单逐项；system.message 属公开第七类入站事件）
        for (String inbound : List.of("user.message", "user.interrupt", "user.tool_confirmation",
                "user.tool_result", "user.custom_tool_result", "system.message")) {
            assertTrue(ChatEventType.fromValue(inbound).inbound(), "应为入站事件: " + inbound);
        }
    }

    @Test
    void should_notMarkInbound_when_inbound_given_outboundOrStreamTypes() {
        // when & then（出站 / 线程 / 计量 / 流式帧均非入站）
        assertFalse(ChatEventType.AGENT_MESSAGE.inbound());
        assertFalse(ChatEventType.AGENT_ARTIFACT_DELIVERED.inbound());
        assertFalse(ChatEventType.SESSION_STATUS_IDLE.inbound());
        assertFalse(ChatEventType.SESSION_THREAD_STATUS_RUNNING.inbound());
        assertFalse(ChatEventType.SESSION_ERROR.inbound());
        assertFalse(ChatEventType.SPAN_MODEL_REQUEST_START.inbound());
        assertFalse(ChatEventType.EVENT_START.inbound());
        assertFalse(ChatEventType.EVENT_DELTA.inbound());
    }

    @Test
    void should_returnTrue_when_isKnown_given_authoritativeValues() {
        // when & then（权威目录全部取值均为已知；含新增 artifact 与 rescheduled 词汇）
        for (String value : AUTHORITATIVE_VALUES) {
            assertTrue(ChatEventType.isKnown(value), "权威事件类型应为已知: " + value);
        }
    }

    @Test
    void should_returnFalse_when_isKnown_given_removedOrUnknownOrNull() {
        // when & then（已废止 / 未知 / null 均非已知）
        for (String removed : REMOVED_VALUES) {
            assertFalse(ChatEventType.isKnown(removed), "已废止事件类型不应为已知: " + removed);
        }
        assertFalse(ChatEventType.isKnown("unknown_type"));
        assertFalse(ChatEventType.isKnown(null));
    }

    @Test
    void should_resolveWholeCatalog_when_fromValue_given_allAuthoritativeValues() {
        // given（枚举条目数与权威目录一一对应）
        assertEquals(ChatEventType.values().length, AUTHORITATIVE_VALUES.size(),
                "枚举条目应与权威事件目录等长（新增/删除事件须同步本清单）");

        // when & then（每个取值严格反解回自身）
        for (String value : AUTHORITATIVE_VALUES) {
            assertEquals(value, ChatEventType.fromValue(value).value());
        }
    }

    @Test
    void should_useStatusPrefixes_when_value_given_statusEvents() {
        // when & then（会话四态与线程四态事件名均带统一前缀）
        for (ChatEventType type : new ChatEventType[]{
                ChatEventType.SESSION_STATUS_RUNNING, ChatEventType.SESSION_STATUS_IDLE,
                ChatEventType.SESSION_STATUS_RESCHEDULED, ChatEventType.SESSION_STATUS_TERMINATED}) {
            assertTrue(type.value().startsWith(ChatEventType.SESSION_STATUS_PREFIX));
        }
        for (ChatEventType type : new ChatEventType[]{
                ChatEventType.SESSION_THREAD_STATUS_RUNNING, ChatEventType.SESSION_THREAD_STATUS_IDLE,
                ChatEventType.SESSION_THREAD_STATUS_RESCHEDULED, ChatEventType.SESSION_THREAD_STATUS_TERMINATED}) {
            assertTrue(type.value().startsWith(ChatEventType.THREAD_STATUS_PREFIX));
        }
        assertEquals("session.status_", ChatEventType.SESSION_STATUS_PREFIX);
        assertEquals("session.thread_status_", ChatEventType.THREAD_STATUS_PREFIX);
    }

    @Test
    void should_coverBuiltinAndMcp_when_toolTypes_given_customToolUseExcluded() {
        // when（平台侧工具调用账本仅内置与 MCP 两类：客户端 agent.custom_tool_use 不参与
        // 权限求值、也不进 HITL 批次重建集合）
        List<String> useTypes = ChatEventType.TOOL_USE_TYPES;
        List<String> resultTypes = ChatEventType.TOOL_RESULT_TYPES;

        // then
        assertEquals(List.of("agent.tool_use", "agent.mcp_tool_use"), useTypes);
        assertEquals(List.of("agent.tool_result", "agent.mcp_tool_result"), resultTypes);
        assertFalse(useTypes.contains(ChatEventType.AGENT_CUSTOM_TOOL_USE.value()));
    }

    @Test
    void should_lowercaseAndDotted_when_value_given_allEnums() {
        // when & then（取值域一律小写；除流式帧外均含点分域前缀）
        for (ChatEventType type : ChatEventType.values()) {
            assertEquals(type.value(), type.value().toLowerCase());
            if (type == ChatEventType.EVENT_START || type == ChatEventType.EVENT_DELTA) {
                continue;
            }
            assertTrue(type.value().contains("."), "非流式帧事件类型应带域前缀: " + type.value());
        }
    }
}