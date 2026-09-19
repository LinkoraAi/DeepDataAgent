package com.linkroa.deepdataagent.vault.domain.model;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Vault} 保管库聚合根不变量单测（名称格式 / metadata JSON 校验 / 归档语义）。
 */
class VaultTest {

    /** 合法元数据 JSON 样本 */
    private static final String VALID_METADATA = "{\"env\":\"prod\"}";

    @Test
    void should_createVault_when_create_given_validFields() {
        // given & when（合法名称与 JSON 元数据）
        Vault vault = Vault.create("vault_1", "数据分析库", VALID_METADATA, 1L);

        // then（未归档、owner 归属、时间戳落位）
        assertEquals("vault_1", vault.vaultId());
        assertEquals("数据分析库", vault.displayName());
        assertEquals(VALID_METADATA, vault.metadata());
        assertEquals(1L, vault.ownerId());
        assertFalse(vault.archived());
        assertNotNull(vault.createdAt());
        assertNotNull(vault.updatedAt());
    }

    @Test
    void should_restoreVault_when_restore_given_databaseRow() {
        // given
        OffsetDateTime now = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));

        // when
        Vault vault = Vault.restore(1L, "vault_1", "数据分析库", null, 1L, null, now, now, "u-1", "u-1");

        // then
        assertEquals(1L, vault.id());
        assertEquals(now, vault.createdAt());
        assertEquals("u-1", vault.createdBy());
    }

    @Test
    void should_throw_when_create_given_blankVaultId() {
        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> Vault.create(" ", "数据分析库", null, 1L));
    }

    @Test
    void should_throw_when_create_given_blankDisplayName() {
        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> Vault.create("vault_1", " ", null, 1L));
    }

    @Test
    void should_acceptName_when_create_given_displayNameAtLengthBoundary() {
        // when（名称上限 255 字符：公开契约与 V1 列宽 VARCHAR(255) 的边界值必须被接受）
        String boundaryName = "n".repeat(255);

        // then
        Vault vault = Vault.create("vault_1", boundaryName, null, 1L);
        assertEquals(boundaryName, vault.displayName());
    }

    @Test
    void should_throw_when_create_given_displayNameExceeds255Chars() {
        // when & then（名称上限 255 字符，越界 1 字符即拒）
        assertThrows(IllegalArgumentException.class,
                () -> Vault.create("vault_1", "n".repeat(256), null, 1L));
    }

    @Test
    void should_throw_when_create_given_displayNameStartingWithDigit() {
        // when & then（名称不能以数字开头）
        assertThrows(IllegalArgumentException.class,
                () -> Vault.create("vault_1", "1数据", null, 1L));
    }

    @Test
    void should_throw_when_create_given_invalidJsonMetadata() {
        // when & then（metadata 填值必须是合法 JSON，防脏数据落 JSONB 列）
        assertThrows(IllegalArgumentException.class,
                () -> Vault.create("vault_1", "数据分析库", "{not-json", 1L));
    }

    @Test
    void should_acceptBlankMetadata_when_create_given_blankMetadata() {
        // when & then（空串等价于未提供元数据）
        Vault vault = Vault.create("vault_1", "数据分析库", "", 1L);
        assertEquals("", vault.metadata());
    }

    @Test
    void should_throw_when_create_given_metadataExceeds4000Chars() {
        // when & then（元数据长度上限 4000）
        String longMeta = "{}" + " ".repeat(4000);
        assertThrows(IllegalArgumentException.class,
                () -> Vault.create("vault_1", "数据分析库", longMeta, 1L));
    }

    @Test
    void should_throw_when_create_given_nullOwnerId() {
        // when & then（owner 必填，实名化归属）
        assertThrows(IllegalArgumentException.class,
                () -> Vault.create("vault_1", "数据分析库", null, null));
    }

    @Test
    void should_archiveAndFlags_when_archive_given_activeVault() {
        // given
        Vault vault = Vault.create("vault_1", "数据分析库", null, 1L);

        // when（归档返回副本，原对象不受影响）
        Vault archived = vault.archive();

        // then
        assertFalse(vault.archived());
        assertTrue(archived.archived());
        assertNotNull(archived.archivedAt());
        assertEquals("vault_1", archived.vaultId());
        assertEquals(1L, archived.ownerId());
    }
}