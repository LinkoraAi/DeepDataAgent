package com.linkroa.deepdataagent.agent.infrastructure.client;

import io.agentscope.core.model.ChatModelBase;

/**
 * ChatModel 工厂接口
 * <p>每种 LLM 提供商实现该接口，用于创建对应的 ChatModel 实例。
 * 通过 {@link ChatModelFactoryRegistry} 统一注册和管理。</p>
 */
public interface ChatModelFactory {

    /**
     * 获取当前工厂支持的提供商名称（小写）
     * <p>用于匹配 {@link com.linkroa.deepdataagent.agent.domain.model.ModelConfig#getProviderName()}。</p>
     *
     * @return 提供商名称，如 "dashscope"、"openai"
     */
    String getProviderName();

    /**
     * 根据模板创建 ChatModel 实例
     *
     * @param template 标准化的模型配置模板
     * @return ChatModel 实例
     */
    ChatModelBase create(ChatModelTemplate template);
}