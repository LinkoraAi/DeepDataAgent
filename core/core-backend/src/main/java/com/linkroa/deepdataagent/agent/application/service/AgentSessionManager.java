package com.linkroa.deepdataagent.agent.application.service;

import com.linkroa.deepdataagent.agent.infrastructure.config.SessionProperties;
import com.linkroa.deepdataagent.memory.DeepLongMemory;
import com.linkroa.deepdataagent.memory.config.MemoryProperties;
import com.linkroa.deepdataagent.memory.spring.DeepLongMemorySessionFactory;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.memory.LongTermMemoryMode;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.tool.Toolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 会话管理器
 * <p>管理 ReActAgent 实例和 DeepLongMemory 实例的生命周期，按 sessionId 缓存。</p>
 */
@Component
public class AgentSessionManager {

    private static final Logger log = LoggerFactory.getLogger(AgentSessionManager.class);

    private final Map<String, ReActAgent> agentCache = new ConcurrentHashMap<>();
    private final Map<String, DeepLongMemory> memoryCache = new ConcurrentHashMap<>();
    private final DeepLongMemorySessionFactory deepLongMemorySessionFactory;
    private final SessionProperties sessionProperties;

    public AgentSessionManager(DeepLongMemorySessionFactory deepLongMemorySessionFactory,
                               SessionProperties sessionProperties) {
        this.deepLongMemorySessionFactory = deepLongMemorySessionFactory;
        this.sessionProperties = sessionProperties;
    }

    /**
     * 获取活跃会话数量
     *
     * @return 当前活跃的 Agent 会话数量
     */
    public int getActiveSessionCount() {
        return agentCache.size();
    }

    /**
     * 获取或创建 Agent 实例
     * <p>在创建前会检查活跃会话数是否超过上限。</p>
     *
     * @param sessionId        会话 ID
     * @param chatModel        ChatModel 实例
     * @param sysPrompt        系统提示词
     * @param toolkit          工具集
     * @param hooks            Hook 列表
     * @return ReActAgent 实例
     * @throws IllegalStateException 当活跃会话数已达上限时抛出
     */
    public ReActAgent getOrCreateAgent(
            String sessionId,
            ChatModelBase chatModel,
            String sysPrompt,
            Toolkit toolkit,
            List<io.agentscope.core.hook.Hook> hooks
    ) {
        if (agentCache.size() >= sessionProperties.getMaxActiveSessions()) {
            throw new IllegalStateException("活跃会话数已达上限: " + sessionProperties.getMaxActiveSessions());
        }

        return agentCache.computeIfAbsent(sessionId, id -> {
            Memory shortTermMemory = new InMemoryMemory();
            // 新会话需要初始化，首次创建时 skipInitialization=false
            DeepLongMemory longTermMemory = deepLongMemorySessionFactory.create(sessionId, false);
            memoryCache.put(sessionId, longTermMemory);

            ReActAgent agent = ReActAgent.builder()
                    .name("DeepDataAnalyst")
                    .description("数据分析专家 Agent，能够通过工具调用完成 SQL 生成、执行和数据分析")
                    .sysPrompt(sysPrompt)
                    .model(chatModel)
                    .toolkit(toolkit)
                    .memory(shortTermMemory)
                    .longTermMemory(longTermMemory)
                    .longTermMemoryMode(LongTermMemoryMode.STATIC_CONTROL)
                    .maxIters(10)
                    .hooks(hooks)
                    .build();

            log.info("AgentSessionManager: created new agent for session={}", sessionId);
            return agent;
        });
    }

    /**
     * 获取或创建 DeepLongMemory 实例
     * <p>对于已存在的会话使用工厂创建并跳过初始化；首次创建时由工厂负责初始化。</p>
     *
     * @param sessionId 会话 ID
     * @return DeepLongMemory 实例
     */
    public DeepLongMemory getOrCreateMemory(String sessionId) {
        return memoryCache.computeIfAbsent(sessionId, id -> {
            // 首次创建：需要初始化
            DeepLongMemory memory = deepLongMemorySessionFactory.create(sessionId, false);
            log.info("AgentSessionManager: created new memory for session={}", sessionId);
            return memory;
        });
    }

    /**
     * 获取已存在的 Agent 实例
     */
    public ReActAgent getAgent(String sessionId) {
        return agentCache.get(sessionId);
    }

    /**
     * 获取已存在的 Memory 实例
     */
    public DeepLongMemory getMemory(String sessionId) {
        return memoryCache.get(sessionId);
    }

    /**
     * 清除指定会话
     */
    public void evictSession(String sessionId) {
        ReActAgent agent = agentCache.remove(sessionId);
        DeepLongMemory memory = memoryCache.remove(sessionId);
        if (memory != null) {
            memory.close();
        }
        log.info("AgentSessionManager: evicted session={}", sessionId);
    }
}