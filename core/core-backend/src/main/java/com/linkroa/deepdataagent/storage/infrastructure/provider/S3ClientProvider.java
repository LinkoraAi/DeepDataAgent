package com.linkroa.deepdataagent.storage.infrastructure.provider;

import com.linkroa.deepdataagent.storage.domain.model.enums.StorageType;
import com.linkroa.deepdataagent.storage.infrastructure.config.FileObjectStorageProperties;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * S3 兼容客户端组装策略接口。
 * <p>不同存储 Provider（RustFS / MinIO / OSS）通过实现本接口提供各自的
 * {@code S3Client} 组装差异（寻址模式、区域语义等），由
 * {@link S3ClientProviderRegistry} 按 {@link StorageType} 路由。</p>
 */
public interface S3ClientProvider {

    /**
     * 判断当前策略是否支持指定存储类型。
     *
     * @param type 存储 Provider 类型
     * @return 支持返回 true
     */
    boolean supports(StorageType type);

    /**
     * 按配置组装 S3 客户端。
     *
     * @param properties 对象存储配置
     * @return 组装完成的 S3 客户端（不发起任何网络请求）
     */
    S3Client createClient(FileObjectStorageProperties properties);
}