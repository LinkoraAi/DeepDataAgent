package com.linkroa.deepdataagent.runtime.domain.model;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;

/**
 * 会话列表游标值对象：以 {@code (created_at, id)} 复合键定位分页断点。
 * <p>游标以 base64url 编码的 {@code "instant:id"} 字符串在协议 / 应用 / 仓储之间
 * 不透明传递（字段为 {@code Instant} + 自增主键，跨时区安全）；空 / 非法游标解析为 null，
 * 表示从第一页开始。</p>
 */
public record SessionCursor(Instant createdAt, Long id) {

    public SessionCursor {
        if (createdAt == null) {
            throw new IllegalArgumentException("游标创建时间不能为空");
        }
        if (id == null) {
            throw new IllegalArgumentException("游标ID不能为空");
        }
    }

    /**
     * 由持久层时间戳与自增主键构造游标。
     */
    public static SessionCursor of(OffsetDateTime createdAt, Long id) {
        if (createdAt == null) {
            throw new IllegalArgumentException("游标创建时间不能为空");
        }
        if (id == null) {
            throw new IllegalArgumentException("游标ID不能为空");
        }
        return new SessionCursor(createdAt.toInstant(), id);
    }

    /**
     * 序列化为不透明游标字符串（URL 安全，无 padding）。
     */
    public String encode() {
        String raw = createdAt + ":" + id;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 解析游标字符串；空 / 非法输入返回 null（表示首页）。
     */
    public static SessionCursor parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            int idx = raw.lastIndexOf(':');
            if (idx <= 0) {
                return null;
            }
            Instant createdAt = Instant.parse(raw.substring(0, idx));
            Long id = Long.valueOf(raw.substring(idx + 1));
            return new SessionCursor(createdAt, id);
        } catch (RuntimeException ex) {
            return null;
        }
    }
}