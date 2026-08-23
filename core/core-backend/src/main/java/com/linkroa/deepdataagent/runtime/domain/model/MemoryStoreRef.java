package com.linkroa.deepdataagent.runtime.domain.model;

import org.apache.commons.lang3.StringUtils;

/**
 * 记忆库引用值对象（运行时领域模型，框架无关注）。
 * <p>由 {@code AgentAssemblyConvert}（ACL）从 memory BC 的发布语言
 * {@code MemoryStoreReferenceDTO} 映射而来，供水工厂装配记忆检索工具时使用。
 * 记忆类型以已格式化字符串名暴露（SHORT_TERM / LONG_TERM），不引入 runtime 自身枚举。</p>
 *
 * @param memoryStoreId 记忆库业务 ID
 * @param name          记忆库名称
 * @param type          记忆类型（格式化字符串名：SHORT_TERM / LONG_TERM）
 */
public record MemoryStoreRef(
        String memoryStoreId,
        String name,
        String type
) {

    public MemoryStoreRef {
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