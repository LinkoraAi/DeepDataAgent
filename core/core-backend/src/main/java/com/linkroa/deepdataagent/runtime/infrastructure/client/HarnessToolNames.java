package com.linkroa.deepdataagent.runtime.infrastructure.client;

import com.linkroa.deepdataagent.runtime.domain.model.McpToolRuntimeName;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 公开契约工具名 → AgentScope Harness 运行时注册实名的映射表（单一事实源）。
 * <p>公开契约（Agent tool）以 {@code Bash / Read / Write / Edit / Glob / Grep /
 * DeliverArtifacts / WebFetch / WebSearch} 等契约名声明内置工具；Harness 2.0.3
 * 实际注册名为 {@code execute / read_file / write_file / edit_file / glob_files /
 * grep_files / deliver_artifact / web_fetch / web_search}（{@code ShellExecuteTool.NAME}
 * 常量、{@code FilesystemTool} 六名、{@code ArtifactDeliveryTool} 与 {@code WebTools}
 * 两名经 2.0.3 正式版 sources 实锤）。工具可见性过滤（{@code ToolsConfig} allow/deny）与
 * 权限规则（{@code PermissionRule}）都必须以运行时实名下发，契约名直传将永不命中。</p>
 * <p>2.0.1→2.0.3 映射变化（升级已处理）：{@code DeliverArtifacts} 由平台自研同名工具
 * 切换为框架内置 {@code deliver_artifact}；{@code WebFetch / WebSearch} 在 2.0.1 无注册实体，
 * 2.0.3 起框架 {@code build()} 默认注册 {@code web_fetch / web_search}（后者需配置
 * TAVILY_API_KEY，否则返回可读配置错误），公开契约白名单本就收录二者，映射补齐后
 * allow/deny 与权限策略恢复按名治理。{@code ImageGen / ImageSearch} 至 2.0.3 仍无注册实体，
 * 不收录本表：翻译返回空，调用方跳过对应过滤/规则（不报错），与 ToolFilter 对未知名
 * WARN 忽略的语义一致。<b>框架升级时须复核本表（红灯位）</b>：注册名变化会导致
 * 可见性与权限双双失效。</p>
 */
public final class HarnessToolNames {

    /** 契约名 → Harness 2.0.3 运行时实名（仅收录有注册实体的内置工具）。 */
    private static final Map<String, String> RUNTIME_NAME_BY_CONTRACT = Map.of(
            "Bash", "execute",
            "Read", "read_file",
            "Write", "write_file",
            "Edit", "edit_file",
            "Glob", "glob_files",
            "Grep", "grep_files",
            "DeliverArtifacts", "deliver_artifact",
            "WebFetch", "web_fetch",
            "WebSearch", "web_search");

    /** 平台辅助工具实名：任务清单（TodoTools 恒注册，不受契约白名单治理）。 */
    public static final String TODO_WRITE = "todo_write";

    /** 联网抓取工具实名：框架无条件注册（宿主 JVM 出网、无内网黑名单），平台默认 deny。 */
    public static final String WEB_FETCH = "web_fetch";

    /** 联网搜索工具实名：框架无条件注册（需 TAVILY_API_KEY），平台默认 deny。 */
    public static final String WEB_SEARCH = "web_search";

    /** MCP 桥接工具实名前缀（平台运行时实名约定，由装配末端改名施加，与契约 {@code custom} 工具禁用前缀同源）。 */
    public static final String MCP_TOOL_PREFIX = McpToolRuntimeName.PREFIX;

    private HarnessToolNames() {
    }

    /**
     * MCP 工具运行时实名：{@code mcp__{server_name}__{tool_name}}。
     * <p>约定唯一事实源在领域层 {@link McpToolRuntimeName}（执行链事件投影与权限规则落表共用同一依据），
     * 本方法为基础设施侧的同义入口。公开契约 {@code mcp_toolset.configs[].name} 给的是
     * <b>服务端原始工具名</b>，按原始名生成权限规则将永不命中——MUST 先经本方法翻译。</p>
     *
     * @param mcpServerName  服务器名（{@code mcp_servers[].name}，非空）
     * @param serverToolName 服务端暴露的原始工具名（非空）
     * @return 运行时实名
     */
    public static String toMcpRuntimeName(String mcpServerName, String serverToolName) {
        return McpToolRuntimeName.of(mcpServerName, serverToolName);
    }

    /**
     * 契约名翻译为运行时实名。
     *
     * @param contractName 公开契约工具名（可空）
     * @return 运行时实名；无注册实体（未收录本表）时为空 Optional
     */
    public static Optional<String> toRuntimeName(String contractName) {
        if (contractName == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(RUNTIME_NAME_BY_CONTRACT.get(contractName));
    }

    /**
     * 批量翻译：保序去重，仅保留有运行时实体的名称（无实体名静默跳过）。
     *
     * @param contractNames 契约名集合（可空/空）
     * @return 运行时实名列表（保持声明顺序）
     */
    public static List<String> toRuntimeNames(Collection<String> contractNames) {
        if (contractNames == null || contractNames.isEmpty()) {
            return List.of();
        }
        Set<String> translated = new LinkedHashSet<>();
        for (String contractName : contractNames) {
            toRuntimeName(contractName).ifPresent(translated::add);
        }
        return List.copyOf(translated);
    }
}
