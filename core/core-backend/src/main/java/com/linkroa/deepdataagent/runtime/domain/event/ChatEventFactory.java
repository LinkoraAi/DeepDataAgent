package com.linkroa.deepdataagent.runtime.domain.event;

import com.linkroa.deepdataagent.runtime.domain.model.TerminalEventSpec;
import com.linkroa.deepdataagent.runtime.domain.model.enums.AgentSessionStatus;
import com.linkroa.deepdataagent.runtime.domain.model.enums.ChatEventType;
import com.linkroa.deepdataagent.runtime.domain.model.enums.TerminalStopReason;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话事件装配工厂（领域事件构造：按信号 / 状态装配类型特化 payload JSON）。
 * <p>扁平 Event 模型下，事件顶层固定字段为 {@code id}（evt_）/ {@code type} / 可选
 * {@code processed_at}，其余字段由本工厂的 payload 顶层展开承载（见
 * {@code application.contract.SseEventEnvelope}）。故 payload 内 MUST NOT 使用
 * {@code id} / {@code type} / {@code processed_at} 三个保留键，类型自有身份一律以
 * snake_case 具名（工具调用身份统一为 {@code tool_use_id}）。</p>
 * <p>要点：消息类事件以 {@code content} content block 数组承载文本；{@code agent.thinking}
 * 不带思考正文（仅存在性 + 空载荷）；状态事件的 {@code stop_reason} 为对象
 * {@code {"type": <值>}} 且不再冗余携带 {@code status}；{@code session.error} 的
 * {@code retry_status} 亦为对象。装配决策与应用层编排解耦，本工厂为无状态纯装配
 * （静态单例 + default 方法，无对象映射语义，故不属 {@code Convert} 命名约定）。</p>
 */
@Mapper
public interface ChatEventFactory {

    ChatEventFactory INSTANCE = Mappers.getMapper(ChatEventFactory.class);

    ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    /** 事件顶层保留键（payload MUST NOT 占用，避免与扁平 Event 信封字段冲突）。 */
    List<String> RESERVED_KEYS = List.of("id", "type", "processed_at");

    /** 每批图像块上限（公开契约：单条消息 image 块 ≤100）。 */
    int MAX_IMAGE_BLOCKS = 100;

    /**
     * 装配结果：源码事件类型 + 类型特化 payload JSON（顶层展开字段）。
     */
    record AssembledEvent(ChatEventType type, String payloadJson) {
    }

    // ==================== 出站 Agent 事件 ====================

    /**
     * 助手文本最终事件（{@code agent.message}，payload.content 为单个 text content block）。
     *
     * @param text 该块完整文本
     */
    default AssembledEvent agentMessage(String text) {
        return event(ChatEventType.AGENT_MESSAGE, values("content", textBlocks(text)));
    }

    /**
     * 思考阶段事件（{@code agent.thinking}）：不带思考正文，payload 为空对象
     * （思考内容不作为消息块对外暴露，仅表达「存在思考阶段」）。
     */
    default AssembledEvent agentThinking() {
        return event(ChatEventType.AGENT_THINKING, Map.of());
    }

