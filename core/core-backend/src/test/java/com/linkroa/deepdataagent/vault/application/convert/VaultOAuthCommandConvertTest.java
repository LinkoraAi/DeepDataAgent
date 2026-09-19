package com.linkroa.deepdataagent.vault.application.convert;

import com.linkroa.deepdataagent.vault.application.command.StartVaultOAuthCommand;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link VaultOAuthCommandConvert} 请求字段裁决单测。
 * <p>覆盖：契约允许字段装配、传输层字段出现即拒（{@code protocol} / {@code scope} /
 * {@code redirect_uri}）、未知字段即拒、空体即拒、可空字段归一（空白 → null）与必填字段不变量。</p>
 *
 * <p>注：application/convert 层按 AGENTS.md 不计入单元测试覆盖率，本测试为行为回归保障。</p>
 */
class VaultOAuthCommandConvertTest {

    @Test
    void should_buildCommand_when_toStartCommand_given_allowedFields() {
        // given（契约允许的四个字段齐备）
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("vault_id", "vault_1");
        fields.put("mcp_server_url", "https://mcp.example.com/sse");
        fields.put("client_id", "client-1");
        fields.put("client_secret", "cs-1");

        // when
        StartVaultOAuthCommand command = VaultOAuthCommandConvert.INSTANCE
                .toStartCommand(fields, "http://localhost:8080");

        // then
        assertEquals("vault_1", command.vaultId());
        assertEquals("https://mcp.example.com/sse", command.mcpServerUrl());
        assertEquals("client-1", command.clientId());
        assertEquals("cs-1", command.clientSecret());
        assertEquals("http://localhost:8080", command.origin());
        assertTrue(command.hasClientId());
        assertTrue(command.hasClientSecret());
    }

    @Test
    void should_rejectForbiddenFields_when_toStartCommand_given_transportLayerFields() {
        // given（契约明示：protocol / scope / redirect_uri MUST NOT 作为本端点请求字段）
        for (String field : List.of("protocol", "scope", "redirect_uri")) {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("vault_id", "vault_1");
            fields.put("mcp_server_url", "https://mcp.example.com/sse");
            fields.put(field, "x");

            // when & then（出现即拒，绝不静默忽略）
            assertThrows(IllegalArgumentException.class,
                    () -> VaultOAuthCommandConvert.INSTANCE.toStartCommand(fields, null));
        }
    }

    @Test
    void should_rejectUnknownField_when_toStartCommand_given_undeclaredField() {
        // given
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("vault_id", "vault_1");
        fields.put("mcp_server_url", "https://mcp.example.com/sse");
        fields.put("client_name", "whatever");

        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> VaultOAuthCommandConvert.INSTANCE.toStartCommand(fields, null));
    }

    @Test
    void should_rejectEmptyBody_when_toStartCommand_given_emptyFields() {
        // when & then（空体与 null 体同口径拒绝）
        assertThrows(IllegalArgumentException.class,
                () -> VaultOAuthCommandConvert.INSTANCE.toStartCommand(Map.of(), null));
        assertThrows(IllegalArgumentException.class,
                () -> VaultOAuthCommandConvert.INSTANCE.toStartCommand(null, null));
    }

    @Test
    void should_rejectNonStringValue_when_toStartCommand_given_numberTypedVaultId() {
        // given（类型不符即拒，不做隐式 toString）
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("vault_id", 1);
        fields.put("mcp_server_url", "https://mcp.example.com/sse");

        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> VaultOAuthCommandConvert.INSTANCE.toStartCommand(fields, null));
    }

    @Test
    void should_normalizeBlankOptionalFields_when_toStartCommand_given_blankClientIdentity() {
        // given（可选字段空白等价于未提供：hasClientId = false 才会走动态注册）
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("vault_id", "vault_1");
        fields.put("mcp_server_url", "https://mcp.example.com/sse");
        fields.put("client_id", "  ");
        fields.put("client_secret", "");

        // when
        StartVaultOAuthCommand command = VaultOAuthCommandConvert.INSTANCE.toStartCommand(fields, null);

        // then
        assertNull(command.clientId());
        assertNull(command.clientSecret());
        assertFalse(command.hasClientId());
        assertFalse(command.hasClientSecret());
        assertNull(command.origin());
    }

    @Test
    void should_rejectBlankRequiredFields_when_toStartCommand_given_blankVaultIdOrUrl() {
        // given（必填字段缺省 / 空白即拒）
        Map<String, Object> blankVaultId = new LinkedHashMap<>();
        blankVaultId.put("vault_id", "");
        blankVaultId.put("mcp_server_url", "https://mcp.example.com/sse");

        Map<String, Object> missingUrl = new LinkedHashMap<>();
        missingUrl.put("vault_id", "vault_1");

        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> VaultOAuthCommandConvert.INSTANCE.toStartCommand(blankVaultId, null));
        assertThrows(IllegalArgumentException.class,
                () -> VaultOAuthCommandConvert.INSTANCE.toStartCommand(missingUrl, null));
    }

    @Test
    void should_rejectOverlongUrl_when_toStartCommand_given_urlExceeding2048() {
        // given（URL 上限与凭证领域不变量及 V1 列宽一致）
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("vault_id", "vault_1");
        fields.put("mcp_server_url", "https://mcp.example.com/" + "a".repeat(2025));

        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> VaultOAuthCommandConvert.INSTANCE.toStartCommand(fields, null));
    }

    @Test
    void should_acceptUrl_when_toStartCommand_given_urlExactly2048() {
        // given
        String maxUrl = "https://mcp.example.com/" + "a".repeat(2024);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("vault_id", "vault_1");
        fields.put("mcp_server_url", maxUrl);

        // when
        StartVaultOAuthCommand command = VaultOAuthCommandConvert.INSTANCE.toStartCommand(fields, null);

        // then（边界值恰为上限，正常装配）
        assertEquals(maxUrl, command.mcpServerUrl());
    }
}