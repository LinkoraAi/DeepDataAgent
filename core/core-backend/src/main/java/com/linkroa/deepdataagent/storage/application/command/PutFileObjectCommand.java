package com.linkroa.deepdataagent.storage.application.command;

import java.io.InputStream;

/**
 * 上传文件对象命令。
 *
 * @param bucket      目标桶（须已创建，多租户/业务隔离边界）
 * @param objectKey   对象唯一标识（调用方自定义存储路径，必填）
 * @param content     文件内容流
 * @param size        内容字节数（须与流实际长度一致）
 * @param contentType 内容类型
 * @param force       是否显式覆盖已有对象；false 时已存在将触发 409 冲突
 */
public record PutFileObjectCommand(String bucket, String objectKey, InputStream content, long size,
                                   String contentType, boolean force) {
}