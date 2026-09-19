package com.linkroa.deepdataagent.vault.application.port;

import com.linkroa.deepdataagent.vault.application.dto.OAuthRefreshOutcomeDTO;
import com.linkroa.deepdataagent.vault.domain.model.VaultCredentialRefresh;

/**
 * 令牌刷新出站端口（进程内依赖倒置，见 design D4 / D6）。
 *
 * <p>以 {@code refresh_token} 向令牌端点换取新访问令牌：明文（刷新令牌、客户端密钥）仅在
 * 本端口调用栈内存中存在，不落库、不进响应与日志；出网经共享信任边界执行器
 * （{@code TrustedEgressClient}），越界目标<b>不发出任何网络请求</b>。</p>
 *
 * <p>本端口只回传事实（是否换得令牌、是否轮换刷新令牌、收到的响应），分类裁决
 * （{@code succeeded} / {@code failed} / {@code connect_error}）与「刷新成功后持久化轮换令牌」
 * 由应用层承担。</p>
 */
public interface VaultOAuthRefreshPort {

    /**
     * 以刷新令牌换取新的访问令牌。
     *
     * @param refresh 刷新配置（含令牌端点、客户端身份与刷新令牌）
     * @return 刷新调用的原始结果（绝不抛异常，任何失败以「未换得令牌」承载）
     */
    OAuthRefreshOutcomeDTO refresh(VaultCredentialRefresh refresh);
}