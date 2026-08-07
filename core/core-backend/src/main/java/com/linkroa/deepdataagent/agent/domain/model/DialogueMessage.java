package com.linkroa.deepdataagent.agent.domain.model;

import com.linkroa.deepdataagent.agent.domain.valueobject.MessageRole;
import com.linkroa.deepdataagent.agent.domain.valueobject.MessageType;
import com.linkroa.deepdataagent.agent.domain.valueobject.MessageStatus;

import java.time.LocalDateTime;

/**
 * 对话消息实体
 * <p>属于 Dialogue 聚合内的实体，消息整体以 JSON 数组序列化存储于聚合根行内。</p>
 * <p>不对单条消息进行编辑或删除，只支持按轮次整体删除或重新生成。</p>
 */
public class DialogueMessage {

    private Long sequenceNumber;
    private MessageRole role;
    private MessageType messageType;
    private DialogueContent content;
    private MessageStatus status;
    private LocalDateTime startTime;
    private LocalDateTime endTime;

    public DialogueMessage() {
    }

    public DialogueMessage(Long sequenceNumber, MessageRole role, MessageType messageType,
                           DialogueContent content, MessageStatus status,
                           LocalDateTime startTime, LocalDateTime endTime) {
        this.sequenceNumber = sequenceNumber;
        this.role = role;
        this.messageType = messageType;
        this.content = content;
        this.status = status;
        this.startTime = startTime;
        this.endTime = endTime;
    }

    /** 创建一条用户消息 */
    public static DialogueMessage userMessage(long seq, String text) {
        return new DialogueMessage(seq, MessageRole.USER, MessageType.MESSAGE,
                DialogueContent.text(text), MessageStatus.COMPLETED,
                LocalDateTime.now(), LocalDateTime.now());
    }

    /** 创建一条正在进行的助手消息 */
    public static DialogueMessage inProgressMessage(long seq, MessageRole role, MessageType type) {
        return new DialogueMessage(seq, role, type,
                DialogueContent.text(""), MessageStatus.IN_PROGRESS,
                LocalDateTime.now(), null);
    }

    public Long getSequenceNumber() {
        return sequenceNumber;
    }

    public void setSequenceNumber(Long sequenceNumber) {
        this.sequenceNumber = sequenceNumber;
    }

    public MessageRole getRole() {
        return role;
    }

    public void setRole(MessageRole role) {
        this.role = role;
    }

    public MessageType getMessageType() {
        return messageType;
    }

    public void setMessageType(MessageType messageType) {
        this.messageType = messageType;
    }

    public DialogueContent getContent() {
        return content;
    }

    public void setContent(DialogueContent content) {
        this.content = content;
    }

    public MessageStatus getStatus() {
        return status;
    }

    public void setStatus(MessageStatus status) {
        this.status = status;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalDateTime startTime) {
        this.startTime = startTime;
    }

    public LocalDateTime getEndTime() {
        return endTime;
    }

    public void setEndTime(LocalDateTime endTime) {
        this.endTime = endTime;
    }

    /**
     * 标记消息完成
     * <p>如果消息已完成或已失败，则不执行任何操作（状态不可变）。</p>
     */
    public void complete() {
        if (this.status == MessageStatus.COMPLETED || this.status == MessageStatus.FAILED) {
            return;
        }
        this.status = MessageStatus.COMPLETED;
        this.endTime = LocalDateTime.now();
    }

    /**
     * 标记消息失败
     * <p>如果消息已完成或已失败，则不执行任何操作（状态不可变）。</p>
     */
    public void fail() {
        if (this.status == MessageStatus.COMPLETED || this.status == MessageStatus.FAILED) {
            return;
        }
        this.status = MessageStatus.FAILED;
        this.endTime = LocalDateTime.now();
    }
}
