package com.linkroa.deepdataagent.runtime.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linkroa.deepdataagent.runtime.infrastructure.persistence.entity.ChatEventEntity;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.OptionalLong;

/**
 * 聊天事件 Mapper。
 * <p>此处以 {@code @SuppressWarnings("null")} 压制 JDT 空指针静态分析对 MyBatis-Plus
 * {@code SFunction} 方法引用（{@code Entity::getXxx}）的误报：该写法是 MP lambda 包装器
 * 解析列名的标准形式（改写为普通 lambda 会导致列名解析失败），运行时与 null 语义无关。</p>
 */
@SuppressWarnings("null")
@Mapper
public interface ChatEventMapper extends BaseMapper<ChatEventEntity> {

    /**
     * 会话内当前最大序列号（无事件返回 0）。调用方须在同一事务内完成分配与插入。
     */
    default long maxSeq(String sessionId) {
        List<ChatEventEntity> rows = selectList(Wrappers.<ChatEventEntity>lambdaQuery()
                .select(ChatEventEntity::getSeq)
                .eq(ChatEventEntity::getSessionId, sessionId)
                .orderByDesc(ChatEventEntity::getSeq)
                .last("LIMIT 1"));
        if (rows.isEmpty()) {
            return 0L;
        }
        Long value = rows.get(0).getSeq();
        return value == null ? 0L : value;
    }

    /**
     * 回放：查询序列号大于 afterSeq 的事件（升序 + 可选限量，游标分页用）。
     *
     * @param sessionId   会话 ID
     * @param afterSeq    回放起点（seq 大于该值）
     * @param types       事件类型过滤（null / 空表示不过滤；可重复传参）
     * @param limit       单页上限（null 表示不限量，SSE 全量回放用）
     * @return 事件列表（按 seq 升序，至多 limit 条）
     */
    default List<ChatEventEntity> findBySessionAfter(String sessionId, long afterSeq, List<String> types, Integer limit) {
        var wrapper = Wrappers.<ChatEventEntity>lambdaQuery()
                .eq(ChatEventEntity::getSessionId, sessionId)
                .gt(ChatEventEntity::getSeq, afterSeq);
        if (types != null && !types.isEmpty()) {
            wrapper.in(ChatEventEntity::getType, types);
        }
        if (limit != null && limit > 0) {
            wrapper.last("LIMIT " + limit);
        }
        return selectList(wrapper.orderByAsc(ChatEventEntity::getSeq));
    }

    /**
     * 按（会话 ID, 事件 ID）查询已落库序列号（SSE Last-Event-ID 断点游标换算；
     * event_id 会话内唯一，未命中返回 {@link OptionalLong#empty()}）。
     */
    default OptionalLong findSeqByEventId(String sessionId, String eventId) {
        List<ChatEventEntity> rows = selectList(Wrappers.<ChatEventEntity>lambdaQuery()
                .select(ChatEventEntity::getSeq)
                .eq(ChatEventEntity::getSessionId, sessionId)
                .eq(ChatEventEntity::getEventId, eventId)
                .last("LIMIT 1"));
        if (rows.isEmpty()) {
            return OptionalLong.empty();
        }
        Long value = rows.get(0).getSeq();
        return value == null ? OptionalLong.empty() : OptionalLong.of(value);
    }

    /**
     * 会话内指定类型的最新一条事件（seq 降序取一，durable HITL 挂起明细定位用）。
     */
    default List<ChatEventEntity> findLatestByType(String sessionId, String type) {
        return selectList(Wrappers.<ChatEventEntity>lambdaQuery()
                .eq(ChatEventEntity::getSessionId, sessionId)
                .eq(ChatEventEntity::getType, type)
                .orderByDesc(ChatEventEntity::getSeq)
                .last("LIMIT 1"));
    }

    /**
     * 会话内按公开事件 id 集合批查指定类型事件（durable 续跑整批明细重建，seq 升序）。
     *
     * @param types 事件类型集合（内置工具调用 {@code agent.tool_use} 与 MCP 工具调用
     *              {@code agent.mcp_tool_use} 同形承载待确认现场，故按集合匹配）
     */
    default List<ChatEventEntity> findByTypeAndEventIds(String sessionId, java.util.Collection<String> types,
                                                        java.util.Collection<String> eventIds) {
        if (eventIds == null || eventIds.isEmpty() || types == null || types.isEmpty()) {
            return List.of();
        }
        return selectList(Wrappers.<ChatEventEntity>lambdaQuery()
                .eq(ChatEventEntity::getSessionId, sessionId)
                .in(ChatEventEntity::getType, types)
                .in(ChatEventEntity::getEventId, eventIds)
                .orderByAsc(ChatEventEntity::getSeq));
    }

    /**
     * 会话内指定类型集合的全部事件（seq 升序）。
     * <p>durable HITL 等待现场定位用：工具调用与工具结果两类各自取齐后由应用层配对。</p>
     *
     * @param types 事件类型集合（空集合返回空列表）
     */
    default List<ChatEventEntity> findBySessionTypes(String sessionId, java.util.Collection<String> types) {
        if (types == null || types.isEmpty()) {
            return List.of();
        }
        return selectList(Wrappers.<ChatEventEntity>lambdaQuery()
                .eq(ChatEventEntity::getSessionId, sessionId)
                .in(ChatEventEntity::getType, types)
                .orderByAsc(ChatEventEntity::getSeq));
    }
}