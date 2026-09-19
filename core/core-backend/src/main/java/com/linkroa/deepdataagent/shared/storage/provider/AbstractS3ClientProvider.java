package com.linkroa.deepdataagent.shared.storage.provider;

import com.linkroa.deepdataagent.shared.storage.config.ObjectStorageProperties;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;

/**
 * S3 客户端组装策略基类：收敛各 S3 后端通用的客户端构建逻辑。
 * <p>子类只需通过 {@link #supports(StorageBackendType)} 声明支持的后端类型，
 * 并在 {@link #createClient(ObjectStorageProperties)} 中决定 path-style 取值。</p>
 */
public abstract class AbstractS3ClientProvider implements S3ClientProvider {

    /**
     * 按配置与寻址模式构建 S3 客户端（不发起网络请求）。
     *
     * @param properties 连接配置
     * @param pathStyle  是否强制 path-style 寻址
     * @return 组装完成的 S3 客户端
     */
    protected S3Client build(ObjectStorageProperties properties, boolean pathStyle) {
        return S3Client.builder()
                .endpointOverride(URI.create(properties.getEndpoint()))
                .region(Region.of(properties.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(properties.getAccessKey(), properties.getSecretKey())))
                .forcePathStyle(pathStyle)
                .build();
    }
}
