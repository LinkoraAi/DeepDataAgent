package com.linkroa.deepdataagent.shared.storage.provider;

import com.linkroa.deepdataagent.shared.storage.ObjectStorage;
import com.linkroa.deepdataagent.shared.storage.config.ObjectStorageProperties;
import com.linkroa.deepdataagent.shared.storage.s3.S3ObjectStorage;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;

import java.util.List;

/**
 * S3 系对象存储装配策略：支撑 {@link StorageBackendType#RUSTFS} / {@code MINIO} / {@code OSS}。
 * <p>先按子类型经 {@link S3ClientProvider} 策略组装 {@link S3Client}（寻址差异在此收敛），
 * 再包裹为协议中立的 {@link S3ObjectStorage}。</p>
 */
@Component
public class S3ObjectStorageProvider implements ObjectStorageProvider {

    /** S3 客户端组装策略集合（RustFs / 通用 MinIO·OSS）。 */
    private final List<S3ClientProvider> clientProviders;

    /**
     * @param clientProviders Spring 注入的全部 S3 客户端策略
     */
    public S3ObjectStorageProvider(List<S3ClientProvider> clientProviders) {
        this.clientProviders = clientProviders;
    }

    /** {@inheritDoc} 本策略支撑所有 S3 兼容后端（{@link StorageBackendType#isS3()}）。 */
    @Override
    public boolean supports(StorageBackendType type) {
        return type.isS3();
    }

    /**
     * {@inheritDoc}
     * <p>先按子类型经 {@link S3ClientProvider} 组装 {@link S3Client}，再包裹为
     * 协议中立的 {@link S3ObjectStorage}（不发起网络请求，桶就绪由装配期统一触发）。</p>
     */
    @Override
    public ObjectStorage create(ObjectStorageProperties properties) {
        StorageBackendType type = properties.resolveType();
        S3ClientProvider clientProvider = clientProviders.stream()
                .filter(candidate -> candidate.supports(type))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("未注册 S3 客户端策略: " + type));
        S3Client client = clientProvider.createClient(properties);
        return new S3ObjectStorage(client, properties.getBucket());
    }
}
