package com.linkroa.deepdataagent.runtime.domain.repository;

import com.linkroa.deepdataagent.runtime.domain.model.AgentSession;
import com.linkroa.deepdataagent.runtime.domain.model.enums.AgentSessionStatus;

import java.util.List;
import java.util.Optional;

/**
 * Agent 会话仓储接口（依赖倒置，领域语义方法声明）。
 */
public interface AgentSessionRepository {

    /**
     * 保存会话（新增或更新）。
     */
    AgentSession save(AgentSession session);

    /**
     * 按业务会话 ID 查询。
     */
    Optional<AgentSession> findBySessionId(String sessionId);

    /**
     * 按用户 ID 分页查询（按创建时间升序）。
     *
     * @param userId 用户 ID
     * @param page   页码（从 1 开始）
     * @param size   每页大小
     * @return 会话列表
     */
    List<AgentSession> findByUserId(String userId, int page, int size);

    /**
     * 按用户 ID 统计会话数。
     */
    long countByUserId(String userId);

    /**
     * 单飞原子 CAS：仅当会话处于 IDLE 时置为 RUNNING（TERMINATED 不可复活）。
     *
     * @param sessionId 会话 ID
     * @return true 表示 CAS 成功（本次可执行），false 表示会话正忙或已终止
     */
    boolean tryMarkRunning(String sessionId);

    /**
     * 幂等回 IDLE：仅当状态为 RUNNING 时恢复（execution 终态守卫）。
     *
     * @param sessionId 会话 ID
     * @return 受影响行数（1=已回 IDLE；0=会话非 RUNNING，如已被终止）
     */
    int markIdle(String sessionId);

    /**
     * 更新会话状态（终止等）。
     */
    void updateStatus(String sessionId, AgentSessionStatus status);

    /**
     * 查询当前 RUNNING 的会话（启动恢复用）。
     */
    List<String> findRunningSessionIds();

    /**
     * 刷新 last_active_at。
     */
    void touchLastActive(String sessionId);
}