package com.linkroa.deepdataagent.vault.domain.model;

import com.linkroa.deepdataagent.vault.domain.model.enums.VaultCredentialTokenEndpointAuthType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link VaultCredentialTokenEndpointAuthType} 值域解析与
 * {@link VaultCredentialTokenEndpointAuth} 不变量单测。
 */
class VaultCredentialTokenEndpointAuthTest {

    @Test
    void should_resolveType_when_fromValue_given_knownValue() {
        // when & then（大小写不敏感 + 首尾空白裁剪）
        assertEquals(VaultCredentialTokenEndpointAuthType.NONE,
                VaultCredentialTokenEndpointAuthType.fromValue("none"));
        assertEquals(VaultCredentialTokenEndpointAuthType.CLIENT_SECRET_BASIC,
                VaultCredentialTokenEndpointAuthType.fromValue(" Client_Secret_Basic "));
        assertEquals(VaultCredentialTokenEndpointAuthType.CLIENT_SECRET_POST,
                VaultCredentialTokenEndpointAuthType.fromValue("client_secret_post"));
    }

    @Test
    void should_throw_when_fromValue_given_blankOrUnknownValue() {
        // when & then（值域外 / 空白 → IllegalArgumentException，防脏值落库）
        assertThrows(IllegalArgumentException.class, () -> VaultCredentialTokenEndpointAuthType.fromValue(" "));
        assertThrows(IllegalArgumentException.class, () -> VaultCredentialTokenEndpointAuthType.fromValue("bearer"));
    }

    @Test
    void should_accept_when_new_given_noneTypeWithoutSecret() {
        // when（公开客户端：none 不要求 client_secret）
        VaultCredentialTokenEndpointAuth auth =
                new VaultCredentialTokenEndpointAuth(VaultCredentialTokenEndpointAuthType.NONE, null);

        // then
        assertEquals(VaultCredentialTokenEndpointAuthType.NONE, auth.type());
        assertNull(auth.clientSecret());
        assertFalse(auth.secretRequired());
    }

    @Test
    void should_accept_when_new_given_secretTypeWithSecret() {
        // when
        VaultCredentialTokenEndpointAuth auth = new VaultCredentialTokenEndpointAuth(
                VaultCredentialTokenEndpointAuthType.CLIENT_SECRET_POST, "cs-1");

        // then
        assertEquals("cs-1", auth.clientSecret());
        assertTrue(auth.secretRequired());
    }

    @Test
    void should_throw_when_new_given_nullType() {
        // when & then（鉴权方式必填）
        assertThrows(IllegalArgumentException.class,
                () -> new VaultCredentialTokenEndpointAuth(null, "cs-1"));
    }

    @Test
    void should_throw_when_new_given_secretTypeWithoutSecret() {
        // when & then（非 none 方式必须携带客户端密钥，否则刷新请求必然被令牌端点拒绝）
        assertThrows(IllegalArgumentException.class, () -> new VaultCredentialTokenEndpointAuth(
                VaultCredentialTokenEndpointAuthType.CLIENT_SECRET_BASIC, " "));
    }
}