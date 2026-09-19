package com.linkroa.deepdataagent.vault.application.convert;

import com.linkroa.deepdataagent.vault.domain.model.VaultCredentialMaterial;
import com.linkroa.deepdataagent.vault.domain.model.VaultCredentialRefresh;
import com.linkroa.deepdataagent.vault.domain.model.VaultCredentialTokenEndpointAuth;
import com.linkroa.deepdataagent.vault.domain.model.enums.VaultCredentialTokenEndpointAuthType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link VaultCredentialMaterialConvert} 秘密材料 ⇄ 加密信封转换单测
 * （信封往返、旧形态裸串兼容、脏信封拒绝，见 design D5）。
 */
class VaultCredentialMaterialConvertTest {

    private static final VaultCredentialMaterialConvert CONVERT = VaultCredentialMaterialConvert.INSTANCE;

    private static final VaultCredentialRefresh REFRESH = new VaultCredentialRefresh(
            "client-1", "rt-1", "https://auth.example.com/token",
            new VaultCredentialTokenEndpointAuth(VaultCredentialTokenEndpointAuthType.CLIENT_SECRET_BASIC, "cs-1"),
            "https://mcp.example.com", "read write");

    @Test
    void should_roundTrip_when_toEnvelopeJsonAndParse_given_materialWithRefresh() {
        // given
        VaultCredentialMaterial material = new VaultCredentialMaterial("at-1", REFRESH);

        // when（信封序列化 → 反解析往返）
        String envelope = CONVERT.toEnvelopeJson(material);
        VaultCredentialMaterial parsed = CONVERT.parse(envelope);

        // then（全部成分无损还原）
        assertTrue(envelope.contains("\"kind\":\"vault_credential_material\""));
        assertEquals("at-1", parsed.accessToken());
        assertTrue(parsed.hasRefresh());
        assertEquals("client-1", parsed.refresh().clientId());
        assertEquals("rt-1", parsed.refresh().refreshToken());
        assertEquals("https://auth.example.com/token", parsed.refresh().tokenEndpoint());
        assertEquals(VaultCredentialTokenEndpointAuthType.CLIENT_SECRET_BASIC,
                parsed.refresh().tokenEndpointAuth().type());
        assertEquals("cs-1", parsed.refresh().tokenEndpointAuth().clientSecret());
        assertEquals("https://mcp.example.com", parsed.refresh().resource());
        assertEquals("read write", parsed.refresh().scope());
    }

    @Test
    void should_omitRefreshKey_when_toEnvelopeJson_given_materialWithoutRefresh() {
        // given
        VaultCredentialMaterial material = new VaultCredentialMaterial("at-1", null);

        // when
        String envelope = CONVERT.toEnvelopeJson(material);

        // then（无刷新配置时省略 refresh 键，反解析仍为无刷新配置）
        assertFalse(envelope.contains("refresh"));
        assertFalse(CONVERT.parse(envelope).hasRefresh());
    }

    @Test
    void should_treatAsLegacyToken_when_parse_given_bareToken() {
        // given（旧形态：密文解密后是访问令牌裸串，非信封 JSON）

        // when
        VaultCredentialMaterial material = CONVERT.parse("sk-legacy-plain-token");

        // then（按只有访问令牌、无刷新配置承接，存量凭证无需数据迁移）
        assertEquals("sk-legacy-plain-token", material.accessToken());
        assertNull(material.refresh());
    }

    @Test
    void should_treatAsLegacyToken_when_parse_given_jsonWithoutKindMarker() {
        // given（恰好是 JSON 对象的令牌不得被误判为信封）

        // when
        VaultCredentialMaterial material = CONVERT.parse("{\"access_token\":\"at-1\"}");

        // then（无 kind 标记 → 整体即访问令牌裸串）
        assertEquals("{\"access_token\":\"at-1\"}", material.accessToken());
        assertFalse(material.hasRefresh());
    }

    @Test
    void should_throw_when_parse_given_blankPlaintext() {
        // when & then（空明文会让注入路径产出空令牌）
        assertThrows(IllegalArgumentException.class, () -> CONVERT.parse(" "));
    }

    @Test
    void should_throw_when_parse_given_envelopeMissingAccessToken() {
        // given（信封形态但缺 access_token → 脏信封拒绝）

        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> CONVERT.parse("{\"kind\":\"vault_credential_material\",\"version\":1}"));
    }

    @Test
    void should_throw_when_parse_given_envelopeWithIncompleteRefresh() {
        // given（信封内 refresh 缺身份字段 → 拒绝，避免解析出半残刷新配置）

        // when & then
        assertThrows(IllegalArgumentException.class, () -> CONVERT.parse(
                "{\"kind\":\"vault_credential_material\",\"version\":1,\"access_token\":\"at-1\","
                        + "\"refresh\":{\"refresh_token\":\"rt-1\"}}"));
    }
}