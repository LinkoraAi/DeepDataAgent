package com.linkroa.deepdataagent.runtime.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Agent 运行时配置（{@code app.agent}）。
 * <p>集中管理 AgentScope Harness 装配所需的模型 / Docker 沙箱 / SSE / PG 状态存储等参数，
 * 支持通过同名环境变量（{@code APP_AGENT_*}）覆盖，与 {@code application.yaml} 的 {@code app.agent} 段对齐。</p>
 */
@Configuration
@ConfigurationProperties(prefix = "app.agent")
public class AgentRuntimeProperties {

    /** 模型 ID（AgentScope model registry 字符串，如 dashscope:qwen-plus） */
    private String modelId = "dashscope:qwen-plus";

    /** 系统提示词（为空时不设置） */
    private String systemPrompt = "";

    /** 单轮最大迭代次数 */
    private int maxIters = 20;

    /** Docker 沙箱镜像 */
    private String sandboxImage = "ubuntu:22.04";

    /** 沙箱内存上限（字节），空表示不限制 */
    private Long sandboxMemoryBytes = 4L * 1024 * 1024 * 1024;

    /** 沙箱 CPU 核数，空表示不限制 */
    private Long sandboxCpuCount = 2L;

    /** 会话工作区根目录（后端本地文件系统，供沙箱装载工作区语义使用） */
    private String workspaceRoot = "./.agent-workspace";

    /** SSE 连接空闲超时（Spring WebMvc 7 超时固定，配合心跳探测死连接） */
    private Duration sseTimeout = Duration.ofMinutes(30);

    /** PG 状态存储 schema（AgentScope 扩展自动建 schema/表） */
    private String stateSchema = "agentscope";

    /** PG 状态存储表名 */
    private String stateTable = "agentscope_sessions";

    /** 是否启用启动恢复（进程重启后清理残留 RUNNING 会话） */
    private boolean startupRecoveryEnabled = true;

    /** 工具执行异步超时 */
    private Duration toolTimeout = Duration.ofMinutes(2);

    public String getModelId() {
        return modelId;
    }

    public void setModelId(String modelId) {
        this.modelId = modelId;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public int getMaxIters() {
        return maxIters;
    }

    public void setMaxIters(int maxIters) {
        this.maxIters = maxIters;
    }

    public String getSandboxImage() {
        return sandboxImage;
    }

    public void setSandboxImage(String sandboxImage) {
        this.sandboxImage = sandboxImage;
    }

    public Long getSandboxMemoryBytes() {
        return sandboxMemoryBytes;
    }

    public void setSandboxMemoryBytes(Long sandboxMemoryBytes) {
        this.sandboxMemoryBytes = sandboxMemoryBytes;
    }

    public Long getSandboxCpuCount() {
        return sandboxCpuCount;
    }

    public void setSandboxCpuCount(Long sandboxCpuCount) {
        this.sandboxCpuCount = sandboxCpuCount;
    }

    public String getWorkspaceRoot() {
        return workspaceRoot;
    }

    public void setWorkspaceRoot(String workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
    }

    public Duration getSseTimeout() {
        return sseTimeout;
    }

    public void setSseTimeout(Duration sseTimeout) {
        this.sseTimeout = sseTimeout;
    }

    public String getStateSchema() {
        return stateSchema;
    }

    public void setStateSchema(String stateSchema) {
        this.stateSchema = stateSchema;
    }

    public String getStateTable() {
        return stateTable;
    }

    public void setStateTable(String stateTable) {
        this.stateTable = stateTable;
    }

    public boolean isStartupRecoveryEnabled() {
        return startupRecoveryEnabled;
    }

    public void setStartupRecoveryEnabled(boolean startupRecoveryEnabled) {
        this.startupRecoveryEnabled = startupRecoveryEnabled;
    }

    public Duration getToolTimeout() {
        return toolTimeout;
    }

    public void setToolTimeout(Duration toolTimeout) {
        this.toolTimeout = toolTimeout;
    }
}