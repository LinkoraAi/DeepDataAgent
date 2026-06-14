package com.linkroa.deepdataagent.agent.controller.response;

/**
 * 模型模板响应
 */
public record ModelTemplateResponse(
    Long id,
    String provider,
    String modelName,
    String displayName,
    String baseUrl,
    String description,
    Integer sortOrder,
    Boolean isEnabled
) {
    public static ModelTemplateResponse from(
        com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.LlmModelTemplateEntity entity
    ) {
        return new ModelTemplateResponse(
            entity.getId(),
            entity.getProvider(),
            entity.getModelName(),
            entity.getDisplayName(),
            entity.getBaseUrl(),
            entity.getDescription(),
            entity.getSortOrder(),
            entity.getIsEnabled() != null && entity.getIsEnabled() == 1
        );
    }
}
