package com.linkroa.deepdataagent.vault.infrastructure.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 凭证库（vault BC）加密属性（独立密钥，与 datasource / model 密钥完全隔离）。
 * <p>采用 {@code @ConfigurationProperties} 绑定，Binder 对未解析占位符（缺失
 * {@code APP_VAULT_ENCRYPTION_KEY}）会原样返回 {@code "${...}"}，因此在 {@link #validate()}
 * 中显式拒绝，保持"未配置时启动即失败"的 fail-fast 语义。
 * 密钥强度对齐 {@code JwtProperties}：须 ≥ 32 字节，防止低熵密钥被离线爆破。</p>
 */
@Component
@ConfigurationProperties(prefix = "vault.encryption")
public class VaultEncryptionProperties {

    private String key;

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    /** 启动期校验：密钥缺失（null / 空白 / 未解析占位符）或强度不足时启动即失败。 */
    @PostConstruct
    void validate() {
        if (key == null || key.isBlank() || key.contains("${")) {
            throw new IllegalStateException(
                    "vault.encryption.key 未配置：请通过环境变量 APP_VAULT_ENCRYPTION_KEY 显式提供（未配置时启动即失败）");
        }
        if (key.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("vault.encryption.key 强度不足：加密密钥须 ≥ 32 字节");
        }
    }
}