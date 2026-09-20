package com.linkroa.deepdataagent.runtime.application.service.execution;

import com.linkroa.deepdataagent.runtime.application.command.SendMessageCommand;
import com.linkroa.deepdataagent.runtime.application.port.AgentRunExecutor;
import com.linkroa.deepdataagent.runtime.application.port.ChatEventPersister;
import com.linkroa.deepdataagent.runtime.application.port.LeaseRenewalHandle;
import com.linkroa.deepdataagent.runtime.application.port.LeaseRenewalScheduler;
import com.linkroa.deepdataagent.runtime.application.service.CoordinationLeaseService;
import com.linkroa.deepdataagent.runtime.application.service.assembly.RuntimeAgentAssemblyService;
import com.linkroa.deepdataagent.runtime.domain.event.AgentStreamSignal;
import com.linkroa.deepdataagent.runtime.domain.event.AgentStreamSignalType;
import com.linkroa.deepdataagent.runtime.domain.event.TurnFinished;
import com.linkroa.deepdataagent.runtime.domain.factory.AgentFactoryPort;
import com.linkroa.deepdataagent.runtime.domain.factory.BuiltAgent;
import com.linkroa.deepdataagent.runtime.domain.model.AgentAssemblySpec;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSession;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSessionContext;
import com.linkroa.deepdataagent.runtime.domain.model.ChatEvent;
import com.linkroa.deepdataagent.runtime.domain.model.SessionResource;
import com.linkroa.deepdataagent.runtime.domain.model.StreamFrame;
import com.linkroa.deepdataagent.runtime.domain.model.Transition;
import com.linkroa.deepdataagent.runtime.domain.model.TurnControl;
import com.linkroa.deepdataagent.runtime.domain.model.enums.AgentSessionStatus;
import com.linkroa.deepdataagent.runtime.domain.model.enums.ChatEventType;
import com.linkroa.deepdataagent.runtime.domain.model.enums.TurnPhase;
import com.linkroa.deepdataagent.runtime.domain.model.runstate.TurnRunState;
import com.linkroa.deepdataagent.runtime.domain.port.ConnectionHandle;
import com.linkroa.deepdataagent.runtime.domain.repository.AgentSessionRepository;
import com.linkroa.deepdataagent.runtime.domain.repository.ChatEventRepository;
import com.linkroa.deepdataagent.runtime.domain.repository.SessionThreadRepository;
import com.linkroa.deepdataagent.runtime.infrastructure.execution.InMemorySessionRegistry;
import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import com.linkroa.deepdataagent.shared.exception.SessionBusyException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link TurnExecutionService} 直测（decompose-command-facade 3.2）：固化自门面纯搬移的执行链红线
 * （design Context 硬约束，红线用例自门面壳测试类逐名迁入，
 * 迁移前后对照登记于 {@code openspec/changes/decompose-command-facade/tasks.md} 3.2 注记）。
 * <ul>
 *   <li><b>启动顺序（D4）</b>：turn 租约 NX 抢占先于 PG 状态 CAS；CAS 提交后才清除落库失败标记；
 *       CAS 落空 / 抛异常必经 owner-scoped 归还租约后原样抛出（不降级为无锁启动）；</li>
 *   <li><b>挂起即物理轮终局</b>：durable 挂起收尾后 agent 释放 + 停续约 + 释放租约，
 *       MUST NOT 经终态出口落 idle 终态（红线④）；</li>
 *   <li><b>D19 批次 / 候选错配</b>：拒绝挂起后沿 {@code onStreamError → finalizeFailed}
 *       收敛为执行错误终态并照常收轮（编排侧半边红线）；</li>
 *   <li><b>防止永久阻塞的收尾处理</b>：严格排空协议落库失败时收轮信号必达、终态事件不落（红线③）；</li>
 *   <li><b>RoundSink 回接点</b>：轮内写经 {@link TurnEventWriter}、终局与挂起经
 *       {@link TurnFinalizer}（本类以真实两服务实例接入，令落库 / 广播顺序断言端到端可固化）。</li>
 * </ul>
 * <p>夹具口径与壳测试一致：真实 {@link InMemorySessionRegistry} + 真实 {@link Scheduler}
 * （{@code Schedulers.immediate()}，令 {@code doOnNext} 在测试线程同步跑完）+ 同步直跑虚拟执行器
 * + 真实 {@link TurnEventWriter} / {@link TurnFinalizer}，仓储 / 装配 / 租约 / 事务为替身。</p>
 */
@ExtendWith(MockitoExtension.class)
class TurnExecutionServiceTest {

    @Mock private AgentSessionRepository sessionRepository;
    @Mock private ChatEventRepository chatEventRepository;
    @Mock private AgentFactoryPort agentFactory;
    @Mock private AgentRunExecutor agentRunExecutor;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private RuntimeAgentAssemblyService runtimeAgentAssemblyService;
    /** 协调层租约 mock：turn 租约获取 / 续约 / 释放（获取默认放行）。 */
    @Mock private CoordinationLeaseService coordinationLeaseService;
    /** 广播端口 mock：验证 {@code session.status_*} / 源码事件经连接层下发。 */
    @Mock private ConnectionHandle connectionHandle;
    @Mock private SessionThreadRepository sessionThreadRepository;
    /** 终局事件发布器 mock：收口器在终态事务内直发 TurnFinished（迭代裁决 2026-09-13 直注）。 */
    @Mock private ApplicationEventPublisher applicationEventPublisher;
    /** turn 租约定时续约调度端口 mock：捕获续约任务与停止句柄，验证全出口停续约。 */
    @Mock private LeaseRenewalScheduler leaseRenewalScheduler;
    @Mock private LeaseRenewalHandle leaseRenewalHandle;

    /** 真实会话级聚合注册表：seq 计数器 / 执行控制面槽位 / 进行中的流快照按真实实现执行。 */
    private final InMemorySessionRegistry sessionRegistry = new InMemorySessionRegistry();

    /** 真实同步调度器：publishOn 后在调用线程同步执行 doOnNext（断言确定性强）。 */
    private final Scheduler blockingScheduler = Schedulers.immediate();

    /**
     * 同步直跑执行器：启动即在调用线程走完一轮，异常收口全部落在测试线程。
     * <p>非 final：异步入口用例（decompose-command-facade 4.4 自壳类迁入）单独替换为 mock 执行器。</p>
     */
    private Executor virtualExecutor = task -> task.run();

    private TurnEventWriter turnEventWriter;
    private TurnFinalizer turnFinalizer;
    private TurnExecutionService service;

    @BeforeEach
    void setUp() {
        turnEventWriter = new TurnEventWriter();
        ReflectionTestUtils.setField(turnEventWriter, "sessionRegistry", sessionRegistry);
        ReflectionTestUtils.setField(turnEventWriter, "chatEventRepository", chatEventRepository);
        turnFinalizer = new TurnFinalizer();
        ReflectionTestUtils.setField(turnFinalizer, "sessionRepository", sessionRepository);
        ReflectionTestUtils.setField(turnFinalizer, "chatEventRepository", chatEventRepository);
        ReflectionTestUtils.setField(turnFinalizer, "transactionTemplate", transactionTemplate);
        ReflectionTestUtils.setField(turnFinalizer, "turnEventWriter", turnEventWriter);
        ReflectionTestUtils.setField(turnFinalizer, "coordinationLeaseService", coordinationLeaseService);
        ReflectionTestUtils.setField(turnFinalizer, "applicationEventPublisher", applicationEventPublisher);
        service = new TurnExecutionService();
        ReflectionTestUtils.setField(service, "sessionRepository", sessionRepository);
        ReflectionTestUtils.setField(service, "chatEventRepository", chatEventRepository);
        ReflectionTestUtils.setField(service, "agentFactory", agentFactory);
        ReflectionTestUtils.setField(service, "agentRunExecutor", agentRunExecutor);
        ReflectionTestUtils.setField(service, "runtimeAgentAssemblyService", runtimeAgentAssemblyService);
        ReflectionTestUtils.setField(service, "sessionRegistry", sessionRegistry);
        ReflectionTestUtils.setField(service, "transactionTemplate", transactionTemplate);
        ReflectionTestUtils.setField(service, "coordinationLeaseService", coordinationLeaseService);
        ReflectionTestUtils.setField(service, "virtualExecutor", virtualExecutor);
        ReflectionTestUtils.setField(service, "blockingScheduler", blockingScheduler);
        ReflectionTestUtils.setField(service, "leaseRenewalScheduler", leaseRenewalScheduler);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(service, "sessionThreadRepository", sessionThreadRepository);
        ReflectionTestUtils.setField(service, "turnEventWriter", turnEventWriter);
        ReflectionTestUtils.setField(service, "turnFinalizer", turnFinalizer);
        // 落库端口三处同步（执行侧启动时清除落库失败标记 + Writer 入队 + 收口器排空 / 校验落库失败标记）
        wirePersister(synchronousPersister());
    }

    // ==================== 夹具 ====================

    /**
     * 落库端口替身三处同步替换（严格排空协议与启动时清除落库失败标记的故障注入点）：
     * 执行侧 {@code chatEventPersister} 仅承载启动时清除落库失败标记（design D1），入队与排空 / 校验落库失败标记
     * 分别经 {@link TurnEventWriter} 与 {@link TurnFinalizer}，三处同注入保持
     * 「注入即全链路生效」语义。
     */
    private void wirePersister(ChatEventPersister persister) {
        ReflectionTestUtils.setField(service, "chatEventPersister", persister);
        ReflectionTestUtils.setField(turnEventWriter, "chatEventPersister", persister);
        ReflectionTestUtils.setField(turnFinalizer, "chatEventPersister", persister);
    }

