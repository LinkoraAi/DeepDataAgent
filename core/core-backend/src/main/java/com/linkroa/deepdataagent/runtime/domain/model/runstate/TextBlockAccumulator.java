package com.linkroa.deepdataagent.runtime.domain.model.runstate;

import com.linkroa.deepdataagent.runtime.domain.model.ChatEvent;
import com.linkroa.deepdataagent.runtime.domain.model.enums.ChatEventType;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Map;

/**
 * 文本 / 思考块累积组件（单轮运行态五件套之一，由 {@link TurnRunState} 门面组合）。
 * <p>吸收原 {@code AgentRunState} 的「文本输出累积 + 文本 / 思考块累积 + 进行中流与 delta 聚合」
 * 三节，以及 {@code finalResultText}（AGENT_RESULT 最终文本兜底）原子字段。
 * 并发契约随字段逐字搬迁：</p>
 * <ul>
 *   <li>{@code output} / {@code pendingDelta} 为普通 {@link StringBuilder}，仅由 Reactor 串行事件流
 *       在单线程内独占写，禁止并发调用；</li>
 *   <li>{@code textBlocks} / {@code thinkingBlocks} 以 {@link ConcurrentHashMap} 承载，
 *       对单个 builder 的追加与只读快照一律 {@code synchronized} 在同一 builder 上
 *       （跨线程只读面：{@link #accumulatedText(String)} 由 HTTP 重连线程读取，与流线程追加写并发）；</li>
 *   <li>{@code finalResultText} 以 {@link AtomicReference} 承载，兼容中断路径并发读；</li>
 *   <li>{@code inFlightStream} 为 {@code volatile} 引用（后装覆盖前装，终态 / 挂起时清空）。</li>
 * </ul>
 * <p>本组件不带日志（领域运行态组件无 slf4j，日志归应用层策略 / 模板）。</p>
 */
public final class TextBlockAccumulator {

    /** 累积文本输出（轮次最终 output；单线程独占写，非线程安全） */
    private final StringBuilder output = new StringBuilder();
    /** AGENT_RESULT 携带的最终结果文本（兜底回填 output；AtomicReference 兼容中断路径并发读） */
    private final AtomicReference<String> finalResultText = new AtomicReference<>("");
    /** 文本块累积（block_id → 累积文本，TEXT_END 落库 agent.message；跨线程只读面见类 Javadoc） */
    private final Map<String, StringBuilder> textBlocks = new ConcurrentHashMap<>();
    /** 思考块累积（block_id → 累积文本，THINKING_END 落库 agent.thinking；与文本块同型同策略） */
    private final Map<String, StringBuilder> thinkingBlocks = new ConcurrentHashMap<>();
    /** 文本块事件 ID（block_id → evt_，流式帧与最终事件共用） */
    private final Map<String, String> textEventIds = new java.util.HashMap<>();
    /** 思考块事件 ID（block_id → evt_，流式帧与最终事件共用） */
    private final Map<String, String> thinkingEventIds = new java.util.HashMap<>();
    /** 已开始流式推送的文本块（block_id → 是否已发 event_start，首个 delta 前置发帧） */
    private final Set<String> startedTextBlocks = new HashSet<>();
    /** 已开始流式推送的思考块（block_id → 是否已发 event_start） */
    private final Set<String> startedThinkingBlocks = new HashSet<>();
    /** 进行中的流式块快照（重连三段语义回补判定依据；首 delta 装载、buffered 落库/终态清空） */
    private volatile InFlightStream inFlightStream;
    /** event_delta 聚合缓冲（按连接协商的 delta_flush 间隔刷写，减少逐 token 帧推送；单线程独占写） */
    private final StringBuilder pendingDelta = new StringBuilder();
    /** 最近一次 delta 刷写时刻（epoch 毫秒；0=从未，首段立发） */
    private long lastDeltaFlushAt;

    // ==================== 文本输出累积 ====================

    /**
     * 追加文本输出（TEXT 增量）。
     * <p>仅由 Reactor 串行事件流在单线程内独占写；本字段为普通 {@code StringBuilder}，
     * 非线程安全，禁止并发调用。</p>
     */
    public void appendOutput(String delta) {
        if (delta != null && !delta.isEmpty()) {
            output.append(delta);
        }
    }

