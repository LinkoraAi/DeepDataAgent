package com.linkroa.deepdataagent.agent.application.dto;

import org.apache.commons.lang3.StringUtils;

/**
 * MCP 服务器装配契约（应用层物化 DTO，仅进程内流转，永不发布）。
 * <p>由 agent BC 在装配边界从版本快照 {@code agent_version.mcp_servers_json} 经既有领域解析器
 * {@code McpServer.parse} 解析后随 {@link ResolvedAgentAssemblyDTO} 一并出版，供下游 runtime BC
 * 防腐层（ACL）映射为自身 {@code AgentAssemblySpec.McpConnection} 值对象（仅取 {@code name} + {@code url}）。</p>
 * <p>形态与公开契约 {@code mcp_servers[]} 一致：{@code name} 供 {@code mcp_toolset} 引用，
 * {@code type} 本期恒为 {@code url}。本 DTO <b>不承载任何凭据材料</b>——鉴权按 URL 在运行时另行匹配，
 * 故可安全随装配缓存驻留。</p>
 *
 * @param name 服务器名（版本内唯一，供工具集引用）
 * @param type 服务器类型（本期恒为 url）
 * @param url  MCP 服务器地址（凭据匹配的目标键，运行时按规范化后的完整 URL 全等匹配）
 */
public record AgentMcpServerDTO(String name, String type, String url) {

    /** 唯一支持的 MCP 服务器类型词汇（对齐领域 {@code McpServer.TYPE_URL}）。 */
    public static final String TYPE_URL = "url";
    /** 运行时工具实名 {@code mcp__{server}__{tool}} 的分隔符（对齐领域 {@code McpServer.NAME_SEPARATOR}）。 */
    public static final String NAME_SEPARATOR = "__";

    /**
     * 紧凑构造器：契约边界校验（name / url 非空，type 仅 url，name 不含实名分隔符）。
     */
    public AgentMcpServerDTO {
        if (StringUtils.isBlank(name)) {
            throw new IllegalArgumentException("MCP 服务器 name 不能为空");
        }
        if (name.contains(NAME_SEPARATOR)) {
            throw new IllegalArgumentException(
                    "MCP 服务器 name 不能包含 \"" + NAME_SEPARATOR + "\"（与运行时工具实名分隔符冲突）");
        }
        if (!TYPE_URL.equals(type)) {
            throw new IllegalArgumentException("MCP 服务器类型非法，本期仅支持 url");
        }
        if (StringUtils.isBlank(url)) {
            throw new IllegalArgumentException("MCP 服务器 url 不能为空");
        }
    }
}