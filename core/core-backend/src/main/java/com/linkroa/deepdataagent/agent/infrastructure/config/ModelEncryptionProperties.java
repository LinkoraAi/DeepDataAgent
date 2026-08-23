package com.linkroa.deepdataagent.agent.infrastructure.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 模型配置加密属性（独立密钥，与 datasource 密钥完全隔离）。
 * <p>采用 {@code @ConfigurationProperties} 绑定（与 {@code datasource} / {@code vault} 加密属性一致），
 * 使 IDE 可识别属性并消除 "Unknown property" 告警；Binder 对未解析占位符（如缺失
 * {@code APP_MODEL_ENCRYPTION_KEY}）会原样返回 {@code "${...}"}，因此在 {@link #validate()} 中
 * 显式拒绝，保持原 {@code @Value} 绑定"未配置时启动即失败"的 fail-fast 语义。</p>
 */
@Component
@ConfigurationProperties(prefix = "model.encryption")
public class ModelEncryptionProperties {

    /** 模型凭证加密密钥明文（AES/GCM，经 SHA-256 派生为 256-bit 密钥材料）。 */
    private String key;

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    /** 启动期校验：密钥缺失（null / 空白 / 未解析占位符）时启动即失败。 */
    @PostConstruct
    void validate() {
        if (key == null || key.isBlank() || key.contains("${")) {
            throw new IllegalStateException(
                    "model.encryption.key 未配置：请通过环境变量 APP_MODEL_ENCRYPTION_KEY 显式提供（未配置时启动即失败）");
        }
    }
}