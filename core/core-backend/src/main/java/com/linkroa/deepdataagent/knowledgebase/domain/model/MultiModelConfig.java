package com.linkroa.deepdataagent.knowledgebase.domain.model;

/**
 * 多模态模型配置值对象。
 *
 * @param modelProfileId 多模态模型 profileId（引用 agent BC 模型注册表）
 */
public record MultiModelConfig(
        String modelProfileId
) {

    public MultiModelConfig {
        if (modelProfileId == null || modelProfileId.isBlank()) {
            throw new IllegalArgumentException("多模态模型ID不能为空");
        }
    }
}
