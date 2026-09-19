package com.linkroa.deepdataagent.memory.api.dto;

import org.apache.commons.lang3.StringUtils;

/**
 * 记忆库引用契约（发布语言 DTO，Published Language）。
 * <p>由 memory BC 在应用边界出版，作为 {@code MemoryStoreApi} 的返回类型，
 * 供 agent / runtime 等消费方引用记忆库。记忆库无类型概念，仅输出业务 ID 与名称。</p>
 *
 * @param storeId 记忆库业务 ID（前缀 ms_）
 * @param name    记忆库名称
 */
public record MemoryStoreReferenceDTO(
        String storeId,
        String name
) {

    /**
     * 紧凑构造器：契约边界校验
     */
    public MemoryStoreReferenceDTO {
        if (StringUtils.isBlank(storeId)) {
            throw new IllegalArgumentException("记忆库ID不能为空");
        }
        if (StringUtils.isBlank(name)) {
            throw new IllegalArgumentException("记忆库名称不能为空");
        }
    }
}