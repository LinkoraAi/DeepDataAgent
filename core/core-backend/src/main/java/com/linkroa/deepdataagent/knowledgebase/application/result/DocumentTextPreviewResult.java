package com.linkroa.deepdataagent.knowledgebase.application.result;

import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.FileType;

/**
 * 文档纯文本预览的应用层结果（办公类文档在线预览）。
 *
 * @param documentId 文档ID
 * @param fileName   文档文件名
 * @param fileType   文档登记的文件格式
 * @param text       提取出的纯文本（无有效文本时为空字符串，永不为 {@code null}）
 * @param truncated  是否因达到预览长度上限被截断（true 时前端提示下载查看完整内容）
 */
public record DocumentTextPreviewResult(
        Long documentId,
        String fileName,
        FileType fileType,
        String text,
        boolean truncated
) {
}
