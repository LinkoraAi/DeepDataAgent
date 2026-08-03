package com.linkroa.deepdataagent.agent.controller.response;

/**
 * 模型配置响应 DTO
 * <p>与前端 {@code ModelConfig} 类型完全对齐，字段名和类型一一对应。</p>
 *
 * @param id            配置 ID
 * @param providerKey   服务商标识（如 "dashscope", "openai", "custom"）
 * @param providerName  服务商显示名称
 * @param modelKey      模型标识（如 "qwen-plus", "gpt-4o"）
 * @param baseUrl       API 请求地址
 * @param apiKeyMasked  API Key（脱敏或原始）
 * @param apiFormat     API 格式（默认 "openai"）
 * @param isDefault     是否为默认模型
 * @param createdAt     创建时间（格式 "yyyy-MM-dd HH:mm:ss"）
 * @param updatedAt     更新时间（格式 "yyyy-MM-dd HH:mm:ss"）
 */
public record ModelConfigResponse(
    Long id,
    String providerKey,
    String providerName,
    String modelKey,
    String baseUrl,
    String apiKeyMasked,
    String apiFormat,
    Boolean isDefault,
    String createdAt,
    String updatedAt
) {
}