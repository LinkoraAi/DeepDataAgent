package com.linkroa.deepdataagent.knowledgebase.controller.response;

/**
 * 办公类文档纯文本预览响应 DTO。
 *
 * @param documentId 文档ID
 * @param fileName   文档文件名
 * @param fileType   文件格式（FileType 枚举名）
 * @param text       提取出的纯文本（无有效文本时为空字符串）
 * @param truncated  是否因达到预览长度上限被截断（true 时前端提示下载查看完整内容）
 */
public record DocumentPreviewTextResponse(
        Long documentId,
        String fileName,
        String fileType,
        String text,
        boolean truncated
) {
}
