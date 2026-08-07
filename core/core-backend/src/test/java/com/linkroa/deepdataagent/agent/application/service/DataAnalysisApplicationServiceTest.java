package com.linkroa.deepdataagent.agent.application.service;

import com.linkroa.deepdataagent.agent.acl.datasource.DatasourceCategory;
import com.linkroa.deepdataagent.agent.acl.datasource.DatasourceGateway;
import com.linkroa.deepdataagent.agent.acl.datasource.DatasourceInfo;
import com.linkroa.deepdataagent.agent.application.adapter.EventAdapter;
import com.linkroa.deepdataagent.agent.application.command.DataAnalysisCommand;
import com.linkroa.deepdataagent.agent.domain.model.AgentSession;
import com.linkroa.deepdataagent.agent.domain.repository.AgentSessionRepository;
import com.linkroa.deepdataagent.agent.domain.repository.DialogueRepository;
import com.linkroa.deepdataagent.agent.domain.valueobject.DialogueStatus;
import com.linkroa.deepdataagent.agent.domain.valueobject.MessageType;
import com.linkroa.deepdataagent.agent.domain.valueobject.SessionStatus;
import com.linkroa.deepdataagent.agent.infrastructure.agent.HarnessAgentFactory;
import com.linkroa.deepdataagent.agent.infrastructure.client.LLMClient;
import com.linkroa.deepdataagent.agent.infrastructure.collector.AnalysisEventBuffer;
import com.linkroa.deepdataagent.agent.infrastructure.config.AgentProperties;
import com.linkroa.deepdataagent.agent.infrastructure.config.DataAnalysisProperties;
import com.linkroa.deepdataagent.agent.infrastructure.config.SessionProperties;
import com.linkroa.deepdataagent.agent.application.context.RunningAnalysisRegistry;
import com.linkroa.deepdataagent.agent.application.context.RunningExecution;
import com.linkroa.deepdataagent.agent.application.context.SessionToolContext;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.MessagePersistenceService;
import com.linkroa.deepdataagent.agent.infrastructure.sse.SSEConnectionPool;
import com.linkroa.deepdataagent.agent.infrastructure.sse.SessionEventBus;
import com.linkroa.deepdataagent.agent.infrastructure.sse.agent.AgentExecutionPool;
import com.linkroa.deepdataagent.shared.exception.SSENotConnectedException;
import com.linkroa.deepdataagent.shared.exception.SessionNotRunningException;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * DataAnalysisApplicationService 单元测试
 * <p>覆盖应用服务构造与流式执行三条终态路径（COMPLETED/FAILED/CANCELLED）的持久化状态。</p>
 */
@ExtendWith(MockitoExtension.class)
class DataAnalysisApplicationServiceTest {

    @Mock
    private DatasourceGateway datasourceGateway;

    @Mock
    private LLMClient llmClient;

    @Mock
    private HarnessAgentFactory agentFactory;

    private EventAdapter eventAdapter;

    @Mock
    private MessagePersistenceService messagePersistenceService;

    @Mock
    private AgentSessionRepository sessionRepository;

    @Mock
    private DialogueRepository dialogueRepository;

    @Mock
    private SessionProperties sessionProperties;

    @Mock
    private AgentProperties agentProperties;

    @Mock
    private DataAnalysisProperties dataAnalysisProperties;

    @Mock
    private SessionToolContext sessionToolContext;

    @Mock
    private SSEConnectionPool sseConnectionPool;

    @Mock
    private SessionEventBus sessionEventBus;

    @Mock
    private AgentExecutionPool agentExecutionPool;

    @Mock
    private HarnessAgent agent;

    private RunningAnalysisRegistry runningAnalysisRegistry;

    private DataAnalysisApplicationService service;

    @BeforeEach
    void setUp() {
        // 使用真实 EventAdapter：registerContext 需返回真实 CollectorContext，供 BatchFlushManager 复制快照
        eventAdapter = new EventAdapter();
        // 使用真实 RunningAnalysisRegistry：停止与重连判定依赖其真实行为
        runningAnalysisRegistry = new RunningAnalysisRegistry();

        service = new DataAnalysisApplicationService(
                datasourceGateway,
                llmClient,
                agentFactory,
                eventAdapter,
                messagePersistenceService,
                sessionRepository,
                dialogueRepository,
                sessionProperties,
                agentProperties,
                dataAnalysisProperties,
                sessionToolContext,
                sseConnectionPool,
                sessionEventBus,
                agentExecutionPool,
                runningAnalysisRegistry
        );
    }

    // ==================== 基础构造测试 ====================

    @Test
    void should_createService_when_constructed_given_validDependencies() {
        // then
        assertNotNull(service);
    }

    // ==================== 流式执行三条终态路径 ====================

