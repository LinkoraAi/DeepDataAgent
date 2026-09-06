package com.linkroa.deepdataagent.storage.infrastructure.provider;

import com.linkroa.deepdataagent.storage.domain.exception.BucketConflictException;
import com.linkroa.deepdataagent.storage.domain.exception.BucketNotFoundException;
import com.linkroa.deepdataagent.storage.domain.exception.FileKeyConflictException;
import com.linkroa.deepdataagent.storage.domain.exception.FileObjectStorageException;
import com.linkroa.deepdataagent.storage.domain.model.FileMetadata;
import com.linkroa.deepdataagent.shared.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link S3FileObjectStorage} 单元测试：
 * 对象方法显式桶透传、桶生命周期（建/删/查）与 S3 错误码细分翻译
 * （NoSuchBucket → 404 桶不存在；NoSuchKey → 404 对象不存在；
 * BucketNotEmpty / BucketAlreadyExists / BucketAlreadyOwnedByYou → 409 冲突）。
 */
@ExtendWith(MockitoExtension.class)
class S3FileObjectStorageTest {

    private static final String TEST_BUCKET = "test-bucket";

    private static final String OBJECT_KEY = "module/biz/202609/a.txt";

    @Mock
    private S3ClientProviderRegistry registry;

    @Mock
    private S3Client s3Client;

    private S3FileObjectStorage storage;

    @BeforeEach
    void setUp() {
        when(registry.getClient()).thenReturn(s3Client);
        storage = new S3FileObjectStorage(registry);
    }

    // ==================== 对象方法：桶显式透传 ====================

    @Test
    void should_putWithBucketAndAtomicCondition_when_put_givenForceFalse() {
        storage.put(TEST_BUCKET, OBJECT_KEY, new ByteArrayInputStream(new byte[]{1}), 1L, "text/plain", false);

        verify(s3Client).putObject(
                argThat((PutObjectRequest request) -> request.bucket().equals(TEST_BUCKET)
                        && request.key().equals(OBJECT_KEY)
                        && "*".equals(request.ifNoneMatch())),
                any(RequestBody.class));
    }

    @Test
    void should_putWithoutCondition_when_put_givenForceTrue() {
        storage.put(TEST_BUCKET, OBJECT_KEY, new ByteArrayInputStream(new byte[]{1}), 1L, "text/plain", true);

        verify(s3Client).putObject(
                argThat((PutObjectRequest request) -> request.ifNoneMatch() == null),
                any(RequestBody.class));
    }

    @Test
    void should_passBucketToRequest_when_get_givenBucket() {
        ResponseInputStream<GetObjectResponse> response = mock(ResponseInputStream.class);
        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(response);

        storage.get(TEST_BUCKET, OBJECT_KEY);

        verify(s3Client).getObject(argThat(
                (GetObjectRequest request) -> request.bucket().equals(TEST_BUCKET)
                        && request.key().equals(OBJECT_KEY)));
    }

    @Test
    void should_throwBucketNotFound_when_get_givenNoSuchBucket() {
        when(s3Client.getObject(any(GetObjectRequest.class)))
                .thenThrow(s3Exception(404, "NoSuchBucket"));

        assertThrows(BucketNotFoundException.class, () -> storage.get(TEST_BUCKET, OBJECT_KEY));
    }

    @Test
    void should_throwResourceNotFound_when_get_givenNoSuchKey() {
        when(s3Client.getObject(any(GetObjectRequest.class)))
                .thenThrow(s3Exception(404, "NoSuchKey"));

        assertThrows(ResourceNotFoundException.class, () -> storage.get(TEST_BUCKET, OBJECT_KEY));
    }

    @Test
    void should_throwResourceNotFound_when_get_givenMissingObject() {
        when(s3Client.getObject(any(GetObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(404).message("Not Found").build());

        assertThrows(ResourceNotFoundException.class, () -> storage.get(TEST_BUCKET, OBJECT_KEY));
    }

    @Test
    void should_throwFileKeyConflict_when_put_givenPreconditionFailed() {
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(S3Exception.builder().statusCode(412).message("Precondition Failed").build());

        assertThrows(FileKeyConflictException.class,
                () -> storage.put(TEST_BUCKET, OBJECT_KEY, new ByteArrayInputStream(new byte[]{1}), 1L,
                        "text/plain", false));
    }

    @Test
    void should_throwFileObjectStorageException_when_get_givenServerError() {
        when(s3Client.getObject(any(GetObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(500).message("Internal Error").build());

        assertThrows(FileObjectStorageException.class, () -> storage.get(TEST_BUCKET, OBJECT_KEY));
    }

    @Test
    void should_returnFalse_when_exists_givenMissingObject() {
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(404).message("Not Found").build());

        assertFalse(storage.exists(TEST_BUCKET, OBJECT_KEY));
    }

    @Test
    void should_returnTrue_when_exists_givenPresentObject() {
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentLength(10L).build());

        assertTrue(storage.exists(TEST_BUCKET, OBJECT_KEY));
    }

    @Test
    void should_returnMetadata_when_head_givenPresentObject() {
        Instant lastModified = Instant.parse("2026-09-06T10:00:00Z");
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder()
                        .contentLength(2048L)
                        .contentType("application/pdf")
                        .lastModified(lastModified)
                        .eTag("\"abc123\"")
                        .build());

        FileMetadata metadata = storage.head(TEST_BUCKET, OBJECT_KEY);

        assertEquals(OBJECT_KEY, metadata.objectKey());
        assertEquals(2048L, metadata.size());
        assertEquals("application/pdf", metadata.contentType());
        assertEquals(lastModified, metadata.lastModified());
        assertEquals("\"abc123\"", metadata.etag());
    }

    @Test
    void should_throwResourceNotFound_when_head_givenMissingObject() {
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(404).message("Not Found").build());

        assertThrows(ResourceNotFoundException.class, () -> storage.head(TEST_BUCKET, OBJECT_KEY));
    }

