package com.linkroa.deepdataagent.runtime.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link McpToolRuntimeName} 值对象单测（D16）：运行时实名派生、前缀判别与服务器名反解。
 * <p>该约定是「{@code mcp_toolset.configs[].name} 按原始工具名落表永不命中」的唯一防线，
 * 同时承担执行链事件投影（{@code agent.mcp_tool_use}）的分类依据，故三个入口均须逼近边界。</p>
 */
class McpToolRuntimeNameTest {

    @Test
    void should_buildPrefixedRuntimeName_when_of_given_serverAndToolName() {
        // given（契约示例：配置原始工具名 get_weather + 服务器 weather）
        String serverName = "weather";
        String serverToolName = "get_weather";

        // when
        String runtimeName = McpToolRuntimeName.of(serverName, serverToolName);

        // then
        assertEquals("mcp__weather__get_weather", runtimeName);
    }

    @Test
    void should_throwIllegalArgument_when_of_given_blankServerOrToolName() {
        // given/when/then：两参数均须非空（空白视为缺失）
        assertThrows(IllegalArgumentException.class, () -> McpToolRuntimeName.of(" ", "get_weather"));
        assertThrows(IllegalArgumentException.class, () -> McpToolRuntimeName.of("weather", ""));
        assertThrows(IllegalArgumentException.class, () -> McpToolRuntimeName.of(null, "get_weather"));
    }

    @Test
    void should_detectMcpTool_when_isMcpTool_given_prefixedName() {
        // given（MCP 实名与内置 / 平台辅助工具实名对照）
        String mcpName = "mcp__weather__get_weather";

        // when/then
        assertTrue(McpToolRuntimeName.isMcpTool(mcpName));
        assertFalse(McpToolRuntimeName.isMcpTool("web_fetch"));
        assertFalse(McpToolRuntimeName.isMcpTool("todo_write"));
        assertFalse(McpToolRuntimeName.isMcpTool(null));
    }

    @Test
    void should_extractServerName_when_serverNameOf_given_mcpRuntimeName() {
        // given
        String runtimeName = McpToolRuntimeName.of("weather", "get_weather");

        // when
        String serverName = McpToolRuntimeName.serverNameOf(runtimeName);

        // then
        assertEquals("weather", serverName);
    }

    @Test
    void should_returnNull_when_serverNameOf_given_nonMcpOrMalformedName() {
        // given（内置工具实名 / 缺分段的 MCP 前缀形态 / 空值）
        // when/then：不可解析一律 null，调用方按非 MCP 语义降级
        assertNull(McpToolRuntimeName.serverNameOf("web_fetch"));
        assertNull(McpToolRuntimeName.serverNameOf("mcp__nodelimiter"));
        assertNull(McpToolRuntimeName.serverNameOf("mcp____tool"));
        assertNull(McpToolRuntimeName.serverNameOf(null));
    }
}