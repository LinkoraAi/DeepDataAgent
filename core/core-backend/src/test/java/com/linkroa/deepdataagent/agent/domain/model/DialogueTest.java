package com.linkroa.deepdataagent.agent.domain.model;

import com.linkroa.deepdataagent.agent.domain.valueobject.DialogueStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Dialogue 实体单元测试
 * <p>覆盖构造器、生命周期方法（start/complete/fail/cancel/interrupt）及状态判断逻辑。</p>
 */
class DialogueTest {

    private Dialogue dialogue;

    @BeforeEach
    void setUp() {
        dialogue = new Dialogue("session-1", "测试问题");
    }

    // ==================== 构造器 ====================

    @Test
    void should_initializeFields_when_constructed_given_validParams() {
        // then
        assertEquals("session-1", dialogue.getSessionId());
        assertEquals("测试问题", dialogue.getUserQuestion());
        assertEquals(DialogueStatus.PENDING, dialogue.getStatus());
        assertEquals(0, dialogue.getDeleted());
        assertNotNull(dialogue.getStartTime());
        assertNotNull(dialogue.getMessages());
        assertTrue(dialogue.getMessages().isEmpty());
    }

    @Test
    void should_createEmptyInstance_when_defaultConstructor() {
        // given & when
        Dialogue empty = new Dialogue();

        // then
        assertNull(empty.getId());
        assertNull(empty.getSessionId());
        assertNull(empty.getUserQuestion());
        assertNotNull(empty.getMessages());
    }

    // ==================== start() ====================

    @Test
    void should_changeStatusToRunning_when_start_given_pendingDialogue() {
        // when
        dialogue.start();

        // then
        assertEquals(DialogueStatus.RUNNING, dialogue.getStatus());
    }

    @Test
    void should_notChangeStatus_when_start_given_alreadyRunningDialogue() {
        // given
        dialogue.start();

        // when
        dialogue.start();

        // then
        assertEquals(DialogueStatus.RUNNING, dialogue.getStatus());
    }

    @Test
    void should_notChangeStatus_when_start_given_completedDialogue() {
        // given
        dialogue.start();
        dialogue.complete();

        // when
        dialogue.start();

        // then
        assertEquals(DialogueStatus.COMPLETED, dialogue.getStatus());
    }

    // ==================== complete() ====================

    @Test
    void should_changeStatusToCompleted_when_complete_given_runningDialogue() {
        // given
        dialogue.start();

        // when
        dialogue.complete();

        // then
        assertEquals(DialogueStatus.COMPLETED, dialogue.getStatus());
        assertNotNull(dialogue.getEndTime());
    }

    @Test
    void should_notChangeStatus_when_complete_given_alreadyCompletedDialogue() {
        // given
        dialogue.start();
        dialogue.complete();

        // when
        dialogue.complete();

        // then
        assertEquals(DialogueStatus.COMPLETED, dialogue.getStatus());
    }

    // ==================== fail() ====================

    @Test
    void should_changeStatusToFailed_when_fail_given_runningDialogue() {
        // given
        dialogue.start();

        // when
        dialogue.fail("处理超时");

        // then
        assertEquals(DialogueStatus.FAILED, dialogue.getStatus());
        assertNotNull(dialogue.getEndTime());
    }

    @Test
    void should_changeStatusToFailed_when_fail_given_pendingDialogue() {
        // when
        dialogue.fail("初始化失败");

        // then
        assertEquals(DialogueStatus.FAILED, dialogue.getStatus());
    }

    @Test
    void should_notChangeStatus_when_fail_given_alreadyCompletedDialogue() {
        // given
        dialogue.start();
        dialogue.complete();

        // when
        dialogue.fail("出错");

        // then
        assertEquals(DialogueStatus.COMPLETED, dialogue.getStatus());
    }

    // ==================== cancel() ====================

    @Test
    void should_changeStatusToCancelled_when_cancel_given_runningDialogue() {
        // given
        dialogue.start();

        // when
        dialogue.cancel();

        // then
        assertEquals(DialogueStatus.CANCELLED, dialogue.getStatus());
        assertNotNull(dialogue.getEndTime());
    }

    @Test
    void should_changeStatusToCancelled_when_cancel_given_pendingDialogue() {
        // when
        dialogue.cancel();

        // then
        assertEquals(DialogueStatus.CANCELLED, dialogue.getStatus());
    }

    @Test
    void should_notChangeStatus_when_cancel_given_alreadyCompletedDialogue() {
        // given
        dialogue.start();
        dialogue.complete();

        // when
        dialogue.cancel();

        // then
        assertEquals(DialogueStatus.COMPLETED, dialogue.getStatus());
    }

    // ==================== interrupt() ====================

    @Test
    void should_changeStatusToInterrupted_when_interrupt_given_runningDialogue() {
        // given
        dialogue.start();

        // when
        dialogue.interrupt();

        // then
        assertEquals(DialogueStatus.INTERRUPTED, dialogue.getStatus());
        assertNotNull(dialogue.getEndTime());
    }

    @Test
    void should_notChangeStatus_when_interrupt_given_pendingDialogue() {
        // when
        dialogue.interrupt();

        // then
        assertEquals(DialogueStatus.PENDING, dialogue.getStatus());
    }

    @Test
    void should_notChangeStatus_when_interrupt_given_completedDialogue() {
        // given
        dialogue.start();
        dialogue.complete();

        // when
        dialogue.interrupt();

        // then
        assertEquals(DialogueStatus.COMPLETED, dialogue.getStatus());
    }

    // ==================== addMessage() ====================

    @Test
    void should_addMessage_when_addMessage_given_validMessage() {
        // given
        DialogueMessage message = DialogueMessage.userMessage(1L, "用户问题");

        // when
        dialogue.addMessage(message);

        // then
        assertEquals(1, dialogue.getMessages().size());
        assertEquals(message, dialogue.getMessages().get(0));
    }

    @Test
    void should_accumulateMessages_when_addMessage_multipleTimes() {
        // given
        DialogueMessage msg1 = DialogueMessage.userMessage(1L, "问题1");
        DialogueMessage msg2 = DialogueMessage.userMessage(2L, "问题2");

        // when
        dialogue.addMessage(msg1);
        dialogue.addMessage(msg2);

        // then
        assertEquals(2, dialogue.getMessages().size());
    }

    // ==================== setters ====================

    @Test
    void should_updateFields_when_setters_called() {
        // given
        String newQuestion = "新问题";
        String metadata = "{\"key\":\"value\"}";

        // when
        dialogue.setUserQuestion(newQuestion);
        dialogue.setMetadata(metadata);
        dialogue.setDeleted(1);

        // then
        assertEquals(newQuestion, dialogue.getUserQuestion());
        assertEquals(metadata, dialogue.getMetadata());
        assertEquals(1, dialogue.getDeleted());
    }
}
