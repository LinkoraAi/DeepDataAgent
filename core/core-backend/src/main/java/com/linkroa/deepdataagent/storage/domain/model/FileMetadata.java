package com.linkroa.deepdataagent.storage.domain.model;

import java.time.Instant;

/**
 * 对象元数据值对象。
 * <p>描述对象存储中单个文件对象的元信息，供列表、元数据查询与下载/预览响应头组装使用。</p>
 *
 * @param objectKey   对象唯一标识（存储路径，由调用方在命名空间约定下自定义）
 * @param size        对象字节数
 * @param contentType 对象内容类型（可能在列表场景下不可得，允许为空）
 * @param lastModified 最后修改时间（Instant，序列化对齐系统中国时区）
 * @param etag        对象 ETag（可能不可得，允许为空）
 */
public record FileMetadata(String objectKey, long size, String contentType, Instant lastModified, String etag) {
}