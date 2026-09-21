package com.linkroa.deepdataagent.knowledgebase.application.command;

/**
 * 删除切片命令。
 *
 * @param id 切片ID
 */
public record DeleteChunkCommand(
        Long id
) {
}
