package com.linkroa.deepdataagent.runtime.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linkroa.deepdataagent.runtime.infrastructure.persistence.entity.ChatEventEntity;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

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
    default long maxSequenceNum(String sessionId) {
        List<ChatEventEntity> rows = selectList(Wrappers.<ChatEventEntity>lambdaQuery()
                .select(ChatEventEntity::getSequenceNum)
                .eq(ChatEventEntity::getSessionId, sessionId)
                .orderByDesc(ChatEventEntity::getSequenceNum)
                .last("LIMIT 1"));
        if (rows.isEmpty()) {
            return 0L;
        }
        Long value = rows.get(0).getSequenceNum();
        return value == null ? 0L : value;
    }

    /**
     * 回放：查询序列号大于 afterSequenceNum 的事件（升序）。
     */
    default List<ChatEventEntity> findBySessionAfter(String sessionId, long afterSequenceNum) {
        return selectList(Wrappers.<ChatEventEntity>lambdaQuery()
                .eq(ChatEventEntity::getSessionId, sessionId)
                .gt(ChatEventEntity::getSequenceNum, afterSequenceNum)
                .orderByAsc(ChatEventEntity::getSequenceNum));
    }

    /**
     * 单轮事件查询（升序）。
     */
    default List<ChatEventEntity> findByRound(String roundId) {
        return selectList(Wrappers.<ChatEventEntity>lambdaQuery()
                .eq(ChatEventEntity::getRoundId, roundId)
                .orderByAsc(ChatEventEntity::getSequenceNum));
    }
}