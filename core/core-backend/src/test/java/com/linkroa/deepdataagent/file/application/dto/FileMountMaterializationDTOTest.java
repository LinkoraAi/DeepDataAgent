package com.linkroa.deepdataagent.file.application.dto;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link FileMountMaterializationDTO} 物化载荷契约不变量单测
 * （纯 record：file_ 前缀 / 非负体积 / 64 位摘要 / 非空路径四项校验）。
 */
class FileMountMaterializationDTOTest {

    private static final String SHA64 = "a".repeat(64);

    @Test
    void should_holdFields_when_construct_given_validContract() {
        // given // when
        FileMountMaterializationDTO dto =
                new FileMountMaterializationDTO("file_1", 1024L, SHA64, Path.of("/tmp/mounts/file_1"));

        // then
        assertEquals("file_1", dto.fileId());
        assertEquals(1024L, dto.sizeBytes());
        assertEquals(SHA64, dto.contentSha256());
        assertEquals(Path.of("/tmp/mounts/file_1"), dto.materializedPath());
    }

    @Test
    void should_acceptZeroSize_when_construct_given_emptyMaterializedFile() {
        // given // when（0 字节副本合法：领域仅拒负体积）
        FileMountMaterializationDTO dto =
                new FileMountMaterializationDTO("file_1", 0L, SHA64, Path.of("/tmp/mounts/file_1"));

        // then
        assertEquals(0L, dto.sizeBytes());
    }

    @Test
    void should_throw_when_construct_given_fileIdWithoutPrefixOrBlank() {
        // given // when & then（文件 ID 必须携带 file_ 前缀）
        assertThrows(IllegalArgumentException.class,
                () -> new FileMountMaterializationDTO("not-a-file", 1L, SHA64, Path.of("/tmp/x")));
        assertThrows(IllegalArgumentException.class,
                () -> new FileMountMaterializationDTO(null, 1L, SHA64, Path.of("/tmp/x")));
    }

    @Test
    void should_throw_when_construct_given_negativeSize() {
        // given // when & then
        assertThrows(IllegalArgumentException.class,
                () -> new FileMountMaterializationDTO("file_1", -1L, SHA64, Path.of("/tmp/x")));
    }

    @Test
    void should_throw_when_construct_given_digestNot64Hex() {
        // given // when & then（摘要长度非 64 或缺失均拒绝）
        assertThrows(IllegalArgumentException.class,
                () -> new FileMountMaterializationDTO("file_1", 1L, "short", Path.of("/tmp/x")));
        assertThrows(IllegalArgumentException.class,
                () -> new FileMountMaterializationDTO("file_1", 1L, null, Path.of("/tmp/x")));
    }

    @Test
    void should_throw_when_construct_given_nullMaterializedPath() {
        // given // when & then（宿主路径必填）
        assertThrows(IllegalArgumentException.class,
                () -> new FileMountMaterializationDTO("file_1", 1L, SHA64, null));
    }
}
