package com.linkroa.deepdataagent.agent.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 网络搜索配置属性
 * <p>配置网络搜索服务的参数，包括 API 密钥、超时时间等。</p>
 * <p>通过 @EnableConfigurationProperties 在 WebSearchConfig 中启用。</p>
 */
@ConfigurationProperties(prefix = "app.web-search")
public class WebSearchProperties {

    /**
     * API 密钥
     */
    private String apiKey = "";

    /**
     * API端点URL
     */
    private String endpoint = "https://api.tavily.com/search";

    /**
     * 最大返回结果数
     */
    private int maxResults = 5;

    /**
     * 超时时间（秒）
     */
    private int timeoutSeconds = 10;

    /**
     * 是否启用网络搜索功能
     */
    private boolean enabled = true;

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public int getMaxResults() {
        return maxResults;
    }

    public void setMaxResults(int maxResults) {
        this.maxResults = maxResults;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
