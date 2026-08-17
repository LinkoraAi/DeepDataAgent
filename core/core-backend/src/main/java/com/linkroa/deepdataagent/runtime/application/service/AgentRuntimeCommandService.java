package com.linkroa.deepdataagent.runtime.application.service;

import com.linkroa.deepdataagent.runtime.application.assembler.AgentRuntimeAssembler;
import com.linkroa.deepdataagent.runtime.application.assembler.ChatEventPayloadAssembler;
import com.linkroa.deepdataagent.runtime.application.assembler.ChatEventPayloadAssembler.AssembledEvent;
import com.linkroa.deepdataagent.runtime.application.assembler.SseEventEnvelopeAssembler;
import com.linkroa.deepdataagent.runtime.application.command.CreateSessionCommand;
import com.linkroa.deepdataagent.runtime.application.command.SendMessageCommand;
import com.linkroa.deepdataagent.runtime.application.command.TerminateSessionCommand;
import com.linkroa.deepdataagent.runtime.application.port.EventBroadcaster;
import com.linkroa.deepdataagent.runtime.domain.event.AgentStreamSignal;
import com.linkroa.deepdataagent.runtime.domain.factory.AgentFactoryPort;
import com.linkroa.deepdataagent.runtime.domain.factory.BuiltAgent;
import com.linkroa.deepdataagent.runtime.domain.model.AgentRunState;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSession;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSessionContext;
import com.linkroa.deepdataagent.runtime.domain.model.ChatEvent;
import com.linkroa.deepdataagent.runtime.domain.model.ExecutionRound;
import com.linkroa.deepdataagent.runtime.domain.model.RunTrace;
import com.linkroa.deepdataagent.runtime.domain.model.enums.AgentSessionStatus;
import com.linkroa.deepdataagent.runtime.domain.model.enums.ChatEventType;
import com.linkroa.deepdataagent.runtime.domain.model.enums.RoundStatus;
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
 * <p>全部阻塞型执行经虚拟线程调度器托管，web 请求线程永不阻塞（解 Tomcat 占用）；
 * 只读查询用例见 {@link AgentRuntimeQueryService}（读写职责拆分）。</p>
 */
@Service
public class AgentRuntimeCommandService {

    private static final Logger log = LoggerFactory.getLogger(AgentRuntimeCommandService.class);

    private static final String DEEP_AGENT_SESSION_NOT_FOUND = "DEEP_AGENT_SESSION_NOT_FOUND";
    private static final String DEEP_AGENT_SESSION_BUSY = "DEEP_AGENT_SESSION_BUSY";
    private static final String DEEP_AGENT_RUN_ERROR = "DEEP_AGENT_RUN_ERROR";
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
    private EventBroadcaster eventBroadcaster;
    @Resource
    private SseEventEnvelopeAssembler sseEventEnvelopeAssembler;
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
    @Resource(name = "agentVirtualScheduler")
    private Scheduler virtualScheduler;
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
     */
    public void terminateSession(TerminateSessionCommand command) {
        AgentSession session = requireSession(command.sessionId());
        transactionTemplate.executeWithoutResult(status ->
                sessionRepository.updateStatus(command.sessionId(), AgentSessionStatus.TERMINATED));
        // 断连/终止语义：幂等中断在跑 agent（当前轮次将经终态路径收尾并恢复状态守卫感知）
        sessionRegistry.get(command.sessionId()).ifPresent(AgentSessionContext::interruptActiveRun);
        eventBroadcaster.complete(command.sessionId());
    }

    // ==================== 消息发送（事务 + 事件流编排） ====================

