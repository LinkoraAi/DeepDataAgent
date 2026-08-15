package com.linkroa.deepdataagent.runtime.application.assembler;

import com.linkroa.deepdataagent.runtime.domain.model.enums.ChatEventType;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 聊天事件 payload 装配（应用层，承接原基础设施层 Adapter 的职责）。
 * <p>将领域中性信号 {@code AgentStreamSignal} 的语义数据装配为 content-blocks 结构的
 * payload JSON：{@code content} 数组成员形如
 * {@code {"type":"text|thinking|tool_call|tool_result", ...}}，顶层携带 {@code is_last}
 * 标记块结束。装配决策与应用层编排（持久化 / 广播 / span）解耦，本类为无状态纯装配器：
 * 状态累积（工具入参聚合、head+tail 截断窗口）由领域模型 {@code AgentRunState} 承载。</p>
 */
@Component
public class ChatEventPayloadAssembler {

    static final String TYPE_TEXT = "text";
    static final String TYPE_THINKING = "thinking";
    static final String TYPE_TOOL_CALL = "tool_call";
    static final String TYPE_TOOL_RESULT = "tool_result";

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    @Resource
    private ObjectMapper objectMapper;

    /**
     * 装配结果：域事件类型 + content-blocks payload JSON。
     */
    public record AssembledEvent(ChatEventType type, String payloadJson) {
    }

    // ==================== 文本 / 推理增量 ====================

    /**
     * 推理增量（is_last=false）。
     */
    public AssembledEvent thinkingDelta(String blockId, String text) {
        return contentEvent(ChatEventType.THINKING,
                block(TYPE_THINKING, text, blockId, null, null, Map.of()), false);
    }

    /**
     * 推理块结束（空 delta + is_last=true 收尾）。
     */
    public AssembledEvent thinkingEnd(String blockId) {
        return contentEvent(ChatEventType.THINKING,
                block(TYPE_THINKING, "", blockId, null, null, Map.of()), true);
    }

    /**
     * 助手文本增量（is_last=false）。
     */
    public AssembledEvent textDelta(String blockId, String text) {
        return contentEvent(ChatEventType.MESSAGE,
                block(TYPE_TEXT, text, blockId, null, null, Map.of()), false);
    }

    /**
     * 文本块结束（空 delta + is_last=true 收尾）。
     */
    public AssembledEvent textEnd(String blockId) {
        return contentEvent(ChatEventType.MESSAGE,
                block(TYPE_TEXT, "", blockId, null, null, Map.of()), true);
    }

    // ==================== 工具调用 ====================

    /**
     * 工具调用开始占位（入参为空，is_last=false；结束时由 {@link #toolCallEnd} 携带聚合入参覆盖）。
     */
    public AssembledEvent toolCallStart(String toolCallId, String toolName) {
        return contentEvent(ChatEventType.TOOL_CALL,
                block(TYPE_TOOL_CALL, null, null, toolCallId, toolName, Map.of()), false);
    }

    /**
     * 工具调用结束（入参已聚合）→ tool_call。
     *
     * @param argsJson 跨 TOOL_CALL_DELTA 聚合出的入参 JSON（非法或空白时收敛为空对象）
     */
    public AssembledEvent toolCallEnd(String toolCallId, String toolName, String argsJson) {
        return contentEvent(ChatEventType.TOOL_CALL,
                block(TYPE_TOOL_CALL, null, null, toolCallId, toolName, parseInput(argsJson)), true);
    }

    /**
     * 工具结果增量（head 窗口内的实时文本，is_last=false）。
     */
    public AssembledEvent toolResultDelta(String toolCallId, String toolName, String headText) {
        return contentEvent(ChatEventType.TOOL_CALL_OUTPUT,
                block(TYPE_TOOL_RESULT, null, null, toolCallId, toolName,
                        outputOf(headText, false)), false);
    }

    /**
     * 工具结果结束（head+tail 截断后补发尾窗，is_last=true）。
     *
     * @param output    截断补发文本（未截断时为纯收尾空串）
     * @param truncated 是否发生截断（超出 head 窗口）
     */
    public AssembledEvent toolResultEnd(String toolCallId, String toolName, String output, boolean truncated) {
        return contentEvent(ChatEventType.TOOL_CALL_OUTPUT,
                block(TYPE_TOOL_RESULT, null, null, toolCallId, toolName,
                        outputOf(output, truncated)), true);
    }

    // ==================== 内部装配 ====================

    /** 组装单个内容块（text/thinking 带 text + block_id；tool_call/tool_result 带 call 关联 + output 子结构）。 */
    private Map<String, Object> block(String type, String text, String blockId,
                                      String toolCallId, String toolName, Map<String, Object> output) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("type", type);
        if (text != null) {
            b.put("text", text);
        }
        if (blockId != null) {
            b.put("block_id", blockId);
        }
        if (toolCallId != null) {
            b.put("tool_call_id", toolCallId);
        }
        if (toolName != null) {
            b.put("name", toolName);
        }
        if (!output.isEmpty()) {
            b.putAll(output);
        }
        return b;
    }

    /** 工具结果子字段（output + truncated，兼容旧 tool_call_output 结构）。 */
    private Map<String, Object> outputOf(String output, boolean truncated) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("output", output == null ? "" : output);
        o.put("truncated", truncated);
        return o;
    }

    /** 顶层 {content:[block], is_last} 包装。 */
    private AssembledEvent contentEvent(ChatEventType type, Map<String, Object> block, boolean isLast) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("content", List.of(block));
        payload.put("is_last", isLast);
        return new AssembledEvent(type, toJson(payload));
    }

    /**
     * 将聚合出的工具入参 JSON 解析为 Map（非法 JSON 时返回空 Map，不阻断事件流）。
     */
    private Map<String, Object> parseInput(String inputJson) {
        if (inputJson == null || inputJson.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(inputJson, MAP_TYPE);
            return parsed == null ? Map.of() : parsed;
        } catch (JacksonException e) {
            return Map.of();
        }
    }

    /** 序列化装配结果（payload 构造失败属编程错误，直接上抛）。 */
    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalStateException("聊天事件 payload 序列化失败", e);
        }
    }
}