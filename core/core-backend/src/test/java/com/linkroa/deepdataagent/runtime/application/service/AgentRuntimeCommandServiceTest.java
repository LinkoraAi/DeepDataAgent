package com.linkroa.deepdataagent.runtime.application.service;

import com.linkroa.deepdataagent.runtime.application.assembler.AgentRuntimeAssemblerImpl;
import com.linkroa.deepdataagent.runtime.application.assembler.ChatEventPayloadAssembler;
import com.linkroa.deepdataagent.runtime.application.command.CreateSessionCommand;
import com.linkroa.deepdataagent.runtime.application.command.SendMessageCommand;
import com.linkroa.deepdataagent.runtime.application.command.TerminateSessionCommand;
import com.linkroa.deepdataagent.runtime.domain.event.AgentStreamSignal;
import com.linkroa.deepdataagent.runtime.domain.event.AgentStreamSignalType;
import com.linkroa.deepdataagent.runtime.domain.event.EventBroadcaster;
import com.linkroa.deepdataagent.runtime.domain.factory.AgentFactoryPort;
import com.linkroa.deepdataagent.runtime.domain.factory.BuiltAgent;
import com.linkroa.deepdataagent.runtime.domain.gateway.AgentToolGateway;
import com.linkroa.deepdataagent.runtime.domain.model.AgentAssemblySpec;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSession;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSessionContext;
import com.linkroa.deepdataagent.runtime.domain.model.ChatEvent;
import com.linkroa.deepdataagent.runtime.domain.model.ExecutionRound;
import com.linkroa.deepdataagent.runtime.domain.model.RunTrace;
import com.linkroa.deepdataagent.runtime.domain.model.enums.AgentSessionStatus;
import com.linkroa.deepdataagent.runtime.domain.model.enums.ChatEventType;
import com.linkroa.deepdataagent.runtime.domain.model.enums.RoundStatus;
import com.linkroa.deepdataagent.runtime.domain.repository.AgentSessionRepository;
import com.linkroa.deepdataagent.runtime.domain.repository.ChatEventRepository;
import com.linkroa.deepdataagent.runtime.domain.repository.ExecutionRoundRepository;
import com.linkroa.deepdataagent.runtime.domain.repository.RunTraceRepository;
import com.linkroa.deepdataagent.runtime.infrastructure.config.AgentRuntimeProperties;
import com.linkroa.deepdataagent.runtime.infrastructure.execution.InMemorySessionRegistry;
import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AgentRuntimeCommandService} 用例编排状态机单测（写操作：创建 / 终止 / 消息发送）。
 * <p>事件流阶段以 {@code streamEvents} 返回的 {@code Flux<AgentStreamSignal>} 冷流驱动，
 * 虚拟调度器以真实 {@code Schedulers.immediate()} 同步执行，便于断言 doOnNext 编排的全部副作用。</p>
 * <p>只读查询用例见 {@link AgentRuntimeQueryServiceTest}。</p>
 */
@ExtendWith(MockitoExtension.class)
class AgentRuntimeCommandServiceTest {

    @Mock private AgentSessionRepository sessionRepository;
    @Mock private ExecutionRoundRepository roundRepository;
    @Mock private ChatEventRepository chatEventRepository;
    @Mock private RunTraceRepository runTraceRepository;
    @Mock private AgentFactoryPort agentFactory;
    @Mock private AgentRunExecutor agentRunExecutor;
    @Mock private EventBroadcaster eventBroadcaster;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private AgentToolGateway toolGateway;
    @Mock private Executor virtualExecutor;

    /** 真实会话级聚合注册表：串行守卫 / 断连中断语义按真实实现执行（与主链路一致）。 */
    private final InMemorySessionRegistry sessionRegistry = new InMemorySessionRegistry();

    /** 真实同步调度器：publishOn 后在调用线程同步执行 doOnNext（测试断言确定性强）。 */
    private final Scheduler virtualScheduler = Schedulers.immediate();