    /**
     * 准备流式执行所需的公共 Mock 依赖
     */
    private void prepareStreamCommonMocks(Long dialogueId) {
        AgentSession session = new AgentSession("session-1", "测试会话", 1L, 100L, 200L, SessionStatus.ACTIVE);
        // 设置最后消息时间，跳过首次分析标题生成分支
        session.setLastMessageTime(LocalDateTime.now());
        when(sessionRepository.findById("session-1")).thenReturn(Optional.of(session));

        DatasourceInfo datasource = new DatasourceInfo(
                100L, "test-ds", DatasourceCategory.JDBC, null, true, null, null);
        when(datasourceGateway.findDatasource(100L)).thenReturn(Optional.of(datasource));

        when(messagePersistenceService.persistUserMessageSync(anyString(), anyString())).thenReturn(dialogueId);
        when(agentFactory.getOrCreateAgent(any(), any(), any(), anyBoolean(), anyList())).thenReturn(agent);
        // 写库节奏：首次延迟 1s、间隔 5s（scheduleAtFixedRate 要求周期必须 > 0）
        when(dataAnalysisProperties.getInitialFlushDelaySeconds()).thenReturn(1L);
        when(dataAnalysisProperties.getFlushIntervalSeconds()).thenReturn(5L);
    }

    @Test
    void should_flushCompleted_when_executeStream_given_agentCompletes() throws Exception {
        // given
        Long dialogueId = 1L;
        prepareStreamCommonMocks(dialogueId);
        when(agent.streamEvents(anyList(), any(RuntimeContext.class))).thenReturn(Flux.empty());
        CountDownLatch flushLatch = new CountDownLatch(1);
        AtomicReference<DialogueStatus> capturedStatus = new AtomicReference<>();
        doAnswer(inv -> {
            capturedStatus.set(inv.getArgument(2));
            flushLatch.countDown();
            return null;
        }).when(dialogueRepository).updateMessages(any(), any(), any());

        DataAnalysisCommand command = new DataAnalysisCommand("session-1", 200L, "100", "分析销量", false, false);

        // when
        service.executeStream(command).blockLast();

        // then
        assertTrue(flushLatch.await(5, TimeUnit.SECONDS));
        assertEquals(DialogueStatus.COMPLETED, capturedStatus.get());
        verify(messagePersistenceService).persistUserMessageSync("session-1", "分析销量");
    }

    @Test
    void should_flushFailed_when_executeStream_given_agentErrors() throws Exception {
        // given
        Long dialogueId = 1L;
        prepareStreamCommonMocks(dialogueId);
        when(agent.streamEvents(anyList(), any(RuntimeContext.class))).thenReturn(Flux.error(new RuntimeException("模拟失败")));
        CountDownLatch flushLatch = new CountDownLatch(1);
        AtomicReference<DialogueStatus> capturedStatus = new AtomicReference<>();
        AtomicReference<List<com.linkroa.deepdataagent.agent.domain.model.DialogueMessage>> capturedMessages = new AtomicReference<>();
        doAnswer(inv -> {
            capturedStatus.set(inv.getArgument(2));
            capturedMessages.set(inv.getArgument(1));
            flushLatch.countDown();
            return null;
        }).when(dialogueRepository).updateMessages(any(), any(), any());

        DataAnalysisCommand command = new DataAnalysisCommand("session-1", 200L, "100", "分析销量", false, false);

        // when & then
        assertThrows(RuntimeException.class, () -> service.executeStream(command).blockLast());
        assertTrue(flushLatch.await(5, TimeUnit.SECONDS));
        assertEquals(DialogueStatus.FAILED, capturedStatus.get());
        // 终态 flush 的消息快照应包含 addError 追加的 ERROR 消息
        assertTrue(capturedMessages.get().stream()
                .anyMatch(m -> m.getMessageType() == MessageType.ERROR));
    }

    @Test
    void should_flushCancelled_when_executeStream_given_subscriptionCancelled() throws Exception {
        // given
        Long dialogueId = 1L;
        prepareStreamCommonMocks(dialogueId);
        // 永不完成但记录订阅的 Flux，用于确定性地测试取消路径
        CountDownLatch subscribedLatch = new CountDownLatch(1);
        when(agent.streamEvents(anyList(), any(RuntimeContext.class)))
                .thenAnswer(inv -> Flux.<AgentEvent>never().doOnSubscribe(s -> subscribedLatch.countDown()));
        CountDownLatch flushLatch = new CountDownLatch(1);
        AtomicReference<DialogueStatus> capturedStatus = new AtomicReference<>();
        doAnswer(inv -> {
            capturedStatus.set(inv.getArgument(2));
            flushLatch.countDown();
            return null;
        }).when(dialogueRepository).updateMessages(any(), any(), any());

        DataAnalysisCommand command = new DataAnalysisCommand("session-1", 200L, "100", "分析销量", false, false);

        // when
        var disposable = service.executeStream(command).subscribe();
        // 等待内侧消息流建立订阅，确保取消信号能传播到 doOnCancel
        assertTrue(subscribedLatch.await(5, TimeUnit.SECONDS));
        disposable.dispose();

        // then
        assertTrue(flushLatch.await(5, TimeUnit.SECONDS));
        assertEquals(DialogueStatus.CANCELLED, capturedStatus.get());
    }

