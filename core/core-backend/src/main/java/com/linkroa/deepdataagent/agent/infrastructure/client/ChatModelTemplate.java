package com.linkroa.deepdataagent.agent.infrastructure.client;

import com.linkroa.deepdataagent.agent.domain.model.AgentModelInfo;

/**
 * 聊天模型模板
 * <p>标准化模型配置，将数据库中的 AgentModelInfo 映射为统一的配置模板。</p>
 *
 * @param providerName 提供商名称（dashscope / openai / deepseek / custom）
 * @param modelId 模型 ID
 * @param apiUrl API 地址
 * @param apiKey API Key（已解密）
 */
public record ChatModelTemplate(
    String providerName,
    String modelId,
    String apiUrl,
    String apiKey
) {
    /**
     * 从 AgentModelInfo 创建 ChatModelTemplate
     *
     * @param info 模型配置实体
     * @param decryptedApiKey 解密后的 API Key
     * @return ChatModelTemplate 实例
     */
    public static ChatModelTemplate from(AgentModelInfo info, String decryptedApiKey) {
        return new ChatModelTemplate(
            info.getProviderName(),
            info.getModelId(),
            info.getApiUrl(),
            decryptedApiKey
        );
    }
}