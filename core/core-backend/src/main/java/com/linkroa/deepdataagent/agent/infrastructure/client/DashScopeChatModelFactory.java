package com.linkroa.deepdataagent.agent.infrastructure.client;

import io.agentscope.core.model.GenerateOptions;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import org.springframework.stereotype.Component;

/**
 * DashScope（通义千问）ChatModel 工厂
 * <p>使用 {@link DashScopeChatModel} 构建 DashScope 模型实例。</p>
 */
@Component
public class DashScopeChatModelFactory implements ChatModelFactory {

    private static final GenerateOptions DEFAULT_OPTIONS = GenerateOptions.builder()
            .temperature(0.1)
            .build();

    @Override
    public String getProviderName() {
        return "dashscope";
    }

    @Override
    public DashScopeChatModel create(ChatModelTemplate template) {
        return DashScopeChatModel.builder()
                .apiKey(template.apiKey())
                .modelName(template.modelId())
                .defaultOptions(DEFAULT_OPTIONS)
                .build();
    }
}