    /**
     * 同步发送消息并消费整轮 SSE 事件流（阻塞至轮次终态）。
     * <p>内部供测试与同步调用方使用；HTTP 入口（SSE 直连 / 202）一律走
     * {@link #sendMessageAsync}，由虚拟线程执行器托管。</p>
     *
     * @return 本轮 round_id / run_id / stop_reason
     */
    public SendMessageResult sendMessage(SendMessageCommand command) {
        return executeRound(command);
    }

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
     */
    private SendMessageResult executeRound(SendMessageCommand command) {
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

        // run_start（应用层合成，含 round_id / run_id）
        persistAndBroadcast(sessionId, roundId,
                new AssembledEvent(ChatEventType.RUN_START,
                        jsonOf(Map.of("round_id", roundId, "run_id", round.runId()))),
                context.nextSequence());
        log.info("轮次开始: sessionId={}, roundId={}, runId={}, roundNumber={}",
                sessionId, roundId, round.runId(), round.roundNumber());
        BuiltAgent agent = null;
        boolean registered = false;
        try {
            // 实时装配：领域规格（无全局回退）+ 模型访问配置（凭证/API 端点仅注入工厂装配）
            RuntimeAgentAssemblyResolver.AssembledAssembly assembly = runtimeAgentAssemblyResolver.assemble(session);
            agent = agentFactory.build(assembly.spec(), assembly.modelAccess());
            // 在跑执行注册（会话级聚合内的 CAS 串行守卫 + 断连中断入口）
            registered = context.sessionContext().registerActiveRun(roundId, agent);
            if (!registered) {
                // 本应被启动事务的 CAS 独占抢占拦截；进程内守卫再失败说明会话有陈旧在跑句柄，
                // 快速失败而非不带守卫裸跑（避免失去断连中断入口 / 误中断他轮句柄）
                log.error("在跑执行注册被拒（会话已有进程内在跑句柄）: sessionId={}, roundId={}", sessionId, roundId);
                return finalizeAsFailure(context,
                        new DeepDataAgentException(DEEP_AGENT_RUN_ERROR + ": 会话已有在跑执行，拒绝并发轮次"), true);
            }
            // ===== 事件流阶段：应用层订阅 Flux，doOnNext 内编排持久化 / 广播 / span / 终态 =====
            return runEventStream(context, agent, command.message());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("轮次等待终态被中断: sessionId={}, roundId={}", sessionId, roundId);
            return new SendMessageResult(roundId, round.runId(), STOP_REASON_INTERRUPTED);
        } catch (ExecutionException ex) {
            // 事件流回调终态化时抛出的异常（如 DB 故障）：未经守护的失败路径兜底终态化
            log.error("Agent 事件流终态异常: sessionId={}, roundId={}", sessionId, roundId, ex.getCause());
            return finalizeAsFailure(context, ex.getCause(), false);
        } catch (RuntimeException ex) {
            // agent 构建 / 注册阶段异常（尚未进入事件流）：直接终态化为 error
            log.error("Agent 执行异常: sessionId={}, roundId={}", sessionId, roundId, ex);
            return finalizeAsFailure(context, ex, !registered);
        } finally {
            if (agent != null) {
                try {
                    agent.close();
                } catch (RuntimeException ex) {
                    log.warn("Agent 释放异常: sessionId={}, roundId={}", sessionId, roundId, ex);
                }
            }
            if (registered) {
                context.sessionContext().clearActiveRun();
            }
        }
    }

    /**
     * 事件流阶段（应用层编排）：订阅冷流并在 doOnNext 内逐信号持久化 / 广播 / span / 终态判定，
     * 经 {@code completion().get()} 在虚拟线程阻塞等待终态。
     */
    private SendMessageResult runEventStream(ExecutionContext context, BuiltAgent agent, String userInput)
            throws InterruptedException, ExecutionException {
        CompletableFuture<SendMessageResult> completion = new CompletableFuture<>();
        agentRunExecutor.streamEvents(agent, userInput, context.session().sessionId(), context.session().userId())
                .publishOn(virtualScheduler)
                .doOnNext(signal -> handleSignal(signal, context, completion))
                .subscribe(
                        ignored -> {
                        },
                        error -> onStreamError(context, error, completion),
                        () -> onStreamComplete(context, completion));
        return completion.get();
    }

