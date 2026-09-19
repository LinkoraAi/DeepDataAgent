package com.linkroa.deepdataagent.file.infrastructure.assembly;

import com.linkroa.deepdataagent.file.api.FileApi;
import com.linkroa.deepdataagent.file.api.dto.FileMountMetaDTO;
import com.linkroa.deepdataagent.file.domain.model.File;
import com.linkroa.deepdataagent.file.domain.model.enums.FilePurpose;
import com.linkroa.deepdataagent.file.domain.repository.FileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * {@link DefaultFileApi} 服务契约进程内实现单测（元数据面：存在性 + owner 归属 + ready 门禁）。
 * <p>越权、不存在、未就绪、null 参数统一收敛为 {@code false} / 空，避免经契约泄露文件存在性。
 * 内容物化与 SHA-256 一致性校验不在本面覆盖范围（见 {@code DefaultFileMountMaterializationPortTest}）。</p>
 */
@ExtendWith(MockitoExtension.class)
class DefaultFileApiTest {

    private static final byte[] CONTENT = "文件内容".getBytes(StandardCharsets.UTF_8);

    @Mock private FileRepository fileRepository;

    private FileApi fileApi;

    @BeforeEach
    void setUp() {
        DefaultFileApi api = new DefaultFileApi();
        ReflectionTestUtils.setField(api, "fileRepository", fileRepository);
        fileApi = api;
    }

    private File buildFile(Long ownerId, FilePurpose purpose) {
        return File.create("file_1", ownerId, "a.txt", "text/plain",
                purpose, null, null, CONTENT);
    }

    @Test
    void should_returnTrue_when_readyForMount_given_ownedReadyFile() {
        // given
        when(fileRepository.findByFileId("file_1"))
                .thenReturn(Optional.of(buildFile(1L, FilePurpose.USER_UPLOAD)));

        // when
        boolean ready = fileApi.readyForMount("file_1", 1L);

        // then
        assertTrue(ready);
    }

    @Test
    void should_returnFalse_when_readyForMount_given_otherOwnersFile() {
        // given（越权：文件归属其他用户）
        when(fileRepository.findByFileId("file_1"))
                .thenReturn(Optional.of(buildFile(2L, FilePurpose.USER_UPLOAD)));

        // when
        boolean ready = fileApi.readyForMount("file_1", 1L);

        // then（越权与不存在统一 false 语义）
        assertFalse(ready);
    }

    @Test
    void should_returnFalse_when_readyForMount_given_missingFile() {
        // given
        when(fileRepository.findByFileId("file_gone")).thenReturn(Optional.empty());

        // when & then
        assertFalse(fileApi.readyForMount("file_gone", 1L));
    }

    @Test
    void should_returnFalse_when_readyForMount_given_nullArguments() {
        // given // when & then（null 参数直接拒绝，不触碰仓储）
        assertFalse(fileApi.readyForMount(null, 1L));
        assertFalse(fileApi.readyForMount("file_1", null));
    }

    @Test
    void should_returnMountMeta_when_findReadyMountMeta_given_ownedReadyFile() {
        // given
        File file = buildFile(1L, FilePurpose.USER_UPLOAD);
        when(fileRepository.findByFileId("file_1")).thenReturn(Optional.of(file));

        // when
        Optional<FileMountMetaDTO> meta = fileApi.findReadyMountMeta("file_1", 1L);

        // then（元信息 DTO 携带配额计数字节数，不读取磁盘内容）
        assertTrue(meta.isPresent());
        assertEquals("file_1", meta.get().fileId());
        assertEquals("a.txt", meta.get().filename());
        assertEquals(file.sizeBytes(), meta.get().sizeBytes());
    }

    @Test
    void should_returnEmpty_when_findReadyMountMeta_given_foreignMissingOrNullArgs() {
        // given（越权：文件归属其他用户）
        when(fileRepository.findByFileId("file_1")).thenReturn(Optional.of(buildFile(2L, FilePurpose.USER_UPLOAD)));
        when(fileRepository.findByFileId("file_gone")).thenReturn(Optional.empty());

        // when & then（越权 / 不存在 / null 参数统一空，不泄露存在性）
        assertTrue(fileApi.findReadyMountMeta("file_1", 1L).isEmpty());
        assertTrue(fileApi.findReadyMountMeta("file_gone", 1L).isEmpty());
        assertTrue(fileApi.findReadyMountMeta(null, 1L).isEmpty());
        assertTrue(fileApi.findReadyMountMeta("file_1", null).isEmpty());
    }
}
