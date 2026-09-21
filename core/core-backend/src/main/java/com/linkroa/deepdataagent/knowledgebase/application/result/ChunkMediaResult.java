package com.linkroa.deepdataagent.knowledgebase.application.result;

import java.io.InputStream;

/**
 * 切片媒体图片内容的应用层结果（在线预览代理）。
 * <p>内容流来自存储访问端口打开的对象，由调用方（REST 层）负责在响应完成后关闭；
 * 应用层不持有流的生命周期。</p>
 *
 * @param contentType   内容类型（按对象键扩展推断，不可识别时为 application/octet-stream）
 * @param contentLength 内容字节数（列举精确命中所得）
 * @param content       图片内容流
 */
public record ChunkMediaResult(
        String contentType,
        long contentLength,
        InputStream content
) {
}
