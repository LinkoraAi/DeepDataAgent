package com.linkroa.deepdataagent.runtime.infrastructure.repository;

import com.linkroa.deepdataagent.runtime.domain.model.SessionThread;
import com.linkroa.deepdataagent.runtime.domain.repository.SessionThreadRepository;
import com.linkroa.deepdataagent.runtime.infrastructure.convert.RuntimePersistenceConvert;
import com.linkroa.deepdataagent.runtime.infrastructure.persistence.entity.SessionThreadEntity;
import com.linkroa.deepdataagent.runtime.infrastructure.persistence.mapper.SessionThreadMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Session 线程仓储实现（MyBatis-Plus）。
 * <p>基础字段（created_at/updated_at/created_by/updated_by/is_deleted）由
 * {@code MybatisPlusMetaObjectHandler} 自动填充；查询面（{@link #findBySession} /
 * {@link #findByThreadId}）服务公开契约的线程端点，归档面不提供（本期无子线程能力）。</p>
 */
@Repository
public class JdbcSessionThreadRepository implements SessionThreadRepository {

    @Resource
    private SessionThreadMapper mapper;

    @Override
    public SessionThread save(SessionThread thread) {
        SessionThreadEntity entity = RuntimePersistenceConvert.INSTANCE.toEntity(thread);
        if (entity.getId() == null) {
            mapper.insert(entity);
        } else {
            mapper.updateById(entity);
        }
        // 直调 mapper.findByThreadId：save 回读不经本仓储接口（避免自调代理）
        return Optional.ofNullable(
                RuntimePersistenceConvert.INSTANCE.toDomain(mapper.findByThreadId(thread.threadId())))
                .orElse(thread);
    }

    @Override
    public Optional<SessionThread> findMain(String sessionId) {
        return Optional.ofNullable(RuntimePersistenceConvert.INSTANCE.toDomain(mapper.findMain(sessionId)));
    }

    @Override
    public List<SessionThread> findBySession(String sessionId) {
        return mapper.findBySession(sessionId).stream()
                .map(RuntimePersistenceConvert.INSTANCE::toDomain)
                .toList();
    }

    @Override
    public Optional<SessionThread> findByThreadId(String sessionId, String threadId) {
        return Optional.ofNullable(RuntimePersistenceConvert.INSTANCE
                .toDomain(mapper.findByThreadIdAndSession(sessionId, threadId)));
    }

    @Override
    public int deleteBySessionId(String sessionId) {
        // @TableLogic 逻辑删除（is_deleted=1）；随会话删除同事务级联
        return mapper.deleteBySessionId(sessionId);
    }
}
