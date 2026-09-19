package com.linkroa.deepdataagent.vault.domain.model;

import com.linkroa.deepdataagent.vault.domain.model.enums.VaultCredentialAuthType;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link VaultCredential} 凭证不变量单测（标识必填 + 鉴权类型值域 + URL 长度边界 + 密文非空 +
 * 元数据 JSON 合法性 + merge 更新语义 + 归档判定）。
 */
class VaultCredentialTest {

    /** 合法密文样本（模拟 AES-GCM [IV||密文] 载荷，仅测试长度与空值约束） */
    private static final byte[] CIPHERTEXT = "dummy-iv-cipher".getBytes(StandardCharsets.UTF_8);

    @Test
    void should_createCredential_when_create_given_validFields() {
        // given & when（密文由应用层加密后传入，领域模型只承载密文不触碰明文）
        VaultCredential credential = VaultCredential.create(
                "cr_1", "vault_1", VaultCredentialAuthType.STATIC_BEARER, "https://mcp.example.com/sse", CIPHERTEXT);

        // then
        assertEquals("cr_1", credential.credentialId());
        assertEquals("vault_1", credential.vaultId());
        assertEquals(VaultCredentialAuthType.STATIC_BEARER, credential.authType());
        assertEquals("https://mcp.example.com/sse", credential.mcpServerUrl());
        assertEquals(CIPHERTEXT, credential.ciphertext());
        assertNull(credential.expiresAt());
        assertNull(credential.metadata());
        assertNotNull(credential.createdAt());
        assertNotNull(credential.updatedAt());
        assertFalse(credential.archived());
    }

