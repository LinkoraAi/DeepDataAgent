package com.linkroa.deepdataagent.knowledgebase.application.result;

import java.io.InputStream;

/**
 * 文档原文内容的应用层结果（预览 / 下载代理）。
 * <p>内容流来自存储上下文的对象存储，由调用方（REST 层）负责在响应完成后关闭；
 * 应用层不持有流的生命周期。</p>
 *
 * @param fileName      文档文件名（用于回传 Content-Disposition）
 * @param contentType   内容类型，解析失败时为 application/octet-stream
 * @param contentLength 内容字节数
 * @param content       文档原文内容流
 */
public record DocumentContentResult(
        String fileName,
        String contentType,
        long contentLength,
        InputStream content
) {
}
