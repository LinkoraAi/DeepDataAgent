package com.linkroa.deepdataagent.vault.api.dto;

import org.apache.commons.lang3.StringUtils;

/**
 * 保管库引用契约（发布语言 DTO，Published Language）。
 * <p>由 vault BC 在应用边界出版，作为 {@code VaultReferenceApi} 的返回类型，
 * 供 runtime 等消费方做挂载存在性 / 归属校验引用。仅输出业务 ID 与显示名称，
 * <b>MUST NOT 携带任何凭证材料（明文 / 密文）</b>。</p>
 *
 * @param vaultId     保管库业务 ID（前缀 vault_）
 * @param displayName 保管库显示名称
 */
public record VaultReferenceDTO(
        String vaultId,
        String displayName
) {

    /**
     * 紧凑构造器：契约边界校验
     */
    public VaultReferenceDTO {
        if (StringUtils.isBlank(vaultId)) {
            throw new IllegalArgumentException("保管库ID不能为空");
        }
        if (StringUtils.isBlank(displayName)) {
            throw new IllegalArgumentException("保管库显示名称不能为空");
        }
    }
}
