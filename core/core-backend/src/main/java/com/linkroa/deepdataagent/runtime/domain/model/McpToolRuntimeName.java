package com.linkroa.deepdataagent.runtime.domain.model;

import org.apache.commons.lang3.StringUtils;

/**
 * MCP 工具运行时实名约定（{@code mcp__{server_name}__{tool_name}}）的唯一事实源。
 * <p>服务器名与工具名共同构成实名，避免跨服务器重名冲突；公开契约
 * {@code mcp_toolset.configs[].name} 给的是<b>服务端原始工具名</b>，权限规则落表前 MUST 经
 * {@link #of} 翻译。该前缀同时被平台保留：契约要求 {@code custom} 工具 MUST NOT 使用
 * {@code mcp__} 前缀，故前缀即「MCP 执行侧」的无歧义标记。</p>
 * <p><b>该形态由平台在框架注册之后施加</b>：框架 2.0.3 的 {@code McpClientManager} 以服务端
 * 原始工具名注册 MCP 工具（与其文档声明的本命名不一致），故装配末端由
 * {@code AgentscopeHarnessAgentFactory#renameMcpTools} 按本约定统一改名——权限规则键、
 * {@code agent.mcp_tool_use} 投影与执行侧判定同以改后实名为准。框架升级须复核该改名点
 * （详见 openspec 变更 align-qoder-vault-credential-capabilities D16）。</p>
 * <p>本类为领域内纯约定载体（无状态、无外部依赖），基础设施层的工具名映射表与执行链的
 * 事件投影（{@code agent.mcp_tool_use}）均以此为单一依据。</p>
 */
public final class McpToolRuntimeName {

    /** MCP 桥接工具实名前缀（平台运行时实名约定，与契约 {@code custom} 工具禁用前缀同源）。 */
    public static final String PREFIX = "mcp__";

    /** 实名的服务器名 / 工具名分段分隔符。 */
    private static final String SEPARATOR = "__";

    private McpToolRuntimeName() {
    }

    /**
     * 服务端原始工具名 → 运行时实名：{@code mcp__{server_name}__{tool_name}}。
     *
     * @param serverName     服务器名（{@code mcp_servers[].name}，非空）
     * @param serverToolName 服务端暴露的原始工具名（{@code mcp_toolset.configs[].name}，非空）
     * @return 运行时实名
     * @throws IllegalArgumentException 任一参数为空
     */
    public static String of(String serverName, String serverToolName) {
        if (StringUtils.isBlank(serverName)) {
            throw new IllegalArgumentException("MCP 服务器名不能为空");
        }
        if (StringUtils.isBlank(serverToolName)) {
            throw new IllegalArgumentException("MCP 工具名不能为空");
        }
        return PREFIX + serverName + SEPARATOR + serverToolName;
    }

    /**
     * 是否 MCP 工具运行时实名（前缀判定，覆盖未配置任何权限策略的 MCP 工具）。
     *
     * @param runtimeName 运行时工具实名（可空）
     * @return true=MCP 工具（执行侧在平台 JVM 内）
     */
    public static boolean isMcpTool(String runtimeName) {
        return runtimeName != null && runtimeName.startsWith(PREFIX);
    }

    /**
     * 从运行时实名反解服务器名（{@code mcp__{server}__{tool}} → {@code server}）。
     * <p>非法形态（非 MCP 前缀、缺分段）返回 null，调用方按未知服务器降级处理。</p>
     *
     * @param runtimeName 运行时工具实名（可空）
     * @return 服务器名；不可解析时为 null
     */
    public static String serverNameOf(String runtimeName) {
        if (!isMcpTool(runtimeName)) {
            return null;
        }
        String rest = runtimeName.substring(PREFIX.length());
        int separator = rest.indexOf(SEPARATOR);
        return separator <= 0 ? null : rest.substring(0, separator);
    }
}