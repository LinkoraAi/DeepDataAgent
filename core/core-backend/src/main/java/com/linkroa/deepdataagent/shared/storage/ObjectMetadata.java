package com.linkroa.deepdataagent.shared.storage;

import java.time.Instant;

/**
 * 对象元数据技术 DTO（{@link ObjectStorage#list} 返回项）。
 * <p>仅描述对象存储中的技术元信息，不携带内容字节，也不表达任何业务语义；
 * 列表场景 {@code contentType} 通常不可得（允许 null），{@code etag} 仅作技术信息，
 * <b>禁止</b>被消费方当作内容完整性校验依据（完整性一律以业务侧自算 SHA-256 为准）。</p>
 *
 * @param key          对象 key（相对配置桶 / 根的命名空间路径）
 * @param size         对象字节数
 * @param contentType  内容类型（列表场景可能为 null）
 * @param lastModified 最后修改时间（可能为 null）
 * @param etag         对象 ETag（可能为 null，不得用于完整性校验）
 */
public record ObjectMetadata(String key, long size, String contentType,
                             Instant lastModified, String etag) {

    /**
     * 紧凑构造器：最小不变量校验。
     *
     * @throws IllegalArgumentException key 为空
     */
    public ObjectMetadata {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("对象 key 不能为空");
        }
        if (size < 0) {
            throw new IllegalArgumentException("对象大小不能为负");
        }
    }
}
