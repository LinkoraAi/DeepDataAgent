package com.linkroa.deepdataagent.agent.domain.model;

import com.linkroa.deepdataagent.agent.domain.valueobject.SessionStatus;

import java.time.LocalDateTime;

/**
 * 会话聚合根
 * <p>管理会话的元数据和生命周期。</p>
 */
public class AgentSession {

    private String id;
    private String title;
    private Long userId;
    private Long datasourceId;
    private Long modelConfigId;
    private SessionStatus status;
    private LocalDateTime lastMessageTime;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
    private Integer deleted;

    public AgentSession() {
    }

    public AgentSession(String id, String title, Long userId, Long datasourceId,
                        Long modelConfigId, SessionStatus status) {
        this.id = id;
        this.title = title;
        this.userId = userId;
        this.datasourceId = datasourceId;
        this.modelConfigId = modelConfigId;
        this.status = status;
        this.deleted = 0;
        this.createdTime = LocalDateTime.now();
        this.updatedTime = LocalDateTime.now();
        // 初始化最后消息时间，确保新会话按创建即排最前（会话未发消息时也视为最新）
        this.lastMessageTime = LocalDateTime.now();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getDatasourceId() {
        return datasourceId;
    }

    public void setDatasourceId(Long datasourceId) {
        this.datasourceId = datasourceId;
    }

    public Long getModelConfigId() {
        return modelConfigId;
    }

    public void setModelConfigId(Long modelConfigId) {
        this.modelConfigId = modelConfigId;
    }

    public SessionStatus getStatus() {
        return status;
    }

    public void setStatus(SessionStatus status) {
        this.status = status;
        this.updatedTime = LocalDateTime.now();
    }

    public LocalDateTime getLastMessageTime() {
        return lastMessageTime;
    }

    public void setLastMessageTime(LocalDateTime lastMessageTime) {
        this.lastMessageTime = lastMessageTime;
    }

    public LocalDateTime getCreatedTime() {
        return createdTime;
    }

    public void setCreatedTime(LocalDateTime createdTime) {
        this.createdTime = createdTime;
    }

    public LocalDateTime getUpdatedTime() {
        return updatedTime;
    }

    public void setUpdatedTime(LocalDateTime updatedTime) {
        this.updatedTime = updatedTime;
    }

    public Integer getDeleted() {
        return deleted;
    }

    public void setDeleted(Integer deleted) {
        this.deleted = deleted;
    }

    /** 关闭会话 */
    public void close() {
        if (this.status != SessionStatus.CLOSED && this.status != SessionStatus.DELETED) {
            this.status = SessionStatus.CLOSED;
            this.updatedTime = LocalDateTime.now();
        }
    }

    /** 归档（软删除）会话 */
    public void archive() {
        this.status = SessionStatus.DELETED;
        this.deleted = 1;
        this.updatedTime = LocalDateTime.now();
    }

    /** 会话是否已关闭 */
    public boolean isClosed() {
        return this.status == SessionStatus.CLOSED || this.deleted != null && this.deleted == 1;
    }

    /** 是否可以发起新对话 */
    public boolean canStartDialogue() {
        return this.status == SessionStatus.ACTIVE
                && (this.deleted == null || this.deleted == 0);
    }

    /** 更新最后消息时间 */
    public void touchLastMessage() {
        this.lastMessageTime = LocalDateTime.now();
        this.updatedTime = LocalDateTime.now();
    }
}
