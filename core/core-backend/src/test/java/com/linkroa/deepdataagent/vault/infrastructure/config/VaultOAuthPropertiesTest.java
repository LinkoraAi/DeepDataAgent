package com.linkroa.deepdataagent.vault.infrastructure.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link VaultOAuthProperties} 配置契约单测。
 *
 * <p>覆盖：实现默认值口径（回调地址 / state TTL 300s / 刷新窗口 60s）、回调地址来源派生，
 * 以及启动期 fail-fast（非法 URL / 非 http(s) / TTL 与窗口必须为正）。</p>
 */
class VaultOAuthPropertiesTest {

    @Test
    void should_exposeContractDefaults_when_propertiesGiven_noOverrides() {
        // given（契约未规定数值：取实现默认值）
        VaultOAuthProperties properties = new VaultOAuthProperties();

        // when & then
        assertDoesNotThrow(properties::validate);
        assertEquals("http://localhost:8080/api/v1/cloud/vaults/oauth/callback", properties.getCallbackUrl());
        assertEquals("http://localhost:8080", properties.callbackOrigin());
        assertEquals(Duration.ofSeconds(300), properties.stateTtl());
        assertEquals(Duration.ofSeconds(60), properties.refreshWindow());
        assertEquals("DeepDataAgent", properties.getClientName());
    }

    @Test
    void should_deriveOriginAndUri_when_callbackUrl_given_absoluteHttpsUrl() {
        // given
        VaultOAuthProperties properties = new VaultOAuthProperties();
        properties.setCallbackUrl("https://api.example.com/api/v1/cloud/vaults/oauth/callback");

        // when & then（来源取 scheme://authority，路径不参与）
        assertEquals("https://api.example.com", properties.callbackOrigin());
        assertEquals("https://api.example.com/api/v1/cloud/vaults/oauth/callback", properties.callbackUri().toString());
    }

    @Test
    void should_throwIllegalState_when_validate_given_relativeCallbackUrl() {
        // given（回调地址必须由服务端配置为绝对地址，相对地址启动即失败）
        VaultOAuthProperties properties = new VaultOAuthProperties();
        properties.setCallbackUrl("/api/v1/cloud/vaults/oauth/callback");

        // when & then
        assertThrows(IllegalStateException.class, properties::validate);
    }

    @Test
    void should_throwIllegalState_when_validate_given_nonHttpScheme() {
        // given（非 http(s) 协议不被接受）
        VaultOAuthProperties properties = new VaultOAuthProperties();
        properties.setCallbackUrl("ftp://api.example.com/callback");

        // when & then
        assertThrows(IllegalStateException.class, properties::validate);
    }

    @Test
    void should_throwIllegalState_when_validate_given_malformedCallbackUrl() {
        // given（不可解析的 URL：包装为启动期失败而非裸 IllegalArgumentException）
        VaultOAuthProperties properties = new VaultOAuthProperties();
        properties.setCallbackUrl("http://bad host/callback");

        // when & then
        assertThrows(IllegalStateException.class, properties::validate);
    }

    @Test
    void should_throwIllegalState_when_validate_given_nonPositiveTtlOrWindow() {
        // given（TTL 与刷新窗口必须为正数：0 会让 state 立即失效 / 刷新窗口失去意义）
        VaultOAuthProperties zeroTtl = new VaultOAuthProperties();
        zeroTtl.setStateTtlSeconds(0);
        VaultOAuthProperties negativeWindow = new VaultOAuthProperties();
        negativeWindow.setRefreshWindowSeconds(-1);

        // when & then
        assertThrows(IllegalStateException.class, zeroTtl::validate);
        assertThrows(IllegalStateException.class, negativeWindow::validate);
    }
}