    /**
     * 工具调用事件（{@code agent.tool_use}，payload.tool_use_id / name / input）。
     */
    default AssembledEvent toolUse(String toolCallId, String toolName, Map<String, Object> input) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool_use_id", toolCallId);
        payload.put("name", toolName);
        payload.put("input", input == null ? Map.of() : input);
        return event(ChatEventType.AGENT_TOOL_USE, payload);
    }

    /**
     * 工具结果事件（{@code agent.tool_result}，payload.tool_use_id / name / state / output）。
     *
     * @param output    工具结果输出（head+tail，截断时含省略标记与通知）
     * @param truncated 是否发生截断
     */
    default AssembledEvent toolResult(String toolCallId, String toolName, String state,
                                      String output, boolean truncated) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool_use_id", toolCallId);
        payload.put("name", toolName);
        payload.put("state", state == null || state.isBlank() ? "success" : state.toLowerCase());
        payload.put("output", output == null ? "" : output);
        payload.put("truncated", truncated);
        return event(ChatEventType.AGENT_TOOL_RESULT, payload);
    }

    /**
     * MCP 工具调用事件（{@code agent.mcp_tool_use}）。
     * <p>MCP 工具由平台侧（JVM 内）执行，与会话沙箱内发起的内置工具调用在类型上可区分；
     * 载荷额外携带 {@code mcp_server_name}（工具归属的 MCP 服务器）与 {@code evaluated_permission}
     * （版本 {@code permission_policy} 求值结果：allow / ask / deny）。</p>
     *
     * @param toolCallId          SDK 工具调用 id（事件载荷 tool_use_id，与结果事件配对键）
     * @param toolName            运行时工具实名（{@code mcp__{server}__{tool}}）
     * @param mcpServerName       工具归属的 MCP 服务器名
     * @param input               工具入参（可空 → 空对象）
     * @param evaluatedPermission 权限求值结果（allow / ask / deny）
     */
    default AssembledEvent mcpToolUse(String toolCallId, String toolName, String mcpServerName,
                                      Map<String, Object> input, String evaluatedPermission) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool_use_id", toolCallId);
        payload.put("name", toolName);
        payload.put("input", input == null ? Map.of() : input);
        payload.put("mcp_server_name", mcpServerName == null ? "" : mcpServerName);
        payload.put("evaluated_permission", evaluatedPermission);
        return event(ChatEventType.AGENT_MCP_TOOL_USE, payload);
    }

    /**
     * MCP 工具结果事件（{@code agent.mcp_tool_result}，每次 MCP 调用 MUST 有配对结果，不得悬空）。
     *
     * @param toolCallId    SDK 工具调用 id（与 {@code agent.mcp_tool_use} 的 tool_use_id 配对）
     * @param toolName      运行时工具实名
     * @param mcpServerName 工具归属的 MCP 服务器名
     * @param state         结果状态（success / error 等，缺省 success）
     * @param output        工具结果输出（head+tail，已脱敏）
     * @param truncated     是否发生截断
     */
    default AssembledEvent mcpToolResult(String toolCallId, String toolName, String mcpServerName,
                                         String state, String output, boolean truncated) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool_use_id", toolCallId);
        payload.put("name", toolName);
        payload.put("mcp_server_name", mcpServerName == null ? "" : mcpServerName);
        payload.put("state", state == null || state.isBlank() ? "success" : state.toLowerCase());
        payload.put("output", output == null ? "" : output);
        payload.put("truncated", truncated);
        return event(ChatEventType.AGENT_MCP_TOOL_RESULT, payload);
    }

    /**
     * 产物交付事件（{@code agent.artifact_delivered}，逐文件一条、保序）。
     *
     * @param fileId           交付产物的文件业务 ID（{@code file_} 前缀）
     * @param originalFilename 原始文件名
     * @param size             字节数
     * @param contentType      MIME 类型
     */
    default AssembledEvent artifactDelivered(String fileId, String originalFilename, long size,
                                             String contentType) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("file_id", fileId);
        payload.put("original_filename", originalFilename);
        payload.put("size", size);
        payload.put("content_type", contentType);
        return event(ChatEventType.AGENT_ARTIFACT_DELIVERED, payload);
    }

    // ==================== 会话 / 线程状态 / 终态 / 错误 ====================

    /**
     * 会话状态事件（{@code session.status_<status>}）。
     * <p>事件类型名由状态值域派生（如 {@code "session.status_idle"}），与
     * {@link AgentSessionStatus#statusEventType()} 对齐；载荷不再冗余携带与类型重复的
     * {@code status} 字段，仅按需携带对象形态 {@code stop_reason}。</p>
     *
     * @param status     目标会话状态
     * @param stopReason 终态原因（收场事件携带；中段事件传 null）
     */
    default AssembledEvent sessionStatus(AgentSessionStatus status, TerminalStopReason stopReason) {
        return event(ChatEventType.fromValue(status.statusEventType()), stopReasonPayload(stopReason));
    }

    /**
     * 主线程状态镜像事件（{@code session.thread_status_<status>}）：线程先、会话后的收场顺序由
     * 决策表保证，载荷口径与会话状态事件一致。
     *
     * @param status     目标线程状态（值域同 {@link AgentSessionStatus} 四态）
     * @param stopReason 终态原因（收场事件携带；中段事件传 null）
     */
    default AssembledEvent threadStatus(AgentSessionStatus status, TerminalStopReason stopReason) {
        return event(ChatEventType.fromValue(ChatEventType.THREAD_STATUS_PREFIX + status.value()),
                stopReasonPayload(stopReason));
    }

    /**
     * 会话错误事件（{@code session.error}，payload.error 含 type / message / retry_status /
     * error_code；type 取值客户端须兼容未知类型，retry_status 为对象且现状恒 terminal——
     * 无自动重试机制，重试态随执行面重试策略落地）。session.error MUST NOT 自行宣布终局。
     *
     * @param code    错误码（如 DEEP_AGENT_RUN_ERROR，映射 error_code）
     * @param message 已脱敏的错误信息
     */
    default AssembledEvent sessionError(String code, String message) {
        Map<String, Object> retryStatus = new LinkedHashMap<>();
        retryStatus.put("type", "terminal");
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("type", "run_error");
        error.put("message", blankToDefault(message, "agent 执行失败"));
        error.put("retry_status", retryStatus);
        error.put("error_code", blankToDefault(code, "DEEP_AGENT_RUN_ERROR"));
        return event(ChatEventType.SESSION_ERROR, values("error", error));
    }

    /**
     * 会话更新事件（{@code session.updated}）：选择性携带被改动字段，永不带环境变量。
     *
     * @param fields 选择性字段（title / metadata 等，空对象表示无可见变更）
     */
    default AssembledEvent sessionUpdated(Map<String, Object> fields) {
        return event(ChatEventType.SESSION_UPDATED, fields == null ? Map.of() : fields);
    }

    /** 会话删除事件（{@code session.deleted}，级联删除前实时广播，载荷为空对象）。 */
    default AssembledEvent sessionDeleted() {
        return event(ChatEventType.SESSION_DELETED, Map.of());
    }

    /**
     * 终态事件规格 → 装配结果（决策表输出侧的 payload 装配落点）。
     * <p>领域层 {@code TerminalEventSpec} 只声明「落哪类事件、携带什么语义值」，
     * 对外 JSON 形状仍由本工厂单一承载。</p>
     */
    default AssembledEvent terminalEvent(TerminalEventSpec spec) {
        return switch (spec.kind()) {
            case THREAD_STATUS -> threadStatus(spec.status(), spec.stopReason());
            case SESSION_STATUS -> sessionStatus(spec.status(), spec.stopReason());
            case SESSION_ERROR -> sessionError(spec.errorCode(), spec.errorMessage());
        };
    }

    // ==================== 模型调用计量（token 下沉，不产出 credits） ====================

    /**
     * 模型调用开始（{@code span.model_request_start}，span 边界起点）。
     */
    default AssembledEvent modelRequestStart(String modelName) {
        return event(ChatEventType.SPAN_MODEL_REQUEST_START, values("model", modelName));
    }

    /**
     * 模型调用结束（{@code span.model_request_end}，{@code is_error} +
     * {@code model_request_start_id} 配对 + {@code model_usage} 下沉 token 计量与模型标识；
     * 本期不产出 credits 字段）。
     *
     * @param startEventId 配对起点 {@code span.model_request_start} 的 evt_ 事件 ID
     * @param isError      本次模型请求是否以错误结束
     */
    default AssembledEvent modelRequestEnd(String startEventId, boolean isError,
                                           Integer inputTokens, Integer outputTokens, String modelName) {
        Map<String, Object> modelUsage = new LinkedHashMap<>();
        modelUsage.put("input_tokens", inputTokens == null ? 0 : inputTokens);
        modelUsage.put("output_tokens", outputTokens == null ? 0 : outputTokens);
        modelUsage.put("model", modelName);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("is_error", isError);
        payload.put("model_request_start_id", startEventId);
        payload.put("model_usage", modelUsage);
        return event(ChatEventType.SPAN_MODEL_REQUEST_END, payload);
    }

    // ==================== 流式专用帧（不落库、不回放） ====================

    /**
     * 流式开始帧（{@code event_start}，payload = {@code event{id,type}}：
     * {@code event.id}（evt_）与最终 buffered 事件 ID 完全一致，{@code type} 为目标事件类型）。
     */
    default AssembledEvent eventStart(String eventId, ChatEventType targetType) {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("id", eventId);
        inner.put("type", targetType.value());
        return event(ChatEventType.EVENT_START, values("event", inner));
    }

    /**
     * 流式增量帧（{@code event_delta}，payload = {@code event_id + delta{type,index,content}}；
     * delta 为 content block 增量对象，现状仅文本块：{@code content{type:"text",text}}）。
     */
    default AssembledEvent eventDelta(String eventId, String delta) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("type", "text");
        content.put("text", delta == null ? "" : delta);
        Map<String, Object> deltaObj = new LinkedHashMap<>();
        deltaObj.put("type", "content_delta");
        deltaObj.put("index", 0);
        deltaObj.put("content", content);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event_id", eventId);
        payload.put("delta", deltaObj);
        return event(ChatEventType.EVENT_DELTA, payload);
    }

    // ==================== 内部装配 ====================

    /** 单键值对 payload。 */
    private Map<String, Object> values(String key, Object value) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(key, value);
        return payload;
    }

    /** 单个 text content block 组成的数组（消息类事件的 content 取此形态）。 */
    private List<Map<String, Object>> textBlocks(String text) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "text");
        block.put("text", text == null ? "" : text);
        List<Map<String, Object>> blocks = new ArrayList<>(1);
        blocks.add(block);
        return blocks;
    }

    /** 对象形态 stop_reason（{@code {"type": <值>}}）；原因缺省时返回空对象（不写键）。 */
    private Map<String, Object> stopReasonPayload(TerminalStopReason stopReason) {
        if (stopReason == null) {
            return Map.of();
        }
        Map<String, Object> reason = new LinkedHashMap<>();
        reason.put("type", stopReason.value());
        return values("stop_reason", reason);
    }

    /** 空串兜底：值为 null 或空白时返回 fallback。 */
    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /** 序列化装配结果（payload 构造失败属编程错误，直接上抛）。 */
    private AssembledEvent event(ChatEventType type, Map<String, Object> payload) {
        try {
            return new AssembledEvent(type, OBJECT_MAPPER.writeValueAsString(payload));
        } catch (JacksonException e) {
            throw new IllegalStateException("聊天事件 payload 序列化失败", e);
        }
    }
}