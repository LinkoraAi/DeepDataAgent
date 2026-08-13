package com.linkroa.deepdataagent.agent.application.service;

import com.linkroa.deepdataagent.agent.acl.datasource.DatasourceCategory;
import com.linkroa.deepdataagent.agent.acl.datasource.DatasourceGateway;
import com.linkroa.deepdataagent.agent.acl.datasource.DatasourceInfo;
import com.linkroa.deepdataagent.agent.application.context.RunningAnalysisRegistry;
import com.linkroa.deepdataagent.agent.application.dto.MessageDTO;
import com.linkroa.deepdataagent.agent.application.dto.SessionDTO;
import com.linkroa.deepdataagent.agent.application.dto.SessionListItemDTO;
import com.linkroa.deepdataagent.agent.domain.model.AgentSession;
import com.linkroa.deepdataagent.agent.domain.model.Dialogue;
import com.linkroa.deepdataagent.agent.domain.model.DialogueMessage;
import com.linkroa.deepdataagent.agent.domain.model.ModelConfig;
import com.linkroa.deepdataagent.agent.domain.repository.ModelConfigRepository;
import com.linkroa.deepdataagent.agent.domain.repository.AgentSessionRepository;
import com.linkroa.deepdataagent.agent.domain.repository.DialogueRepository;
import com.linkroa.deepdataagent.agent.domain.valueobject.MessageRole;
import com.linkroa.deepdataagent.agent.domain.valueobject.SessionStatus;
import com.linkroa.deepdataagent.agent.infrastructure.collector.AnalysisEventBuffer;
import com.linkroa.deepdataagent.agent.infrastructure.config.SessionProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * SessionApplicationService 单元测试
 * <p>测试会话应用服务的核心业务逻辑，包括会话创建、查询、列表、关闭等操作。</p>
 *
 * @author DeepDataAgent
 */
@ExtendWith(MockitoExtension.class)
class SessionApplicationServiceTest {

    @Mock
    private AgentSessionRepository sessionRepository;

    @Mock
    private ModelConfigRepository modelConfigRepository;

    @Mock
    private DialogueRepository dialogueRepository;

    @Mock
    private DatasourceGateway datasourceGateway;

    @Mock
    private SessionProperties sessionProperties;

    private RunningAnalysisRegistry runningAnalysisRegistry;

    private SessionApplicationService service;

    @BeforeEach
    void setUp() {
        runningAnalysisRegistry = new RunningAnalysisRegistry();
        service = new SessionApplicationService(
                sessionRepository,
                modelConfigRepository,
                dialogueRepository,
                sessionProperties,
                datasourceGateway,
                runningAnalysisRegistry
        );
    }

    // ==================== createSession ====================

