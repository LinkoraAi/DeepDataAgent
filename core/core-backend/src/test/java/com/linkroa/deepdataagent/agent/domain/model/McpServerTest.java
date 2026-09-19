package com.linkroa.deepdataagent.agent.domain.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link McpServer} 单元测试：解析、词汇校验、数量上限与序列化。
 */
class McpServerTest {

    @Test
    void should_parseServers_when_parse_given_validJson() {
        // given
        String json = "[{\"name\":\"github\",\"type\":\"url\",\"url\":\"https://mcp.example.com/github\"}]";

        // when
        List<McpServer> servers = McpServer.parse(json);

        // then
        assertEquals(1, servers.size());
        assertEquals("github", servers.get(0).name());
        assertEquals("https://mcp.example.com/github", servers.get(0).url());
    }

    @Test
    void should_returnEmpty_when_parse_given_blankJson() {
        // given & when & then
        assertEquals(List.of(), McpServer.parse(null));
        assertEquals(List.of(), McpServer.parse(" "));
    }

    @Test
    void should_throw_when_constructor_given_illegalType() {
        // given & when & then
        assertThrows(IllegalArgumentException.class,
                () -> new McpServer("github", "sse", "https://mcp.example.com"));
    }

    @Test
    void should_throw_when_constructor_given_blankUrl() {
        // given & when & then
        assertThrows(IllegalArgumentException.class,
                () -> new McpServer("github", McpServer.TYPE_URL, " "));
    }

    @Test
    void should_throw_when_constructor_given_nameContainingSeparator() {
        // given（name 含 "__" 会使运行时实名 mcp__{server}__{tool} 反解析歧义）
        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> new McpServer("my__server", McpServer.TYPE_URL, "https://mcp.example.com"));
        assertThrows(IllegalArgumentException.class,
                () -> McpServer.parse("[{\"name\":\"a__b\",\"type\":\"url\",\"url\":\"https://mcp.example.com\"}]"));
    }

    @Test
    void should_throw_when_validateCount_given_moreThanTwenty() {
        // given
        List<McpServer> servers = IntStream.rangeClosed(1, McpServer.MAX_SERVERS + 1)
                .mapToObj(i -> new McpServer("s" + i, McpServer.TYPE_URL, "https://mcp.example.com/" + i))
                .toList();

        // when & then
        assertThrows(IllegalArgumentException.class, () -> McpServer.validateCount(servers));
    }

    @Test
    void should_roundTrip_when_toJson_given_parsedServers() {
        // given
        String json = "[{\"name\":\"github\",\"type\":\"url\",\"url\":\"https://mcp.example.com/github\"}]";

        // when
        String serialized = McpServer.toJson(McpServer.parse(json));

        // then
        assertTrue(serialized.contains("\"name\":\"github\""));
        assertEquals(McpServer.parse(json), McpServer.parse(serialized));
        assertNull(McpServer.toJson(List.of()));
    }
}
