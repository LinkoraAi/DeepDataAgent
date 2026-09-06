package com.linkroa.deepdataagent.storage.application.query;

/**
 * 列出文件对象查询。
 *
 * @param bucket 目标桶（必填，桶不存在将触发 404）
 * @param prefix 对象 key 前缀（可为空串，表示列出全部）
 */
public record ListFileObjectsQuery(String bucket, String prefix) {
}