package com.linkroa.deepdataagent.file.infrastructure.assembly;

import com.linkroa.deepdataagent.file.application.dto.FileMountMaterializationDTO;
import com.linkroa.deepdataagent.file.application.port.FileContentPort;
import com.linkroa.deepdataagent.file.domain.model.File;
import com.linkroa.deepdataagent.file.domain.model.enums.FilePurpose;
import com.linkroa.deepdataagent.file.domain.repository.FileRepository;
import com.linkroa.deepdataagent.shared.exception.FileContentIntegrityException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DefaultFileMountMaterializationPort} 宿主物化端口实现单测。
 * <p>覆盖：「归属 + 就绪」门禁短路（不触碰对象复制）/ 流式物化成功返回载荷 /
 * 内容对象缺失收敛为空 / 摘要不符删残缺副本并抛一致性异常。流式复制与摘要由
 * {@link FileContentPort#copyToHost} 承担，本测试以 mock 端口验证协作与门禁。</p>
 */
@ExtendWith(MockitoExtension.class)
class DefaultFileMountMaterializationPortTest {

    private static final byte[] CONTENT = "挂载文件内容".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path tempDir;

    @Mock private FileRepository fileRepository;
    @Mock private FileContentPort fileContentPort;

    private DefaultFileMountMaterializationPort port;

    @BeforeEach
    void setUp() {
        port = new DefaultFileMountMaterializationPort();
        ReflectionTestUtils.setField(port, "fileRepository", fileRepository);
        ReflectionTestUtils.setField(port, "fileContentPort", fileContentPort);
    }

    private File buildOwnedFile() {
        return File.create("file_1", 1L, "a.txt", "text/plain",
                FilePurpose.USER_UPLOAD, null, null, CONTENT);
    }

    @Test
    void should_returnMaterialization_when_materialize_given_gatePassedAndDigestMatches() throws Exception {
        // given（copyToHost 为 mock，预置其应已写出的宿主副本；返回摘要与登记一致）
        Path target = tempDir.resolve("mounts").resolve("file_1");
        Files.createDirectories(target.getParent());
        Files.write(target, CONTENT);
        File file = buildOwnedFile();
        when(fileRepository.findByFileId("file_1")).thenReturn(Optional.of(file));
        when(fileContentPort.copyToHost("file_1", target)).thenReturn(Optional.of(file.contentSha256()));

        // when
        Optional<FileMountMaterializationDTO> result = port.materialize("file_1", 1L, target);

        // then（载荷携带业务 ID、登记摘要与宿主路径；sizeBytes 取自领域登记值）
        assertTrue(result.isPresent());
        assertEquals("file_1", result.get().fileId());
        assertEquals(file.sizeBytes(), result.get().sizeBytes());
        assertEquals(file.contentSha256(), result.get().contentSha256());
        assertEquals(target, result.get().materializedPath());
    }

    @Test
    void should_skipCopy_when_materialize_given_missingNotOwnedNullOrFileId() {
        // given（不存在与越权均以门禁拒绝）
        when(fileRepository.findByFileId("file_gone")).thenReturn(Optional.empty());
        when(fileRepository.findByFileId("file_1"))
                .thenReturn(Optional.of(File.create("file_1", 2L, "a.txt", "text/plain",
                        FilePurpose.USER_UPLOAD, null, null, CONTENT)));

        // when & then（越权 / 不存在 / null 参数统一空，不泄露存在性）
        assertTrue(port.materialize("file_gone", 1L, tempDir.resolve("x")).isEmpty());
        assertTrue(port.materialize("file_1", 1L, tempDir.resolve("x")).isEmpty());
        assertTrue(port.materialize(null, 1L, tempDir.resolve("x")).isEmpty());
        assertTrue(port.materialize("file_1", null, tempDir.resolve("x")).isEmpty());

        // then（门禁短路于对象复制之前，越权请求不产生复制 IO）
        verify(fileContentPort, never()).copyToHost(anyString(), any());
    }

    @Test
    void should_returnEmpty_when_materialize_given_contentObjectMissing() {
        // given（记录存在且归属正确，但内容对象缺失 → copyToHost 返回空）
        Path target = tempDir.resolve("mounts").resolve("file_1");
        when(fileRepository.findByFileId("file_1")).thenReturn(Optional.of(buildOwnedFile()));
        when(fileContentPort.copyToHost("file_1", target)).thenReturn(Optional.empty());

        // when & then（对象缺失收敛为空，不抛异常）
        assertTrue(port.materialize("file_1", 1L, target).isEmpty());
    }

    @Test
    void should_deleteResidueAndThrow_when_materialize_given_digestMismatch() {
        // given（copyToHost 成功落盘但摘要与登记不符——对象内容被篡改）
        Path target = tempDir.resolve("mounts").resolve("file_1");
        when(fileRepository.findByFileId("file_1")).thenReturn(Optional.of(buildOwnedFile()));
        when(fileContentPort.copyToHost("file_1", target)).thenReturn(Optional.of("f".repeat(64)));

        // when & then（抛一致性异常，而非静默返回空）
        assertThrows(FileContentIntegrityException.class, () -> port.materialize("file_1", 1L, target));
    }
}
