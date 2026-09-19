package com.linkroa.deepdataagent.shared.storage.provider;

import com.linkroa.deepdataagent.shared.storage.ObjectStorage;
import com.linkroa.deepdataagent.shared.storage.config.ObjectStorageProperties;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 对象存储后端装配策略注册表（枚举工厂）。
 * <p>构造时收集 Spring 注入的全部 {@link ObjectStorageProvider}，按
 * {@link StorageBackendType} 建索引；{@link #createStorage(ObjectStorageProperties)}
 * 按当前配置类型路由创建实例。新增后端 = 新增策略组件，无需改动本类。</p>
 */
@Component
public class ObjectStorageProviderRegistry {

    /** 后端类型 → 装配策略索引。 */
    private final Map<StorageBackendType, ObjectStorageProvider> providers =
            new EnumMap<>(StorageBackendType.class);

    /**
     * @param providerBeans Spring 注入的全部对象存储装配策略
     */
    public ObjectStorageProviderRegistry(List<ObjectStorageProvider> providerBeans) {
        for (ObjectStorageProvider provider : providerBeans) {
            for (StorageBackendType type : StorageBackendType.values()) {
                if (provider.supports(type)) {
                    providers.put(type, provider);
                }
            }
        }
    }

    /**
     * 按配置创建对象存储实例。
     *
     * @param properties 对象存储配置
     * @return 对应后端的对象存储实例
     * @throws IllegalStateException 该后端类型无装配策略（不应发生，类型枚举与策略同步维护）
     */
    public ObjectStorage createStorage(ObjectStorageProperties properties) {
        StorageBackendType type = properties.resolveType();
        ObjectStorageProvider provider = providers.get(type);
        if (provider == null) {
            throw new IllegalStateException("未注册对象存储后端策略: " + type);
        }
        return provider.create(properties);
    }
}
