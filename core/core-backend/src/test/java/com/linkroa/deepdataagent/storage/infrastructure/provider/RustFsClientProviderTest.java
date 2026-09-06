package com.linkroa.deepdataagent.storage.infrastructure.provider;

import com.linkroa.deepdataagent.storage.domain.model.enums.StorageType;
import com.linkroa.deepdataagent.storage.infrastructure.config.FileObjectStorageProperties;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RustFsClientProvider} 单元测试。
 */
class RustFsClientProviderTest {

    private final RustFsClientProvider provider = new RustFsClientProvider();

    @Test
    void should_supportRustfs_when_supports_givenRustfsType() {
        assertTrue(provider.supports(StorageType.RUSTFS));
    }

    @Test
    void should_notSupportMinio_when_supports_givenMinioType() {
        assertFalse(provider.supports(StorageType.MINIO));
    }

    @Test
    @SuppressWarnings("resource")
    void should_buildClientWithPathStyle_when_createClient_givenProperties() {
        FileObjectStorageProperties properties = new FileObjectStorageProperties();
        properties.setEndpoint("http://192.168.204.128:9010");
        properties.setRegion("us-east-1");
        properties.setAccessKey("rustfs");
        properties.setSecretKey("rustfs");
        properties.setPathStyle(true);

        S3ClientBuilder builder = mock(S3ClientBuilder.class);
        when(builder.endpointOverride(any(URI.class))).thenReturn(builder);
        when(builder.region(any(Region.class))).thenReturn(builder);
        when(builder.credentialsProvider(any(AwsCredentialsProvider.class))).thenReturn(builder);
        when(builder.forcePathStyle(anyBoolean())).thenReturn(builder);
        S3Client client = mock(S3Client.class);
        when(builder.build()).thenReturn(client);

        try (MockedStatic<S3Client> s3 = mockStatic(S3Client.class)) {
            s3.when(S3Client::builder).thenReturn(builder);

            S3Client created = provider.createClient(properties);

            assertSame(client, created);
            verify(builder).endpointOverride(URI.create("http://192.168.204.128:9010"));
            verify(builder).region(Region.of("us-east-1"));
            // RustFS 仅支持 path-style，组装参数必须固化开启
            verify(builder).forcePathStyle(true);
        }
    }
}