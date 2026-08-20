package com.linkroa.deepdataagent.runtime.application.service;

import com.linkroa.deepdataagent.runtime.application.assembler.AgentRuntimeAssembler;
import com.linkroa.deepdataagent.runtime.application.assembler.ChatEventPayloadAssembler;
import com.linkroa.deepdataagent.runtime.application.assembler.ChatEventPayloadAssembler.AssembledEvent;
import com.linkroa.deepdataagent.runtime.application.command.CreateSessionCommand;
import com.linkroa.deepdataagent.runtime.application.command.SendMessageCommand;
import com.linkroa.deepdataagent.runtime.application.command.TerminateSessionCommand;
import com.linkroa.deepdataagent.runtime.domain.event.AgentStreamSignal;
import com.linkroa.deepdataagent.runtime.domain.factory.AgentFactoryPort;
import com.linkroa.deepdataagent.runtime.domain.factory.BuiltAgent;
import com.linkroa.deepdataagent.runtime.domain.model.AgentRunState;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSession;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSessionContext;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSessionExecution;
import com.linkroa.deepdataagent.runtime.domain.model.ChatEvent;
import com.linkroa.deepdataagent.runtime.domain.model.ExecutionRound;
import com.linkroa.deepdataagent.runtime.domain.model.NoOpConnectionHandle;
import com.linkroa.deepdataagent.runtime.domain.model.RunTrace;
import com.linkroa.deepdataagent.runtime.domain.model.enums.AgentSessionStatus;
import com.linkroa.deepdataagent.runtime.domain.model.enums.ChatEventType;
import com.linkroa.deepdataagent.runtime.domain.model.enums.RoundStatus;
import com.linkroa.deepdataagent.runtime.domain.model.enums.SessionState;
import com.linkroa.deepdataagent.runtime.domain.model.enums.SpanKind;
import com.linkroa.deepdataagent.runtime.domain.model.enums.SpanStatus;
import com.linkroa.deepdataagent.runtime.domain.repository.AgentSessionRepository;
import com.linkroa.deepdataagent.runtime.domain.repository.ChatEventRepository;
import com.linkroa.deepdataagent.runtime.domain.repository.ExecutionRoundRepository;
import com.linkroa.deepdataagent.runtime.domain.repository.RunTraceRepository;
import com.linkroa.deepdataagent.runtime.domain.repository.SessionRegistry;
import com.linkroa.deepdataagent.runtime.infrastructure.util.PayloadSanitizer;
import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.scheduler.Scheduler;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

/**
 * Agent 运行时命令服务（会话写操作 + 消息发送编排）。
 * <p>消息发送由两个事务边界 + 中间事件流阶段组成：</p>
 * <ol>
 *   <li><b>启动事务</b>：经 CAS（IDLE→RUNNING）独占抢占会话（同一会话同一时刻仅一个执行在跑），
 *       随后创建 execution_round + 根 span，完成会话级状态准备；</li>
 *   <li><b>事件流阶段</b>：订阅 {@code AgentRunExecutor.streamEvents} 冷流，逐信号完成 seq 分配 /
 *       payload 装配 / 落库 / SSE 广播 / span 追踪，并判定 AGENT_END 提前终态；</li>
 *   <li><b>终态事务</b>：round 终态 + 终态事件落库 + 会话回 IDLE + 根 span 结束，仅广播一发 session_status。</li>
 * </ol>
 * <p>阻塞型执行经虚拟线程执行器（阻塞等待）与 boundedElastic 阻塞调度器（落库/广播编排）
 * 托管，二者默认均运行于虚拟线程，web 请求线程永不阻塞（解 Tomcat 占用）；
 * 只读查询用例见 {@link AgentRuntimeQueryService}（读写职责拆分）。</p>
 *
 * <p><b>运行时主链路</b>（虚拟线程阻塞模型，完整编排见 {@code executeRound} / {@code runEventStream}）：</p>
 * <pre>{@code
 *  请求线程 → sendMessageAsync(快速校验 session) → virtualExecutor.execute(...)   // 立即返回，不解 Tomcat
 *  虚拟线程 → executeRound:
 *     启动事务: CAS(IDLE→RUNNING) + 建 execution_round/根 span + sessionContext.beginRound
 *     sessionContext.transitionState(RUNNING) → 广播 run_start
 *     execution.submit → runAgent: build → activate(agent::interrupt) → runEventStream
 *        streamEvents → publishOn(blockingScheduler) → doOnNext(handleSignal) 逐信号落库 + connection().push
 *        completion.get() 阻塞等待（虚拟线程让出载体线程）
 *        onComplete/onError → 终态唯一出口（finalizeRoundAndComplete / finalizeFailedRound）
 *     终态事务: round 终态 + 会话回 IDLE + 广播 session_status(IDLE, stop_reason)
 * }</pre>
 *
 * <p><b>中断 / 断连 / 终止链路</b>（见 {@code terminateSession} / {@link AgentSessionContext#cancel()}）：</p>
 * <pre>{@code
 *  客户端/断连 → terminateSession / onDisconnect
 *     requireSession + [事务] updateStatus(TERMINATED)
 *     sessionRegistry.get → AgentSessionContext.cancel()
 *        execution.cancel() → 触发 interrupt 句柄(agent::interrupt) → SDK 流自然收流(onStreamComplete)
 *        tryTransitionState(INTERRUPTED)（仅 RUNNING 态合法）
 *     bindConnection(NoOpConnectionHandle) → 释放全部 SSE emitter
 * }</pre>
 */
