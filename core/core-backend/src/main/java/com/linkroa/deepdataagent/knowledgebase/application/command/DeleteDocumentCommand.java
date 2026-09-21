package com.linkroa.deepdataagent.knowledgebase.application.command;

/**
 * 删除文档命令（级联清理文档下的全部切片）。
 *
 * @param id 文档ID
 */
public record DeleteDocumentCommand(
        Long id
) {
}
