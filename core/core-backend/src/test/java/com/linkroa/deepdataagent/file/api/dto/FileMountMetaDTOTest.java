package com.linkroa.deepdataagent.file.api.dto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link FileMountMetaDTO} 发布语言契约不变量单测（纯 record，仅紧凑构造器校验）。
 */
class FileMountMetaDTOTest {

    @Test
    void should_keepAllComponents_when_constructor_given_validMountMeta() {
        // given // when
        FileMountMetaDTO dto = new FileMountMetaDTO("file_1", "a.txt", 1024L);

        // then
        assertEquals("file_1", dto.fileId());
        assertEquals("a.txt", dto.filename());
        assertEquals(1024L, dto.sizeBytes());
    }

    @Test
    void should_allowZeroBytes_when_constructor_given_emptyFile() {
        // given // when（0 字节合法：空文件同样可挂载）
        FileMountMetaDTO dto = new FileMountMetaDTO("file_1", "empty.txt", 0L);

        // then
        assertEquals(0L, dto.sizeBytes());
    }

    @Test
    void should_throwException_when_constructor_given_invalidComponents() {
        // given // when // then（文件 ID / 文件名非空白，字节数非负）
        assertThrows(IllegalArgumentException.class,
                () -> new FileMountMetaDTO(" ", "a.txt", 1L));
        assertThrows(IllegalArgumentException.class,
                () -> new FileMountMetaDTO("file_1", " ", 1L));
        assertThrows(IllegalArgumentException.class,
                () -> new FileMountMetaDTO("file_1", "a.txt", -1L));
    }
}