@Service
public class AgentRuntimeCommandService {

    private static final Logger log = LoggerFactory.getLogger(AgentRuntimeCommandService.class);

    private static final String DEEP_AGENT_SESSION_NOT_FOUND = "DEEP_AGENT_SESSION_NOT_FOUND";
    private static final String DEEP_AGENT_SESSION_BUSY = "DEEP_AGENT_SESSION_BUSY";
    private static final String DEEP_AGENT_RUN_ERROR = "DEEP_AGENT_RUN_ERROR";
    private static final String STOP_REASON_STOP = "stop";
    private static final String STOP_REASON_MAX_ITERATIONS = "max_iterations";
    private static final String STOP_REASON_ERROR = "error";
    private static final String STOP_REASON_INTERRUPTED = "interrupted";
    private static final String ROOT_SPAN_NAME = "agent.run";

    @Resource
    private AgentSessionRepository sessionRepository;
    @Resource
    private ExecutionRoundRepository roundRepository;
    @Resource
    private ChatEventRepository chatEventRepository;
    @Resource
    private RunTraceRepository runTraceRepository;
    @Resource
    private AgentFactoryPort agentFactory;
    @Resource
    private AgentRunExecutor agentRunExecutor;
    @Resource
    private AgentRuntimeAssembler assembler;
    @Resource
    private RuntimeAgentAssemblyResolver runtimeAgentAssemblyResolver;
    @Resource
    private SessionRegistry sessionRegistry;
    @Resource
    private TransactionTemplate transactionTemplate;
    @Resource
    private ChatEventPayloadAssembler payloadAssembler;
    @Resource(name = "agentVirtualExecutor")
    private Executor virtualExecutor;
    @Resource(name = "agentBlockingScheduler")
    private Scheduler blockingScheduler;
    @Resource
    private ObjectMapper objectMapper;

    // ==================== 会话管理（写操作） ====================

    /**
     * 创建会话（IDLE 初始态，懒构建 agent）。
     * <p>前置校验：agentId + 发布号必须绑定真实 Agent 版本台账（发布号非十进制 /
     * Agent 不存在或已归档 → 404），不允许无全局回退。</p>
     */
    public AgentSession createSession(CreateSessionCommand command) {
        // 运行时装配解析即校验链：发布号格式 / Agent 存在且未归档 / 版本存在 / profile 存在（免解密）
        runtimeAgentAssemblyResolver.assertResolvable(command.agentId(), command.agentVersion());
        return transactionTemplate.execute(status -> sessionRepository.save(assembler.toSession(command)));
    }

    /**
     * 终止会话：状态置 TERMINATED（不可复活），中断在跑执行，释放 SSE 订阅。
     * <pre>{@code
     *  terminateSession
     *    ├─ requireSession（不存在 → 404）
     *    ├─ [事务] updateStatus(TERMINATED)          // 不可复活
     *    ├─ AgentSessionContext.cancel()             // 幂等中断在跑执行
     *    │     ├─ execution.cancel() → agent.interrupt() → SDK 流 onStreamComplete → 中断终态
     *    │     └─ tryTransitionState(INTERRUPTED)
     *    └─ bindConnection(NoOpConnectionHandle)     // 释放全部 SSE emitter（旧句柄 close）
     * }</pre>
     */
    public void terminateSession(TerminateSessionCommand command) {
        requireSession(command.sessionId());
        transactionTemplate.executeWithoutResult(status ->
                sessionRepository.updateStatus(command.sessionId(), AgentSessionStatus.TERMINATED));
        // 断连/终止语义：幂等取消在跑执行并标记中断（当前轮次经中断终态路径收尾）
        sessionRegistry.get(command.sessionId()).ifPresent(AgentSessionContext::cancel);
        // 关闭全部订阅者：解绑连接触发旧句柄 close 完成全部 emitter 关闭（替代 eventBroadcaster.complete）
        sessionRegistry.get(command.sessionId()).ifPresent(ctx -> ctx.bindConnection(NoOpConnectionHandle.INSTANCE));
    }

