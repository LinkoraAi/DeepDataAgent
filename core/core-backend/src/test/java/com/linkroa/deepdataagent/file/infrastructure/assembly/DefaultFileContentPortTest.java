package com.linkroa.deepdataagent.file.infrastructure.assembly;

import com.linkroa.deepdataagent.file.domain.model.File;
import com.linkroa.deepdataagent.file.domain.model.enums.FilePurpose;
import com.linkroa.deepdataagent.file.domain.repository.FileRepository;
import com.linkroa.deepdataagent.shared.storage.ObjectStorageErrorKind;
import com.linkroa.deepdataagent.shared.storage.ObjectStorageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DefaultFileContentPort} 双载体编排单测：key 规划、先对象后元数据及失败补偿、
 * 删除同删（对象失败仅告警）、下载缺失翻译为空、挂载物化流式摘要。
 */
@ExtendWith(MockitoExtension.class)
class DefaultFileContentPortTest {

    private static final byte[] CONTENT = "hello 文件内容".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path tempDir;

    @Mock
    private FileRepository fileRepository;
    @Mock
    private com.linkroa.deepdataagent.shared.storage.ObjectStorage objectStorage;

    private DefaultFileContentPort port;

    @BeforeEach
    void setUp() {
        port = new DefaultFileContentPort();
        ReflectionTestUtils.setField(port, "fileRepository", fileRepository);
        ReflectionTestUtils.setField(port, "objectStorage", objectStorage);
    }

    private File newFile(String fileId) {
        return File.create(fileId, 1L, "a.txt", "text/plain",
                FilePurpose.USER_UPLOAD, null, null, CONTENT);
    }

    @Test
    void should_putObjectThenInsertMetadata_when_register_given_newFile() {
        // given
        File file = newFile("file_1");
        when(fileRepository.save(any(File.class))).thenAnswer(inv -> inv.getArgument(0));

        // when
        File saved = port.register(file, CONTENT);

        // then（对象 key 恒为 files/<file_id>，携带正确大小 / MIME；随后元数据入库）
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(objectStorage).put(keyCaptor.capture(), any(InputStream.class),
                eq((long) CONTENT.length), eq("text/plain"));
        assertThat(keyCaptor.getValue()).isEqualTo("files/file_1");
        verify(fileRepository).save(file);
        assertThat(saved.fileId()).isEqualTo("file_1");
        verify(objectStorage, never()).delete(anyString());
    }

    @Test
    void should_compensateDeleteObjectAndRethrow_when_register_given_metadataInsertFails() {
        // given（元数据入库失败）
        File file = newFile("file_2");
        when(fileRepository.save(any(File.class))).thenThrow(new RuntimeException("db down"));

        // when & then（原样上抛，且补偿删除已写对象）
        assertThrows(RuntimeException.class, () -> port.register(file, CONTENT));
        verify(objectStorage).delete("files/file_2");
    }

    @Test
    void should_logicalDeleteMetadataAndDeleteObject_when_deleteFile_given_fileId() {
        // given
        when(fileRepository.deleteByFileId("file_1")).thenReturn(1);

        // when
        int rows = port.deleteFile("file_1");

        // then
        assertThat(rows).isEqualTo(1);
        verify(fileRepository).deleteByFileId("file_1");
        verify(objectStorage).delete("files/file_1");
    }

    @Test
    void should_notRollbackMetadataAndNotThrow_when_deleteFile_given_objectDeleteFails() {
        // given（元数据逻辑删成功，但对象删除失败）
        when(fileRepository.deleteByFileId("file_1")).thenReturn(1);
        doThrow(new ObjectStorageException(ObjectStorageErrorKind.UNAVAILABLE, "x"))
                .when(objectStorage).delete("files/file_1");

        // when & then（仅告警、返回元数据行数，不回滚、不上抛）
        assertThat(assertDoesNotThrow(() -> port.deleteFile("file_1"))).isEqualTo(1);
    }

    @Test
    void should_returnBytes_when_readContent_given_objectExists() {
        // given
        when(objectStorage.get("files/file_1"))
                .thenReturn(new ByteArrayInputStream(CONTENT));

        // when
        Optional<byte[]> read = port.readContent("file_1");

        // then
        assertThat(read).isPresent();
        assertThat(read.get()).isEqualTo(CONTENT);
    }

    @Test
    void should_returnEmpty_when_readContent_given_objectNotFound() {
        // given（记录在、对象缺 → NOT_FOUND 翻译为空，由调用方映射一致性事故）
        when(objectStorage.get("files/file_gone"))
                .thenThrow(new ObjectStorageException(ObjectStorageErrorKind.NOT_FOUND, "x"));

        // when
        Optional<byte[]> read = port.readContent("file_gone");

        // then
        assertThat(read).isEmpty();
    }

    @Test
    void should_streamToHostAndReturnDigest_when_copyToHost_given_objectExists() throws Exception {
        // given
        File file = newFile("file_1");
        Path target = tempDir.resolve("out").resolve("file_1");
        when(objectStorage.get("files/file_1")).thenReturn(new ByteArrayInputStream(CONTENT));

        // when
        Optional<String> digest = port.copyToHost("file_1", target);

        // then（原子就位、内容一致、摘要等于登记 SHA-256）
        assertThat(digest).contains(file.contentSha256());
        assertThat(Files.exists(target)).isTrue();
        assertThat(Files.readAllBytes(target)).isEqualTo(CONTENT);
    }

    @Test
    void should_returnEmpty_when_copyToHost_given_objectNotFound() {
        // given
        Path target = tempDir.resolve("out").resolve("file_gone");
        when(objectStorage.get("files/file_gone"))
                .thenThrow(new ObjectStorageException(ObjectStorageErrorKind.NOT_FOUND, "x"));

        // when
        Optional<String> digest = port.copyToHost("file_gone", target);

        // then（缺失收敛为空，不留半成品副本）
        assertThat(digest).isEmpty();
        assertThat(Files.exists(target)).isFalse();
    }

    @Test
    void should_notInvokeStorage_when_register_given_putNotReachedForSizeZeroProbe() {
        // given（防御性断言：register 总是先 put；0 字节也走对象写入）
        File empty = File.create("file_0", 1L, "e.txt", "text/plain",
                FilePurpose.USER_UPLOAD, null, null, new byte[0]);
        when(fileRepository.save(any(File.class))).thenAnswer(inv -> inv.getArgument(0));

        // when
        port.register(empty, new byte[0]);

        // then
        verify(objectStorage).put(eq("files/file_0"), any(InputStream.class), anyLong(), eq("text/plain"));
    }
}
