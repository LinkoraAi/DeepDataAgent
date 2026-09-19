package com.linkroa.deepdataagent.vault.infrastructure.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link VaultEncryptionProperties} 启动期校验单测：锁定「缺失 / 未解析占位符 fail-fast」
 * 与「密钥强度 ≥32 字节」（对齐 JwtProperties，防低熵密钥离线爆破）两级语义。
 */
class VaultEncryptionPropertiesTest {

    @Test
    void should_throwIllegalState_when_validate_given_missingKey() {
        // given（密钥缺失：null / 空白 / Binder 未解析占位符原样返回）
        VaultEncryptionProperties properties = new VaultEncryptionProperties();
        properties.setKey("  ");

        // when & then
        IllegalStateException exception = assertThrows(IllegalStateException.class, properties::validate);
        assertEquals("vault.encryption.key 未配置：请通过环境变量 APP_VAULT_ENCRYPTION_KEY 显式提供（未配置时启动即失败）",
                exception.getMessage());
    }

    @Test
    void should_throwIllegalState_when_validate_given_keyShorterThan32Bytes() {
        // given（短密钥：UTF-8 字节数不足 32）
        VaultEncryptionProperties properties = new VaultEncryptionProperties();
        properties.setKey("short-vault-key");

        // when & then
        IllegalStateException exception = assertThrows(IllegalStateException.class, properties::validate);
        assertEquals("vault.encryption.key 强度不足：加密密钥须 ≥ 32 字节", exception.getMessage());
    }

    @Test
    void should_pass_when_validate_given_keyAtLeast32Bytes() {
        // given（合法高熵密钥：≥32 字节）
        VaultEncryptionProperties properties = new VaultEncryptionProperties();
        properties.setKey("test-vault-key-32-bytes-long!!!!");

        // when & then
        assertDoesNotThrow(properties::validate);
    }
}
