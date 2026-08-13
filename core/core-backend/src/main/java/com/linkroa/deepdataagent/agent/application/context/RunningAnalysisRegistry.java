package com.linkroa.deepdataagent.agent.application.context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 运行中分析注册表
 * <p>记录当前正在执行的数据分析会话，{@code sessionId -> RunningExecution}。
 * 与 SSE 连接映射（{@code SSEConnectionPool#sessionClientIdMap}）解耦：后者在客户端断开即被清空，
 * 而运行中状态必须跨断连存活，直到 agent 真正结束、出错或取消。</p>
 *
 * <p>线程安全：使用 {@link ConcurrentHashMap}，所有方法均为线程安全。</p>
 */
@Component
public class RunningAnalysisRegistry {

    private static final Logger log = LoggerFactory.getLogger(RunningAnalysisRegistry.class);

    /** sessionId -> 运行中分析执行句柄 */
    private final ConcurrentHashMap<String, RunningExecution> registry = new ConcurrentHashMap<>();

    /**
     * 注册一个运行中分析
     *
     * @param sessionId 会话 ID
     * @param execution 运行中分析执行句柄
     */
    public void register(String sessionId, RunningExecution execution) {
        if (sessionId == null || execution == null) {
            return;
        }
        registry.put(sessionId, execution);
    }

    /**
     * 获取运行中分析执行句柄
     *
     * @param sessionId 会话 ID
     * @return 执行句柄；未运行时返回 null
     */
    public RunningExecution get(String sessionId) {
        return registry.get(sessionId);
    }

    /**
     * 移除运行中分析
     *
     * @param sessionId 会话 ID
     */
    public void remove(String sessionId) {
        if (sessionId != null) {
            registry.remove(sessionId);
        }
    }

    /**
     * 判断会话是否正在分析中
     *
     * @param sessionId 会话 ID
     * @return true 表示正在分析
     */
    public boolean isRunning(String sessionId) {
        return sessionId != null && registry.containsKey(sessionId);
    }

    /**
     * 获取所有运行中会话 ID
     *
     * @return 运行中会话 ID 集合
     */
    public Set<String> getRunningSessionIds() {
        return Set.copyOf(registry.keySet());
    }

    /**
     * 获取运行中分析的会话数量
     *
     * @return 数量
     */
    public int size() {
        return registry.size();
    }
}