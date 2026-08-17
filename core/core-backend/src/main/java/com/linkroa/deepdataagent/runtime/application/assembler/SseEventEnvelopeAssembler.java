package com.linkroa.deepdataagent.runtime.application.assembler;

import com.linkroa.deepdataagent.runtime.application.contract.SseEventEnvelope;
import com.linkroa.deepdataagent.runtime.domain.model.ChatEvent;
import com.linkroa.deepdataagent.runtime.domain.model.enums.ChatEventType;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 领域聊天事件 → {@link SseEventEnvelope} 信封装配器（无状态纯装配）。
 * <p>将 {@link ChatEvent} 映射为对外 {@code Message} 信封：{@code type} 取领域事件类型小写、
 * {@code role} 由事件类型派生（工具类为 tool、其余为 assistant）、{@code content} 统一为
 * ContentBlock 数组——块事件（THINKING / MESSAGE / TOOL_CALL / TOOL_CALL_OUTPUT）直取
 * {@code payload.content}，扁平合成事件（RUN_START / RUN_END / RUN_ERROR / ERROR /
 * SESSION_STATUS）包装为 {@code {"type":"data","data":{...}}}；其中 {@code SESSION_STATUS}
 * 额外将 {@code status} 更名为 {@code session_status}（值保持枚举名不变）。</p>
 */
@Component
public class SseEventEnvelopeAssembler {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    @Resource
    private ObjectMapper objectMapper;

    /**
     * 将领域聊天事件装配为对外事件信封。
     */
    public SseEventEnvelope toEnvelope(ChatEvent event) {
        String type = event.eventType().name().toLowerCase(Locale.ROOT);
        String role = roleOf(event.eventType());
        Map<String, Object> payload = parsePayload(event.payload());

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("session_id", event.sessionId());
        metadata.put("round_id", event.roundId());

        List<Map<String, Object>> content = toContent(event.eventType(), payload, metadata);
        return new SseEventEnvelope("message", event.eventId(), event.createdAt(), role, type,
                content, metadata, "completed", event.sequenceNum());
    }

    /** 内容块转换：块事件直取 content 数组（is_last 迁入 metadata），扁平事件包装为 data 块。 */
    private List<Map<String, Object>> toContent(ChatEventType type, Map<String, Object> payload,
                                                Map<String, Object> metadata) {
        Object rawContent = payload.get("content");
        if (rawContent instanceof List<?> blocks) {
            if (payload.get("is_last") instanceof Boolean last) {
                metadata.put("is_last", last);
            }
            return asBlockList(blocks);
        }
        return List.of(Map.of("type", "data", "data", toDataBlock(type, payload)));
    }

    /** 扁平合成事件的数据块；SESSION_STATUS 将 status 更名为 session_status（值保持枚举名不变）。 */
    private Map<String, Object> toDataBlock(ChatEventType type, Map<String, Object> payload) {
        if (type == ChatEventType.SESSION_STATUS) {
            Map<String, Object> data = new LinkedHashMap<>();
            Object status = payload.get("status");
            data.put("session_status", status instanceof String s ? s : "");
            if (payload.containsKey("stop_reason")) {
                data.put("stop_reason", payload.get("stop_reason"));
            }
            return data;
        }
        return payload;
    }

    /** 事件类型 → 信封 role：工具调用 / 工具结果归为 tool，其余归为 assistant。 */
    private static String roleOf(ChatEventType type) {
        return type == ChatEventType.TOOL_CALL || type == ChatEventType.TOOL_CALL_OUTPUT
                ? "tool"
                : "assistant";
    }

    /** 解析 payload JSON（非法或空白收敛为空 Map，不阻断事件流）。 */
    private Map<String, Object> parsePayload(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(raw, MAP_TYPE);
            return parsed == null ? Map.of() : parsed;
        } catch (JacksonException e) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asBlockList(List<?> blocks) {
        return (List<Map<String, Object>>) blocks;
    }
}