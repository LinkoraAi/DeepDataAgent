package com.linkroa.deepdataagent.runtime.application.service.execution;

import com.linkroa.deepdataagent.runtime.application.command.InboundEventDraft;
import com.linkroa.deepdataagent.runtime.application.command.SendMessageCommand;
import com.linkroa.deepdataagent.runtime.application.port.AgentRunExecutor;
import com.linkroa.deepdataagent.runtime.application.port.ChatEventPersister;
import com.linkroa.deepdataagent.runtime.application.port.LeaseRenewalScheduler;
import com.linkroa.deepdataagent.runtime.application.port.SessionRuntimeRegistry;
import com.linkroa.deepdataagent.runtime.application.service.CoordinationLeaseService;
import com.linkroa.deepdataagent.runtime.application.service.assembly.RuntimeAgentAssemblyService;
import com.linkroa.deepdataagent.runtime.domain.event.AgentStreamSignal;
import com.linkroa.deepdataagent.runtime.domain.event.ChatEventFactory;
import com.linkroa.deepdataagent.runtime.domain.event.ChatEventFactory.AssembledEvent;
import com.linkroa.deepdataagent.runtime.domain.factory.AgentFactoryPort;
import com.linkroa.deepdataagent.runtime.domain.factory.BuiltAgent;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSession;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSessionContext;
import com.linkroa.deepdataagent.runtime.domain.model.ChatEvent;
import com.linkroa.deepdataagent.runtime.domain.model.SessionThread;
import com.linkroa.deepdataagent.runtime.domain.model.Transition;
import com.linkroa.deepdataagent.runtime.domain.model.TurnControl;
import com.linkroa.deepdataagent.runtime.domain.model.enums.AgentSessionStatus;
import com.linkroa.deepdataagent.runtime.domain.model.enums.ChatEventType;
import com.linkroa.deepdataagent.runtime.domain.model.runstate.TurnRunState;
import com.linkroa.deepdataagent.runtime.domain.repository.AgentSessionRepository;
import com.linkroa.deepdataagent.runtime.domain.repository.ChatEventRepository;
import com.linkroa.deepdataagent.runtime.domain.repository.SessionThreadRepository;
import com.linkroa.deepdataagent.runtime.domain.service.TurnResult;
import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import com.linkroa.deepdataagent.shared.exception.ResourceNotFoundException;
import com.linkroa.deepdataagent.shared.exception.SessionBusyException;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * turn 执行链服务（decompose-command-facade 3.2）：消息发送入口 → 开跑抢占 → 启动事务 →
 * 单轮执行骨架装配 → 信号分发 → 收流 / 异常收流 → 收轮的自持服务。
 * <p>方法体自原命令门面（decompose-command-facade 4.4 已物理删除）纯搬移，事务边界、异常类型与包装、日志语义、
 * 求值次序（尤其 {@code onStreamComplete} 内两源取消谓词先于内存守卫）零变化。</p>
 * <p><b>RoundSink 回接点收口</b>：本轮副作用出口（{@link #roundSink}）在本服务内装配——
 * 轮内写（落库 / 广播 / 实时帧 / delta 刷写）直连 {@link TurnEventWriter}，终局与 HITL 挂起
 * 经注入的 {@link TurnFinalizer}，载荷解析经本服务私有 {@link #parsePayload}；门面侧不再残留
 * 任何 {@code RoundSink} 匿名类，令 design D3 的依赖方向单向成立：
 * {@code TurnExecutionService → TurnFinalizer → TurnEventWriter}（收口侧不反向依赖执行侧）。</p>
 * <p><b>续跑入口</b>（{@link #withTurn} / {@link #newRoundContext} / {@link #runRound} /
 * {@link #acquireTurnLeaseOrConflict} / {@link #releaseTurnLeaseQuietly}）以 public 暴露给
 * HITL 簇（4.1 抽 {@code HumanConfirmationService} 前仍在门面内），MUST NOT 为其增设端口。</p>
 */
@Service
public class TurnExecutionService {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(TurnExecutionService.class);

    private static final String DEEP_AGENT_SESSION_NOT_FOUND = "DEEP_AGENT_SESSION_NOT_FOUND";
    private static final String DEEP_AGENT_SESSION_BUSY = "DEEP_AGENT_SESSION_BUSY";
    private static final String DEEP_AGENT_RUN_ERROR = "DEEP_AGENT_RUN_ERROR";

    /** 单轮执行模板（无状态，变更 R10）：新一轮与续跑轮共用固定骨架，差异仅流供给函数。 */
    private static final RoundExecutionTemplate roundExecutionTemplate = new RoundExecutionTemplate();

    /** 信号策略注册表（无状态，变更 R11）：信号类型 → 策略的显式 Map，跨会话 / 跨轮次复用。 */
    private static final SignalHandlerRegistry signalHandlerRegistry = new SignalHandlerRegistry();

    @Resource
    private AgentSessionRepository sessionRepository;
    @Resource
    private ChatEventRepository chatEventRepository;
    @Resource
    private AgentFactoryPort agentFactory;
    @Resource
    private AgentRunExecutor agentRunExecutor;
    @Resource
    private RuntimeAgentAssemblyService runtimeAgentAssemblyService;
    @Resource
    private SessionRuntimeRegistry sessionRegistry;
    @Resource
    private TransactionTemplate transactionTemplate;
    /** 落库端口：仅用于开跑清毒（design D1，旧轮故障不继承到新一轮）。 */
    @Resource
    private ChatEventPersister chatEventPersister;
    @Resource
    private CoordinationLeaseService coordinationLeaseService;
    @Resource(name = "agentVirtualExecutor")
    private Executor virtualExecutor;
    @Resource(name = "agentBlockingScheduler")
    private Scheduler blockingScheduler;
    /** turn 租约定时续约调度端口：轮次生命周期内 TTL/3 周期续约，覆盖长静默工具调用窗口；
     *  承载形态（平台池 / 每轮虚拟线程）经 {@code app.coordination.lease-renewal} 二选一装配。 */
    @Resource
    private LeaseRenewalScheduler leaseRenewalScheduler;
    @Resource
    private ObjectMapper objectMapper;
    /** 线程仓储：轮内事件的线程归属锚点（启动事务内解析主线程一次）。 */
    @Resource
    private SessionThreadRepository sessionThreadRepository;
    /** turn 事件写入器（decompose-command-facade 2.3）：本轮轮内写副作用的唯一出口。 */
    @Resource
    private TurnEventWriter turnEventWriter;
    /** turn 收口服务（decompose-command-facade 3.1）：终局四出口与 HITL 挂起收尾的唯一归属，
     *  本服务只在执行链的收流 / 异常 / 挂起点一行委托（design D3 单向依赖）。 */
    @Resource
    private TurnFinalizer turnFinalizer;

    // ==================== 消息发送（事务 + 事件流编排） ====================

    /**
     * 异步发送消息（虚拟线程托管）：会话校验与执行编排均在虚拟线程任务内完成，
     * {@code onComplete} 在 turn 终态（含广播）后回调
     * （SSE 直连场景用于关闭对应 emitter，保证终态事件送达后才断开）。
     * <p>校验失败（会话不存在 / 终止 / 归档 / 等待人工确认）被异步路径隔离为日志，
     * 不向调用线程抛出；需要同步快速失败的场景由控制器在调用前显式校验。</p>
     */
    public void sendMessageAsync(SendMessageCommand command, Runnable onComplete) {
        virtualExecutor.execute(() -> {
            try {
                requireSession(command.sessionId());
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

    // ==================== 执行控制面与租约 ====================

    /**
     * 承载单轮执行控制面生命周期：new {@link TurnControl} → 置入 currentTurn → 同步执行 → finally 条件清除。
     * <p>等价于原执行槽的 submit，但<b>不再拒止</b>：置位发现槽位非空仅由
     * {@link AgentSessionContext#beginTurn(TurnControl)} 记 ERROR（跨进程互斥权威在 DB CAS，进程内视图
     * fail-open）；清除仅在槽位仍指向本轮控制对象时生效，防毫秒窗内误清新一轮。</p>
     *
     * @param sessionContext 会话级聚合
     * @param task           本轮执行体（接收本轮 {@link TurnControl}）
     */
    public void withTurn(AgentSessionContext sessionContext, Consumer<TurnControl> task) {
        TurnControl turn = new TurnControl();
        sessionContext.beginTurn(turn);
        try {
            task.accept(turn);
        } finally {
            sessionContext.endTurn(turn);
        }
    }

    /**
     * 抢占会话 turn 租约（move-coordination-leases-to-redis D4 开跑顺序：租约 NX 先于 PG 状态 CAS）。
     * <p>确证失败（同键存在未过期租约）→ 409 冲突语义拒绝；存储不可用（Redis / DB 抛异常）
     * 原样向上抛出，<b>MUST NOT 降级为无锁开跑</b>（降级即自废 fail-closed 互斥权威）。
     * 调用点保证：抢占成功但后续 PG 侧失败时经 {@link #releaseTurnLeaseQuietly} 归还。</p>
     *
     * @param reason 冲突日志 / 错误消息后缀（区分开跑与 HITL 领取场景）
     */
    public void acquireTurnLeaseOrConflict(String sessionId, String reason) {
        if (!coordinationLeaseService.tryAcquireTurnLease(sessionId)) {
            throw new SessionBusyException(DEEP_AGENT_SESSION_BUSY + ": " + reason);
        }
    }

    /**
     * 归还刚抢占的 turn 租约（owner-scoped，不误摘他实例接管后的租约，审查修复 F11）。
     * <p>租约抢占成功而 PG CAS / 事务失败时调用；归还自身异常仅记日志——租约 TTL（10min）
     * 自过期兜底，MUST NOT 以归还异常掩盖原始失败。</p>
     */
    public void releaseTurnLeaseQuietly(String sessionId, String occasion) {
        try {
            coordinationLeaseService.releaseTurnLease(sessionId);
        } catch (RuntimeException ex) {
            log.error("{}失败后 turn 租约归还异常（等待 TTL 自过期兜底）: sessionId={}", occasion, sessionId, ex);
        }
    }

    // ==================== 单轮执行 ====================

    /**
     * 执行单轮 turn（启动事务 + 事件流 + 终态事务编排）。
     * <pre>{@code
     *  executeRound
     *    ├─ requireSession（不存在/已归档 → 404；已终止 → 409；活跃执行 → 409 冲突拒绝）
     *    ├─ turn 租约 NX 抢占（先于 PG CAS；确证占用 → 409，存储不可用 → 快速失败不降级）
     *    ├─ [启动事务] CAS(idle→processing/running) + beginRound(DB max 抬升 seq 基准)
     *    │             └─ 失败 / 异常：owner-scoped 归还租约后原样抛出
     *    ├─ 广播 session.status_processing
     *    └─ withTurn(runAgent)   // 开跑置入本轮控制面，finally 条件清除（进程内不再拒止）
     *          └─ 事件流阶段（见 runStream）+ 终态唯一出口（finalizeNormal / finalizeFailed / finalizeInterrupted）
     * }</pre>
     */
    private void executeRound(SendMessageCommand command) {
        TurnStart started = startTurn(command, List.of());
        // ===== 执行层：withTurn 收敛 agent 完整生命周期（置入控制面 + 阻塞等待 + finally 条件清除）=====
        withTurn(started.context().sessionContext(),
                turn -> runAgent(started.context(), started.session(), command.message(), turn));
    }

    /**
     * REST 入站 {@code user.message} 驱动的 turn 启动（events spec「用户消息回显落库与推送」）：
     * 同步完成「门禁 → 租约抢占 → 启动事务内按权威顺序落库
     * {@code status_running → thread_status_running → 整批入站事件} → 提交后广播」，
     * 随后经虚拟线程异步触发本轮执行。
     * <p>启动段留在请求线程内是<b>语义要求</b>：HTTP 200 回显的 {@code data[]} 即本方法返回的
     * 已落库事件，且「状态先于内容可见」要求 user.message 落库前同 seq 序列已有两个 running 事件。</p>
     *
     * @param command        消息发送命令（会话 ID + 文本）
     * @param inboundDrafts  同批入站事件草案（按到达顺序；含 user.message 与可选批尾 system.message）
     * @return 启动事务内已落库的事件（保序，含两个状态事件，供 REST 回显）
     */
    public List<ChatEvent> startTurnWithInbound(SendMessageCommand command, List<InboundEventDraft> inboundDrafts) {
        TurnStart started = startTurn(command, inboundDrafts);
        virtualExecutor.execute(() -> {
            try {
                withTurn(started.context().sessionContext(),
                        turn -> runAgent(started.context(), started.session(), command.message(), turn));
            } catch (RuntimeException ex) {
                log.error("入站 user.message 异步执行失败: sessionId={}", command.sessionId(), ex);
            }
        });
        return started.persistedEvents();
    }

    /**
     * 启动一轮 turn 的同步前半段：门禁 → 租约抢占 → 启动事务（CAS 独占抢占 + 抬升 seq 基准 +
     * 按权威顺序同步落库事件）→ 提交后清毒与广播。
     * <pre>{@code
     * startTurn
     *    ├─ requireSession（不存在/已归档 → 404；已终止 → 409；活跃执行 → 409 冲突拒绝）
     *    ├─ turn 租约 NX 抢占（先于 PG CAS；确证占用 → 409，存储不可用 → 快速失败不降级）
     *    ├─ [启动事务] CAS(idle→running) + beginRound(DB max 抬升 seq 基准)
     *    │             + 落库 status_running → thread_status_running → 入站事件
     *    │             └─ 失败 / 异常：owner-scoped 归还租约后原样抛出
     *    └─ 提交后：清毒 + 保序广播（执行由调用方紧接着触发）
     * }</pre>
     *
     * @param command        消息发送命令
     * @param inboundDrafts  入站事件草案（非入站路径传空列表）
     * @return 启动现场（执行上下文 + 会话镜像 + 事务内已落库事件）
     */
    private TurnStart startTurn(SendMessageCommand command, List<InboundEventDraft> inboundDrafts) {
        AgentSession session = requireSession(command.sessionId());
        if (session.archived()) {
            // 归档即会话对写操作面不可见（sessions spec：归档后提交 user.message → 404）
            throw new ResourceNotFoundException(DEEP_AGENT_SESSION_NOT_FOUND + ": 会话已归档");
        }
        if (session.status() == AgentSessionStatus.TERMINATED) {
            // 终态会话状态冲突：terminated 不是归档，按会话契约特例 409 invalid_request_error（不返回 404）
            throw new SessionBusyException(DEEP_AGENT_SESSION_BUSY + ": 会话已终止，不可提交新消息");
        }
        if (session.hasActiveExecution()) {
            // 单活跃执行约束：running / awaiting_confirmation / cancelling（轮次 running）
            // 期间新执行提交按 409 冲突拒绝（REST 入站路径已同步前置拦截，此处兜底
            // 调度器 / 部署触发等异步入口，异常被异步路径隔离为日志）
            throw new SessionBusyException(DEEP_AGENT_SESSION_BUSY
                    + ": 会话存在活跃执行，请先取消当前轮或等待回到 idle: " + command.sessionId());
        }

        // ===== 开跑抢占（move-coordination-leases-to-redis D4：协调租约 NX 先于 PG 状态 CAS） =====
        // 租约确证已被占用 → 409 冲突；存储不可用（Redis / DB 异常）直接向上抛出——
        // 不降级为无锁开跑（降级即自废 fail-closed 互斥权威），此时 PG 状态一字未动
        acquireTurnLeaseOrConflict(command.sessionId(), "会话已有在跑租约，请稍后再试");

        // ===== 启动事务：CAS 独占抢占 + 抬升事件 seq 基准 + 权威顺序落库（失败则归还租约） =====
        // 事件流 seq 以 DB 最大序号抬升一次，全 turn（含状态事件/终态事件）由会话级计数器内存分配，
        // DB 唯一索引兜底；status_running → thread_status_running → 入站事件 的次序即
        // events spec 明文要求的「状态先于内容」权威次序
        TurnStart started;
        try {
            started = transactionTemplate.execute(status -> {
                if (sessionRepository.transition(command.sessionId(), Transition.BEGIN_TURN) <= 0) {
                    // CAS 失败：会话非 idle（活跃执行 / 终态），单执行约束按 409 冲突语义拒绝
                    throw new SessionBusyException(DEEP_AGENT_SESSION_BUSY + ": 会话正在执行中，请稍后再试");
                }
                AgentSessionContext sessionContext = sessionRegistry.getOrCreate(session);
                long dbMax = chatEventRepository.nextSequenceNum(command.sessionId()) - 1L;
                TurnRunState runState = sessionContext.beginRound(dbMax);
                // 线程归属：启动事务内解析主线程一次，随执行现场贯穿本轮全部落库事件
                String mainThreadId = resolveMainThreadId(command.sessionId());
                ExecutionContext context = new ExecutionContext(sessionContext, runState, mainThreadId);
                List<ChatEvent> persisted = new ArrayList<>();
                persisted.add(saveStartupEvent(context,
                        ChatEventFactory.INSTANCE.sessionStatus(AgentSessionStatus.RUNNING, null)));
                persisted.add(saveStartupEvent(context,
                        ChatEventFactory.INSTANCE.threadStatus(AgentSessionStatus.RUNNING, null)));
                for (InboundEventDraft draft : inboundDrafts) {
                    persisted.add(saveStartupEvent(context, draft));
                }
                return new TurnStart(context, session, List.copyOf(persisted));
            });
        } catch (RuntimeException ex) {
            releaseTurnLeaseQuietly(command.sessionId(), "启动事务");
            throw ex;
        }
        if (started == null) {
            releaseTurnLeaseQuietly(command.sessionId(), "启动事务");
            throw new DeepDataAgentException(DEEP_AGENT_RUN_ERROR + ": 创建执行现场失败");
        }
        // 开跑清毒（design D1）：本轮 CAS 已提交（status → running），旧轮遗留的持久化失败标志
        // 不得继承到新一轮（旧轮丢行属已回滚旧轮，事实已在 ERROR 留痕）。非事务内存操作，
        // 置于提交之后执行——避免「CAS 失败归还租约」路径上清毒早于回滚的语义歧义
        chatEventPersister.clearPoisonFlag(command.sessionId());
        // 提交后按落库次序广播（实时订阅者与历史回放同源同序）
        started.persistedEvents().forEach(event ->
                turnEventWriter.pushQuietly(started.context().sessionContext(), event));
        log.info("turn 开始: sessionId={}", command.sessionId());
        return started;
    }

    /** 启动事务内同步落库组装事件（提交后由调用方统一广播）。 */
    private ChatEvent saveStartupEvent(ExecutionContext context, AssembledEvent assembled) {
        return saveStartupEvent(context, turnEventWriter.assemble(context, assembled));
    }

    /** 启动事务内同步落库入站事件（seq 由会话级计数器保序分配，线程归属取自执行现场）。 */
    private ChatEvent saveStartupEvent(ExecutionContext context, InboundEventDraft draft) {
        return saveStartupEvent(context, ChatEvent.create(context.sessionId(), draft.type(),
                draft.payloadJson(), context.nextSequence(), null, context.sessionThreadId()));
    }

    /** 启动事务内同步落库单条事件（save 返回 null 时回退入参本身，保证回显不丢）。 */
    private ChatEvent saveStartupEvent(ExecutionContext context, ChatEvent event) {
        ChatEvent saved = chatEventRepository.save(event);
        return saved != null ? saved : event;
    }

    /**
     * 启动阶段产出载体。
     *
     * @param context         本轮执行现场快照
     * @param session         会话镜像（实时装配用）
     * @param persistedEvents 启动事务内已落库事件（保序：两个状态事件在前，入站事件在后）
     */
    private record TurnStart(ExecutionContext context, AgentSession session, List<ChatEvent> persistedEvents) {
    }

    /**
     * 在 withTurn 承载的本轮控制面内执行 agent：装配 → 订阅事件流并阻塞 → 四出口收轮 → 释放，
     * 骨架全部委托 {@link RoundExecutionTemplate}（变更 R10），本方法只提供新一轮流供给
     * （{@code streamEvents}：用户输入原样进入数据面，挂载经宿主 bind mount 可见，清单由系统提示承载 D13 / D14）。
     *
     * @param context   本轮执行现场快照
     * @param session   会话镜像（实时装配用；挂载由装配解析期物化对账承载，不再参与输入展开）
     * @param userInput 用户消息
     * @param turn      本轮执行控制面（完成信号与中断注册）
     */
    private void runAgent(ExecutionContext context, AgentSession session, String userInput,
                          TurnControl turn) {
        RoundContext ctx = newRoundContext(context, session, turn, "turn",
                ex -> log.warn("Agent 释放异常: sessionId={}", session.sessionId(), ex));
        roundExecutionTemplate.run(ctx, agent -> agentRunExecutor.streamEvents(
                agent, userInput, session.sessionId(), session.userId()));
    }

    /**
     * 装配本轮 {@link RoundContext}（变更 R10）：把执行服务既有协作端口与编排回调显式注入执行模板，
     * 令新一轮 / 续跑轮共用同一骨架而差异仅在流供给函数。
     * <p>信号消费 / 收流 / 异常收流 / 取消谓词均绑定到本服务的既有私有方法
     * （{@code handleSignal} / {@code onStreamComplete} / {@code onStreamError} / {@code isCancelRequested}），
     * 中断与失败出口绑定到 {@link TurnFinalizer}（{@code finalizeInterrupted} / {@code finalizeAsFailure}），
     * 保持终态编排权威在收口服务侧（design D3）。</p>
     *
     * @param context             本轮执行现场快照
     * @param session             会话镜像
     * @param turn                本轮执行控制面
     * @param roundLabel          本轮日志语义标签（"turn" / "续跑轮"）
     * @param onAgentCloseFailure Agent 句柄释放异常出口（按路径定制日志）
     */
    public RoundContext newRoundContext(ExecutionContext context, AgentSession session, TurnControl turn,
                                        String roundLabel, Consumer<RuntimeException> onAgentCloseFailure) {
        // 本轮副作用出口：捕获 context / turn，轮内写经 Writer、终局与挂起经 Finalizer（行为与拆分前逐字等价）
        RoundSink sink = roundSink(context, turn);
        return new RoundContext(
                context.sessionContext(),
                session,
                turn,
                context.runState(),
                context.sessionThreadId(),
                agentFactory,
                runtimeAgentAssemblyService::assemble,
                blockingScheduler,
                leaseRenewalScheduler,
                coordinationLeaseService,
                signal -> handleSignal(signal, context, sink),
                () -> onStreamComplete(context, turn),
                error -> onStreamError(context, error, turn),
                () -> isCancelRequested(context),
                () -> turnFinalizer.finalizeInterrupted(context),
                (ex, setupFailure) -> turnFinalizer.finalizeAsFailure(context, ex, setupFailure),
                onAgentCloseFailure,
                roundLabel);
    }

    /**
     * 以本轮显式执行上下文跑完单轮执行骨架（续跑所需的 {@code runRound} 语义，3.2 public 暴露）：
     * 委托 {@link RoundExecutionTemplate}，差异仅在流供给函数（新一轮 {@code streamEvents} /
     * 续跑轮 {@code resumeConfirmation}）。
     *
     * @param ctx          本轮显式执行上下文（{@link #newRoundContext} 产出）
     * @param fluxSupplier 流供给（给定已装配 Agent 产出冷流）
     */
    public void runRound(RoundContext ctx, Function<BuiltAgent, Flux<AgentStreamSignal>> fluxSupplier) {
        roundExecutionTemplate.run(ctx, fluxSupplier);
    }

    /**
     * 单个流信号编排（变更 R11）：保留固定骨架——fail-closed 租约守卫 + 调试日志 + 分发，
     * 具体每类信号的处理（状态累积 → 源码事件装配 → 落库 + 广播 / 实时帧推送 / 终态触发 / HITL 挂起）
     * 交由 {@link SignalHandlerRegistry} 按类型分派到对应 {@link SignalHandler}，副作用经本轮
     * {@link RoundSink} 回落到本服务与收口 / 写入服务。新增信号 = 新策略 + 注册表登记一行，本骨架零修改。
     */
    private void handleSignal(AgentStreamSignal signal, ExecutionContext context, RoundSink sink) {
        TurnRunState runState = context.runState();
        String sessionId = context.session().sessionId();
        log.debug("信号处理: sessionId={}, type={}, toolCallId={}, blockId={}",
                sessionId, signal.type(), signal.toolCallId(), signal.blockId());
        // fail-closed 静默中止：续约失败已确立执行权丧失，后续到达信号一律丢弃
        //（不落库 / 不广播 / 不迁移状态；续约任务负责取消订阅，此守卫为边界竞态兜底）
        if (runState.leaseLost()) {
            log.warn("turn 租约已丢失，丢弃在途信号（fail-closed）: sessionId={}, signalType={}",
                    sessionId, signal.type());
            return;
        }
        signalHandlerRegistry.dispatch(new SignalContext(signal, context.sessionContext(), runState, sink));
    }

    /**
     * 绑定本轮执行现场的 {@link RoundSink}（变更 R11 / decompose-command-facade 3.2 回接点收口）：
     * 把策略所需副作用分两类直连——轮内写（落库 / 广播 / 实时帧 / delta 刷写）经 {@link TurnEventWriter}，
     * 终局与 HITL 挂起经 {@link TurnFinalizer}，捕获本轮 {@code context} 与 {@code turn}。
     * <p>每轮构造一次（{@link #newRoundContext} 内），跨该轮所有信号复用，避免逐信号重建
     * {@code ExecutionContext}；委托体逐字对应拆分前 {@code handleSignal} switch 各 case 的副作用调用，
     * 保持行为等价。</p>
     */
    private RoundSink roundSink(ExecutionContext context, TurnControl turn) {
        return new RoundSink() {
            @Override
            public void persistAndBroadcast(AssembledEvent event) {
                turnEventWriter.persistAndBroadcast(context, event);
            }

            @Override
            public void persistAndBroadcast(AssembledEvent event, String eventId) {
                turnEventWriter.persistAndBroadcast(context, event, eventId);
            }

            @Override
            public void pushFrame(AssembledEvent frame, String eventId, ChatEventType targetType) {
                turnEventWriter.pushFrame(context, frame, eventId, targetType);
            }

            @Override
            public void flushTextDelta(TurnRunState runState, String eventId) {
                turnEventWriter.flushTextDelta(context, runState, eventId);
            }

            @Override
            public Map<String, Object> parsePayload(String payloadJson) {
                return TurnExecutionService.this.parsePayload(payloadJson);
            }

            @Override
            public void finalizeNormal() {
                turnFinalizer.finalizeNormal(context, turn);
            }

            @Override
            public void enterWaitingConfirm(AgentStreamSignal signal) {
                turnFinalizer.enterWaitingConfirm(context, signal, turn);
            }
        };
    }

    // ==================== 收流 / 异常收流 ====================

    /**
     * 流正常结束（onComplete）：EXCEED_MAX_ITERS 的 defer 终态、AGENT_END 缺失兜底、
     * 以及断连 / 终止中断后 SDK 正常收流的收尾入口。
     * <p>收流终态分类交领域纯函数 {@code TurnResult.classifyStreamClose}
     * （在途取消 &gt; HITL 挂起驻留 &gt; 迭代上限 &gt; 正常结束）；
     * 在途取消（{@link #isCancelRequested} 两源谓词）优先于迭代上限终止：
     * 取消与 maxIters 竞态时经中断终态出口收敛回 idle，不落 terminated。</p>
     */
    private void onStreamComplete(ExecutionContext context, TurnControl turn) {
        String sessionId = context.session().sessionId();
        if (context.runState().leaseLost()) {
            // fail-closed 静默中止（决策表第 6 行，进策略前短路）：续约失败已确立执行权丧失
            //（终态权归复位路径或新持有者；完成信号已由中止句柄放行，订阅已取消，
            // 续约任务由 runStream finally 取消，agent 句柄由 runAgent finally 关闭）
            log.warn("turn 租约已丢失，忽略流收流回调（fail-closed 静默中止，不终态化）: sessionId={}",
                    sessionId);
            return;
        }
        // 两源取消谓词先于内存守卫求值（保持现状求值顺序：取消读在租约守卫之后，不多不少一次 DB 读）
        TurnResult result = TurnResult.classifyStreamClose(isCancelRequested(context),
                context.runState().confirmationPending(), context.runState().exceededMaxIters());
        switch (result.kind()) {
            case INTERRUPTED -> {
                // 决策表第 4 行：断连 / 终止 / user.interrupt 在途——走中断终态（回 idle + session.interrupted）
                log.warn("在途取消后 SDK 正常收流，执行置中断（取消优先）: sessionId={}", sessionId);
                turnFinalizer.finalizeInterrupted(context);
                turn.finish();
            }
            case HITL_SUSPENDED ->
                    // 决策表第 8 行：HITL 挂起收轮（enterWaitingConfirm 已放行完成信号并释放租约）——
                    // SDK 流随后收流，不再终态化，等待事实已存账本，续跑由确认/拒绝指令经领取事务重建现场驱动
                    log.info("HITL 挂起已收轮，SDK 流收流不终态: sessionId={}", sessionId);
            default -> {
                // 决策表第 1 / 2 / 3 行：正常结束（COMPLETED）或迭代上限（MAX_ITERATIONS）
                log.info("事件流正常收流完成: sessionId={}", sessionId);
                turnFinalizer.finalizeNormal(context, turn);
            }
        }
    }

    /**
     * 在途取消谓词（两源求值，不提前缓存、不在流开始处固化）：
     * <ol>
     *   <li>进程内中断标志——同进程快速路径（{@code interruptCurrentRun} 置位后可靠，
     *       亦承载 SSE 最后连接断开的瞬态中断）；</li>
     *   <li>{@code canceling} 持久状态痕迹——取消侧 CAS 提交于共享库，跨进程可见、
     *       不随长轮次过期、重启后仍可见，为跨进程取消的权威依据。</li>
     * </ol>
     * <p>两个求值点语义不变：订阅建立后复检点据此判断「取消是否在途」以补触发定向中断
     * （保留一次 DB 读）；收流终态判定点据此决定按中断（idle/stop）还是正常语义收敛。</p>
     */
    private boolean isCancelRequested(ExecutionContext context) {
        String sessionId = context.session().sessionId();
        return context.runState().interrupted()
                || sessionRepository.isCancelling(sessionId);
    }

    /**
     * 流异常结束（onError）：依显式中断状态区分中断与执行错误后统一走对应终态。
     * <p>有意不使用 {@link #isCancelRequested} 两源谓词：跨进程取消不会使执行实例的流异常，
     * {@code session.error} 语义不受「取消 × 迭代上限」竞态影响，维持进程内标志判定即可。</p>
     */
    private void onStreamError(ExecutionContext context, Throwable error,
                               TurnControl turn) {
        String sessionId = context.session().sessionId();
        if (context.runState().leaseLost()) {
            // fail-closed 静默中止：租约丢失后流回调（含取消订阅边界竞态到达的 onError）一律忽略，
            // 不终态化 / 不 CAS / 不广播（完成信号已由中止句柄放行，此处 MUST NOT 再 finish）
            log.warn("turn 租约已丢失，忽略流异常回调（fail-closed 静默中止）: sessionId={}", sessionId);
            return;
        }
        boolean interrupted = context.runState().interrupted();
        log.error("Agent 事件流异常: sessionId={}, interrupted={}", sessionId, interrupted, error);
        if (interrupted) {
            turnFinalizer.finalizeInterrupted(context);
        } else {
            turnFinalizer.finalizeFailed(context, blankToDefault(error.getMessage(), "agent 执行失败"));
        }
        turn.finish();
    }

    // ==================== 私有工具方法（随执行簇自持副本，与门面既有实现逐字同形） ====================

    /**
     * 按 ID 查询会话，不存在时抛 404（执行路径的统一前置校验）。
     * <p>仅供内部执行路径（{@code sendMessageAsync} / 调度器触发）使用：执行权由会话状态 CAS
     * 与协调层租约守卫，不做 owner 校验（调度触发链路无用户认证现场）。</p>
     */
    private AgentSession requireSession(String sessionId) {
        return sessionRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException(DEEP_AGENT_SESSION_NOT_FOUND + ": 会话不存在"));
    }

    /**
     * 解析会话主线程归属 ID（轮外低频落库路径现查一次；主线程缺失降级 null = 无归属，不阻断落库）。
     * <p>轮内高频路径不经本方法：归属在执行现场构造（启动 / 领取事务）时解析一次，
     * 随 {@link ExecutionContext#sessionThreadId()} 传递。</p>
     */
    private String resolveMainThreadId(String sessionId) {
        return sessionThreadRepository.findMain(sessionId)
                .map(SessionThread::threadId)
                .orElse(null);
    }

    /** 解析事件 payload JSON 为 Map（非法收敛为空 Map）。 */
    private Map<String, Object> parsePayload(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(payloadJson,
                    new TypeReference<Map<String, Object>>() {
                    });
            return parsed == null ? Map.of() : parsed;
        } catch (Exception ex) {
            return Map.of();
        }
    }

    /** 空串兜底：值为 null 或空白时返回 fallback。 */
    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
