package com.linkroa.deepdataagent.runtime.application.convert;

import com.linkroa.deepdataagent.runtime.application.contract.SseEventEnvelope;
import com.linkroa.deepdataagent.runtime.domain.model.ChatEvent;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * 领域聊天事件 → {@link SseEventEnvelope} 扁平 Event 转换器（无状态纯装配）。
 * <p>装配规则（扁平 Event 契约）：固定字段仅取 {@code eventId}（evt_）与 {@code type}
 * （{@code event.type().value()}），{@code processed_at} 取事件处理时间（可空）；
 * 类型自有字段（{@code content} 块数组 / {@code tool_use_id} / {@code stop_reason} /
 * {@code error} / span 字段等）由 payload 原样顶层展开——content blocks 直接透传、
 * 不再派生 {@code role}、不再包装固定槽。持久化事件的领域内部字段
 * （sessionId/seq/roundId/payload/审计字段/DB 主键）一律不进入事件对象。</p>
 */
@Mapper
public interface SseEventEnvelopeConvert {

    SseEventEnvelopeConvert INSTANCE = Mappers.getMapper(SseEventEnvelopeConvert.class);

    ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    /**
     * 将领域聊天事件装配为对外扁平 Event。
     */
    default SseEventEnvelope toEnvelope(ChatEvent event) {
        return new SseEventEnvelope(
                event.eventId(),
                event.type().value(),
                event.processedAt(),
                parsePayload(event.payload())
        );
    }

    /** 解析 payload JSON（非法或空白收敛为空 Map，不阻断事件流）。 */
    private Map<String, Object> parsePayload(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = OBJECT_MAPPER.readValue(raw, MAP_TYPE);
            return parsed == null ? Map.of() : parsed;
        } catch (JacksonException e) {
            return Map.of();
        }
    }
}