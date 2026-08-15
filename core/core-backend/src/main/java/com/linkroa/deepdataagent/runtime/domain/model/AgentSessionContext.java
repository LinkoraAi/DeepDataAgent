package com.linkroa.deepdataagent.runtime.domain.model;

import com.linkroa.deepdataagent.runtime.domain.factory.BuiltAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 会话运行时聚合对象（进程内「逻辑线程组」）。
 * <p>一个会话对应一个聚合实例，跨越查询接口与后续轮次常驻内存，聚合四层职责：</p>
 * <ul>
 *   <li><b>身份层</b>：{@link AgentSession} 会话镜像（DB 为权威，镜像提供 agentId / userId 等
 *       不变身份信息与 TERMINATED 判定）；</li>
 *   <li><b>事件序号层</b>：跨轮次单调递增的 {@code seqCounter}——每轮经
 *       {@link #beginRound} 与 DB 最大序号取 {@code max} 抬升基准且永不回退，
 *       保证同会话内事件序列号跨轮唯一递增，消除每轮从 DB 重查的往返；</li>
 *   <li><b>执行层</b>：当前轮事件流状态 {@link AgentRunState}（每轮经 {@link #beginRound} 替换）；
 *       在跑执行句柄 {@link ActiveRun}（串行守卫 + 断连/终止中断入口）；</li>
 *   <li><b>连接层</b>：SSE 订阅组由 {@code infrastructure.sse.SseEmitterRegistry} 独立维护，
 *       通过「最后订阅者消失 → {@link #interruptActiveRun()}」与本聚合协作。
 * </ul>
 * <p>本类为进程内运行时对象，零 Spring / 基础设施依赖，状态以 DB + 事件流为权威；
 * 对同一会话的并发访问由应用服务的单飞 CAS（数据库状态机）串行化。</p>
 */
public final class AgentSessionContext {

    private static final Logger log = LoggerFactory.getLogger(AgentSessionContext.class);

    /** 身份层：会话镜像（DB 权威） */
    private final AgentSession session;
    /** 事件序号层：跨轮次单调递增计数器（DB max 初始化，内存分配全覆盖） */
    private final AtomicLong seqCounter = new AtomicLong(0);
    /** 执行层：当前轮事件流状态（每轮经 beginRound 替换） */
    private volatile AgentRunState runState;
    /** 执行层：在跑执行句柄（CAS 串行守卫；null=当前无在跑执行） */
    private final AtomicReference<ActiveRun> activeRun = new AtomicReference<>();

    public AgentSessionContext(AgentSession session) {
        this.session = session;
    }

    // ==================== 身份层 ====================

    /**
     * 会话镜像（DB 为权威）。
     */
    public AgentSession session() {
        return session;
    }

    /**
     * 会话 ID（聚合键）。
     */
    public String sessionId() {
        return session.sessionId();
    }

    // ==================== 事件序号层 ====================

    /**
     * 分配下一会话级事件序列号（跨轮次单调递增，DB 唯一索引兜底）。
     */
    public long nextSequence() {
        return seqCounter.incrementAndGet();
    }

    /**
     * 开启新一轮：将事件序号基准抬升至 DB 最大序号（取 {@code max} 不回退），
     * 并替换当前轮事件流状态。
     *
     * @param dbMaxSequence 本会话 DB 中当前最大事件序列号（短事务 A 内查询）
     * @return 本轮事件流状态（调用方持有并贯穿本轮编排）
     */
    public AgentRunState beginRound(long dbMaxSequence) {
        seqCounter.accumulateAndGet(dbMaxSequence, Math::max);
        AgentRunState next = new AgentRunState();
        this.runState = next;
        return next;
    }

    /**
     * 当前轮事件流状态（仅访问；变更一律经 {@link #beginRound} 替换）。
     */
    public AgentRunState runState() {
        return runState;
    }

    // ==================== 执行层（在跑执行句柄） ====================

    /**
     * 注册本轮在跑执行；同会话已有在跑执行时拒绝（进程内串行守卫，
     * 与数据库单飞 CAS 互为双保险）。
     *
     * @param roundId 本轮轮次 ID
     * @param agent   已装配 agent 句柄（中断用）
     * @return true=注册成功；false=同会话已有在跑执行
     */
    public boolean registerActiveRun(String roundId, BuiltAgent agent) {
        ActiveRun run = new ActiveRun(sessionId(), roundId, agent, System.currentTimeMillis());
        if (!activeRun.compareAndSet(null, run)) {
            log.warn("会话已有在跑执行，拒绝重复注册: sessionId={}, roundId={}", sessionId(), roundId);
            return false;
        }
        return true;
    }

    /**
     * 当前在跑执行（可能不存在；调用方据其存在性区分「执行中」与「已中断」）。
     */
    public Optional<ActiveRun> activeRun() {
        return Optional.ofNullable(activeRun.get());
    }

    /**
     * 正常完成 / 异常兜底：仅清除注册（执行已自行结束，不中断 agent）。
     */
    public void clearActiveRun() {
        activeRun.set(null);
    }

    /**
     * 断连 / 终止：清除注册并幂等中断在跑 agent（无在跑执行时空操作）。
     */
    public void interruptActiveRun() {
        ActiveRun run = activeRun.getAndSet(null);
        if (run == null) {
            return;
        }
        try {
            log.info("断连/终止中断在跑执行: sessionId={}, roundId={}", sessionId(), run.roundId());
            run.agent().interrupt();
        } catch (Exception ex) {
            log.warn("中断 agent 执行异常: sessionId={}, roundId={}", sessionId(), run.roundId(), ex);
        }
    }

    /**
     * 在跑执行上下文。
     *
     * @param sessionId 会话 ID
     * @param roundId   本轮轮次 ID
     * @param agent     已装配 agent 句柄（中断 / 释放）
     * @param startedAt 启动时间戳（epoch 毫秒）
     */
    public record ActiveRun(String sessionId, String roundId, BuiltAgent agent, long startedAt) {
    }
}