package com.linkroa.deepdataagent.vault.domain.model;

import com.linkroa.deepdataagent.vault.domain.model.enums.VaultCredentialTokenEndpointAuthType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link VaultCredentialMaterial} 秘密材料值对象不变量单测
 * （访问令牌必填 + 刷新配置可空 + 副本替换保持其余字段）。
 */
class VaultCredentialMaterialTest {

    private static final VaultCredentialRefresh REFRESH = new VaultCredentialRefresh(
            "client-1", "rt-1", "https://auth.example.com/token",
            new VaultCredentialTokenEndpointAuth(VaultCredentialTokenEndpointAuthType.NONE, null), null, null);

    @Test
    void should_buildMaterial_when_new_given_accessTokenOnly() {
        // when（旧形态：只有访问令牌、无刷新配置）
        VaultCredentialMaterial material = new VaultCredentialMaterial("at-1", null);

        // then
        assertEquals("at-1", material.accessToken());
        assertNull(material.refresh());
        assertFalse(material.hasRefresh());
    }

    @Test
    void should_reportHasRefresh_when_new_given_refreshConfig() {
        // when（信封形态：持有刷新配置）
        VaultCredentialMaterial material = new VaultCredentialMaterial("at-1", REFRESH);

        // then
        assertTrue(material.hasRefresh());
        assertSame(REFRESH, material.refresh());
    }

    @Test
    void should_throw_when_new_given_blankAccessToken() {
        // when & then（访问令牌必填，空值会让注入路径产出空 Authorization 头）
        assertThrows(IllegalArgumentException.class, () -> new VaultCredentialMaterial(" ", REFRESH));
    }

    @Test
    void should_replaceAccessTokenOnly_when_withAccessToken_given_newToken() {
        // given
        VaultCredentialMaterial material = new VaultCredentialMaterial("at-old", REFRESH);

        // when
        VaultCredentialMaterial rotated = material.withAccessToken("at-new");

        // then（刷新配置原样保留）
        assertEquals("at-new", rotated.accessToken());
        assertSame(REFRESH, rotated.refresh());
    }

    @Test
    void should_replaceRefreshOnly_when_withRefresh_given_newRefresh() {
        // given
        VaultCredentialMaterial material = new VaultCredentialMaterial("at-1", REFRESH);

        // when
        VaultCredentialMaterial rotated = material.withRefresh(REFRESH.withRefreshToken("rt-2"));

        // then（访问令牌原样保留）
        assertEquals("at-1", rotated.accessToken());
        assertEquals("rt-2", rotated.refresh().refreshToken());
    }
}