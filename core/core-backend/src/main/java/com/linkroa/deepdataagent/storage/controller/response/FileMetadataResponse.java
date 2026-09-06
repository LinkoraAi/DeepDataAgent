package com.linkroa.deepdataagent.storage.controller.response;

import java.time.Instant;

/**
 * 对象元数据响应 DTO。
 *
 * @param objectKey    对象唯一标识
 * @param size         对象字节数
 * @param contentType  对象内容类型
 * @param lastModified 最后修改时间
 * @param etag         对象 ETag
 */
public record FileMetadataResponse(String objectKey, long size, String contentType, Instant lastModified, String etag) {
}