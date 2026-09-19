package com.linkroa.deepdataagent.vault.domain.model;

import com.linkroa.deepdataagent.vault.domain.model.enums.VaultCredentialTokenEndpointAuthType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link VaultCredentialRefresh} 刷新配置值对象不变量单测
 * （身份字段与刷新令牌必填 + 副本替换保持其余字段）。
 */
class VaultCredentialRefreshTest {

    private static final VaultCredentialTokenEndpointAuth AUTH =
            new VaultCredentialTokenEndpointAuth(VaultCredentialTokenEndpointAuthType.CLIENT_SECRET_BASIC, "cs-1");

    @Test
    void should_buildRefresh_when_new_given_allFields() {
        // when
        VaultCredentialRefresh refresh = new VaultCredentialRefresh(
                "client-1", "rt-1", "https://auth.example.com/token", AUTH, "https://mcp.example.com", "read write");

        // then
        assertEquals("client-1", refresh.clientId());
        assertEquals("rt-1", refresh.refreshToken());
        assertEquals("https://auth.example.com/token", refresh.tokenEndpoint());
        assertEquals(AUTH, refresh.tokenEndpointAuth());
        assertEquals("https://mcp.example.com", refresh.resource());
        assertEquals("read write", refresh.scope());
    }

    @Test
    void should_throw_when_new_given_missingRequiredFields() {
        // when & then（client_id / token_endpoint 为身份字段必填；refresh_token 缺失即半残配置）
        assertThrows(IllegalArgumentException.class, () -> new VaultCredentialRefresh(
                " ", "rt-1", "https://auth.example.com/token", AUTH, null, null));
        assertThrows(IllegalArgumentException.class, () -> new VaultCredentialRefresh(
                "client-1", " ", "https://auth.example.com/token", AUTH, null, null));
        assertThrows(IllegalArgumentException.class, () -> new VaultCredentialRefresh(
                "client-1", "rt-1", " ", AUTH, null, null));
        assertThrows(IllegalArgumentException.class, () -> new VaultCredentialRefresh(
                "client-1", "rt-1", "https://auth.example.com/token", null, null, null));
    }

    @Test
    void should_replaceTokenOnly_when_withRefreshToken_given_newToken() {
        // given
        VaultCredentialRefresh refresh = new VaultCredentialRefresh(
                "client-1", "rt-old", "https://auth.example.com/token", AUTH, null, "read");

        // when
        VaultCredentialRefresh rotated = refresh.withRefreshToken("rt-new");

        // then（身份字段与 scope 保持，仅刷新令牌替换）
        assertEquals("rt-new", rotated.refreshToken());
        assertEquals("client-1", rotated.clientId());
        assertEquals("https://auth.example.com/token", rotated.tokenEndpoint());
        assertEquals("read", rotated.scope());
    }

    @Test
    void should_clearScope_when_withScope_given_null() {
        // given
        VaultCredentialRefresh refresh = new VaultCredentialRefresh(
                "client-1", "rt-1", "https://auth.example.com/token", AUTH, null, "read write");

        // when（显式 null = 清除 scope）
        VaultCredentialRefresh cleared = refresh.withScope(null);

        // then
        assertNull(cleared.scope());
        assertEquals("rt-1", cleared.refreshToken());
    }

    @Test
    void should_replaceAuthOnly_when_withTokenEndpointAuth_given_newAuth() {
        // given
        VaultCredentialRefresh refresh = new VaultCredentialRefresh(
                "client-1", "rt-1", "https://auth.example.com/token", AUTH, null, "read");
        VaultCredentialTokenEndpointAuth publicClient =
                new VaultCredentialTokenEndpointAuth(VaultCredentialTokenEndpointAuthType.NONE, null);

        // when
        VaultCredentialRefresh rotated = refresh.withTokenEndpointAuth(publicClient);

        // then
        assertEquals(publicClient, rotated.tokenEndpointAuth());
        assertEquals("rt-1", rotated.refreshToken());
        assertEquals("read", rotated.scope());
    }
}