package com.linkroa.deepdataagent.agent.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.linkroa.deepdataagent.agent.domain.model.AgentSession;
import com.linkroa.deepdataagent.agent.domain.repository.AgentSessionRepository;
import com.linkroa.deepdataagent.agent.domain.valueobject.SessionStatus;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.AgentSessionEntity;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.mapper.AgentSessionMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 会话仓储实现
 * <p>基于 MyBatis-Plus 实现 AgentSession 领域模型的持久化。</p>
 */
@Repository
public class AgentSessionRepositoryImpl implements AgentSessionRepository {

    private final AgentSessionMapper mapper;

    public AgentSessionRepositoryImpl(AgentSessionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<AgentSession> findById(String sessionId) {
        AgentSessionEntity entity = mapper.selectById(sessionId);
        if (entity == null) {
            return Optional.empty();
        }
        return Optional.of(toDomain(entity));
    }

    @Override
    public List<AgentSession> findByUserId(Long userId) {
        LambdaQueryWrapper<AgentSessionEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AgentSessionEntity::getUserId, userId)
                .eq(AgentSessionEntity::getIsDeleted, 0)
                .orderByDesc(AgentSessionEntity::getUpdatedTime);
        return mapper.selectList(wrapper).stream()
                .map(this::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public List<AgentSession> findActiveSessions() {
        LambdaQueryWrapper<AgentSessionEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AgentSessionEntity::getStatus, SessionStatus.ACTIVE.name())
                .eq(AgentSessionEntity::getIsDeleted, 0)
                .orderByDesc(AgentSessionEntity::getLastMessageTime);
        return mapper.selectList(wrapper).stream()
                .map(this::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public List<AgentSession> findActiveSessionsPaged(int limit, int offset) {
        LambdaQueryWrapper<AgentSessionEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AgentSessionEntity::getStatus, SessionStatus.ACTIVE.name())
                .eq(AgentSessionEntity::getIsDeleted, 0)
                .orderByDesc(AgentSessionEntity::getLastMessageTime)
                .last("LIMIT " + limit + " OFFSET " + offset);
        return mapper.selectList(wrapper).stream()
                .map(this::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public AgentSession save(AgentSession session) {
        AgentSessionEntity entity = toEntity(session);
        mapper.insert(entity);
        return session;
    }

    @Override
    public void updateStatus(String sessionId, SessionStatus status) {
        LambdaUpdateWrapper<AgentSessionEntity> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(AgentSessionEntity::getId, sessionId)
                .set(AgentSessionEntity::getStatus, status.name())
                .set(AgentSessionEntity::getUpdatedTime, LocalDateTime.now());
        mapper.update(null, wrapper);
    }

    @Override
    public void updateTitle(String sessionId, String title) {
        mapper.updateTitle(sessionId, title);
    }

    @Override
    public void touchLastMessage(String sessionId) {
        LocalDateTime now = LocalDateTime.now();
        LambdaUpdateWrapper<AgentSessionEntity> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(AgentSessionEntity::getId, sessionId)
                .set(AgentSessionEntity::getLastMessageTime, now)
                .set(AgentSessionEntity::getUpdatedTime, now);
        mapper.update(null, wrapper);
    }

    @Override
    public int countActiveSessions() {
        Integer count = mapper.countActiveSessions();
        return count != null ? count : 0;
    }

    /**
     * 实体转领域模型
     *
     * @param entity 数据库实体
     * @return 领域模型
     */
    private AgentSession toDomain(AgentSessionEntity entity) {
        AgentSession session = new AgentSession();
        session.setId(entity.getId());
        session.setTitle(entity.getTitle());
        session.setUserId(entity.getUserId());
        session.setDatasourceId(entity.getDatasourceId());
        session.setModelConfigId(entity.getModelConfigId());
        session.setStatus(SessionStatus.valueOf(entity.getStatus()));
        session.setLastMessageTime(entity.getLastMessageTime());
        session.setCreatedTime(entity.getCreatedTime());
        session.setUpdatedTime(entity.getUpdatedTime());
        session.setDeleted(entity.getIsDeleted());
        return session;
    }

    /**
     * 领域模型转实体
     *
     * @param session 领域模型
     * @return 数据库实体
     */
    private AgentSessionEntity toEntity(AgentSession session) {
        AgentSessionEntity entity = new AgentSessionEntity();
        entity.setId(session.getId());
        entity.setTitle(session.getTitle());
        entity.setUserId(session.getUserId());
        entity.setDatasourceId(session.getDatasourceId());
        entity.setModelConfigId(session.getModelConfigId());
        entity.setStatus(session.getStatus().name());
        entity.setLastMessageTime(session.getLastMessageTime());
        entity.setCreatedTime(session.getCreatedTime());
        entity.setUpdatedTime(session.getUpdatedTime());
        entity.setIsDeleted(session.getDeleted() != null ? session.getDeleted() : 0);
        return entity;
    }
}
