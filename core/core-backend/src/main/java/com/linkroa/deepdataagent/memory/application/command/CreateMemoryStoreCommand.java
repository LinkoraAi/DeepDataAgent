package com.linkroa.deepdataagent.memory.application.command;

/**
 * 创建记忆库命令
 *
 * @param name        名称
 * @param description 描述（可空）
 */
public record CreateMemoryStoreCommand(
        String name,
        String description
) {
}