    @Test
    void should_throwBucketNotFound_when_list_givenNoSuchBucket() {
        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenThrow(s3Exception(404, "NoSuchBucket"));

        assertThrows(BucketNotFoundException.class, () -> storage.list(TEST_BUCKET, "module/"));
    }

    @Test
    void should_returnMetadataList_when_list_givenObjects() {
        S3Object first = S3Object.builder().key("module/biz/202609/a.txt").size(100L)
                .lastModified(Instant.parse("2026-09-06T10:00:00Z")).eTag("e1").build();
        S3Object second = S3Object.builder().key("module/biz/202609/b.txt").size(200L)
                .lastModified(Instant.parse("2026-09-06T11:00:00Z")).eTag("e2").build();
        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(ListObjectsV2Response.builder().contents(first, second).build());

        List<FileMetadata> result = storage.list(TEST_BUCKET, "module/biz/202609/");

        assertEquals(2, result.size());
        assertEquals("module/biz/202609/a.txt", result.get(0).objectKey());
        assertEquals(100L, result.get(0).size());
        assertEquals("module/biz/202609/b.txt", result.get(1).objectKey());
    }

    @Test
    void should_returnEmptyList_when_list_givenNoObjects() {
        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(ListObjectsV2Response.builder().build());

        List<FileMetadata> result = storage.list(TEST_BUCKET, "empty/");

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void should_deleteObjectInBucket_when_delete_givenKey() {
        storage.delete(TEST_BUCKET, OBJECT_KEY);

        verify(s3Client).deleteObject(argThat(
                (DeleteObjectRequest request) -> request.bucket().equals(TEST_BUCKET)
                        && request.key().equals(OBJECT_KEY)));
    }

    // ==================== 桶生命周期 ====================

    @Test
    void should_createBucket_when_createBucket_givenName() {
        storage.createBucket(TEST_BUCKET);

        verify(s3Client).createBucket(
                argThat((CreateBucketRequest request) -> request.bucket().equals(TEST_BUCKET)));
    }

    @Test
    void should_throwBucketConflict_when_createBucket_givenBucketOwnedByYou() {
        when(s3Client.createBucket(any(CreateBucketRequest.class)))
                .thenThrow(s3Exception(409, "BucketAlreadyOwnedByYou"));

        BucketConflictException ex = assertThrows(BucketConflictException.class,
                () -> storage.createBucket(TEST_BUCKET));

        assertTrue(ex.getMessage().contains(TEST_BUCKET));
    }

    @Test
    void should_throwBucketConflict_when_createBucket_givenBucketAlreadyExists() {
        when(s3Client.createBucket(any(CreateBucketRequest.class)))
                .thenThrow(s3Exception(409, "BucketAlreadyExists"));

        assertThrows(BucketConflictException.class, () -> storage.createBucket(TEST_BUCKET));
    }

    @Test
    void should_deleteBucket_when_deleteBucket_givenEmptyBucket() {
        storage.deleteBucket(TEST_BUCKET);

        verify(s3Client).deleteBucket(
                argThat((DeleteBucketRequest request) -> request.bucket().equals(TEST_BUCKET)));
    }

    @Test
    void should_throwBucketConflict_when_deleteBucket_givenNotEmptyBucket() {
        when(s3Client.deleteBucket(any(DeleteBucketRequest.class)))
                .thenThrow(s3Exception(409, "BucketNotEmpty"));

        assertThrows(BucketConflictException.class, () -> storage.deleteBucket(TEST_BUCKET));
    }

    @Test
    void should_throwBucketNotFound_when_deleteBucket_givenNoSuchBucket() {
        when(s3Client.deleteBucket(any(DeleteBucketRequest.class)))
                .thenThrow(s3Exception(404, "NoSuchBucket"));

        assertThrows(BucketNotFoundException.class, () -> storage.deleteBucket(TEST_BUCKET));
    }

    @Test
    void should_throwBucketNotFound_when_deleteBucket_givenBare404() {
        when(s3Client.deleteBucket(any(DeleteBucketRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(404).message("Not Found").build());

        assertThrows(BucketNotFoundException.class, () -> storage.deleteBucket(TEST_BUCKET));
    }

    @Test
    void should_returnTrue_when_bucketExists_givenPresentBucket() {
        when(s3Client.headBucket(any(HeadBucketRequest.class))).thenReturn(null);

        assertTrue(storage.bucketExists(TEST_BUCKET));
    }

    @Test
    void should_returnFalse_when_bucketExists_givenNoSuchBucket() {
        when(s3Client.headBucket(any(HeadBucketRequest.class)))
                .thenThrow(s3Exception(404, "NoSuchBucket"));

        assertFalse(storage.bucketExists(TEST_BUCKET));
    }

    @Test
    void should_passBucket_when_bucketExists_givenName() {
        storage.bucketExists(TEST_BUCKET);

        verify(s3Client).headBucket(
                argThat((HeadBucketRequest request) -> request.bucket().equals(TEST_BUCKET)));
    }

    /**
     * 构造带指定状态码与错误码的 {@link S3Exception}（模拟 RustFS 存储服务响应）。
     *
     * @param statusCode HTTP 状态码
     * @param errorCode  S3 错误码
     * @return S3 异常
     */
    private S3Exception s3Exception(int statusCode, String errorCode) {
        S3Exception.Builder builder = S3Exception.builder().statusCode(statusCode);
        builder.awsErrorDetails(AwsErrorDetails.builder().errorCode(errorCode).build());
        return (S3Exception) builder.build();
    }
}