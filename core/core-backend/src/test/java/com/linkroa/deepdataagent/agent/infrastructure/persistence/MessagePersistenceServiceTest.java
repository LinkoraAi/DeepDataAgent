package com.linkroa.deepdataagent.agent.infrastructure.persistence;

import com.linkroa.deepdataagent.agent.domain.model.Dialogue;
import com.linkroa.deepdataagent.agent.domain.model.DialogueMessage;
import com.linkroa.deepdataagent.agent.domain.repository.AgentSessionRepository;
import com.linkroa.deepdataagent.agent.domain.repository.DialogueRepository;
import com.linkroa.deepdataagent.agent.domain.valueobject.DialogueStatus;
import com.linkroa.deepdataagent.agent.domain.valueobject.MessageRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

/**
 * MessagePersistenceService 单元测试
 * <p>覆盖用户消息同步持久化并返回 dialogueId、攒批写入、会话最后消息时间触摸。</p>
 */
@ExtendWith(MockitoExtension.class)
class MessagePersistenceServiceTest {

    @Mock
    private AgentSessionRepository sessionRepository;

    @Mock
    private DialogueRepository dialogueRepository;

    private MessagePersistenceService service;

    @BeforeEach
    void setUp() {
        service = new MessagePersistenceService(sessionRepository, dialogueRepository);
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
        assertEquals(1L, messages.get(0).getSequenceNumber());
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
}