    /**
     * 更新会话元数据（对齐 Managed Agents 更新会话，仅改 title/metadata）。
     */
    public AgentSession updateSession(String sessionId, String title, String metadataJson) {
        requireSession(sessionId);
        transactionTemplate.executeWithoutResult(status ->
                sessionRepository.updateMeta(sessionId, title, metadataJson));
        return requireSession(sessionId);
    }

    // ==================== 消息发送（事务 + 事件流编排） ====================

    /**
     * 异步发送消息（虚拟线程托管）：先同步校验会话存在（快速失败），
     * 再提交虚拟线程池执行；{@code onComplete} 在轮次终态（含广播）后回调
     * （SSE 直连场景用于关闭对应 emitter，保证终态事件送达后才断开）。
     */
    public void sendMessageAsync(SendMessageCommand command, Runnable onComplete) {
        requireSession(command.sessionId());
        virtualExecutor.execute(() -> {
            try {
                executeRound(command);
            } catch (RuntimeException ex) {
                log.error("异步发送消息失败: sessionId={}", command.sessionId(), ex);
            } finally {
                if (onComplete != null) {
                    try {
                        onComplete.run();
                    } catch (RuntimeException ex) {
                        log.warn("异步发送完成回调异常: sessionId={}", command.sessionId(), ex);
                    }
                }
            }
        });
    }

    /**
     * 异步发送消息（不含完成回调），兼容既有调用。
     */
    public void sendMessageAsync(SendMessageCommand command) {
        sendMessageAsync(command, null);
    }

    // ==================== 私有工具方法 ====================

    /**
     * 执行单轮（启动事务 + 事件流 + 终态事务编排）。
     * <pre>{@code
     *  executeRound
     *    ├─ requireSession（不存在/已终止 → 快速失败）
     *    ├─ [启动事务] CAS(IDLE→RUNNING) + round/根 span + beginRound(DB max 抬升序号)
     *    ├─ transitionState(RUNNING)（内存状态机，与 DB RUNNING 双轨）
     *    ├─ persistAndBroadcast(run_start)
     *    └─ execution.submit(runAgent)   // 进程内单执行串行守卫
     *          └─ 事件流阶段（见 runEventStream）+ 终态唯一出口（见 finalizeRoundAndComplete / finalizeFailedRound）
     * }</pre>
     */
    private void executeRound(SendMessageCommand command) {
        AgentSession session = requireSession(command.sessionId());
        if (session.status() == AgentSessionStatus.TERMINATED) {
            throw new DeepDataAgentException(DEEP_AGENT_SESSION_NOT_FOUND + ": 会话已终止");
        }

        // ===== 启动事务：CAS 独占抢占置 RUNNING + 创建执行轮次 + 根 span + 事件流状态聚合（任一失败整体回滚） =====
        // 会话级聚合（逻辑线程组）首次 getOrCreate；事件流 seq 以 DB 最大序号抬升一次，
        // 全轮次（含 run_start/终态/session_status）由会话级计数器内存分配，DB 唯一索引兜底
        ExecutionContext context = transactionTemplate.execute(status -> {
            if (!sessionRepository.tryMarkRunning(command.sessionId())) {
                throw new DeepDataAgentException(DEEP_AGENT_SESSION_BUSY + ": 会话正在执行中，请稍后再试");
            }
            String runId = command.runId() != null ? command.runId()
                    : UUID.randomUUID().toString().replace("-", "");
            int roundNumber = roundRepository.nextRoundNumber(command.sessionId());
            ExecutionRound round = roundRepository.save(
                    ExecutionRound.create(command.sessionId(), runId, roundNumber, command.message()));
            String traceId = UUID.randomUUID().toString().replace("-", "");
            RunTrace root = runTraceRepository.save(RunTrace.createRoot(traceId, round.roundId(), ROOT_SPAN_NAME));
            AgentSessionContext sessionContext = sessionRegistry.getOrCreate(session);
            AgentRunState runState = sessionContext.beginRound(chatEventRepository.nextSequenceNum(command.sessionId()));
            return new ExecutionContext(sessionContext, round, runState, traceId, root);
        });
        if (context == null) {
            throw new DeepDataAgentException(DEEP_AGENT_RUN_ERROR + ": 创建执行轮次失败");
        }
        ExecutionRound round = context.round();
        String sessionId = context.session().sessionId();
        String roundId = context.round().roundId();

        // 内存状态机：IDLE → RUNNING
        context.sessionContext().transitionState(SessionState.RUNNING);

        // run_start（应用层合成，含 round_id / run_id）
        persistAndBroadcast(context, roundId,
                new AssembledEvent(ChatEventType.RUN_START,
                        jsonOf(Map.of("round_id", roundId, "run_id", round.runId()))),
                context.nextSequence());
        log.info("轮次开始: sessionId={}, roundId={}, runId={}, roundNumber={}",
                sessionId, roundId, round.runId(), round.roundNumber());

        // ===== 执行层：execution.submit 收敛 agent 完整生命周期（串行守卫 + 阻塞等待 + 释放）=====
        try {
            context.sessionContext().execution().submit(execCtx -> runAgent(context, session, command.message(), execCtx));
        } catch (IllegalStateException ex) {
            // 进程内串行守卫拒绝（本应被启动事务 CAS 拦截，此处仅双保险兜底）
            log.error("在跑执行注册被拒（会话已有进程内在跑句柄）: sessionId={}, roundId={}", sessionId, roundId);
            finalizeAsFailure(context,
                    new DeepDataAgentException(DEEP_AGENT_RUN_ERROR + ": 会话已有在跑执行，拒绝并发轮次"), true);
        }
    }

