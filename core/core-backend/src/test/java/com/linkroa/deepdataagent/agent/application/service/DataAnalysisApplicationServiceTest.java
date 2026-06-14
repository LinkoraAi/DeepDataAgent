package com.linkroa.deepdataagent.agent.application.service;

import com.linkroa.deepdataagent.agent.acl.datasource.ApiConnectionInfo;
import com.linkroa.deepdataagent.agent.acl.datasource.DatasourceGateway;
import com.linkroa.deepdataagent.agent.acl.datasource.DatasourceCategory;
import com.linkroa.deepdataagent.agent.acl.datasource.DatasourceInfo;
import com.linkroa.deepdataagent.agent.acl.datasource.JdbcCategory;
import com.linkroa.deepdataagent.agent.application.command.DataAnalysisCommand;
import com.linkroa.deepdataagent.agent.application.event.AnalysisEvent;
import com.linkroa.deepdataagent.agent.domain.model.DataAnalysisResult;
import com.linkroa.deepdataagent.agent.domain.repository.SessionRepository;
import com.linkroa.deepdataagent.agent.domain.service.DataAnalysisDomainService;
import com.linkroa.deepdataagent.agent.infrastructure.client.LLMClient;
import com.linkroa.deepdataagent.agent.infrastructure.config.SessionProperties;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.MessagePersistenceService;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.AgentSessionEntity;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.ConversationMsgEntity;
import com.linkroa.deepdataagent.agent.infrastructure.tool.AnalysisGeneratorTool;
import com.linkroa.deepdataagent.agent.infrastructure.tool.ApiDataFetcherTool;
import com.linkroa.deepdataagent.agent.infrastructure.tool.ChartGeneratorTool;
import com.linkroa.deepdataagent.agent.infrastructure.tool.SchemaRetrieverTool;
import com.linkroa.deepdataagent.agent.infrastructure.tool.SqlExecutorTool;
import com.linkroa.deepdataagent.agent.infrastructure.tool.TextToSqlTool;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * DataAnalysisApplicationService 单元测试
 * <p>测试流式数据分析执行、会话验证、消息持久化和上下文恢复等核心行为。</p>
 */
@ExtendWith(MockitoExtension.class)
class DataAnalysisApplicationServiceTest {

    @Mock
    private DataAnalysisDomainService domainService;

    @Mock
    private DatasourceGateway datasourceGateway;

    @Mock
    private LLMClient llmClient;

    @Mock
    private AgentSessionManager sessionManager;

    @Mock
    private MessagePersistenceService messagePersistenceService;

    @Mock
    private SessionRepository sessionRepository;

    @Mock
    private SessionProperties sessionProperties;

    @Mock
    private SchemaRetrieverTool schemaRetrieverTool;

    @Mock
    private TextToSqlTool textToSqlTool;

    @Mock
    private SqlExecutorTool sqlExecutorTool;

    @Mock
    private ApiDataFetcherTool apiDataFetcherTool;

    @Mock
    private ChartGeneratorTool chartGeneratorTool;

    @Mock
    private AnalysisGeneratorTool analysisGeneratorTool;

    @Mock
    private ChatModelBase chatModel;

    @Mock
    private ReActAgent mockAgent;

    private DataAnalysisApplicationService service;

    @BeforeEach
    void setUp() {
        service = new DataAnalysisApplicationService(
                domainService,
                datasourceGateway,
                List.of(),
                llmClient,
                sessionManager,
                messagePersistenceService,
                sessionRepository,
                sessionProperties,
                schemaRetrieverTool,
                textToSqlTool,
                sqlExecutorTool,
                apiDataFetcherTool,
                chartGeneratorTool,
                analysisGeneratorTool
        );
    }

    // ==================== executeStream ====================

    @Test
    void should_emitErrorAndComplete_when_executeStream_given_nonexistentSession() throws InterruptedException {
        // given
        DataAnalysisCommand command = createCommand("session-nonexistent");
        when(sessionRepository.findById("session-nonexistent")).thenReturn(Optional.empty());

        AtomicReference<AnalysisEvent> errorEvent = new AtomicReference<>();
        AtomicBoolean completed = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);

        // when
        service.executeStream(command)
                .doOnNext(event -> {
                    if (AnalysisEvent.TYPE_ERROR.equals(event.type())) {
                        errorEvent.set(event);
                    }
                })
                .doOnComplete(() -> {
                    completed.set(true);
                    latch.countDown();
                })
                .subscribe();

        latch.await(3, TimeUnit.SECONDS);