    private AgentRuntimeProperties properties;
    private AgentRuntimeCommandService service;
    private ChatEventPayloadAssembler payloadAssembler;

    @BeforeEach
    void setUp() {
        properties = new AgentRuntimeProperties();
        payloadAssembler = new ChatEventPayloadAssembler();
        ReflectionTestUtils.setField(payloadAssembler, "objectMapper", new ObjectMapper());
        assembleService();
    }

    private void assembleService() {
        service = newService(agentRunExecutor);
    }

    private AgentRuntimeCommandService newService(AgentRunExecutor executor) {
        AgentRuntimeCommandService svc = new AgentRuntimeCommandService();
        ReflectionTestUtils.setField(svc, "sessionRepository", sessionRepository);
        ReflectionTestUtils.setField(svc, "roundRepository", roundRepository);
        ReflectionTestUtils.setField(svc, "chatEventRepository", chatEventRepository);
        ReflectionTestUtils.setField(svc, "runTraceRepository", runTraceRepository);
        ReflectionTestUtils.setField(svc, "agentFactory", agentFactory);
        ReflectionTestUtils.setField(svc, "agentRunExecutor", executor);
        ReflectionTestUtils.setField(svc, "eventBroadcaster", eventBroadcaster);
        ReflectionTestUtils.setField(svc, "properties", properties);
        ReflectionTestUtils.setField(svc, "assembler", new AgentRuntimeAssemblerImpl());
        ReflectionTestUtils.setField(svc, "toolGateway", toolGateway);
        ReflectionTestUtils.setField(svc, "sessionRegistry", sessionRegistry);
        ReflectionTestUtils.setField(svc, "transactionTemplate", transactionTemplate);
        ReflectionTestUtils.setField(svc, "payloadAssembler", payloadAssembler);
        ReflectionTestUtils.setField(svc, "virtualExecutor", virtualExecutor);
        ReflectionTestUtils.setField(svc, "virtualScheduler", virtualScheduler);
        ReflectionTestUtils.setField(svc, "objectMapper", new ObjectMapper());
        return svc;
    }

    /** 让 mock 事务模板同步执行回调（execute / executeWithoutResult），两类回调按需使用可共存。 */
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

