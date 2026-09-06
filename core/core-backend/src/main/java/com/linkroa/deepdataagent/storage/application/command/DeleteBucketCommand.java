package com.linkroa.deepdataagent.storage.application.command;

/**
 * 删除桶命令。
 *
 * @param bucket 桶名（仅允许空桶：缺失抛 404，非空抛 409）
 */
public record DeleteBucketCommand(String bucket) {
}