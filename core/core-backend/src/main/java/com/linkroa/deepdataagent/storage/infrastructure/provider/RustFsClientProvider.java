package com.linkroa.deepdataagent.storage.infrastructure.provider;

import com.linkroa.deepdataagent.storage.domain.model.enums.StorageType;
import com.linkroa.deepdataagent.storage.infrastructure.config.FileObjectStorageProperties;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;

/**
 * RustFS 存储 Provider（S3 兼容协议）。
 * <p>RustFS 仅支持 path-style 寻址（endpoint/bucket/key），且不校验区域，
 * region 作为占位值传入；组装参数固化于本策略类，Provider 差异不渗入业务层。</p>
 */
@Component
public class RustFsClientProvider implements S3ClientProvider {

    @Override
    public boolean supports(StorageType type) {
        return type == StorageType.RUSTFS;
    }

    @Override
    public S3Client createClient(FileObjectStorageProperties properties) {
        return S3Client.builder()
                .endpointOverride(URI.create(properties.getEndpoint()))
                .region(Region.of(properties.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(properties.getAccessKey(), properties.getSecretKey())))
                .forcePathStyle(properties.isPathStyle())
                .build();
    }
}