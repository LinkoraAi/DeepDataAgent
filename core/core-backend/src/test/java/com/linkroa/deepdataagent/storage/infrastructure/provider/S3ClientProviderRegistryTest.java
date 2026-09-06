package com.linkroa.deepdataagent.storage.infrastructure.provider;

import com.linkroa.deepdataagent.storage.domain.model.enums.StorageType;
import com.linkroa.deepdataagent.storage.infrastructure.config.FileObjectStorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.s3.S3Client;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link S3ClientProviderRegistry} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class S3ClientProviderRegistryTest {

    @Mock
    private FileObjectStorageProperties properties;

    @Test
    void should_resolveProvider_when_resolve_givenRegisteredType() {
        S3ClientProvider provider = new RustFsClientProvider();
        S3ClientProviderRegistry registry =
                new S3ClientProviderRegistry(properties, List.of(provider));

        assertSame(provider, registry.resolve(StorageType.RUSTFS));
    }

    @Test
    void should_throwException_when_resolve_givenUnregisteredType() {
        S3ClientProviderRegistry registry =
                new S3ClientProviderRegistry(properties, List.of(new RustFsClientProvider()));

        assertThrows(IllegalArgumentException.class, () -> registry.resolve(StorageType.MINIO));
    }

    @Test
    void should_cacheSingleClient_when_getClient_givenMultipleCalls() {
        when(properties.resolveType()).thenReturn(StorageType.RUSTFS);
        S3Client client = mock(S3Client.class);
        S3ClientProviderRegistry registry =
                new S3ClientProviderRegistry(properties, List.of(new SingleClientProvider(client)));

        S3Client first = registry.getClient();
        S3Client second = registry.getClient();

        assertSame(client, first);
        assertSame(client, second);
    }

    /**
     * 仅支持 RUSTFS 且固定返回同一客户端的假 Provider（用于验证懒加载单例缓存）。
     */
    private static final class SingleClientProvider implements S3ClientProvider {

        private final S3Client client;

        private SingleClientProvider(S3Client client) {
            this.client = client;
        }

        @Override
        public boolean supports(StorageType type) {
            return type == StorageType.RUSTFS;
        }

        @Override
        public S3Client createClient(FileObjectStorageProperties props) {
            return client;
        }
    }
}