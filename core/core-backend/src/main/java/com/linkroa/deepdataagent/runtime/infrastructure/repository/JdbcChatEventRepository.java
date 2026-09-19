package com.linkroa.deepdataagent.runtime.infrastructure.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linkroa.deepdataagent.runtime.domain.model.ChatEvent;
import com.linkroa.deepdataagent.runtime.domain.model.ChatEventQuery;
import com.linkroa.deepdataagent.runtime.domain.model.enums.ChatEventType;
import com.linkroa.deepdataagent.runtime.domain.repository.ChatEventRepository;
import com.linkroa.deepdataagent.runtime.infrastructure.convert.RuntimePersistenceConvert;
import com.linkroa.deepdataagent.runtime.infrastructure.persistence.entity.ChatEventEntity;
import com.linkroa.deepdataagent.runtime.infrastructure.persistence.mapper.ChatEventMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * 聊天事件仓储实现（MyBatis-Plus）。
 * <p>seq 基于会话内最大序列号分配；调用方须在同一会话同时只有一个执行所限定的短事务内完成
 * {@code nextSequenceNum + save}，保证会话内严格递增（DB 唯一索引 {@code (session_id, seq)} 兜底幂等）。</p>
 */
@Repository
public class JdbcChatEventRepository implements ChatEventRepository {

    @Resource
    private ChatEventMapper mapper;

    @Override
    public ChatEvent save(ChatEvent event) {
        ChatEventEntity entity = RuntimePersistenceConvert.INSTANCE.toEntity(event);
        // 基础字段（created_at/updated_at/created_by/updated_by/is_deleted）由 MybatisPlusMetaObjectHandler 自动填充
        entity.setId(null);
        mapper.insert(entity);
        return event;
    }

    @Override
    public long nextSequenceNum(String sessionId) {
        return mapper.maxSeq(sessionId) + 1L;
    }

    @Override
    public List<ChatEvent> findBySessionAfter(String sessionId, long afterSeq, List<String> types, Integer limit) {
        return mapper.findBySessionAfter(sessionId, afterSeq, types, limit)
                .stream()
                .map(RuntimePersistenceConvert.INSTANCE::toDomain)
                .toList();
    }

    @Override
    public OptionalLong findSeqByEventId(String sessionId, String eventId) {
        return mapper.findSeqByEventId(sessionId, eventId);
    }

    @Override
    public int deleteBySessionId(String sessionId) {
        // @TableLogic 逻辑删除（is_deleted=1），回放查询自动不可见
        return mapper.delete(com.baomidou.mybatisplus.core.toolkit.Wrappers.<ChatEventEntity>lambdaQuery()
                .eq(ChatEventEntity::getSessionId, sessionId));
    }

    @Override
    public List<ChatEvent> findByTypes(String sessionId, List<String> types) {
        return mapper.findBySessionTypes(sessionId, types)
                .stream()
                .map(RuntimePersistenceConvert.INSTANCE::toDomain)
                .toList();
    }

    @Override
    public List<ChatEvent> findToolUsesByEventIds(String sessionId, List<String> eventIds) {
        return mapper.findByTypeAndEventIds(sessionId, ChatEventType.TOOL_USE_TYPES, eventIds)
                .stream()
                .map(RuntimePersistenceConvert.INSTANCE::toDomain)
                .toList();
    }

    /**
     * 事件列表游标分页与过滤查询。
     * <p>条件装配收敛于本实现：{@code ChatEventMapper} 的既有查询方法面（按 seq 升序回放 /
     * 类型集合 / 事件 ID 定位）不足以表达 {@code order} 降序、{@code before_id} 反向游标与
     * {@code created_at} 区间，故在此以 MyBatis-Plus {@code LambdaQueryWrapper} 组合（列引用一律
     * {@code Entity::getX} 方法引用）。</p>
     */
    @SuppressWarnings("null")
    @Override
    public List<ChatEvent> findPage(ChatEventQuery query) {
        LambdaQueryWrapper<ChatEventEntity> wrapper = Wrappers.<ChatEventEntity>lambdaQuery()
                .eq(ChatEventEntity::getSessionId, query.sessionId());
        if (query.sessionThreadId() != null) {
            wrapper.eq(ChatEventEntity::getSessionThreadId, query.sessionThreadId());
        }
        if (query.afterSeq() != null) {
            wrapper.gt(ChatEventEntity::getSeq, query.afterSeq());
        }
        if (query.beforeSeq() != null) {
            wrapper.lt(ChatEventEntity::getSeq, query.beforeSeq());
        }
        if (!query.types().isEmpty()) {
            wrapper.in(ChatEventEntity::getType, query.types());
        }
        if (query.createdAtGt() != null) {
            wrapper.gt(ChatEventEntity::getCreatedAt, query.createdAtGt());
        }
        if (query.createdAtGte() != null) {
            wrapper.ge(ChatEventEntity::getCreatedAt, query.createdAtGte());
        }
        if (query.createdAtLt() != null) {
            wrapper.lt(ChatEventEntity::getCreatedAt, query.createdAtLt());
        }
        if (query.createdAtLte() != null) {
            wrapper.le(ChatEventEntity::getCreatedAt, query.createdAtLte());
        }
        wrapper.orderBy(true, query.ascending(), ChatEventEntity::getSeq);
        wrapper.last("LIMIT " + query.limit());
        return mapper.selectList(wrapper).stream()
                .map(RuntimePersistenceConvert.INSTANCE::toDomain)
                .toList();
    }

    @Override
    public Optional<ChatEvent> findByEventId(String sessionId, String eventId) {
        return mapper.selectList(Wrappers.<ChatEventEntity>lambdaQuery()
                        .eq(ChatEventEntity::getSessionId, sessionId)
                        .eq(ChatEventEntity::getEventId, eventId)
                        .last("LIMIT 1"))
                .stream()
                .findFirst()
                .map(RuntimePersistenceConvert.INSTANCE::toDomain);
    }
}