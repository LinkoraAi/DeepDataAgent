package com.linkroa.deepdataagent.knowledgebase.application.command;

/**
 * 删除知识库命令（级联清理库内文档与切片）。
 *
 * @param id 知识库ID
 */
public record DeleteKnowledgeBaseCommand(
        Long id
) {
}
