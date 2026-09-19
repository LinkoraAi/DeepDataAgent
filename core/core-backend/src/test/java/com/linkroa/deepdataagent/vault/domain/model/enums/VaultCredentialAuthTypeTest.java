package com.linkroa.deepdataagent.vault.domain.model.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link VaultCredentialAuthType} 鉴权类型值域单测（static_bearer / mcp_oauth，源码小写取值，
 * 大小写不敏感解析，值域外拒绝）。
 */
class VaultCredentialAuthTypeTest {

    @Test
    void should_returnSourceValue_when_getValue_given_allTypes() {
        // given // when // then
        assertEquals("static_bearer", VaultCredentialAuthType.STATIC_BEARER.getValue());
        assertEquals("mcp_oauth", VaultCredentialAuthType.MCP_OAUTH.getValue());
    }

    @Test
    void should_parseCaseInsensitive_when_fromValue_given_upperOrMixedCase() {
        // given // when // then
        assertEquals(VaultCredentialAuthType.STATIC_BEARER, VaultCredentialAuthType.fromValue("STATIC_BEARER"));
        assertEquals(VaultCredentialAuthType.STATIC_BEARER, VaultCredentialAuthType.fromValue("Static_Bearer"));
        assertEquals(VaultCredentialAuthType.MCP_OAUTH, VaultCredentialAuthType.fromValue(" mcp_oauth "));
    }

    @Test
    void should_throwException_when_fromValue_given_unknownValue() {
        // given // when // then
        assertThrows(IllegalArgumentException.class, () -> VaultCredentialAuthType.fromValue("api_key"));
    }

    @Test
    void should_throwException_when_fromValue_given_blankValue() {
        // given // when // then
        assertThrows(IllegalArgumentException.class, () -> VaultCredentialAuthType.fromValue(" "));
        assertThrows(IllegalArgumentException.class, () -> VaultCredentialAuthType.fromValue(null));
    }
}
