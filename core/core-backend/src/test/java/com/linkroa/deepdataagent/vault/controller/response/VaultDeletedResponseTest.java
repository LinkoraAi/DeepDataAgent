package com.linkroa.deepdataagent.vault.controller.response;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * {@link VaultDeletedResponse} 删除响应契约字面量单测。
 * <p>覆盖 design D14：两个删除端点共用 {@code {id, type}} 形状，Vault 删除返回
 * {@code vault_deleted}、Credential 删除返回 {@code vault_credential_deleted}，两类型字面量必须互不相同。</p>
 */
class VaultDeletedResponseTest {

    @Test
    void should_buildVaultDeletedShape_when_vault_given_vaultId() {
        // given & when
        VaultDeletedResponse response = VaultDeletedResponse.vault("vault_1");

        // then（id 原样透传，type 取契约固定字面量）
        assertEquals("vault_1", response.id());
        assertEquals("vault_deleted", response.type());
        assertEquals(VaultDeletedResponse.VAULT_TYPE, response.type());
    }

    @Test
    void should_buildCredentialDeletedShape_when_vaultCredential_given_credentialId() {
        // given & when
        VaultDeletedResponse response = VaultDeletedResponse.vaultCredential("vcred_1");

        // then
        assertEquals("vcred_1", response.id());
        assertEquals("vault_credential_deleted", response.type());
        assertEquals(VaultDeletedResponse.VAULT_CREDENTIAL_TYPE, response.type());
    }

    @Test
    void should_differTypes_when_compare_given_vaultAndCredentialDeletedTypes() {
        // given（两个删除类型字面量）
        String vaultType = VaultDeletedResponse.VAULT_TYPE;
        String credentialType = VaultDeletedResponse.VAULT_CREDENTIAL_TYPE;

        // when & then（不得相同，否则前端无法区分删除对象）
        assertNotEquals(vaultType, credentialType);
    }
}