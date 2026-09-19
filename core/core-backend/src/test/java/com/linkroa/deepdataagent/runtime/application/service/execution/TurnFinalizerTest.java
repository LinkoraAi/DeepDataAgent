package com.linkroa.deepdataagent.runtime.application.service.execution;

import com.linkroa.deepdataagent.runtime.application.port.ChatEventPersister;
import com.linkroa.deepdataagent.runtime.application.service.CoordinationLeaseService;
import com.linkroa.deepdataagent.runtime.domain.event.AgentStreamSignal;
import com.linkroa.deepdataagent.runtime.domain.event.AgentStreamSignalType;
import com.linkroa.deepdataagent.runtime.domain.event.TurnFinished;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSession;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSessionContext;
import com.linkroa.deepdataagent.runtime.domain.model.ChatEvent;
import com.linkroa.deepdataagent.runtime.domain.model.Transition;
import com.linkroa.deepdataagent.runtime.domain.model.TurnControl;
import com.linkroa.deepdataagent.runtime.domain.model.enums.AgentSessionStatus;
import com.linkroa.deepdataagent.runtime.domain.model.enums.ChatEventType;
import com.linkroa.deepdataagent.runtime.domain.model.runstate.TurnRunState;
import com.linkroa.deepdataagent.runtime.domain.port.ConnectionHandle;
import com.linkroa.deepdataagent.runtime.domain.repository.AgentSessionRepository;
import com.linkroa.deepdataagent.runtime.domain.repository.ChatEventRepository;
import com.linkroa.deepdataagent.runtime.infrastructure.execution.InMemorySessionRegistry;
import com.linkroa.deepdataagent.runtime.infrastructure.persistence.BatchChatEventPersister;
import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link TurnFinalizer} 直测（decompose-command-facade 3.1）：钉死自门面纯搬移的轮次收口四条红线
 * （design Context 硬约束，用例自门面壳测试类逐名迁入，
 * 迁移前后对照登记于 {@code openspec/changes/decompose-command-facade/tasks.md} 3.1 注记）。
 * <ul>
 *   <li><b>严格排空协议</b>：终态 / 挂起事务<b>外</b>先 {@code flush()} 整队排空，事务<b>首行</b>查毒，
 *       毒发整批回滚且会话状态不迁移（含「他会话行不随本会话回滚丢失」的跨会话不变式）；</li>
 *   <li><b>D19 拒绝挂起</b>：批次 / 候选错配的抛点位于排空与事务<b>之前</b>（零 flush、零事务、零落库）；</li>
 *   <li><b>防悬挂收尾</b>：查毒异常就地消化不上抛，收轮信号与租约释放必达（{@code awaitFinish} 放行）；</li>
 *   <li><b>挂起即物理轮终局</b>：清在途流快照 → 置等待守卫 → 收轮 → 释放租约的先后顺序。</li>
 * </ul>
 * <p>编排线程侧的轮次生命周期（{@code agent.close()} / 续约任务 {@code cancel()}）随其断言主体
 * 归属留在门面壳与执行模板的用例中（design D4：断言不删、随主体归位）。</p>
 * <p>夹具口径与壳测试一致：真实 {@link InMemorySessionRegistry} + 真实
 * {@link TurnEventWriter}（同一组替身），令「哪些事件按何序入账本 / 广播」可端到端断言；
 * 落库端口在同步直存替身、恒置毒替身与真实批量落库器（写故障注入）之间按用例切换。</p>
 */
@ExtendWith(MockitoExtension.class)
class TurnFinalizerTest {

    /** 执行现场线程归属（轮内路径的主协调器线程 id）。 */
    private static final String MAIN_THREAD_ID = "sthr_main";

    @Mock private AgentSessionRepository sessionRepository;
    @Mock private ChatEventRepository chatEventRepository;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private CoordinationLeaseService coordinationLeaseService;
    @Mock private ApplicationEventPublisher applicationEventPublisher;
    @Mock private ConnectionHandle connectionHandle;

    /** 真实会话级聚合注册表：seq 计数器 / 在途流快照 / 等待守卫按真实实现执行。 */
    private final InMemorySessionRegistry sessionRegistry = new InMemorySessionRegistry();

    private TurnEventWriter turnEventWriter;
    private TurnFinalizer turnFinalizer;

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
        wirePersister(synchronousPersister());
        // 事务模板同步执行回调：排空 / 查毒 / 迁移 / 落库在测试线程内按真实次序跑完
        lenient().doAnswer(inv -> {
            TransactionCallback<Object> callback = inv.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactionTemplate).execute(any());
    }

    // ==================== 夹具 ====================

    /** 落库端口两处同步替换（Writer 入队侧 + 收口器排空 / 查毒侧），保持「注入即全链路生效」。 */
    private void wirePersister(ChatEventPersister persister) {
        ReflectionTestUtils.setField(turnEventWriter, "chatEventPersister", persister);
        ReflectionTestUtils.setField(turnFinalizer, "chatEventPersister", persister);
    }

