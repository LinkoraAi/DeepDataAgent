package com.linkroa.deepdataagent.runtime.infrastructure.repository;

import com.linkroa.deepdataagent.runtime.domain.model.ChatEvent;
import com.linkroa.deepdataagent.runtime.domain.repository.ChatEventRepository;
import com.linkroa.deepdataagent.runtime.infrastructure.persistence.RuntimePersistenceMapper;
import com.linkroa.deepdataagent.runtime.infrastructure.persistence.entity.ChatEventEntity;
import com.linkroa.deepdataagent.runtime.infrastructure.persistence.mapper.ChatEventMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 聊天事件仓储实现（MyBatis-Plus）。
 * <p>序列号基于会话内最大序列号分配；调用方须在单飞所限定的短事务内完成
 * {@code nextSequenceNum + save}，保证会话内严格递增。</p>
 */
@Repository
public class JdbcChatEventRepository implements ChatEventRepository {

    @Resource
    private ChatEventMapper mapper;
    @Resource
    private RuntimePersistenceMapper persistenceMapper;

    @Override
    public ChatEvent save(ChatEvent event) {
        ChatEventEntity entity = persistenceMapper.toEntity(event);
        // 基础字段（created_at/updated_at/created_by/updated_by/is_deleted）由 MybatisPlusMetaObjectHandler 自动填充
        entity.setId(null);
        mapper.insert(entity);
        return event;
    }

    @Override
    public long nextSequenceNum(String sessionId) {
        return mapper.maxSequenceNum(sessionId) + 1L;
    }

    @Override
    public List<ChatEvent> findBySessionAfter(String sessionId, long afterSequenceNum) {
        return mapper.findBySessionAfter(sessionId, afterSequenceNum)
                .stream()
                .map(persistenceMapper::toDomain)
                .toList();
    }

    @Override
    public List<ChatEvent> findByRound(String roundId) {
        return mapper.findByRound(roundId)
                .stream()
                .map(persistenceMapper::toDomain)
                .toList();
    }
}