    // ==================== 停止分析 ====================

    @Test
    void should_returnFalse_when_stopAnalysis_given_noRunningAnalysis() {
        // when
        boolean stopped = service.stopAnalysis("session-none");

        // then
        assertFalse(stopped);
    }

    @Test
    void should_disposeSubscriptionAndInterruptAgent_when_stopAnalysis_given_runningAnalysis() {
        // given
        Disposable subscription = mock(Disposable.class);
        DataAnalysisCommand command = new DataAnalysisCommand("session-1", 200L, "100", "分析销量", false, false);
        runningAnalysisRegistry.register("session-1",
                new RunningExecution(1L, agent, subscription, command, new AnalysisEventBuffer()));

        // when
        boolean stopped = service.stopAnalysis("session-1");

        // then
        assertTrue(stopped);
        verify(subscription).dispose();
        verify(agent).interrupt();
        verify(sessionEventBus).unregister("session-1");
        verify(sseConnectionPool).removeSessionClientId("session-1");
        assertFalse(runningAnalysisRegistry.isRunning("session-1"));
    }

    @Test
    void should_notDispose_when_stopAnalysis_given_alreadyDisposedSubscription() {
        // given
        Disposable subscription = mock(Disposable.class);
        when(subscription.isDisposed()).thenReturn(true);
        DataAnalysisCommand command = new DataAnalysisCommand("session-1", 200L, "100", "分析销量", false, false);
        runningAnalysisRegistry.register("session-1",
                new RunningExecution(1L, agent, subscription, command, new AnalysisEventBuffer()));

        // when
        boolean stopped = service.stopAnalysis("session-1");

        // then
        assertTrue(stopped);
        verify(subscription, never()).dispose();
        verify(agent).interrupt();
        assertFalse(runningAnalysisRegistry.isRunning("session-1"));
    }

    @Test
    void should_registerRunningExecution_when_executeStream_given_subscriptionHolder() throws Exception {
        // given
        Long dialogueId = 1L;
        prepareStreamCommonMocks(dialogueId);
        CountDownLatch subscribedLatch = new CountDownLatch(1);
        when(agent.streamEvents(anyList(), any(RuntimeContext.class)))
                .thenAnswer(inv -> Flux.<AgentEvent>never().doOnSubscribe(s -> subscribedLatch.countDown()));
        DataAnalysisCommand command = new DataAnalysisCommand("session-1", 200L, "100", "分析销量", false, false);

        // when
        DataAnalysisApplicationService.DelegatedDisposable holder =
                new DataAnalysisApplicationService.DelegatedDisposable();
        var disposable = service.executeStream(command, holder).subscribe();
        assertTrue(subscribedLatch.await(5, TimeUnit.SECONDS));

        // then 注册表应包含该会话
        assertTrue(runningAnalysisRegistry.isRunning("session-1"));
        var execution = runningAnalysisRegistry.get("session-1");
        assertNotNull(execution);
        assertEquals(dialogueId, execution.dialogueId());
        assertSame(agent, execution.agent());

        // when 停止：注册表移除并中断 agent
        boolean stopped = service.stopAnalysis("session-1");
        assertTrue(stopped);
        verify(agent).interrupt();
        assertFalse(runningAnalysisRegistry.isRunning("session-1"));
    }

    // ==================== 三态分发（启动/续流/404） ====================

    @Test
    void should_updateClientIdAndReplayEvents_when_executeAnalysis_given_runningSessionAndResumeOnly() {
        // given：会话运行中且缓冲已累积事件
        String sessionId = "session-1";
        String clientId = "client-A";
        when(sseConnectionPool.isConnected(clientId)).thenReturn(true);
        AnalysisEventBuffer buffer = new AnalysisEventBuffer();
        AgentEvent event1 = mock(AgentEvent.class);
        AgentEvent event2 = mock(AgentEvent.class);
        buffer.add(event1);
        buffer.add(event2);
        runningAnalysisRegistry.register(sessionId,
                new RunningExecution(1L, agent, mock(Disposable.class),
                        new DataAnalysisCommand(sessionId, 200L, "100", "分析销量", false, false), buffer));
        DataAnalysisCommand resumeCommand = new DataAnalysisCommand(sessionId, null, null, null, false, true);

        // when
        DataAnalysisApplicationService.AnalysisExecutionResult result = service.executeAnalysis(resumeCommand, clientId);

        // then：仅续流，不启动新分析，回放缓冲事件到新连接
        assertEquals(sessionId, result.sessionId());
        verify(sseConnectionPool).updateSessionClientId(sessionId, clientId);
        verify(sseConnectionPool).sendReplay(clientId, sessionId, List.of(event1, event2));
        // 续流绝不启动新分析
        verify(messagePersistenceService, never()).persistUserMessageSync(anyString(), anyString());
        verify(agentExecutionPool, never()).execute(anyString(), any());
    }

