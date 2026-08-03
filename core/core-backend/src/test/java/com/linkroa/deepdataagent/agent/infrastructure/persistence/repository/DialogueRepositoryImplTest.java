package com.linkroa.deepdataagent.agent.infrastructure.persistence.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.linkroa.deepdataagent.agent.domain.model.Dialogue;
import com.linkroa.deepdataagent.agent.domain.model.DialogueMessage;
import com.linkroa.deepdataagent.agent.domain.valueobject.DialogueStatus;
import com.linkroa.deepdataagent.agent.domain.valueobject.MessageRole;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.DialogueEntity;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.mapper.DialogueMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DialogueRepositoryImpl 单元测试
 * <p>覆盖 updateMessages、markAllRunningAsFailed、save、findById、findBySessionId、
 * findRoundsBySessionId、findRunningDialogues、updateStatus、updateStatusAndMetadata 等仓储方法。</p>
 */
@ExtendWith(MockitoExtension.class)
class DialogueRepositoryImplTest {

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
    void should_updateMessages_when_updateMessages_given_messagesAndStatus() {
        // given
        List<DialogueMessage> messages = List.of(DialogueMessage.userMessage(1, "分析销量"));

        // when
        repository.updateMessages(1L, messages, DialogueStatus.RUNNING);

        // then
        verify(dialogueMapper).updateMessagesAndStatus(eq(1L), anyString(), eq("RUNNING"));
    }

    @Test
    void should_markAllRunningAsFailed_when_markAllRunningAsFailed_given_runningDialogues() {
        // when
        repository.markAllRunningAsFailed();

        // then
        verify(dialogueMapper).markAllRunningAsFailed();
    }

    @Test
    void should_saveNewDialogue_when_save_given_newDialogue() {
        // given
        Dialogue dialogue = new Dialogue("session-1", "分析销量");
        doAnswer(inv -> {
            DialogueEntity entity = inv.getArgument(0);
            entity.setId(99L);
            return 1;
        }).when(dialogueMapper).insert(any(DialogueEntity.class));

        // when
        Dialogue saved = repository.save(dialogue);

        // then
        assertEquals(99L, saved.getId());
        assertEquals(99L, dialogue.getId());
        assertEquals("session-1", saved.getSessionId());
    }

    @Test
    void should_updateExistingDialogue_when_save_given_dialogueWithExistingId() {
        // given
        Dialogue dialogue = new Dialogue("session-1", "分析销量");
        dialogue.setId(5L);
        DialogueEntity existing = DialogueEntity.builder().id(5L).build();
        when(dialogueMapper.selectById(5L)).thenReturn(existing);

        // when
        repository.save(dialogue);

        // then
        verify(dialogueMapper).updateById(any(DialogueEntity.class));
    }

    @Test
    void should_findById_when_findById_given_existingEntity() {
        // given
        String json = serializeMessages(List.of(DialogueMessage.userMessage(1, "分析销量")));
        DialogueEntity entity = DialogueEntity.builder()
                .id(1L).sessionId("session-1").userQuestion("分析销量")
                .messages(json).status("RUNNING")
                .startTime(LocalDateTime.now()).isDeleted(0).build();
        when(dialogueMapper.selectById(1L)).thenReturn(entity);

        // when
        Optional<Dialogue> result = repository.findById(1L);

        // then
        assertTrue(result.isPresent());
        assertEquals(1L, result.get().getId());
        assertEquals(DialogueStatus.RUNNING, result.get().getStatus());
        assertEquals(1, result.get().getMessages().size());
        assertEquals(MessageRole.USER, result.get().getMessages().get(0).getRole());
    }

    @Test
    void should_returnEmpty_when_findById_given_notFound() {
        // given
        when(dialogueMapper.selectById(1L)).thenReturn(null);

        // when
        Optional<Dialogue> result = repository.findById(1L);

        // then
        assertTrue(result.isEmpty());
    }

