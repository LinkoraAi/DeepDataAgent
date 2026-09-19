package com.linkroa.deepdataagent.vault.application.command;

import com.linkroa.deepdataagent.vault.domain.model.VaultCredentialTokenEndpointAuth;
import org.apache.commons.lang3.StringUtils;

import java.time.OffsetDateTime;

/**
 * 更新凭证命令（merge-patch 语义，见 design D3.1）。
 *
 * <p>三态表达约定：{@code xxxPresent} 标志区分「缺省不改」与「显式提供」——
 * present=false 时对应值字段无意义（应用层回填原值）；present=true 且值为 {@code null}
 * 表示<b>显式清除</b>（{@code expires_at}→无到期、{@code refresh.scope}→无范围、
 * {@code metadata}→{@code {}}）。</p>
 *
 * <p>按类型限定可更新字段：{@code static_bearer} 仅 {@code token}；{@code mcp_oauth} 仅
 * {@code access_token}、{@code expires_at}、{@code refresh.refresh_token}、
 * {@code refresh.scope}、{@code refresh.token_endpoint_auth}。跨类型误用与身份字段
 * （{@code auth.type}、{@code mcp_server_url}、{@code refresh.client_id}、
 * {@code refresh.token_endpoint}）在协议装配层即拒（400），故命令不承载身份字段的新值。</p>
 *
 * <p>{@code auth.type} 仅在提交 {@code auth} 补丁时必填（{@code authType} 非空），
 * 其与凭证既有类型的一致性由应用层裁决（命令侧不感知既有状态）。</p>
 *
 * @param vaultId                          所属保管库业务ID（必填）
 * @param credentialId                     凭证业务ID（必填）
 * @param authType                         声明的鉴权类型（null=本次未提交 auth 补丁）
 * @param token                            新静态凭证明文（与 present 成对，仅 static_bearer）
 * @param tokenPresent                     静态凭证明文是否提供
 * @param accessToken                      新 OAuth 访问令牌（与 present 成对，仅 mcp_oauth）
 * @param accessTokenPresent               OAuth 访问令牌是否提供
 * @param expiresAt                        访问令牌到期时间（与 present 成对；present 且 null=清除）
 * @param expiresAtPresent                 到期时间是否提供
 * @param refreshToken                     新刷新令牌（与 present 成对）
 * @param refreshTokenPresent              刷新令牌是否提供
 * @param refreshScope                     新授权范围（与 present 成对；present 且 null=清除）
 * @param refreshScopePresent              授权范围是否提供
 * @param refreshTokenEndpointAuth         新令牌端点鉴权（与 present 成对）
 * @param refreshTokenEndpointAuthPresent  令牌端点鉴权是否提供
 * @param metadataMerge                    元数据浅合并增量 JSON 文本（与 present 成对；present 且 null=整体重置）
 * @param metadataPresent                  元数据是否提供
 */
public record UpdateVaultCredentialCommand(
        String vaultId,
        String credentialId,
        String authType,
        String token,
        boolean tokenPresent,
        String accessToken,
        boolean accessTokenPresent,
        OffsetDateTime expiresAt,
        boolean expiresAtPresent,
        String refreshToken,
        boolean refreshTokenPresent,
        String refreshScope,
        boolean refreshScopePresent,
        VaultCredentialTokenEndpointAuth refreshTokenEndpointAuth,
        boolean refreshTokenEndpointAuthPresent,
        String metadataMerge,
        boolean metadataPresent
) {

    /**
     * 紧凑构造器：不变量校验（归属标识必填 + 空补丁拒绝）。
     */
    public UpdateVaultCredentialCommand {
        if (StringUtils.isBlank(vaultId)) {
            throw new IllegalArgumentException("保管库ID不能为空");
        }
        if (StringUtils.isBlank(credentialId)) {
            throw new IllegalArgumentException("凭证ID不能为空");
        }
        if (StringUtils.isBlank(authType) && !tokenPresent && !accessTokenPresent && !expiresAtPresent
                && !refreshTokenPresent && !refreshScopePresent && !refreshTokenEndpointAuthPresent
                && !metadataPresent) {
            throw new IllegalArgumentException("更新补丁不能为空，至少需提供 auth 或 metadata 之一");
        }
    }

    /**
     * 是否提交了 auth 补丁（{@code auth.type} 已声明）。
     */
    public boolean authPresent() {
        return StringUtils.isNotBlank(authType);
    }

    /**
     * 是否提交了刷新配置补丁（任一 refresh 子字段提供）。
     */
    public boolean refreshPresent() {
        return refreshTokenPresent || refreshScopePresent || refreshTokenEndpointAuthPresent;
    }
}