    @Test
    void should_updateClientIdWithoutReplay_when_executeAnalysis_given_runningSessionEmptyBuffer() {
        // given：会话运行中但缓冲为空
        String sessionId = "session-1";
        String clientId = "client-A";
        when(sseConnectionPool.isConnected(clientId)).thenReturn(true);
        AnalysisEventBuffer buffer = new AnalysisEventBuffer();
        runningAnalysisRegistry.register(sessionId,
                new RunningExecution(1L, agent, mock(Disposable.class),
                        new DataAnalysisCommand(sessionId, 200L, "100", "分析销量", false, false), buffer));
        DataAnalysisCommand resumeCommand = new DataAnalysisCommand(sessionId, null, null, null, false, true);

        // when
        DataAnalysisApplicationService.AnalysisExecutionResult result = service.executeAnalysis(resumeCommand, clientId);

        // then
        assertEquals(sessionId, result.sessionId());
        verify(sseConnectionPool).updateSessionClientId(sessionId, clientId);
        verify(sseConnectionPool, never()).sendReplay(anyString(), anyString(), anyList());
    }

    @Test
    void should_throwSessionNotRunning_when_executeAnalysis_given_notRunningSessionAndResumeOnly() {
        // given：会话未在运行中，resumeOnly 请求
        String sessionId = "session-1";
        String clientId = "client-A";
        when(sseConnectionPool.isConnected(clientId)).thenReturn(true);
        DataAnalysisCommand resumeCommand = new DataAnalysisCommand(sessionId, null, null, null, false, true);

        // when & then：抛出 404 语义异常且无任何副作用
        assertThrows(SessionNotRunningException.class, () -> service.executeAnalysis(resumeCommand, clientId));
        verify(sseConnectionPool, never()).updateSessionClientId(anyString(), anyString());
        verify(messagePersistenceService, never()).persistUserMessageSync(anyString(), anyString());
        verify(agentExecutionPool, never()).execute(anyString(), any());
    }

    @Test
    void should_throwSSENotConnected_when_executeAnalysis_given_notConnectedClient() {
        // given：客户端未建立 SSE 连接
        String sessionId = "session-1";
        String clientId = "client-A";
        when(sseConnectionPool.isConnected(clientId)).thenReturn(false);
        DataAnalysisCommand command = new DataAnalysisCommand(sessionId, 200L, "100", "分析销量", false, false);

        // when & then
        assertThrows(SSENotConnectedException.class, () -> service.executeAnalysis(command, clientId));
    }

    @Test
    void should_throwIllegalArgument_when_executeAnalysis_given_missingStartupParams() {
        // given：会话未运行、非续流，但启动参数缺失
        String sessionId = "session-1";
        String clientId = "client-A";
        when(sseConnectionPool.isConnected(clientId)).thenReturn(true);
        DataAnalysisCommand invalidCommand = new DataAnalysisCommand(sessionId, null, null, null, false, false);

        // when & then
        assertThrows(IllegalArgumentException.class, () -> service.executeAnalysis(invalidCommand, clientId));
    }

    @Test
    void should_startNewAnalysis_when_executeAnalysis_given_notRunningSessionAndNotResumeOnly() throws Exception {
        // given：会话未运行、非续流，参数完整
        String sessionId = "session-1";
        String clientId = "client-A";
        when(sseConnectionPool.isConnected(clientId)).thenReturn(true);
        Sinks.Many<AgentEvent> sink = Sinks.many().unicast().onBackpressureBuffer();
        when(sessionEventBus.register(sessionId)).thenReturn(sink);
        when(agentExecutionPool.execute(eq(sessionId), any())).thenReturn(true);
        DataAnalysisCommand command = new DataAnalysisCommand(sessionId, 200L, "100", "分析销量", false, false);

        // when
        DataAnalysisApplicationService.AnalysisExecutionResult result = service.executeAnalysis(command, clientId);

        // then：正常启动新分析
        assertEquals(sessionId, result.sessionId());
        verify(sseConnectionPool).updateSessionClientId(sessionId, clientId);
        verify(sessionEventBus).register(sessionId);
        verify(agentExecutionPool).execute(eq(sessionId), any());
    }
}