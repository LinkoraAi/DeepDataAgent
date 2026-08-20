package com.linkroa.deepdataagent.runtime.infrastructure.repository;

import com.linkroa.deepdataagent.runtime.domain.model.AgentSession;
import com.linkroa.deepdataagent.runtime.domain.model.enums.AgentSessionStatus;
import com.linkroa.deepdataagent.runtime.domain.repository.AgentSessionRepository;
import com.linkroa.deepdataagent.runtime.infrastructure.persistence.RuntimePersistenceMapper;
import com.linkroa.deepdataagent.runtime.infrastructure.persistence.entity.AgentSessionEntity;
import com.linkroa.deepdataagent.runtime.infrastructure.persistence.mapper.AgentSessionMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Agent 会话仓储实现（MyBatis-Plus）。
 * <p>基础字段（created_at/updated_at/created_by/updated_by/is_deleted）由
 * {@code MybatisPlusMetaObjectHandler} 自动填充；同一会话同时只有一个执行的互斥由 {@code tryMarkRunning} 原子 CAS 保证。</p>
 */
@Repository
public class JdbcAgentSessionRepository implements AgentSessionRepository {

    @Resource
    private AgentSessionMapper mapper;
    @Resource
    private RuntimePersistenceMapper persistenceMapper;

    @Override
    public AgentSession save(AgentSession session) {
        AgentSessionEntity entity = persistenceMapper.toEntity(session);
        if (entity.getId() == null) {
            entity.setId(null);
            mapper.insert(entity);
        } else {
            mapper.updateById(entity);
        }
        return findBySessionId(session.sessionId()).orElse(session);
    }

    @Override
    public Optional<AgentSession> findBySessionId(String sessionId) {
        return Optional.ofNullable(persistenceMapper.toDomain(mapper.findBySessionId(sessionId)));
    }

    @Override
    public List<AgentSession> findByUserId(String userId, int page, int size) {
        return mapper.findByUserId(userId, page, size)
                .stream()
                .map(persistenceMapper::toDomain)
                .toList();
    }

    @Override
    public long countByUserId(String userId) {
        return mapper.countByUserId(userId);
    }

    @Override
    public boolean tryMarkRunning(String sessionId) {
        // 受影响行数=1 表示 CAS 成功（IDLE → RUNNING），否则会话正忙或已终止
        return mapper.tryMarkRunning(sessionId) == 1;
    }

    @Override
    public int markIdle(String sessionId) {
        return mapper.markIdle(sessionId);
    }

    @Override
    public void updateStatus(String sessionId, AgentSessionStatus status) {
        mapper.updateStatus(sessionId, status.name());
    }

    @Override
    public void updateMeta(String sessionId, String title, String metadata) {
        mapper.updateMeta(sessionId, title, metadata);
    }

    @Override
    public List<String> findRunningSessionIds() {
        return mapper.findRunningSessionIds();
    }

    @Override
    public void touchLastActive(String sessionId) {
        mapper.touchLastActive(sessionId);
    }
}