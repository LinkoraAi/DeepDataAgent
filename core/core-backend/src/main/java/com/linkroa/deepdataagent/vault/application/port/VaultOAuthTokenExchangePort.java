package com.linkroa.deepdataagent.vault.application.port;

import com.linkroa.deepdataagent.vault.application.dto.OAuthTokenOutcomeDTO;
import com.linkroa.deepdataagent.vault.domain.model.VaultOAuthCodeExchange;

/**
 * 授权码换取令牌出站端口（{@code grant_type=authorization_code}，见 design D8）。
 *
 * <p>与刷新同源：令牌端点请求同样经共享出网执行器（信任边界复检、越界零请求、
 * 重定向不携带鉴权头），失败以「未换得令牌」承载而非抛异常。</p>
 *
 * <p><b>明文边界</b>：授权码、PKCE 校验串与客户端密钥只进请求体 / 请求头（内存与网络），
 * 不落库、不进响应与日志；回传诊断中的响应体已脱敏截断。</p>
 */
public interface VaultOAuthTokenExchangePort {

    /**
     * 以授权码换取令牌。
     *
     * @param exchange 换码载荷（令牌端点、客户端身份、授权码、PKCE 校验串、回调地址）
     * @return 换码结果（未收到响应 / 非 2xx / 报文不含访问令牌时 {@code accessToken} 为空）
     */
    OAuthTokenOutcomeDTO exchangeAuthorizationCode(VaultOAuthCodeExchange exchange);
}