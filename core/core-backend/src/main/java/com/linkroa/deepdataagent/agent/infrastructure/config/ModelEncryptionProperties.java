package com.linkroa.deepdataagent.agent.infrastructure.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 模型配置加密属性（独立密钥，与 datasource 密钥完全隔离）
 */
@Component
public class ModelEncryptionProperties {

    private final String key;

    public ModelEncryptionProperties(@Value("${model.encryption.key}") String key) {
        this.key = key;
    }

    public String getKey() {
        return key;
    }
}