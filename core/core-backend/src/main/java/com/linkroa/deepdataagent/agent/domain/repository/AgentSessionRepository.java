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

    void updateStatus(String id, com.linkroa.deepdataagent.agent.domain.valueobject.SessionStatus status);

    void updateTitle(String id, String title);

    void touchLastMessage(String id);

    int countActiveSessions();
}
