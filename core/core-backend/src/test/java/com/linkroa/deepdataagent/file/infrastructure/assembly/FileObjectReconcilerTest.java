package com.linkroa.deepdataagent.file.infrastructure.assembly;

import com.linkroa.deepdataagent.file.domain.model.File;
import com.linkroa.deepdataagent.file.domain.model.enums.FilePurpose;
import com.linkroa.deepdataagent.file.domain.repository.FileRepository;
import com.linkroa.deepdataagent.shared.storage.ObjectMetadata;
import com.linkroa.deepdataagent.shared.storage.ObjectStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link FileObjectReconciler} 启动对账单测：无<b>存活</b>元数据行的对象回收、
 * 存活行保留、畸形 key 跳过、列举 / 单项失败容错不阻断。
 */
@ExtendWith(MockitoExtension.class)
class FileObjectReconcilerTest {

    @Mock
    private ObjectStorage objectStorage;
    @Mock
    private FileRepository fileRepository;

    private FileObjectReconciler reconciler;

    @BeforeEach
    void setUp() {
        reconciler = new FileObjectReconciler();
        ReflectionTestUtils.setField(reconciler, "objectStorage", objectStorage);
        ReflectionTestUtils.setField(reconciler, "fileRepository", fileRepository);
    }

    private ObjectMetadata object(String key) {
        return new ObjectMetadata(key, 1L, null, null, null);
    }

    private File liveFile(String fileId) {
        return File.create(fileId, 1L, "a.txt", "text/plain",
                FilePurpose.USER_UPLOAD, null, null, "x".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void should_deleteOnlyOrphans_when_reconcileOnce_given_mixedLiveAndMissingRows() {
        // given（存活行 / 无行孤儿 / 畸形 key 混合）
        when(objectStorage.list("files/")).thenReturn(List.of(
                object("files/file_keep"),
                object("files/file_orphan"),
                object("files/not-a-file-id"),
                object("files/dir/file_nested")));
        when(fileRepository.findByFileId("file_keep")).thenReturn(Optional.of(liveFile("file_keep")));
        when(fileRepository.findByFileId("file_orphan")).thenReturn(Optional.empty());

        // when
        reconciler.reconcileOnce();

        // then（仅孤儿对象删除；存活保留；畸形 key 不查表不删除）
        verify(objectStorage).delete("files/file_orphan");
        verify(objectStorage, never()).delete("files/file_keep");
        verify(objectStorage, never()).delete("files/not-a-file-id");
        verify(objectStorage, never()).delete("files/dir/file_nested");
        verify(fileRepository, never()).findByFileId("not-a-file-id");
    }

    @Test
    void should_continueAndNotThrow_when_reconcileOnce_given_listFails() {
        // given
        when(objectStorage.list("files/"))
                .thenThrow(new RuntimeException("s3 unavailable"));

        // when & then（列举失败不阻断启动，不做任何删除）
        reconciler.reconcileOnce();
        verify(objectStorage, never()).delete(anyString());
    }

    @Test
    void should_continueNextItem_when_reconcileOnce_given_singleDeleteFails() {
        // given（第一个孤儿删除抛错，第二个孤儿仍处理）
        when(objectStorage.list("files/")).thenReturn(List.of(
                object("files/file_bad"), object("files/file_ok")));
        when(fileRepository.findByFileId(anyString())).thenReturn(Optional.empty());
        org.mockito.Mockito.doThrow(new RuntimeException("delete failed"))
                .when(objectStorage).delete("files/file_bad");

        // when & then（容错不抛，第二个仍删除）
        reconciler.reconcileOnce();
        verify(objectStorage).delete("files/file_ok");
    }
}
