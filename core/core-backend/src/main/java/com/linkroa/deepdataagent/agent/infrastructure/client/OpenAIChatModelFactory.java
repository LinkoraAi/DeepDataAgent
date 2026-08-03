package com.linkroa.deepdataagent.agent.infrastructure.client;

import io.agentscope.core.model.GenerateOptions;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * OpenAI 兼容协议 ChatModel 工厂
 * <p>适用于所有兼容 OpenAI API 协议的提供商，包括 deepseek、openai、anthropic、custom 等。
 * 支持自定义 API 地址，并自动规范化 baseUrl。</p>
 */
@Component
public class OpenAIChatModelFactory implements ChatModelFactory {

    private static final GenerateOptions DEFAULT_OPTIONS = GenerateOptions.builder()
            .temperature(0.1)
            .build();

    /** 支持的提供商名称列表 */
    private static final String[] SUPPORTED_PROVIDERS = {
            "openai", "deepseek", "anthropic", "custom"
    };

    @Override
    public String getProviderName() {
        return "openai";
    }

    /**
     * 判断当前工厂是否支持指定提供商
     *
     * @param providerName 提供商名称（小写）
     * @return 是否支持
     */
    public boolean supports(String providerName) {
        for (String p : SUPPORTED_PROVIDERS) {
            if (p.equals(providerName)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public OpenAIChatModel create(ChatModelTemplate template) {
        var builder = OpenAIChatModel.builder()
                .apiKey(template.apiKey())
                .modelName(template.modelId())
                .generateOptions(DEFAULT_OPTIONS);

        if (StringUtils.hasText(template.apiUrl())) {
            builder.baseUrl(normalizeBaseUrl(template.apiUrl()));
        }

        return builder.build();
    }

    /**
     * 规范化 baseUrl：移除末尾的 /v1/chat/completions 路径
     * <p>OpenAI SDK 会自动在 baseUrl 后追加 /v1/chat/completions，
     * 如果用户配置的 baseUrl 已包含该路径，会导致 URL 重复。</p>
     *
     * @param baseUrl 原始 baseUrl
     * @return 规范化后的 baseUrl
     */
    private String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isEmpty()) {
            return baseUrl;
        }
        return baseUrl.replaceAll("/v1/chat/completions/?$", "");
    }
}