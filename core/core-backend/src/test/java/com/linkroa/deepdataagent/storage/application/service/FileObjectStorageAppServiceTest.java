package com.linkroa.deepdataagent.storage.application.service;

import com.linkroa.deepdataagent.storage.application.command.CreateBucketCommand;
import com.linkroa.deepdataagent.storage.application.command.DeleteBucketCommand;
import com.linkroa.deepdataagent.storage.application.command.PutFileObjectCommand;
import com.linkroa.deepdataagent.storage.application.query.BucketExistsQuery;
import com.linkroa.deepdataagent.storage.application.query.ListFileObjectsQuery;
import com.linkroa.deepdataagent.storage.domain.exception.BucketConflictException;
import com.linkroa.deepdataagent.storage.domain.exception.BucketNotFoundException;
import com.linkroa.deepdataagent.storage.domain.exception.FileKeyConflictException;
import com.linkroa.deepdataagent.storage.domain.model.FileMetadata;
import com.linkroa.deepdataagent.storage.domain.repository.FileObjectStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link FileObjectStorageAppService} 单元测试：
 * 对象方法显式桶校验与透传、桶名校验（非法 400）、桶生命周期编排
 * （重名 409、删除非空 409、桶缺失 404 透出，端口调用校验）。
 */
@ExtendWith(MockitoExtension.class)
class FileObjectStorageAppServiceTest {

    private static final String VALID_BUCKET = "test-bucket";

    private static final String VALID_KEY = "module/biz/202609/a.txt";

    @Mock
    private FileObjectStorage fileObjectStorage;

    private FileObjectStorageAppService appService;

    @BeforeEach
    void setUp() {
        appService = new FileObjectStorageAppService(fileObjectStorage);
    }

    private PutFileObjectCommand command(long size) {
        return new PutFileObjectCommand(VALID_BUCKET, VALID_KEY, new ByteArrayInputStream(new byte[0]), size,
                "text/plain", false);
    }

    // ==================== 对象方法 ====================

    @Test
    void should_putAndReturnKey_when_putObject_givenValidCommand() {
        PutFileObjectCommand cmd = command(10L);

        String objectKey = appService.putObject(cmd);

        assertEquals(VALID_KEY, objectKey);
        verify(fileObjectStorage).put(VALID_BUCKET, VALID_KEY, cmd.content(), 10L, "text/plain", false);
    }

    @Test
    void should_throwException_when_putObject_givenInvalidBucket() {
        PutFileObjectCommand cmd = new PutFileObjectCommand("InvalidBucket", VALID_KEY,
                new ByteArrayInputStream(new byte[0]), 10L, "text/plain", false);

        assertThrows(IllegalArgumentException.class, () -> appService.putObject(cmd));
        verify(fileObjectStorage, never()).put(anyString(), anyString(),
                org.mockito.ArgumentMatchers.any(), anyLong(), anyString(), anyBoolean());
    }

    @Test
    void should_throwException_when_putObject_givenEmptyObjectKey() {
        PutFileObjectCommand cmd = new PutFileObjectCommand(VALID_BUCKET, "",
                new ByteArrayInputStream(new byte[0]), 10L, "text/plain", false);

        assertThrows(IllegalArgumentException.class, () -> appService.putObject(cmd));
    }

    @Test
    void should_throwException_when_putObject_givenOversizedObjectKey() {
        String longKey = "a".repeat(1025);
        PutFileObjectCommand cmd = new PutFileObjectCommand(VALID_BUCKET, longKey,
                new ByteArrayInputStream(new byte[0]), 10L, "text/plain", false);

        assertThrows(IllegalArgumentException.class, () -> appService.putObject(cmd));
    }

    @Test
    void should_throwException_when_putObject_givenIllegalObjectKeyCharacters() {
        PutFileObjectCommand cmd = new PutFileObjectCommand(VALID_BUCKET, "a b/c#.txt",
                new ByteArrayInputStream(new byte[0]), 10L, "text/plain", false);

        assertThrows(IllegalArgumentException.class, () -> appService.putObject(cmd));
    }

    @Test
    void should_throwException_when_putObject_givenPathTraversalSegment() {
        PutFileObjectCommand cmd = new PutFileObjectCommand(VALID_BUCKET, "module/../secret.txt",
                new ByteArrayInputStream(new byte[0]), 10L, "text/plain", false);

        assertThrows(IllegalArgumentException.class, () -> appService.putObject(cmd));
    }