    @Test
    void should_restoreCredential_when_restore_given_databaseRow() {
        // given
        OffsetDateTime now = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));

        // when
        VaultCredential credential = VaultCredential.restore(
                1L, "cr_1", "vault_1", VaultCredentialAuthType.MCP_OAUTH, "https://mcp.example.com/sse",
                CIPHERTEXT, now, "{\"env\":\"prod\"}", null, now, now, "u-1", "u-1");

        // then
        assertEquals(1L, credential.id());
        assertEquals(VaultCredentialAuthType.MCP_OAUTH, credential.authType());
        assertEquals(now, credential.expiresAt());
        assertEquals("{\"env\":\"prod\"}", credential.metadata());
        assertEquals(now, credential.createdAt());
        assertEquals("u-1", credential.createdBy());
        assertFalse(credential.archived());
    }

    @Test
    void should_beArchived_when_archived_given_archivedAtPresent() {
        // given
        OffsetDateTime now = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));

        // when（archived_at 非空 = 已归档，不再用于新 Session 挂载）
        VaultCredential credential = VaultCredential.restore(
                1L, "cr_1", "vault_1", VaultCredentialAuthType.STATIC_BEARER, "https://mcp.example.com/sse",
                CIPHERTEXT, null, null, now, now, now, "u-1", "u-1");

        // then
        assertTrue(credential.archived());
    }

    @Test
    void should_throw_when_create_given_blankCredentialId() {
        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> VaultCredential.create(" ", "vault_1", VaultCredentialAuthType.STATIC_BEARER,
                        "https://a.example.com", CIPHERTEXT));
    }

    @Test
    void should_throw_when_create_given_blankVaultId() {
        // when & then（凭证必须归属某保管库）
        assertThrows(IllegalArgumentException.class,
                () -> VaultCredential.create("cr_1", " ", VaultCredentialAuthType.STATIC_BEARER,
                        "https://a.example.com", CIPHERTEXT));
    }

    @Test
    void should_throw_when_create_given_nullAuthType() {
        // when & then（鉴权类型值域由领域枚举承载，null 拒绝）
        assertThrows(IllegalArgumentException.class,
                () -> VaultCredential.create("cr_1", "vault_1", null, "https://a.example.com", CIPHERTEXT));
    }

    @Test
    void should_throw_when_create_given_blankMcpServerUrl() {
        // when & then（MCP 服务器 URL 必填，运行时按此匹配注入鉴权）
        assertThrows(IllegalArgumentException.class,
                () -> VaultCredential.create("cr_1", "vault_1", VaultCredentialAuthType.STATIC_BEARER,
                        " ", CIPHERTEXT));
    }

    @Test
    void should_acceptUrl_when_create_given_mcpServerUrlAtLengthBoundary() {
        // when（URL 上限 2048 字符：公开契约与 V1 列宽 VARCHAR(2048) 的边界值必须被接受）
        String boundaryUrl = "https://example.com/" + "p".repeat(2028);

        // then
        assertEquals(2048, boundaryUrl.length());
        VaultCredential credential = VaultCredential.create("cr_1", "vault_1",
                VaultCredentialAuthType.STATIC_BEARER, boundaryUrl, CIPHERTEXT);
        assertEquals(boundaryUrl, credential.mcpServerUrl());
    }

    @Test
    void should_throw_when_create_given_mcpServerUrlExceeds2048Chars() {
        // when & then（URL 上限 2048 字符，越界 1 字符即拒）
        String longUrl = "https://example.com/" + "p".repeat(2029);
        assertThrows(IllegalArgumentException.class,
                () -> VaultCredential.create("cr_1", "vault_1", VaultCredentialAuthType.STATIC_BEARER,
                        longUrl, CIPHERTEXT));
    }

    @Test
    void should_throw_when_create_given_nullCiphertext() {
        // when & then（密文非空：明文必须先加密再落库）
        assertThrows(IllegalArgumentException.class,
                () -> VaultCredential.create("cr_1", "vault_1", VaultCredentialAuthType.STATIC_BEARER,
                        "https://a.example.com", null));
    }

    @Test
    void should_throw_when_create_given_emptyCiphertext() {
        // when & then（空密文等同于明文落库风险，拒绝）
        assertThrows(IllegalArgumentException.class,
                () -> VaultCredential.create("cr_1", "vault_1", VaultCredentialAuthType.STATIC_BEARER,
                        "https://a.example.com", new byte[0]));
    }

    @Test
    void should_throw_when_create_given_invalidMetadataJson() {
        // given（元数据落 JSONB 列，非法 JSON 会污染列语义，必须拒绝）

        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> VaultCredential.restore(1L, "cr_1", "vault_1", VaultCredentialAuthType.STATIC_BEARER,
                        "https://a.example.com", CIPHERTEXT, null, "{not-json", null,
                        OffsetDateTime.now(ZoneId.of("Asia/Shanghai")),
                        OffsetDateTime.now(ZoneId.of("Asia/Shanghai")), "u-1", "u-1"));
    }

    @Test
    void should_replaceMaterials_when_withUpdated_given_ciphertextExpiresAtAndMetadata() {
        // given（merge 补丁由应用层解析为最终值，领域模型只承载结果）
        VaultCredential existing = VaultCredential.restore(
                1L, "cr_1", "vault_1", VaultCredentialAuthType.MCP_OAUTH, "https://mcp.example.com/sse",
                CIPHERTEXT, null, null, null, null, null, "u-1", "u-1");
        byte[] newCiphertext = "new-iv-cipher".getBytes(StandardCharsets.UTF_8);
        OffsetDateTime newExpiresAt = OffsetDateTime.now(ZoneId.of("Asia/Shanghai")).plusHours(1);

        // when
        VaultCredential updated = existing.withUpdated(newCiphertext, newExpiresAt, "{\"env\":\"prod\"}");

        // then（秘密材料 / 到期时间 / 元数据均替换；身份字段、标识与归档态保持）
        assertEquals(newCiphertext, updated.ciphertext());
        assertEquals(newExpiresAt, updated.expiresAt());
        assertEquals("{\"env\":\"prod\"}", updated.metadata());
        assertEquals(VaultCredentialAuthType.MCP_OAUTH, updated.authType());
        assertEquals("https://mcp.example.com/sse", updated.mcpServerUrl());
        assertEquals("cr_1", updated.credentialId());
        assertEquals(1L, updated.id());
        assertFalse(updated.archived());
        assertNotNull(updated.updatedAt());
    }

    @Test
    void should_keepCiphertext_when_withUpdated_given_nullCiphertext() {
        // given（null 密文 = 本次未提交秘密材料补丁，保持原值）
        VaultCredential existing = VaultCredential.restore(
                1L, "cr_1", "vault_1", VaultCredentialAuthType.MCP_OAUTH, "https://mcp.example.com/sse",
                CIPHERTEXT, null, "{\"env\":\"prod\"}", null, null, null, "u-1", "u-1");

        // when（显式清除到期时间：最终值为 null）
        VaultCredential updated = existing.withUpdated(null, null, "{\"env\":\"prod\"}");

        // then
        assertEquals(CIPHERTEXT, updated.ciphertext());
        assertNull(updated.expiresAt());
        assertEquals("{\"env\":\"prod\"}", updated.metadata());
    }

    @Test
    void should_keepCreatedAndArchived_when_withUpdated_given_archivedCredential() {
        // given（归档时间与创建时间不由 merge 更新改写）
        OffsetDateTime createdAt = OffsetDateTime.now(ZoneId.of("Asia/Shanghai")).minusDays(1);
        OffsetDateTime archivedAt = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
        VaultCredential existing = VaultCredential.restore(
                1L, "cr_1", "vault_1", VaultCredentialAuthType.STATIC_BEARER, "https://mcp.example.com/sse",
                CIPHERTEXT, null, null, archivedAt, createdAt, createdAt, "u-1", "u-1");

        // when
        VaultCredential updated = existing.withUpdated(null, null, "{}");

        // then
        assertEquals(createdAt, updated.createdAt());
        assertEquals(archivedAt, updated.archivedAt());
        assertEquals("{}", updated.metadata());
    }
}