package com.linkroa.deepdataagent.shared.storage.provider;

import com.linkroa.deepdataagent.shared.storage.config.ObjectStorageProperties;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * 通用 S3 兼容客户端策略：支撑 MinIO 与阿里云 OSS。
 * <p>两者均以标准 S3 SDK 接入，寻址模式（path-style / virtual-host-style）
 * 随 {@code app.storage.path-style} 配置决定；未来若某后端出现签名 / 区域差异，
 * 再拆分为独立策略。</p>
 */
@Component
public class GenericS3ClientProvider extends AbstractS3ClientProvider {

    /**
     * {@inheritDoc}
     * <p>本策略支撑 {@link StorageBackendType#MINIO} 与 {@link StorageBackendType#OSS}。</p>
     */
    @Override
    public boolean supports(StorageBackendType type) {
        return type == StorageBackendType.MINIO || type == StorageBackendType.OSS;
    }

    /**
     * {@inheritDoc}
     * <p>寻址模式随 {@code app.storage.path-style} 配置决定。</p>
     */
    @Override
    public S3Client createClient(ObjectStorageProperties properties) {
        return build(properties, properties.isPathStyle());
    }
}
