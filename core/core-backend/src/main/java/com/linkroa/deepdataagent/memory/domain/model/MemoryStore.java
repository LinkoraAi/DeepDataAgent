package com.linkroa.deepdataagent.memory.domain.model;

import com.linkroa.deepdataagent.memory.domain.model.enums.MemoryStoreStatus;
import org.apache.commons.lang3.StringUtils;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.regex.Pattern;

/**
 * 记忆库领域模型（Store 聚合根，对应 memory_stores 表）。
 * <p>Memory Store 语义：带 {@code status}（active/archived）与
 * 统计列 {@code entryCount} / {@code totalSize}（随条目内容变更由应用层维护）；
 * 归档后 memory 与 version 仍可读，写操作拒绝（409）。</p>
 *
 * @param id          数据库主键
 * @param storeId     记忆库业务唯一ID（前缀 ms_）
 * @param name        名称（≤64字符）
 * @param description 描述（null 归一为空串，≤500字符）
 * @param status      状态（active/archived）
 * @param entryCount  活跃记忆条目数
 * @param totalSize   活跃记忆内容总字节数
 * @param ownerId     归属用户 ID（数字）
 * @param archivedAt  归档时间（null=未归档）
 * @param createdAt   创建时间
 * @param updatedAt   更新时间
 * @param createdBy   创建人
 * @param updatedBy   更新人
 */
public record MemoryStore(
        Long id,
        String storeId,
        String name,
        String description,
        MemoryStoreStatus status,
        int entryCount,
        long totalSize,
        Long ownerId,
        OffsetDateTime archivedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        String createdBy,
        String updatedBy
) {

    private static final Pattern NAME_PATTERN = Pattern.compile("^[\\p{IsHan}a-zA-Z][\\p{IsHan}a-zA-Z0-9_\\-]{0,63}$");

    /**
     * 紧凑构造器：不变量校验
     */
    public MemoryStore {
        if (StringUtils.isBlank(storeId)) {
            throw new IllegalArgumentException("记忆库ID不能为空");
        }
        if (StringUtils.isBlank(name)) {
            throw new IllegalArgumentException("记忆库名称不能为空");
        }
        if (name.length() > 64) {
            throw new IllegalArgumentException("记忆库名称长度不能超过64个字符");
        }
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException("记忆库名称只能包含中文、英文字母、数字、下划线和连字符，且不能以数字或特殊字符开头");
        }
        description = description == null ? "" : description;
        if (description.length() > 500) {
            throw new IllegalArgumentException("记忆库描述不能超过500个字符");
        }
        if (status == null) {
            throw new IllegalArgumentException("记忆库状态不能为空");
        }
        if (entryCount < 0) {
            throw new IllegalArgumentException("记忆条目数不能为负");
        }
        if (totalSize < 0) {
            throw new IllegalArgumentException("记忆内容总字节数不能为负");
        }
        if (ownerId == null) {
            throw new IllegalArgumentException("记忆库归属用户不能为空");
        }
    }

    /**
     * 创建新的记忆库（初始 active，统计列归零）。
     */
    public static MemoryStore create(String storeId, String name, String description, Long ownerId) {
        OffsetDateTime now = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
        return new MemoryStore(null, storeId, name, description, MemoryStoreStatus.ACTIVE,
                0, 0L, ownerId, null, now, now, null, null);
    }

    /**
     * 归档（返回置 status=archived 与归档时间的副本）。
     */
    public MemoryStore archive() {
        return new MemoryStore(id, storeId, name, description, MemoryStoreStatus.ARCHIVED,
                entryCount, totalSize, ownerId,
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")),
                createdAt, updatedAt, createdBy, updatedBy);
    }

    /**
     * 是否已归档。
     */
    public boolean archived() {
        return status == MemoryStoreStatus.ARCHIVED;
    }
}