    /**
     * 记录 AGENT_RESULT 最终文本（与增量累积互相兜底）。
     */
    public void setFinalResultText(String text) {
        if (text != null && !text.isEmpty()) {
            finalResultText.set(text);
        }
    }

    /**
     * 轮次最终输出（增量累积优先，缺失时回退 AGENT_RESULT 文本）。
     */
    public String output() {
        if (output.length() > 0) {
            return output.toString();
        }
        return finalResultText.get();
    }

    // ==================== 文本 / 思考块累积（流式帧 + 最终事件关联） ====================

    /**
     * 追加文本块增量并返回该块累积文本（TEXT_DELTA 实时帧用）。
     * <p>builder 与 {@link #accumulatedText(String)}（HTTP 线程）并发，故追加与读取同在 builder 上加锁。</p>
     */
    public String appendText(String blockId, String delta) {
        if (blockId == null || delta == null) {
            return "";
        }
        StringBuilder text = textBlocks.computeIfAbsent(blockId, k -> new StringBuilder());
        synchronized (text) {
            text.append(delta);
            return text.toString();
        }
    }

    /**
     * 取出并移除文本块完整文本（TEXT_END 落库 agent.message）。
     */
    public String takeText(String blockId) {
        if (blockId == null) {
            return "";
        }
        StringBuilder text = textBlocks.remove(blockId);
        if (text == null) {
            return "";
        }
        synchronized (text) {
            return text.toString();
        }
    }

    /**
     * 惰性分配文本块事件 ID（首次调用生成，流式帧与最终事件共用）。
     */
    public String ensureTextEventId(String blockId) {
        return textEventIds.computeIfAbsent(blockId, k -> newEventId());
    }

    /**
     * 取出并移除文本块事件 ID（TEXT_END 落库用）。
     */
    public String takeTextEventId(String blockId) {
        return textEventIds.remove(blockId);
    }

    /**
     * 追加思考块增量并返回该块累积文本（THINKING_DELTA 实时帧用）。
     * <p>与文本块同型：builder 追加与只读快照同在 builder 上加锁。</p>
     */
    public String appendThinking(String blockId, String delta) {
        if (blockId == null || delta == null) {
            return "";
        }
        StringBuilder text = thinkingBlocks.computeIfAbsent(blockId, k -> new StringBuilder());
        synchronized (text) {
            text.append(delta);
            return text.toString();
        }
    }

    /**
     * 取出并移除思考块完整文本（THINKING_END 落库 agent.thinking）。
     */
    public String takeThinking(String blockId) {
        if (blockId == null) {
            return "";
        }
        StringBuilder text = thinkingBlocks.remove(blockId);
        if (text == null) {
            return "";
        }
        synchronized (text) {
            return text.toString();
        }
    }

    /**
     * 惰性分配思考块事件 ID（首次调用生成，流式帧与最终事件共用）。
     */
    public String ensureThinkingEventId(String blockId) {
        return thinkingEventIds.computeIfAbsent(blockId, k -> newEventId());
    }

    /**
     * 取出并移除思考块事件 ID（THINKING_END 落库用）。
     */
    public String takeThinkingEventId(String blockId) {
        return thinkingEventIds.remove(blockId);
    }

    /**
     * 标记文本块开始流式推送（首个 delta 到达时调用一次）。
     *
     * @return true=首次（调用方应先推 event_start 再推 event_delta）
     */
    public boolean markTextStreamStarted(String blockId) {
        return blockId != null && startedTextBlocks.add(blockId);
    }

    /**
     * 标记思考块开始流式推送（首个 delta 到达时调用一次）。
     *
     * @return true=首次（调用方应先推 event_start；thinking 不暴露推理内容、无 delta 帧）
     */
    public boolean markThinkingStreamStarted(String blockId) {
        return blockId != null && startedThinkingBlocks.add(blockId);
    }

    // ==================== 进行中流与 delta 聚合（连接级协商增量帧） ====================

    /**
     * 进行中的流式块快照（重连三段语义回补判定依据）。
     *
     * @param eventId    流式事件 ID（evt_，与最终 buffered 事件同 ID）
     * @param targetType 目标事件类型（{@code agent.message} / {@code agent.thinking}）
     * @param blockId    源块 ID（累积文本读取入口，一段重连回补历史 delta 用）
     * @param baseSeq    流开始时的会话序列号现值（start 前最后一条已落库事件的 seq）
     */
    public record InFlightStream(String eventId, ChatEventType targetType, String blockId, long baseSeq) {

