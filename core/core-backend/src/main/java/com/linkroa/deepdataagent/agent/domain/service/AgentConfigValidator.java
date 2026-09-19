package com.linkroa.deepdataagent.agent.domain.service;

import com.linkroa.deepdataagent.agent.domain.model.AgentTool;
import com.linkroa.deepdataagent.agent.domain.model.McpServer;
import com.linkroa.deepdataagent.agent.domain.model.SkillBinding;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Agent 版本配置领域校验器（跨值对象的结构不变量，零框架依赖）。
 * <p>单值对象内的不变量（custom schema type=object、enabled/disallowed 互斥、字段词汇）
 * 由各值对象紧凑构造器保证；本校验器承载<b>跨列表</b>规则：
 * skills/mcp_servers/tools 数量上限（20/20/128）、{@code mcp_servers[]} 名称唯一性与
 * {@code mcp_toolset} 引用完整性（所引用的 {@code mcp_server_name} 必须在 {@code mcp_servers[]} 中声明）。</p>
 * <p>目录级校验（模型 id 是否在目录、effort/context_window 档位是否受支持、custom 技能资产存在性）
 * 属应用层职责（依赖模型目录 / skill 资产端口），不在本校验器内。</p>
 */
public final class AgentConfigValidator {

    private AgentConfigValidator() {
    }

    /**
     * 校验版本配置面的跨列表不变量。
     *
     * @param tools   工具配置列表（可空 = 未配置）
     * @param servers MCP 服务器声明列表（可空 = 未配置）
     * @param skills  技能绑定列表（可空 = 未配置）
     * @throws IllegalArgumentException 数量超限、服务器名重复或 mcp_toolset 引用了未声明的服务器
     */
    public static void validate(List<AgentTool> tools, List<McpServer> servers, List<SkillBinding> skills) {
        AgentTool.validateCount(tools);
        McpServer.validateCount(servers);
        SkillBinding.validateCount(skills);
        Set<String> declaredNames = uniqueServerNames(servers);
        if (tools == null || tools.isEmpty()) {
            return;
        }
        for (AgentTool tool : tools) {
            if (AgentTool.TYPE_MCP_TOOLSET.equals(tool.type()) && !declaredNames.contains(tool.mcpServerName())) {
                throw new IllegalArgumentException("mcp_toolset 引用了未声明的 MCP 服务器: " + tool.mcpServerName());
            }
        }
    }

    /**
     * 校验多智能体编排配置未被提交：本期 multiagent 非空一律 400 invalid_request_error
     * （响应恒 null，仅保留词汇位），不做静默忽略。
     *
     * @param multiagentJson 多智能体编排配置 JSON（可空 / 空对象 / 空数组视为未提交）
     * @throws IllegalArgumentException 提交了非空 multiagent
     */
    public static void validateMultiagentAbsent(String multiagentJson) {
        if (multiagentJson == null) {
            return;
        }
        String trimmed = multiagentJson.trim();
        if (trimmed.isEmpty() || "null".equals(trimmed) || "{}".equals(trimmed) || "[]".equals(trimmed)) {
            return;
        }
        throw new IllegalArgumentException("multiagent 本期不实现，提交非空值须 400");
    }

    /**
     * 收集服务器名并要求版本内唯一：重名会让运行时工具实名 {@code mcp__{server}__{tool}} 产生歧义，
     * 且装配期同名连接互相覆盖（后写胜出），故在发布期即拒绝。
     *
     * @param servers 服务器声明列表（可空）
     * @return 已声明的服务器名集合
     * @throws IllegalArgumentException 存在重复的服务器名
     */
    private static Set<String> uniqueServerNames(List<McpServer> servers) {
        Set<String> declaredNames = new HashSet<>();
        if (servers == null) {
            return declaredNames;
        }
        for (McpServer server : servers) {
            if (!declaredNames.add(server.name())) {
                throw new IllegalArgumentException("MCP 服务器 name 重复: " + server.name());
            }
        }
        return declaredNames;
    }
}
