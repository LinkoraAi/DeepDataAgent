package com.linkroa.deepdataagent.memory.application.command;

import com.linkroa.deepdataagent.memory.domain.model.MemoryMetadata;

/**
 * 更新记忆条目内容命令（OCC 乐观并发：必须携带当前版本号，不符返回 409）。
 * <p>{@code path} 更新时不可变，故不在命令中；元数据传 null 表示沿用现有。</p>
 *
 * @param storeId          记忆库业务 ID
 * @param memoryId         记忆业务 ID（mem_ 前缀）
 * @param content          新内容（UTF-8 ≤100KB）
 * @param expectedVersion  客户端持有的当前版本号（OCC 校验）
 * @param metadata         新元数据（null=沿用现有）
 */
public record UpdateMemoryEntryCommand(
        String storeId,
        String memoryId,
        String content,
        int expectedVersion,
        MemoryMetadata metadata
) {
}
