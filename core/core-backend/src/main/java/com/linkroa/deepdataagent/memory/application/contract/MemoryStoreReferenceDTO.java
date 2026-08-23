package com.linkroa.deepdataagent.memory.application.contract;

import org.apache.commons.lang3.StringUtils;

/**
 * 记忆库引用契约（发布语言 DTO，Published Language）。
 * <p>由 memory BC 在应用边界出版，作为 {@code MemoryStoreApi} 的返回类型，
 * 供 agent / runtime 等消费方引用记忆库。对外输出已格式化值（记忆类型以字符串名暴露），
 * 不泄露本 BC 领域枚举 / 值对象。</p>
 *
 * @param memoryStoreId 记忆库业务 ID
 * @param name          记忆库名称
 * @param type          记忆类型（格式化字符串名：SHORT_TERM / LONG_TERM）
 * @param workspaceId   工作空间归属（本期占位，可空）
 */
public record MemoryStoreReferenceDTO(
        String memoryStoreId,
        String name,
        String type,
        String workspaceId
) {

    /**
     * 紧凑构造器：契约边界校验
     */
    public MemoryStoreReferenceDTO {
        if (StringUtils.isBlank(memoryStoreId)) {
            throw new IllegalArgumentException("记忆库ID不能为空");
        }
        if (StringUtils.isBlank(name)) {
            throw new IllegalArgumentException("记忆库名称不能为空");
        }
        if (StringUtils.isBlank(type)) {
            throw new IllegalArgumentException("记忆类型不能为空");
        }
    }
}