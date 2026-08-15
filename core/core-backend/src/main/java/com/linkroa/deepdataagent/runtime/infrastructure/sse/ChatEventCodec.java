package com.linkroa.deepdataagent.runtime.infrastructure.sse;

import com.linkroa.deepdataagent.runtime.domain.model.ChatEvent;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.StdSerializer;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 领域 {@link ChatEvent} → SSE（name + data）编解码。
 * <p>SSE {@code event} 字段 = 域事件类型小写（run_start / thinking / ...），
 * {@code data} = 事件 JSON（含 event_id / session_id / round_id / sequence_num / payload /
 * created_at）。时间统一 Asia/Shanghai ISO-8601。</p>
 */
public final class ChatEventCodec {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private static final ObjectMapper OBJECT_MAPPER = buildObjectMapper();

    private ChatEventCodec() {
    }

    private static ObjectMapper buildObjectMapper() {
        SimpleModule module = new SimpleModule();
        module.addSerializer(OffsetDateTime.class, new StdSerializer<>(OffsetDateTime.class) {
            @Override
            public void serialize(OffsetDateTime value, JsonGenerator gen, SerializationContext context) {
                gen.writeString(value.withOffsetSameInstant(
                                ZoneId.of("Asia/Shanghai").getRules().getOffset(value.toInstant()))
                        .format(ISO));
            }
        });
        return JsonMapper.builder().addModule(module).build();
    }

    /**
     * 转换为 SSE 事件构造器（event name + data）。
     */
    public static SseEmitter.SseEventBuilder toSseEvent(ChatEvent event) {
        return SseEmitter.event()
                .name(event.eventType().name().toLowerCase(java.util.Locale.ROOT))
                .data(toJson(event));
    }

    public static String toJson(ChatEvent event) {
        try {
            return OBJECT_MAPPER.writeValueAsString(event);
        } catch (JacksonException e) {
            throw new IllegalStateException("ChatEvent SSE 序列化失败", e);
        }
    }
}