    @Test
    void should_throwException_when_putObject_givenEmptyContent() {
        assertThrows(IllegalArgumentException.class, () -> appService.putObject(command(0L)));
    }

    @Test
    void should_throwConflict_when_putObject_givenExistingKeyAndNotForce() {
        when(fileObjectStorage.exists(VALID_BUCKET, VALID_KEY)).thenReturn(true);

        assertThrows(FileKeyConflictException.class, () -> appService.putObject(command(10L)));
        verify(fileObjectStorage, never()).put(anyString(), anyString(),
                org.mockito.ArgumentMatchers.any(), anyLong(), anyString(), anyBoolean());
    }

    @Test
    void should_putSuccess_when_putObject_givenExistingKeyAndForce() {
        PutFileObjectCommand cmd = new PutFileObjectCommand(VALID_BUCKET, VALID_KEY,
                new ByteArrayInputStream(new byte[0]), 10L, "text/plain", true);

        String objectKey = appService.putObject(cmd);

        assertEquals(VALID_KEY, objectKey);
        verify(fileObjectStorage).put(VALID_BUCKET, VALID_KEY, cmd.content(), 10L, "text/plain", true);
    }

    @Test
    void should_getStream_when_getObject_givenValidBucketAndKey() {
        InputStream stream = new ByteArrayInputStream(new byte[]{1});
        when(fileObjectStorage.get(VALID_BUCKET, VALID_KEY)).thenReturn(stream);

        InputStream result = appService.getObject(VALID_BUCKET, VALID_KEY);

        assertSame(stream, result);
    }

    @Test
    void should_returnMetadata_when_headObject_givenValidBucketAndKey() {
        FileMetadata metadata = new FileMetadata(VALID_KEY, 100L, "text/plain",
                Instant.parse("2026-09-06T10:00:00Z"), "etag");
        when(fileObjectStorage.head(VALID_BUCKET, VALID_KEY)).thenReturn(metadata);

        FileMetadata result = appService.headObject(VALID_BUCKET, VALID_KEY);

        assertSame(metadata, result);
    }

    @Test
    void should_returnList_when_listObjects_givenBucketAndPrefix() {
        when(fileObjectStorage.list(VALID_BUCKET, "module/biz/")).thenReturn(List.of());
        ListFileObjectsQuery query = new ListFileObjectsQuery(VALID_BUCKET, "module/biz/");

        List<FileMetadata> result = appService.listObjects(query);

        assertEquals(0, result.size());
        verify(fileObjectStorage).list(VALID_BUCKET, "module/biz/");
    }

    @Test
    void should_throwException_when_listObjects_givenInvalidBucket() {
        assertThrows(IllegalArgumentException.class,
                () -> appService.listObjects(new ListFileObjectsQuery("InvalidBucket", "module/")));
        verify(fileObjectStorage, never()).list(anyString(), anyString());
    }

    @Test
    void should_deleteObject_when_deleteObject_givenValidBucketAndKey() {
        appService.deleteObject(VALID_BUCKET, VALID_KEY);

        verify(fileObjectStorage).delete(VALID_BUCKET, VALID_KEY);
    }

    @Test
    void should_throwException_when_getObject_givenIllegalKey() {
        assertThrows(IllegalArgumentException.class, () -> appService.getObject(VALID_BUCKET, "../escaped"));
    }

    // ==================== 桶名校验（非法 → 400） ====================

    @Test
    void should_throwException_when_createBucket_givenBlankBucket() {
        assertThrows(IllegalArgumentException.class,
                () -> appService.createBucket(new CreateBucketCommand("  ")));
    }

    @Test
    void should_throwException_when_createBucket_givenTooShortBucket() {
        assertThrows(IllegalArgumentException.class,
                () -> appService.createBucket(new CreateBucketCommand("ab")));
    }

    @Test
    void should_throwException_when_createBucket_givenTooLongBucket() {
        assertThrows(IllegalArgumentException.class,
                () -> appService.createBucket(new CreateBucketCommand("a".repeat(64))));
    }

    @Test
    void should_throwException_when_createBucket_givenUppercaseBucket() {
        assertThrows(IllegalArgumentException.class,
                () -> appService.createBucket(new CreateBucketCommand("TestBucket")));
    }

