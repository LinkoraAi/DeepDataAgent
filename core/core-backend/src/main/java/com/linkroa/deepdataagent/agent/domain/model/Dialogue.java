package com.linkroa.deepdataagent.agent.domain.model;

import com.linkroa.deepdataagent.agent.domain.valueobject.DialogueStatus;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 对话轮次聚合根
 * <p>代表一轮完整的对话（用户提问 + Agent 完整回复）。</p>
 * <p>独立管理，通过 sessionId 关联 AgentSession（空心菱形聚合关系）。</p>
 */
public class Dialogue {

    private Long id;
    private String sessionId;
    private String userQuestion;
    private List<DialogueMessage> messages;
    private DialogueStatus status;
    private String metadata;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer deleted;

    public Dialogue() {
        this.messages = new ArrayList<>();
    }

    public Dialogue(String sessionId, String userQuestion) {
        this.sessionId = sessionId;
        this.userQuestion = userQuestion;
        this.status = DialogueStatus.PENDING;
        this.deleted = 0;
        this.startTime = LocalDateTime.now();
        this.messages = new ArrayList<>();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getUserQuestion() {
        return userQuestion;
    }

    public void setUserQuestion(String userQuestion) {
        this.userQuestion = userQuestion;
    }

    public List<DialogueMessage> getMessages() {
        return messages;
    }

    public void setMessages(List<DialogueMessage> messages) {
        this.messages = messages;
    }

    public void addMessage(DialogueMessage message) {
        this.messages.add(message);
    }

    public DialogueStatus getStatus() {
        return status;
    }

    public void setStatus(DialogueStatus status) {
        this.status = status;
    }

    public String getMetadata() {
        return metadata;
    }

    public void setMetadata(String metadata) {
        this.metadata = metadata;
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

    public Integer getDeleted() {
        return deleted;
    }

    public void setDeleted(Integer deleted) {
        this.deleted = deleted;
    }

    /** 标记对话轮次为 RUNNING 状态 */
    public void start() {
        if (this.status == DialogueStatus.PENDING) {
            this.status = DialogueStatus.RUNNING;
        }
    }

    /** 标记对话完成 */
    public void complete() {
        if (this.status == DialogueStatus.RUNNING) {
            this.status = DialogueStatus.COMPLETED;
            this.endTime = LocalDateTime.now();
        }
    }

    /** 标记对话失败 */
    public void fail(String error) {
        if (this.status == DialogueStatus.RUNNING || this.status == DialogueStatus.PENDING) {
            this.status = DialogueStatus.FAILED;
            this.endTime = LocalDateTime.now();
        }
    }

    /** 标记对话取消 */
    public void cancel() {
        if (this.status == DialogueStatus.RUNNING || this.status == DialogueStatus.PENDING) {
            this.status = DialogueStatus.CANCELLED;
            this.endTime = LocalDateTime.now();
        }
    }

    /** 标记对话被中断 */
    public void interrupt() {
        if (this.status == DialogueStatus.RUNNING) {
            this.status = DialogueStatus.INTERRUPTED;
            this.endTime = LocalDateTime.now();
        }
    }
}
