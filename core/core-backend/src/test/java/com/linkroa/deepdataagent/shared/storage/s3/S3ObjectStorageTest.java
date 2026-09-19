package com.linkroa.deepdataagent.shared.storage.s3;

import com.linkroa.deepdataagent.shared.storage.ObjectMetadata;
import com.linkroa.deepdataagent.shared.storage.ObjectStorageErrorKind;
import com.linkroa.deepdataagent.shared.storage.ObjectStorageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link S3ObjectStorage} S3 协议后端单测（mock {@link S3Client}）：上传 / 下载与异常翻译、
 * <b>列举 continuationToken 翻页全量聚合</b>、前缀批量删除、启动桶确保幂等。
 */
class S3ObjectStorageTest {

    private static final String BUCKET = "deepdataagent";

    private S3Client client;
    private S3ObjectStorage storage;

    @BeforeEach
    void setUp() {
        client = mock(S3Client.class);
        storage = new S3ObjectStorage(client, BUCKET);
    }

    private static S3Exception s3Error(int status, String code) {
        return (S3Exception) S3Exception.builder().statusCode(status).message(code)
                .awsErrorDetails(software.amazon.awssdk.awscore.exception.AwsErrorDetails.builder()
                        .errorCode(code).build())
                .build();
    }

    @Test
    void should_putObject_when_put_given_streamAndSize() {
        // given
        byte[] body = "hello".getBytes(StandardCharsets.UTF_8);

        // when
        storage.put("files/file_1", new ByteArrayInputStream(body), body.length, "text/plain");

        // then
        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).putObject(captor.capture(), any(RequestBody.class));
        assertThat(captor.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(captor.getValue().key()).isEqualTo("files/file_1");
        assertThat(captor.getValue().contentType()).isEqualTo("text/plain");
        assertThat(captor.getValue().contentLength()).isEqualTo(body.length);
    }

    @Test
    void should_defaultContentType_when_put_given_nullContentType() {
        // given
        byte[] body = "x".getBytes(StandardCharsets.UTF_8);

        // when
        storage.put("files/file_1", new ByteArrayInputStream(body), body.length, null);

        // then
        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).putObject(captor.capture(), any(RequestBody.class));
        assertThat(captor.getValue().contentType()).isEqualTo("application/octet-stream");
    }

    @Test
    void should_returnStream_when_get_given_objectExists() throws Exception {
        // given
        byte[] body = "abc".getBytes(StandardCharsets.UTF_8);
        when(client.getObject(any(GetObjectRequest.class)))
                .thenReturn(new ResponseInputStream<>(GetObjectResponse.builder().build(),
                        new ByteArrayInputStream(body)));

        // when
        try (InputStream in = storage.get("files/file_1")) {
            // then
            assertThat(in.readAllBytes()).isEqualTo(body);
        }
    }

    @Test
    void should_translateNotFound_when_get_given_noSuchKey() {
        // given
        when(client.getObject(any(GetObjectRequest.class))).thenThrow(s3Error(404, "NoSuchKey"));

        // when // then
        assertThatThrownBy(() -> storage.get("files/file_gone"))
                .isInstanceOfSatisfying(ObjectStorageException.class,
                        ex -> assertThat(ex.kind()).isEqualTo(ObjectStorageErrorKind.NOT_FOUND));
    }

    @Test
    void should_translateUnavailable_when_get_given_connectionFailure() {
        // given
        when(client.getObject(any(GetObjectRequest.class)))
                .thenThrow(SdkClientException.create("connection refused"));

        // when // then
        assertThatThrownBy(() -> storage.get("files/file_1"))
                .isInstanceOfSatisfying(ObjectStorageException.class,
                        ex -> assertThat(ex.kind()).isEqualTo(ObjectStorageErrorKind.UNAVAILABLE));
    }

    @Test
    void should_aggregateAllPages_when_list_given_truncatedResponse() {
        // given（首页 truncated + token，次页 truncated=false，模拟 >单页 的全量列举）
        ListObjectsV2Response page1 = ListObjectsV2Response.builder()
                .isTruncated(true)
                .nextContinuationToken("next-token")
                .contents(List.of(S3Object.builder().key("skills/skill_x/1/SKILL.md").size(3L).build()))
                .build();
        ListObjectsV2Response page2 = ListObjectsV2Response.builder()
                .isTruncated(false)
                .contents(List.of(S3Object.builder().key("skills/skill_x/1/refs/a.md").size(2L).build()))
                .build();
        when(client.listObjectsV2(any(ListObjectsV2Request.class))).thenAnswer(invocation -> {
            ListObjectsV2Request request = invocation.getArgument(0);
            return request.continuationToken() == null ? page1 : page2;
        });

        // when
        List<ObjectMetadata> result = storage.list("skills/skill_x/1/");

        // then（两页对象全量聚合，且第二页携带首页 token）
        assertThat(result).hasSize(2)
                .extracting(ObjectMetadata::key)
                .containsExactly("skills/skill_x/1/SKILL.md", "skills/skill_x/1/refs/a.md");
        ArgumentCaptor<ListObjectsV2Request> captor = ArgumentCaptor.forClass(ListObjectsV2Request.class);
        verify(client, org.mockito.Mockito.times(2)).listObjectsV2(captor.capture());
        List<ListObjectsV2Request> requests = captor.getAllValues();
        assertThat(requests.get(0).continuationToken()).isNull();
        assertThat(requests.get(1).continuationToken()).isEqualTo("next-token");
        assertThat(requests.get(1).prefix()).isEqualTo("skills/skill_x/1/");
    }

    @Test
    void should_batchDeleteObjects_when_deletePrefix_given_keysPresent() {
        // given（列举返回 2 个对象）
        when(client.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(ListObjectsV2Response.builder().isTruncated(false).contents(List.of(
                        S3Object.builder().key("skills/s/1/SKILL.md").size(1L).build(),
                        S3Object.builder().key("skills/s/1/a.md").size(1L).build())).build());

        // when
        storage.deletePrefix("skills/s/1/");

        // then（一次 DeleteObjects 批量携带 2 个 key）
        ArgumentCaptor<DeleteObjectsRequest> captor = ArgumentCaptor.forClass(DeleteObjectsRequest.class);
        verify(client).deleteObjects(captor.capture());
        assertThat(captor.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(captor.getValue().delete().objects()).hasSize(2)
                .extracting(software.amazon.awssdk.services.s3.model.ObjectIdentifier::key)
                .containsExactlyInAnyOrder("skills/s/1/SKILL.md", "skills/s/1/a.md");
    }

    @Test
    void should_createBucketOnce_when_ensureReady_given_bucketAbsent() {
        // given（headBucket 404：桶不存在）
        when(client.headBucket(any(HeadBucketRequest.class))).thenThrow(s3Error(404, "NoSuchBucket"));

        // when
        storage.ensureReady();

        // then（自动建桶一次）
        verify(client).createBucket(any(CreateBucketRequest.class));
    }

    @Test
    void should_skipCreate_when_ensureReady_given_bucketPresent() {
        // given（headBucket 正常返回，不抛异常即桶存在）

        // when
        storage.ensureReady();

        // then
        verify(client, never()).createBucket(any(CreateBucketRequest.class));
    }

    @Test
    void should_validateKey_when_put_given_traversalKey() {
        // given // when // then（技术 key 校验先于协议调用）
        assertThatThrownBy(() -> storage.put("../escape", new ByteArrayInputStream(new byte[0]), 0, null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }
}