    @Test
    void should_throwException_when_createBucket_givenIllegalCharacterBucket() {
        assertThrows(IllegalArgumentException.class,
                () -> appService.createBucket(new CreateBucketCommand("bucket#1")));
    }

    @Test
    void should_throwException_when_createBucket_givenLeadingDotOrHyphenBucket() {
        assertThrows(IllegalArgumentException.class,
                () -> appService.createBucket(new CreateBucketCommand(".bucket")));
        assertThrows(IllegalArgumentException.class,
                () -> appService.createBucket(new CreateBucketCommand("-bucket")));
    }

    @Test
    void should_throwException_when_createBucket_givenIpShapeBucket() {
        assertThrows(IllegalArgumentException.class,
                () -> appService.createBucket(new CreateBucketCommand("192.168.1.1")));
    }

    // ==================== 桶生命周期编排 ====================

    @Test
    void should_createBucket_when_createBucket_givenValidName() {
        when(fileObjectStorage.bucketExists(VALID_BUCKET)).thenReturn(false);

        appService.createBucket(new CreateBucketCommand(VALID_BUCKET));

        verify(fileObjectStorage).bucketExists(VALID_BUCKET);
        verify(fileObjectStorage).createBucket(VALID_BUCKET);
    }

    @Test
    void should_throwConflict_when_createBucket_givenBucketAlreadyExists() {
        when(fileObjectStorage.bucketExists(VALID_BUCKET)).thenReturn(true);

        BucketConflictException ex = assertThrows(BucketConflictException.class,
                () -> appService.createBucket(new CreateBucketCommand(VALID_BUCKET)));

        assertTrue(ex.getMessage().contains(VALID_BUCKET));
        verify(fileObjectStorage, never()).createBucket(VALID_BUCKET);
    }

    @Test
    void should_propagateConflict_when_createBucket_givenExistingBucket() {
        doThrow(new BucketConflictException("桶已存在: " + VALID_BUCKET))
                .when(fileObjectStorage).createBucket(VALID_BUCKET);

        assertThrows(BucketConflictException.class,
                () -> appService.createBucket(new CreateBucketCommand(VALID_BUCKET)));
    }

    @Test
    void should_deleteBucket_when_deleteBucket_givenValidName() {
        appService.deleteBucket(new DeleteBucketCommand(VALID_BUCKET));

        verify(fileObjectStorage).deleteBucket(VALID_BUCKET);
    }

    @Test
    void should_propagateNotFound_when_deleteBucket_givenMissingBucket() {
        doThrow(new BucketNotFoundException("桶不存在: " + VALID_BUCKET))
                .when(fileObjectStorage).deleteBucket(VALID_BUCKET);

        assertThrows(BucketNotFoundException.class,
                () -> appService.deleteBucket(new DeleteBucketCommand(VALID_BUCKET)));
    }

    @Test
    void should_propagateConflict_when_deleteBucket_givenNotEmptyBucket() {
        doThrow(new BucketConflictException("桶非空，无法删除: " + VALID_BUCKET))
                .when(fileObjectStorage).deleteBucket(VALID_BUCKET);

        assertThrows(BucketConflictException.class,
                () -> appService.deleteBucket(new DeleteBucketCommand(VALID_BUCKET)));
    }

    @Test
    void should_returnTrue_when_bucketExists_givenExistingBucket() {
        when(fileObjectStorage.bucketExists(VALID_BUCKET)).thenReturn(true);

        assertTrue(appService.bucketExists(new BucketExistsQuery(VALID_BUCKET)));
        verify(fileObjectStorage).bucketExists(VALID_BUCKET);
    }

    @Test
    void should_returnFalse_when_bucketExists_givenMissingBucket() {
        when(fileObjectStorage.bucketExists(VALID_BUCKET)).thenReturn(false);

        assertFalse(appService.bucketExists(new BucketExistsQuery(VALID_BUCKET)));
    }

    @Test
    void should_throwException_when_bucketExists_givenInvalidBucket() {
        assertThrows(IllegalArgumentException.class,
                () -> appService.bucketExists(new BucketExistsQuery("InvalidBucket")));
        verify(fileObjectStorage, never()).bucketExists(anyString());
    }
}