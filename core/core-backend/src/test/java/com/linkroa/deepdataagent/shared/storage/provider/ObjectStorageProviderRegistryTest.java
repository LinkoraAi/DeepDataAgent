package com.linkroa.deepdataagent.shared.storage.provider;

import com.linkroa.deepdataagent.shared.storage.ObjectStorage;
import com.linkroa.deepdataagent.shared.storage.config.ObjectStorageProperties;
import com.linkroa.deepdataagent.shared.storage.local.LocalDiskObjectStorage;
import com.linkroa.deepdataagent.shared.storage.s3.S3ObjectStorage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 对象存储后端装配注册表与 S3 客户端策略路由单测。
 */
class ObjectStorageProviderRegistryTest {

    /** 构造含 local + S3（RustFs / 通用）全部策略的注册表。 */
    private static ObjectStorageProviderRegistry fullRegistry() {
        List<S3ClientProvider> s3Clients =
                List.of(new RustFsS3ClientProvider(), new GenericS3ClientProvider());
        return new ObjectStorageProviderRegistry(
                List.of(new LocalObjectStorageProvider(), new S3ObjectStorageProvider(s3Clients)));
    }

    private ObjectStorageProperties localProps() {
        ObjectStorageProperties props = new ObjectStorageProperties();
        props.setType("local");
        props.getLocal().setRoot("./data/objects");
        return props;
    }

    private ObjectStorageProperties s3Props(StorageBackendType type) {
        ObjectStorageProperties props = new ObjectStorageProperties();
        props.setType(type.name().toLowerCase());
        props.setBucket("deepdataagent");
        props.setEndpoint("http://localhost:9000");
        props.setAccessKey("ak");
        props.setSecretKey("sk");
        props.setRegion("us-east-1");
        return props;
    }

    @Test
    void should_createLocalStorage_when_createStorage_given_localType() {
        // given
        ObjectStorageProviderRegistry registry = fullRegistry();

        // when
        ObjectStorage storage = registry.createStorage(localProps());

        // then
        assertThat(storage).isInstanceOf(LocalDiskObjectStorage.class);
    }

    @Test
    void should_createS3Storage_when_createStorage_given_rustfsMinioOss() {
        // given
        ObjectStorageProviderRegistry registry = fullRegistry();

        // when // then（三类 S3 后端均路由到 S3ObjectStorage，createClient 不发起网络请求）
        assertThat(registry.createStorage(s3Props(StorageBackendType.RUSTFS)))
                .isInstanceOf(S3ObjectStorage.class);
        assertThat(registry.createStorage(s3Props(StorageBackendType.MINIO)))
                .isInstanceOf(S3ObjectStorage.class);
        assertThat(registry.createStorage(s3Props(StorageBackendType.OSS)))
                .isInstanceOf(S3ObjectStorage.class);
    }

    @Test
    void should_routeBySupports_when_s3ClientProviders_given_backendTypes() {
        // given // when // then
        S3ClientProvider rustFs = new RustFsS3ClientProvider();
        S3ClientProvider generic = new GenericS3ClientProvider();
        assertThat(rustFs.supports(StorageBackendType.RUSTFS)).isTrue();
        assertThat(rustFs.supports(StorageBackendType.MINIO)).isFalse();
        assertThat(generic.supports(StorageBackendType.MINIO)).isTrue();
        assertThat(generic.supports(StorageBackendType.OSS)).isTrue();
        assertThat(generic.supports(StorageBackendType.LOCAL)).isFalse();
    }

    @Test
    void should_failFast_when_createStorage_given_noProviderRegistered() {
        // given（空策略注册表）
        ObjectStorageProviderRegistry empty = new ObjectStorageProviderRegistry(List.of());

        // when // then
        assertThatThrownBy(() -> empty.createStorage(localProps()))
                .isInstanceOf(IllegalStateException.class);
    }
}