    @Test
    void should_returnSessionResponse_when_createSession_given_validInput() {
        // given
        Long userId = 1L;
        Long datasourceId = 100L;
        Long modelConfigId = 200L;
        String userQuestion = "测试问题";

        DatasourceInfo datasourceInfo = createDatasourceInfo(datasourceId);
        when(datasourceGateway.findDatasource(datasourceId)).thenReturn(Optional.of(datasourceInfo));
        when(modelConfigRepository.findById(modelConfigId)).thenReturn(Optional.of(new ModelConfig()));
        when(sessionRepository.countActiveSessions()).thenReturn(0);
        when(sessionProperties.getMaxActiveSessions()).thenReturn(10);
        when(sessionRepository.save(any(AgentSession.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        SessionDTO result = service.createSession(userId, datasourceId, modelConfigId, userQuestion);

        // then
        assertNotNull(result);
        assertTrue(result.id().startsWith("session-"));
        assertEquals(datasourceId, result.datasourceId());
        assertEquals(modelConfigId, result.modelConfigId());
        assertEquals("ACTIVE", result.status());
        verify(sessionRepository).save(any(AgentSession.class));
    }

    @Test
    void should_throwException_when_createSession_given_nonexistentDatasource() {
        // given
        Long userId = 1L;
        Long datasourceId = 999L;
        Long modelConfigId = 200L;
        String userQuestion = "测试问题";

        when(datasourceGateway.findDatasource(datasourceId)).thenReturn(Optional.empty());

        // when & then
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.createSession(userId, datasourceId, modelConfigId, userQuestion)
        );
        assertTrue(exception.getMessage().contains("数据源不存在"));
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void should_throwException_when_createSession_given_nonexistentModelConfig() {
        // given
        Long userId = 1L;
        Long datasourceId = 100L;
        Long modelConfigId = 999L;
        String userQuestion = "测试问题";

        DatasourceInfo datasourceInfo = createDatasourceInfo(datasourceId);
        when(datasourceGateway.findDatasource(datasourceId)).thenReturn(Optional.of(datasourceInfo));
        when(modelConfigRepository.findById(modelConfigId)).thenReturn(Optional.empty());

        // when & then
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.createSession(userId, datasourceId, modelConfigId, userQuestion)
        );
        assertTrue(exception.getMessage().contains("模型配置不存在"));
        verify(sessionRepository, never()).save(any());
    }

    // ==================== getSession ====================

    @Test
    void should_returnSessionResponse_when_getSession_given_validSessionId() {
        // given
        String sessionId = "session-123";
        AgentSession session = createAgentSession(sessionId, "测试会话", 1L, 100L, 200L, SessionStatus.ACTIVE);
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

        // when
        SessionDTO result = service.getSession(sessionId);

        // then
        assertNotNull(result);
        assertEquals(sessionId, result.id());
        assertEquals("测试会话", result.title());
        assertEquals(100L, result.datasourceId());
        assertEquals(200L, result.modelConfigId());
        assertEquals("ACTIVE", result.status());
    }

    @Test
    void should_throwException_when_getSession_given_nonexistentSessionId() {
        // given
        String sessionId = "nonexistent-session";
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.empty());

        // when & then
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.getSession(sessionId)
        );
        assertTrue(exception.getMessage().contains("会话不存在"));
    }

    // ==================== listSessions ====================

    @Test
    void should_returnSessionListItems_when_listSessions_given_validLimitAndOffset() {
        // given
        Integer limit = 10;
        Integer offset = 0;
        List<AgentSession> sessions = List.of(
                createAgentSession("session-1", "会话1", 1L, 100L, 200L, SessionStatus.ACTIVE),
                createAgentSession("session-2", "会话2", 1L, 101L, 201L, SessionStatus.ACTIVE)
        );
        when(sessionRepository.findActiveSessionsPaged(limit, offset)).thenReturn(sessions);

        // when
        List<SessionListItemDTO> result = service.listSessions(limit, offset);

        // then
        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals("session-1", result.get(0).id());
        assertEquals("session-2", result.get(1).id());
        verify(sessionRepository).findActiveSessionsPaged(limit, offset);
    }

    @Test
    void should_returnAllActiveSessions_when_listSessions_given_nullParameters() {
        // given
        List<AgentSession> sessions = List.of(
                createAgentSession("session-1", "会话1", 1L, 100L, 200L, SessionStatus.ACTIVE)
        );
        when(sessionRepository.findActiveSessions()).thenReturn(sessions);

        // when
        List<SessionListItemDTO> result = service.listSessions(null, null);

        // then
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("session-1", result.get(0).id());
        verify(sessionRepository).findActiveSessions();
        verify(sessionRepository, never()).findActiveSessionsPaged(anyInt(), anyInt());
    }