        // then
        assertNotNull(errorEvent.get());
        assertEquals("会话不存在", errorEvent.get().message());
        assertTrue(completed.get());
        verify(sessionRepository).findById("session-nonexistent");
    }

    @Test
    void should_emitErrorAndComplete_when_executeStream_given_closedSession() throws InterruptedException {
        // given
        DataAnalysisCommand command = createCommand("session-closed");
        AgentSessionEntity closedSession = createSessionEntity("session-closed", 1L, 2L, "closed", 3);
        when(sessionRepository.findById("session-closed")).thenReturn(Optional.of(closedSession));

        AtomicReference<AnalysisEvent> errorEvent = new AtomicReference<>();
        AtomicBoolean completed = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);

        // when
        service.executeStream(command)
                .doOnNext(event -> {
                    if (AnalysisEvent.TYPE_ERROR.equals(event.type())) {
                        errorEvent.set(event);
                    }
                })
                .doOnComplete(() -> {
                    completed.set(true);
                    latch.countDown();
                })
                .subscribe();

        latch.await(3, TimeUnit.SECONDS);

        // then
        assertNotNull(errorEvent.get());
        assertEquals("会话已关闭", errorEvent.get().message());
        assertTrue(completed.get());
        verify(datasourceGateway, never()).findDatasource(any());
    }

    @Test
    void should_callPersistServices_when_executeStream_given_validActiveSession() throws InterruptedException {
        // given
        DataAnalysisCommand command = createCommand("session-1");
        AgentSessionEntity activeSession = createSessionEntity("session-1", 1L, 2L, "active", 0);
        DatasourceInfo datasourceInfo = createDatasourceInfo(1L);

        when(sessionRepository.findById("session-1")).thenReturn(Optional.of(activeSession));
        when(datasourceGateway.findDatasource(1L)).thenReturn(Optional.of(datasourceInfo));
        when(sessionProperties.getContextLoadSize()).thenReturn(5);
        when(sessionRepository.findRecentMessages("session-1", 5)).thenReturn(List.of());

        InMemoryMemory memory = new InMemoryMemory();
        when(mockAgent.getMemory()).thenReturn(memory);
        when(mockAgent.call(any(List.class))).thenReturn(reactor.core.publisher.Mono.just(
                Msg.builder().role(io.agentscope.core.message.MsgRole.ASSISTANT).textContent("测试回答").build()
        ));
        when(sessionManager.getOrCreateAgent(anyString(), any(), anyString(), any(), any())).thenReturn(mockAgent);
        when(sessionManager.getOrCreateMemory("session-1")).thenReturn(null);

        AtomicBoolean completed = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);

        // when
        service.executeStream(command)
                .doOnComplete(() -> {
                    completed.set(true);
                    latch.countDown();
                })
                .onErrorResume(e -> {
                    completed.set(true);
                    latch.countDown();
                    return null;
                })
                .subscribe();

        latch.await(3, TimeUnit.SECONDS);

        // then
        assertTrue(completed.get());
        verify(messagePersistenceService).persistUserMessage(eq("session-1"), anyString());
        verify(messagePersistenceService).persistAssistantMessage(eq("session-1"), anyString());
        verify(messagePersistenceService).updateSessionMetadata("session-1");
        verify(messagePersistenceService).generateAndSetTitle(eq("session-1"), eq(2L), anyString(), eq(true));
    }

    @Test
    void should_setIsFirstAnalysisFalse_when_executeStream_given_existingMessages() throws InterruptedException {
        // given
        DataAnalysisCommand command = createCommand("session-2");
        AgentSessionEntity activeSession = createSessionEntity("session-2", 1L, 2L, "active", 5);
        DatasourceInfo datasourceInfo = createDatasourceInfo(1L);

        when(sessionRepository.findById("session-2")).thenReturn(Optional.of(activeSession));
        when(datasourceGateway.findDatasource(1L)).thenReturn(Optional.of(datasourceInfo));
        when(sessionProperties.getContextLoadSize()).thenReturn(5);
        when(sessionRepository.findRecentMessages("session-2", 5)).thenReturn(List.of());

        InMemoryMemory memory = new InMemoryMemory();
        when(mockAgent.getMemory()).thenReturn(memory);
        when(mockAgent.call(any(List.class))).thenReturn(reactor.core.publisher.Mono.just(
                Msg.builder().role(io.agentscope.core.message.MsgRole.ASSISTANT).textContent("测试回答").build()
        ));
        when(sessionManager.getOrCreateAgent(anyString(), any(), anyString(), any(), any())).thenReturn(mockAgent);
        when(sessionManager.getOrCreateMemory("session-2")).thenReturn(null);

        AtomicBoolean completed = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);

        // when
        service.executeStream(command)
                .doOnComplete(() -> {
                    completed.set(true);
                    latch.countDown();
                })
                .onErrorResume(e -> {
                    completed.set(true);
                    latch.countDown();
                    return null;
                })
                .subscribe();

        latch.await(3, TimeUnit.SECONDS);

        // then
        assertTrue(completed.get());
        verify(messagePersistenceService).generateAndSetTitle(eq("session-2"), eq(2L), anyString(), eq(false));
    }

    // ==================== execute (sync) ====================

    @Test
    void should_throwException_when_execute_given_nonexistentDatasource() {
        // given
        DataAnalysisCommand command = createCommand("session-1");
        when(datasourceGateway.findDatasource(1L)).thenReturn(Optional.empty());

        // when & then
        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> service.execute(command));
        assertTrue(exception.getMessage().contains("数据源不存在"));
    }

    @Test
    void should_throwException_when_execute_given_disabledDatasource() {
        // given
        DataAnalysisCommand command = createCommand("session-1");
        DatasourceInfo disabledDatasource = new DatasourceInfo(
                1L, "test", DatasourceCategory.JDBC, JdbcCategory.MYSQL, false, null, null);
        when(datasourceGateway.findDatasource(1L)).thenReturn(Optional.of(disabledDatasource));

        // when & then
        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> service.execute(command));
        assertTrue(exception.getMessage().contains("数据源未启用"));
    }

    // ==================== injectRecoveryContext (indirect via executeStream) ====================

    @Test
    void should_restoreMessages_when_executeStream_given_recentMessagesInDb() throws InterruptedException {
        // given
        DataAnalysisCommand command = createCommand("session-recovery");
        AgentSessionEntity activeSession = createSessionEntity("session-recovery", 1L, 2L, "active", 0);
        DatasourceInfo datasourceInfo = createDatasourceInfo(1L);

        ConversationMsgEntity userMsg = createMessageEntity("session-recovery", "user", "上一次的问题");
        ConversationMsgEntity assistantMsg = createMessageEntity("session-recovery", "assistant", "上一次的回答");
        List<ConversationMsgEntity> recentMessages = List.of(userMsg, assistantMsg);

        when(sessionRepository.findById("session-recovery")).thenReturn(Optional.of(activeSession));
        when(datasourceGateway.findDatasource(1L)).thenReturn(Optional.of(datasourceInfo));
        when(sessionProperties.getContextLoadSize()).thenReturn(10);
        when(sessionRepository.findRecentMessages("session-recovery", 10)).thenReturn(recentMessages);

        InMemoryMemory memory = new InMemoryMemory();
        when(mockAgent.getMemory()).thenReturn(memory);
        when(mockAgent.call(any(List.class))).thenReturn(reactor.core.publisher.Mono.just(
                Msg.builder().role(io.agentscope.core.message.MsgRole.ASSISTANT).textContent("测试回答").build()
        ));
        when(sessionManager.getOrCreateAgent(anyString(), any(), anyString(), any(), any())).thenReturn(mockAgent);
        when(sessionManager.getOrCreateMemory("session-recovery")).thenReturn(null);

        AtomicBoolean completed = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);

        // when
        service.executeStream(command)
                .doOnComplete(() -> {
                    completed.set(true);
                    latch.countDown();
                })
                .onErrorResume(e -> {
                    completed.set(true);
                    latch.countDown();
                    return null;
                })
                .subscribe();

        latch.await(3, TimeUnit.SECONDS);

        // then
        assertTrue(completed.get());
        // 验证历史消息已被注入到 memory 中
        assertEquals(2, memory.getMessages().size());
        assertEquals("上一次的问题", memory.getMessages().get(0).getTextContent());
        assertEquals("上一次的回答", memory.getMessages().get(1).getTextContent());
    }

    @Test
    void should_continueWhenLongTermMemoryFails_when_executeStream_given_memoryRetrievalError() throws InterruptedException {
        // given
        DataAnalysisCommand command = createCommand("session-mem-fail");
        AgentSessionEntity activeSession = createSessionEntity("session-mem-fail", 1L, 2L, "active", 0);
        DatasourceInfo datasourceInfo = createDatasourceInfo(1L);

        when(sessionRepository.findById("session-mem-fail")).thenReturn(Optional.of(activeSession));
        when(datasourceGateway.findDatasource(1L)).thenReturn(Optional.of(datasourceInfo));
        when(sessionProperties.getContextLoadSize()).thenReturn(5);
        when(sessionRepository.findRecentMessages("session-mem-fail", 5)).thenReturn(List.of());

        InMemoryMemory memory = new InMemoryMemory();
        when(mockAgent.getMemory()).thenReturn(memory);
        when(mockAgent.call(any(List.class))).thenReturn(reactor.core.publisher.Mono.just(
                Msg.builder().role(io.agentscope.core.message.MsgRole.ASSISTANT).textContent("测试回答").build()
        ));
        when(sessionManager.getOrCreateAgent(anyString(), any(), anyString(), any(), any())).thenReturn(mockAgent);

        // 模拟 getOrCreateMemory 返回 null，导致 NPE 被 catch 处理
        when(sessionManager.getOrCreateMemory("session-mem-fail")).thenReturn(null);

        AtomicBoolean completed = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);

        // when
        service.executeStream(command)
                .doOnComplete(() -> {
                    completed.set(true);
                    latch.countDown();
                })
                .onErrorResume(e -> {
                    completed.set(true);
                    latch.countDown();
                    return null;
                })
                .subscribe();

        latch.await(3, TimeUnit.SECONDS);

        // then
        // 即使长期记忆检索失败，流程也应该正常完成
        assertTrue(completed.get());
    }

    // ==================== execute (sync) - API branch ====================

    @Test
    void should_executeApiQueryAndReturnResult_when_execute_given_enabledApiDatasource() {
        // given
        DataAnalysisCommand command = createCommand("session-1");
        ApiConnectionInfo apiConfig = new ApiConnectionInfo(1L, List.of("users_api"));
        DatasourceInfo apiDatasource = new DatasourceInfo(
                1L, "api-datasource", DatasourceCategory.API, null, true, null, apiConfig);

        when(datasourceGateway.findDatasource(1L)).thenReturn(Optional.of(apiDatasource));
        when(datasourceGateway.extractSchema(1L)).thenReturn("API Schema info");

        List<Map<String, Object>> queryData = List.of(
                Map.of("name", "Alice", "age", 30),
                Map.of("name", "Bob", "age", 25)
        );
        when(datasourceGateway.executeApiQuery(1L, "users_api", 1000)).thenReturn(queryData);

        DataAnalysisResult expectedResult = new DataAnalysisResult(
                "API: users_api", queryData, null, "分析报告");
        when(domainService.analyze(any(), eq("API Schema info"), eq("API: users_api"), eq(queryData)))
                .thenReturn(expectedResult);

        // when
        DataAnalysisResult result = service.execute(command);

        // then
        assertNotNull(result);
        assertEquals("API: users_api", result.sql());
        assertEquals(queryData, result.queryData());
        verify(datasourceGateway).executeApiQuery(1L, "users_api", 1000);
        verify(domainService).analyze(any(), eq("API Schema info"), eq("API: users_api"), eq(queryData));
        // Verify SQL generation was NOT called for API datasource
        verify(domainService, never()).generateSql(any(), any(), any(), any());
    }

    @Test
    void should_throwException_when_execute_given_apiDatasourceWithNoSchema() {
        // given
        DataAnalysisCommand command = createCommand("session-1");
        ApiConnectionInfo apiConfig = new ApiConnectionInfo(1L, List.of());
        DatasourceInfo apiDatasource = new DatasourceInfo(
                1L, "api-datasource", DatasourceCategory.API, null, true, null, apiConfig);

        when(datasourceGateway.findDatasource(1L)).thenReturn(Optional.of(apiDatasource));
        when(datasourceGateway.extractSchema(1L)).thenReturn("API Schema info");

        // when & then
        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> service.execute(command));
        assertTrue(exception.getMessage().contains("API 数据源未配置 Schema"));
    }

    @Test
    void should_throwException_when_execute_given_disabledApiDatasource() {
        // given
        DataAnalysisCommand command = createCommand("session-1");
        DatasourceInfo disabledApiDatasource = new DatasourceInfo(
                1L, "api-datasource", DatasourceCategory.API, null, false, null, null);

        when(datasourceGateway.findDatasource(1L)).thenReturn(Optional.of(disabledApiDatasource));

        // when & then
        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> service.execute(command));
        assertTrue(exception.getMessage().contains("数据源未启用"));
    }

    // ==================== 辅助方法 ====================

    private DataAnalysisCommand createCommand(String sessionId) {
        return new DataAnalysisCommand(sessionId, 2L, "1", "今年的销售趋势如何？");
    }

    private DatasourceInfo createDatasourceInfo(Long id) {
        return new DatasourceInfo(id, "test-datasource", DatasourceCategory.JDBC, JdbcCategory.MYSQL, true, null, null);
    }

    private AgentSessionEntity createSessionEntity(String id, Long datasourceId, Long modelConfigId, String status, Integer messageCount) {
        return AgentSessionEntity.builder()
                .id(id)
                .title("测试会话")
                .datasourceId(datasourceId)
                .modelConfigId(modelConfigId)
                .status(status)
                .messageCount(messageCount)
                .createdAt("2025-01-01 00:00:00")
                .updatedAt("2025-01-01 00:00:00")
                .isDeleted(0)
                .build();
    }

    private ConversationMsgEntity createMessageEntity(String sessionId, String role, String content) {
        ConversationMsgEntity entity = new ConversationMsgEntity();
        entity.setSessionId(sessionId);
        entity.setRole(role);
        entity.setContent(content);
        entity.setCreatedAt("2025-01-01 00:00:00");
        return entity;
    }
}
