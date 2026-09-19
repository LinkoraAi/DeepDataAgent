package com.linkroa.deepdataagent.vault.application.dto;

import org.apache.commons.lang3.StringUtils;

/**
 * OAuth 回调落库结果（应用层 DTO，仅进程内流转）。
 *
 * <p>回调成功的响应 MUST NOT 返回任何令牌密文：本 DTO 只承载「在哪个库下建了哪条凭证」的
 * 标识，供回调页面以 {@code oauth_callback} 消息回传 opener。</p>
 *
 * @param vaultId      凭据所属保管库业务 ID
 * @param credentialId 新建凭证业务 ID
 * @param origin       发起授权时的来源（state 记录）：回调页 postMessage 的目标源，
 *                     MUST NOT 以 {@code *} 广播（origin 校验）
 */
public record OAuthCallbackResultDTO(String vaultId, String credentialId, String origin) {

    /**
     * 紧凑构造器：契约边界校验（三项必填）。
     */
    public OAuthCallbackResultDTO {
        if (StringUtils.isBlank(vaultId)) {
            throw new IllegalArgumentException("保管库ID不能为空");
        }
        if (StringUtils.isBlank(credentialId)) {
            throw new IllegalArgumentException("凭证ID不能为空");
        }
        if (StringUtils.isBlank(origin)) {
            throw new IllegalArgumentException("回调来源不能为空");
        }
    }
}