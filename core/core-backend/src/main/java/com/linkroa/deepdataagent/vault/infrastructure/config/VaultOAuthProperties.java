package com.linkroa.deepdataagent.vault.infrastructure.config;

import jakarta.annotation.PostConstruct;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;

/**
 * MCP OAuth 流转与刷新参数（{@code vault.oauth}，见 design D7 / D8 与「已裁定事项」）。
 *
 * <p>回调地址由<b>服务端配置</b>且 MUST NOT 被请求覆盖（公开契约明示）；state TTL 与刷新窗口
 * 在契约中无数值口径，取实现默认值（300s / 60s）并落为可配置项。</p>
 */
@Component
@ConfigurationProperties(prefix = "vault.oauth")
public class VaultOAuthProperties {

    /** 回调地址（服务端配置，浏览器授权完成后回到此处；须为绝对 http(s) 地址）。 */
    private String callbackUrl = "http://localhost:8080/api/v1/cloud/vaults/oauth/callback";

    /** 一次性 state 的存活时长（秒）。 */
    private int stateTtlSeconds = 300;

    /** access token 刷新窗口（秒）：距过期时间小于该值时判定为「临期」。 */
    private int refreshWindowSeconds = 60;

    /** 动态客户端注册时上报的客户端展示名。 */
    private String clientName = "DeepDataAgent";

    public String getCallbackUrl() {
        return callbackUrl;
    }

    public void setCallbackUrl(String callbackUrl) {
        this.callbackUrl = callbackUrl;
    }

    public int getStateTtlSeconds() {
        return stateTtlSeconds;
    }

    public void setStateTtlSeconds(int stateTtlSeconds) {
        this.stateTtlSeconds = stateTtlSeconds;
    }

    public int getRefreshWindowSeconds() {
        return refreshWindowSeconds;
    }

    public void setRefreshWindowSeconds(int refreshWindowSeconds) {
        this.refreshWindowSeconds = refreshWindowSeconds;
    }

    public String getClientName() {
        return clientName;
    }

    public void setClientName(String clientName) {
        this.clientName = clientName;
    }

    /** state 存活时长。 */
    public Duration stateTtl() {
        return Duration.ofSeconds(stateTtlSeconds);
    }

    /** 刷新窗口时长。 */
    public Duration refreshWindow() {
        return Duration.ofSeconds(refreshWindowSeconds);
    }

    /**
     * 回调地址来源（{@code scheme://authority}）：随发起授权响应回传，
     * 供前端校验回调与自身同源。
     */
    public String callbackOrigin() {
        URI uri = callbackUri();
        return uri.getScheme() + "://" + uri.getAuthority();
    }

    /** 回调地址 URI（启动期已校验，此处必然可解析）。 */
    public URI callbackUri() {
        return URI.create(callbackUrl.trim());
    }

    /** 启动期校验：回调地址必须是绝对 http(s) 地址，TTL / 窗口必须为正，否则启动即失败。 */
    @PostConstruct
    void validate() {
        URI uri;
        try {
            uri = callbackUri();
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("vault.oauth.callback-url 不是合法 URL: " + callbackUrl, e);
        }
        if (!uri.isAbsolute() || StringUtils.isBlank(uri.getAuthority())
                || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalStateException(
                    "vault.oauth.callback-url 必须是绝对的 http(s) 地址（由服务端配置，请求不可覆盖）: " + callbackUrl);
        }
        if (stateTtlSeconds <= 0) {
            throw new IllegalStateException("vault.oauth.state-ttl-seconds 必须为正数: " + stateTtlSeconds);
        }
        if (refreshWindowSeconds <= 0) {
            throw new IllegalStateException("vault.oauth.refresh-window-seconds 必须为正数: " + refreshWindowSeconds);
        }
    }
}