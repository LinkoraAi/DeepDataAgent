package com.linkroa.deepdataagent.shared.storage.config;

import com.linkroa.deepdataagent.shared.storage.provider.StorageBackendType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * {@link ObjectStorageProperties} 配置解析与分支 fail-fast 单测。
 */
class ObjectStoragePropertiesTest {

    @Test
    void should_defaultToLocal_when_newProperties() {
        // given // when
        ObjectStorageProperties props = new ObjectStorageProperties();

        // then（默认 local，零外部依赖）
        assertThat(props.resolveType()).isEqualTo(StorageBackendType.LOCAL);
        assertThat(props.getBucket()).isEqualTo("deepdataagent");
    }

    @Test
    void should_passValidation_when_validate_given_localWithRoot() {
        // given
        ObjectStorageProperties props = new ObjectStorageProperties();
        props.setType("local");
        props.getLocal().setRoot("./data/objects");

        // when // then
        assertDoesNotThrow(props::validate);
    }

    @Test
    void should_failFast_when_validate_given_localWithoutRoot() {
        // given
        ObjectStorageProperties props = new ObjectStorageProperties();
        props.getLocal().setRoot("  ");

        // when // then
        assertThatThrownBy(props::validate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void should_failFast_when_validate_given_s3WithoutEndpointOrCredentials() {
        // given（缺 endpoint）
        ObjectStorageProperties noEndpoint = s3Props();
        noEndpoint.setEndpoint("");
        // given（缺密钥）
        ObjectStorageProperties noSecret = s3Props();
        noSecret.setSecretKey("");

        // when // then
        assertThatThrownBy(noEndpoint::validate).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(noSecret::validate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void should_passValidation_when_validate_given_s3Complete() {
        // given
        ObjectStorageProperties props = s3Props();

        // when // then（合法桶名 + 齐全连接参数通过）
        assertDoesNotThrow(props::validate);
    }

    @Test
    void should_failFast_when_validate_given_illegalBucketName() {
        // given（含下划线，违反 S3 桶名字符集）
        ObjectStorageProperties props = s3Props();
        props.setBucket("Bad_Bucket");

        // when // then
        assertThatThrownBy(props::validate).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void should_resolveTypeCaseInsensitive_when_resolveType_given_mixedCase() {
        // given
        ObjectStorageProperties props = new ObjectStorageProperties();
        props.setType("RustFS");

        // when // then
        assertThat(props.resolveType()).isEqualTo(StorageBackendType.RUSTFS);
    }

    @Test
    void should_failFast_when_resolveType_given_blankOrUnknown() {
        // given
        ObjectStorageProperties blank = new ObjectStorageProperties();
        blank.setType("  ");
        ObjectStorageProperties unknown = new ObjectStorageProperties();
        unknown.setType("webdav");

        // when // then
        assertThatThrownBy(blank::resolveType).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(unknown::resolveType).isInstanceOf(IllegalStateException.class);
    }

    /** 构造一份连接参数齐全的 s3 配置夹具。 */
    private static ObjectStorageProperties s3Props() {
        ObjectStorageProperties props = new ObjectStorageProperties();
        props.setType("minio");
        props.setBucket("deepdataagent");
        props.setEndpoint("http://localhost:9000");
        props.setAccessKey("ak");
        props.setSecretKey("sk");
        props.setRegion("us-east-1");
        return props;
    }
}
