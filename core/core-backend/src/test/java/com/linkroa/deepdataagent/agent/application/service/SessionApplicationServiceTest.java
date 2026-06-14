package com.linkroa.deepdataagent.agent.application.service;

import com.linkroa.deepdataagent.agent.acl.datasource.DatasourceCategory;
import com.linkroa.deepdataagent.agent.acl.datasource.DatasourceGateway;
import com.linkroa.deepdataagent.agent.acl.datasource.DatasourceInfo;
import com.linkroa.deepdataagent.agent.controller.response.MessageResponse;
import com.linkroa.deepdataagent.agent.controller.response.SessionListItem;
import com.linkroa.deepdataagent.agent.controller.response.SessionResponse;
import com.linkroa.deepdataagent.agent.domain.repository.SessionRepository;
import com.linkroa.deepdataagent.agent.infrastructure.config.SessionProperties;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.AgentSessionEntity;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.ConversationMsgEntity;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.LlmModelConfigEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * SessionApplicationService 单元测试
 * <p>测试会话创建、列表、查询、关闭及消息获取等核心行为。</p>
 */
@ExtendWith(MockitoExtension.class)
class SessionApplicationServiceTest {

    @Mock
    private SessionRepository sessionRepository;

    @Mock
    private SessionProperties sessionProperties;

    @Mock
    private DatasourceGateway datasourceGateway;

    @Mock
    private ModelConfigApplicationService modelConfigApplicationService;

    @Mock
    private AgentSessionManager agentSessionManager;

    private SessionApplicationService service;

    @BeforeEach
    void setUp() {
        service = new SessionApplicationService(
                sessionRepository, sessionProperties, datasourceGateway,
                modelConfigApplicationService, agentSessionManager);
    }

    // ==================== createSession ====================

    @Test
    void should_returnSessionResponse_when_createSession_given_validInput() {
        // given
        Long datasourceId = 1L;
        Long modelConfigId = 2L;
        DatasourceInfo datasourceInfo = createDatasourceInfo(datasourceId);
        LlmModelConfigEntity modelConfig = createModelConfigEntity(modelConfigId);

        when(datasourceGateway.findDatasource(datasourceId)).thenReturn(Optional.of(datasourceInfo));
        when(modelConfigApplicationService.getConfigById(modelConfigId)).thenReturn(modelConfig);
        when(sessionProperties.getMaxActiveSessions()).thenReturn(100);
        when(sessionRepository.countActiveSessions()).thenReturn(5);
        doNothing().when(sessionRepository).save(any());

        // when
        SessionResponse result = service.createSession(datasourceId, modelConfigId);

        // then
        assertNotNull(result);
        assertTrue(result.id().startsWith("session-"));
        assertEquals("新对话", result.title());
        assertEquals(datasourceId, result.datasourceId());
        assertEquals(modelConfigId, result.modelConfigId());
        assertEquals("active", result.status());
        assertEquals(0, result.messageCount());
        verify(sessionRepository).save(any(AgentSessionEntity.class));
    }

