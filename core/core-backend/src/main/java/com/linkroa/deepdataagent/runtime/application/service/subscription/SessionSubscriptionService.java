package com.linkroa.deepdataagent.runtime.application.service.subscription;

import com.linkroa.deepdataagent.runtime.application.convert.AgentRuntimeCommandConvert;
import com.linkroa.deepdataagent.runtime.application.service.AgentRuntimeQueryService;
import com.linkroa.deepdataagent.runtime.domain.event.ChatEventFactory;
import com.linkroa.deepdataagent.runtime.application.port.SseTransportPort;
import com.linkroa.deepdataagent.runtime.domain.model.runstate.TextBlockAccumulator.InFlightStream;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSession;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSessionContext;
import com.linkroa.deepdataagent.runtime.domain.model.ChatEvent;
import com.linkroa.deepdataagent.runtime.domain.port.ConnectionHandle;
import com.linkroa.deepdataagent.runtime.domain.model.StreamFrame;
import com.linkroa.deepdataagent.runtime.domain.model.enums.ChatEventType;
import com.linkroa.deepdataagent.runtime.application.port.SessionRuntimeRegistry;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * SSE 订阅应用服务（控制器编排下沉落点，逻辑逐行平移自 {@code AgentChatEventController} 私有方法）。
 * <p>{@link #open} 封装「取/建会话运行时 → 工厂获取/重建连接句柄 → 绑定（含断连回调）→
 * 先绑定后回放（下发 {@code : connected} 握手行 → 回放 {@code Last-Event-ID} 之后的历史事件）→
 * 三段重连判定」全序。顺序锁定：<b>注册实时连接必须先于历史回放</b>（绑定到回放之间的实时广播
 * 与该 emitter 同步，客户端按 event_id / seq 幂等去重，杜绝「回放先于绑定」的丢失窗口）。</p>
 * <p>SSE 协议细节（emitter 创建 / 注释帧 / 信封与流帧编码）经 {@link SseTransportPort} 触达，
 * 应用层不直接依赖 {@code infrastructure.sse}；句柄唯一由会话运行时（{@link SessionRuntimeRegistry}）持有。</p>
 */
@Service
public class SessionSubscriptionService {

    /** 增量协商值域：仅消息与思考流支持增量帧（对齐 {@code event_deltas[]} 契约取值域）。 */
    private static final Set<ChatEventType> DELTA_NEGOTIABLE_TYPES =
            Set.of(ChatEventType.AGENT_MESSAGE, ChatEventType.AGENT_THINKING);

    @Resource
    private AgentRuntimeQueryService queryService;
    @Resource
    private SessionRuntimeRegistry sessionRegistry;
    @Resource
    private SseTransportPort sseTransportPort;

    /**
     * 打开一条 SSE 订阅连接：校验会话存在（快速失败，避免绑定后查询 404）后按全序执行
     * 绑定注册与历史回放；回放/重连下发任一异常即以错误完成该连接（行为对齐原控制器）。
     *
     * @param sessionId            会话 ID
     * @param lastEventId          SSE 标准 {@code Last-Event-ID} 断点游标（可空，evt_ ID 或数字 seq）
     * @param eventDeltas          连接级增量协商参数 {@code event_deltas[]}（可空 = 仅 buffered）
     * @param deltaFlushIntervalMs 增量刷写间隔毫秒（可空 / 非正数收敛为缺省 50ms）
     * @return 已完成绑定与回放、保持实时订阅的 emitter（控制器唯一接触的框架类型出口）
     */
    public SseEmitter open(String sessionId, String lastEventId,
                           List<String> eventDeltas, Long deltaFlushIntervalMs) {
        // 先校验会话存在（快速失败，避免绑定后查询 404）
        AgentSession session = queryService.getSession(sessionId);

        // 连接级增量协商：仅显式声明的目标类型走增量帧，未声明 = 仅 buffered；
        // 不支持 / 未知取值 MUST 400（不再静默忽略）
        Set<ChatEventType> deltaTargets = resolveDeltaTargets(eventDeltas);
        long flushIntervalMs = deltaFlushIntervalMs == null || deltaFlushIntervalMs <= 0
                ? SseTransportPort.DEFAULT_DELTA_FLUSH_INTERVAL_MS : deltaFlushIntervalMs;

        SseEmitter emitter = bindAndRegister(session, deltaTargets, flushIntervalMs);
        try {
            // 连接建立后立即下发注释行，随后回放历史事件（对齐 Managed Agents : connected）
            sseTransportPort.sendComment(emitter, "connected");
            // 绑定完成后再回放：绑定到回放之间的实时广播与该 emitter 同步，
            // 客户端按 event_id / seq 幂等去重，杜绝「回放先于绑定」的丢失窗口
            AgentRuntimeQueryService.ReplayPosition position =
                    queryService.resolveReplayPosition(sessionId, lastEventId);
            List<ChatEvent> history = queryService.replayEvents(AgentRuntimeCommandConvert.INSTANCE
                    .toReplayQuery(sessionId, position.afterSequence(), null));
            for (ChatEvent event : history) {
                sseTransportPort.sendEvent(emitter, event);
            }
            // 三段重连：游标早于进行中事件 start 且仍在生成（一段）→ 回补 event_start 与
            // 已累积文本的聚合 delta；游标等于进行中事件 id（二段）→ 不重放历史 delta、
            // 仅推重连后新增；生成已完成（三段）→ 进行中流已清空，天然不再重放 delta
            if (!position.midStream()) {
                replayInFlightStart(emitter, sessionId, deltaTargets, position.afterSequence());
            }
        } catch (Exception ex) {
            emitter.completeWithError(ex);
            return emitter;
        }
        return emitter;
    }

    /**
     * 打开一条线程作用域 SSE 订阅连接（公开契约嵌套端点
     * {@code GET /sessions/{id}/threads/{thread_id}/events/stream}）。
     * <p>线程流<b>仅提供 buffered</b>：不支持 {@code event_deltas[]} 增量协商（调用方先于本方法
     * 对携带增量参数者返回 400）；回放按线程归属过滤（{@code session_thread_id} 为事件表内部过滤键，
     * 不扩张对外扁平 Event 公开字段），其后保持会话级实时订阅（当前执行面仅主线程产生事件）。</p>
     *
     * @param sessionId   会话 ID
     * @param threadId    线程业务 ID（{@code sthr_} 前缀）
     * @param lastEventId SSE 标准 {@code Last-Event-ID} 断点游标（可空，evt_ ID 或数字 seq）
     * @return 已完成绑定与线程作用域回放的 emitter
     */
    public SseEmitter openThread(String sessionId, String threadId, String lastEventId) {
        AgentSession session = queryService.getSession(sessionId);
        queryService.requireThread(sessionId, threadId);
        SseEmitter emitter = bindAndRegister(session, Set.of(),
                SseTransportPort.DEFAULT_DELTA_FLUSH_INTERVAL_MS);
        try {
            sseTransportPort.sendComment(emitter, "connected");
            AgentRuntimeQueryService.ReplayPosition position =
                    queryService.resolveReplayPosition(sessionId, lastEventId);
            for (ChatEvent event : queryService.replayThreadEvents(sessionId, threadId, position.afterSequence())) {
                sseTransportPort.sendEvent(emitter, event);
            }
        } catch (Exception ex) {
            emitter.completeWithError(ex);
            return emitter;
        }
        return emitter;
    }

    // ==================== 私有方法（逐行平移自控制器） ====================

    /**
     * 将会话绑定到连接层并注册一个携带增量协商参数的订阅者：复用会话级连接句柄
     * （多订阅者 fan-out）、注册「全部断连 → 取消运行中的执行」回调，
     * 再创建受超时 / 断连保护的 emitter（协商仅作用于本连接）。
     * <p>句柄「复用判定 + 绑定」对同一会话上下文加锁串行（句柄唯一所有者为 {@code AgentSessionContext}，
     * 替代原注册表 map 的 compute 原子性）：并发多标签页订阅共享同一句柄，不互相顶掉连接组。</p>
     */
    private SseEmitter bindAndRegister(AgentSession session, Set<ChatEventType> deltaTargets, long flushIntervalMs) {
        AgentSessionContext context = sessionRegistry.getOrCreate(session);
        ConnectionHandle handle;
        synchronized (context) {
            handle = sseTransportPort.acquireHandle(context.connection());
            context.bindConnection(handle);
        }
        // 断连不取消：SSE 连接仅为观察 / 回放通道，连接断开 MUST NOT 触发运行中的执行取消
        //（取消只能由显式 POST /cancel 或 user.interrupt 发起；客户端断线重连凭 Last-Event-ID 回放）
        return sseTransportPort.openConnection(handle, deltaTargets, flushIntervalMs);
    }

    /**
     * 解析 {@code event_deltas[]} 协商值：仅接受 {@code agent.message} / {@code agent.thinking}，
     * 任何未知类型或非增量可协商类型（如 {@code agent.tool_use}）MUST 抛参错 → 400
     * {@code invalid_request_error}（不再静默忽略）。
     */
    private Set<ChatEventType> resolveDeltaTargets(List<String> eventDeltas) {
        if (eventDeltas == null || eventDeltas.isEmpty()) {
            return Set.of();
        }
        Set<ChatEventType> targets = EnumSet.noneOf(ChatEventType.class);
        for (String raw : eventDeltas) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            ChatEventType type;
            try {
                type = ChatEventType.fromValue(raw);
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("不支持的 event_deltas[] 取值: " + raw);
            }
            if (!DELTA_NEGOTIABLE_TYPES.contains(type)) {
                throw new IllegalArgumentException("不支持的 event_deltas[] 取值: " + raw);
            }
            targets.add(type);
        }
        return targets;
    }

    /**
     * 一段重连回补：游标早于（或恰为）进行中事件的 {@code event_start} 且该目标类型已协商——
     * 向本 emitter 回补 event_start 帧；{@code agent.message} 另回补一条已累积文本的聚合
     * event_delta（近似「保留的历史 delta」），其后新增增量经实时订阅续流并以 buffered 收尾。
     */
    private void replayInFlightStart(SseEmitter emitter, String sessionId,
                                     Set<ChatEventType> deltaTargets, long afterSequence) throws IOException {
        if (deltaTargets.isEmpty()) {
            return;
        }
        InFlightStream inFlight = queryService.currentInFlightStream(sessionId);
        if (inFlight == null || !deltaTargets.contains(inFlight.targetType())
                || afterSequence > inFlight.baseSeq()) {
            return;
        }
        sseTransportPort.sendFrame(emitter, new StreamFrame(ChatEventType.EVENT_START,
                inFlight.eventId(), inFlight.targetType(),
                ChatEventFactory.INSTANCE
                        .eventStart(inFlight.eventId(), inFlight.targetType()).payloadJson()));
        if (inFlight.targetType() == ChatEventType.AGENT_MESSAGE) {
            String accumulated = queryService.accumulatedStreamText(sessionId, inFlight.blockId());
            if (!accumulated.isEmpty()) {
                sseTransportPort.sendFrame(emitter, new StreamFrame(ChatEventType.EVENT_DELTA,
                        inFlight.eventId(), ChatEventType.AGENT_MESSAGE,
                        ChatEventFactory.INSTANCE
                                .eventDelta(inFlight.eventId(), accumulated).payloadJson()));
            }
        }
    }
}