    @Test
    void should_findBySessionId_when_findBySessionId_given_entities() {
        // given
        DialogueEntity entity = DialogueEntity.builder()
                .id(1L).sessionId("session-1").status("COMPLETED").build();
        when(dialogueMapper.selectBySessionId("session-1")).thenReturn(List.of(entity));

        // when
        List<Dialogue> result = repository.findBySessionId("session-1");

        // then
        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).getId());
    }

    @Test
    void should_findLatestRounds_when_findRoundsBySessionId_given_nullCursor() {
        // given
        DialogueEntity entity = DialogueEntity.builder()
                .id(3L).sessionId("session-1").status("COMPLETED").build();
        when(dialogueMapper.selectRoundsBySessionId("session-1", null, 5))
                .thenReturn(List.of(entity));

        // when
        List<Dialogue> result = repository.findRoundsBySessionId("session-1", null, 5);

        // then
        assertEquals(1, result.size());
        assertEquals(3L, result.get(0).getId());
        verify(dialogueMapper).selectRoundsBySessionId("session-1", null, 5);
    }

    @Test
    void should_findOlderRounds_when_findRoundsBySessionId_given_beforeDialogueId() {
        // given
        DialogueEntity entity = DialogueEntity.builder()
                .id(2L).sessionId("session-1").status("COMPLETED").build();
        when(dialogueMapper.selectRoundsBySessionId("session-1", 5L, 5))
                .thenReturn(List.of(entity));

        // when
        List<Dialogue> result = repository.findRoundsBySessionId("session-1", 5L, 5);

        // then
        assertEquals(1, result.size());
        assertEquals(2L, result.get(0).getId());
        verify(dialogueMapper).selectRoundsBySessionId("session-1", 5L, 5);
    }

    @Test
    void should_returnEmpty_when_findRoundsBySessionId_given_noOlderRounds() {
        // given
        when(dialogueMapper.selectRoundsBySessionId("session-1", 1L, 5)).thenReturn(List.of());

        // when
        List<Dialogue> result = repository.findRoundsBySessionId("session-1", 1L, 5);

        // then
        assertTrue(result.isEmpty());
        verify(dialogueMapper).selectRoundsBySessionId("session-1", 1L, 5);
    }

    @Test
    void should_parseMessages_when_findRoundsBySessionId_given_entityWithMessages() {
        // given
        String json = serializeMessages(List.of(DialogueMessage.userMessage(1, "分析销量")));
        DialogueEntity entity = DialogueEntity.builder()
                .id(3L).sessionId("session-1").messages(json).status("COMPLETED").build();
        when(dialogueMapper.selectRoundsBySessionId("session-1", null, 5))
                .thenReturn(List.of(entity));

        // when
        List<Dialogue> result = repository.findRoundsBySessionId("session-1", null, 5);

        // then
        assertEquals(1, result.size());
        assertEquals(1, result.get(0).getMessages().size());
        assertEquals(MessageRole.USER, result.get(0).getMessages().get(0).getRole());
    }

    @Test
    void should_findRunningDialogues_when_findRunningDialogues_given_runningEntities() {
        // given
        DialogueEntity entity = DialogueEntity.builder()
                .id(1L).sessionId("session-1").status("RUNNING").build();
        when(dialogueMapper.selectRunning()).thenReturn(List.of(entity));

        // when
        List<Dialogue> result = repository.findRunningDialogues();

        // then
        assertEquals(1, result.size());
        assertEquals(DialogueStatus.RUNNING, result.get(0).getStatus());
    }

    @Test
    void should_updateStatus_when_updateStatus_given_status() {
        // when
        repository.updateStatus(1L, DialogueStatus.COMPLETED);

        // then
        verify(dialogueMapper).updateStatus(1L, "COMPLETED");
    }

    @Test
    void should_updateStatusAndMetadata_when_updateStatusAndMetadata_given_statusAndMetadata() {
        // when
        repository.updateStatusAndMetadata(1L, DialogueStatus.FAILED, "{\"calls\":1}");

        // then
        verify(dialogueMapper).updateStatusAndMetadata(1L, "FAILED", "{\"calls\":1}");
    }

    private String serializeMessages(List<DialogueMessage> messages) {
        try {
            return objectMapper.writeValueAsString(messages);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}