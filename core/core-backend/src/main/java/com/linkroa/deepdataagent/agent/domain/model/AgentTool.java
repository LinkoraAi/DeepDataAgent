package com.linkroa.deepdataagent.agent.domain.model;

import org.apache.commons.lang3.StringUtils;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Agent 工具配置值对象（{@code agent_version.tools_json} 数组条目，Agent tool 契约结构）。
 * <p>按 {@code type} 区分四类工具配置：</p>
 * <ul>
 *   <li>{@code agent_toolset_20260401} —— 内置工具集：白名单 {@code enabled_tools}、
 *       隐藏/拒绝名单 {@code disallowed_tools}、逐工具配置 {@code configs[]}；</li>
 *   <li>{@code browser_toolset_20260714} —— 浏览器工具集（仅 type 字段）；</li>
 *   <li>{@code mcp_toolset} —— 引用 {@code mcp_servers[].name} 的 MCP 工具集 + {@code configs[]}；</li>
 *   <li>{@code custom} —— 自定义工具：name/description/input_schema（JSON Schema，type 须为 object），
 *       不支持 {@code configs}/{@code permission_policy}。</li>
 * </ul>
 *
 * @param type            工具类型（四选一）
 * @param enabledTools    内置工具白名单（agent_toolset）
 * @param disallowedTools   内置工具隐藏/拒绝名单（agent_toolset）
 * @param configs         逐工具配置项（agent_toolset / mcp_toolset）
 * @param mcpServerName   引用的 MCP 服务器名（mcp_toolset 必填）
 * @param name            自定义工具名（custom 必填）
 * @param description     自定义工具描述（custom 必填）
 * @param inputSchema     自定义工具输入 JSON Schema（custom 必填，type 须为 object）
 */
