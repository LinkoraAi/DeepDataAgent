package com.linkroa.deepdataagent.agent.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent 配置属性
 * <p>配置 Agent 相关的参数，包括系统提示词等。</p>
 * <p>通过 @EnableConfigurationProperties 在 AgentConfig 中启用。</p>
 */
@ConfigurationProperties(prefix = "app.agent")
public class AgentProperties {

    /**
     * JDBC 数据源的系统提示词
     */
    private String sysPrompt = "";

    /**
     * API 数据源的系统提示词
     */
    private String apiSysPrompt = "";

    public String getSysPrompt() {
        return sysPrompt;
    }

    public void setSysPrompt(String sysPrompt) {
        this.sysPrompt = sysPrompt;
    }

    public String getApiSysPrompt() {
        return apiSysPrompt;
    }

    public void setApiSysPrompt(String apiSysPrompt) {
        this.apiSysPrompt = apiSysPrompt;
    }
}
