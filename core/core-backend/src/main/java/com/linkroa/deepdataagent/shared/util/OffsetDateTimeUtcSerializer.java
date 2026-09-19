package com.linkroa.deepdataagent.shared.util;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ser.std.StdSerializer;

import java.time.OffsetDateTime;

/**
 * {@link OffsetDateTime} → RFC 3339 UTC（{@code Z} 后缀）序列化器。
 * <p>对外 API 契约统一时间口径（shared/api-conventions）：响应时间戳 MUST 为
 * RFC 3339 UTC（如 {@code 2026-05-18T08:00:00Z}），MUST NOT 输出 {@code +08:00}
 * 本地偏移——BREAKING 收敛，序列化层统一按 {@code toInstant()} 归零时区偏移；
 * DB 侧 TIMESTAMPTZ 与 JDBC 会话时区（Asia/Shanghai）保持不变，仅序列化口径变更。</p>
 * <p>整秒值输出 {@code ...T02:30:00Z}；含亚秒精度时按 ISO-8601 输出小数秒
 * （RFC 3339 允许）。REST 侧经 {@code JacksonTimeConfig} 全局注册，
 * SSE 侧由 {@code ChatEventCodec} 显式挂载，两处共用本实现保证口径一致。</p>
 */
public class OffsetDateTimeUtcSerializer extends StdSerializer<OffsetDateTime> {

    /**
     * 构造器：向 Jackson 声明序列化目标类型为 {@link OffsetDateTime}。
     */
    public OffsetDateTimeUtcSerializer() {
        super(OffsetDateTime.class);
    }

    @Override
    public void serialize(OffsetDateTime value, JsonGenerator gen, SerializationContext ctxt) {
        gen.writeString(value.toInstant().toString());
    }
}