    /**
     * 在 execution 提交的任务内执行 agent：装配 → 注册中断句柄 → 订阅事件流并阻塞 → 释放。
     * <pre>{@code
     *  runAgent（execution.submit 提交，独占执行槽位）
     *    ├─ build(agent)                        // 装配失败 → finalizeAsFailure(error)
     *    ├─ execCtx.activate(agent::interrupt)  // 注册中断句柄（cancel 先到时立即触发）
     *    ├─ runEventStream(...)                 // 订阅事件流 + completion.get() 阻塞
     *    ├─ catch: Interrupted → 恢复中断标记；Execution/Runtime → finalizeAsFailure
     *    └─ finally: agent.close()              // 无论成败均释放 SDK 句柄
     * }</pre>
     *
     * @param context   本轮执行现场快照
     * @param session   会话镜像（实时装配用）
     * @param userInput 用户消息
     * @param execCtx   执行控制句柄（completion 与中断注册）
     */
    private void runAgent(ExecutionContext context, AgentSession session, String userInput,
                          AgentSessionExecution.ExecutionContext execCtx) {
        BuiltAgent agent = null;
        boolean built = false;
        try {
            agent = agentFactory.build(runtimeAgentAssemblyResolver.assemble(session));
            built = true;
            execCtx.activate(agent::interrupt);
            runEventStream(context, agent, userInput, execCtx.completion());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("轮次等待终态被中断: sessionId={}, roundId={}", session.sessionId(), context.round().roundId());
        } catch (ExecutionException ex) {
            log.error("Agent 事件流终态异常: sessionId={}, roundId={}", session.sessionId(), context.round().roundId(), ex.getCause());
            finalizeAsFailure(context, ex.getCause(), false);
        } catch (RuntimeException ex) {
            log.error("Agent 执行异常: sessionId={}, roundId={}", session.sessionId(), context.round().roundId(), ex);
            finalizeAsFailure(context, ex, !built);
        } finally {
            if (agent != null) {
                try {
                    agent.close();
                } catch (RuntimeException ex) {
                    log.warn("Agent 释放异常: sessionId={}, roundId={}", session.sessionId(), context.round().roundId(), ex);
                }
            }
        }
    }

    /**
     * 事件流阶段（应用层编排）：订阅冷流并在 doOnNext 内逐信号持久化 / 广播 / span / 终态判定，
     * 经 {@code completion().get()} 在虚拟线程阻塞等待终态。
     * <pre>{@code
     *  runEventStream（运行于虚拟线程）
     *    streamEvents(冷流) ─ publishOn(blockingScheduler) ─▶ doOnNext(handleSignal)
     *        ├─ 逐信号：seq 分配 / payload 装配 / 落库 / connection().push(SSE) / span 追踪
     *        └─ AGENT_END → finalizeRoundAndComplete（提前终态，complete completion）
     *    subscribe(onNext, onError=onStreamError, onComplete=onStreamComplete)
     *    completion.get()  // 阻塞等待终态；虚拟线程阻塞时让出载体线程（M:N）
     * }</pre>
     *
     * @param context    本轮执行现场快照
     * @param agent      已装配 agent 句柄
     * @param userInput  用户消息
     * @param completion 执行完成信号（由 execution 槽位提供，终态路径 complete）
     */
    private void runEventStream(ExecutionContext context, BuiltAgent agent, String userInput,
                                CompletableFuture<Void> completion)
            throws InterruptedException, ExecutionException {
        agentRunExecutor.streamEvents(agent, userInput, context.session().sessionId(), context.session().userId())
                .publishOn(blockingScheduler)
                .doOnNext(signal -> handleSignal(signal, context, completion))
                .subscribe(
                        ignored -> {},
                        error -> onStreamError(context, error, completion),
                        () -> onStreamComplete(context, completion));
        completion.get();
    }

