package com.linkroa.deepdataagent.runtime.domain.repository;

import com.linkroa.deepdataagent.runtime.domain.model.SessionThread;

import java.util.List;
import java.util.Optional;

/**
 * Session 线程仓储接口（依赖倒置，领域语义方法声明）。
 * <p>数据底座与查询面：创建会话随行落主线程（{@link #save}）、事件线程归属锚点
 * （{@link #findMain}）、线程查询面（{@link #findBySession} / {@link #findByThreadId}，
 * 公开契约 {@code GET /sessions/{id}/threads} 与线程作用域事件端点）、
 * 会话删除级联（{@link #deleteBySessionId}）。</p>
 * <p>归档面不提供：线程归档端点本期不实现（multiagent 子线程能力出界），
 * 主线程 {@code archived_at} 恒为 null。</p>
 */
public interface SessionThreadRepository {

    /**
     * 保存线程（新增；线程创建后无可变属性，不提供整行更新）。
     */
    SessionThread save(SessionThread thread);

    /**
     * 查询会话主线程（{@code parent_thread_id IS NULL} 的唯一线程）。
     * <p>事件线程归属装配的锚点：会话创建即随行落主线程，理论上必然存在；
     * 缺失（历史脏数据）时返回空，由调用方降级为无归属事件。</p>
     */
    Optional<SessionThread> findMain(String sessionId);

    /**
     * 查询会话全部线程（主线程排首位，其余按创建时间升序）。
     * <p>公开契约 {@code GET /sessions/{id}/threads}：主线程 MUST 排在首位。</p>
     *
     * @param sessionId 会话 ID
     * @return 线程列表（主线程首位；无线程返回空列表）
     */
    List<SessionThread> findBySession(String sessionId);

    /**
     * 按线程业务 ID 查询线程（限定所属会话，防跨会话越权定位）。
     *
     * @param sessionId 会话 ID
     * @param threadId  线程业务 ID（{@code sthr_} 前缀）
     * @return 线程（empty = 该会话无此线程）
     */
    Optional<SessionThread> findByThreadId(String sessionId, String threadId);

    /**
     * 删除会话的全部线程（6.3 delete 面：会话删除同事务级联逻辑删）。
     *
     * @return 受影响行数
     */
    int deleteBySessionId(String sessionId);
}
