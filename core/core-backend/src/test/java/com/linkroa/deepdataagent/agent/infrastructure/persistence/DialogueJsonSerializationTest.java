package com.linkroa.deepdataagent.agent.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.linkroa.deepdataagent.agent.domain.model.DialogueMessage;
import com.linkroa.deepdataagent.agent.domain.valueobject.MessageRole;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.DialogueEntity;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.mapper.DialogueMapper;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.repository.DialogueRepositoryImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * DialogueJsonSerializationTest 单元测试
 * <p>覆盖 dialogue.messages JSON 字段的序列化/反序列化：空列表、null、非法 JSON、全过程消息序列。</p>
 */
@ExtendWith(MockitoExtension.class)
class DialogueJsonSerializationTest {

    @Mock
    private DialogueMapper dialogueMapper;

    private ObjectMapper objectMapper;

    private DialogueRepositoryImpl repository;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        repository = new DialogueRepositoryImpl(dialogueMapper, objectMapper);
    }

    @Test
    void should_serializeFullProcessMessages_when_updateMessages_given_messageSequence() {
        // given
        List<DialogueMessage> messages = List.of(
                DialogueMessage.userMessage(1, "分析近30天销量"),
                DialogueMessage.inProgressMessage(2, MessageRole.ASSISTANT,
                        com.linkroa.deepdataagent.agent.domain.valueobject.MessageType.THINKING),
                DialogueMessage.inProgressMessage(3, com.linkroa.deepdataagent.agent.domain.valueobject.MessageRole.TOOL,
                        com.linkroa.deepdataagent.agent.domain.valueobject.MessageType.TOOL_CALL)
        );

        // when
        repository.updateMessages(10L, messages, com.linkroa.deepdataagent.agent.domain.valueobject.DialogueStatus.RUNNING);

        // then
        verify(dialogueMapper).updateMessagesAndStatus(eq(10L), anyString(), eq("RUNNING"));
        // 捕获序列化后的 JSON 并反序列化验证
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(dialogueMapper).updateMessagesAndStatus(eq(10L), jsonCaptor.capture(), eq("RUNNING"));
        String json = jsonCaptor.getValue();
        assertNotNull(json);
        assertTrue(json.contains("\"role\":\"USER\""));
        assertTrue(json.contains("\"messageType\":\"THINKING\""));
        assertTrue(json.contains("\"messageType\":\"TOOL_CALL\""));
    }

    @Test
    void should_serializeNull_when_save_given_emptyMessages() {
        // given
        com.linkroa.deepdataagent.agent.domain.model.Dialogue dialogue =
                new com.linkroa.deepdataagent.agent.domain.model.Dialogue("session-1", "问题");
        dialogue.setMessages(List.of());

        // when
        repository.save(dialogue);

        // then
        verify(dialogueMapper).insert(any(DialogueEntity.class));
    }

    @Test
    void should_returnEmptyList_when_findMessagesByDialogueId_given_nullJson() {
        // given
        when(dialogueMapper.selectMessages(anyLong())).thenReturn(null);

        // when
        List<DialogueMessage> result = repository.findMessagesByDialogueId(1L);

        // then
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void should_returnEmptyList_when_findMessagesByDialogueId_given_blankJson() {
        // given
        when(dialogueMapper.selectMessages(anyLong())).thenReturn("   ");

        // when
        List<DialogueMessage> result = repository.findMessagesByDialogueId(1L);

        // then
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void should_returnEmptyList_when_findMessagesByDialogueId_given_invalidJson() {
        // given
        when(dialogueMapper.selectMessages(anyLong())).thenReturn("not-valid-json{{{");

        // when
        List<DialogueMessage> result = repository.findMessagesByDialogueId(1L);

        // then
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void should_roundTripMessages_when_findMessagesByDialogueId_given_validJson() {
        // given
        List<DialogueMessage> messages = List.of(
                DialogueMessage.userMessage(1, "用户问题"),
                DialogueMessage.inProgressMessage(2, com.linkroa.deepdataagent.agent.domain.valueobject.MessageRole.ASSISTANT,
                        com.linkroa.deepdataagent.agent.domain.valueobject.MessageType.MESSAGE)
        );
        String json;
        try {
            json = objectMapper.writeValueAsString(messages);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        when(dialogueMapper.selectMessages(anyLong())).thenReturn(json);

        // when
        List<DialogueMessage> result = repository.findMessagesByDialogueId(1L);

        // then
        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals(1L, result.get(0).getMessageNumber());
        assertEquals(com.linkroa.deepdataagent.agent.domain.valueobject.MessageRole.USER, result.get(0).getRole());
        assertEquals("用户问题", result.get(0).getContent().result());
        assertEquals(2L, result.get(1).getMessageNumber());
    }
}