    /**
     * 单个流信号编排：状态累积 → span（llm.call / tool.call）→ payload 装配 → 落库 + 广播；
     * AGENT_END 触发终态提前（EXCEED_MAX_ITERS 后 defer 到 onComplete）。
     */
    private void handleSignal(AgentStreamSignal signal, ExecutionContext context,
                              CompletableFuture<Void> completion) {
        AgentRunState runState = context.runState();
        String sessionId = context.session().sessionId();
        String roundId = context.round().roundId();
        log.debug("信号处理: sessionId={}, roundId={}, type={}, toolCallId={}, blockId={}",
                sessionId, roundId, signal.type(), signal.toolCallId(), signal.blockId());
        switch (signal.type()) {
            case TEXT_DELTA -> {
                runState.appendOutput(signal.text());
                persistAndBroadcast(context, roundId,
                        payloadAssembler.textDelta(signal.blockId(), signal.text()),
                        context.nextSequence());
            }
            case AGENT_RESULT -> runState.setFinalResultText(signal.resultText());
            case EXCEED_MAX_ITERS -> runState.markExceedMaxIters();
            case MODEL_CALL_START -> runState.markModelCallStart();
            case MODEL_CALL_END -> {
                // llm.call 为 span 数据，不入 chat 事件流
                OffsetDateTime start = runState.takeModelSpanStart();
                if (start != null) {
                    persistSpan(context, new AgentRunExecutor.TraceSpanDraft(
                            "llm.call", SpanKind.CLIENT, null, null, null,
                            start, now(), SpanStatus.OK,
                            signal.inputTokens(), signal.outputTokens(), signal.modelName(), BigDecimal.ZERO));
                }
            }
            case TOOL_CALL_START -> {
                runState.startToolCall(signal.toolCallId(), signal.toolName());
                persistAndBroadcast(context, roundId,
                        payloadAssembler.toolCallStart(signal.toolCallId(), signal.toolName()),
                        context.nextSequence());
            }
            case TOOL_CALL_DELTA -> runState.appendToolArgs(signal.toolCallId(), signal.text());
            case TOOL_CALL_END -> {
                // takeToolArgs 一次性完成「取出聚合入参 + 留存原始快照供 tool.call span」
                String argsJson = runState.takeToolArgs(signal.toolCallId());
                persistAndBroadcast(context, roundId,
                        payloadAssembler.toolCallEnd(signal.toolCallId(), signal.toolName(), argsJson),
                        context.nextSequence());
            }
            case TOOL_RESULT_TEXT_DELTA -> {
                // head 实时窗口：head 未满则实时发布，head 已满后返回空串丢弃（不落库不发布）
                String head = runState.appendToolResult(signal.text());
                if (!head.isEmpty()) {
                    persistAndBroadcast(context, roundId,
                            payloadAssembler.toolResultDelta(signal.toolCallId(), signal.toolName(), head),
                            context.nextSequence());
                }
            }
            case TOOL_RESULT_END -> {
                // head+tail 截断补发（含省略标记 + 截断通知），随后完成 tool.call span
                String tail = runState.endToolResult();
                persistAndBroadcast(context, roundId,
                        payloadAssembler.toolResultEnd(signal.toolCallId(), signal.toolName(),
                                tail != null ? tail : "", runState.toolResultTruncated()),
                        context.nextSequence());
                // 与 MODEL_CALL_END 相同保护：异常信号序（缺 TOOL_CALL_START 起点）时不伪造时间，跳过 span
                OffsetDateTime toolStart = runState.takeToolSpanStart(signal.toolCallId());
                if (toolStart != null) {
                    persistSpan(context, new AgentRunExecutor.TraceSpanDraft(
                            "tool.call", SpanKind.CLIENT,
                            runState.toolName(signal.toolCallId(), signal.toolName()),
                            PayloadSanitizer.sanitize(runState.takeToolInput(signal.toolCallId())),
                            runState.toolResultHeadText(),
                            toolStart, now(),
                            "error".equalsIgnoreCase(signal.toolState()) ? SpanStatus.ERROR : SpanStatus.OK));
                }
            }
            case AGENT_END -> {
                // SDK 终态（end_turn）：除 EXCEED_MAX_ITERS 后的 defer 外提前完成终态序列；
                // SDK 终态不落库不发布（终态事件由终态唯一出口合成）
                if (!runState.exceededMaxIters()) {
                    finalizeRoundAndComplete(context, completion);
                }
            }
            case THINKING_DELTA -> persistAndBroadcast(context, roundId,
                    payloadAssembler.thinkingDelta(signal.blockId(), signal.text()),
                    context.nextSequence());
            case THINKING_END -> persistAndBroadcast(context, roundId,
                    payloadAssembler.thinkingEnd(signal.blockId()), context.nextSequence());
            case TEXT_END -> persistAndBroadcast(context, roundId,
                    payloadAssembler.textEnd(signal.blockId()), context.nextSequence());
            // START 无业务语义，忽略
            default -> throw new IllegalArgumentException("Unexpected value: " + signal.type());
        }
    }