    /** 同步落库替身：enqueue 即 save、flush 无操作、校验落库失败标记恒为假（无持久化故障的常态）。 */
    private ChatEventPersister synchronousPersister() {
        return new ChatEventPersister() {
            @Override
            public void enqueue(ChatEvent event) {
                chatEventRepository.save(event);
            }

            @Override
            public void flush() {
            }

            @Override
            public boolean isPoisoned(String sessionId) {
                return false;
            }

            @Override
            public void clearPoisonFlag(String sessionId) {
            }
        };
    }

    /**
     * 事件表不可信（恒已标记落库失败）替身：事务前 flush 无操作、事务首行校验落库失败标记恒为真，
     * 由收口器在事务 lambda 首行抛业务异常触发整批回滚（红线③的故障源）。
     */
    private ChatEventPersister strictFlushFailingPersister() {
        return new ChatEventPersister() {
            @Override
            public void enqueue(ChatEvent event) {
                chatEventRepository.save(event);
            }

            @Override
            public void flush() {
            }

            @Override
            public boolean isPoisoned(String sessionId) {
                return true;
            }

            @Override
            public void clearPoisonFlag(String sessionId) {
            }
        };
    }

    /** 让 mock 事务模板同步执行回调（execute / executeWithoutResult 两类回调共存）。 */
    private void wireTransactionTemplate() {
        lenient().doAnswer(inv -> {
            TransactionCallback<Object> callback = inv.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactionTemplate).execute(any());
        lenient().doAnswer(inv -> {
            Consumer<TransactionStatus> consumer = inv.getArgument(0);
            consumer.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    /**
     * 装配事件流成功路径的公共桩（启动抢占 + 启动事务 + 构建 + 注册 + 会话状态迁移 CAS）。
     * <p>公共桩以 {@code lenient} 注册避免 UnnecessaryStubbing 误报（各用例只消费其中一部分）；
     * {@code TO_IDLE} 按真实 CAS 语义返回 {@code (1, 0)}：AGENT_END 提前终态成功后，
     * onComplete 兜底再次迁移影响行数为 0（不重复落终态事件）。</p> */
    private BuiltAgent wireHappyPathForExecution() {
        wireTransactionTemplate();
        lenient().when(sessionRepository.transition(anyString(), eq(Transition.BEGIN_TURN))).thenReturn(1);
        lenient().when(coordinationLeaseService.tryAcquireTurnLease(anyString())).thenReturn(true);
        lenient().when(leaseRenewalScheduler.schedule(any(Runnable.class), any(Duration.class)))
                .thenReturn(leaseRenewalHandle);
        lenient().when(coordinationLeaseService.renewTurnLease(anyString())).thenReturn(true);
        lenient().when(sessionRepository.transition(anyString(), eq(Transition.FINISH_TURN))).thenReturn(1, 0);
        lenient().when(sessionRepository.transition(anyString(), eq(Transition.TERMINATE))).thenReturn(1);
        lenient().when(sessionRepository.transition(anyString(), eq(Transition.PHASE_AWAIT))).thenReturn(1);
        lenient().when(sessionRepository.transition(anyString(), eq(Transition.PHASE_RESUME)))
                .thenReturn(1);
        // beginRound 以 DB max 抬升 seq 基准：mock 返回下一序号 1 → 基准 0（从 1 起分配）
        lenient().when(chatEventRepository.nextSequenceNum(anyString())).thenReturn(1L);
        lenient().when(chatEventRepository.save(any(ChatEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(runtimeAgentAssemblyService.assemble(any(AgentSession.class))).thenReturn(sampleAssembly());
        BuiltAgent agent = mock(BuiltAgent.class);
        lenient().when(agentFactory.build(any(AgentAssemblySpec.class))).thenReturn(agent);
        return agent;
    }

    /** 会话语境绑定连接句柄（广播经 {@code connection().push} 验证）。 */
    private AgentSessionContext bindConnection(AgentSession session) {
        AgentSessionContext context = sessionRegistry.getOrCreate(session);
        context.bindConnection(connectionHandle);
        return context;
    }

    /** 汇总 chatEventRepository.save 的记录（事件类型 / payload / seq，按调用顺序）。 */
    private List<ChatEvent> savedChatEvents() {
        return org.mockito.Mockito.mockingDetails(chatEventRepository).getInvocations().stream()
                .filter(inv -> inv.getMethod().getName().equals("save"))
                .map(inv -> inv.getArgument(0, ChatEvent.class))
                .collect(Collectors.toList());
    }

    /** 取落库记录中首个指定类型事件（落库顺序断言用）。 */
    private ChatEvent firstOfType(List<ChatEvent> saved, ChatEventType type) {
        return saved.stream().filter(e -> e.type() == type).findFirst().orElseThrow();
    }

    /**
     * HITL 挂起批次信号流：两个工具调用（tc-1 / tc-2）完成入参聚合后，
     * REQUIRE 信号按 reply 整批携带待确认 id，AGENT_END 收流（挂起守卫使其不终态化）。
     */
    private Flux<AgentStreamSignal> suspendBatchSignals() {
        return Flux.just(
                AgentStreamSignal.tool(AgentStreamSignalType.TOOL_CALL_START, "tc-1", "search", null, null),
                AgentStreamSignal.tool(AgentStreamSignalType.TOOL_CALL_DELTA, "tc-1", "search",
                        "{\"q\":\"x\"}", null),
                AgentStreamSignal.tool(AgentStreamSignalType.TOOL_CALL_END, "tc-1", "search", null, null),
                AgentStreamSignal.tool(AgentStreamSignalType.TOOL_CALL_START, "tc-2", "calculator", null, null),
                AgentStreamSignal.tool(AgentStreamSignalType.TOOL_CALL_DELTA, "tc-2", "calculator",
                        "{\"expr\":\"1+1\"}", null),
                AgentStreamSignal.tool(AgentStreamSignalType.TOOL_CALL_END, "tc-2", "calculator", null, null),
                AgentStreamSignal.hitl(AgentStreamSignalType.HUMAN_CONFIRM_REQUIRED, "reply-1",
                        List.of("tc-1", "tc-2")),
                AgentStreamSignal.of(AgentStreamSignalType.AGENT_END, null, null));
    }

    /** 正常收流信号流（AGENT_END 提前终态 + onComplete 兜底幂等）。 */
    private Flux<AgentStreamSignal> endSignals() {
        return Flux.just(AgentStreamSignal.of(AgentStreamSignalType.AGENT_END, null, null));
    }

    private AgentSession idleSession() {
        return AgentSession.create("1", "agent-a", "1.0.0", "{}", null);
    }

    /** 指定状态的会话夹具（终止快速失败场景输入）。 */
    private AgentSession sessionWithStatus(AgentSessionStatus status) {
        AgentSession base = idleSession();
        OffsetDateTime now = OffsetDateTime.now();
        return new AgentSession(base.id(), base.sessionId(), base.userId(), base.agentId(), base.agentVersion(),
                status, status == AgentSessionStatus.IDLE ? TurnPhase.IDLE : TurnPhase.RUNNING,
                base.metadata(), base.title(), base.environmentId(),
                base.vaultIds(), base.memoryStoreIds(), base.environmentVariables(), base.resources(),
                base.triggerType(), base.triggerId(), base.lastActiveAt(),
                null, now, now, null, null);
    }

    /** 装配结果样本（构建路径输入：领域规格，含凭证 / 端点）。 */
    private AgentAssemblySpec sampleAssembly() {
        return AgentAssemblySpec.builder()
                .withIdentity(new AgentAssemblySpec.AgentIdentity("agent-a", "v1"))
                .withModelBinding(new AgentAssemblySpec.ModelBinding(
                        "openai:gpt-4", "sk-cred", "https://api.example.com/v1", null, null))
                .withPromptContent(new AgentAssemblySpec.PromptContent("你是数据分析专家", null))
                .withMaxIters(10)
                .withSandbox(AgentAssemblySpec.Sandbox.of("python:3.12", 8192L, 4L))
                .withMountView(new AgentAssemblySpec.MountView(
                        List.of(), List.of(), List.of(), java.util.Map.of(), List.of()))
                .withToolGovernance(AgentAssemblySpec.ToolGovernance.empty())
                .withArtifactOwnership(AgentAssemblySpec.ArtifactOwnership.empty())
                .build();
    }

    // ==================== 红线④：挂起即物理轮终局 ====================

    @Test
    void should_suspendWithoutWaitingEventsAndFinishRound_when_sendMessageAsync_given_suspendSignals() {
        // given（挂起轮：两个工具调用后 REQUIRE 批次挂起——durable 挂起即物理轮终局）
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        BuiltAgent agent = wireHappyPathForExecution();
        bindConnection(session);
        when(agentRunExecutor.streamEvents(any(BuiltAgent.class), anyString(), anyString(), anyString()))
                .thenReturn(suspendBatchSignals());

        // when（同步执行器：挂起轮在调用内完成收尾）
        service.sendMessageAsync(new SendMessageCommand(session.sessionId(), "执行"));

        // then：等待现场只由事件表既有工具调用行承载（两条 agent.tool_use，载荷以 tool_use_id 承载身份），
        // 不再有 requires_action / waiting_confirmation 旁路事件
        List<ChatEvent> saved = savedChatEvents();
        List<ChatEvent> toolUses = saved.stream()
                .filter(e -> e.type() == ChatEventType.AGENT_TOOL_USE).toList();
        assertEquals(2, toolUses.size());
        assertTrue(toolUses.stream().allMatch(e -> e.payload().contains("\"tool_use_id\"")),
                "agent.tool_use 应以 tool_use_id 承载工具调用身份");
        assertTrue(toolUses.stream().allMatch(e -> !e.payload().contains("reply_id")),
                "对外不发布 reply_id / SDK 内部调用 id");
        verify(connectionHandle, atLeastOnce()).push(argThat(e -> e.type() == ChatEventType.AGENT_TOOL_USE));
        // then：挂起不收场——不落 idle / terminated 终态状态事件
        assertTrue(saved.stream().noneMatch(e -> e.type() == ChatEventType.SESSION_STATUS_IDLE
                        || e.type() == ChatEventType.SESSION_STATUS_TERMINATED),
                "挂起驻留不得落终态状态事件");
        // then：相位迁移 running → awaiting_confirmation（对外 status 恒为 running）
        verify(sessionRepository).transition(session.sessionId(), Transition.PHASE_AWAIT);
        // then：挂起即物理轮终局——完成阻塞收轮（agent 释放）+ 释放 turn 租约，不经终态出口（不落 idle 终态）
        verify(agent).close();
        verify(coordinationLeaseService).releaseTurnLease(session.sessionId());
        verify(sessionRepository, never()).transition(anyString(), eq(Transition.FINISH_TURN));
        // then（挂起收轮同样取消续约任务：等待横跨用户思考时间，不得有多余续约）
        verify(leaseRenewalHandle).cancel();
    }

    // ==================== 红线②：D19 批次 / 候选错配的编排侧收敛 ====================

    /**
     * D19 挂起侧批次对齐的<b>端到端收敛</b>固化（拒绝挂起的判定与抛点次序已迁
     * {@code TurnFinalizerTest} 直测，本用例承接其编排侧半边红线——拒绝挂起后 MUST 沿
     * {@code onStreamError → finalizeFailed} 收敛为执行错误终态并照常收轮）。
     */
    @Test
    void should_convergeToErrorTerminal_when_sendMessageAsync_given_suspendBatchIdsNotRegistered() {
        // given（候选 tc-1 已登记，挂起信号批次 id 全部未登记 → 错配拒绝挂起）
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        BuiltAgent agent = wireHappyPathForExecution();
        bindConnection(session);
        when(agentRunExecutor.streamEvents(any(BuiltAgent.class), anyString(), anyString(), anyString()))
                .thenReturn(Flux.just(
                        AgentStreamSignal.tool(AgentStreamSignalType.TOOL_CALL_START, "tc-1", "search", null, null),
                        AgentStreamSignal.tool(AgentStreamSignalType.TOOL_CALL_DELTA, "tc-1", "search",
                                "{\"q\":\"x\"}", null),
                        AgentStreamSignal.tool(AgentStreamSignalType.TOOL_CALL_END, "tc-1", "search", null, null),
                        AgentStreamSignal.hitl(AgentStreamSignalType.HUMAN_CONFIRM_REQUIRED, "reply-1",
                                List.of("ghost-id"))));

        // when
        service.sendMessageAsync(new SendMessageCommand(session.sessionId(), "执行"));

        // then（不执行挂起相位迁移、不确立等待）
        verify(sessionRepository, never()).transition(anyString(), eq(Transition.PHASE_AWAIT));
        // then（收敛为执行错误终态：session.error + thread_status_idle + status_idle(error)）
        List<ChatEvent> saved = savedChatEvents();
        ChatEvent error = firstOfType(saved, ChatEventType.SESSION_ERROR);
        assertTrue(error.payload().contains("DEEP_AGENT_RUN_ERROR"), "session.error 应携带错误码");
        assertTrue(error.payload().contains("HITL"), "session.error 消息应含会话与批次定位信息");
        assertEquals(ChatEventType.SESSION_THREAD_STATUS_IDLE, saved.get(saved.size() - 2).type());
        assertTrue(firstOfType(saved, ChatEventType.SESSION_STATUS_IDLE).payload()
                        .contains("\"stop_reason\":{\"type\":\"error\"}"),
                "session.status_idle 应携带 stop_reason=error");
        verify(sessionRepository).transition(session.sessionId(), Transition.FINISH_TURN);
        // then（收轮：agent 释放 + 租约释放 + 停续约）
        verify(agent).close();
        verify(coordinationLeaseService).releaseTurnLease(session.sessionId());
        verify(leaseRenewalHandle).cancel();
    }

    /**
     * D19 对称分支②的端到端收敛固化（本轮候选登记为空：MUST NOT 以仅状态事件确立残缺等待）。
     */
    @Test
    void should_convergeToErrorTerminal_when_sendMessageAsync_given_suspendWithoutCandidateRegistered() {
        // given（挂起信号携带批次 id，但本轮无任何候选登记）
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        BuiltAgent agent = wireHappyPathForExecution();
        bindConnection(session);
        when(agentRunExecutor.streamEvents(any(BuiltAgent.class), anyString(), anyString(), anyString()))
                .thenReturn(Flux.just(
                        AgentStreamSignal.hitl(AgentStreamSignalType.HUMAN_CONFIRM_REQUIRED, "reply-1",
                                List.of("tc-1"))));

        // when
        service.sendMessageAsync(new SendMessageCommand(session.sessionId(), "执行"));

        // then（同样不确立等待，改以执行错误终态收敛回 idle）
        verify(sessionRepository, never()).transition(anyString(), eq(Transition.PHASE_AWAIT));
        List<ChatEvent> saved = savedChatEvents();
        assertTrue(firstOfType(saved, ChatEventType.SESSION_ERROR).payload().contains("HITL"),
                "session.error 应携带批次错配定位信息");
        verify(sessionRepository).transition(session.sessionId(), Transition.FINISH_TURN);
        verify(coordinationLeaseService).releaseTurnLease(session.sessionId());
        verify(agent).close();
    }

    // ==================== 红线③：防止永久阻塞的收尾处理（落库失败仍收轮） ====================

    @Test
    void should_completeFutureAndSkipTerminalEvents_when_onStreamError_given_strictFlushFails() {
        // given（流异常走 finalizeFailed → 终态事务首行命中落库失败标记抛异常）
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        BuiltAgent agent = wireHappyPathForExecution();
        bindConnection(session);
        wirePersister(strictFlushFailingPersister());
        when(agentRunExecutor.streamEvents(any(BuiltAgent.class), anyString(), anyString(), anyString()))
                .thenReturn(Flux.error(new RuntimeException("model 调用失败")));

        // when
        assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> service.sendMessageAsync(new SendMessageCommand(session.sessionId(), "你好")));

        // then（不落 session.error / session.status_idle，不迁移状态）
        verify(sessionRepository, never()).transition(anyString(), eq(Transition.FINISH_TURN));
        List<ChatEvent> saved = savedChatEvents();
        assertTrue(saved.stream().noneMatch(e -> e.type() == ChatEventType.SESSION_ERROR
                        || e.type() == ChatEventType.SESSION_STATUS_IDLE),
                "排空失败后不得产生错误终态事件，实际: " + saved.stream().map(e -> e.type().name()).toList());
        // then（onStreamError 尾部完成信号必达：收轮 + 停续约 + 释放租约）
        verify(leaseRenewalHandle).cancel();
        verify(coordinationLeaseService, atLeastOnce()).releaseTurnLease(session.sessionId());
        verify(agent).close();
    }

    // ==================== 启动顺序与快速失败（D4） ====================

    @Test
    void should_acquireLeaseBeforeCasAndClearPoisonAfterCommit_when_sendMessageAsync_given_roundStart() {
        // given（落库端口以 mock 承载，逐方法观察清除落库失败标记与入队的先后次序）
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        wireHappyPathForExecution();
        bindConnection(session);
        ChatEventPersister persister = mock(ChatEventPersister.class);
        wirePersister(persister);
        when(agentRunExecutor.streamEvents(any(BuiltAgent.class), anyString(), anyString(), anyString()))
                .thenReturn(endSignals());

        // when
        service.sendMessageAsync(new SendMessageCommand(session.sessionId(), "你好"));

        // then（D4 启动顺序：租约 NX 先于 PG CAS；两个 running 状态事件在启动事务内按权威次序落库；
        //        CAS 提交后才清除旧轮落库失败标记并广播）
        InOrder startOrder = inOrder(coordinationLeaseService, sessionRepository, chatEventRepository, persister);
        startOrder.verify(coordinationLeaseService).tryAcquireTurnLease(session.sessionId());
        startOrder.verify(sessionRepository).transition(session.sessionId(), Transition.BEGIN_TURN);
        startOrder.verify(chatEventRepository).save(argThat(e -> e.type() == ChatEventType.SESSION_STATUS_RUNNING));
        startOrder.verify(chatEventRepository)
                .save(argThat(e -> e.type() == ChatEventType.SESSION_THREAD_STATUS_RUNNING));
        startOrder.verify(persister).clearPoisonFlag(session.sessionId());
    }

    @Test
    void should_releaseLeaseQuietlyAndSkipStream_when_sendMessageAsync_given_startTransactionCasFails() {
        // given（租约已到手，PG CAS 落空——单活跃执行约束按 409 冲突语义拒绝）
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        wireHappyPathForExecution();
        when(sessionRepository.transition(session.sessionId(), Transition.BEGIN_TURN)).thenReturn(0);

        // when（异步路径把冲突隔离为日志，不外抛）
        assertDoesNotThrow(() -> service.sendMessageAsync(new SendMessageCommand(session.sessionId(), "你好")));

        // then（抢占成功而 CAS 落空：owner-scoped 归还租约，不遗留到 TTL；事件流不启动）
        verify(coordinationLeaseService).releaseTurnLease(session.sessionId());
        verify(sessionRepository, never()).transition(anyString(), eq(Transition.FINISH_TURN));
        verify(agentFactory, never()).build(any(AgentAssemblySpec.class));
        verify(agentRunExecutor, never()).streamEvents(any(BuiltAgent.class), anyString(), anyString(), anyString());
    }

    @Test
    void should_notTouchPg_when_sendMessageAsync_given_turnLeaseHeldByPeer() {
        // given（租约确证已被占用：同键存在未过期租约）
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        wireHappyPathForExecution();
        when(coordinationLeaseService.tryAcquireTurnLease(session.sessionId())).thenReturn(false);

        // when
        assertDoesNotThrow(() -> service.sendMessageAsync(new SendMessageCommand(session.sessionId(), "你好")));

        // then（PG 状态一字未动：未启动即无 CAS、无事件、无归还——未曾持有不得归还）
        verify(sessionRepository, never()).transition(anyString(), any(Transition.class));
        verify(chatEventRepository, never()).save(any(ChatEvent.class));
        verify(coordinationLeaseService, never()).releaseTurnLease(anyString());
    }

    @Test
    void should_lookupSessionOnly_when_sendMessageAsync_given_missingSession() {
        // given（会话不存在：执行路径前置校验快速失败）
        when(sessionRepository.findBySessionId("sess_ghost")).thenReturn(Optional.empty());

        // when（异常被异步路径隔离为日志）
        assertDoesNotThrow(() -> service.sendMessageAsync(new SendMessageCommand("sess_ghost", "你好")));

        // then（不抢占租约、不写事件表）
        verify(sessionRepository).findBySessionId("sess_ghost");
        verify(coordinationLeaseService, never()).tryAcquireTurnLease(anyString());
        verify(chatEventRepository, never()).save(any(ChatEvent.class));
    }

    @Test
    void should_notBeginTurn_when_sendMessageAsync_given_terminatedSession() {
        // given（已终止会话：终态会话不得再开轮）
        AgentSession session = sessionWithStatus(AgentSessionStatus.TERMINATED);
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));

        // when
        assertDoesNotThrow(() -> service.sendMessageAsync(new SendMessageCommand(session.sessionId(), "你好")));

        // then（快速失败先于租约抢占与状态 CAS）
        verify(coordinationLeaseService, never()).tryAcquireTurnLease(anyString());
        verify(sessionRepository, never()).transition(anyString(), any(Transition.class));
    }

    // ==================== 完成回调（SSE 直连场景：终态送达后才断开） ====================

    @Test
    void should_invokeOnCompleteAfterTerminalEvents_when_sendMessageAsync_given_onCompleteCallback() {
        // given（正常收流轮次：终态事件入账后才回调）
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        BuiltAgent agent = wireHappyPathForExecution();
        bindConnection(session);
        when(agentRunExecutor.streamEvents(any(BuiltAgent.class), anyString(), anyString(), anyString()))
                .thenReturn(endSignals());
        AtomicInteger eventsAtCallback = new AtomicInteger(-1);

        // when
        service.sendMessageAsync(new SendMessageCommand(session.sessionId(), "你好"),
                () -> eventsAtCallback.set(savedChatEvents().size()));

        // then（回调时终态事件已入账：processing + status_idle 两行以上）
        assertTrue(eventsAtCallback.get() >= 2,
                "onComplete 须在轮次终态事件入账后触发，实际入账数: " + eventsAtCallback.get());
        verify(sessionRepository).transition(session.sessionId(), Transition.FINISH_TURN);
        verify(agent).close();
        verify(coordinationLeaseService).releaseTurnLease(session.sessionId());
    }

    @Test
    void should_isolateCallbackFailure_when_sendMessageAsync_given_onCompleteThrows() {
        // given（完成回调抛异常：SSE 关闭失败不得污染轮次收尾）
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        BuiltAgent agent = wireHappyPathForExecution();
        bindConnection(session);
        when(agentRunExecutor.streamEvents(any(BuiltAgent.class), anyString(), anyString(), anyString()))
                .thenReturn(endSignals());

        // when
        assertDoesNotThrow(() -> service.sendMessageAsync(new SendMessageCommand(session.sessionId(), "你好"),
                () -> {
                    throw new IllegalStateException("emitter 已关闭");
                }));

        // then（本轮照常终态化与收轮）
        verify(sessionRepository).transition(session.sessionId(), Transition.FINISH_TURN);
        verify(agent).close();
        verify(coordinationLeaseService).releaseTurnLease(session.sessionId());
    }

    // ==================== 执行控制面承载（withTurn） ====================

    @Test
    void should_setAndConditionallyClearCurrentTurn_when_withTurn_given_taskBody() {
        // given（真实会话级聚合：槽位读写按真实实现执行）
        AgentSession session = idleSession();
        AgentSessionContext sessionContext = bindConnection(session);
        AtomicReference<TurnControl> seenInside = new AtomicReference<>();

        // when
        service.withTurn(sessionContext, turn -> {
            seenInside.set(turn);
            assertSame(turn, sessionContext.currentTurn(), "执行体内应见本轮控制面已置入槽位");
        });

        // then（finally 条件清除：槽位仍指向本轮 → 置空）
        assertNotNull(seenInside.get());
        assertNull(sessionContext.currentTurn());
    }

    @Test
    void should_stillClearCurrentTurn_when_withTurn_given_taskThrows() {
        // given（执行体抛运行时异常：控制面不得残留）
        AgentSession session = idleSession();
        AgentSessionContext sessionContext = bindConnection(session);

        // when & then（异常原样上抛，finally 仍清槽）
        assertThrows(IllegalStateException.class,
                () -> service.withTurn(sessionContext, turn -> {
                    throw new IllegalStateException("装配失败");
                }));
        assertNull(sessionContext.currentTurn());
    }

    // ==================== 租约抢占 / 归还 ====================

    @Test
    void should_throwConflict_when_acquireTurnLeaseOrConflict_given_leaseHeldByPeer() {
        // given（确证失权：Redis 返回获取失败）
        when(coordinationLeaseService.tryAcquireTurnLease("sess_lease")).thenReturn(false);

        // when
        SessionBusyException ex = assertThrows(SessionBusyException.class,
                () -> service.acquireTurnLeaseOrConflict("sess_lease", "HITL 领取"));

        // then（409 冲突语义携带平台错误码与场景后缀）
        assertTrue(ex.getMessage().contains("DEEP_AGENT_SESSION_BUSY"), "错误消息应含冲突错误码");
        assertTrue(ex.getMessage().contains("HITL 领取"), "错误消息应含场景区分后缀");
    }

    @Test
    void should_swallowStoreFailure_when_releaseTurnLeaseQuietly_given_releaseThrows() {
        // given（归还自身异常：TTL 自过期兜底）
        doThrow(new RuntimeException("Redis 不可达")).when(coordinationLeaseService).releaseTurnLease("sess_lease");

        // when & then（MUST NOT 以归还异常掩盖原始失败）
        assertDoesNotThrow(() -> service.releaseTurnLeaseQuietly("sess_lease", "启动事务"));
        verify(coordinationLeaseService).releaseTurnLease("sess_lease");
    }

    // ==================== 续跑入口（newRoundContext + runRound） ====================

    @Test
    void should_runSuppliedStreamAndFinalizeNormally_when_runRound_given_newRoundContext() {
        // given（续跑轮：执行现场由领取事务产出，本轮以显式流供给驱动骨架）
        AgentSession session = idleSession();
        BuiltAgent agent = wireHappyPathForExecution();
        AgentSessionContext sessionContext = bindConnection(session);
        TurnRunState runState = sessionContext.beginRound(0L);
        ExecutionContext context = new ExecutionContext(sessionContext, runState, "sthr_main");
        TurnControl turn = new TurnControl();
        sessionContext.beginTurn(turn);
        AtomicReference<BuiltAgent> supplied = new AtomicReference<>();
        List<RuntimeException> closeFailures = new ArrayList<>();
        RoundContext roundContext = service.newRoundContext(context, session, turn, "续跑轮",
                closeFailures::add);

        // when
        service.runRound(roundContext, built -> {
            supplied.set(built);
            return endSignals();
        });

        // then（流供给收到本轮装配的 Agent 句柄，终态与收轮照常）
        assertSame(agent, supplied.get(), "runRound 的流供给必须收到本轮构建的 Agent");
        verify(agentFactory).build(any(AgentAssemblySpec.class));
        verify(sessionRepository).transition(session.sessionId(), Transition.FINISH_TURN);
        verify(coordinationLeaseService).releaseTurnLease(session.sessionId());
        verify(leaseRenewalHandle).cancel();
        assertSame(turn, sessionContext.currentTurn(), "runRound 不清调用方槽位（清槽权威在 withTurn）");
        assertTrue(closeFailures.isEmpty(), "Agent 句柄正常释放，无需定制失败出口");
    }

    // ==================== 消息发送：事件流编排 ====================

    @Test
    void should_persistAndBroadcastSourceEvents_when_sendMessageAsync_given_thinkingAndMessageStream() {
        // given
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        BuiltAgent agent = wireHappyPathForExecution();
        bindConnection(session);
        when(agentRunExecutor.streamEvents(any(BuiltAgent.class), anyString(), anyString(), anyString()))
                .thenReturn(Flux.just(
                        AgentStreamSignal.of(AgentStreamSignalType.THINKING_DELTA, "思考", "blk-0"),
                        AgentStreamSignal.of(AgentStreamSignalType.THINKING_END, null, "blk-0"),
                        AgentStreamSignal.of(AgentStreamSignalType.TEXT_DELTA, "你好", "blk-1"),
                        AgentStreamSignal.of(AgentStreamSignalType.TEXT_END, null, "blk-1"),
                        AgentStreamSignal.of(AgentStreamSignalType.AGENT_END, null, null)));

        // when
        service.sendMessageAsync(new SendMessageCommand(session.sessionId(), "你好"));

        // then（启动事务 CAS + 启动双状态事件 + 源码事件按序落库 + 终态回 idle）
        verify(sessionRepository).transition(session.sessionId(), Transition.BEGIN_TURN);
        List<ChatEvent> saved = savedChatEvents();
        assertEquals(6, saved.size());
        assertEquals(ChatEventType.SESSION_STATUS_RUNNING, saved.get(0).type());
        assertEquals(ChatEventType.SESSION_THREAD_STATUS_RUNNING, saved.get(1).type());
        assertEquals(ChatEventType.AGENT_THINKING, saved.get(2).type());
        assertEquals("{}", saved.get(2).payload(), "agent.thinking 仅表达存在性，不暴露推理正文");
        assertEquals(ChatEventType.AGENT_MESSAGE, saved.get(3).type());
        assertTrue(saved.get(3).payload().contains("你好"),
                "agent.message payload 应携带块完整文本");
        assertEquals(ChatEventType.SESSION_THREAD_STATUS_IDLE, saved.get(4).type());
        assertEquals(ChatEventType.SESSION_STATUS_IDLE, saved.get(5).type());
        assertTrue(saved.get(5).payload().contains("\"stop_reason\":{\"type\":\"end_turn\"}"),
                "session.status_idle 应携带 stop_reason=end_turn");
        // 流内增量帧（event_start / event_delta）仅经 pushFrame 实时下发：不落库、不消耗 seq
        assertTrue(savedChatEvents().stream().noneMatch(e -> e.type() == ChatEventType.EVENT_START
                        || e.type() == ChatEventType.EVENT_DELTA),
                "event_start / event_delta 帧不得落库");
        // 事件信封统一 evt_ 前缀
        assertTrue(saved.stream().allMatch(e -> e.eventId().startsWith(ChatEvent.EVENT_ID_PREFIX)));
        // 终态：回归 IDLE + buffered 源码事件广播（6 条），增量帧 3 帧（thinking start / text start / text delta）
        // ——thinking 协商仅 start 无 delta，不暴露推理内容
        verify(sessionRepository).transition(session.sessionId(), Transition.FINISH_TURN);
        verify(sessionRepository).touchLastActive(session.sessionId());
        verify(connectionHandle, times(6)).push(any(ChatEvent.class));
        verify(connectionHandle, times(3)).pushFrame(any(StreamFrame.class));
        verify(agent).close();
    }

    @Test
    void should_schedulePeriodicRenewalAndCancelOnRoundEnd_when_sendMessageAsync_given_terminatedRound() {
        // given（正常轮次：turn 租约定时续约任务按 TTL/3 周期调度，轮次终态后统一取消）
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        wireHappyPathForExecution();
        bindConnection(session);
        when(agentRunExecutor.streamEvents(any(BuiltAgent.class), anyString(), anyString(), anyString()))
                .thenReturn(Flux.just(AgentStreamSignal.of(AgentStreamSignalType.AGENT_END, null, null)));

        // when
        service.sendMessageAsync(new SendMessageCommand(session.sessionId(), "你好"));

        // then（续约任务以 TTL/3 为周期启动（首轮延迟一个周期后开始），覆盖长静默工具调用窗口）
        long expectedPeriodMs = CoordinationLeaseService.TURN_LEASE_TTL.toMillis() / 3;
        verify(leaseRenewalScheduler).schedule(any(Runnable.class), eq(Duration.ofMillis(expectedPeriodMs)));
        // then（轮次终态出口统一 cancel：无多余续约）
        verify(leaseRenewalHandle).cancel();
    }

    @Test
    void should_silentlyAbortWithoutTerminalWrite_when_leaseRenewalReturnsFalse_given_inFlightRound() {
        // given（轮次进行中续约周期触发且续约失败：执行权丧失 → fail-closed 静默中止）
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        BuiltAgent agent = wireHappyPathForExecution();
        bindConnection(session);
        // 续约任务体捕获（mock 调度器不实际执行，由首个流信号侧效模拟中途续约周期触发）
        AtomicReference<Runnable> renewalTask = new AtomicReference<>();
        when(leaseRenewalScheduler.schedule(any(Runnable.class), any(Duration.class)))
                .thenAnswer(inv -> {
                    renewalTask.set(inv.getArgument(0));
                    return leaseRenewalHandle;
                });
        // 续约返回 false：租约已过期被回收或已被他实例接管
        when(coordinationLeaseService.renewTurnLease(anyString())).thenReturn(false);
        when(agentRunExecutor.streamEvents(any(BuiltAgent.class), anyString(), anyString(), anyString()))
                .thenReturn(Flux.just(AgentStreamSignal.of(AgentStreamSignalType.TEXT_DELTA, "第一段", "blk-1"))
                        .doOnNext(signal -> {
                            Runnable task = renewalTask.get();
                            if (task != null) {
                                task.run();
                            }
                        })
                        .concatWith(Flux.just(
                                AgentStreamSignal.of(AgentStreamSignalType.TEXT_DELTA, "第二段", "blk-1"),
                                AgentStreamSignal.of(AgentStreamSignalType.TEXT_END, null, "blk-1"),
                                AgentStreamSignal.of(AgentStreamSignalType.AGENT_END, null, null))));

        // when（fail-closed 中止不抛出：阻塞等待经中止句柄完成收轮）
        service.sendMessageAsync(new SendMessageCommand(session.sessionId(), "你好"));

        // then（续约任务确实被触发且确证失败）
        verify(coordinationLeaseService).renewTurnLease(session.sessionId());
        // then（不写终态事件：仅启动事务的启动双状态事件落库，流内 / 终态事件一律不产生）
        List<ChatEvent> saved = savedChatEvents();
        assertTrue(saved.stream().allMatch(e -> e.type() == ChatEventType.SESSION_STATUS_RUNNING
                        || e.type() == ChatEventType.SESSION_THREAD_STATUS_RUNNING),
                "fail-closed 后不得落库流内事件或任何终态事件，实际: "
                        + saved.stream().map(e -> e.type().name()).toList());
        // then（不做会话状态 CAS：不回 idle / 不进 terminated）
        verify(sessionRepository, never()).transition(anyString(), eq(Transition.FINISH_TURN));
        verify(sessionRepository, never()).transition(anyString(), eq(Transition.TERMINATE));
        // then（不广播终态帧 / 流内增量帧：启动状态事件之外的推送一律不得发生）
        ArgumentCaptor<ChatEvent> pushed = ArgumentCaptor.forClass(ChatEvent.class);
        verify(connectionHandle, never()).pushFrame(any(StreamFrame.class));
        verify(connectionHandle, atLeastOnce()).push(pushed.capture());
        assertTrue(pushed.getAllValues().stream()
                        .allMatch(e -> e.type() == ChatEventType.SESSION_STATUS_RUNNING
                                || e.type() == ChatEventType.SESSION_THREAD_STATUS_RUNNING),
                "fail-closed 后仅允许启动状态事件广播，终态 / 流内事件不得广播");
        // then（不释放租约：终态权不归本实例，租约自然过期由复位路径 / 新持有者接管）
        verify(coordinationLeaseService, never()).releaseTurnLease(anyString());
        // then（进程内资源释放：续约任务取消 + SDK 句柄关闭）
        verify(leaseRenewalHandle).cancel();
        verify(agent).close();
    }

    @Test
    void should_passUserInputThrough_when_sendMessageAsync_given_mountedFileSession() {
        // given（会话挂载文件：挂载不再内联展开，用户输入原样进入数据面（D13），
        // 宿主副本经装配解析期 reconcile 对账、bind mount 可见）
        AgentSession session = AgentSession.createWithTrigger(
                "1", "agent-a", "1.0.0", "{}", null, null, null,
                List.of(SessionResource.file("file_1", null)));
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        wireHappyPathForExecution();
        bindConnection(session);
        when(agentRunExecutor.streamEvents(any(BuiltAgent.class), anyString(), anyString(), anyString()))
                .thenReturn(Flux.just(AgentStreamSignal.of(AgentStreamSignalType.AGENT_END, null, null)));

        // when
        service.sendMessageAsync(new SendMessageCommand(session.sessionId(), "你好"));

        // then（事件表与数据面只见原始输入，不含文件正文；会话归属用户透传）
        verify(agentRunExecutor).streamEvents(any(BuiltAgent.class),
                eq("你好"), eq(session.sessionId()), eq("1"));
    }

    @Test
    void should_persistToolUseAndTruncatedResult_when_sendMessageAsync_given_toolStream() {
        // given
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        BuiltAgent agent = wireHappyPathForExecution();
        bindConnection(session);
        // 工具结果 20KB：head 16KB 实时窗口 + tail 4KB 截断补发
        String bigResult = "X".repeat(20 * 1024);
        when(agentRunExecutor.streamEvents(any(BuiltAgent.class), anyString(), anyString(), anyString()))
                .thenReturn(Flux.just(
                        AgentStreamSignal.tool(AgentStreamSignalType.TOOL_CALL_START, "tc-1", "search", null, null),
                        AgentStreamSignal.tool(AgentStreamSignalType.TOOL_CALL_DELTA, "tc-1", null, "{\"q\":\"x\"}", null),
                        AgentStreamSignal.tool(AgentStreamSignalType.TOOL_CALL_END, "tc-1", "search", null, null),
                        AgentStreamSignal.tool(AgentStreamSignalType.TOOL_RESULT_TEXT_DELTA, "tc-1", "search", "R1", null),
                        AgentStreamSignal.tool(AgentStreamSignalType.TOOL_RESULT_TEXT_DELTA, "tc-1", "search", bigResult, null),
                        AgentStreamSignal.tool(AgentStreamSignalType.TOOL_RESULT_END, "tc-1", "search", null, "SUCCESS"),
                        AgentStreamSignal.of(AgentStreamSignalType.AGENT_END, null, null)));

        // when
        service.sendMessageAsync(new SendMessageCommand(session.sessionId(), "你好"));

        // then：agent.tool_use 携带聚合入参，agent.tool_result 携带 head+tail 截断输出
        List<ChatEvent> saved = savedChatEvents();
        assertEquals(ChatEventType.SESSION_STATUS_RUNNING, saved.get(0).type());
        assertEquals(ChatEventType.SESSION_THREAD_STATUS_RUNNING, saved.get(1).type());
        assertEquals(ChatEventType.AGENT_TOOL_USE, saved.get(2).type());
        assertTrue(saved.get(2).payload().contains("\"q\":\"x\""),
                "agent.tool_use payload 应携带聚合后的完整入参");
        assertEquals(ChatEventType.AGENT_TOOL_RESULT, saved.get(3).type());
        assertTrue(saved.get(3).payload().contains("\"truncated\":true"),
                "agent.tool_result 应标记截断");
        assertTrue(saved.get(3).payload().contains("省略"),
                "agent.tool_result 输出应携带省略标记");
        assertEquals(ChatEventType.SESSION_STATUS_IDLE, saved.get(5).type());
        verify(sessionRepository).transition(session.sessionId(), Transition.FINISH_TURN);
        // 工具域不在 event_deltas[] 增量协商值域：全程不推任何流式帧
        verify(connectionHandle, never()).pushFrame(any(StreamFrame.class));
        verify(agent).close();
    }

    @Test
    void should_aggregateDeltasByFlushInterval_when_sendMessageAsync_given_largeFlushInterval() {
        // given（连接增量刷写间隔取极大值：仅 event_start 与首段 delta 立发，未刷完尾部由 buffered 覆盖）
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        BuiltAgent agent = wireHappyPathForExecution();
        bindConnection(session);
        when(connectionHandle.deltaFlushIntervalMs()).thenReturn(60_000L);
        when(agentRunExecutor.streamEvents(any(BuiltAgent.class), anyString(), anyString(), anyString()))
                .thenReturn(Flux.just(
                        AgentStreamSignal.of(AgentStreamSignalType.TEXT_DELTA, "你", "blk-1"),
                        AgentStreamSignal.of(AgentStreamSignalType.TEXT_DELTA, "好", "blk-1"),
                        AgentStreamSignal.of(AgentStreamSignalType.TEXT_DELTA, "呀", "blk-1"),
                        AgentStreamSignal.of(AgentStreamSignalType.TEXT_END, null, "blk-1"),
                        AgentStreamSignal.of(AgentStreamSignalType.AGENT_END, null, null)));

        // when
        service.sendMessageAsync(new SendMessageCommand(session.sessionId(), "你好"));

        // then（增量帧共 2 帧：text start + 首段聚合 delta；后续片段进缓冲未刷写）
        ArgumentCaptor<StreamFrame> frames = ArgumentCaptor.forClass(StreamFrame.class);
        verify(connectionHandle, times(2)).pushFrame(frames.capture());
        List<StreamFrame> pushed = frames.getAllValues();
        assertEquals(ChatEventType.EVENT_START, pushed.get(0).frameType());
        assertEquals(ChatEventType.AGENT_MESSAGE, pushed.get(0).targetType());
        assertEquals(ChatEventType.EVENT_DELTA, pushed.get(1).frameType());
        assertTrue(pushed.get(1).payloadJson().contains("\"text\":\"你\""),
                "首段 delta 应立发，后续片段进入聚合缓冲");
        // 帧共享 buffered 事件 ID（SSE id == event_start.event.id == delta.event_id == buffered id）
        assertEquals(pushed.get(0).eventId(), pushed.get(1).eventId());
        List<ChatEvent> saved = savedChatEvents();
        ChatEvent bufferedMessage = saved.stream()
                .filter(e -> e.type() == ChatEventType.AGENT_MESSAGE).findFirst().orElseThrow();
        assertEquals(pushed.get(0).eventId(), bufferedMessage.eventId(),
                "buffered agent.message 应与增量帧共用同一 evt_ ID");
        assertTrue(bufferedMessage.payload().contains("你好呀"),
                "buffered 权威事件应携带完整累积文本（含未刷出的尾部增量）");
        verify(agent).close();
    }

    @Test
    void should_persistSpanUsage_when_sendMessageAsync_given_modelCallSignals() {
        // given
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        BuiltAgent agent = wireHappyPathForExecution();
        bindConnection(session);
        when(agentRunExecutor.streamEvents(any(BuiltAgent.class), anyString(), anyString(), anyString()))
                .thenReturn(Flux.just(
                        AgentStreamSignal.of(AgentStreamSignalType.MODEL_CALL_START, null, null),
                        new AgentStreamSignal(AgentStreamSignalType.MODEL_CALL_END, null, null, null, null, null,
                                null, 10, 20, "gpt-4", null, null),
                        AgentStreamSignal.of(AgentStreamSignalType.AGENT_RESULT, null, null)
                                .withResultText("答案"),
                        AgentStreamSignal.of(AgentStreamSignalType.AGENT_END, null, null)));

        // when
        service.sendMessageAsync(new SendMessageCommand(session.sessionId(), "你好"));

        // then：span.model_request_start/end 承载模型调用边界，end 下沉 usage token 计量
        List<ChatEvent> saved = savedChatEvents();
        assertEquals(ChatEventType.SPAN_MODEL_REQUEST_START, saved.get(2).type());
        assertEquals(ChatEventType.SPAN_MODEL_REQUEST_END, saved.get(3).type());
        assertTrue(saved.get(3).payload().contains("\"input_tokens\":10"),
                "usage 应下沉 input_tokens");
        assertTrue(saved.get(3).payload().contains("\"output_tokens\":20"),
                "usage 应下沉 output_tokens");
        assertTrue(saved.get(3).payload().contains("gpt-4"),
                "usage 应携带模型标识");
        // 缺失文本块时以 AGENT_RESULT 最终文本兜底落一条 agent.message
        assertEquals(ChatEventType.AGENT_MESSAGE, saved.get(4).type());
        assertTrue(saved.get(4).payload().contains("答案"));
        assertEquals(ChatEventType.SESSION_STATUS_IDLE, saved.get(6).type());
        verify(sessionRepository).transition(session.sessionId(), Transition.FINISH_TURN);
        verify(agent).close();
    }

    @Test
    void should_refireTargetedInterrupt_when_runStream_given_cancelingArrivedBeforeSubscribe() {
        // given：取消先于执行流启动到达（进程内标志未置位、activate 未触发句柄），
        // canceling 持久痕迹可读——2.0.3 框架在每次调用入口 reset interruptControl 并随
        // 状态重载换新，activate 即时触发会被抹掉，须由订阅建立后的两源复检补触发（D8）
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        BuiltAgent agent = wireHappyPathForExecution();
        bindConnection(session);
        when(sessionRepository.isCancelling(session.sessionId())).thenReturn(true);
        when(agentRunExecutor.streamEvents(any(BuiltAgent.class), anyString(), anyString(), anyString()))
                .thenReturn(Flux.just(
                        AgentStreamSignal.of(AgentStreamSignalType.TEXT_DELTA, "你好", "blk-1")));

        // when
        service.sendMessageAsync(new SendMessageCommand(session.sessionId(), "你好"));

        // then：补触发恰好打一次定向中断（activate 路径未触发，唯一来源是订阅后复检）
        verify(agent, times(1)).interrupt(session.userId(), session.sessionId());
        // 终态仍按中断语义收敛（两源谓词兜底，不因补触发与否产生状态死路）：
        // 真实中断下 SDK 收流不发 AGENT_END，onStreamComplete 两源命中走中断终态（收场二事件，无独立 interrupted 事件）
        List<ChatEvent> captured = savedChatEvents();
        assertEquals(ChatEventType.SESSION_THREAD_STATUS_IDLE, captured.get(captured.size() - 2).type());
        ChatEvent last = captured.get(captured.size() - 1);
        assertEquals(ChatEventType.SESSION_STATUS_IDLE, last.type());
        assertTrue(last.payload().contains("\"stop_reason\":{\"type\":\"interrupted\"}"));
    }

    @Test
    void should_logBusy_when_sendMessageAsync_given_sessionAlreadyProcessing() {
        // given（会话已被其他执行抢占 processing：CAS 失败 → 异步路径记录日志且不产生任何事件；
        // 启动顺序为租约 NX 先于 PG CAS，故租约先拿到、CAS 落空后必须归还）
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        wireTransactionTemplate();
        when(coordinationLeaseService.tryAcquireTurnLease(session.sessionId())).thenReturn(true);
        when(sessionRepository.transition(session.sessionId(), Transition.BEGIN_TURN)).thenReturn(0);

        // when
        service.sendMessageAsync(new SendMessageCommand(session.sessionId(), "你好"));

        // then（失败被异步路径隔离为日志，副作用上无事件落库；租约 owner-scoped 归还，不遗留到 TTL）
        verify(sessionRepository).transition(session.sessionId(), Transition.BEGIN_TURN);
        verify(coordinationLeaseService).releaseTurnLease(session.sessionId());
        verify(chatEventRepository, never()).save(any(ChatEvent.class));
    }

    @Test
    void should_logBusy_when_sendMessageAsync_given_turnLeaseHeld() {
        // given（会话已有运行中的租约：协调层获取失败 → 异步路径记录日志且不产生任何事件）
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        when(coordinationLeaseService.tryAcquireTurnLease(session.sessionId())).thenReturn(false);

        // when
        service.sendMessageAsync(new SendMessageCommand(session.sessionId(), "你好"));

        // then（租约拒绝被异步路径隔离为日志：NX 先于 CAS，PG 状态一字未动、无事件落库）
        verify(coordinationLeaseService).tryAcquireTurnLease(session.sessionId());
        verify(sessionRepository, never()).transition(anyString(), any(Transition.class));
        verify(chatEventRepository, never()).save(any(ChatEvent.class));
    }

    @Test
    void should_lookupSessionOnlyAndPersistNothing_when_sendMessageAsync_given_missingSession_endToEnd() {
        // given（会话不存在：同步前置校验失败，异步任务内记录错误，无任何事件落库）
        when(sessionRepository.findBySessionId("nope")).thenReturn(Optional.empty());

        // when
        service.sendMessageAsync(new SendMessageCommand("nope", "你好"));

        // then
        verify(sessionRepository).findBySessionId("nope");
        verify(chatEventRepository, never()).save(any(ChatEvent.class));
    }

    @Test
    void should_notBeginTurnAndPersistNothing_when_sendMessageAsync_given_terminatedSession_endToEnd() {
        // given（已终止会话：异步路径拒绝执行且不触发 CAS）
        AgentSession session = idleSession().withStatus(AgentSessionStatus.TERMINATED);
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));

        // when
        service.sendMessageAsync(new SendMessageCommand(session.sessionId(), "你好"));

        // then（失败被异步路径隔离为日志，副作用上不触发 CAS / 落库）
        verify(sessionRepository, never()).transition(anyString(), eq(Transition.BEGIN_TURN));
        verify(chatEventRepository, never()).save(any(ChatEvent.class));
    }

    @Test
    void should_persistErrorTerminal_when_sendMessageAsync_given_streamErrors() {
        // given
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        BuiltAgent agent = wireHappyPathForExecution();
        bindConnection(session);
        // 执行失败时仍注册为运行中（非断连，真实注册表），onError 走 error 终止而非 interrupted
        when(agentRunExecutor.streamEvents(any(BuiltAgent.class), anyString(), anyString(), anyString()))
                .thenReturn(Flux.error(new RuntimeException("model 调用失败")));

        // when
        service.sendMessageAsync(new SendMessageCommand(session.sessionId(), "你好"));

        // then：session.error + 收场二事件（thread_status_idle + status_idle(error)）落库，会话回 IDLE
        List<ChatEvent> captured = savedChatEvents();
        assertEquals(5, captured.size()); // status_running + thread_status_running + session.error + thread_status_idle + status_idle
        assertEquals(ChatEventType.SESSION_STATUS_RUNNING, captured.get(0).type());
        assertEquals(ChatEventType.SESSION_ERROR, captured.get(2).type());
        assertTrue(captured.get(2).payload().contains("DEEP_AGENT_RUN_ERROR"),
                "session.error 应携带错误码");
        assertEquals(ChatEventType.SESSION_THREAD_STATUS_IDLE, captured.get(3).type());
        assertEquals(ChatEventType.SESSION_STATUS_IDLE, captured.get(4).type());
        assertTrue(captured.get(4).payload().contains("\"stop_reason\":{\"type\":\"error\"}"),
                "session.status_idle 应携带 stop_reason=error");
        verify(sessionRepository).transition(session.sessionId(), Transition.FINISH_TURN);
        verify(agent).close();
    }

    @Test
    void should_persistErrorTerminal_when_sendMessageAsync_given_buildFails() {
        // given
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        wireHappyPathForExecution();
        bindConnection(session);
        // 运行装配成功，但工厂构建失败（事件流启动前，会话级未注册运行中的句柄）：推导为 error 而非 interrupted
        when(agentFactory.build(any(AgentAssemblySpec.class)))
                .thenThrow(new DeepDataAgentException("DEEP_AGENT_BUILD_FAILED"));

        // when
        service.sendMessageAsync(new SendMessageCommand(session.sessionId(), "你好"));

        // then：session.error + 收场二事件（thread_status_idle + status_idle(error)）落库，会话回 IDLE（系统故障不误报中断）
        List<ChatEvent> captured = savedChatEvents();
        assertEquals(5, captured.size());
        assertEquals(ChatEventType.SESSION_ERROR, captured.get(2).type());
        assertEquals(ChatEventType.SESSION_STATUS_IDLE, captured.get(4).type());
        verify(sessionRepository).transition(session.sessionId(), Transition.FINISH_TURN);
    }

    @Test
    void should_persistInterruptedTerminal_when_sendMessageAsync_given_streamInterrupted() {
        // given：断连 / user.interrupt 中断发生在流处理期间（会话级聚合标记显式中断），SDK 正常收流但未发 AGENT_END
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        BuiltAgent agent = wireHappyPathForExecution();
        bindConnection(session);
        when(agentRunExecutor.streamEvents(any(BuiltAgent.class), anyString(), anyString(), anyString()))
                .thenAnswer(inv -> Flux.just(AgentStreamSignal.of(AgentStreamSignalType.TEXT_DELTA, "你好", "blk-1"))
                        .doOnNext(ignored -> sessionRegistry.get(session.sessionId())
                                .ifPresent(AgentSessionContext::interruptCurrentRun)));

        // when
        service.sendMessageAsync(new SendMessageCommand(session.sessionId(), "你好"));

        // then：流正常收尾但执行已被显式中断 → 收场二事件（thread_status_idle + status_idle(interrupted)）
        List<ChatEvent> captured = savedChatEvents();
        assertEquals(ChatEventType.SESSION_THREAD_STATUS_IDLE, captured.get(captured.size() - 2).type());
        assertEquals(ChatEventType.SESSION_STATUS_IDLE, captured.get(captured.size() - 1).type());
        assertTrue(captured.get(captured.size() - 1).payload()
                        .contains("\"stop_reason\":{\"type\":\"interrupted\"}"),
                "session.status_idle 应携带 stop_reason=interrupted");
        verify(sessionRepository).transition(session.sessionId(), Transition.FINISH_TURN);
        verify(agent).close();
        // 定向中断断言（D7）：中断句柄以会话身份 (userId, sessionId) 打向框架对应槽位
        verify(agent, atLeastOnce()).interrupt(session.userId(), session.sessionId());
    }

    @Test
    void should_persistTerminatedTerminal_when_sendMessageAsync_given_exceedMaxIters() {
        // given
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        BuiltAgent agent = wireHappyPathForExecution();
        bindConnection(session);
        when(agentRunExecutor.streamEvents(any(BuiltAgent.class), anyString(), anyString(), anyString()))
                .thenReturn(Flux.just(
                        AgentStreamSignal.of(AgentStreamSignalType.EXCEED_MAX_ITERS, null, null),
                        AgentStreamSignal.of(AgentStreamSignalType.AGENT_END, null, null)));

        // when
        service.sendMessageAsync(new SendMessageCommand(session.sessionId(), "你好"));

        // then：迭代上限为正常终态但会话进 terminated（收场二事件，stop_reason=max_iterations）
        List<ChatEvent> captured = savedChatEvents();
        assertEquals(ChatEventType.SESSION_THREAD_STATUS_TERMINATED, captured.get(captured.size() - 2).type());
        assertEquals(ChatEventType.SESSION_STATUS_TERMINATED, captured.get(captured.size() - 1).type());
        assertTrue(captured.get(captured.size() - 1).payload()
                        .contains("\"stop_reason\":{\"type\":\"max_iterations\"}"),
                "session.status_terminated 应携带 stop_reason=max_iterations");
        verify(sessionRepository).transition(session.sessionId(), Transition.TERMINATE);
        verify(agent).close();
    }

    // ==================== 取消 × 迭代上限竞态（fix-session-cancel-terminal-state） ====================

    @Test
    void should_convergeToIdle_when_finalizeTurn_given_cancelingAndExceedMaxIters() {
        // given：取消已同进程生效（进程内中断标志置位、canceling 持久痕迹已提交），迭代上限随流收流
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        BuiltAgent agent = wireHappyPathForExecution();
        bindConnection(session);
        lenient().when(sessionRepository.isCancelling(session.sessionId())).thenReturn(true);
        when(agentRunExecutor.streamEvents(any(BuiltAgent.class), anyString(), anyString(), anyString()))
                .thenReturn(Flux.just(
                                AgentStreamSignal.of(AgentStreamSignalType.EXCEED_MAX_ITERS, null, null),
                                AgentStreamSignal.of(AgentStreamSignalType.AGENT_END, null, null))
                        .doOnComplete(() -> sessionRegistry.get(session.sessionId())
                                .ifPresent(AgentSessionContext::interruptCurrentRun)));

        // when
        service.sendMessageAsync(new SendMessageCommand(session.sessionId(), "你好"));

        // then：取消优先收敛 idle + interrupted，terminated 路径完全不触及（无静默死路）
        List<ChatEvent> captured = savedChatEvents();
        assertEquals(ChatEventType.SESSION_THREAD_STATUS_IDLE, captured.get(captured.size() - 2).type());
        ChatEvent last = captured.get(captured.size() - 1);
        assertEquals(ChatEventType.SESSION_STATUS_IDLE, last.type());
        assertTrue(last.payload().contains("\"stop_reason\":{\"type\":\"interrupted\"}"));
        assertTrue(captured.stream().noneMatch(e -> e.type() == ChatEventType.SESSION_STATUS_TERMINATED),
                "取消进行中不得产生 terminated 终态事件");
        verify(sessionRepository, never()).transition(anyString(), eq(Transition.TERMINATE));
        verify(sessionRepository).transition(session.sessionId(), Transition.FINISH_TURN);
        verify(agent).close();
    }

    @Test
    void should_convergeToIdle_when_finalizeTurn_given_crossProcessCancelDbTraceOnly() {
        // given：跨进程取消——进程内标志未置位、仅共享库 canceling 持久痕迹可读，
        //        迭代上限随流收流；取消优先收敛 idle，TO_TERMINATED 迁移零调用
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        BuiltAgent agent = wireHappyPathForExecution();
        bindConnection(session);
        when(sessionRepository.isCancelling(session.sessionId())).thenReturn(true);
        when(agentRunExecutor.streamEvents(any(BuiltAgent.class), anyString(), anyString(), anyString()))
                .thenReturn(Flux.just(
                        AgentStreamSignal.of(AgentStreamSignalType.EXCEED_MAX_ITERS, null, null),
                        AgentStreamSignal.of(AgentStreamSignalType.AGENT_END, null, null)));

        // when
        service.sendMessageAsync(new SendMessageCommand(session.sessionId(), "你好"));

        // then：经持久取消痕迹感知进行中的取消，取消优先收敛 idle，TO_TERMINATED 迁移零调用
        List<ChatEvent> captured = savedChatEvents();
        ChatEvent last = captured.get(captured.size() - 1);
        assertEquals(ChatEventType.SESSION_STATUS_IDLE, last.type());
        assertTrue(last.payload().contains("\"stop_reason\":{\"type\":\"interrupted\"}"));
        assertTrue(captured.stream().noneMatch(e -> e.type() == ChatEventType.SESSION_STATUS_TERMINATED));
        verify(sessionRepository, never()).transition(anyString(), eq(Transition.TERMINATE));
        verify(sessionRepository).transition(session.sessionId(), Transition.FINISH_TURN);
        verify(agent).close();
    }

    @Test
    void should_fallbackToIdle_when_finalizeTurn_given_terminateCasMiss() {
        // given：终态判定谓词未命中（读到 processing），但迁移时取消恰好提交——
        //        TO_TERMINATED（前置 processing）CAS 0 行，TO_IDLE（前置 processing/canceling）成功
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        BuiltAgent agent = wireHappyPathForExecution();
        bindConnection(session);
        when(sessionRepository.transition(session.sessionId(), Transition.TERMINATE)).thenReturn(0);
        when(sessionRepository.transition(session.sessionId(), Transition.FINISH_TURN)).thenReturn(1);
        when(agentRunExecutor.streamEvents(any(BuiltAgent.class), anyString(), anyString(), anyString()))
                .thenReturn(Flux.just(
                        AgentStreamSignal.of(AgentStreamSignalType.EXCEED_MAX_ITERS, null, null),
                        AgentStreamSignal.of(AgentStreamSignalType.AGENT_END, null, null)));

        // when
        service.sendMessageAsync(new SendMessageCommand(session.sessionId(), "你好"));

        // then：同事务回退收敛 idle，事件集与实际迁入状态一致（收场二事件 interrupted），
        //      不得广播未生效 terminated 对应的事件
        List<ChatEvent> captured = savedChatEvents();
        ChatEvent last = captured.get(captured.size() - 1);
        assertEquals(ChatEventType.SESSION_STATUS_IDLE, last.type());
        assertTrue(last.payload().contains("\"stop_reason\":{\"type\":\"interrupted\"}"),
                "回退终态事件集须为中断语义");
        assertTrue(captured.stream().noneMatch(e -> e.type() == ChatEventType.SESSION_STATUS_TERMINATED),
                "未生效的 terminated 迁移不得落事件");
        verify(sessionRepository).transition(session.sessionId(), Transition.FINISH_TURN);
        verify(agent).close();
    }

    @Test
    void should_transitionToTerminated_when_finalizeTurn_given_pureMaxItersWithoutAnyCancel() {
        // given：两源全部未命中（无进程内标志、无 canceling 痕迹）的纯迭代上限
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        BuiltAgent agent = wireHappyPathForExecution();
        bindConnection(session);
        when(agentRunExecutor.streamEvents(any(BuiltAgent.class), anyString(), anyString(), anyString()))
                .thenReturn(Flux.just(
                        AgentStreamSignal.of(AgentStreamSignalType.EXCEED_MAX_ITERS, null, null),
                        AgentStreamSignal.of(AgentStreamSignalType.AGENT_END, null, null)));

        // when
        service.sendMessageAsync(new SendMessageCommand(session.sessionId(), "你好"));

        // then：仍进 terminated（max_iterations），谓词两源均被查询（固化两源求值口径；
        // D8 竞态补触发使订阅后复检 + 终态判定点各求值一次，计数放宽为至少一次）
        List<ChatEvent> captured = savedChatEvents();
        ChatEvent last = captured.get(captured.size() - 1);
        assertEquals(ChatEventType.SESSION_STATUS_TERMINATED, last.type());
        assertTrue(last.payload().contains("\"stop_reason\":{\"type\":\"max_iterations\"}"));
        verify(sessionRepository, atLeastOnce()).isCancelling(session.sessionId());
        verify(agent).close();
    }

    // ==================== 会话中断 / 取消信号（终态出口调用序列 characterization 已随 3.1 迁至
    //                      execution.TurnFinalizerTest，本类只留经编排入口的端到端断言） ====================

    @Test
    void should_evaluate_cancel_when_isCancelRequested_given_only_db_canceling_visible() {
        // given：跨实例取消——本实例进程内中断标志全程不置位（无 SSE 断连、无 interruptCurrentRun 来源），
        //        仅共享库 canceling 持久痕迹可见；一轮流正常收流（无 AGENT_END，终态由 onComplete 谓词分类）
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        BuiltAgent agent = wireHappyPathForExecution();
        when(sessionRepository.isCancelling(session.sessionId())).thenReturn(true);
        when(agentRunExecutor.streamEvents(any(BuiltAgent.class), anyString(), anyString(), anyString()))
                .thenReturn(Flux.just(AgentStreamSignal.of(AgentStreamSignalType.TEXT_DELTA, "你好", "blk-1")));

        // when
        service.sendMessageAsync(new SendMessageCommand(session.sessionId(), "你好"));

        // then：两源谓词的 DB 源单独命中即判真——订阅建立后复检据持久痕迹补触发定向中断，
        //        收流终态按中断语义收敛回 idle（TO_TERMINATED 迁移零调用，无静默死路）
        verify(agent, atLeastOnce()).interrupt(session.userId(), session.sessionId());
        List<ChatEvent> captured = savedChatEvents();
        ChatEvent last = captured.get(captured.size() - 1);
        assertEquals(ChatEventType.SESSION_STATUS_IDLE, last.type());
        assertTrue(last.payload().contains("\"stop_reason\":{\"type\":\"interrupted\"}"));
        verify(sessionRepository, never()).transition(anyString(), eq(Transition.TERMINATE));
        verify(agent).close();
    }

    @Test
    void should_submitAsyncTask_when_sendMessageAsync_given_validSession() {
        // given（异步入口：使用 mock 执行器验证任务被提交；会话校验在任务内完成，此处无需桩会话）
        virtualExecutor = mock(Executor.class);
        // 装配口径：本类服务实例常驻，直接替换其执行器字段（原壳类经重建门面实例达成同一效果）
        ReflectionTestUtils.setField(service, "virtualExecutor", virtualExecutor);

        // when
        service.sendMessageAsync(new SendMessageCommand("s-1", "你好"));

        // then：异步任务被提交到虚拟线程池
        verify(virtualExecutor).execute(any(Runnable.class));
    }

    // ==================== 主线程归属降级（decompose-command-facade 4.4 自壳类迁入） ====================

    @Test
    void should_persistEventsWithNullThread_when_sendMessageAsync_given_mainThreadMissing() {
        // given：会话无存活主线程（历史孤儿会话场景）——归属解析降级为 null
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        when(sessionThreadRepository.findMain(session.sessionId())).thenReturn(Optional.empty());
        wireHappyPathForExecution();
        bindConnection(session);
        when(agentRunExecutor.streamEvents(any(BuiltAgent.class), anyString(), anyString(), anyString()))
                .thenReturn(Flux.just(
                        AgentStreamSignal.of(AgentStreamSignalType.TEXT_DELTA, "你好", "blk-1"),
                        AgentStreamSignal.of(AgentStreamSignalType.TEXT_END, null, "blk-1"),
                        AgentStreamSignal.of(AgentStreamSignalType.AGENT_END, null, null)));

        // when（主线程缺失不得阻断执行与落库）
        service.sendMessageAsync(new SendMessageCommand(session.sessionId(), "你好"));

        // then：事件照常落库，归属为 null（读端按会话级历史仍可见）
        List<ChatEvent> saved = savedChatEvents();
        assertFalse(saved.isEmpty(), "主线程缺失时事件应正常落库");
        assertTrue(saved.stream().allMatch(e -> e.sessionThreadId() == null),
                "主线程缺失时归属应降级为 null");
    }

    // ==================== 入站事件摄取 ====================

    @Test
    void should_rejectNewExecution_when_sendMessageAsync_given_waitingConfirmationSession() {
        // given（会话处于 HITL 等待确认态（对外 running + 内部相位 awaiting_confirmation）：新执行被拒绝）
        AgentSession session = idleSession().withStatus(AgentSessionStatus.RUNNING)
                .withPhase(TurnPhase.AWAITING_CONFIRMATION);
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));

        // when
        service.sendMessageAsync(new SendMessageCommand(session.sessionId(), "你好"));

        // then（不触发 CAS / 不获取租约 / 无事件落库）
        verify(sessionRepository, never()).transition(anyString(), eq(Transition.BEGIN_TURN));
        verify(coordinationLeaseService, never()).tryAcquireTurnLease(anyString());
        verify(chatEventRepository, never()).save(any(ChatEvent.class));
    }

