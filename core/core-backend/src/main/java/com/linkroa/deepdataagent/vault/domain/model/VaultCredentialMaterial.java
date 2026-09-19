package com.linkroa.deepdataagent.vault.domain.model;

import org.apache.commons.lang3.StringUtils;

/**
 * 凭证秘密材料值对象（{@code mcp_oauth} 加密信封的明文载荷，见 design D5）。
 *
 * <p>单列 {@code ciphertext}（BYTEA）承载<b>整包加密</b>的秘密材料：访问令牌与刷新配置
 * 一并序列化后整体加密，避免为每个 OAuth 成分建列；{@code expires_at} 作为非密列独立承载，
 * 使命中刷新判定无需解密。本值对象只在「解密 → 合并 → 重加密」的应用层调用栈内存中存在，
 * 不落库、不进响应与日志。</p>
 *
 * <p>旧形态兼容：历史行是「单一明文串」（访问令牌裸串）——解析时按只有访问令牌、
 * 无刷新配置处理，故 {@link #refresh()} 可空。</p>
 *
 * @param accessToken 访问令牌（必填，密文成分）
 * @param refresh     刷新配置（可空 = 该凭证无刷新配置，不可经更新补丁新增）
 */
public record VaultCredentialMaterial(
        String accessToken,
        VaultCredentialRefresh refresh
) {

    /**
     * 紧凑构造器：不变量校验（访问令牌必填）。
     */
    public VaultCredentialMaterial {
        if (StringUtils.isBlank(accessToken)) {
            throw new IllegalArgumentException("凭证访问令牌不能为空");
        }
    }

    /**
     * 是否持有刷新配置（无刷新配置的凭证不可补加 refresh）。
     */
    public boolean hasRefresh() {
        return refresh != null;
    }

    /**
     * 替换访问令牌（其余字段保持）。
     */
    public VaultCredentialMaterial withAccessToken(String newAccessToken) {
        return new VaultCredentialMaterial(newAccessToken, refresh);
    }

    /**
     * 替换刷新配置（其余字段保持）。
     */
    public VaultCredentialMaterial withRefresh(VaultCredentialRefresh newRefresh) {
        return new VaultCredentialMaterial(accessToken, newRefresh);
    }
}