package com.linkroa.deepdataagent.agent.domain.model;

import java.time.LocalDateTime;

/**
 * 模型配置聚合根
 * <p>合并了原来的 provider + info + config 三张表，每条记录包含完整的服务商信息、模型信息和 API 配置。</p>
 */
public class ModelConfig {

    private Long id;
    private String providerDisplayName;
    private String providerName;
    private String modelId;
    private String apiUrl;
    private String apiKey;
    private Integer defaultModel;
    private Integer enabled;
    private Integer sortOrder;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
    private Integer deleted;

    public ModelConfig() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getProviderDisplayName() {
        return providerDisplayName;
    }

    public void setProviderDisplayName(String providerDisplayName) {
        this.providerDisplayName = providerDisplayName;
    }

    public String getProviderName() {
        return providerName;
    }

    public void setProviderName(String providerName) {
        this.providerName = providerName;
    }

    public String getModelId() {
        return modelId;
    }

    public void setModelId(String modelId) {
        this.modelId = modelId;
    }

    public String getApiUrl() {
        return apiUrl;
    }

    public void setApiUrl(String apiUrl) {
        this.apiUrl = apiUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public Integer getDefaultModel() {
        return defaultModel;
    }

    public void setDefaultModel(Integer defaultModel) {
        this.defaultModel = defaultModel;
    }

    public Integer getEnabled() {
        return enabled;
    }

    public void setEnabled(Integer enabled) {
        this.enabled = enabled;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
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

    /** 模型是否可用（未删除且已启用） */
    public boolean isAvailable() {
        return (this.deleted == null || this.deleted == 0)
                && (this.enabled == null || this.enabled == 1);
    }
}