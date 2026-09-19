package com.linkroa.deepdataagent.file.controller.response;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;

/**
 * 文件响应 DTO（文件对象形态，元数据不含内容）。
 * <p>对外字段名统一 snake_case（Java 组件名保持驼峰，仅以 {@code @JsonProperty} 改写序列化键）：
 * 资源 ID 序列化为 {@code id}、字节数与内容类型为 {@code size_bytes} / {@code mime_type}。</p>
 *
 * @param fileId       文件业务 ID（前缀 file_；序列化键 {@code id}）
 * @param type         对象类型（恒为 file）
 * @param filename     文件名
 * @param mimeType     内容类型（服务端探测；序列化键 {@code mime_type}）
 * @param sizeBytes    内容字节长度（序列化键 {@code size_bytes}）
 * @param purpose      文件用途（五态契约值）
 * @param status       文件状态（ready）
 * @param downloadable 是否可经 /content 直接下载（由 purpose 派生）
 * @param scope        文件作用域（未关联为 null）
 * @param metadata     自定义元数据 JSON 文本（缺省 {}）
 * @param createdAt    创建时间（序列化键 {@code created_at}）
 * @param updatedAt    更新时间（序列化键 {@code updated_at}）
 */
public record FileResponse(
        @JsonProperty("id") String fileId,
        String type,
        String filename,
        @JsonProperty("mime_type") String mimeType,
        @JsonProperty("size_bytes") long sizeBytes,
        String purpose,
        String status,
        boolean downloadable,
        FileScopeResponse scope,
        String metadata,
        @JsonProperty("created_at") OffsetDateTime createdAt,
        @JsonProperty("updated_at") OffsetDateTime updatedAt
) {

    /**
     * 文件作用域响应（{id, type}）。
     *
     * @param id   作用域资源 ID（如 sess_...）
     * @param type 作用域资源类型（当前仅 session）
     */
    public record FileScopeResponse(String id, String type) {
    }
}
