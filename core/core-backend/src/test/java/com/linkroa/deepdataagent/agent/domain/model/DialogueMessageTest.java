package com.linkroa.deepdataagent.agent.domain.model;

import com.linkroa.deepdataagent.agent.domain.valueobject.MessageRole;
import com.linkroa.deepdataagent.agent.domain.valueobject.MessageStatus;
import com.linkroa.deepdataagent.agent.domain.valueobject.MessageType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DialogueMessage 实体单元测试
 * <p>覆盖构造器、标记完成/失败等方法。</p>
 */
class DialogueMessageTest {

    private DialogueMessage message;

    @BeforeEach
    void setUp() {
        LocalDateTime now = LocalDateTime.now();
        message = new DialogueMessage(
                1L,
                MessageRole.USER,
                MessageType.MESSAGE,
                DialogueContent.text("测试问题"),
                MessageStatus.IN_PROGRESS,
                now,
                null
        );
    }

    // ==================== 构造器 ====================

    @Test
    void should_initializeFields_when_constructed_given_validParams() {
        // then
        assertEquals(1L, message.getSequenceNumber());
        assertEquals(MessageRole.USER, message.getRole());
        assertEquals(MessageType.MESSAGE, message.getMessageType());
        assertNotNull(message.getContent());
        assertEquals("测试问题", message.getContent().result());
        assertEquals(MessageStatus.IN_PROGRESS, message.getStatus());
        assertNotNull(message.getStartTime());
        assertNull(message.getEndTime());
    }

    @Test
    void should_createEmptyInstance_when_defaultConstructor() {
        // given & when
        DialogueMessage empty = new DialogueMessage();

        // then
        assertNull(empty.getSequenceNumber());
        assertNull(empty.getRole());
        assertNull(empty.getMessageType());
        assertNull(empty.getContent());
        assertNull(empty.getStatus());
    }

    // ==================== complete() ====================

    @Test
    void should_changeStatusToCompleted_when_complete_given_inProgressMessage() {
        // when
        message.complete();

        // then
        assertEquals(MessageStatus.COMPLETED, message.getStatus());
        assertNotNull(message.getEndTime());
    }

    @Test
    void should_notChangeStatus_when_complete_given_alreadyCompletedMessage() {
        // given
        message.complete();
        LocalDateTime firstEndTime = message.getEndTime();

        // when
        message.complete();

        // then
        assertEquals(MessageStatus.COMPLETED, message.getStatus());
        assertEquals(firstEndTime, message.getEndTime());
    }

    // ==================== fail() ====================

    @Test
    void should_changeStatusToFailed_when_fail_given_inProgressMessage() {
        // when
        message.fail();

        // then
        assertEquals(MessageStatus.FAILED, message.getStatus());
        assertNotNull(message.getEndTime());
    }

    @Test
    void should_notChangeStatus_when_fail_given_alreadyCompletedMessage() {
        // given
        message.complete();

        // when
        message.fail();

        // then
        assertEquals(MessageStatus.COMPLETED, message.getStatus());
    }

    // ==================== userMessage() ====================

    @Test
    void should_createUserMessage_when_userMessage_given_validParams() {
        // when
        DialogueMessage userMsg = DialogueMessage.userMessage(10L, "用户问题");

        // then
        assertEquals(10L, userMsg.getSequenceNumber());
        assertEquals(MessageRole.USER, userMsg.getRole());
        assertEquals(MessageType.MESSAGE, userMsg.getMessageType());
        assertEquals("用户问题", userMsg.getContent().result());
        assertEquals(MessageStatus.COMPLETED, userMsg.getStatus());
        assertNotNull(userMsg.getStartTime());
        assertNotNull(userMsg.getEndTime());
    }

    // ==================== inProgressMessage() ====================

    @Test
    void should_createInProgressMessage_when_inProgressMessage_given_validParams() {
        // when
        DialogueMessage inProgressMsg = DialogueMessage.inProgressMessage(
                20L, MessageRole.ASSISTANT, MessageType.MESSAGE);

        // then
        assertEquals(20L, inProgressMsg.getSequenceNumber());
        assertEquals(MessageRole.ASSISTANT, inProgressMsg.getRole());
        assertEquals(MessageType.MESSAGE, inProgressMsg.getMessageType());
        assertEquals("", inProgressMsg.getContent().result());
        assertEquals(MessageStatus.IN_PROGRESS, inProgressMsg.getStatus());
        assertNotNull(inProgressMsg.getStartTime());
        assertNull(inProgressMsg.getEndTime());
    }

    // ==================== setters ====================

    @Test
    void should_updateFields_when_setters_called() {
        // given
        LocalDateTime newStartTime = LocalDateTime.now().plusHours(1);
        LocalDateTime newEndTime = LocalDateTime.now().plusHours(2);

        // when
        message.setSequenceNumber(99L);
        message.setRole(MessageRole.ASSISTANT);
        message.setMessageType(MessageType.TOOL_CALL);
        message.setContent(DialogueContent.text("新内容"));
        message.setStatus(MessageStatus.COMPLETED);
        message.setStartTime(newStartTime);
        message.setEndTime(newEndTime);

        // then
        assertEquals(99L, message.getSequenceNumber());
        assertEquals(MessageRole.ASSISTANT, message.getRole());
        assertEquals(MessageType.TOOL_CALL, message.getMessageType());
        assertEquals("新内容", message.getContent().result());
        assertEquals(MessageStatus.COMPLETED, message.getStatus());
        assertEquals(newStartTime, message.getStartTime());
        assertEquals(newEndTime, message.getEndTime());
    }
}
