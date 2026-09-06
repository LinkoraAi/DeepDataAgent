package com.linkroa.deepdataagent.storage.application.command;

/**
 * 创建桶命令。
 *
 * @param bucket 桶名（S3 命名规范子集，非法抛参数异常 400，重名抛冲突 409）
 */
public record CreateBucketCommand(String bucket) {
}