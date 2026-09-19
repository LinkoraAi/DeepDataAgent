package com.linkroa.deepdataagent.shared.storage.provider;

import com.linkroa.deepdataagent.shared.storage.config.ObjectStorageProperties;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * RustFS 存储客户端策略（S3 兼容协议）。
 * <p>RustFS 仅支持 path-style 寻址（endpoint/bucket/key），区域不校验（占位传入），
 * 故 {@code forcePathStyle} 恒为 {@code true}，组装差异不渗入业务层。</p>
 */
@Component
public class RustFsS3ClientProvider extends AbstractS3ClientProvider {

    /**
     * {@inheritDoc}
     * <p>本策略仅支持 {@link StorageBackendType#RUSTFS}。</p>
     */
    @Override
    public boolean supports(StorageBackendType type) {
        return type == StorageBackendType.RUSTFS;
    }

    /**
     * {@inheritDoc}
     * <p>RustFS 仅支持 path-style，忽略配置取值，恒强制开启。</p>
     */
    @Override
    public S3Client createClient(ObjectStorageProperties properties) {
        return build(properties, true);
    }
}