public record AgentTool(String type, List<String> enabledTools, List<String> disallowedTools,
                        List<ToolConfig> configs, String mcpServerName,
                        String name, String description, Map<String, Object> inputSchema) {

    /** 内置工具集类型（含白名单/黑名单/逐工具配置）。 */
    public static final String TYPE_AGENT_TOOLSET = "agent_toolset_20260401";
    /** 浏览器工具集类型。 */
    public static final String TYPE_BROWSER_TOOLSET = "browser_toolset_20260714";
    /** MCP 工具集类型（引用已声明的 MCP 服务器）。 */
    public static final String TYPE_MCP_TOOLSET = "mcp_toolset";
    /** 自定义工具类型。 */
    public static final String TYPE_CUSTOM = "custom";
    /** 单版本工具数量上限（tools ≤128）。 */
    public static final int MAX_TOOLS = 128;
    /** MCP 桥接工具名称保留前缀（custom 工具不得占用）。 */
    public static final String MCP_TOOL_PREFIX = "mcp__";
    /** 内置工具基座全集（白/黑名单与 custom 工具名均不得越出该集合）。 */
    public static final Set<String> BUILTIN_TOOL_NAMES = Set.of(
            "Bash", "DeliverArtifacts", "Edit", "Glob", "Grep", "ImageGen",
            "ImageSearch", "Read", "WebFetch", "WebSearch", "Write");
    /** 保留工具名（custom 工具不得占用，忽略大小写）。 */
    public static final String RESERVED_TOOL_NAME_ADVISOR = "advisor";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 紧凑构造器：按类型校验工具配置不变量（互斥名单、名单越界、custom 必填项与 schema 形态）。
     * <p>浏览器工具集（任何 {@code browser_toolset_*}）本期不实现，构造即拒绝（400 invalid_request_error）。</p>
     *
     * @throws IllegalArgumentException 类型非法 / 浏览器工具集 / 名单越界 / enabled 与 disallowed 交集非空 /
     *                                  custom 约束不满足
     */
    public AgentTool {
        enabledTools = enabledTools == null ? List.of() : List.copyOf(enabledTools);
        disallowedTools = disallowedTools == null ? List.of() : List.copyOf(disallowedTools);
        configs = configs == null ? List.of() : List.copyOf(configs);
        inputSchema = inputSchema == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(inputSchema));
        if (TYPE_BROWSER_TOOLSET.equals(type) || (type != null && type.startsWith("browser_toolset"))) {
            throw new IllegalArgumentException("浏览器工具集本期不实现");
        }
        if (!TYPE_AGENT_TOOLSET.equals(type) && !TYPE_MCP_TOOLSET.equals(type) && !TYPE_CUSTOM.equals(type)) {
            throw new IllegalArgumentException("工具类型非法，须为 agent_toolset/mcp_toolset/custom");
        }
        if (TYPE_AGENT_TOOLSET.equals(type)) {
            validateBuiltinNames(enabledTools, "enabled_tools");
            validateBuiltinNames(disallowedTools, "disallowed_tools");
        }
        Set<String> overlap = new HashSet<>(enabledTools);
        overlap.retainAll(new HashSet<>(disallowedTools));
        if (!overlap.isEmpty()) {
            throw new IllegalArgumentException("同一工具不得同时出现在 enabled_tools 与 disallowed_tools");
        }
        if (TYPE_MCP_TOOLSET.equals(type) && StringUtils.isBlank(mcpServerName)) {
            throw new IllegalArgumentException("mcp_toolset 必须引用 mcp_server_name");
        }
        if (TYPE_CUSTOM.equals(type)) {
            if (StringUtils.isBlank(name)) {
                throw new IllegalArgumentException("custom 工具 name 不能为空");
            }
            if (BUILTIN_TOOL_NAMES.contains(name)
                    || RESERVED_TOOL_NAME_ADVISOR.equalsIgnoreCase(name)
                    || name.startsWith(MCP_TOOL_PREFIX)) {
                throw new IllegalArgumentException("custom 工具不得与内置工具重名、不得占用保留名 advisor 或 mcp__ 前缀");
            }
            if (StringUtils.isBlank(description)) {
                throw new IllegalArgumentException("custom 工具 description 不能为空");
            }
            if (!"object".equals(inputSchema.get("type"))) {
                throw new IllegalArgumentException("custom 工具 input_schema.type 必须为 object");
            }
            if (!configs.isEmpty()) {
                throw new IllegalArgumentException("custom 工具不支持 configs/permission_policy");
            }
        }
    }

    /** 校验工具名单仅引用内置工具名（越界 → 400，不静默忽略）。 */
    private static void validateBuiltinNames(List<String> names, String field) {
        for (String name : names) {
            if (!BUILTIN_TOOL_NAMES.contains(name)) {
                throw new IllegalArgumentException(field + " 引用了内置工具集之外的工具名: " + name);
            }
        }
    }

    /**
     * 工具逐条配置项。
     *
     * @param name             工具名
     * @param enabled          是否启用（false = 隐藏并拒绝调用；可空 = 默认启用）
     * @param permissionPolicy 权限策略（always_allow / always_ask / always_deny，可空 = 平台默认）
     */
    public record ToolConfig(String name, Boolean enabled, String permissionPolicy) {

        /** 恒允许策略。 */
        public static final String POLICY_ALWAYS_ALLOW = "always_allow";
        /** 每次询问策略（触发 HITL tool_confirmation）。 */
        public static final String POLICY_ALWAYS_ASK = "always_ask";
        /** 恒拒绝策略。 */
        public static final String POLICY_ALWAYS_DENY = "always_deny";

        /**
         * 紧凑构造器：工具名非空、权限策略词汇合法。
         */
        public ToolConfig {
            if (StringUtils.isBlank(name)) {
                throw new IllegalArgumentException("工具配置项 name 不能为空");
            }
            if (permissionPolicy != null && !POLICY_ALWAYS_ALLOW.equals(permissionPolicy)
                    && !POLICY_ALWAYS_ASK.equals(permissionPolicy) && !POLICY_ALWAYS_DENY.equals(permissionPolicy)) {
                throw new IllegalArgumentException("工具 permission_policy 非法，须为 always_allow/always_ask/always_deny");
            }
        }
    }

    /**
     * 解析工具配方 JSON 数组（{@code [{type, enabled_tools?, ..., name?, ...}]}，空白配方返回空列表）。
     *
     * @param toolsJson 工具配方 JSON（可空）
     * @return 工具配置列表（保持声明顺序）
     * @throws IllegalStateException JSON 结构非法（无法解析为对象数组）
     */
    public static List<AgentTool> parse(String toolsJson) {
        if (StringUtils.isBlank(toolsJson)) {
            return List.of();
        }
        List<Map<String, Object>> items;
        try {
            items = OBJECT_MAPPER.readValue(toolsJson, new TypeReference<>() {
            });
        } catch (RuntimeException e) {
            throw new IllegalStateException("工具配置JSON解析失败", e);
        }
        List<AgentTool> tools = new ArrayList<>();
        for (Map<String, Object> item : items) {
            if (item == null) {
                throw new IllegalStateException("工具配置格式非法：存在空对象");
            }
            tools.add(new AgentTool(
                    stringOf(item, "type"),
                    stringListOf(item.get("enabled_tools")),
                    stringListOf(item.get("disallowed_tools")),
                    configListOf(item.get("configs")),
                    stringOf(item, "mcp_server_name"),
                    stringOf(item, "name"),
                    stringOf(item, "description"),
                    mapOf(item.get("input_schema"))));
        }
        return tools;
    }

    /**
     * 序列化工具配置列表为 JSON 数组（空列表返回 {@code null}；仅落与类型相关的非空键）。
     *
     * @param tools 工具配置列表
     * @return 配方 JSON 字符串；列表为空返回 {@code null}
     */
    public static String toJson(List<AgentTool> tools) {
        if (tools == null || tools.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (AgentTool tool : tools) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", tool.type());
            putIfNotEmpty(item, "enabled_tools", tool.enabledTools());
            putIfNotEmpty(item, "disallowed_tools", tool.disallowedTools());
            if (!tool.configs().isEmpty()) {
                item.put("configs", tool.configs().stream().map(AgentTool::configToMap).toList());
            }
            putIfPresent(item, "mcp_server_name", tool.mcpServerName());
            putIfPresent(item, "name", tool.name());
            putIfPresent(item, "description", tool.description());
            putIfNotEmpty(item, "input_schema", tool.inputSchema());
            items.add(item);
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(items);
        } catch (RuntimeException e) {
            throw new IllegalStateException("工具配置序列化失败", e);
        }
    }

    /**
     * 校验工具数量不超过上限。
     *
     * @param tools 工具配置列表
     * @throws IllegalArgumentException 数量超过 {@link #MAX_TOOLS}
     */
    public static void validateCount(List<AgentTool> tools) {
        if (tools != null && tools.size() > MAX_TOOLS) {
            throw new IllegalArgumentException("工具数量不能超过" + MAX_TOOLS + "个");
        }
    }

    private static Map<String, Object> configToMap(ToolConfig config) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", config.name());
        if (config.enabled() != null) {
            map.put("enabled", config.enabled());
        }
        if (config.permissionPolicy() != null) {
            map.put("permission_policy", config.permissionPolicy());
        }
        return map;
    }

    private static String stringOf(Map<String, Object> item, String key) {
        Object value = item.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static List<String> stringListOf(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list)) {
            throw new IllegalStateException("工具配置字段格式非法：期望数组");
        }
        return list.stream().map(String::valueOf).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object raw) {
        if (raw == null) {
            return Map.of();
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalStateException("工具配置字段格式非法：期望对象");
        }
        return (Map<String, Object>) map;
    }

    private static List<ToolConfig> configListOf(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list)) {
            throw new IllegalStateException("工具配置字段格式非法：期望数组");
        }
        List<ToolConfig> configs = new ArrayList<>();
        for (Object element : list) {
            Map<String, Object> item = mapOf(element);
            Object enabled = item.get("enabled");
            if (enabled != null && !(enabled instanceof Boolean)) {
                throw new IllegalStateException("工具配置项 enabled 须为布尔值");
            }
            configs.add(new ToolConfig(
                    stringOf(item, "name"),
                    (Boolean) enabled,
                    stringOf(item, "permission_policy")));
        }
        return configs;
    }

    private static void putIfPresent(Map<String, Object> target, String key, String value) {
        if (StringUtils.isNotEmpty(value)) {
            target.put(key, value);
        }
    }

    private static void putIfNotEmpty(Map<String, Object> target, String key, Object value) {
        if (value instanceof List<?> list && !list.isEmpty()) {
            target.put(key, list);
        } else if (value instanceof Map<?, ?> map && !map.isEmpty()) {
            target.put(key, map);
        }
    }
}