    @Test
    void should_returnEmptyList_when_listSessions_given_noActiveSessions() {
        // given
        when(sessionRepository.findActiveSessions()).thenReturn(List.of());

        // when
        List<SessionListItemDTO> result = service.listSessions(null, null);

        // then
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void should_markRunningSessions_when_listSessions_given_someRunningInRegistry() {
        // given
        List<AgentSession> sessions = List.of(
                createAgentSession("session-1", "会话1", 1L, 100L, 200L, SessionStatus.ACTIVE),
                createAgentSession("session-2", "会话2", 1L, 101L, 201L, SessionStatus.ACTIVE)
        );
        when(sessionRepository.findActiveSessions()).thenReturn(sessions);
        registerRunning("session-1");

        // when
        List<SessionListItemDTO> result = service.listSessions(null, null);

        // then
        assertEquals(2, result.size());
        assertTrue(result.get(0).running());
        assertFalse(result.get(1).running());
    }

    // ==================== closeSession ====================

    @Test
    void should_closeSession_when_closeSession_given_activeSession() {
        // given
        String sessionId = "session-123";
        AgentSession session = createAgentSession(sessionId, "测试会话", 1L, 100L, 200L, SessionStatus.ACTIVE);
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

        // when
        service.closeSession(sessionId);

        // then
        assertEquals(SessionStatus.DELETED, session.getStatus());
        assertEquals(1, session.getDeleted());
        verify(sessionRepository).softDelete(sessionId);
    }

    @Test
    void should_throwException_when_closeSession_given_nonexistentSessionId() {
        // given
        String sessionId = "nonexistent-session";
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.empty());

        // when & then
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.closeSession(sessionId)
        );
        assertTrue(exception.getMessage().contains("会话不存在"));
        verify(sessionRepository, never()).softDelete(any());
    }

    @Test
    void should_throwException_when_closeSession_given_alreadyDeletedSession() {
        // given
        String sessionId = "session-123";
        AgentSession session = createAgentSession(sessionId, "测试会话", 1L, 100L, 200L, SessionStatus.DELETED);
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

        // when & then
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> service.closeSession(sessionId)
        );
        assertTrue(exception.getMessage().contains("已删除"));
        verify(sessionRepository, never()).softDelete(any());
    }

    // ==================== updateSessionTitle ====================

    @Test
    void should_updateTitle_when_updateSessionTitle_given_validSessionId() {
        // given
        String sessionId = "session-123";
        AgentSession session = createAgentSession(sessionId, "旧标题", 1L, 100L, 200L, SessionStatus.ACTIVE);
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

        // when
        service.updateSessionTitle(sessionId, " 新标题 ");

        // then
        verify(sessionRepository).updateTitle(sessionId, "新标题");
    }

    @Test
    void should_throwException_when_updateSessionTitle_given_blankTitle() {
        // when & then
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.updateSessionTitle("session-123", "   ")
        );
        assertTrue(exception.getMessage().contains("标题不能为空"));
        verify(sessionRepository, never()).updateTitle(any(), any());
    }

    @Test
    void should_throwException_when_updateSessionTitle_given_nonexistentSessionId() {
        // given
        String sessionId = "nonexistent-session";
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.empty());

        // when & then
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.updateSessionTitle(sessionId, "新标题")
        );
        assertTrue(exception.getMessage().contains("会话不存在"));
        verify(sessionRepository, never()).updateTitle(any(), any());
    }

    @Test
    void should_throwException_when_updateSessionTitle_given_deletedSession() {
        // given
        String sessionId = "session-123";
        AgentSession session = createAgentSession(sessionId, "旧标题", 1L, 100L, 200L, SessionStatus.DELETED);
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

        // when & then
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> service.updateSessionTitle(sessionId, "新标题")
        );
        assertTrue(exception.getMessage().contains("已删除"));
        verify(sessionRepository, never()).updateTitle(any(), any());
    }

    // ==================== getMessages ====================

    @Test
    void should_returnEmptyList_when_getMessages_given_validSessionId() {
        // given
        String sessionId = "session-123";
        AgentSession session = createAgentSession(sessionId, "测试会话", 1L, 100L, 200L, SessionStatus.ACTIVE);
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
        when(dialogueRepository.findRoundsBySessionId(sessionId, null, 5)).thenReturn(List.of());

        // when
        List<MessageDTO> result = service.getMessages(sessionId, null, null);

        // then
        assertNotNull(result);
        assertTrue(result.isEmpty());
        verify(dialogueRepository).findRoundsBySessionId(sessionId, null, 5);
    }

    @Test
    void should_returnLatestRounds_when_getMessages_given_multiRoundSession() {
        // given
        String sessionId = "session-123";
        AgentSession session = createAgentSession(sessionId, "测试会话", 1L, 100L, 200L, SessionStatus.ACTIVE);
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

        // 6 轮对话，每轮含 3 条消息（用户 + 思考 + 助手报告）
        List<Dialogue> rounds = List.of(
                createDialogue(6L, sessionId, "问题6", createRoundMessages("问题6", "报告6")),
                createDialogue(5L, sessionId, "问题5", createRoundMessages("问题5", "报告5")),
                createDialogue(4L, sessionId, "问题4", createRoundMessages("问题4", "报告4")),
                createDialogue(3L, sessionId, "问题3", createRoundMessages("问题3", "报告3")),
                createDialogue(2L, sessionId, "问题2", createRoundMessages("问题2", "报告2"))
        );
        when(dialogueRepository.findRoundsBySessionId(sessionId, null, 5)).thenReturn(rounds);

        // when
        List<MessageDTO> result = service.getMessages(sessionId, 5, null);

        // then
        // 只返回最新 5 轮（id 2~6），每轮 3 条消息共 15 条，最旧的第 1 轮被排除
        assertEquals(15, result.size());
        // 输出按 (dialogueId ASC, messageNumber ASC) 升序：从第 2 轮的用户消息开始
        assertEquals("问题2", result.get(0).content());
        assertEquals("user", result.get(0).role());
        assertEquals(2L, result.get(0).dialogueId());
        // 每轮消息完整（用户 + 思考 + 助手报告）
        assertEquals(3, result.stream().filter(m -> m.dialogueId() == 6L).count());
        assertEquals(3, result.stream().filter(m -> m.dialogueId() == 2L).count());
        // 最后一轮（id 6）的助手报告在列表末尾
        assertEquals("报告6", result.get(result.size() - 1).content());
        assertEquals("assistant", result.get(result.size() - 1).role());
    }

    @Test
    void should_returnAllRounds_when_getMessages_given_fewerRoundsThanLimit() {
        // given
        String sessionId = "session-123";
        AgentSession session = createAgentSession(sessionId, "测试会话", 1L, 100L, 200L, SessionStatus.ACTIVE);
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

        List<Dialogue> rounds = List.of(
                createDialogue(3L, sessionId, "问题3", createRoundMessages("问题3", "报告3")),
                createDialogue(2L, sessionId, "问题2", createRoundMessages("问题2", "报告2")),
                createDialogue(1L, sessionId, "问题1", createRoundMessages("问题1", "报告1"))
        );
        when(dialogueRepository.findRoundsBySessionId(sessionId, null, 5)).thenReturn(rounds);

        // when
        List<MessageDTO> result = service.getMessages(sessionId, 5, null);

        // then
        assertEquals(9, result.size());
        assertEquals("问题1", result.get(0).content());
        assertEquals("报告3", result.get(result.size() - 1).content());
    }

    @Test
    void should_returnOlderRounds_when_getMessages_given_beforeDialogueId() {
        // given
        String sessionId = "session-123";
        AgentSession session = createAgentSession(sessionId, "测试会话", 1L, 100L, 200L, SessionStatus.ACTIVE);
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

        // 游标 beforeDialogueId=5：返回 id < 5 的最近 5 轮（即 4,3,2,1）
        List<Dialogue> rounds = List.of(
                createDialogue(4L, sessionId, "问题4", createRoundMessages("问题4", "报告4")),
                createDialogue(3L, sessionId, "问题3", createRoundMessages("问题3", "报告3")),
                createDialogue(2L, sessionId, "问题2", createRoundMessages("问题2", "报告2")),
                createDialogue(1L, sessionId, "问题1", createRoundMessages("问题1", "报告1"))
        );
        when(dialogueRepository.findRoundsBySessionId(sessionId, 5L, 5)).thenReturn(rounds);

        // when
        List<MessageDTO> result = service.getMessages(sessionId, 5, 5L);

        // then
        assertEquals(12, result.size());
        assertEquals("问题1", result.get(0).content());
        assertEquals("报告4", result.get(result.size() - 1).content());
        verify(dialogueRepository).findRoundsBySessionId(sessionId, 5L, 5);
    }

    @Test
    void should_reconstructUserMessage_when_getMessages_given_missingUserMessageInDialogue() {
        // given
        String sessionId = "session-123";
        AgentSession session = createAgentSession(sessionId, "测试会话", 1L, 100L, 200L, SessionStatus.ACTIVE);
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

        // 历史脏数据：持久化消息缺少用户消息，只含思考消息
        DialogueMessage thinking = DialogueMessage.inProgressMessage(2L,
                MessageRole.ASSISTANT, com.linkroa.deepdataagent.agent.domain.valueobject.MessageType.THINKING);
        Dialogue dialogue = createDialogue(1L, sessionId, "分析数据库有什么内容", List.of(thinking));
        when(dialogueRepository.findRoundsBySessionId(sessionId, null, 5)).thenReturn(List.of(dialogue));

        // when
        List<MessageDTO> result = service.getMessages(sessionId, null, null);

        // then
        assertEquals(2, result.size());
        // 首条为重建的用户消息，内容来自 dialogue.user_question
        assertEquals("user", result.get(0).role());
        assertEquals("分析数据库有什么内容", result.get(0).content());
        assertEquals(1L, result.get(0).id());
        // 第二条为原始思考消息
        assertEquals("thinking", result.get(1).role());
    }

    @Test
    void should_throwException_when_getMessages_given_nonexistentSessionId() {
        // given
        String sessionId = "nonexistent-session";
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.empty());

        // when & then
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.getMessages(sessionId, 10, null)
        );
        assertTrue(exception.getMessage().contains("会话不存在"));
    }

    // ==================== 辅助方法 ====================

    /**
     * 创建指定 ID 与消息列表的对话轮次
     */
    private Dialogue createDialogue(Long id, String sessionId, String userQuestion, List<DialogueMessage> messages) {
        Dialogue dialogue = new Dialogue(sessionId, userQuestion);
        dialogue.setId(id);
        dialogue.setMessages(messages);
        return dialogue;
    }

    /**
     * 创建一轮完整消息（用户 + 思考 + 助手报告）
     */
    private List<DialogueMessage> createRoundMessages(String question, String report) {
        return List.of(
                DialogueMessage.userMessage(1L, question),
                DialogueMessage.inProgressMessage(2L,
                        MessageRole.ASSISTANT, com.linkroa.deepdataagent.agent.domain.valueobject.MessageType.THINKING),
                new DialogueMessage(3L, MessageRole.ASSISTANT,
                        com.linkroa.deepdataagent.agent.domain.valueobject.MessageType.MESSAGE,
                        com.linkroa.deepdataagent.agent.domain.model.DialogueContent.text(report),
                        com.linkroa.deepdataagent.agent.domain.valueobject.MessageStatus.COMPLETED,
                        java.time.LocalDateTime.now(), java.time.LocalDateTime.now())
        );
    }

    private DatasourceInfo createDatasourceInfo(Long id) {
        return new DatasourceInfo(id, "test-datasource", DatasourceCategory.JDBC, null, true, null, null);
    }

    private AgentSession createAgentSession(String id, String title, Long userId, Long datasourceId, Long modelConfigId, SessionStatus status) {
        return new AgentSession(id, title, userId, datasourceId, modelConfigId, status);
    }

    /**
     * 在注册表中登记一个运行中的会话执行句柄
     *
     * @param sessionId 会话 ID
     * @return 该会话对应的分析事件缓冲（用于测试回放行为）
     */
    private AnalysisEventBuffer registerRunning(String sessionId) {
        AnalysisEventBuffer buffer = new AnalysisEventBuffer();
        runningAnalysisRegistry.register(sessionId,
                new com.linkroa.deepdataagent.agent.application.context.RunningExecution(
                        1L, mock(io.agentscope.harness.agent.HarnessAgent.class),
                        mock(reactor.core.Disposable.class), null, buffer));
        return buffer;
    }
}
