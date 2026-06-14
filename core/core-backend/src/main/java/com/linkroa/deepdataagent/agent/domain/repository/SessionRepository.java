package com.linkroa.deepdataagent.agent.domain.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.AgentSessionEntity;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.ConversationMsgEntity;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.mapper.AgentSessionMapper;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.mapper.ConversationMsgMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * 会话仓储层
 * <p>封装对 agent_session 和 conversation_msg 表的数据访问，提供业务语义方法。</p>
 */
@Repository
public class SessionRepository {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AgentSessionMapper sessionMapper;
    private final ConversationMsgMapper msgMapper;

    public SessionRepository(AgentSessionMapper sessionMapper, ConversationMsgMapper msgMapper) {
        this.sessionMapper = sessionMapper;
        this.msgMapper = msgMapper;
    }

    // ==================== AgentSession 操作 ====================

    public void save(AgentSessionEntity entity) {
        sessionMapper.insert(entity);
    }

    public Optional<AgentSessionEntity> findById(String sessionId) {
        AgentSessionEntity entity = sessionMapper.selectById(sessionId);
        return Optional.ofNullable(entity);
    }

    public List<AgentSessionEntity> findActiveSessions() {
        return sessionMapper.selectActiveSessions();
    }

    public int incrementMessageCount(String sessionId) {
        String now = LocalDateTime.now().format(DATE_TIME_FORMATTER);
        return sessionMapper.incrementMessageCount(sessionId, now);
    }

    public int closeSession(String sessionId) {
        return sessionMapper.closeSession(sessionId);
    }

    public int updateTitle(String sessionId, String title) {
        return sessionMapper.updateTitle(sessionId, title);
    }

    public int countActiveSessions() {
        return sessionMapper.countActiveSessions();
    }

    // ==================== ConversationMsg 操作 ====================

    public void saveMessage(ConversationMsgEntity entity) {
        msgMapper.insert(entity);
    }

    public List<ConversationMsgEntity> findMessagesBySessionId(String sessionId, int limit, int offset) {
        return msgMapper.selectBySessionIdPaged(sessionId, limit, offset);
    }

    public List<ConversationMsgEntity> findRecentMessages(String sessionId, int limit) {
        return msgMapper.selectRecentBySessionId(sessionId, limit);
    }

    public int countMessagesBySessionId(String sessionId) {
        return msgMapper.countBySessionId(sessionId);
    }
}
