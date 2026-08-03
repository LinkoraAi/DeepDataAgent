package com.linkroa.deepdataagent.agent.application.context;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Session 工具上下文
 * <p>存储会话级别的数据，供 Agent 工具在调用时访问。
 * 由于 AgentScope 框架的 RuntimeContext 可能无法正确传播到工具层，
 * 使用此组件作为替代方案，按 sessionId 存储 modelConfigId 等上下文信息。</p>
 *
 * <p>使用方法：在发起分析前调用 {@link #register(String, Long)} 注册，
 * 分析完成后调用 {@link #unregister(String)} 清理。</p>
 */
@Component
public class SessionToolContext {

    /** sessionId -> modelConfigId 映射 */
    private final Map<String, Long> modelConfigRegistry = new ConcurrentHashMap<>();

    /**
     * 注册会话的 modelConfigId
     *
     * @param sessionId 会话 ID
     * @param modelConfigId 模型配置 ID
     */
    public void register(String sessionId, Long modelConfigId) {
        if (sessionId != null && modelConfigId != null) {
            modelConfigRegistry.put(sessionId, modelConfigId);
        }
    }

    /**
     * 获取会话的 modelConfigId
     *
     * @param sessionId 会话 ID
     * @return 模型配置 ID，如果未注册则返回 null
     */
    public Long getModelConfigId(String sessionId) {
        return modelConfigRegistry.get(sessionId);
    }

    /**
     * 注销会话的上下文
     *
     * @param sessionId 会话 ID
     */
    public void unregister(String sessionId) {
        if (sessionId != null) {
            modelConfigRegistry.remove(sessionId);
        }
    }
}