    @Test
    void should_acquireAndReleaseTurnLease_when_sendMessageAsync_given_normalFinish() {
        // given（turn 正常结束：启动事务获取租约，终态出口释放租约）
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        wireHappyPathForExecution();
        when(agentRunExecutor.streamEvents(any(BuiltAgent.class), anyString(), anyString(), anyString()))
                .thenReturn(Flux.just(AgentStreamSignal.of(AgentStreamSignalType.AGENT_END, null, null)));

        // when
        service.sendMessageAsync(new SendMessageCommand(session.sessionId(), "你好"));

        // then（协调层仅 turn 租约生命周期：启动事务获取 + 终态出口释放）
        verify(coordinationLeaseService).tryAcquireTurnLease(session.sessionId());
        verify(coordinationLeaseService).releaseTurnLease(session.sessionId());
    }

    // ==================== 轮次终局事件发布（write-back-deployment-run-terminal-state 事件化，fix-runtime-layering 5.4） ====================

    @Test
    void should_publishTurnFinishedWithoutTriggerType_when_sendMessageAsync_given_plainSessionWithoutTrigger() {
        // given：普通用户会话（无触发打标）正常收敛
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        wireHappyPathForExecution();
        bindConnection(session);
        when(agentRunExecutor.streamEvents(any(BuiltAgent.class), anyString(), anyString(), anyString()))
                .thenReturn(Flux.just(AgentStreamSignal.of(AgentStreamSignalType.AGENT_END, null, null)));

        // when
        service.sendMessageAsync(new SendMessageCommand(session.sessionId(), "你好"));

        // then：普通会话照常发布终局事件但无触发打标（回写门槛收敛到 AFTER_COMMIT 监听器）
        verify(sessionRepository).transition(session.sessionId(), Transition.FINISH_TURN);
        verify(applicationEventPublisher, times(1))
                .publishEvent(new TurnFinished(session.sessionId(), null, "succeeded"));
    }

}
