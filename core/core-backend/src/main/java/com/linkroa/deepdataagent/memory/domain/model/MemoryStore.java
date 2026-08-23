package com.linkroa.deepdataagent.memory.domain.model;

import com.linkroa.deepdataagent.memory.domain.model.enums.MemoryType;
import org.apache.commons.lang3.StringUtils;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.regex.Pattern;

/**
 * 记忆库领域模型（对应 memory_store 表）
 *
 * @param id          数据库主键
 * @param memoryId    记忆库业务唯一ID
 * @param name        名称（≤64字符，唯一）
 * @param type        记忆类型（短期 / 长期）
 * @param workspaceId 工作空间归属（本期占位，默认值兜底，不做边界校验）
 * @param createdAt   创建时间
 * @param updatedAt   更新时间
 * @param createdBy   创建人
 * @param updatedBy   更新人
 */
public record MemoryStore(
        Long id,
        String memoryId,
        String name,
        MemoryType type,
        String workspaceId,
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
        if (StringUtils.isBlank(memoryId)) {
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
        if (type == null) {
            throw new IllegalArgumentException("记忆类型不能为空");
        }
    }

    /**
     * 创建新的记忆库
     */
    public static MemoryStore create(String memoryId, String name, MemoryType type, String workspaceId) {
        return new MemoryStore(
                null, memoryId, name, type, workspaceId,
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")),
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")),
                null, null
        );
    }

    /**
     * 从数据库恢复（查询场景）
     */
    public static MemoryStore restore(
            Long id,
            String memoryId,
            String name,
            MemoryType type,
            String workspaceId,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            String createdBy,
            String updatedBy
    ) {
        return new MemoryStore(id, memoryId, name, type, workspaceId, createdAt, updatedAt, createdBy, updatedBy);
    }
}