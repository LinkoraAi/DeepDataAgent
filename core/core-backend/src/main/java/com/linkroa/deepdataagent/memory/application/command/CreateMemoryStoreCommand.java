package com.linkroa.deepdataagent.memory.application.command;

import com.linkroa.deepdataagent.memory.domain.model.enums.MemoryType;

/**
 * 创建记忆库命令
 *
 * @param name 名称
 * @param type 记忆类型（短期 / 长期）
 */
public record CreateMemoryStoreCommand(
        String name,
        MemoryType type
) {
}