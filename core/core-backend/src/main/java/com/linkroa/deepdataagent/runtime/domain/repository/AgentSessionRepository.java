package com.linkroa.deepdataagent.runtime.domain.repository;

import com.linkroa.deepdataagent.runtime.domain.model.AgentSession;
import com.linkroa.deepdataagent.runtime.domain.model.SessionCursor;
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
     * 按用户 + 过滤条件游标分页查询（按 {@code (created_at, id)} 升序）。
     *
     * @param userId   用户 ID
     * @param agentId  Agent ID（可空，不过滤）
     * @param statuses 状态集合（空表示不过滤）
     * @param cursor   游标断点（null 表示第一页）
     * @param limit    本次最多返回条数（含用于判断是否还有下一页的多取一条）
     * @return 会话列表（按游标升序）
     */
    List<AgentSession> findByFilters(String userId, String agentId,
                                     List<AgentSessionStatus> statuses, SessionCursor cursor, int limit);

    /**
     * 抢占执行权的原子 CAS：仅当会话处于 IDLE 时置为 RUNNING（保证同一会话同时只有一个执行；TERMINATED 不可复活）。
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
     * 更新会话标题与元数据（对齐 Managed Agents 更新会话，仅改 title/metadata）。
     *
     * @param sessionId 会话 ID
     * @param title     会话标题（可空）
     * @param metadata  扩展元数据（JSON 文本）
     */
    void updateMeta(String sessionId, String title, String metadata);

    /**
     * 查询当前 RUNNING 的会话（启动恢复用）。
     */
    List<String> findRunningSessionIds();

    /**
     * 刷新 last_active_at。
     */
    void touchLastActive(String sessionId);
}