        public InFlightStream {
            if (eventId == null || eventId.isBlank()) {
                throw new IllegalArgumentException("进行中流事件 ID 不能为空");
            }
            if (targetType == null) {
                throw new IllegalArgumentException("进行中流目标类型不能为空");
            }
        }
    }

    /**
     * 装载进行中流快照（event_start 发出时调用；同轮单流，后装覆盖前装）。
     *
     * @param eventId    流式事件 ID
     * @param targetType 目标事件类型
     * @param blockId    源块 ID
     * @param baseSeq    流开始时的序列号基准
     */
    public void markInFlightStream(String eventId, ChatEventType targetType, String blockId, long baseSeq) {
        this.inFlightStream = new InFlightStream(eventId, targetType, blockId, baseSeq);
    }

    /**
     * 当前进行中的流式块快照（无进行中流返回 {@code null}）。
     */
    public InFlightStream inFlightStream() {
        return inFlightStream;
    }

    /**
     * 清空进行中流快照（仅当 {@code eventId} 与当前进行中流一致时——buffered 落库前调用，
     * 防止误清后起的流）。
     *
     * @param eventId 已收尾的流式事件 ID（可空，空则不清）
     */
    public void clearInFlightStream(String eventId) {
        InFlightStream current = inFlightStream;
        if (current != null && eventId != null && current.eventId().equals(eventId)) {
            inFlightStream = null;
        }
    }

    /**
     * 挂起（HITL 等待确认）时无条件丢弃进行中流快照与未刷完的尾部增量聚合缓冲。
     * <p>挂起即物理轮终局：未收尾的文本 / 思考块不会再收到 TEXT_END / THINKING_END，
     * 快照若残留，断线重连会回补一条永远等不到收尾的 {@code event_start} 帧。
     * 与 {@link #dropInFlightAndPending()} 的终态清理同构（本轮生成结束即无进行中流）。</p>
     */
    public void clearInFlightStreamOnSuspend() {
        dropInFlightAndPending();
    }

    /**
     * 丢弃进行中流快照与 delta 聚合缓冲（终态 / 挂起共用：本轮生成结束即无进行中流，
     * 未刷完的尾部增量由 buffered 权威事件覆盖）。由 {@link TurnRunState#tryFinalize()} 领取资格后调用。
     */
    public void dropInFlightAndPending() {
        inFlightStream = null;
        pendingDelta.setLength(0);
    }

    /**
     * 文本块当前累积文本（只读副本，不移除；一段重连回补聚合 delta 用）。
     * <p>由 HTTP 线程调用，与流线程 {@link #appendText(String, String)} 并发访问同一 builder，
     * 故在同一 builder 对象上加锁取快照。</p>
     */
    public String accumulatedText(String blockId) {
        if (blockId == null) {
            return "";
        }
        StringBuilder text = textBlocks.get(blockId);
        if (text == null) {
            return "";
        }
        synchronized (text) {
            return text.toString();
        }
    }

    /**
     * 追加待刷写的 delta 片段（聚合缓冲，按 {@link #shouldFlushDelta} 节奏下发）。
     */
    public void appendPendingDelta(String delta) {
        if (delta != null && !delta.isEmpty()) {
            pendingDelta.append(delta);
        }
    }

    /**
     * 是否到达 delta 刷写窗口（从未刷写时恒为 true——首段立发）。
     *
     * @param intervalMs 刷写间隔毫秒（非正数视为逐段立发）
     * @return true=应刷写
     */
    public boolean shouldFlushDelta(long intervalMs) {
        return System.currentTimeMillis() - lastDeltaFlushAt >= Math.max(intervalMs, 0L);
    }

    /**
     * 排空聚合缓冲并刷新刷写时刻。
     *
     * @return 本次应下发的聚合 delta 文本（缓冲为空时返回空串）
     */
    public String drainPendingDelta() {
        String flushed = pendingDelta.toString();
        pendingDelta.setLength(0);
        if (!flushed.isEmpty()) {
            lastDeltaFlushAt = System.currentTimeMillis();
        }
        return flushed;
    }

    /** 生成流式事件 ID（evt_ 前缀，与最终事件共用）。 */
    private static String newEventId() {
        return ChatEvent.EVENT_ID_PREFIX + UUID.randomUUID().toString().replace("-", "");
    }
}
