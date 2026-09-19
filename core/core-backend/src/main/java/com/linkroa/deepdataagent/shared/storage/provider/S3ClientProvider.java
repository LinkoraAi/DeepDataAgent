package com.linkroa.deepdataagent.shared.storage.provider;

import com.linkroa.deepdataagent.shared.storage.config.ObjectStorageProperties;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * S3 兼容客户端组装策略接口。
 * <p>不同 S3 后端（RustFS / MinIO / OSS）通过实现本接口提供各自的 {@link S3Client}
 * 组装差异（寻址模式、区域语义等），由 {@link S3ClientProviderRegistry} 按
 * {@link StorageBackendType} 路由；新增 S3 后端 = 加策略实现（组件扫描自动注册）。</p>
 */
public interface S3ClientProvider {

    /**
     * 判断当前策略是否支持指定后端类型。
     *
     * @param type 后端类型（S3 系）
     * @return 支持返回 true
     */
    boolean supports(StorageBackendType type);

    /**
     * 按配置组装 S3 客户端（不发起任何网络请求）。
     *
     * @param properties 对象存储配置
     * @return 组装完成的 S3 客户端
     */
    S3Client createClient(ObjectStorageProperties properties);
}
