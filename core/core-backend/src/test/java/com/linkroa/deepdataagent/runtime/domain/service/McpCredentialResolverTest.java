package com.linkroa.deepdataagent.runtime.domain.service;

import com.linkroa.deepdataagent.runtime.domain.model.AgentAssemblySpec.McpConnection;
import com.linkroa.deepdataagent.runtime.domain.model.AgentAssemblySpec.VaultCredentialRef;
import com.linkroa.deepdataagent.runtime.domain.model.McpConnectionCredential;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link McpCredentialResolver} 单测：Target 精确匹配（仅按完整 URL 全等、去尾斜杠规范化、
 * 服务器名称不参与匹配、禁止前缀匹配）、鉴权类型注入口径（static_bearer / mcp_oauth 注入，
 * environment_variable 不注入）、多命中冲突与脱敏面。
 */
class McpCredentialResolverTest {

    private static final String TOKEN = "ghp_super-secret-token-123";

    private final McpCredentialResolver resolver = new McpCredentialResolver();

    private static McpConnection connection(String name, String url) {
        return new McpConnection(name, url);
    }

    private static VaultCredentialRef credential(String authType, String target, String token) {
        return new VaultCredentialRef("vault_1", "cr_1", authType, target, token);
    }

    // ==================== 命中口径 ====================

    @Test
    void should_notInject_when_resolve_given_targetEqualsConnectionNameOnly() {
        // given（凭证 Target 与连接名全等，但与该连接 URL 不等：服务器名称不参与匹配）
        List<McpConnection> connections = List.of(connection("github", "https://api.githubcopilot.com/mcp/"));
        List<VaultCredentialRef> credentials = List.of(
                credential("static_bearer", "github", TOKEN));

        // when
        List<McpConnectionCredential> resolved = resolver.resolve(connections, credentials);

        // then（不命中 = 无鉴权头，连接其余字段原样产出）
        assertEquals(1, resolved.size());
        McpConnectionCredential credential = resolved.get(0);
        assertEquals("github", credential.connectionName());
        assertEquals("https://api.githubcopilot.com/mcp/", credential.url());
        assertTrue(credential.headers().isEmpty());
    }

    @Test
    void should_notInject_when_resolve_given_sameNameButDifferentUrl() {
        // given（凭证与连接同名，但绑定的是另一个 MCP 服务器 URL：不得误命中）
        List<McpConnection> connections = List.of(
                connection("weather", "https://mcp.example.com/v1/mcp"));
        List<VaultCredentialRef> credentials = List.of(
                credential("static_bearer", "weather", TOKEN),
                credential("static_bearer", "https://other.example.com/v1/mcp", "tok-other"));

        // when
        List<McpConnectionCredential> resolved = resolver.resolve(connections, credentials);

        // then（URL 全等是唯一命中口径，同名的 `weather` 凭证不得注入）
        assertTrue(resolved.get(0).headers().isEmpty());
    }

    @Test
    void should_injectBearerHeader_when_resolve_given_targetMatchesFullUrl() {
        // given（凭证 Target 为完整 URL，与连接 URL 仅尾部斜杠差异——规范化后全等）
        List<McpConnection> connections = List.of(
                connection("db-mcp", "https://mcp.example.com/v1/mcp"));
        List<VaultCredentialRef> credentials = List.of(
                credential("static_bearer", "https://mcp.example.com/v1/mcp/", TOKEN));

        // when
        List<McpConnectionCredential> resolved = resolver.resolve(connections, credentials);

        // then
        assertEquals("Bearer " + TOKEN, resolved.get(0).headers().get("Authorization"));
    }

    @Test
    void should_injectBearerHeader_when_resolve_given_mcpOAuthType() {
        // given（mcp_oauth 型同样具备自动注入语义，注入其 access token 明文）
        List<McpConnection> connections = List.of(connection("jira", "https://mcp.atlassian.com/v1/sse"));
        List<VaultCredentialRef> credentials = List.of(
                credential("mcp_oauth", "https://mcp.atlassian.com/v1/sse", "oauth-access-token-xyz"));

        // when
        List<McpConnectionCredential> resolved = resolver.resolve(connections, credentials);

        // then
        assertEquals("Bearer oauth-access-token-xyz", resolved.get(0).headers().get("Authorization"));
    }

