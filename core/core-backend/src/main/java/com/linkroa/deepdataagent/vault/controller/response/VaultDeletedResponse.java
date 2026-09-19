package com.linkroa.deepdataagent.vault.controller.response;

/**
 * 保管库侧删除响应（对齐公开契约的 {@code {id, type}} 形状，见 design D14）。
 *
 * <p>两处删除端点共用本形状：删除 Vault 返回 {@code type="vault_deleted"}，
 * 删除 Credential 返回 {@code type="vault_credential_deleted"}。</p>
 *
 * @param id   被删除资源的业务 ID
 * @param type 删除类型标识
 */
public record VaultDeletedResponse(
        String id,
        String type
) {

    /** 删除 Vault 的类型标识。 */
    public static final String VAULT_TYPE = "vault_deleted";

    /** 删除 Credential 的类型标识。 */
    public static final String VAULT_CREDENTIAL_TYPE = "vault_credential_deleted";

    /** 删除 Vault 的响应。 */
    public static VaultDeletedResponse vault(String id) {
        return new VaultDeletedResponse(id, VAULT_TYPE);
    }

    /** 删除 Credential 的响应。 */
    public static VaultDeletedResponse vaultCredential(String id) {
        return new VaultDeletedResponse(id, VAULT_CREDENTIAL_TYPE);
    }
}