    /**
     * 流正常结束（onComplete）：EXCEED_MAX_ITERS 的 defer 终态、AGENT_END 缺失兜底、
     * 以及断连 / 终止中断后 SDK 正常收流的收尾入口。
     */
    private void onStreamComplete(ExecutionContext context, CompletableFuture<Void> completion) {
        String sessionId = context.session().sessionId();
        String roundId = context.round().roundId();
        if (context.sessionContext().state() == SessionState.INTERRUPTED) {
            // 断连 / 终止已显式标记中断：走中断终态（不发布 idle 的 stop）
            log.warn("断连/终止后 SDK 正常收流，执行置中断: sessionId={}, roundId={}", sessionId, roundId);
            finalizeFailedRound(context, true, "执行被中断");
            completion.complete(null);
            return;
        }
        log.info("事件流正常收流完成: sessionId={}, roundId={}", sessionId, roundId);
        finalizeRoundAndComplete(context, completion);
    }

    /**
     * 流异常结束（onError）：依显式中断状态区分中断与执行错误后统一走失败终态。
     */
    private void onStreamError(ExecutionContext context, Throwable error,
                               CompletableFuture<Void> completion) {
        String sessionId = context.session().sessionId();
        String roundId = context.round().roundId();
        boolean interrupted = context.sessionContext().state() == SessionState.INTERRUPTED;
        log.error("Agent 事件流异常: sessionId={}, roundId={}, interrupted={}",
                sessionId, roundId, interrupted, error);
        finalizeFailedRound(context, interrupted,
                blankToDefault(error.getMessage(), interrupted ? "执行被中断" : "agent 执行失败"));
        completion.complete(null);
    }

    /**
     * 正常终态唯一入口（AGENT_END 提前 / onComplete 兜底共用）：
     * {@code RUNNING → DONE} 状态机 CAS 原子守卫保证只终态化一次，{@code stop_reason}
     * 由 {@code exceedMaxIters} 派生（stop / max_iterations）。
     */
    private void finalizeRoundAndComplete(ExecutionContext context, CompletableFuture<Void> completion) {
        AgentSessionContext sessionContext = context.sessionContext();
        if (sessionContext.tryTransitionState(SessionState.DONE)) {
            AgentRunState runState = context.runState();
            String stopReason = runState.exceededMaxIters() ? STOP_REASON_MAX_ITERATIONS : STOP_REASON_STOP;
            finalizeRound(context, SessionState.DONE.toRoundStatus(), runState.output(), stopReason,
                    List.of(new AssembledEvent(ChatEventType.RUN_END,
                            jsonOf(Map.of("stop_reason", stopReason)))));
            sessionContext.transitionState(SessionState.IDLE);
        }
        completion.complete(null);
    }

    /**
     * 兜底失败终态（agent 构建 / 注册 / 终态化回调抛出的异常路径）。
     * <p>事件流启动前的失败（构建 / 注册）一律推导为执行错误；事件流启动后的失败
     * 依显式中断状态推导，避免把系统故障误报为中断。</p>
     *
     * @param setupFailure 是否发生在事件流启动前（构建 / 注册失败 → error；已进入事件流 → 依中断状态推导）
     */
    private void finalizeAsFailure(ExecutionContext context, Throwable ex, boolean setupFailure) {
        boolean interrupted = !setupFailure
                && context.sessionContext().state() == SessionState.INTERRUPTED;
        finalizeFailedRound(context, interrupted,
                blankToDefault(ex.getMessage(), interrupted ? "执行被中断" : "agent 执行失败"));
    }

