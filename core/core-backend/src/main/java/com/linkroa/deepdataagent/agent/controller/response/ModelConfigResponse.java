package com.linkroa.deepdataagent.agent.controller.response;

/**
 * 模型配置响应
 */
public record ModelConfigResponse(
    Long id,
    String name,
    String provider,
    String baseUrl,
    String apiKeyMasked,
    String modelName,
    Double temperature,
    Boolean isDefault,
    String description,
    String createdAt,
    String updatedAt
) {
    public static ModelConfigResponse from(
        com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.LlmModelConfigEntity entity
    ) {
        String masked = maskApiKey(entity.getApiKey());
        return new ModelConfigResponse(
            entity.getId(),
            entity.getName(),
            entity.getProvider(),
            entity.getBaseUrl(),
            masked,
            entity.getModelName(),
            entity.getTemperature(),
            entity.getIsDefault() != null && entity.getIsDefault() == 1,
            entity.getDescription(),
            entity.getCreatedAt(),
            entity.getUpdatedAt()
        );
    }

    private static String maskApiKey(String key) {
        if (key == null || key.isBlank()) return "";
        if (key.length() <= 7) return "****";
        return key.substring(0, 3) + "****" + key.substring(key.length() - 4);
    }
}
