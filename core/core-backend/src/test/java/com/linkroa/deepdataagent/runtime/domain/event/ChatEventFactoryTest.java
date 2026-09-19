package com.linkroa.deepdataagent.runtime.domain.event;

import com.linkroa.deepdataagent.runtime.domain.model.TerminalEventSpec;
import com.linkroa.deepdataagent.runtime.domain.model.enums.AgentSessionStatus;
import com.linkroa.deepdataagent.runtime.domain.model.enums.ChatEventType;
import com.linkroa.deepdataagent.runtime.domain.model.enums.TerminalStopReason;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ChatEventFactory} 事件装配工厂单测（扁平 Event 契约）。
 * <p>扁平 Event 下事件身份由顶层 {@code type} + 类型自有字段（payload 顶层展开）表达，
 * 故此处逐方法验证两条：① 装配出的源码事件类型；② 类型特化 payload JSON 的逐字段形状
 * （content 块数组 / 对象形态 stop_reason / 嵌套 error / 配对 id）。payload MUST NOT 占用
 * {@code id} / {@code type} / {@code processed_at} 三个扁平 Event 保留键。</p>
 */
class ChatEventFactoryTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ChatEventFactory factory = ChatEventFactory.INSTANCE;

    // ==================== 出站 Agent 消息 / 思考 ====================

    @Test
    void should_assembleContentBlocks_when_agentMessage_given_text() {
        // when（助手文本以 content 块数组承载，顶层透传）
        ChatEventFactory.AssembledEvent event = factory.agentMessage("这是一段回答");

        // then
        assertEquals(ChatEventType.AGENT_MESSAGE, event.type());
        List<?> content = (List<?>) payload(event).get("content");
        assertEquals(1, content.size());
        assertEquals(Map.of("type", "text", "text", "这是一段回答"), content.getFirst());
    }

    @Test
    void should_convergeNullTextToEmptyBlock_when_agentMessage_given_nullText() {
        // when（null 文本收敛为空 text 块，不阻断事件装配）
        ChatEventFactory.AssembledEvent event = factory.agentMessage(null);

        // then
        List<?> content = (List<?>) payload(event).get("content");
        assertEquals(Map.of("type", "text", "text", ""), content.getFirst());
    }

    @Test
    void should_emitEmptyPayload_when_agentThinking_given_noArgs() {
        // when（思考事件仅表达「存在思考阶段」，不携带思考正文）
        ChatEventFactory.AssembledEvent event = factory.agentThinking();

        // then（空对象载荷；无 text / content 键）
        assertEquals(ChatEventType.AGENT_THINKING, event.type());
        assertTrue(payload(event).isEmpty());
        assertFalse(event.payloadJson().contains("text"));
        assertFalse(event.payloadJson().contains("content"));
    }

    // ==================== 工具调用 / 结果 ====================

    @Test
    void should_assembleToolUseFields_when_toolUse_given_idNameAndInput() {
        // when
        ChatEventFactory.AssembledEvent event = factory.toolUse("tc-1", "search", Map.of("q", "x"));

        // then（tool_use_id / name / input；身份键为 snake_case）
        assertEquals(ChatEventType.AGENT_TOOL_USE, event.type());
        Map<String, Object> payload = payload(event);
        assertEquals("tc-1", payload.get("tool_use_id"));
        assertEquals("search", payload.get("name"));
        assertEquals(Map.of("q", "x"), payload.get("input"));
    }

    @Test
    void should_convergeNullInputToEmptyObject_when_toolUse_given_nullInput() {
        // when
        ChatEventFactory.AssembledEvent event = factory.toolUse("tc-1", "search", null);

        // then
        assertEquals(Map.of(), payload(event).get("input"));
    }

    @Test
    void should_assembleToolResultFields_when_toolResult_given_truncatedOutput() {
        // when
        ChatEventFactory.AssembledEvent event =
                factory.toolResult("tc-1", "search", "success", "结果已返回", true);

        // then（tool_use_id / name / state / output / truncated 全字段）
        assertEquals(ChatEventType.AGENT_TOOL_RESULT, event.type());
        Map<String, Object> payload = payload(event);
        assertEquals("tc-1", payload.get("tool_use_id"));
        assertEquals("search", payload.get("name"));
        assertEquals("success", payload.get("state"));
        assertEquals("结果已返回", payload.get("output"));
        assertEquals(true, payload.get("truncated"));
    }

    @Test
    void should_normalizeBlankStateAndOutput_when_toolResult_given_blankFields() {
        // when（state / output 空白时按 success / 空串兜底）
        ChatEventFactory.AssembledEvent event = factory.toolResult("tc-1", "search", null, null, false);

        // then
        Map<String, Object> payload = payload(event);
        assertEquals("success", payload.get("state"));
        assertEquals("", payload.get("output"));
    }

    @Test
    void should_assembleMcpToolUseWithPermission_when_mcpToolUse_given_fullFields() {
        // when（MCP 调用载荷额外携带服务器名与权限求值）
        ChatEventFactory.AssembledEvent event = factory.mcpToolUse("tc-1", "mcp__weather__get_weather",
                "weather", Map.of("city", "SH"), "ask");

        // then
        assertEquals(ChatEventType.AGENT_MCP_TOOL_USE, event.type());
        Map<String, Object> payload = payload(event);
        assertEquals("tc-1", payload.get("tool_use_id"));
        assertEquals("mcp__weather__get_weather", payload.get("name"));
        assertEquals(Map.of("city", "SH"), payload.get("input"));
        assertEquals("weather", payload.get("mcp_server_name"));
        assertEquals("ask", payload.get("evaluated_permission"));
    }

    @Test
    void should_convergeMissingFields_when_mcpToolUse_given_nullInputAndServerName() {
        // when（入参空对象、服务器名空串兜底）
        ChatEventFactory.AssembledEvent event = factory.mcpToolUse("tc-1", "mcp__weather__get_weather",
                null, null, "allow");

        // then
        Map<String, Object> payload = payload(event);
        assertEquals(Map.of(), payload.get("input"));
        assertEquals("", payload.get("mcp_server_name"));
    }

    @Test
    void should_assemblePairedResult_when_mcpToolResult_given_toolUseId() {
        // when（与 agent.mcp_tool_use 按 tool_use_id 配对，MUST 不悬空）
        ChatEventFactory.AssembledEvent event = factory.mcpToolResult("tc-1", "mcp__weather__get_weather",
                "weather", "success", "25C", false);

        // then
        assertEquals(ChatEventType.AGENT_MCP_TOOL_RESULT, event.type());
        Map<String, Object> payload = payload(event);
        assertEquals("tc-1", payload.get("tool_use_id"));
        assertEquals("mcp__weather__get_weather", payload.get("name"));
        assertEquals("weather", payload.get("mcp_server_name"));
        assertEquals("success", payload.get("state"));
        assertEquals("25C", payload.get("output"));
    }

    @Test
    void should_normalizeBlankState_when_mcpToolResult_given_blankState() {
        // when
        ChatEventFactory.AssembledEvent event = factory.mcpToolResult("tc-1", "mcp__weather__get_weather",
                "weather", null, null, false);

        // then
        Map<String, Object> payload = payload(event);
        assertEquals("success", payload.get("state"));
        assertEquals("", payload.get("output"));
    }

    // ==================== 产物交付 ====================

    @Test
    void should_assembleArtifactFields_when_artifactDelivered_given_fullFileMeta() {
        // when（逐文件一条、保序）
        ChatEventFactory.AssembledEvent event =
                factory.artifactDelivered("file_1", "报告.pdf", 2048L, "application/pdf");

        // then（file_id / original_filename / size / content_type 逐字段）
        assertEquals(ChatEventType.AGENT_ARTIFACT_DELIVERED, event.type());
        Map<String, Object> payload = payload(event);
        assertEquals("file_1", payload.get("file_id"));
        assertEquals("报告.pdf", payload.get("original_filename"));
        assertEquals(2048, payload.get("size"));
        assertEquals("application/pdf", payload.get("content_type"));
    }

    // ==================== 会话 / 线程状态 ====================

    @Test
    void should_assembleObjectStopReason_when_sessionStatus_given_idleWithStopReason() {
        // when
        ChatEventFactory.AssembledEvent event =
                factory.sessionStatus(AgentSessionStatus.IDLE, TerminalStopReason.STOP);

        // then（stop_reason 为对象；不冗余携带与类型重复的 status）
        assertEquals(ChatEventType.SESSION_STATUS_IDLE, event.type());
        Map<String, Object> payload = payload(event);
        assertEquals(Map.of("type", "end_turn"), payload.get("stop_reason"));
        assertFalse(payload.containsKey("status"));
    }

    @Test
    void should_emitEmptyPayload_when_sessionStatus_given_runningWithoutStopReason() {
        // when（开场状态事件无终态原因）
        ChatEventFactory.AssembledEvent event = factory.sessionStatus(AgentSessionStatus.RUNNING, null);

        // then
        assertEquals(ChatEventType.SESSION_STATUS_RUNNING, event.type());
        assertTrue(payload(event).isEmpty());
        assertFalse(event.payloadJson().contains("stop_reason"));
    }

    @Test
    void should_mirrorThreadStatusType_when_threadStatus_given_idleWithStopReason() {
        // when（主线程状态镜像：类型由 session.thread_status_ 前缀 + 状态值派生）
        ChatEventFactory.AssembledEvent event =
                factory.threadStatus(AgentSessionStatus.IDLE, TerminalStopReason.INTERRUPTED);

        // then
        assertEquals(ChatEventType.SESSION_THREAD_STATUS_IDLE, event.type());
        assertEquals(Map.of("type", "interrupted"), payload(event).get("stop_reason"));
    }

    @Test
    void should_mirrorThreadStatusType_when_threadStatus_given_terminatedWithMaxIterations() {
        // when（达到迭代上限的终止镜像）
        ChatEventFactory.AssembledEvent event =
                factory.threadStatus(AgentSessionStatus.TERMINATED, TerminalStopReason.MAX_ITERATIONS);

        // then
        assertEquals(ChatEventType.SESSION_THREAD_STATUS_TERMINATED, event.type());
        assertEquals(Map.of("type", "max_iterations"), payload(event).get("stop_reason"));
    }

    // ==================== 会话错误 / 更新 / 删除 ====================

    @Test
    void should_assembleNestedError_when_sessionError_given_codeAndMessage() {
        // when
        ChatEventFactory.AssembledEvent event = factory.sessionError("DEEP_AGENT_RUN_ERROR", "执行超时");

        // then（error 嵌套对象：type / message / retry_status 对象 / error_code）
        assertEquals(ChatEventType.SESSION_ERROR, event.type());
        Map<String, Object> error = nested(event, "error");
        assertEquals("run_error", error.get("type"));
        assertEquals("执行超时", error.get("message"));
        assertEquals(Map.of("type", "terminal"), error.get("retry_status"));
        assertEquals("DEEP_AGENT_RUN_ERROR", error.get("error_code"));
        assertFalse(payload(event).containsKey("content"));
    }

    @Test
    void should_applyDefaults_when_sessionError_given_blankCodeAndMessage() {
        // when（code / message 空白时按默认值兜底）
        ChatEventFactory.AssembledEvent event = factory.sessionError(null, null);

        // then
        Map<String, Object> error = nested(event, "error");
        assertEquals("DEEP_AGENT_RUN_ERROR", error.get("error_code"));
        assertEquals("agent 执行失败", error.get("message"));
    }

    @Test
    void should_keepOnlyChangedFields_when_sessionUpdated_given_selectiveFields() {
        // when（选择性字段：title + metadata，永不携带环境变量）
        ChatEventFactory.AssembledEvent event = factory.sessionUpdated(
                Map.of("title", "新标题", "metadata", Map.of("k", "v")));

        // then
        assertEquals(ChatEventType.SESSION_UPDATED, event.type());
        Map<String, Object> payload = payload(event);
        assertEquals("新标题", payload.get("title"));
        assertEquals(Map.of("k", "v"), payload.get("metadata"));
        assertFalse(event.payloadJson().contains("environment_variables"));
    }

    @Test
    void should_convergeNullFieldsToEmptyObject_when_sessionUpdated_given_nullFields() {
        // when（null 字段集合收敛为空对象，不阻断事件装配）
        ChatEventFactory.AssembledEvent event = factory.sessionUpdated(null);

        // then
        assertTrue(payload(event).isEmpty());
    }

    @Test
    void should_emitEmptyPayload_when_sessionDeleted_given_noArgs() {
        // when（删除事件载荷为空对象，级联删除前实时广播）
        ChatEventFactory.AssembledEvent event = factory.sessionDeleted();

        // then
        assertEquals(ChatEventType.SESSION_DELETED, event.type());
        assertTrue(payload(event).isEmpty());
    }

    // ==================== 终态事件规格 → 装配（决策表输出侧） ====================

    @Test
    void should_assembleStatusEvent_when_terminalEvent_given_statusSpec() {
        // given（收场顺序后半：会话状态事件）
        TerminalEventSpec spec = TerminalEventSpec.statusEvent(AgentSessionStatus.IDLE, TerminalStopReason.STOP);

        // when
        ChatEventFactory.AssembledEvent event = factory.terminalEvent(spec);

        // then（与直接调用 sessionStatus 等价）
        assertEquals(ChatEventType.SESSION_STATUS_IDLE, event.type());
        assertEquals(Map.of("type", "end_turn"), payload(event).get("stop_reason"));
    }

    @Test
    void should_assembleThreadStatusEvent_when_terminalEvent_given_threadStatusSpec() {
        // given（收场顺序前半：线程状态镜像事件）
        TerminalEventSpec spec =
                TerminalEventSpec.threadStatusEvent(AgentSessionStatus.IDLE, TerminalStopReason.ERROR);

        // when
        ChatEventFactory.AssembledEvent event = factory.terminalEvent(spec);

        // then
        assertEquals(ChatEventType.SESSION_THREAD_STATUS_IDLE, event.type());
        assertEquals(Map.of("type", "error"), payload(event).get("stop_reason"));
    }

    @Test
    void should_assembleErrorEvent_when_terminalEvent_given_errorSpec() {
        // given
        TerminalEventSpec spec = TerminalEventSpec.errorEvent("DEEP_AGENT_RUN_ERROR", "模型调用超时");

        // when
        ChatEventFactory.AssembledEvent event = factory.terminalEvent(spec);

        // then
        assertEquals(ChatEventType.SESSION_ERROR, event.type());
        Map<String, Object> error = nested(event, "error");
        assertEquals("DEEP_AGENT_RUN_ERROR", error.get("error_code"));
        assertEquals("模型调用超时", error.get("message"));
    }

    // ==================== 模型调用计量（token 下沉，不产出 credits） ====================

    @Test
    void should_assembleModel_when_modelRequestStart_given_modelName() {
        // when
        ChatEventFactory.AssembledEvent event = factory.modelRequestStart("openai:gpt-4");

        // then
        assertEquals(ChatEventType.SPAN_MODEL_REQUEST_START, event.type());
        assertEquals("openai:gpt-4", payload(event).get("model"));
    }

    @Test
    void should_assemblePairedUsage_when_modelRequestEnd_given_usage() {
        // when（配对起点 id + token 计量下沉；不产出 credits）
        ChatEventFactory.AssembledEvent event =
                factory.modelRequestEnd("evt_start", false, 120, 35, "openai:gpt-4");

        // then
        assertEquals(ChatEventType.SPAN_MODEL_REQUEST_END, event.type());
        Map<String, Object> payload = payload(event);
        assertEquals(false, payload.get("is_error"));
        assertEquals("evt_start", payload.get("model_request_start_id"));
        Map<String, Object> usage = asMap(payload.get("model_usage"));
        assertEquals(120, usage.get("input_tokens"));
        assertEquals(35, usage.get("output_tokens"));
        assertEquals("openai:gpt-4", usage.get("model"));
        assertFalse(event.payloadJson().contains("credits"));
    }

    @Test
    void should_applyZeroDefaults_when_modelRequestEnd_given_nullTokens() {
        // when（null token 按 0 兜底）
        ChatEventFactory.AssembledEvent event = factory.modelRequestEnd("evt_start", false, null, null, "m");

        // then
        Map<String, Object> usage = asMap(payload(event).get("model_usage"));
        assertEquals(0, usage.get("input_tokens"));
        assertEquals(0, usage.get("output_tokens"));
    }

    // ==================== 流式专用帧（不落库、不回放） ====================

    @Test
    void should_assembleStartFrame_when_eventStart_given_eventIdAndTargetType() {
        // when（event_start 帧载荷 = event{id,type}，无顶层 id / processed_at）
        ChatEventFactory.AssembledEvent event = factory.eventStart("evt_1", ChatEventType.AGENT_MESSAGE);

        // then（event.id 与最终 buffered 事件 ID 完全一致）
        assertEquals(ChatEventType.EVENT_START, event.type());
        assertEquals("{\"event\":{\"id\":\"evt_1\",\"type\":\"agent.message\"}}", event.payloadJson());
    }

    @Test
    void should_assembleDeltaFrame_when_eventDelta_given_deltaText() {
        // when（event_delta 帧载荷 = event_id + delta{type,index,content}）
        ChatEventFactory.AssembledEvent event = factory.eventDelta("evt_1", "增量文本");

        // then
        assertEquals(ChatEventType.EVENT_DELTA, event.type());
        assertEquals("{\"event_id\":\"evt_1\",\"delta\":{\"type\":\"content_delta\",\"index\":0,"
                + "\"content\":{\"type\":\"text\",\"text\":\"增量文本\"}}}", event.payloadJson());
    }

    @Test
    void should_applyBlankDeltaDefault_when_eventDelta_given_nullDelta() {
        // when（null delta 按空串兜底）
        ChatEventFactory.AssembledEvent event = factory.eventDelta("evt_1", null);

        // then
        Map<String, Object> content = asMap(nested(event, "delta").get("content"));
        assertEquals("", content.get("text"));
    }

    // ==================== 装配辅助 ====================

    /** 解析类型特化 payload JSON 为顶层字段 Map。 */
    private static Map<String, Object> payload(ChatEventFactory.AssembledEvent event) {
        try {
            Map<String, Object> parsed = OBJECT_MAPPER.readValue(event.payloadJson(), MAP_TYPE);
            return parsed == null ? Map.of() : parsed;
        } catch (JacksonException e) {
            throw new IllegalStateException("payload 解析失败", e);
        }
    }

    /** 逐层取嵌套对象字段（断言嵌套形状用）。 */
    private static Map<String, Object> nested(ChatEventFactory.AssembledEvent event, String key) {
        return asMap(payload(event).get(key));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }
}