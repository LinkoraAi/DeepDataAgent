package com.linkroa.deepdataagent.runtime.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linkroa.deepdataagent.runtime.infrastructure.persistence.entity.SessionThreadEntity;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * Session 线程 Mapper（session_thread）。
 * <p>此处以 {@code @SuppressWarnings("null")} 压制 JDT 空指针静态分析对 MyBatis-Plus
 * {@code SFunction} 方法引用（{@code Entity::getXxx}）的误报：该写法是 MP lambda 包装器
 * 解析列名的标准形式，运行时与 null 语义无关。</p>
 * <p>方法面：{@link #findByThreadId}（save 回读）、{@link #findMain}（事件线程归属锚点）、
 * {@link #findBySession}（线程列表，主线程首位）、
 * {@link #findByThreadIdAndSession}（限定会话的线程定位）、{@link #deleteBySessionId}（会话删除级联）。</p>
 */
@SuppressWarnings("null")
@Mapper
public interface SessionThreadMapper extends BaseMapper<SessionThreadEntity> {

    default SessionThreadEntity findByThreadId(String threadId) {
        return selectOne(Wrappers.<SessionThreadEntity>lambdaQuery()
                .eq(SessionThreadEntity::getThreadId, threadId)
                .last("LIMIT 1"));
    }

    /**
     * 查询会话主线程（{@code parent_thread_id IS NULL}，@TableLogic 自动补 is_deleted=0）。
     */
    default SessionThreadEntity findMain(String sessionId) {
        return selectOne(Wrappers.<SessionThreadEntity>lambdaQuery()
                .eq(SessionThreadEntity::getSessionId, sessionId)
                .isNull(SessionThreadEntity::getParentThreadId)
                .last("LIMIT 1"));
    }

    /**
     * 逻辑删除会话全部线程（@TableLogic 下实为 is_deleted=1）。
     */
    default int deleteBySessionId(String sessionId) {
        return delete(Wrappers.<SessionThreadEntity>lambdaQuery()
                .eq(SessionThreadEntity::getSessionId, sessionId));
    }

    /**
     * 查询会话全部线程：主线程（{@code parent_thread_id IS NULL}）排首位，其余按创建时间升序。
     * <p>主线程首位由排序表达式承载（{@code parent_thread_id IS NULL} 在 PG 中 TRUE 优先于 FALSE
     * 升序靠前），保证公开契约「主线程 MUST 排在首位」不依赖数据库自然顺序。</p>
     */
    default List<SessionThreadEntity> findBySession(String sessionId) {
        return selectList(Wrappers.<SessionThreadEntity>lambdaQuery()
                .eq(SessionThreadEntity::getSessionId, sessionId)
                .last("ORDER BY (parent_thread_id IS NULL) DESC, created_at ASC, id ASC"));
    }

    /**
     * 按线程业务 ID 查询线程（限定所属会话，防跨会话越权定位）。
     */
    default SessionThreadEntity findByThreadIdAndSession(String sessionId, String threadId) {
        return selectOne(Wrappers.<SessionThreadEntity>lambdaQuery()
                .eq(SessionThreadEntity::getSessionId, sessionId)
                .eq(SessionThreadEntity::getThreadId, threadId)
                .last("LIMIT 1"));
    }
}
