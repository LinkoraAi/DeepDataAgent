package com.linkroa.deepdataagent.vault.application.dto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ResolvedVaultCredentialDTO} 运行时解密注入契约边界校验 + 脱敏单测。
 * <p>契约 DTO 为纯 record：仅允许紧凑构造器内的不变量校验（标识与元数据必填），
 * 并对 toString 强制脱敏，凭证明文不随日志 / 异常链输出。</p>
 */
class ResolvedVaultCredentialDTOTest {

    @Test
    void should_acceptValidContract_when_construct_given_allRequiredFields() {
        // given & when（合法契约，明文可空但此处给出完整载荷）
        ResolvedVaultCredentialDTO dto = new ResolvedVaultCredentialDTO(
                "vault_1", "cr_1", "static_bearer", "https://mcp.example.com/sse", "sk-plain");

        // then
        assertTrue(dto.vaultId().equals("vault_1"));
        assertTrue(dto.credentialId().equals("cr_1"));
        assertTrue(dto.authType().equals("static_bearer"));
        assertTrue(dto.mcpServerUrl().equals("https://mcp.example.com/sse"));
        assertTrue(dto.token().equals("sk-plain"));
    }

    @Test
    void should_reject_when_construct_given_blankVaultId() {
        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> new ResolvedVaultCredentialDTO(" ", "cr_1", "static_bearer", "https://a.example.com", "s"));
    }

    @Test
    void should_reject_when_construct_given_blankCredentialId() {
        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> new ResolvedVaultCredentialDTO("vault_1", " ", "static_bearer", "https://a.example.com", "s"));
    }

    @Test
    void should_reject_when_construct_given_blankAuthType() {
        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> new ResolvedVaultCredentialDTO("vault_1", "cr_1", " ", "https://a.example.com", "s"));
    }

    @Test
    void should_reject_when_construct_given_blankMcpServerUrl() {
        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> new ResolvedVaultCredentialDTO("vault_1", "cr_1", "static_bearer", " ", "s"));
    }

    @Test
    void should_acceptNullToken_when_construct_given_nullToken() {
        // given & when & then（明文可空：无鉴权场景仍可传递元数据，不触发校验）
        new ResolvedVaultCredentialDTO("vault_1", "cr_1", "static_bearer", "https://a.example.com", null);
    }

    @Test
    void should_maskToken_when_toString_given_plainTextToken() {
        // given（含解密密文的完整契约）
        ResolvedVaultCredentialDTO dto = new ResolvedVaultCredentialDTO(
                "vault_1", "cr_1", "static_bearer", "https://mcp.example.com/sse", "sk-plain-token");

        // when
        String text = dto.toString();

        // then（凭证明文不得出现在 toString，mask 固定为 ****）
        assertFalse(text.contains("sk-plain-token"));
        assertTrue(text.contains("token=****"));
    }
}
