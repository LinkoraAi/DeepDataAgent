package com.linkroa.deepdataagent.agent.application.dto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ResolvedModelCredentialDTO} 装配缓存命中路径的凭据段窄契约边界校验 + 脱敏单测。
 * <p>契约 DTO 为纯 record、零业务逻辑：本契约承载解密后的模型凭据段，归 {@code application.dto}
 * （机密材料化，永不进 {@code api} 面）。无鉴权模型 {@code credential} 可空（与全量装配契约等价），
 * 故构造器不强制字段非空——「非空」由 {@code resolveModelCredential} 方法恒返回实例与重跑归属校验保证。
 * 红线：{@code toString} 恒掩码明文凭据，杜绝随日志 / 异常链泄露。</p>
 */
class ResolvedModelCredentialDTOTest {

    @Test
    void should_keepBothFields_when_construct_given_credentialAndEndpoint() {
        // given & when
        ResolvedModelCredentialDTO dto = new ResolvedModelCredentialDTO(
                "sk-plain-token", "https://api.example.com/v1");

        // then
        assertEquals("sk-plain-token", dto.credential());
        assertEquals("https://api.example.com/v1", dto.apiEndpointUrl());
    }

    @Test
    void should_acceptNullCredential_when_construct_given_unauthenticatedModel() {
        // given & when & then（无鉴权模型：明文可空，不触发任何校验）
        ResolvedModelCredentialDTO dto = new ResolvedModelCredentialDTO(null, null);
        assertNull(dto.credential());
        assertNull(dto.apiEndpointUrl());
    }

    @Test
    void should_maskCredential_when_toString_given_longPlainText() {
        // given（长度大于 4 的明文凭据）
        ResolvedModelCredentialDTO dto = new ResolvedModelCredentialDTO(
                "sk-super-secret-value", "https://api.example.com/v1");

        // when
        String text = dto.toString();

        // then（明文主体不得出现在 toString，仅保留前 4 位 + 掩码）
        assertFalse(text.contains("super-secret-value"));
        assertTrue(text.contains("sk-s****"));
    }

    @Test
    void should_maskFully_when_toString_given_shortCredential() {
        // given（长度不足以保留前 4 位的短凭据）
        ResolvedModelCredentialDTO dto = new ResolvedModelCredentialDTO("abc", "https://api.example.com/v1");

        // when
        String text = dto.toString();

        // then（短凭据整体掩码，明文不外泄）
        assertFalse(text.contains("abc"));
        assertTrue(text.contains("credential=****"));
    }
}