    /**
     * 失败终态统一出口（onComplete 断连 / onError / 构建注册失败共用）：
     * round 置 FAILED + 双事件（RUN_ERROR / ERROR）经终态唯一出口落库 + 会话回 IDLE。
     * <p>中断路径（{@code interrupted=true}）状态已由取消路径置为 {@code INTERRUPTED}；
     * 错误路径经 {@code RUNNING → ERROR} 状态机 CAS 单赢家守卫。</p>
     *
     * @param interrupted 是否因中断退出（stop_reason=interrupted）而非执行错误（error）
     */
    private void finalizeFailedRound(ExecutionContext context, boolean interrupted, String message) {
        AgentSessionContext sessionContext = context.sessionContext();
        SessionState terminal = interrupted ? SessionState.INTERRUPTED : SessionState.ERROR;
        if (interrupted) {
            // 中断路径：状态已由 cancel 置为 INTERRUPTED；非此状态即已被其他路径终态化
            if (sessionContext.state() != SessionState.INTERRUPTED) {
                return;
            }
        } else if (!sessionContext.tryTransitionState(SessionState.ERROR)) {
            // 错误路径：RUNNING → ERROR 单赢家守卫，失败即已有终态
            return;
        }
        String stopReason = interrupted ? STOP_REASON_INTERRUPTED : STOP_REASON_ERROR;
        String sanitized = PayloadSanitizer.sanitize(blankToDefault(message, "agent 执行失败"));
        finalizeRound(context, terminal.toRoundStatus(), "", stopReason,
                List.of(new AssembledEvent(ChatEventType.RUN_ERROR,
                                jsonOf(Map.of("stop_reason", stopReason, "message", sanitized))),
                        new AssembledEvent(ChatEventType.ERROR,
                                jsonOf(Map.of("code", DEEP_AGENT_RUN_ERROR, "message", sanitized)))));
        sessionContext.transitionState(SessionState.IDLE);
    }

    /**
     * 终态唯一出口：终态事务内 round 终态 + 终态事件落库 + 会话回 IDLE + 根 span 结束；
     * 提交后仅合成广播一发 session_status（流内 SDK 终态不再单独广播）。
     */
    private void finalizeRound(ExecutionContext context, RoundStatus finalStatus,
                               String finalOutput, String stopReason,
                               List<AssembledEvent> terminalEvents) {
        ExecutionRound round = context.round();
        String sessionId = round.sessionId();
        String roundId = round.roundId();
        Integer idleRows = transactionTemplate.execute(status -> {
            roundRepository.save(round.complete(finalOutput, finalStatus));
            // 终态事件随轮次终态同事务落库：回放必见终态，stream 端不再有 RUN_END 先行窗口
            for (AssembledEvent event : terminalEvents) {
                chatEventRepository.save(ChatEvent.create(
                        sessionId, roundId, event.type(), event.payloadJson(),
                        context.nextSequence()));
            }
            int markIdle = sessionRepository.markIdle(sessionId);
            sessionRepository.touchLastActive(sessionId);
            runTraceRepository.save(context.root().finish(now()));
            return markIdle;
        });
        if (idleRows == null || idleRows <= 0) {
            // 会话已非 RUNNING（如被终止不可复活）：不广播 idle 终态，避免与 TERMINATED 语义冲突
            log.info("会话终态回 IDLE 失败（可能已被终止），跳过 session_status 广播: sessionId={}",
                    sessionId);
            return;
        }
        persistAndBroadcast(context, roundId,
                new AssembledEvent(ChatEventType.SESSION_STATUS,
                        jsonOf(Map.of("status", AgentSessionStatus.IDLE.name(), "stop_reason", stopReason))),
                context.nextSequence());
        log.info("轮次终态落库完成: sessionId={}, roundId={}, status={}, stopReason={}",
                sessionId, roundId, finalStatus, stopReason);
    }

    /**
     * 事件持久化与广播（流内事件与合成事件共用）：seq 统一由
     * {@link AgentSessionContext#nextSequence()} 会话级计数器分配（DB 唯一索引兜底），
     * 落库失败记录 ERROR 但不中断事件流；广播失败记录 WARN（事件已落库可回放兜底）。
     */
    private void persistAndBroadcast(ExecutionContext context, String roundId, AssembledEvent assembled, long sequenceNum) {
        String sessionId = context.session().sessionId();
        pushQuietly(context.sessionContext(), saveQuietly(sessionId, roundId, assembled.type(), assembled.payloadJson(), sequenceNum));
    }

