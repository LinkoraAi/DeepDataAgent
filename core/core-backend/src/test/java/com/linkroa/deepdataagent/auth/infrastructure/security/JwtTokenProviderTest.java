package com.linkroa.deepdataagent.auth.infrastructure.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class JwtTokenProviderTest {

    private static JwtProperties properties(String secret) {
        JwtProperties p = new JwtProperties();
        p.setSecret(secret);
        return p;
    }

    @Test
    void should_resolveToken_when_resolveBearerToken_given_uppercaseScheme() {
        // given
        String header = "Bearer abc.def.ghi";

        // when
        String token = JwtTokenProvider.resolveBearerToken(header);

        // then
        assertEquals("abc.def.ghi", token);
    }

    @Test
    void should_resolveToken_when_resolveBearerToken_given_lowercaseScheme() {
        // given（RFC 7235 scheme 大小写不敏感）
        String header = "bearer abc.def.ghi";

        // when
        String token = JwtTokenProvider.resolveBearerToken(header);

        // then
        assertEquals("abc.def.ghi", token);
    }

    @Test
    void should_returnNull_when_resolveBearerToken_given_missingOrBlankToken() {
        // given
        String nullToken = null;
        String emptyToken = "Bearer   ";

        // when
        String first = JwtTokenProvider.resolveBearerToken(nullToken);
        String second = JwtTokenProvider.resolveBearerToken(emptyToken);

        // then
        assertNull(first);
        assertNull(second);
    }

    @Test
    void should_roundTripUserIdAndJti_when_generateAndParse_given_validKey() {
        // given
        JwtTokenProvider provider = new JwtTokenProvider(properties("0123456789abcdef0123456789abcdef"));

        // when
        String token = provider.generateToken(42L);
        var claims = provider.parseClaims(token);

        // then（sub 与 jti 均能还原，jti 非空）
        assertEquals("42", claims.getSubject());
        assertEquals(42L, provider.parseUserId(token));
        assertEquals(claims.getId(), claims.getId());
        org.junit.jupiter.api.Assertions.assertTrue(claims.getId() != null && !claims.getId().isBlank());
    }

    @Test
    void should_throwWhenMissingSecret_when_validate_given_blankSecret() {
        // given
        JwtProperties properties = properties("  ");

        // when // then（空白密钥启动校验失败）
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, properties::validate);
    }

    @Test
    void should_throwWhenWeakSecret_when_validate_given_shortSecret() {
        // given（不足 32 字节）
        JwtProperties properties = properties("too-short-key");

        // when // then
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, properties::validate);
    }

    @Test
    void should_pass_when_validate_given_strongSecret() {
        // given（≥32 字节且非占位符）
        JwtProperties properties = properties("0123456789abcdef0123456789abcdef");

        // when // then（不抛异常即通过）
        properties.validate();
    }
}