    @Test
    void should_normalizeTrailingSlashes_when_resolve_given_multiTrailingSlashUrl() {
        // given（连接 URL 多个尾斜杠 vs 凭证 Target 无尾斜杠）
        List<McpConnection> connections = List.of(
                connection("svc", "https://mcp.example.com/api///"));
        List<VaultCredentialRef> credentials = List.of(
                credential("static_bearer", "https://mcp.example.com/api", TOKEN));

        // when
        List<McpConnectionCredential> resolved = resolver.resolve(connections, credentials);

        // then（尾斜杠规范化后命中）
        assertEquals("Bearer " + TOKEN, resolved.get(0).headers().get("Authorization"));
    }

    @Test
    void should_notInject_when_resolve_given_targetOnlyPrefixOfConnectionUrl() {
        // given（前缀相似但非全等：严禁前缀匹配——短 Target 不得命中更长 URL）
        List<McpConnection> connections = List.of(
                connection("svc", "https://mcp.example.com/v2/sse"));
        List<VaultCredentialRef> credentials = List.of(
                credential("static_bearer", "https://mcp.example.com", TOKEN));

        // when
        List<McpConnectionCredential> resolved = resolver.resolve(connections, credentials);

        // then（不命中 = 无鉴权头）
        assertTrue(resolved.get(0).headers().isEmpty());
    }

    @Test
    void should_notInject_when_resolve_given_connectionUrlPrefixOfTarget() {
        // given（反向前缀：连接 URL 是 Target 的前缀同样不得命中）
        List<McpConnection> connections = List.of(
                connection("svc", "https://mcp.example.com"));
        List<VaultCredentialRef> credentials = List.of(
                credential("static_bearer", "https://mcp.example.com/v2/sse", TOKEN));

        // when
        List<McpConnectionCredential> resolved = resolver.resolve(connections, credentials);

        // then
        assertTrue(resolved.get(0).headers().isEmpty());
    }

    @Test
    void should_notInject_when_resolve_given_differentPathCase() {
        // given（path 大小写敏感：/Mcp 与 /mcp 是两个不同端点）
        List<McpConnection> connections = List.of(
                connection("svc", "https://mcp.example.com/mcp"));
        List<VaultCredentialRef> credentials = List.of(
                credential("static_bearer", "https://mcp.example.com/Mcp", TOKEN));

        // when
        List<McpConnectionCredential> resolved = resolver.resolve(connections, credentials);

        // then
        assertTrue(resolved.get(0).headers().isEmpty());
    }

    // ==================== 类型口径 ====================

    @Test
    void should_notInject_when_resolve_given_environmentVariableCredential() {
        // given（environment_variable 型：Target 恰与连接 URL 全等也不自动注入——
        //        变量名型密钥按平台红线绝不批量导出到数据面）
        List<McpConnection> connections = List.of(connection("MY_API", "https://mcp.example.com/mcp"));
        List<VaultCredentialRef> credentials = List.of(
                credential("environment_variable", "https://mcp.example.com/mcp", "env-secret-value"));

        // when
        List<McpConnectionCredential> resolved = resolver.resolve(connections, credentials);

        // then
        assertTrue(resolved.get(0).headers().isEmpty());
    }

    @Test
    void should_ignoreUnknownAuthType_when_resolve_given_unsupportedType() {
        // given（未知鉴权类型字域：不具备自动注入语义，静默不注入）
        List<McpConnection> connections = List.of(connection("svc", "https://mcp.example.com/mcp"));
        List<VaultCredentialRef> credentials = List.of(
                credential("api_key", "https://mcp.example.com/mcp", "whatever"));

        // when
        List<McpConnectionCredential> resolved = resolver.resolve(connections, credentials);

        // then
        assertTrue(resolved.get(0).headers().isEmpty());
    }

    // ==================== 无凭证 / 空入参 ====================

    @Test
    void should_returnEmptyHeaderConnection_when_resolve_given_noCredentials() {
        // given（版本声明了连接但本轮无保管库挂载：连接照常产出、无鉴权头）
        List<McpConnection> connections = List.of(
                connection("public-mcp", "https://public.example.com/mcp"));

        // when
        List<McpConnectionCredential> resolved = resolver.resolve(connections, List.of());

        // then（保序一一对应）
        assertEquals(1, resolved.size());
        assertEquals("public-mcp", resolved.get(0).connectionName());
        assertTrue(resolved.get(0).headers().isEmpty());
    }

