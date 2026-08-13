package com.linkroa.deepdataagent.agent.infrastructure.client;

import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ChatModel 工厂注册器
 * <p>收集所有 {@link ChatModelFactory} 实现，根据 providerName 路由到对应的工厂实例。
 * 新增 LLM 提供商时，只需新增一个 ChatModelFactory 实现，无需修改现有代码。</p>
 */
@Component
public class ChatModelFactoryRegistry {

    private static final Logger log = LoggerFactory.getLogger(ChatModelFactoryRegistry.class);

    private final Map<String, ChatModelFactory> factoryMap = new ConcurrentHashMap<>();

    public ChatModelFactoryRegistry(List<ChatModelFactory> factories) {
        for (ChatModelFactory factory : factories) {
            factoryMap.put(factory.getProviderName().toLowerCase(), factory);
        }
        log.info("ChatModelFactoryRegistry: registered {} ChatModel factories", factories.size());
    }

    /**
     * 根据提供商名称获取对应的工厂实例
     *
     * @param providerName 提供商名称（不区分大小写）
     * @return ChatModelFactory 实例
     * @throws DeepDataAgentException 如果未找到匹配的工厂
     */
    public ChatModelFactory getFactory(String providerName) {
        String key = providerName.toLowerCase();
        ChatModelFactory factory = factoryMap.get(key);
        if (factory != null) {
            return factory;
        }

        // 兜底：遍历所有工厂，检查是否通过 supports() 支持该提供商
        for (ChatModelFactory f : factoryMap.values()) {
            if (f instanceof OpenAIChatModelFactory openAIFactory && openAIFactory.supports(key)) {
                return openAIFactory;
            }
        }

        throw new DeepDataAgentException("不支持的 LLM 提供商: " + providerName);
    }

    /**
     * 获取已注册的工厂数量
     *
     * @return 工厂数量
     */
    public int size() {
        return factoryMap.size();
    }
}