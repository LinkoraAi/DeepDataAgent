package com.linkroa.deepdataagent.shared.storage.provider;

import com.linkroa.deepdataagent.shared.storage.ObjectStorage;
import com.linkroa.deepdataagent.shared.storage.config.ObjectStorageProperties;

/**
 * 对象存储后端装配策略（技术 SPI）。
 * <p>每个后端（local / 各 S3 兼容实现）声明支持的 {@link StorageBackendType}
 * 并据配置创建一个 {@link ObjectStorage} 实例，由 {@link ObjectStorageProviderRegistry}
 * 按 {@code app.storage.type} 路由。</p>
 */
public interface ObjectStorageProvider {

    /**
     * 判断当前策略是否支持指定后端类型。
     *
     * @param type 后端类型
     * @return 支持返回 true
     */
    boolean supports(StorageBackendType type);

    /**
     * 按配置创建对象存储实例（不要求完成网络就绪，就绪动作由端口的
     * {@link ObjectStorage#ensureReady()} 在启动装配期统一触发）。
     *
     * @param properties 对象存储配置
     * @return 对象存储实例
     */
    ObjectStorage create(ObjectStorageProperties properties);
}
