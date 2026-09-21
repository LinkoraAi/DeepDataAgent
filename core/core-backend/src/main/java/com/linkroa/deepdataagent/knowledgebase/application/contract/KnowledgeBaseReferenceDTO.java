package com.linkroa.deepdataagent.knowledgebase.application.contract;

import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

/**
 * 知识库引用契约（发布语言 DTO，Published Language）。
 * <p>由 knowledgebase BC 在应用边界出版，作为 {@code KnowledgeBaseApi} 的返回类型，
 * 供 rag / agent 等消费方引用知识库。对外只暴露标识与名称等最小信息集，
 * 生命周期状态以字符串名输出，不泄露本 BC 领域枚举与配置类值对象。</p>
 *
 * @param kbId            知识库主键
 * @param name            知识库名称
 * @param lifecycleStatus 生命周期状态（格式化字符串名：ACTIVE / DELETING / DELETE_FAILED）
 */
public record KnowledgeBaseReferenceDTO(
        Long kbId,
        String name,
        String lifecycleStatus
) {

    /**
     * 紧凑构造器：契约边界校验。
     */
    public KnowledgeBaseReferenceDTO {
        if (ObjectUtils.isEmpty(kbId)) {
            throw new IllegalArgumentException("知识库ID不能为空");
        }
        if (StringUtils.isBlank(name)) {
            throw new IllegalArgumentException("知识库名称不能为空");
        }
        if (StringUtils.isBlank(lifecycleStatus)) {
            throw new IllegalArgumentException("知识库生命周期状态不能为空");
        }
    }
}
