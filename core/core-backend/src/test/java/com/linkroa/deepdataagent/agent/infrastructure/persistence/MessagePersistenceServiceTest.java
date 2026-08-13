package com.linkroa.deepdataagent.agent.infrastructure.persistence;

import com.linkroa.deepdataagent.agent.domain.model.Dialogue;
import com.linkroa.deepdataagent.agent.domain.model.DialogueMessage;
import com.linkroa.deepdataagent.agent.domain.repository.AgentSessionRepository;
import com.linkroa.deepdataagent.agent.domain.repository.DialogueRepository;
import com.linkroa.deepdataagent.agent.domain.valueobject.DialogueStatus;
import com.linkroa.deepdataagent.agent.domain.valueobject.MessageRole;
import com.linkroa.deepdataagent.agent.domain.valueobject.MessageType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MessagePersistenceService 单元测试
 * <p>覆盖用户消息同步持久化并返回 dialogueId、updateMessagesSync 实时全量落库（含异常传播）、
 * 会话最后消息时间触摸。</p>
 */
@ExtendWith(MockitoExtension.class)
class MessagePersistenceServiceTest {

    @Mock
    private AgentSessionRepository sessionRepository;

    @Mock
    private DialogueRepository dialogueRepository;

    @Mock
    private TransactionTemplate transactionTemplate;

    private MessagePersistenceService service;

    @BeforeEach
    void setUp() {
        service = new MessagePersistenceService(sessionRepository, dialogueRepository, transactionTemplate);
        // 模拟编程式事务：直接执行回调，不实际开启事务（updateSessionMetadataSync 测试不触发，故 lenient）
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<Long> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
    }

    @Test
    void should_returnDialogueId_when_persistUserMessageSync_given_sessionAndQuestion() {
        // given
        doAnswer(inv -> {
            Dialogue dialogue = inv.getArgument(0);
            dialogue.setId(42L);
            return dialogue;
        }).when(dialogueRepository).save(any());

        // when
        Long dialogueId = service.persistUserMessageSync("session-1", "分析销量");

        // then
        assertEquals(42L, dialogueId);
        ArgumentCaptor<List<DialogueMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(dialogueRepository).updateMessages(eq(42L), captor.capture(), eq(DialogueStatus.RUNNING));
        List<DialogueMessage> messages = captor.getValue();
        assertEquals(1, messages.size());
        assertEquals(MessageRole.USER, messages.get(0).getRole());
        assertEquals(1L, messages.get(0).getMessageNumber());
        assertEquals("分析销量", messages.get(0).getContent().result());
        verify(sessionRepository).touchLastMessage("session-1");
    }

    @Test
    void should_touchLastMessage_when_updateSessionMetadataSync_given_sessionId() {
        // when
        service.updateSessionMetadataSync("session-1");

        // then
        verify(sessionRepository).touchLastMessage("session-1");
    }

    @Test
    void should_propagateException_when_persistUserMessageSync_given_saveFails() {
        // given
        when(dialogueRepository.save(any())).thenThrow(new RuntimeException("保存对话失败"));

        // when & then
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.persistUserMessageSync("session-1", "分析销量"));
        assertEquals("保存对话失败", ex.getMessage());
        // 事务回调中途失败，后续写入不得执行，保证三处写入原子性
        verify(dialogueRepository, never()).updateMessages(any(), any(), any());
        verify(sessionRepository, never()).touchLastMessage(anyString());
    }

    @Test
    void should_updateMessages_when_updateMessagesSync_given_messagesAndStatus() {
        // given
        Long dialogueId = 42L;
        List<DialogueMessage> messages = List.of(
                DialogueMessage.userMessage(1, "分析销量"),
                DialogueMessage.inProgressMessage(2, MessageRole.ASSISTANT, MessageType.MESSAGE));

        // when
        service.updateMessagesSync(dialogueId, messages, DialogueStatus.RUNNING);

        // then
        ArgumentCaptor<List<DialogueMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(dialogueRepository).updateMessages(eq(42L), captor.capture(), eq(DialogueStatus.RUNNING));
        assertEquals(2, captor.getValue().size());
        assertEquals(messages, captor.getValue());
    }

    @Test
    void should_propagateException_when_updateMessagesSync_given_repositoryFails() {
        // given
        doThrow(new RuntimeException("写库失败"))
                .when(dialogueRepository)
                .updateMessages(any(), anyList(), any(DialogueStatus.class));

        // when & then
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.updateMessagesSync(42L, List.of(), DialogueStatus.RUNNING));
        assertEquals("写库失败", ex.getMessage());
    }
}