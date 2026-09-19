package com.linkroa.deepdataagent.runtime.application.service.execution;

import com.linkroa.deepdataagent.runtime.application.port.ChatEventPersister;
import com.linkroa.deepdataagent.runtime.application.port.SessionRuntimeRegistry;
import com.linkroa.deepdataagent.runtime.domain.event.ChatEventFactory;
import com.linkroa.deepdataagent.runtime.domain.event.ChatEventFactory.AssembledEvent;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSessionContext;
import com.linkroa.deepdataagent.runtime.domain.model.ChatEvent;
import com.linkroa.deepdataagent.runtime.domain.model.StreamFrame;
import com.linkroa.deepdataagent.runtime.domain.model.enums.ChatEventType;
import com.linkroa.deepdataagent.runtime.domain.model.runstate.TurnRunState;
import com.linkroa.deepdataagent.runtime.domain.repository.ChatEventRepository;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * turn 事件写入器（decompose-command-facade 2.3）：轮内 / 终态事件「推送 + 异步落库」共用的
 * 单点底座——承载「异步批量落库队列协作（enqueue 语义）+ 会话级 seq 分配 + SSE 连接层推流
 * 吞异常」三件事（design D2：真实机制缝而非透传层）。
 * <p>八方法自原命令门面（decompose-command-facade 4.4 已物理删除）纯搬移，红线语义零变化：
 * <b>先推后入队</b>（SSE 不被落库 I/O 阻塞）、推送失败<b>吞异常仅记 WARN</b>
 * （断线重连回放兜底）、seq 统一走会话级计数器（DB 唯一索引兜底）。
 * 严格排空协议的事务侧协作（flush / isPoisoned / requireLedgerDurable）自 3.1 起随轮次收口家族
 * 迁至 {@code execution.TurnFinalizer}，本组件仅承载轮内 / 终态事件共用的写入底座。</p>
 */
@Service
public class TurnEventWriter {

    private static final Logger log = LoggerFactory.getLogger(TurnEventWriter.class);

    @Resource
    private SessionRuntimeRegistry sessionRegistry;
    @Resource
    private ChatEventRepository chatEventRepository;
    @Resource
    private ChatEventPersister chatEventPersister;

    /**
     * 事件推送与异步落库（流内事件与合成事件共用，用于最终事件）：seq 统一由
     * {@link AgentSessionContext#nextSequence()} 会话级计数器分配（DB 唯一索引兜底）。
     * <p>顺序：先经连接层推送（SSE 不被落库 I/O 阻塞），再入异步批量队列落库；
     * 落库失败由后台重试耗尽后记结构化 ERROR 并<b>置该会话持久化失败毒标志</b>——事件已推送、
     * 断线重连回放兜底，但该会话后续终态 / 挂起的严格排空协议（事务前 {@code flush} 整队排空 +
     * 事务首行 {@code isPoisoned} 查毒）会因此拒绝状态迁移、整事务回滚，
     * 杜绝「账本缺行却已迁状态」。</p>
     */
    public void persistAndBroadcast(ExecutionContext context, AssembledEvent assembled) {
        persistAndBroadcast(context, assembled, null);
    }

    /**
     * 事件推送与异步落库（可指定事件 ID：流式块最终事件复用帧的 evt_ ID，
     * 保证 {@code event_delta} 实时帧与落库最终事件在回放 + 实时订阅重合窗口可关联 / 去重）。
     */
    public void persistAndBroadcast(ExecutionContext context, AssembledEvent assembled, String eventId) {
        String sessionId = context.session().sessionId();
        ChatEvent event = assemble(context, assembled, eventId);
        pushQuietly(context.sessionContext(), event);
        chatEventPersister.enqueue(event);
    }

    /** 组装持久化事件（seq 由会话级计数器分配；eventId 缺失时自动生成）。 */
    ChatEvent assemble(ExecutionContext context, AssembledEvent assembled) {
        return assemble(context, assembled, null);
    }

    /** 组装持久化事件（可指定事件 ID，缺失时自动生成 evt_ 前缀；线程归属取自执行现场）。 */
    ChatEvent assemble(ExecutionContext context, AssembledEvent assembled, String eventId) {
        long seq = context.nextSequence();
        return ChatEvent.create(context.session().sessionId(), assembled.type(),
                assembled.payloadJson(), seq, eventId, context.sessionThreadId());
    }

    /**
     * 流式帧推送（event_start / event_delta）：帧不落库、不消耗 seq、不入回放，
     * 仅经连接层实时下发——由连接组按各连接的 {@code event_deltas[]} 协商目标类型过滤。
     */
    public void pushFrame(ExecutionContext context, AssembledEvent frame, String eventId,
                          ChatEventType targetType) {
        try {
            context.sessionContext().connection().pushFrame(
                    new StreamFrame(frame.type(), eventId, targetType, frame.payloadJson()));
        } catch (RuntimeException ex) {
            log.warn("SSE 增量帧推送失败: sessionId={}, type={}",
                    context.sessionId(), frame.type().value(), ex);
        }
    }

    /** 排空 delta 聚合缓冲并向协商连接刷写 event_delta 帧（缓冲为空时静默）。 */
    public void flushTextDelta(ExecutionContext context, TurnRunState runState, String eventId) {
        String flushed = runState.drainPendingDelta();
        if (!flushed.isEmpty()) {
            pushFrame(context, ChatEventFactory.INSTANCE.eventDelta(eventId, flushed),
                    eventId, ChatEventType.AGENT_MESSAGE);
        }
    }

    /** 尝试向连接层推送：领域事件经连接句柄广播，协议转换在基础设施；失败记录 WARN（事件仍会异步入队落库，断线重连回放兜底）。 */
    public void pushQuietly(AgentSessionContext sessionContext, ChatEvent event) {
        if (event == null) {
            return;
        }
        try {
            sessionContext.connection().push(event);
        } catch (RuntimeException ex) {
            log.warn("SSE 广播失败: sessionId={}, type={}", event.sessionId(), event.type().value(), ex);
        }
    }

    /**
     * 分配会话内下一事件序列号（统一入口）。
     * <p>会话在场（registry 中存在聚合，会话级 {@code AtomicLong} 计数器已按 DB max 抬升）
     * 时经内存计数器分配：跨线程 {@link AtomicLong#incrementAndGet} 保证与会话内所有事件
     * （流内异步队列 / 终态事务 / 出带指令）严格不重复；会话不在场（冷启动、本次进程从未运行）
     * 时回退到 DB {@code max(seq)+1}，由 {@code beginRound} 的 max 抬升兜底收敛。</p>
     *
     * @param sessionId 会话 ID
     * @return 下一可用序列号（从 1 开始，会话内单调递增）
     */
    public long nextSequence(String sessionId) {
        return sessionRegistry.get(sessionId)
                .map(AgentSessionContext::nextSequence)
                .orElseGet(() -> chatEventRepository.nextSequenceNum(sessionId));
    }
}
