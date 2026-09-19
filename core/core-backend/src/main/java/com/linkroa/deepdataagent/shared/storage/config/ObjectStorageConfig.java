package com.linkroa.deepdataagent.shared.storage.config;

import com.linkroa.deepdataagent.shared.storage.ObjectStorage;
import com.linkroa.deepdataagent.shared.storage.provider.ObjectStorageProviderRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 对象存储启动装配（shared 技术能力）。
 * <p>按 {@code app.storage.type} 经 {@link ObjectStorageProviderRegistry} 创建唯一
 * {@link ObjectStorage} Bean，并在装配期触发一次就绪准备（local 建根目录 / S3 确保配置桶），
 * 就绪失败 fail-fast 中止启动，避免带故障存储对外提供服务。</p>
 */
@Configuration
public class ObjectStorageConfig {

    /**
     * 创建并就绪对象存储端口实例。
     *
     * @param registry 后端装配策略注册表
     * @param properties 对象存储配置（已在 {@link ObjectStorageProperties#validate()} 完成分支校验）
     * @return 已就绪的对象存储
     */
    @Bean
    public ObjectStorage objectStorage(ObjectStorageProviderRegistry registry,
                                       ObjectStorageProperties properties) {
        ObjectStorage storage = registry.createStorage(properties);
        storage.ensureReady();
        return storage;
    }
}
