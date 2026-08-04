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
import com.linkroa.deepdataagent.agent.infrastructure.config.AgentProperties;
import com.linkroa.deepdataagent.agent.infrastructure.config.SessionProperties;
import com.linkroa.deepdataagent.agent.application.context.SessionToolContext;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.MessagePersistenceService;
import com.linkroa.deepdataagent.agent.infrastructure.sse.SSEConnectionPool;
import com.linkroa.deepdataagent.agent.infrastructure.sse.SessionEventBus;
import com.linkroa.deepdataagent.agent.infrastructure.sse.agent.AgentExecutionPool;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

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
    private SessionToolContext sessionToolContext;

    @Mock
    private SSEConnectionPool sseConnectionPool;

    @Mock
    private SessionEventBus sessionEventBus;

    @Mock
    private AgentExecutionPool agentExecutionPool;

    @Mock
    private HarnessAgent agent;

    private DataAnalysisApplicationService service;

    @BeforeEach
    void setUp() {
        // 使用真实 EventAdapter：registerContext 需返回真实 CollectorContext，供 BatchFlushManager 复制快照
        eventAdapter = new EventAdapter();

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
                sessionToolContext,
                sseConnectionPool,
                sessionEventBus,
                agentExecutionPool
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

        DataAnalysisCommand command = new DataAnalysisCommand("session-1", 200L, "100", "分析销量", false);

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

        DataAnalysisCommand command = new DataAnalysisCommand("session-1", 200L, "100", "分析销量", false);

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

        DataAnalysisCommand command = new DataAnalysisCommand("session-1", 200L, "100", "分析销量", false);

        // when
        var disposable = service.executeStream(command).subscribe();
        // 等待内侧消息流建立订阅，确保取消信号能传播到 doOnCancel
        assertTrue(subscribedLatch.await(5, TimeUnit.SECONDS));
        disposable.dispose();

        // then
        assertTrue(flushLatch.await(5, TimeUnit.SECONDS));
        assertEquals(DialogueStatus.CANCELLED, capturedStatus.get());
    }
}