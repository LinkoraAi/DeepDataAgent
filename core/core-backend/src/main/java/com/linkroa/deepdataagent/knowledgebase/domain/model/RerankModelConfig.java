package com.linkroa.deepdataagent.knowledgebase.domain.model;

/**
 * 重排模型配置值对象。
 *
 * @param enabled        是否启用重排
 * @param modelProfileId 重排模型 profileId（引用 agent BC 模型注册表）
 * @param topK           重排保留的 top K 数量
 */
public record RerankModelConfig(
        Boolean enabled,
        String modelProfileId,
        Integer topK
) {

    /**
     * 判断重排是否启用。
     */
    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }
}