    @Test
    void should_returnEmptyList_when_resolve_given_noConnections() {
        // given & when（无连接：null / 空清单两态均产空）
        List<McpConnectionCredential> fromNull = resolver.resolve(null, List.of());
        List<McpConnectionCredential> fromEmpty = resolver.resolve(List.of(), List.of());

        // then
        assertTrue(fromNull.isEmpty());
        assertTrue(fromEmpty.isEmpty());
    }

    @Test
    void should_keepConnectionOrder_when_resolve_given_multipleConnections() {
        // given（两连接一中一不中：顺序与入参一致，各取各的鉴权）
        List<McpConnection> connections = List.of(
                connection("alpha", "https://a.example.com/mcp"),
                connection("beta", "https://b.example.com/mcp"));
        List<VaultCredentialRef> credentials = List.of(
                credential("static_bearer", "https://b.example.com/mcp", "tok-beta"));

        // when
        List<McpConnectionCredential> resolved = resolver.resolve(connections, credentials);

        // then
        assertEquals(List.of("alpha", "beta"),
                resolved.stream().map(McpConnectionCredential::connectionName).toList());
        assertTrue(resolved.get(0).headers().isEmpty());
        assertEquals("Bearer tok-beta", resolved.get(1).headers().get("Authorization"));
    }

    // ==================== 冲突与不变量 ====================

    @Test
    void should_throwWithoutToken_when_resolve_given_multipleCredentialsMatchSameConnection() {
        // given（同一连接被两条可注入凭证按同一 URL 命中：无法裁决）
        List<McpConnection> connections = List.of(
                connection("svc", "https://mcp.example.com/mcp"));
        List<VaultCredentialRef> credentials = List.of(
                new VaultCredentialRef("vault_1", "cr_alpha", "static_bearer",
                        "https://mcp.example.com/mcp", TOKEN),
                new VaultCredentialRef("vault_2", "cr_beta", "mcp_oauth",
                        "https://mcp.example.com/mcp/", "tok-2"));

        // when
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> resolver.resolve(connections, credentials));

        // then（消息含连接名与冲突凭证引用，绝不含 token 明文）
        assertTrue(ex.getMessage().contains("svc"));
        assertTrue(ex.getMessage().contains("cr_alpha"));
        assertTrue(ex.getMessage().contains("cr_beta"));
        assertFalse(ex.getMessage().contains(TOKEN));
        assertFalse(ex.getMessage().contains("tok-2"));
    }

    @Test
    void should_throwQuietly_when_resolve_given_matchedCredentialMissingPlainToken() {
        // given（材料化后端异常：命中的凭证明文缺失）
        List<McpConnection> connections = List.of(connection("svc", "https://mcp.example.com/mcp"));
        List<VaultCredentialRef> credentials = List.of(
                new VaultCredentialRef("vault_9", "cr_null_token", "static_bearer",
                        "https://mcp.example.com/mcp", null));

        // when
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> resolver.resolve(connections, credentials));

        // then（定位信息齐备，无明文可泄）
        assertTrue(ex.getMessage().contains("svc"));
        assertTrue(ex.getMessage().contains("vault_9"));
        assertTrue(ex.getMessage().contains("cr_null_token"));
    }

    // ==================== 脱敏面 ====================

    @Test
    void should_maskHeaderValues_when_toString_given_authorizedCredential() {
        // given
        List<McpConnection> connections = List.of(connection("svc", "https://mcp.example.com/mcp"));
        List<VaultCredentialRef> credentials = List.of(
                credential("static_bearer", "https://mcp.example.com/mcp", TOKEN));

        // when
        McpConnectionCredential resolved = resolver.resolve(connections, credentials).get(0);
        String text = resolved.toString();

        // then（键名可见、值一律掩码，明文不随日志 / 异常链泄露）
        assertFalse(text.contains(TOKEN));
        assertTrue(text.contains("Authorization=****"));
        // Map.copyOf 后 headers 值仍完整可取（脱敏只影响 toString 面）
        assertEquals(Map.of("Authorization", "Bearer " + TOKEN), resolved.headers());
    }
}