    /** 同步落库替身：enqueue 即 save、flush 无操作、查毒恒为假（无持久化故障的常态）。 */
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
     * 账本不可信（恒已置毒）替身：事务前 flush 无操作、事务首行查毒恒为真，
     * 由被测收口器在事务 lambda 首行抛业务异常触发整批回滚。
     */
    private ChatEventPersister poisonedPersister() {
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

    /**
     * 真实异步批量落库器（同锁 flush 排空 + isPoisoned 查毒，即严格排空协议本体），
     * 以账本 mock 为落库目标；指定事件类型写失败即模拟「重试耗尽仍未落库」的置毒源。
     *
     * @param failingType 必写失败的事件类型（null = 全程无故障）
     */
    private BatchChatEventPersister batchPersisterFailingOn(ChatEventType failingType) {
        lenient().when(chatEventRepository.save(any(ChatEvent.class))).thenAnswer(inv -> {
            ChatEvent event = inv.getArgument(0);
            if (failingType != null && event.type() == failingType) {
                throw new RuntimeException("事件账本写入故障（注入）: " + failingType.value());
            }
            return event;
        });
        return new BatchChatEventPersister(chatEventRepository);
    }

    /** 可观测落库端口（mock）：默认不置毒、enqueue 不落库，使终态事件成为唯一 save 来源。 */
    private ChatEventPersister observablePersister() {
        ChatEventPersister persister = mock(ChatEventPersister.class);
        wirePersister(persister);
        return persister;
    }

    /** 账本落库回显桩。 */
    private void wireLedgerSave() {
        lenient().when(chatEventRepository.save(any(ChatEvent.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    /** 状态迁移 CAS 命中桩。 */
    private void wireTransition(String sessionId, Transition transition, int affectedRows) {
        lenient().when(sessionRepository.transition(sessionId, transition)).thenReturn(affectedRows);
    }

    /** 执行现场夹具：真实会话语境（绑定连接句柄）+ 本轮累积态（seq 基准 0，事件自 1 起分配）。 */
    private ExecutionContext newContext(AgentSession session) {
        AgentSessionContext sessionContext = sessionRegistry.getOrCreate(session);
        sessionContext.bindConnection(connectionHandle);
        TurnRunState runState = sessionContext.beginRound(0L);
        return new ExecutionContext(sessionContext, runState, MAIN_THREAD_ID);
    }

    /** 登记一条待确认候选（TOOL_CALL_END 现场：SDK id + 工具名 + 入参 + 公开事件 id 锚点）。 */
    private void rememberCandidate(TurnRunState runState, String toolCallId, String toolName,
                                    String inputJson, String toolEventId) {
        runState.rememberConfirmCandidate(toolCallId, toolName, inputJson, toolEventId);
    }

    /** HITL 挂起信号（按 reply 整批携带待确认 id）。 */
    private AgentStreamSignal suspendSignal(List<String> toolCallIds) {
        return AgentStreamSignal.hitl(AgentStreamSignalType.HUMAN_CONFIRM_REQUIRED, "reply-1", toolCallIds);
    }

    /** 汇总账本 save 记录（按调用顺序）。 */
    private List<ChatEvent> savedChatEvents() {
        return org.mockito.Mockito.mockingDetails(chatEventRepository).getInvocations().stream()
                .filter(inv -> inv.getMethod().getName().equals("save"))
                .map(inv -> inv.getArgument(0, ChatEvent.class))
                .collect(Collectors.toList());
    }

    /** 落库记录中首个指定类型事件（落库顺序断言用）。 */
    private ChatEvent firstOfType(List<ChatEvent> saved, ChatEventType type) {
        return saved.stream().filter(e -> e.type() == type).findFirst().orElseThrow();
    }

    /**
     * 终态编排次序钉桩（严格排空协议 → 窄读 → 迁移 → 事件落库 → touchLastActive → 终局事件）。
     */
    private void verifyTerminalCallSequence(String sessionId, InOrder inOrder, ChatEventPersister persister,
                                            List<Transition> transitions, List<ChatEventType> terminalEvents) {
        inOrder.verify(persister).flush();
        inOrder.verify(persister).isPoisoned(sessionId);
        inOrder.verify(sessionRepository).currentStatus(sessionId);
        transitions.forEach(transition -> inOrder.verify(sessionRepository).transition(sessionId, transition));
        terminalEvents.forEach(type ->
                inOrder.verify(chatEventRepository).save(argThat(event -> event.type() == type)));
        inOrder.verify(sessionRepository).touchLastActive(sessionId);
        inOrder.verify(applicationEventPublisher).publishEvent(any(TurnFinished.class));
    }

    private AgentSession idleSession() {
        return AgentSession.create("1", "agent-a", "1.0.0", "{}", null);
    }

    /** 调度打标会话夹具（cron 触发）：终局出口须发布带打标的终局事件。 */
    private AgentSession scheduledIdleSession() {
        return AgentSession.createWithTrigger("1", "agent-a", "1.0.0", "{}", null, "cron", "dep-1");
    }

    // ==================== 终态唯一编排次序（决策表第 1 / 2 / 3 / 4 / 5 / 7 行） ====================

    @Test
    void should_keepTerminalCallSequence_when_finalizeNormal_given_completedTurn() {
        // given（决策表第 1 行：正常结束 → TO_IDLE；落库端口替换为可观测 mock）
        AgentSession session = idleSession();
        String sessionId = session.sessionId();
        wireLedgerSave();
        wireTransition(sessionId, Transition.FINISH_TURN, 1);
        ChatEventPersister persister = observablePersister();
        ExecutionContext context = newContext(session);
        TurnControl turn = new TurnControl();

        // when
        turnFinalizer.finalizeNormal(context, turn);

        // then：排空先于事务、CAS 先于事件落库、事件先于终局事件、终局事件先于广播、广播后必释放租约
        InOrder inOrder = inOrder(persister, sessionRepository, chatEventRepository,
                applicationEventPublisher, connectionHandle, coordinationLeaseService);
        verifyTerminalCallSequence(sessionId, inOrder, persister,
                List.of(Transition.FINISH_TURN),
                List.of(ChatEventType.SESSION_THREAD_STATUS_IDLE, ChatEventType.SESSION_STATUS_IDLE));
        inOrder.verify(connectionHandle).push(argThat(e -> e.type() == ChatEventType.SESSION_STATUS_IDLE));
        inOrder.verify(coordinationLeaseService).releaseTurnLease(sessionId);
        // then（收轮信号必达）
        assertTimeoutPreemptively(Duration.ofSeconds(2), turn::awaitFinish);
    }

    @Test
    void should_keepTerminalCallSequence_when_finalizeNormal_given_pureMaxIterations() {
        // given（决策表第 2 行：纯迭代上限——首选 TO_TERMINATED 命中即止）
        AgentSession session = idleSession();
        String sessionId = session.sessionId();
        wireLedgerSave();
        wireTransition(sessionId, Transition.TERMINATE, 1);
        ChatEventPersister persister = observablePersister();
        ExecutionContext context = newContext(session);
        context.runState().markExceedMaxIters();
        TurnControl turn = new TurnControl();

        // when
        turnFinalizer.finalizeNormal(context, turn);

        // then：单次迁移 + terminated 收场二事件，idle 迁移零调用
        InOrder inOrder = inOrder(persister, sessionRepository, chatEventRepository,
                applicationEventPublisher, connectionHandle, coordinationLeaseService);
        verifyTerminalCallSequence(sessionId, inOrder, persister,
                List.of(Transition.TERMINATE),
                List.of(ChatEventType.SESSION_THREAD_STATUS_TERMINATED, ChatEventType.SESSION_STATUS_TERMINATED));
        inOrder.verify(connectionHandle).push(argThat(e -> e.type() == ChatEventType.SESSION_STATUS_TERMINATED));
        inOrder.verify(coordinationLeaseService).releaseTurnLease(sessionId);
        verify(sessionRepository, never()).transition(anyString(), eq(Transition.FINISH_TURN));
        assertTimeoutPreemptively(Duration.ofSeconds(2), turn::awaitFinish);
    }

    @Test
    void should_keepTerminalCallSequence_when_finalizeNormal_given_maxIterationsWithTerminateCasMiss() {
        // given（决策表第 3 行：迭代上限 × 取消抢跑——首选 CAS 0 行，同链回退 TO_IDLE）
        AgentSession session = idleSession();
        String sessionId = session.sessionId();
        wireLedgerSave();
        wireTransition(sessionId, Transition.TERMINATE, 0);
        wireTransition(sessionId, Transition.FINISH_TURN, 1);
        ChatEventPersister persister = observablePersister();
        ExecutionContext context = newContext(session);
        context.runState().markExceedMaxIters();
        TurnControl turn = new TurnControl();

        // when
        turnFinalizer.finalizeNormal(context, turn);

        // then：两次迁移按链顺序各一次，落库事件集与被采纳尝试一致（中断收场二事件），terminated 事件零落库
        InOrder inOrder = inOrder(persister, sessionRepository, chatEventRepository,
                applicationEventPublisher, connectionHandle, coordinationLeaseService);
        verifyTerminalCallSequence(sessionId, inOrder, persister,
                List.of(Transition.TERMINATE, Transition.FINISH_TURN),
                List.of(ChatEventType.SESSION_THREAD_STATUS_IDLE, ChatEventType.SESSION_STATUS_IDLE));
        inOrder.verify(connectionHandle).push(argThat(e -> e.type() == ChatEventType.SESSION_STATUS_IDLE));
        inOrder.verify(coordinationLeaseService).releaseTurnLease(sessionId);
        verify(chatEventRepository, never()).save(argThat(e -> e.type() == ChatEventType.SESSION_STATUS_TERMINATED));
        assertTimeoutPreemptively(Duration.ofSeconds(2), turn::awaitFinish);
    }

    @Test
    void should_keepTerminalCallSequence_when_finalizeInterrupted_given_inProcessInterrupt() {
        // given（决策表第 4 行：断连 / user.interrupt 在途 → 中断终态，事件对同事务）
        AgentSession session = idleSession();
        String sessionId = session.sessionId();
        wireLedgerSave();
        wireTransition(sessionId, Transition.FINISH_TURN, 1);
        ChatEventPersister persister = observablePersister();
        ExecutionContext context = newContext(session);
        context.runState().markInterrupted();

        // when
        turnFinalizer.finalizeInterrupted(context);

        // then：中断收场二事件（线程先、会话后）同事务；广播按同序下发
        InOrder inOrder = inOrder(persister, sessionRepository, chatEventRepository,
                applicationEventPublisher, connectionHandle, coordinationLeaseService);
        verifyTerminalCallSequence(sessionId, inOrder, persister,
                List.of(Transition.FINISH_TURN),
                List.of(ChatEventType.SESSION_THREAD_STATUS_IDLE, ChatEventType.SESSION_STATUS_IDLE));
        inOrder.verify(connectionHandle).push(argThat(e -> e.type() == ChatEventType.SESSION_THREAD_STATUS_IDLE));
        inOrder.verify(connectionHandle).push(argThat(e -> e.type() == ChatEventType.SESSION_STATUS_IDLE));
        inOrder.verify(coordinationLeaseService).releaseTurnLease(sessionId);
    }

    @Test
    void should_keepTerminalCallSequence_when_finalizeFailed_given_executionError() {
        // given（决策表第 5 行：执行出错 → session.error + idle(error)）
        AgentSession session = idleSession();
        String sessionId = session.sessionId();
        wireLedgerSave();
        wireTransition(sessionId, Transition.FINISH_TURN, 1);
        ChatEventPersister persister = observablePersister();
        ExecutionContext context = newContext(session);

        // when
        turnFinalizer.finalizeFailed(context, "model 调用失败");

        // then：错误事件先于 idle 状态事件；租约照常释放（执行权即刻让出）
        InOrder inOrder = inOrder(persister, sessionRepository, chatEventRepository,
                applicationEventPublisher, coordinationLeaseService);
        verifyTerminalCallSequence(sessionId, inOrder, persister,
                List.of(Transition.FINISH_TURN),
                List.of(ChatEventType.SESSION_ERROR, ChatEventType.SESSION_THREAD_STATUS_IDLE,
                        ChatEventType.SESSION_STATUS_IDLE));
        inOrder.verify(coordinationLeaseService).releaseTurnLease(sessionId);
    }

    @Test
    void should_skipEventsBroadcastAndStillReleaseLease_when_finalizeNormal_given_explicitTerminalWins() {
        // given（决策表第 7 行：终态事务窄读到 terminated，显式指令胜出）
        AgentSession session = idleSession();
        String sessionId = session.sessionId();
        when(sessionRepository.currentStatus(sessionId)).thenReturn(Optional.of(AgentSessionStatus.TERMINATED));
        ChatEventPersister persister = observablePersister();
        ExecutionContext context = newContext(session);
        TurnControl turn = new TurnControl();

        // when
        turnFinalizer.finalizeNormal(context, turn);

        // then：窄读后即止——零迁移、零事件、零 touchLastActive、零终局事件、零广播，租约仍释放
        InOrder inOrder = inOrder(persister, sessionRepository, coordinationLeaseService);
        inOrder.verify(persister).flush();
        inOrder.verify(persister).isPoisoned(sessionId);
        inOrder.verify(sessionRepository).currentStatus(sessionId);
        inOrder.verify(coordinationLeaseService).releaseTurnLease(sessionId);
        verify(sessionRepository, never()).transition(anyString(), eq(Transition.FINISH_TURN));
        verify(sessionRepository, never()).transition(anyString(), eq(Transition.TERMINATE));
        verify(sessionRepository, never()).touchLastActive(anyString());
        verify(chatEventRepository, never()).save(any(ChatEvent.class));
        verify(applicationEventPublisher, never()).publishEvent(any(TurnFinished.class));
        verify(connectionHandle, never()).push(argThat(e -> e.type() == ChatEventType.SESSION_STATUS_IDLE));
        // then（真 no-op 仍收轮：调用方尾部的完成信号由 finalizeNormal 放行）
        assertTimeoutPreemptively(Duration.ofSeconds(2), turn::awaitFinish);
    }

    // ==================== 轮次终局事件发布（终态事务内，回滚不发） ====================

    @Test
    void should_publishSucceededTurnFinished_when_finalizeNormal_given_scheduledSessionConvergedToIdle() {
        // given（调度会话正常收敛：idle，stop=stop）
        AgentSession session = scheduledIdleSession();
        String sessionId = session.sessionId();
        wireLedgerSave();
        wireTransition(sessionId, Transition.FINISH_TURN, 1);
        ExecutionContext context = newContext(session);
        TurnControl turn = new TurnControl();

        // when
        turnFinalizer.finalizeNormal(context, turn);

        // then：正常收敛终局按成功收口，终局事件恰好发布一次（带调度打标 triggerType）
        verify(sessionRepository).transition(sessionId, Transition.FINISH_TURN);
        verify(applicationEventPublisher, times(1))
                .publishEvent(new TurnFinished(sessionId, "cron", "succeeded"));
    }

    @Test
    void should_publishFailedTurnFinished_when_finalizeNormal_given_scheduledSessionHitMaxIters() {
        // given（迭代上限进 terminated：TO_TERMINATED 迁移 CAS 命中）
        AgentSession session = scheduledIdleSession();
        String sessionId = session.sessionId();
        wireLedgerSave();
        wireTransition(sessionId, Transition.TERMINATE, 1);
        ExecutionContext context = newContext(session);
        context.runState().markExceedMaxIters();
        TurnControl turn = new TurnControl();

        // when
        turnFinalizer.finalizeNormal(context, turn);

        // then：terminated 终局（迭代上限）按失败收口
        verify(sessionRepository).transition(sessionId, Transition.TERMINATE);
        verify(applicationEventPublisher).publishEvent(new TurnFinished(sessionId, "cron", "failed"));
    }

    @Test
    void should_publishTerminatedTurnFinished_when_finalizeNormal_given_scheduledSessionTerminateCasFallback() {
        // given（terminated CAS 0 行回退收敛 idle：取消竞态兜底，实际迁入带 canceledFallback 标记）
        AgentSession session = scheduledIdleSession();
        String sessionId = session.sessionId();
        wireLedgerSave();
        wireTransition(sessionId, Transition.TERMINATE, 0);
        wireTransition(sessionId, Transition.FINISH_TURN, 1);
        ExecutionContext context = newContext(session);
        context.runState().markExceedMaxIters();
        TurnControl turn = new TurnControl();

        // when
        turnFinalizer.finalizeNormal(context, turn);

        // then：取消回退出口按中断收口（而非回退前的 failed 判定）
        verify(sessionRepository).transition(sessionId, Transition.FINISH_TURN);
        verify(applicationEventPublisher).publishEvent(new TurnFinished(sessionId, "cron", "terminated"));
    }

    @Test
    void should_publishFailedTurnFinished_when_finalizeFailed_given_scheduledSessionExecutionError() {
        // given（执行错误终态：session.error + idle(error)）
        AgentSession session = scheduledIdleSession();
        String sessionId = session.sessionId();
        wireLedgerSave();
        wireTransition(sessionId, Transition.FINISH_TURN, 1);
        ExecutionContext context = newContext(session);

        // when
        turnFinalizer.finalizeFailed(context, "model 调用失败");

        // then：执行错误终局按失败收口
        verify(sessionRepository).transition(sessionId, Transition.FINISH_TURN);
        verify(applicationEventPublisher).publishEvent(new TurnFinished(sessionId, "cron", "failed"));
    }

    @Test
    void should_publishTerminatedTurnFinished_when_finalizeInterrupted_given_scheduledSessionInterrupted() {
        // given（显式中断终局：session.interrupted + idle(interrupted)）
        AgentSession session = scheduledIdleSession();
        String sessionId = session.sessionId();
        wireLedgerSave();
        wireTransition(sessionId, Transition.FINISH_TURN, 1);
        ExecutionContext context = newContext(session);
        context.runState().markInterrupted();

        // when
        turnFinalizer.finalizeInterrupted(context);

        // then：中断终局按中断收口
        verify(sessionRepository).transition(sessionId, Transition.FINISH_TURN);
        verify(applicationEventPublisher).publishEvent(new TurnFinished(sessionId, "cron", "terminated"));
    }

    // ==================== 红线二：D19 批次 / 候选错配拒绝挂起（抛点先于排空与事务） ====================

    @Test
    void should_rejectSuspendBeforeFlushAndTransaction_when_enterWaitingConfirm_given_batchIdsNotRegistered() {
        // given（D19 对称分支①：候选 tc-1 已登记，但挂起信号批次 id 全部未登记 → 错配）
        AgentSession session = idleSession();
        String sessionId = session.sessionId();
        ChatEventPersister persister = observablePersister();
        ExecutionContext context = newContext(session);
        rememberCandidate(context.runState(), "tc-1", "search", "{\"q\":\"x\"}", "evt_tool_1");
        TurnControl turn = new TurnControl();
        AgentStreamSignal signal = suspendSignal(List.of("ghost-id"));

        // when & then（异常沿 onStreamError → finalizeFailed 收敛为执行错误终态，本方法只负责快速失败）
        DeepDataAgentException error = assertThrows(DeepDataAgentException.class,
                () -> turnFinalizer.enterWaitingConfirm(context, signal, turn));
        assertTrue(error.getMessage().contains("DEEP_AGENT_RUN_ERROR"), "错误消息应携带错误码");
        assertTrue(error.getMessage().contains("HITL"), "错误消息应含会话与批次定位信息");
        // then（抛点在排空与事务之前：零排空、零事务、零状态迁移）
        verify(persister, never()).flush();
        verify(transactionTemplate, never()).execute(any());
        verify(sessionRepository, never()).transition(anyString(), eq(Transition.PHASE_AWAIT));
        // then（MUST NOT 落 / 广播任何等待事件——等待现场现由账本既有 tool_use 明细承载）
        assertTrue(savedChatEvents().isEmpty(), "批次与候选登记错配时不得落任何事件");
        verify(connectionHandle, never()).push(any(ChatEvent.class));
        // then（拒绝挂起本身不收轮：收轮由错误终态收敛路径承担）
        verify(coordinationLeaseService, never()).releaseTurnLease(sessionId);
    }

    @Test
    void should_rejectSuspendBeforeFlushAndTransaction_when_enterWaitingConfirm_given_noCandidateRegistered() {
        // given（D19 对称分支②：本轮候选登记为空，挂起信号携带批次 id → 错配）
        AgentSession session = idleSession();
        String sessionId = session.sessionId();
        ChatEventPersister persister = observablePersister();
        ExecutionContext context = newContext(session);
        TurnControl turn = new TurnControl();
        AgentStreamSignal signal = suspendSignal(List.of("tc-1"));

        // when & then（两条对称分支归一为「空批次」拒绝，杜绝仅状态事件的残缺等待）
        DeepDataAgentException error = assertThrows(DeepDataAgentException.class,
                () -> turnFinalizer.enterWaitingConfirm(context, signal, turn));
        assertTrue(error.getMessage().contains("HITL"), "错误消息应携带批次错配定位信息");
        verify(persister, never()).flush();
        verify(transactionTemplate, never()).execute(any());
        verify(sessionRepository, never()).transition(anyString(), eq(Transition.PHASE_AWAIT));
        assertTrue(savedChatEvents().isEmpty(), "候选登记为空时不得以仅状态事件确立残缺等待");
        verify(coordinationLeaseService, never()).releaseTurnLease(sessionId);
    }

    // ==================== 红线一 / 三：严格排空协议毒发回滚 + 防悬挂收轮 ====================

    @Test
    void should_rollbackTerminalAndStillFinishRound_when_finalizeNormal_given_streamEventWriteFails() {
        // given（真实批量落库器 + agent.thinking 写库必失败：事务前 flush 排空后本会话置毒）
        AgentSession session = idleSession();
        String sessionId = session.sessionId();
        BatchChatEventPersister persister = batchPersisterFailingOn(ChatEventType.AGENT_THINKING);
        wirePersister(persister);
        persister.enqueue(ChatEvent.create(sessionId, ChatEventType.AGENT_THINKING, "{}", 1L, "evt_thinking_1"));
        ExecutionContext context = newContext(session);
        TurnControl turn = new TurnControl();

        // when（查毒异常不得挂死编排线程：本轮必须按时收轮）
        assertDoesNotThrow(() -> turnFinalizer.finalizeNormal(context, turn));

        // then（终态事务整批回滚：状态不迁移 idle、终态事件不落库不广播）
        verify(sessionRepository, never()).transition(anyString(), eq(Transition.FINISH_TURN));
        verify(sessionRepository, never()).touchLastActive(anyString());
        List<ChatEvent> saved = savedChatEvents();
        assertTrue(saved.stream().noneMatch(e -> e.type() == ChatEventType.SESSION_STATUS_IDLE),
                "账本缺行时不得出现无终局事件的终态会话");
        verify(connectionHandle, never()).push(argThat(e -> e.type() == ChatEventType.SESSION_STATUS_IDLE));
        verify(applicationEventPublisher, never()).publishEvent(any(TurnFinished.class));
        // then（本轮仍照常收轮：完成阻塞等待 + 释放租约）
        assertTimeoutPreemptively(Duration.ofSeconds(2), turn::awaitFinish);
        verify(coordinationLeaseService, atLeastOnce()).releaseTurnLease(sessionId);
    }

    @Test
    void should_rollbackTerminalAndStillFinishRound_when_finalizeNormal_given_ledgerAlwaysPoisoned() {
        // given（账本恒已置毒：事务前 flush 无操作、终态事务首行查毒命中抛异常）
        AgentSession session = idleSession();
        String sessionId = session.sessionId();
        wireLedgerSave();
        wirePersister(poisonedPersister());
        ExecutionContext context = newContext(session);
        TurnControl turn = new TurnControl();

        // when（awaitFinish 必被放行——否则本断言超时失败：编排线程永久阻塞即回归 design D2 的悬挂）
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> turnFinalizer.finalizeNormal(context, turn));

        // then（必释放租约：会话可被复位路径接管，而非被续约钉在 processing）
        verify(coordinationLeaseService, atLeastOnce()).releaseTurnLease(sessionId);
        // then（不迁移状态、不落 / 不广播任何终态事件）
        verify(sessionRepository, never()).transition(anyString(), eq(Transition.FINISH_TURN));
        verify(sessionRepository, never()).transition(anyString(), eq(Transition.PHASE_AWAIT));
        List<ChatEvent> saved = savedChatEvents();
        assertTrue(saved.stream().noneMatch(e -> e.type() == ChatEventType.SESSION_STATUS_IDLE
                        || e.type() == ChatEventType.SESSION_THREAD_STATUS_IDLE
                        || e.type() == ChatEventType.SESSION_ERROR),
                "排空失败后不得产生终态事件，实际: " + saved.stream().map(e -> e.type().name()).toList());
        verify(connectionHandle, never()).push(argThat(e -> e.type() == ChatEventType.SESSION_STATUS_IDLE));
        assertTimeoutPreemptively(Duration.ofSeconds(2), turn::awaitFinish);
    }

    @Test
    void should_rollbackSuspendAndStillFinishRound_when_enterWaitingConfirm_given_toolUseWriteFails() {
        // given（真实批量落库器 + agent.tool_use 写库必失败：待确认明细经重试耗尽仍不可确认落库）
        AgentSession session = idleSession();
        String sessionId = session.sessionId();
        BatchChatEventPersister persister = batchPersisterFailingOn(ChatEventType.AGENT_TOOL_USE);
        wirePersister(persister);
        persister.enqueue(ChatEvent.create(sessionId, ChatEventType.AGENT_TOOL_USE,
                "{\"tool_use_id\":\"tc-1\",\"name\":\"search\",\"input\":{}}", 1L, "evt_tool_1"));
        ExecutionContext context = newContext(session);
        rememberCandidate(context.runState(), "tc-1", "search", "{\"q\":\"x\"}", "evt_tool_1");
        TurnControl turn = new TurnControl();

        // when（事务首行查毒异常不得挂死编排线程：本轮必须按时收轮）
        assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> turnFinalizer.enterWaitingConfirm(context, suspendSignal(List.of("tc-1")), turn));

        // then（挂起事务整批回滚：不迁移 awaiting_confirmation 相位）
        verify(sessionRepository, never()).transition(anyString(), eq(Transition.PHASE_AWAIT));
        // then（也不落任何终态事件：会话保持运行态，由租约失效与启动复位路径收敛）
        verify(sessionRepository, never()).transition(anyString(), eq(Transition.FINISH_TURN));
        List<ChatEvent> saved = savedChatEvents();
        assertTrue(saved.stream().noneMatch(e -> e.type() == ChatEventType.SESSION_STATUS_IDLE
                        || e.type() == ChatEventType.SESSION_THREAD_STATUS_IDLE
                        || e.type() == ChatEventType.SESSION_ERROR),
                "明细不可信时不得落任何终态事件，实际: " + saved.stream().map(e -> e.type().name()).toList());
        // then（本轮照常收轮：turn 租约即刻释放，无悬挂）
        assertTimeoutPreemptively(Duration.ofSeconds(2), turn::awaitFinish);
        verify(coordinationLeaseService, atLeastOnce()).releaseTurnLease(sessionId);
    }

    @Test
    void should_completeRoundAndReleaseLease_when_enterWaitingConfirm_given_ledgerAlwaysPoisoned() {
        // given（挂起事务首行查毒命中抛异常：待确认明细不可信）
        AgentSession session = idleSession();
        String sessionId = session.sessionId();
        wireLedgerSave();
        wirePersister(poisonedPersister());
        ExecutionContext context = newContext(session);
        rememberCandidate(context.runState(), "tc-1", "search", "{\"q\":\"x\"}", "evt_tool_1");
        TurnControl turn = new TurnControl();

        // when（收轮必达：挂起失败路径同样不得让 awaitFinish 永久阻塞）
        assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> turnFinalizer.enterWaitingConfirm(context, suspendSignal(List.of("tc-1")), turn));

        // then（不进入等待相位、不落任何终态事件）
        verify(sessionRepository, never()).transition(anyString(), eq(Transition.PHASE_AWAIT));
        verify(sessionRepository, never()).transition(anyString(), eq(Transition.FINISH_TURN));
        List<ChatEvent> saved = savedChatEvents();
        assertTrue(saved.stream().noneMatch(e -> e.type() == ChatEventType.SESSION_STATUS_IDLE
                        || e.type() == ChatEventType.SESSION_THREAD_STATUS_IDLE
                        || e.type() == ChatEventType.SESSION_ERROR),
                "排空失败后不得落终态事件，实际: " + saved.stream().map(e -> e.type().name()).toList());
        verify(connectionHandle, never()).push(any(ChatEvent.class));
        // then（释放租约 + 收轮，会话保留 processing 交由复位路径收敛）
        verify(coordinationLeaseService, atLeastOnce()).releaseTurnLease(sessionId);
        assertTimeoutPreemptively(Duration.ofSeconds(2), turn::awaitFinish);
    }

    // ==================== 红线四：挂起即物理轮终局（成功路径次序） ====================

    @Test
    void should_stillSuspendDurable_when_enterWaitingConfirm_given_strictFlushPasses() {
        // given（真实批量落库器、无写故障：轮内在队明细随事务前 flush 先入账本）
        AgentSession session = idleSession();
        String sessionId = session.sessionId();
        BatchChatEventPersister persister = batchPersisterFailingOn(null);
        wirePersister(persister);
        persister.enqueue(ChatEvent.create(sessionId, ChatEventType.AGENT_TOOL_USE,
                "{\"tool_use_id\":\"tc-1\",\"name\":\"search\",\"input\":{}}", 1L, "evt_tool_1"));
        persister.enqueue(ChatEvent.create(sessionId, ChatEventType.AGENT_TOOL_USE,
                "{\"tool_use_id\":\"tc-2\",\"name\":\"calculator\",\"input\":{}}", 2L, "evt_tool_2"));
        ExecutionContext context = newContext(session);
        TurnRunState runState = context.runState();
        rememberCandidate(runState, "tc-1", "search", "{\"q\":\"x\"}", "evt_tool_1");
        rememberCandidate(runState, "tc-2", "calculator", "{\"expr\":\"1+1\"}", "evt_tool_2");
        wireTransition(sessionId, Transition.PHASE_AWAIT, 1);
        TurnControl turn = new TurnControl();

        // when
        turnFinalizer.enterWaitingConfirm(context, suspendSignal(List.of("tc-1", "tc-2")), turn);

        // then（等待现场由账本既有工具调用明细承载：两条 agent.tool_use 随事务前 flush 先入账本，
        //       挂起不再落 session.status_waiting_confirmation / session.requires_action 旁路事件）
        List<ChatEvent> saved = savedChatEvents();
        assertEquals(2, saved.stream().filter(e -> e.type() == ChatEventType.AGENT_TOOL_USE).count());
        assertTrue(saved.stream().allMatch(e -> e.type() == ChatEventType.AGENT_TOOL_USE),
                "挂起全程零状态事件，等待事实只由账本既有 tool_use 明细承载");
        // then（挂起成功路径行为不回归：一次相位 CAS + 收轮 + 释放租约，不落 idle 终态）
        verify(sessionRepository).transition(sessionId, Transition.PHASE_AWAIT);
        verify(sessionRepository, never()).transition(anyString(), eq(Transition.FINISH_TURN));
        assertTimeoutPreemptively(Duration.ofSeconds(2), turn::awaitFinish);
        verify(coordinationLeaseService).releaseTurnLease(sessionId);
        // then（挂起驻留不是运行终局：零终局事件）
        verify(applicationEventPublisher, never()).publishEvent(any(TurnFinished.class));
    }

    @Test
    void should_dropInFlightSnapshotAndGuardConfirmation_when_enterWaitingConfirm_given_textBlockStreamingMidSuspend() {
        // given（文本块流式中途挂起：在途块等不到 TEXT_END，快照残留会让重连回补无收尾的 event_start）
        AgentSession session = idleSession();
        String sessionId = session.sessionId();
        BatchChatEventPersister persister = batchPersisterFailingOn(null);
        wirePersister(persister);
        ExecutionContext context = newContext(session);
        TurnRunState runState = context.runState();
        runState.markInFlightStream("evt_text_1", ChatEventType.AGENT_MESSAGE, "blk-1", 0L);
        assertNotNull(runState.inFlightStream(), "前置：在途流快照已登记");
        rememberCandidate(runState, "tc-1", "search", "{\"q\":\"x\"}", "evt_tool_1");
        wireTransition(sessionId, Transition.PHASE_AWAIT, 1);
        TurnControl turn = new TurnControl();

        // when
        turnFinalizer.enterWaitingConfirm(context, suspendSignal(List.of("tc-1")), turn);

        // then（挂起成功：进入等待态）
        verify(sessionRepository).transition(sessionId, Transition.PHASE_AWAIT);
        // then（进行中流快照被清理——重连回补的判定依据即查询侧读取的同一快照，为空即不回补半块 start 帧）
        assertNull(runState.inFlightStream());
        verify(connectionHandle, never()).push(argThat(e -> e.type() == ChatEventType.EVENT_START));
        // then（等待守卫置位：AGENT_END / onComplete 兜底据此不再终态化本轮）
        assertTrue(runState.confirmationPending(), "挂起后本轮须置等待守卫");
    }

    // ==================== 红线一补强：毒发回滚严格限定本会话（跨会话不变式） ====================

    @Test
    void should_keepOtherSessionRowsCommitted_when_saveAndBroadcastTerminal_given_poisonRollbackWithSecondSessionQueued() {
        // given（A 会话本轮存在写失败事件将置毒，B 会话事件同在全局队列中——排空已移至 A 事务外的
        //       flush、逐条独立提交，B 行不得进入 A 的 JDBC 事务、不得随 A 毒发回滚丢失）
        AgentSession session = idleSession();
        String sessionId = session.sessionId();
        BatchChatEventPersister persister = batchPersisterFailingOn(ChatEventType.AGENT_THINKING);
        wirePersister(persister);
        ChatEvent otherSessionEvent = ChatEvent.create("sess_b", ChatEventType.AGENT_MESSAGE, "{}", 1L);
        persister.enqueue(otherSessionEvent);
        persister.enqueue(ChatEvent.create(sessionId, ChatEventType.AGENT_THINKING, "{}", 1L, "evt_thinking_1"));
        ExecutionContext context = newContext(session);
        TurnControl turn = new TurnControl();

        // when（A 轮进终态：事务前 flush 使 A 置毒且把 B 行独立落库；事务首行查毒回滚）
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> turnFinalizer.finalizeNormal(context, turn));

        // then（B 会话事件行已由事务外排空落库，不随 A 会话毒发回滚而消失）
        verify(chatEventRepository).save(otherSessionEvent);
        // then（B 行写入先于 A 终态事务开启——证明其不在 A 事务内写入；旧「事务内排空」机制下
        //       execute 记录先于该 save，本 InOrder 断言必失败）
        InOrder drainBeforeTerminalTx = inOrder(transactionTemplate, chatEventRepository);
        drainBeforeTerminalTx.verify(chatEventRepository).save(otherSessionEvent);
        drainBeforeTerminalTx.verify(transactionTemplate).execute(any());
        // then（A 会话仍按协议回滚：状态不迁移、不落终态事件，毒发影响严格限定本会话）
        verify(sessionRepository, never()).transition(anyString(), eq(Transition.FINISH_TURN));
        List<ChatEvent> saved = savedChatEvents();
        assertTrue(saved.stream().noneMatch(e -> e.type() == ChatEventType.SESSION_STATUS_IDLE
                        && e.sessionId().equals(sessionId)),
                "A 会话账本缺行时不得出现无终局事件的终态会话");
        assertTimeoutPreemptively(Duration.ofSeconds(2), turn::awaitFinish);
    }
}