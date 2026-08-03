package com.linkroa.deepdataagent.agent.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Agent 记忆配置属性
 * <p>配置 AgentScope 框架原生记忆能力，包括工作目录、整合与落盘限流等参数。</p>
 * <p>通过 {@code @EnableConfigurationProperties} 在 HarnessAgentFactory 相关配置中启用。</p>
 */
@ConfigurationProperties(prefix = "app.agent.memory")
public class AgentMemoryProperties {

    /**
     * 默认记忆工作目录。
     * <p>基于项目根目录构建绝对路径，确保无论从何处启动应用，记忆文件都统一存储在项目根目录的 data/agentscope 目录下。</p>
     */
    private static final String DEFAULT_WORKSPACE = resolveProjectBasePath() + "/data/agentscope";

    /**
     * 记忆工作目录，默认 {@code <项目根>/data/agentscope}
     */
    private String workspace = DEFAULT_WORKSPACE;

    /**
     * 是否启用框架记忆，默认 true
     */
    private boolean enabled = true;

    /**
     * 长期记忆整合的最大 token 数，默认 4000
     */
    private int consolidationMaxTokens = 4000;

    /**
     * 长期记忆整合的最小间隔，默认 1 小时
     */
    private Duration consolidationMinGap = Duration.ofHours(1);

    /**
     * 记忆落盘触发策略，默认 throttled（限流）
     */
    private String flushTrigger = "throttled";

    public String getWorkspace() {
        return workspace;
    }

    public void setWorkspace(String workspace) {
        this.workspace = StringUtils.hasText(workspace) ? workspace : DEFAULT_WORKSPACE;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getConsolidationMaxTokens() {
        return consolidationMaxTokens;
    }

    public void setConsolidationMaxTokens(int consolidationMaxTokens) {
        this.consolidationMaxTokens = consolidationMaxTokens;
    }

    public Duration getConsolidationMinGap() {
        return consolidationMinGap;
    }

    public void setConsolidationMinGap(Duration consolidationMinGap) {
        this.consolidationMinGap = consolidationMinGap;
    }

    public String getFlushTrigger() {
        return flushTrigger;
    }

    public void setFlushTrigger(String flushTrigger) {
        this.flushTrigger = flushTrigger;
    }

    /**
     * 解析项目根目录路径。
     * <p>优先通过环境变量 APP_BASE_DIR 获取，否则从当前工作目录向上查找最顶层包含 pom.xml 的目录作为项目根目录。
     * 之所以查找最顶层而非第一个，是因为 Maven 多模块项目中子模块目录也包含 pom.xml，
     * 需要跳过子模块目录，定位到真正的项目根目录。</p>
     */
    private static String resolveProjectBasePath() {
        String envPath = System.getenv("APP_BASE_DIR");
        if (envPath != null && !envPath.isBlank()) {
            return envPath;
        }

        // 从当前工作目录向上查找最顶层包含 pom.xml 的目录（即项目根目录）
        Path currentDir = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path searchDir = currentDir;
        Path topmostProjectDir = null;
        while (searchDir != null) {
            if (Files.exists(searchDir.resolve("pom.xml"))) {
                topmostProjectDir = searchDir;
            }
            searchDir = searchDir.getParent();
        }

        // 如果找到包含 pom.xml 的目录，返回最顶层那个；否则回退到当前工作目录
        return topmostProjectDir != null ? topmostProjectDir.toString() : currentDir.toString();
    }
}