    @Test
    void should_throwException_when_createSession_given_nonexistentDatasource() {
        // given
        when(datasourceGateway.findDatasource(999L)).thenReturn(Optional.empty());

        // when & then
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.createSession(999L, 1L));
        assertTrue(exception.getMessage().contains("数据源不存在"));
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void should_throwException_when_createSession_given_nonexistentModelConfig() {
        // given
        DatasourceInfo datasourceInfo = createDatasourceInfo(1L);
        when(datasourceGateway.findDatasource(1L)).thenReturn(Optional.of(datasourceInfo));
        when(modelConfigApplicationService.getConfigById(999L)).thenReturn(null);

        // when & then
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.createSession(1L, 999L));
        assertTrue(exception.getMessage().contains("模型配置不存在"));
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void should_throwException_when_createSession_given_activeSessionCountAtLimit() {
        // given
        DatasourceInfo datasourceInfo = createDatasourceInfo(1L);
        LlmModelConfigEntity modelConfig = createModelConfigEntity(1L);

        when(datasourceGateway.findDatasource(1L)).thenReturn(Optional.of(datasourceInfo));
        when(modelConfigApplicationService.getConfigById(1L)).thenReturn(modelConfig);
        when(sessionProperties.getMaxActiveSessions()).thenReturn(100);
        when(sessionRepository.countActiveSessions()).thenReturn(100);

        // when & then
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> service.createSession(1L, 1L));
        assertTrue(exception.getMessage().contains("活跃会话数已达上限"));
        verify(sessionRepository, never()).save(any());
    }

    // ==================== listSessions ====================

    @Test
    void should_returnSessionListItems_when_listSessions_given_activeSessions() {
        // given
        List<AgentSessionEntity> activeSessions = List.of(
                createSessionEntity("session-1", "会话1", 1L, 1L),
                createSessionEntity("session-2", "会话2", 2L, 2L)
        );
        when(sessionRepository.findActiveSessions()).thenReturn(activeSessions);

        // when
        List<SessionListItem> result = service.listSessions();

        // then
        assertEquals(2, result.size());
        assertEquals("session-1", result.get(0).id());
        assertEquals("会话1", result.get(0).title());
        assertEquals("session-2", result.get(1).id());
    }

    @Test
    void should_returnEmptyList_when_listSessions_given_noActiveSessions() {
        // given
        when(sessionRepository.findActiveSessions()).thenReturn(List.of());

        // when
        List<SessionListItem> result = service.listSessions();

        // then
        assertTrue(result.isEmpty());
    }

    // ==================== getSession ====================

    @Test
    void should_returnSessionResponse_when_getSession_given_validSessionId() {
        // given
        AgentSessionEntity entity = createSessionEntity("session-1", "测试会话", 1L, 1L);
        when(sessionRepository.findById("session-1")).thenReturn(Optional.of(entity));

        // when
        SessionResponse result = service.getSession("session-1");

        // then
        assertNotNull(result);
        assertEquals("session-1", result.id());
        assertEquals("测试会话", result.title());
        assertEquals(1L, result.datasourceId());
    }

    @Test
    void should_throwException_when_getSession_given_nonexistentSessionId() {
        // given
        when(sessionRepository.findById("nonexistent")).thenReturn(Optional.empty());

        // when & then
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.getSession("nonexistent"));
        assertTrue(exception.getMessage().contains("会话不存在"));
    }

    // ==================== closeSession ====================

    @Test
    void should_closeSession_when_closeSession_given_activeSession() {
        // given
        AgentSessionEntity entity = createSessionEntity("session-1", "测试会话", 1L, 1L);
        when(sessionRepository.findById("session-1")).thenReturn(Optional.of(entity));
        when(sessionRepository.closeSession("session-1")).thenReturn(1);

        // when
        service.closeSession("session-1");

        // then
        verify(sessionRepository).closeSession("session-1");
        verify(agentSessionManager).evictSession("session-1");
    }

    @Test
    void should_throwException_when_closeSession_given_nonexistentSessionId() {
        // given
        when(sessionRepository.findById("nonexistent")).thenReturn(Optional.empty());

        // when & then
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.closeSession("nonexistent"));
        assertTrue(exception.getMessage().contains("会话不存在"));
        verify(sessionRepository, never()).closeSession(any());
        verify(agentSessionManager, never()).evictSession(any());
    }

    @Test
    void should_throwException_when_closeSession_given_alreadyClosedSession() {
        // given
        AgentSessionEntity entity = AgentSessionEntity.builder()
                .id("session-1")
                .title("测试会话")
                .datasourceId(1L)
                .modelConfigId(1L)
                .status("closed")
                .messageCount(5)
                .isDeleted(0)
                .build();
        when(sessionRepository.findById("session-1")).thenReturn(Optional.of(entity));

        // when & then
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> service.closeSession("session-1"));
        assertTrue(exception.getMessage().contains("已关闭"));
        verify(sessionRepository, never()).closeSession(any());
        verify(agentSessionManager, never()).evictSession(any());
    }

    // ==================== getMessages ====================

    @Test
    void should_returnMessageResponses_when_getMessages_given_validSessionId() {
        // given
        AgentSessionEntity entity = createSessionEntity("session-1", "测试会话", 1L, 1L);
        List<ConversationMsgEntity> messages = List.of(
                createMessageEntity(1L, "session-1", "user", "你好"),
                createMessageEntity(2L, "session-1", "assistant", "你好！有什么可以帮助你的？")
        );

        when(sessionRepository.findById("session-1")).thenReturn(Optional.of(entity));
        when(sessionRepository.findMessagesBySessionId("session-1", 50, 0)).thenReturn(messages);

        // when
        List<MessageResponse> result = service.getMessages("session-1", 50, 0);

        // then
        assertEquals(2, result.size());
        assertEquals(1L, result.get(0).id());
        assertEquals("user", result.get(0).role());
        assertEquals("你好", result.get(0).content());
        assertEquals(2L, result.get(1).id());
        assertEquals("assistant", result.get(1).role());
    }

    @Test
    void should_returnEmptyList_when_getMessages_given_noMessages() {
        // given
        AgentSessionEntity entity = createSessionEntity("session-1", "测试会话", 1L, 1L);
        when(sessionRepository.findById("session-1")).thenReturn(Optional.of(entity));
        when(sessionRepository.findMessagesBySessionId("session-1", 10, 5)).thenReturn(List.of());

        // when
        List<MessageResponse> result = service.getMessages("session-1", 10, 5);

        // then
        assertTrue(result.isEmpty());
        verify(sessionRepository).findMessagesBySessionId("session-1", 10, 5);
    }

    @Test
    void should_throwException_when_getMessages_given_nonexistentSessionId() {
        // given
        when(sessionRepository.findById("nonexistent")).thenReturn(Optional.empty());

        // when & then
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.getMessages("nonexistent", 50, 0));
        assertTrue(exception.getMessage().contains("会话不存在"));
        verify(sessionRepository, never()).findMessagesBySessionId(any(), anyInt(), anyInt());
    }

    // ==================== 辅助方法 ====================

    private DatasourceInfo createDatasourceInfo(Long id) {
        return new DatasourceInfo(id, "test-datasource", DatasourceCategory.JDBC, null, true, null, null);
    }

    private LlmModelConfigEntity createModelConfigEntity(Long id) {
        LlmModelConfigEntity entity = new LlmModelConfigEntity();
        entity.setId(id);
        entity.setName("test-model");
        entity.setProvider("openai");
        return entity;
    }

    private AgentSessionEntity createSessionEntity(String id, String title, Long datasourceId, Long modelConfigId) {
        return AgentSessionEntity.builder()
                .id(id)
                .title(title)
                .datasourceId(datasourceId)
                .modelConfigId(modelConfigId)
                .status("active")
                .messageCount(0)
                .createdAt("2025-01-01 00:00:00")
                .updatedAt("2025-01-01 00:00:00")
                .isDeleted(0)
                .build();
    }

    private ConversationMsgEntity createMessageEntity(Long id, String sessionId, String role, String content) {
        ConversationMsgEntity entity = new ConversationMsgEntity();
        entity.setId(id);
        entity.setSessionId(sessionId);
        entity.setRole(role);
        entity.setContent(content);
        entity.setCreatedAt("2025-01-01 00:00:00");
        return entity;
    }
}
