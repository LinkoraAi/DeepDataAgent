package com.linkroa.deepdataagent.agent.domain.repository;

import com.linkroa.deepdataagent.agent.domain.model.AgentSession;

import java.util.List;
import java.util.Optional;

/**
 * 会话仓储接口
 * <p>定义在 domain 层，实现在 infrastructure 层。</p>
 */
public interface AgentSessionRepository {

    Optional<AgentSession> findById(String id);

    List<AgentSession> findByUserId(Long userId);

    List<AgentSession> findActiveSessions();

    List<AgentSession> findActiveSessionsPaged(int limit, int offset);

    AgentSession save(AgentSession session);

    void updateTitle(String id, String title);

    /**
     * 软删除会话
     * <p>将会话状态置为 DELETED 并标记逻辑删除，与全局 is_deleted 逻辑删除约定保持一致。</p>
     *
     * @param sessionId 会话 ID
     */
    void softDelete(String sessionId);

    void touchLastMessage(String id);

    int countActiveSessions();
}