    /**
     * 单个流信号编排：状态累积 → span（llm.call / tool.call）→ payload 装配 → 落库 + 广播；
     * AGENT_END 触发终态提前（EXCEED_MAX_ITERS 后 defer 到 onComplete）。
     */
    private void handleSignal(AgentStreamSignal signal, ExecutionContext context,
                              CompletableFuture<SendMessageResult> completion) {
        AgentRunState runState = context.runState();
        String sessionId = context.session().sessionId();
        String roundId = context.round().roundId();
        log.debug("信号处理: sessionId={}, roundId={}, type={}, toolCallId={}, blockId={}",
                sessionId, roundId, signal.type(), signal.toolCallId(), signal.blockId());
        switch (signal.type()) {
            case TEXT_DELTA -> {
                runState.appendOutput(signal.text());
                persistAndBroadcast(sessionId, roundId,
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
                persistAndBroadcast(sessionId, roundId,
                        payloadAssembler.toolCallStart(signal.toolCallId(), signal.toolName()),
                        context.nextSequence());
            }
            case TOOL_CALL_DELTA -> runState.appendToolArgs(signal.toolCallId(), signal.text());
            case TOOL_CALL_END -> {
                // takeToolArgs 一次性完成「取出聚合入参 + 留存原始快照供 tool.call span」
                String argsJson = runState.takeToolArgs(signal.toolCallId());
                persistAndBroadcast(sessionId, roundId,
                        payloadAssembler.toolCallEnd(signal.toolCallId(), signal.toolName(), argsJson),
                        context.nextSequence());
            }
            case TOOL_RESULT_TEXT_DELTA -> {
                // head 实时窗口：head 未满则实时发布，head 已满后返回空串丢弃（不落库不发布）
                String head = runState.appendToolResult(signal.text());
                if (!head.isEmpty()) {
                    persistAndBroadcast(sessionId, roundId,
                            payloadAssembler.toolResultDelta(signal.toolCallId(), signal.toolName(), head),
                            context.nextSequence());
                }
            }
            case TOOL_RESULT_END -> {
                // head+tail 截断补发（含省略标记 + 截断通知），随后完成 tool.call span
                String tail = runState.endToolResult();
                persistAndBroadcast(sessionId, roundId,
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
            case THINKING_DELTA -> persistAndBroadcast(sessionId, roundId,
                    payloadAssembler.thinkingDelta(signal.blockId(), signal.text()),
                    context.nextSequence());
            case THINKING_END -> persistAndBroadcast(sessionId, roundId,
                    payloadAssembler.thinkingEnd(signal.blockId()), context.nextSequence());
            case TEXT_END -> persistAndBroadcast(sessionId, roundId,
                    payloadAssembler.textEnd(signal.blockId()), context.nextSequence());
            // START 无业务语义，忽略
        }
    }

    /**
     * 流正常结束（onComplete）：EXCEED_MAX_ITERS 的 defer 终态、AGENT_END 缺失兜底、
     * 断连中断后 SDK 正常收流（注册表已移除 → interrupted）。
     */
    private void onStreamComplete(ExecutionContext context, CompletableFuture<SendMessageResult> completion) {
        AgentRunState runState = context.runState();
        ExecutionRound round = context.round();
        String sessionId = context.session().sessionId();
        String roundId = round.roundId();
        if (runState.isFinalized()) {
            // 已由 AGENT_END 提前终态化：仅补发结果
            log.info("事件流收流完成（AGENT_END 已提前终态）: sessionId={}, roundId={}, stopReason={}",
                    sessionId, roundId, runState.stopReason());
            completion.complete(new SendMessageResult(roundId, round.runId(), runState.stopReason()));
            return;
        }
        if (!context.sessionContext().activeRun().isPresent()) {
            // 断连 / 终止后 SDK 正常收流：走中断终态（不发布 idle 的 stop）
            log.warn("断连/终止后 SDK 正常收流，执行置中断: sessionId={}, roundId={}", sessionId, roundId);
            completion.complete(finalizeFailedRound(context, true, "执行被中断"));
            return;
        }
        log.info("事件流正常收流完成: sessionId={}, roundId={}, stopReason={}",
                sessionId, roundId, runState.stopReason());
        finalizeRoundAndComplete(context, completion);
    }

    /**
     * 流异常结束（onError）：按「注册表是否已移除」区分中断与执行错误后统一走失败终态。
     */
    private void onStreamError(ExecutionContext context, Throwable error,
                               CompletableFuture<SendMessageResult> completion) {
        AgentRunState runState = context.runState();
        ExecutionRound round = context.round();
        String sessionId = context.session().sessionId();
        String roundId = round.roundId();
        if (runState.isFinalized()) {
            // 终态已生效后的杂散异常（如终态化事务失败后 doOnNext 传播）：终态不再重复，
            // 但必须记录异常详情供排查（此前该路径会静默吞掉终态落库失败）
            log.error("事件流终态后异常（终态已生效，仅记录不重复终态化）: sessionId={}, roundId={}",
                    sessionId, roundId, error);
            completion.complete(new SendMessageResult(roundId, round.runId(), runState.stopReason()));
            return;
        }
        log.error("Agent 事件流异常: sessionId={}, roundId={}", sessionId, roundId, error);
        boolean interrupted = !context.sessionContext().activeRun().isPresent();
        completion.complete(finalizeFailedRound(context, interrupted,
                blankToDefault(error.getMessage(), interrupted ? "执行被中断" : "agent 执行失败")));
    }

    /**
     * 正常终态唯一入口（AGENT_END 提前 / onComplete 兜底共用）：
     * {@link AgentRunState#tryFinalized()} 原子守卫保证只终态化一次。
     */
    private void finalizeRoundAndComplete(ExecutionContext context, CompletableFuture<SendMessageResult> completion) {
        AgentRunState runState = context.runState();
        ExecutionRound round = context.round();
        String stopReason = runState.stopReason(); // stop / max_iterations
        if (runState.tryFinalized()) {
            finalizeRound(context, RoundStatus.COMPLETED, runState.output(), stopReason,
                    List.of(new AssembledEvent(ChatEventType.RUN_END,
                            jsonOf(Map.of("stop_reason", stopReason)))));
        }
        completion.complete(new SendMessageResult(round.roundId(), round.runId(), stopReason));
    }

    /**
     * 兜底失败终态（agent 构建 / 注册 / 终态化回调抛出的异常路径）。
     * <p>中断只可能发生在事件流启动<b>之后</b>（断连 / 终止移除在跑句柄）；事件流启动前
     * 的失败（构建 / 注册）一律推导为执行错误，避免把系统故障误报为中断。</p>
     *
     * @param setupFailure 是否发生在事件流启动前（构建 / 注册失败 → error；已进入事件流 → 依在跑句柄推导）
     */
    private SendMessageResult finalizeAsFailure(ExecutionContext context, Throwable ex, boolean setupFailure) {
        AgentRunState runState = context.runState();
        ExecutionRound round = context.round();
        if (runState.isFinalized()) {
            // 终于态已尝试（如终态化事务内部失败）：不再重复终态化，避免重复事件
            return new SendMessageResult(round.roundId(), round.runId(), STOP_REASON_ERROR);
        }
        boolean interrupted = !setupFailure && !context.sessionContext().activeRun().isPresent();
        return finalizeFailedRound(context, interrupted,
                blankToDefault(ex.getMessage(), interrupted ? "执行被中断" : "agent 执行失败"));
    }

    /**
     * 失败终态统一出口（onComplete 断连 / onError / 构建注册失败共用）：
     * round 置 FAILED + 双事件（RUN_ERROR / ERROR）经终态唯一出口落库 + 会话回 IDLE。
     *
     * @param interrupted 是否因中断退出（stop_reason=interrupted）而非执行错误（error）
     * @return 本轮发送结果（含推导出的 stop_reason）
     */
    private SendMessageResult finalizeFailedRound(ExecutionContext context, boolean interrupted, String message) {
        String stopReason = interrupted ? STOP_REASON_INTERRUPTED : STOP_REASON_ERROR;
        String sanitized = PayloadSanitizer.sanitize(blankToDefault(message, "agent 执行失败"));
        finalizeRound(context, RoundStatus.FAILED, "", stopReason,
                List.of(new AssembledEvent(ChatEventType.RUN_ERROR,
                                jsonOf(Map.of("stop_reason", stopReason, "message", sanitized))),
                        new AssembledEvent(ChatEventType.ERROR,
                                jsonOf(Map.of("code", DEEP_AGENT_RUN_ERROR, "message", sanitized)))));
        ExecutionRound round = context.round();
        return new SendMessageResult(round.roundId(), round.runId(), stopReason);
    }

    /**
     * 终态唯一出口：终态事务内 round 终态 + 终态事件落库 + 会话回 IDLE + 根 span 结束；
     * 提交后仅合成广播一发 session_status（流内 SDK 终态不再单独广播）。
     */
    private void finalizeRound(ExecutionContext context, RoundStatus finalStatus,
                               String finalOutput, String stopReason,
                               List<AssembledEvent> terminalEvents) {
        AgentRunState runState = context.runState();
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
        persistAndBroadcast(sessionId, roundId,
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
    private void persistAndBroadcast(String sessionId, String roundId, AssembledEvent assembled, long sequenceNum) {
        broadcastQuietly(sessionId, saveQuietly(sessionId, roundId, assembled.type(), assembled.payloadJson(), sequenceNum));
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

    /** 尝试 SSE 广播：先装配对外信封再广播，失败记录 WARN（事件已落库，客户端重连可回放）。 */
    private void broadcastQuietly(String sessionId, ChatEvent event) {
        if (event == null) {
            return;
        }
        try {
            eventBroadcaster.broadcast(sessionId, sseEventEnvelopeAssembler.toEnvelope(event));
        } catch (RuntimeException ex) {
            log.warn("SSE 广播失败: sessionId={}, eventType={}", sessionId, event.eventType(), ex);
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
     * 按 ID 校验轮次存在，不存在时抛业务异常（轮次相关用例的统一前置校验）。
     */
    private void requireRound(String roundId) {
        roundRepository.findByRoundId(roundId)
                .orElseThrow(() -> new DeepDataAgentException("DEEP_AGENT_ROUND_NOT_FOUND: 轮次不存在"));
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
     * 消息发送结果。
     *
     * @param roundId    本轮轮次 ID
     * @param runId      本轮 run ID（OpenAPI 层语义）
     * @param stopReason 停止原因（stop / max_iterations / error / interrupted）
     */
    public record SendMessageResult(String roundId, String runId, String stopReason) {
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