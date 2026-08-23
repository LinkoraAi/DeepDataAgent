package com.linkroa.deepdataagent.vault.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 凭证库（vault BC）加密属性（独立密钥，与 datasource / model 密钥完全隔离）。
 */
@ConfigurationProperties(prefix = "vault.encryption")
public class VaultEncryptionProperties {

    private String key;

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }
}