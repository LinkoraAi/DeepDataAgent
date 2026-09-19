package com.linkroa.deepdataagent.shared.storage.provider;

import com.linkroa.deepdataagent.shared.storage.ObjectStorage;
import com.linkroa.deepdataagent.shared.storage.config.ObjectStorageProperties;
import com.linkroa.deepdataagent.shared.storage.local.LocalDiskObjectStorage;
import org.springframework.stereotype.Component;

/**
 * 本地磁盘布局装配策略：对应 {@link StorageBackendType#LOCAL}。
 */
@Component
public class LocalObjectStorageProvider implements ObjectStorageProvider {

    /** {@inheritDoc} 本策略仅支持本地磁盘布局 {@link StorageBackendType#LOCAL}。 */
    @Override
    public boolean supports(StorageBackendType type) {
        return type == StorageBackendType.LOCAL;
    }

    /** {@inheritDoc} 按配置本地根目录创建 {@link LocalDiskObjectStorage}。 */
    @Override
    public ObjectStorage create(ObjectStorageProperties properties) {
        return new LocalDiskObjectStorage(properties);
    }
}
