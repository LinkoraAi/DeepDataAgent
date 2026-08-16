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

    /** PG 状态存储 schema（AgentScope 扩展自动建 schema/表） */
    private String stateSchema = "agentscope";

    /** PG 状态存储表名 */
    private String stateTable = "agentscope_sessions";

    /** 是否启用启动恢复（进程重启后清理残留 RUNNING 会话） */
    private boolean startupRecoveryEnabled = true;

    /** 工具执行异步超时 */
    private Duration toolTimeout = Duration.ofMinutes(2);

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