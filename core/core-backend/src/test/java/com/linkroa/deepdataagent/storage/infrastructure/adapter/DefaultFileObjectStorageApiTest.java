package com.linkroa.deepdataagent.storage.infrastructure.adapter;

import com.linkroa.deepdataagent.storage.application.command.CreateBucketCommand;
import com.linkroa.deepdataagent.storage.application.command.DeleteBucketCommand;
import com.linkroa.deepdataagent.storage.application.command.PutFileObjectCommand;
import com.linkroa.deepdataagent.storage.application.query.BucketExistsQuery;
import com.linkroa.deepdataagent.storage.application.query.ListFileObjectsQuery;
import com.linkroa.deepdataagent.storage.application.service.FileObjectStorageAppService;
import com.linkroa.deepdataagent.storage.domain.model.FileMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DefaultFileObjectStorageApi} 单元测试：
 * 桶三方法（建/删/查）与对象方法的 {@code bucket} 参数委托透传。
 */
@ExtendWith(MockitoExtension.class)
class DefaultFileObjectStorageApiTest {

    private static final String VALID_BUCKET = "test-bucket";

    private static final String OBJECT_KEY = "module/biz/202609/a.txt";

    @Mock
    private FileObjectStorageAppService appService;

    private DefaultFileObjectStorageApi api;

    @BeforeEach
    void setUp() {
        api = new DefaultFileObjectStorageApi(appService);
    }

    @Test
    void should_delegatePut_when_putObject_givenValidArgs() {
        InputStream content = new ByteArrayInputStream(new byte[]{1});
        when(appService.putObject(any(PutFileObjectCommand.class))).thenReturn(OBJECT_KEY);

        String result = api.putObject(VALID_BUCKET, OBJECT_KEY, content, 10L, "text/plain", false);

        assertEquals(OBJECT_KEY, result);
        verify(appService).putObject(commandMatching(VALID_BUCKET, OBJECT_KEY, content, 10L, "text/plain", false));
    }

    @Test
    void should_delegateGet_when_getObject_givenBucketAndKey() {
        InputStream stream = new ByteArrayInputStream(new byte[]{1});
        when(appService.getObject(VALID_BUCKET, OBJECT_KEY)).thenReturn(stream);

        InputStream result = api.getObject(VALID_BUCKET, OBJECT_KEY);

        assertSame(stream, result);
        verify(appService).getObject(VALID_BUCKET, OBJECT_KEY);
    }

    @Test
    void should_delegateHead_when_headObject_givenBucketAndKey() {
        FileMetadata metadata = new FileMetadata(OBJECT_KEY, 100L, "text/plain",
                Instant.parse("2026-09-06T10:00:00Z"), "etag");
        when(appService.headObject(VALID_BUCKET, OBJECT_KEY)).thenReturn(metadata);

        FileMetadata result = api.headObject(VALID_BUCKET, OBJECT_KEY);

        assertSame(metadata, result);
        verify(appService).headObject(VALID_BUCKET, OBJECT_KEY);
    }

    @Test
    void should_delegateList_when_listObjects_givenBucketAndPrefix() {
        List<FileMetadata> list = List.of(new FileMetadata(OBJECT_KEY, 100L, null, null, null));
        when(appService.listObjects(any(ListFileObjectsQuery.class))).thenReturn(list);

        List<FileMetadata> result = api.listObjects(VALID_BUCKET, "module/biz/");

        assertEquals(1, result.size());
        verify(appService).listObjects(new ListFileObjectsQuery(VALID_BUCKET, "module/biz/"));
    }

    @Test
    void should_delegateDelete_when_deleteObject_givenBucketAndKey() {
        api.deleteObject(VALID_BUCKET, OBJECT_KEY);

        verify(appService).deleteObject(VALID_BUCKET, OBJECT_KEY);
    }

    @Test
    void should_delegateCreate_when_createBucket_givenName() {
        api.createBucket(VALID_BUCKET);

        verify(appService).createBucket(new CreateBucketCommand(VALID_BUCKET));
    }

    @Test
    void should_delegateDelete_when_deleteBucket_givenName() {
        api.deleteBucket(VALID_BUCKET);

        verify(appService).deleteBucket(new DeleteBucketCommand(VALID_BUCKET));
    }

    @Test
    void should_delegateExists_when_bucketExists_givenName() {
        when(appService.bucketExists(new BucketExistsQuery(VALID_BUCKET))).thenReturn(true);

        assertEquals(true, api.bucketExists(VALID_BUCKET));
        verify(appService).bucketExists(new BucketExistsQuery(VALID_BUCKET));
    }

    /**
     * 构造与预期入参逐字段一致的 {@link PutFileObjectCommand}（委托透传校验）。
     */
    private PutFileObjectCommand commandMatching(String bucket, String objectKey, InputStream content, long size,
                                                 String contentType, boolean force) {
        return org.mockito.ArgumentMatchers.argThat(cmd -> cmd.bucket().equals(bucket)
                && cmd.objectKey().equals(objectKey)
                && cmd.content() == content
                && cmd.size() == size
                && force == cmd.force()
                && Objects.equals(contentType, cmd.contentType()));
    }
}