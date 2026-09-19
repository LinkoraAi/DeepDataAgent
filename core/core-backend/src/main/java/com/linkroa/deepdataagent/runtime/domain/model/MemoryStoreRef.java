package com.linkroa.deepdataagent.runtime.domain.model;

import org.apache.commons.lang3.StringUtils;

/**
 * 记忆库引用值对象（运行时领域模型，框架无关注）。
 * <p>由 {@code AgentAssemblyConvert}（ACL）从 memory BC 的发布语言
 * {@code MemoryStoreReferenceDTO} 映射而来，供水工厂装配记忆检索工具时使用。
 * 记忆库无「短期 / 长期」类型概念，仅输出业务 ID 与名称。</p>
 *
 * @param storeId 记忆库业务 ID（前缀 ms_）
 * @param name    记忆库名称
 */
public record MemoryStoreRef(
        String storeId,
        String name
) {

    public MemoryStoreRef {
        if (StringUtils.isBlank(storeId)) {
            throw new IllegalArgumentException("记忆库ID不能为空");
        }
        if (StringUtils.isBlank(name)) {
            throw new IllegalArgumentException("记忆库名称不能为空");
        }
    }
}