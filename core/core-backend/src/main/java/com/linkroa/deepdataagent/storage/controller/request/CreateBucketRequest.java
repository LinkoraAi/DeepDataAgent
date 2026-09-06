package com.linkroa.deepdataagent.storage.controller.request;

/**
 * 创建桶请求 DTO。
 *
 * @param bucket 桶名（S3 命名规范子集，必填）
 */
public record CreateBucketRequest(String bucket) {
}