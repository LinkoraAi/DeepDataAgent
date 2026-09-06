package com.linkroa.deepdataagent.storage.infrastructure.provider;

import com.linkroa.deepdataagent.storage.domain.model.enums.StorageType;
import com.linkroa.deepdataagent.storage.infrastructure.config.FileObjectStorageProperties;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * S3 客户端提供者注册表（策略 + 枚举工厂）。
 * <p>启动时按 {@code supports(type)} 注册全部 {@link S3ClientProvider} 实现；
 * {@code resolve} 按存储类型在运行时路由；{@code getClient} 按当前配置类型
 * 懒加载并缓存唯一 {@link S3Client} 单例（双重检查锁保证线程安全）。
 * 新增 Provider = 加枚举值 + 加策略类（组件扫描自动注册），无需改动本类。</p>
 */
@Component
public class S3ClientProviderRegistry {

    private final Map<StorageType, S3ClientProvider> providers = new EnumMap<>(StorageType.class);

    private final FileObjectStorageProperties properties;

    /** 懒加载缓存的唯一 S3 客户端单例（volatile 保证双检锁可见性） */
    private volatile S3Client cachedClient;

    /**
     * 构造时注册全部策略实现。
     *
     * @param properties    对象存储配置（决定路由目标类型）
     * @param providerBeans 由 Spring 注入的全部 S3ClientProvider 实现
     */
    public S3ClientProviderRegistry(FileObjectStorageProperties properties, List<S3ClientProvider> providerBeans) {
        this.properties = properties;
        for (S3ClientProvider provider : providerBeans) {
            for (StorageType type : StorageType.values()) {
                if (provider.supports(type)) {
                    providers.put(type, provider);
                }
            }
        }
    }

    /**
     * 按存储类型路由到对应 Provider 策略。
     *
     * @param type 存储 Provider 类型
     * @return 对应策略实现
     * @throws IllegalArgumentException 未注册该类型的策略
     */
    public S3ClientProvider resolve(StorageType type) {
        S3ClientProvider provider = providers.get(type);
        if (provider == null) {
            throw new IllegalArgumentException("不支持的存储 Provider: " + type);
        }
        return provider;
    }

    /**
     * 获取当前配置类型对应的 S3 客户端（懒加载 + 双检锁单例缓存）。
     *
     * @return 全局唯一的 S3 客户端
     */
    public S3Client getClient() {
        S3Client client = cachedClient;
        if (client == null) {
            synchronized (this) {
                client = cachedClient;
                if (client == null) {
                    StorageType type = properties.resolveType();
                    client = resolve(type).createClient(properties);
                    cachedClient = client;
                }
            }
        }
        return client;
    }
}