    /** 装配事件流成功路径的公共桩（短事务 A + 构建 + 注册）。 */
    private BuiltAgent wireHappyPathForExecution() {
        wireTransactionTemplate();
        when(sessionRepository.tryMarkRunning(anyString())).thenReturn(true);
        when(sessionRepository.markIdle(anyString())).thenReturn(1);
        when(roundRepository.nextRoundNumber(anyString())).thenReturn(1);
        when(roundRepository.save(any(ExecutionRound.class))).thenAnswer(inv -> inv.getArgument(0));
        when(runTraceRepository.save(any(RunTrace.class))).thenAnswer(inv -> inv.getArgument(0));
        when(chatEventRepository.save(any(ChatEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(chatEventRepository.nextSequenceNum(anyString())).thenReturn(1L);
        when(toolGateway.availableToolNames()).thenReturn(Set.of());
        BuiltAgent agent = mock(BuiltAgent.class);
        when(agentFactory.build(any(AgentAssemblySpec.class))).thenReturn(agent);
        return agent;
    }

    /** 汇总 chatEventRepository.save 的记录（事件类型与 payload 顺序）。 */
    private List<ChatEvent> savedChatEvents() {
        return org.mockito.Mockito.mockingDetails(chatEventRepository).getInvocations().stream()
                .filter(inv -> inv.getMethod().getName().equals("save"))
                .map(inv -> inv.getArgument(0, ChatEvent.class))
                .collect(Collectors.toList());
    }

    // ==================== 会话管理 ====================

    @Test
    void should_createSession_when_createSession_given_validCommand() {
        // given
        wireTransactionTemplate();
        when(sessionRepository.save(any(AgentSession.class))).thenAnswer(inv -> inv.getArgument(0));
        CreateSessionCommand command = new CreateSessionCommand("u-1", "agent-a", "1.0.0", "会话", "{}");

        // when
        AgentSession session = service.createSession(command);

        // then
        assertEquals("u-1", session.userId());
        assertEquals(AgentSessionStatus.IDLE, session.status());
        verify(sessionRepository).save(any(AgentSession.class));
    }

    @Test
    void should_throwNotFound_when_terminateSession_given_missingSession() {
        // given
        when(sessionRepository.findBySessionId("nope")).thenReturn(Optional.empty());

        // when & then
        assertThrows(DeepDataAgentException.class, () -> service.terminateSession(new TerminateSessionCommand("nope")));
    }

    @Test
    void should_terminateSession_when_terminateSession_given_existingSession() {
        // given
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        wireTransactionTemplate();

        // when
        service.terminateSession(new TerminateSessionCommand(session.sessionId()));

        // then（终结会话：TERMINATED + 会话级聚合幂等中断（无在跑执行时空操作） + 释放 SSE 订阅）
        verify(sessionRepository).updateStatus(session.sessionId(), AgentSessionStatus.TERMINATED);
        verify(eventBroadcaster).complete(session.sessionId());
        // 会话从未执行过 → registry 无聚合实例，中断为空操作且不抛异常
        assertTrue(sessionRegistry.get(session.sessionId()).isEmpty());
        assertEquals(AgentSessionStatus.IDLE, session.status()); // 原对象不被修改（record 不可变）
    }

    @Test
    void should_interruptActiveRun_when_terminateSession_given_runningSession() {
        // given：会话聚合已创建且注册在跑执行（模拟执行中终止）
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        wireTransactionTemplate();
        BuiltAgent agent = mock(BuiltAgent.class);
        sessionRegistry.getOrCreate(session).registerActiveRun("r-1", agent);

        // when
        service.terminateSession(new TerminateSessionCommand(session.sessionId()));

        // then（幂等中断在跑 agent：句柄被清空且触发 interrupt）
        assertTrue(sessionRegistry.get(session.sessionId()).orElseThrow().activeRun().isEmpty());
        verify(agent).interrupt();
    }

    // ==================== 消息发送：状态机 ====================

    @Test
    void should_runRoundAndFinish_when_sendMessage_given_streamFlowsToAgentEnd() {
        // given
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        BuiltAgent agent = wireHappyPathForExecution();
        when(agentRunExecutor.streamEvents(any(BuiltAgent.class), anyString(), anyString(), anyString()))
                .thenReturn(Flux.just(
                        AgentStreamSignal.of(AgentStreamSignalType.TEXT_DELTA, "你好", "blk-1"),
                        AgentStreamSignal.of(AgentStreamSignalType.THINKING_END, null, "blk-0"),
                        AgentStreamSignal.of(AgentStreamSignalType.AGENT_END, null, null)));

        // when
        AgentRuntimeCommandService.SendMessageResult result =
                service.sendMessage(new SendMessageCommand(session.sessionId(), "你好"));

        // then（短事务 A + 应用层编排事件流 + 短事务 B 完整路径）
        assertNotNull(result.roundId());
        assertNotNull(result.runId());
        assertEquals("stop", result.stopReason());
        verify(sessionRepository).tryMarkRunning(session.sessionId());
        // AGENT_END 贪婪触发终态、注入 SDK 事件按序落库：run_start + text_delta(+thinking_end) + run_end + session_status
        List<ChatEvent> saved = savedChatEvents();
        assertEquals(ChatEventType.RUN_START, saved.get(0).eventType());
        assertEquals(ChatEventType.MESSAGE, saved.get(1).eventType());
        assertEquals(ChatEventType.THINKING, saved.get(2).eventType());
        assertEquals(ChatEventType.RUN_END, saved.get(3).eventType());
        assertEquals(ChatEventType.SESSION_STATUS, saved.get(4).eventType());
        assertTrue(saved.get(1).payload().contains("你好"),
                "文本增量 payload 应携带实时头部窗口文本");
        // 终态：round complete + 会话回 IDLE + 根 span 结束
        ArgumentCaptor<ExecutionRound> roundCaptor = ArgumentCaptor.forClass(ExecutionRound.class);
        verify(roundRepository, times(2)).save(roundCaptor.capture());
        assertEquals(RoundStatus.COMPLETED, roundCaptor.getAllValues().get(1).status());
        verify(sessionRepository).markIdle(session.sessionId());
        verify(sessionRepository).touchLastActive(session.sessionId());
        verify(runTraceRepository, times(2)).save(any(RunTrace.class));
        // run_start + text_delta + thinking_end + session_status 四次广播；SDK 终态只落库不广播
        verify(eventBroadcaster, times(4)).broadcast(eq(session.sessionId()), any(ChatEvent.class));
        verify(agent).close();
    }

    @Test
    void should_persistToolCallAndResultTruncated_when_sendMessage_given_toolStream() {
        // given
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        BuiltAgent agent = wireHappyPathForExecution();
        // 工具结果 20KB：head 16KB 实时窗口 + tail 4KB 截断补发
        String bigResult = "X".repeat(20 * 1024);
        when(agentRunExecutor.streamEvents(any(BuiltAgent.class), anyString(), anyString(), anyString()))
                .thenReturn(Flux.just(
                        AgentStreamSignal.tool(AgentStreamSignalType.TOOL_CALL_START, "tc-1", "search", null, null),
                        AgentStreamSignal.tool(AgentStreamSignalType.TOOL_CALL_DELTA, "tc-1", null,
                                "{\"q\":\"x\"}", null),
                        AgentStreamSignal.tool(AgentStreamSignalType.TOOL_CALL_END, "tc-1", "search", null, null),
                        AgentStreamSignal.tool(AgentStreamSignalType.TOOL_RESULT_TEXT_DELTA, "tc-1", "search", "R1", null),
                        AgentStreamSignal.tool(AgentStreamSignalType.TOOL_RESULT_TEXT_DELTA, "tc-1", "search", bigResult, null),
                        AgentStreamSignal.tool(AgentStreamSignalType.TOOL_RESULT_END, "tc-1", "search", null, "SUCCESS"),
                        AgentStreamSignal.of(AgentStreamSignalType.AGENT_END, null, null)));

        // when
        AgentRuntimeCommandService.SendMessageResult result =
                service.sendMessage(new SendMessageCommand(session.sessionId(), "你好"));

        // then：工具入参聚合（delta）、head+tail 截断、tool.call span 落库
        assertEquals("stop", result.stopReason());
        List<ChatEvent> saved = savedChatEvents().stream()
                .filter(e -> e.eventType() == ChatEventType.TOOL_CALL || e.eventType() == ChatEventType.TOOL_CALL_OUTPUT)
                .toList();
        // tool_call start + end、tool_result delta(R1) + delta(head16KB) + end(截断补发) 共 5 条
        assertEquals(5, saved.size());
        assertTrue(saved.get(1).payload().contains("\"q\":\"x\""),
                "tool_call end payload 应携带聚合后的完整入参");
        assertTrue(saved.get(4).payload().contains("truncated"),
                "tool_result end payload 应携带截断通知");
        // root + tool.call span + root.finish
        verify(runTraceRepository, times(3)).save(any(RunTrace.class));
        verify(agent).close();
    }

    @Test
    void should_throwBusy_when_sendMessage_given_sessionAlreadyRunning() {
        // given
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        wireTransactionTemplate();
        when(sessionRepository.tryMarkRunning(session.sessionId())).thenReturn(false);

        // when & then
        DeepDataAgentException ex = assertThrows(DeepDataAgentException.class,
                () -> service.sendMessage(new SendMessageCommand(session.sessionId(), "你好")));
        assertTrue(ex.getMessage().contains("DEEP_AGENT_SESSION_BUSY"));
        verify(roundRepository, never()).save(any(ExecutionRound.class));
    }

    @Test
    void should_throwNotFound_when_sendMessage_given_missingSession() {
        // given
        when(sessionRepository.findBySessionId("nope")).thenReturn(Optional.empty());

        // when & then
        DeepDataAgentException ex = assertThrows(DeepDataAgentException.class,
                () -> service.sendMessage(new SendMessageCommand("nope", "你好")));
        assertTrue(ex.getMessage().contains("DEEP_AGENT_SESSION_NOT_FOUND"));
    }

    @Test
    void should_throwNotFound_when_sendMessage_given_terminatedSession() {
        // given
        AgentSession session = AgentSession.create("u-1", "agent-a", "1.0.0", "{}", null);
        AgentSession terminated = session.withStatus(AgentSessionStatus.TERMINATED);
        when(sessionRepository.findBySessionId(terminated.sessionId())).thenReturn(Optional.of(terminated));

        // when & then
        DeepDataAgentException ex = assertThrows(DeepDataAgentException.class,
                () -> service.sendMessage(new SendMessageCommand(terminated.sessionId(), "你好")));
        assertTrue(ex.getMessage().contains("DEEP_AGENT_SESSION_NOT_FOUND"));
    }

    @Test
    void should_markFailed_when_sendMessage_given_streamErrors() {
        // given
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        BuiltAgent agent = wireHappyPathForExecution();
        // 执行失败时仍在跑注册（非断连，真实注册表），onError 走 error 终止而非 interrupted
        when(agentRunExecutor.streamEvents(any(BuiltAgent.class), anyString(), anyString(), anyString()))
                .thenReturn(Flux.error(new RuntimeException("model 调用失败")));

        // when
        AgentRuntimeCommandService.SendMessageResult result =
                service.sendMessage(new SendMessageCommand(session.sessionId(), "你好"));

        // then：双错误事件 + FAILED 终态 + 会话回 IDLE + agent 释放
        List<ChatEvent> captured = savedChatEvents();
        assertEquals(4, captured.size()); // run_start + run_error + error + session_status
        assertEquals(ChatEventType.RUN_START, captured.get(0).eventType());
        assertEquals(ChatEventType.RUN_ERROR, captured.get(1).eventType());
        assertEquals(ChatEventType.ERROR, captured.get(2).eventType());
        assertEquals(ChatEventType.SESSION_STATUS, captured.get(3).eventType());
        assertEquals("error", result.stopReason());
        verify(sessionRepository).markIdle(session.sessionId());
        verify(agent).close();
    }

    @Test
    void should_markErrorNotInterrupted_when_sendMessage_given_buildFails() {
        // given
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        wireTransactionTemplate();
        when(sessionRepository.tryMarkRunning(anyString())).thenReturn(true);
        when(sessionRepository.markIdle(anyString())).thenReturn(1);
        when(roundRepository.nextRoundNumber(anyString())).thenReturn(1);
        when(roundRepository.save(any(ExecutionRound.class))).thenAnswer(inv -> inv.getArgument(0));
        when(runTraceRepository.save(any(RunTrace.class))).thenAnswer(inv -> inv.getArgument(0));
        when(chatEventRepository.save(any(ChatEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(chatEventRepository.nextSequenceNum(anyString())).thenReturn(1L);
        // agent 构建失败（事件流启动前，会话级未注册在跑句柄）：推导为 error 而非 interrupted
        when(agentFactory.build(any(AgentAssemblySpec.class)))
                .thenThrow(new DeepDataAgentException("DEEP_AGENT_BUILD_FAILED"));

        // when
        AgentRuntimeCommandService.SendMessageResult result =
                service.sendMessage(new SendMessageCommand(session.sessionId(), "你好"));

        // then：双错误事件 + 会话回 IDLE + stop_reason=error（系统故障不误报中断）
        List<ChatEvent> captured = savedChatEvents();
        assertEquals(4, captured.size()); // run_start + run_error + error + session_status
        assertEquals(ChatEventType.RUN_START, captured.get(0).eventType());
        assertEquals(ChatEventType.RUN_ERROR, captured.get(1).eventType());
        assertEquals(ChatEventType.ERROR, captured.get(2).eventType());
        assertEquals(ChatEventType.SESSION_STATUS, captured.get(3).eventType());
        assertEquals("error", result.stopReason());
        verify(sessionRepository).markIdle(session.sessionId());
    }

    @Test
    void should_markInterrupted_when_sendMessage_given_streamCompletesAfterDisconnect() {
        // given：断连中断发生在流处理期间（会话级聚合在跑句柄被清除），SDK 正常收流但未发 AGENT_END
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        BuiltAgent agent = wireHappyPathForExecution();
        when(agentRunExecutor.streamEvents(any(BuiltAgent.class), anyString(), anyString(), anyString()))
                .thenAnswer(inv -> Flux.just(AgentStreamSignal.of(AgentStreamSignalType.TEXT_DELTA, "你好", "blk-1"))
                        .doOnNext(ignored -> sessionRegistry.get(session.sessionId())
                                .ifPresent(AgentSessionContext::interruptActiveRun)));

        // when
        AgentRuntimeCommandService.SendMessageResult result =
                service.sendMessage(new SendMessageCommand(session.sessionId(), "你好"));

        // then：流正常收尾但执行已被中断 → interrupted 终态
        assertEquals("interrupted", result.stopReason());
        ArgumentCaptor<ExecutionRound> roundCaptor = ArgumentCaptor.forClass(ExecutionRound.class);
        verify(roundRepository, times(2)).save(roundCaptor.capture());
        assertEquals(RoundStatus.FAILED, roundCaptor.getAllValues().get(1).status());
        verify(sessionRepository).markIdle(session.sessionId());
        verify(agent).close();
    }

    @Test
    void should_markCompletedWithMaxIterations_when_sendMessage_given_exceedMaxIters() {
        // given
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        BuiltAgent agent = wireHappyPathForExecution();
        when(agentRunExecutor.streamEvents(any(BuiltAgent.class), anyString(), anyString(), anyString()))
                .thenReturn(Flux.just(
                        AgentStreamSignal.of(AgentStreamSignalType.EXCEED_MAX_ITERS, null, null),
                        AgentStreamSignal.of(AgentStreamSignalType.AGENT_END, null, null)));

        // when
        AgentRuntimeCommandService.SendMessageResult result =
                service.sendMessage(new SendMessageCommand(session.sessionId(), "你好"));

        // then：迭代上限为正常终态（stop_reason=max_iterations，round=COMPLETED）
        assertEquals("max_iterations", result.stopReason());
        ArgumentCaptor<ExecutionRound> roundCaptor = ArgumentCaptor.forClass(ExecutionRound.class);
        verify(roundRepository, times(2)).save(roundCaptor.capture());
        assertEquals(RoundStatus.COMPLETED, roundCaptor.getAllValues().get(1).status());
        verify(agent).close();
    }

    @Test
    void should_keepRunId_when_sendMessage_given_preGeneratedRunId() {
        // given
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        BuiltAgent agent = wireHappyPathForExecution();
        when(agentRunExecutor.streamEvents(any(BuiltAgent.class), anyString(), anyString(), anyString()))
                .thenReturn(Flux.just(AgentStreamSignal.of(AgentStreamSignalType.TEXT_DELTA, "答案", "blk-1"),
                        AgentStreamSignal.of(AgentStreamSignalType.AGENT_END, null, null)));

        // when
        AgentRuntimeCommandService.SendMessageResult result =
                service.sendMessage(new SendMessageCommand(session.sessionId(), "你好", "pre-run-42"));

        // then
        assertEquals("pre-run-42", result.runId());
    }

    @Test
    void should_executeAsync_when_sendMessageAsync_given_validSession() {
        // given
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));

        // when
        service.sendMessageAsync(new SendMessageCommand(session.sessionId(), "你好"));

        // then：异步任务被提交到虚拟线程池
        verify(virtualExecutor).execute(any(Runnable.class));
    }

    // ==================== 工具 ====================

    private AgentSession idleSession() {
        return AgentSession.create("u-1", "agent-a", "1.0.0", "{}", null);
    }
}