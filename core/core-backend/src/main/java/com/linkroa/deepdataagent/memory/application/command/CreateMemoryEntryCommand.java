package com.linkroa.deepdataagent.memory.application.command;

import com.linkroa.deepdataagent.memory.domain.model.MemoryMetadata;

/**
 * 创建记忆条目命令（记忆库内以相对 path 寻址首写）。
 *
 * @param storeId  记忆库业务 ID
 * @param path     记忆路径（相对，不以 / 开头，库内活跃唯一）
 * @param content  文本内容（UTF-8 ≤100KB）
 * @param metadata 自定义元数据（可空，≤16 对）
 */
public record CreateMemoryEntryCommand(
        String storeId,
        String path,
        String content,
        MemoryMetadata metadata
) {
}
