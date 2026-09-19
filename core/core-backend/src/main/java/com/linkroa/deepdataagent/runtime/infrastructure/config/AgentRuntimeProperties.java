package com.linkroa.deepdataagent.runtime.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Agent 运行时配置（{@code app.agent}）。
 * <p>集中管理 AgentScope Harness 装配所需的 Docker 沙箱 / SSE / PG 状态存储等参数，
 * 支持通过同名环境变量（{@code APP_AGENT_*}）覆盖，与 {@code application.yaml} 的 {@code app.agent} 段对齐。
 * 模型 / 提示词 / 迭代上限已下沉至 Agent 台账（{@code agent_version + model_profile}），
 * 经运行时装配链路（{@code AgentVersionAssemblyPort}）下发，不再在此保留全局回退。</p>
 */
@Configuration
@ConfigurationProperties(prefix = "app.agent")
public class AgentRuntimeProperties {

    /** Docker 沙箱镜像 */
    private String sandboxImage = "ubuntu:22.04";

    /** 沙箱内存上限（字节），空表示不限制 */
    private Long sandboxMemoryBytes = 4L * 1024 * 1024 * 1024;

    /** 沙箱 CPU 核数，空表示不限制 */
    private Long sandboxCpuCount = 2L;

    /** SSE 连接空闲超时（Spring WebMvc 7 超时固定，配合心跳探测死连接） */
    private Duration sseTimeout = Duration.ofMinutes(30);

    /**
     * MCP 单次请求（含工具调用）超时：装配期 discovery 为同步阻塞过程，
     * 未显式下发会把「无响应服务器」从失败降级变成长期等待。
     */
    private Duration mcpRequestTimeout = Duration.ofSeconds(30);

    /** MCP 客户端初始化（握手 {@code initialize} + 工具枚举 {@code tools/list}）超时。 */
    private Duration mcpInitializationTimeout = Duration.ofSeconds(15);

    /** PG 状态存储 schema（AgentScope 扩展自动建 schema/表） */
    private String stateSchema = "agentscope";

    /** PG 状态存储表名 */
    private String stateTable = "agentscope_sessions";

    /** 是否启用启动恢复（进程重启后清理残留 RUNNING 会话） */
    private boolean startupRecoveryEnabled = true;

    /**
     * AgentScope 工作区根目录（宿主侧）：每个 Agent 在其下拥有 {@code <agentId>} 子目录，
     * 装配时物化 AGENTS.md，供 Harness 的 WorkspaceContextMiddleware 注入系统提示，
     * 并经 sandbox workspace projection 投影进沙箱工作区根。
     */
    private String workspaceRoot = "./data/agentscope-workspaces";

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

    public Duration getSseTimeout() {
        return sseTimeout;
    }

    public void setSseTimeout(Duration sseTimeout) {
        this.sseTimeout = sseTimeout;
    }

    public Duration getMcpRequestTimeout() {
        return mcpRequestTimeout;
    }

    public void setMcpRequestTimeout(Duration mcpRequestTimeout) {
        this.mcpRequestTimeout = mcpRequestTimeout;
    }

    public Duration getMcpInitializationTimeout() {
        return mcpInitializationTimeout;
    }

    public void setMcpInitializationTimeout(Duration mcpInitializationTimeout) {
        this.mcpInitializationTimeout = mcpInitializationTimeout;
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

    public String getWorkspaceRoot() {
        return workspaceRoot;
    }

    public void setWorkspaceRoot(String workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
    }
}