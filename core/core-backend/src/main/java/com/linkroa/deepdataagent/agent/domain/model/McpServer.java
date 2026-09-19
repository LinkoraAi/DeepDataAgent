package com.linkroa.deepdataagent.agent.domain.model;

import org.apache.commons.lang3.StringUtils;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 服务器声明值对象（{@code agent_version.mcp_servers_json} 数组条目，契约 {@code mcp_servers[]}）。
 * <p>形如 {@code {name, type: "url", url}}：{@code name} 供 {@code mcp_toolset} 引用，
 * {@code type} 本期仅支持 {@code url}。</p>
 *
 * @param name 服务器名（版本内唯一，供工具集引用）
 * @param type 服务器类型（本期恒为 url）
 * @param url  MCP 服务器地址
 */
public record McpServer(String name, String type, String url) {

    /** 唯一支持的 MCP 服务器类型词汇。 */
    public static final String TYPE_URL = "url";
    /** 单版本 MCP 服务器数量上限（mcp_servers ≤20）。 */
    public static final int MAX_SERVERS = 20;
    /** 运行时工具实名 {@code mcp__{server}__{tool}} 的分隔符；服务器名含它会使实名反解析歧义。 */
    public static final String NAME_SEPARATOR = "__";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 紧凑构造器：name/url 非空、type 词汇合法、name 不含实名分隔符。
     *
     * @throws IllegalArgumentException name 或 url 为空 / type 非 url / name 含 {@value #NAME_SEPARATOR}
     */
    public McpServer {
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

    /**
     * 解析 MCP 服务器配方 JSON 数组（空白配方返回空列表）。
     *
     * @param mcpServersJson 配方 JSON（可空）
     * @return 服务器声明列表（保持声明顺序）
     * @throws IllegalStateException JSON 结构非法
     */
    public static List<McpServer> parse(String mcpServersJson) {
        if (StringUtils.isBlank(mcpServersJson)) {
            return List.of();
        }
        List<Map<String, Object>> items;
        try {
            items = OBJECT_MAPPER.readValue(mcpServersJson, new TypeReference<>() {
            });
        } catch (RuntimeException e) {
            throw new IllegalStateException("MCP服务器配置JSON解析失败", e);
        }
        List<McpServer> servers = new ArrayList<>();
        for (Map<String, Object> item : items) {
            if (item == null) {
                throw new IllegalStateException("MCP服务器配置格式非法：存在空对象");
            }
            servers.add(new McpServer(
                    stringOf(item, "name"),
                    stringOf(item, "type"),
                    stringOf(item, "url")));
        }
        return servers;
    }

    /**
     * 序列化服务器声明列表为 JSON 数组（空列表返回 {@code null}）。
     *
     * @param servers 服务器声明列表
     * @return 配方 JSON 字符串；列表为空返回 {@code null}
     */
    public static String toJson(List<McpServer> servers) {
        if (servers == null || servers.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (McpServer server : servers) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", server.name());
            item.put("type", server.type());
            item.put("url", server.url());
            items.add(item);
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(items);
        } catch (RuntimeException e) {
            throw new IllegalStateException("MCP服务器配置序列化失败", e);
        }
    }

    /**
     * 校验服务器数量不超过上限。
     *
     * @param servers 服务器声明列表
     * @throws IllegalArgumentException 数量超过 {@link #MAX_SERVERS}
     */
    public static void validateCount(List<McpServer> servers) {
        if (servers != null && servers.size() > MAX_SERVERS) {
            throw new IllegalArgumentException("MCP 服务器数量不能超过" + MAX_SERVERS + "个");
        }
    }

    private static String stringOf(Map<String, Object> item, String key) {
        Object value = item.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
