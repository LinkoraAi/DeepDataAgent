package com.linkroa.deepdataagent.agent.infrastructure.client;

import com.linkroa.deepdataagent.agent.domain.repository.AgentModelInfoRepository;
import io.agentscope.core.model.ChatModelBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型注册表
 * <p>管理 ChatModel 实例的缓存和复用，当模型配置更新时主动清除缓存。</p>
 */
@Component
public class ModelRegistry {

    private static final Logger log = LoggerFactory.getLogger(ModelRegistry.class);

    private final LLMClient llmClient;
    private final AgentModelInfoRepository modelInfoRepository;
    private final Map<Long, ChatModelBase> cache = new ConcurrentHashMap<>();

    public ModelRegistry(LLMClient llmClient, AgentModelInfoRepository modelInfoRepository) {
        this.llmClient = llmClient;
        this.modelInfoRepository = modelInfoRepository;
    }

    /**
     * 获取或创建 ChatModel 实例
     *
     * @param modelConfigId 模型配置 ID
     * @return 缓存的 ChatModel 实例
     */
    public ChatModelBase getOrCreate(Long modelConfigId) {
        return cache.computeIfAbsent(modelConfigId, id -> {
            log.debug("ModelRegistry: creating ChatModel for configId={}", id);
            return llmClient.getChatModel(id);
        });
    }

    /**
     * 清除指定模型的缓存
     *
     * @param modelConfigId 模型配置 ID
     */
    public void evict(Long modelConfigId) {
        ChatModelBase removed = cache.remove(modelConfigId);
        if (removed != null) {
            log.debug("ModelRegistry: evicted cache for configId={}", modelConfigId);
        }
        // 同步清除 LLMClient 中的缓存
        llmClient.evictCache(modelConfigId);
    }

    /**
     * 清除所有模型缓存
     */
    public void evictAll() {
        int size = cache.size();
        cache.clear();
        log.debug("ModelRegistry: evicted all caches ({} entries)", size);
    }

    /**
     * 获取缓存大小
     */
    public int cacheSize() {
        return cache.size();
    }
}