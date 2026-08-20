package com.linkroa.deepdataagent.runtime.domain.model;

import com.linkroa.deepdataagent.runtime.domain.model.enums.SessionState;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 会话运行时聚合对象（进程内「逻辑线程组」，对齐操作系统 M:N 调度现场）。
 * <p>一个会话对应一个聚合实例，跨越查询接口与后续轮次常驻内存，显式聚合四层职责：</p>
 * <ul>
 *   <li><b>身份层</b>：{@link AgentSession}（提供 agentId / userId 等不变身份与 TERMINATED 判定）；</li>
 *   <li><b>执行层</b>：{@link AgentSessionExecution}（虚拟线程阻塞模型下的单执行串行守卫 + 中断入口）、
 *       {@link AgentRunState}（当前轮事件流累积态，每轮经 {@link #beginRound} 替换）、
 *       {@link AtomicLong}（事件序号，跨轮次单调递增，DB max 初始化、内存分配全覆盖）；</li>
 *   <li><b>连接层</b>：{@link ConnectionHandle}（一个会话对应一组连接，多订阅者 fan-out，
 *       领域事件经 {@link #connection()} 推送、协议转换在基础设施；默认
 *       {@link NoOpConnectionHandle}）；</li>
 *   <li><b>状态层</b>：{@link SessionState}（纯内存状态机，一轮执行的流转与结束原因唯一出口守卫，
 *       与会话级持久化 {@code AgentSessionStatus} 正交）。</li>
 * </ul>
 * <p>对同一会话的并发访问由应用服务经数据库状态机 CAS 串行化（同一会话同时只有一个执行），
 * 进程内在跑句柄仅为双保险。</p>
 */
@Slf4j
public final class AgentSessionContext {

    // ==================== 身份层 Identity ====================

    /** 会话身份（不可变，DB 镜像）：提供 agentId / userId 等不变身份 */
    private final AgentSession session;

    // ==================== 执行层 Execution ====================

    /** 执行运行时：单执行串行守卫 + 中断入口 */
    private final AgentSessionExecution execution = new AgentSessionExecution();

    /** 当前轮事件流累积态（每轮经 {@link #beginRound} 替换） */
    private volatile AgentRunState runState;

    /** 事件序号：跨轮次单调递增（DB max 初始化，内存分配全覆盖） */
    private final AtomicLong seqCounter = new AtomicLong(0);

    // ==================== 连接层 Connection ====================

    /** 连接句柄：默认空操作，SSE 场景经 {@link #bindConnection} 绑定/替换 */
    private volatile ConnectionHandle connection;

    // ==================== 状态层 State ====================

    /** 会话执行状态机（内存态，默认 IDLE） */
    private final AtomicReference<SessionState> state = new AtomicReference<>(SessionState.IDLE);

    public AgentSessionContext(AgentSession session) {
        this.session = session;
        this.connection = NoOpConnectionHandle.INSTANCE;
    }

    // ==================== 身份层 ====================

    /**
     * 会话镜像（DB 为权威）。
     *
     * @return 会话领域模型
     */
    public AgentSession session() {
        return session;
    }

    /**
     * 会话 ID（聚合键）。
     *
     * @return 会话 ID
     */
    public String sessionId() {
        return session.sessionId();
    }

    // ==================== 执行层 ====================

    /**
     * 分配下一会话级事件序列号（跨轮次单调递增，DB 唯一索引兜底）。
     *
     * @return 下一事件序列号
     */
    public long nextSequence() {
        return seqCounter.incrementAndGet();
    }

    /**
     * 开启新一轮：将事件序号基准抬升至 DB 最大序号（取 {@code max} 不回退），
     * 并替换当前轮事件流累积态。
     *
     * @param dbMaxSequence 本会话 DB 中当前最大事件序列号（短事务内查询）
     * @return 本轮事件流累积态（调用方持有并贯穿本轮编排）
     */
    public AgentRunState beginRound(long dbMaxSequence) {
        seqCounter.accumulateAndGet(dbMaxSequence, Math::max);
        AgentRunState next = new AgentRunState();
        this.runState = next;
        return next;
    }

    /**
     * 当前轮事件流累积态（仅访问；变更一律经 {@link #beginRound} 替换）。
     *
     * @return 当前轮累积态
     */
    public AgentRunState runState() {
        return runState;
    }

    /**
     * 会话执行运行时（单执行串行守卫 + 中断入口）。
     *
     * @return 执行运行时
     */
    public AgentSessionExecution execution() {
        return execution;
    }

    /**
     * 取消当前活跃执行并标记中断状态（幂等）。
     * <p>先触发已注册的中断句柄（{@code agent.interrupt()}）令事件流自然结束，
     * 再尝试将状态机由 {@link SessionState#RUNNING} 迁移到 {@link SessionState#INTERRUPTED}
     * （仅 RUNNING 态为合法转换；空闲 / 已终态时 {@code tryTransitionState} 静默忽略）。
     * 断连回调与终止会话共用此入口，保证「中断」在状态层有显式落点。</p>
     */
    public void cancel() {
        execution.cancel();
        tryTransitionState(SessionState.INTERRUPTED);
    }

    // ==================== 连接层 ====================

    /**
     * 当前连接句柄（默认 {@link NoOpConnectionHandle}）。
     *
     * @return 连接句柄
     */
    public ConnectionHandle connection() {
        return connection;
    }

    /**
     * 绑定连接句柄（原子替换，替换时释放旧句柄资源）。
     *
     * @param handle 新连接句柄（不可为 null）
     */
    public void bindConnection(ConnectionHandle handle) {
        if (handle == null) {
            throw new IllegalArgumentException("ConnectionHandle 不能为空");
        }
        ConnectionHandle old = this.connection;
        this.connection = handle;
        if (old != null && old != NoOpConnectionHandle.INSTANCE && old != handle) {
            old.close();
        }
    }

    // ==================== 状态层 ====================

    /**
     * 当前会话执行状态。
     *
     * @return 状态机当前状态
     */
    public SessionState state() {
        return state.get();
    }

    /**
     * 状态迁移（CAS 重试 + 合法性校验），非法转换抛 {@link IllegalStateException}。
     *
     * @param target 目标状态
     */
    public void transitionState(SessionState target) {
        while (true) {
            SessionState current = state.get();
            current.validateTransition(target);
            if (state.compareAndSet(current, target)) {
                return;
            }
        }
    }

    /**
     * 尝试状态迁移（CAS 重试）；非法转换返回 {@code false} 而非抛异常。
     *
     * @param target 目标状态
     * @return true=迁移成功；false=非法转换
     */
    public boolean tryTransitionState(SessionState target) {
        while (true) {
            SessionState current = state.get();
            if (!current.canTransitionTo(target)) {
                return false;
            }
            if (state.compareAndSet(current, target)) {
                return true;
            }
        }
    }
}