package com.linkroa.deepdataagent.runtime.domain.model;

import com.linkroa.deepdataagent.runtime.domain.model.runstate.TurnRunState;
import com.linkroa.deepdataagent.runtime.domain.port.ConnectionHandle;
import com.linkroa.deepdataagent.runtime.domain.port.NoOpConnectionHandle;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 会话运行时聚合对象（进程内「逻辑线程组」，对齐操作系统 M:N 调度现场）。
 * <p>一个会话对应一个聚合实例，跨越查询接口与后续轮次常驻内存，显式聚合四层职责：</p>
 * <ul>
 *   <li><b>身份层</b>：{@link AgentSession}（提供 agentId / userId 等不变身份与可运行态判定，DB 为权威）；</li>
 *   <li><b>执行层</b>：{@link TurnControl}（进程内「阻塞等待 + 中断传递」载体，每轮替换的执行控制面，
 *       由原执行槽类拍平而来——互斥权威在 DB，不在此处）、
 *       {@link TurnRunState}（当前 turn 事件流累积态，每轮经 {@link #beginRound} 替换）、
 *       {@link AtomicLong}（事件 seq，跨 turn 单调递增，DB max 初始化、内存分配全覆盖）；</li>
 *   <li><b>连接层</b>：{@link ConnectionHandle}（一个会话对应一组连接，多订阅者 fan-out，
 *       领域事件经 {@link #connection()} 推送、协议转换在基础设施；默认
 *       {@link NoOpConnectionHandle}）；</li>
 *   <li><b>状态层</b>：双列状态机由 DB 承载——对外 {@code status} 四态
 *       （idle / running / rescheduling / terminated）与内部 {@code turn_phase} 四态
 *       （idle / running / awaiting_confirmation / cancelling，经 {@code active()} 判定「存在活跃执行」，
 *       相位 MUST NOT 出现在任何响应或状态事件中），本聚合不保留
 *       内存状态机副本——HITL 挂起为 durable 事实（事件表批次明细 + 会话状态），
 *       轮内守卫经 {@link TurnRunState#confirmationPending()} 判定，进程内
 *       无跨轮驻留现场。</li>
 * </ul>
 * <p>对同一会话的并发访问由应用服务经数据库状态机 CAS 串行化（跨进程 / 跨实例互斥权威在 DB），
 * 进程内 {@link TurnControl} 仅承载阻塞与中断传递、不做执行拒止（对「清槽前」毫秒竞态窗口采 fail-open
 * 显式取舍）。轮次级瞬态状态机（SessionState / RoundStatus / HitlState）已随事件溯源重构移除。</p>
 */
@Slf4j
public final class AgentSessionContext {

    // ==================== 身份层 Identity ====================

    /** 会话身份（不可变，DB 镜像）：提供 agentId / userId 等不变身份 */
    private final AgentSession session;

    // ==================== 执行层 Execution ====================

    /** 当前轮执行控制面（null=空闲；启动事务成功后经 {@link #beginTurn} 置入，轮终局 finally 经 {@link #endTurn} 条件清除） */
    private volatile TurnControl currentTurn;

    /** 当前 turn 事件流累积态（每轮经 {@link #beginRound} 替换） */
    private volatile TurnRunState runState;

    /** 事件 seq：跨 turn 单调递增（DB max 初始化，内存分配全覆盖） */
    private final AtomicLong seqCounter = new AtomicLong(0);

    // ==================== 连接层 Connection ====================

    /** 连接句柄：默认空操作，SSE 场景经 {@link #bindConnection} 绑定/替换 */
    private volatile ConnectionHandle connection;

    /**
     * 以会话身份创建进程内运行时聚合：执行槽位为空闲、连接句柄初始为 {@link NoOpConnectionHandle}。
     *
     * @param session 会话身份（DB 为权威，不可为 null）
     */
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
     * 分配下一会话级事件序列号（跨 turn 单调递增，DB 唯一索引兜底）。
     *
     * @return 下一事件序列号
     */
    public long nextSequence() {
        return seqCounter.incrementAndGet();
    }

    /**
     * 当前已分配的事件序列号（不消耗 seq）：流式增量帧起始游标基准——
     * 进行中流的 {@code baseSeq} 即「start 前最后一条已落库事件」的 seq。
     *
     * @return 当前序列号（未分配任何事件时为 0）
     */
    public long currentSequence() {
        return seqCounter.get();
    }

    /**
     * 开启新一轮执行：将事件 seq 基准抬升至 DB 最大序号（取 {@code max} 不回退），
     * 并替换当前 turn 事件流累积态。
     *
     * @param dbMaxSequence 本会话 DB 中当前最大事件 seq（短事务内查询）
     * @return 本轮事件流累积态（调用方持有并贯穿本轮编排）
     */
    public TurnRunState beginRound(long dbMaxSequence) {
        seqCounter.accumulateAndGet(dbMaxSequence, Math::max);
        TurnRunState next = new TurnRunState();
        this.runState = next;
        return next;
    }

    /**
     * 当前 turn 事件流累积态（仅访问；变更一律经 {@link #beginRound} 替换）。
     *
     * @return 当前累积态
     */
    public TurnRunState runState() {
        return runState;
    }

    /**
     * 开启本轮控制面：置入 {@code currentTurn}。
     * <p>置位时发现槽位非空<b>不拒止</b>（跨进程互斥权威在 DB {@code BEGIN_TURN} CAS），仅记结构化
     * ERROR 告警后照常执行——撞「上轮 finally 清槽前」毫秒窗双跑，由 Redis turn 租约 owner-scoped Lua、
     * 事件 {@code (session_id, seq)} 全序与终态 CAS 共同保证事件表不错乱、终态不双写。</p>
     *
     * @param turn 本轮执行控制面（不可为 null）
     */
    public void beginTurn(TurnControl turn) {
        if (turn == null) {
            throw new IllegalArgumentException("TurnControl 不能为空");
        }
        if (this.currentTurn != null) {
            log.error("执行槽非空仍开轮（进程内视图 fail-open，互斥权威在 DB CAS）: sessionId={}", sessionId());
        }
        this.currentTurn = turn;
    }

    /**
     * 清除本轮控制面：仅当槽位仍指向本轮对象时置空，防毫秒窗内误清新一轮控制面
     * （新轮已 {@link #beginTurn} 覆盖时旧轮 finally 不得摘除新轮）。
     *
     * @param turn 本轮执行控制面（启动时同一对象）
     */
    public void endTurn(TurnControl turn) {
        if (this.currentTurn == turn) {
            this.currentTurn = null;
        }
    }

    /**
     * 当前轮执行控制面（只读访问；null=空闲）。变更一律经 {@link #beginTurn} / {@link #endTurn}。
     *
     * @return 当前控制面
     */
    public TurnControl currentTurn() {
        return currentTurn;
    }

    /**
     * 取消当前活跃轮（幂等）：触发本轮已登记的定向中断句柄（{@code agent.interrupt(userId, sessionId)}，
     * 槽位键与执行下发运行时的会话身份同源）令事件流自然结束。断连回调、终止与 {@code user.interrupt}
     * 共用此入口；会话级状态回 idle / terminated 由应用服务按 DB 状态机编排。
     * <p>空闲（{@code currentTurn} 为 null）时为空操作。</p>
     */
    public void cancel() {
        TurnControl turn = currentTurn;
        if (turn != null) {
            turn.cancel();
        }
    }

    /**
     * 中断当前活跃轮：先标记当前 turn 为「显式中断」（终态路径据此收场为
     * {@code stop_reason.type=interrupted} 并回 idle，而非 cancelled 终态），再触发本轮控制面 cancel。
     * <p>{@code user.interrupt} 入口；与 {@link #cancel()} 的区别在于中断语义
     * 会被事件流感知，并经终态收场事件集表达为
     * {@code session.thread_status_idle} → {@code session.status_idle}（共享 {@code stop_reason}）；
     * 已废止的 {@code session.interrupted} / {@code session.status_canceling} MUST NOT 产出。
     * 两段语义（{@link TurnRunState#markInterrupted()} 终态表达 / {@link TurnControl#cancel()} 句柄触发）
     * 刻意保持分离，不得合并。</p>
     */
    public void interruptCurrentRun() {
        TurnRunState state = runState;
        if (state != null) {
            state.markInterrupted();
        }
        TurnControl turn = currentTurn;
        if (turn != null) {
            turn.cancel();
        }
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
}