    /** 尝试落库：失败记录 ERROR 并返回 null（不中断调用方，由断线重连回放兜底）。 */
    private ChatEvent saveQuietly(String sessionId, String roundId, ChatEventType type, String payload, long sequenceNum) {
        try {
            return chatEventRepository.save(ChatEvent.create(sessionId, roundId, type, payload, sequenceNum));
        } catch (RuntimeException ex) {
            log.error("聊天事件落库失败: sessionId={}, eventType={}", sessionId, type, ex);
            return null;
        }
    }

    /** 尝试向连接层推送：领域事件经连接句柄广播，协议转换在基础设施；失败记录 WARN（事件已落库可回放）。 */
    private void pushQuietly(AgentSessionContext sessionContext, ChatEvent event) {
        if (event == null) {
            return;
        }
        try {
            sessionContext.connection().push(event);
        } catch (RuntimeException ex) {
            log.warn("SSE 广播失败: sessionId={}, eventType={}", event.sessionId(), event.eventType(), ex);
        }
    }

    /**
     * span 落库（独立事务导出子 span，工具入参/出参已由应用层脱敏）。
     */
    private void persistSpan(ExecutionContext context, AgentRunExecutor.TraceSpanDraft draft) {
        try {
            String traceId = context.traceId();
            RunTrace root = context.root();
            String roundId = context.round().roundId();
            transactionTemplate.executeWithoutResult(status -> {
                RunTrace child = RunTrace.createChild(
                        traceId, root.spanId(), roundId, draft.spanName(), draft.toolName(), draft.startTime());
                runTraceRepository.save(withSpanDraft(child.finish(draft.endTime()), draft));
            });
        } catch (RuntimeException ex) {
            log.error("tracing span 落库失败: roundId={}, spanName={}", context.round().roundId(), draft.spanName(), ex);
        }
    }

    /**
     * 按 ID 查询会话，不存在时抛业务异常（会话相关用例的统一前置校验）。
     */
    private AgentSession requireSession(String sessionId) {
        return sessionRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new DeepDataAgentException(DEEP_AGENT_SESSION_NOT_FOUND + ": 会话不存在"));
    }

    /**
     * 将 span 草案入参/出参/状态合并到已终态的 span 上（record 不可变，经 restore 重建）。
     */
    private RunTrace withSpanDraft(RunTrace span, AgentRunExecutor.TraceSpanDraft draft) {
        return RunTrace.restore(
                span.id(), span.traceId(), span.spanId(), span.parentSpanId(), span.roundId(), span.spanName(),
                span.spanKind(), draft.status() != null ? draft.status() : span.status(),
                span.startTime(), span.endTime(), span.durationMs(),
                span.inputTokens(), span.outputTokens(), span.modelName(), span.estimatedCost(),
                draft.toolName() != null ? draft.toolName() : span.toolName(),
                draft.toolInput() != null ? draft.toolInput() : span.toolInput(),
                draft.toolOutput() != null ? draft.toolOutput() : span.toolOutput(),
                span.attributes(), span.createdAt(), span.updatedAt(), span.createdBy(), span.updatedBy()
        );
    }

    /** 序列化事件 payload（小对象直传，失败属编程错误直接上抛）。 */
    private String jsonOf(Map<String, Object> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception ex) {
            throw new IllegalStateException("事件 payload 序列化失败", ex);
        }
    }

    /** 空串兜底：值为 null 或空白时返回 fallback。 */
    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /** 当前时间（全链路统一 Asia/Shanghai 时区）。 */
    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
    }

    /**
     * 轮次执行上下文：启动事务产出的聚合参数（会话级聚合 / 轮次 / 流状态 / 链路），
     * 贯穿事件流编排与终态路径，取代五参串联调用链。
     *
     * @param sessionContext 会话级聚合（逻辑线程组，跨轮常驻）
     * @param round          本轮轮次
     * @param runState       本轮事件流状态（beginRound 产出）
     * @param traceId        本轮根追踪 ID
     * @param root           本轮根 span
     */
    private record ExecutionContext(AgentSessionContext sessionContext, ExecutionRound round, AgentRunState runState,
                                    String traceId, RunTrace root) {
        /** 会话镜像（身份信息 / TERMINATED 判定）。 */
        AgentSession session() {
            return sessionContext.session();
        }

        /** 会话级事件序列号（跨轮计数器，本会话单调递增）。 */
        long nextSequence() {
            return sessionContext.nextSequence();
        }
    }
}