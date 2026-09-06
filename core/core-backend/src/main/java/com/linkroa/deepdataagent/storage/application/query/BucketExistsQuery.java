package com.linkroa.deepdataagent.storage.application.query;

/**
 * 桶存在性查询（支持「先查后建」）。
 *
 * @param bucket 桶名
 */
public record BucketExistsQuery(String bucket) {
}