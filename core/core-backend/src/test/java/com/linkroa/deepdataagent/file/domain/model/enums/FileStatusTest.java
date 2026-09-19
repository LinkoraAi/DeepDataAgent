package com.linkroa.deepdataagent.file.domain.model.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link FileStatus} 枚举单测（ready 态契约词汇解析）。
 */
class FileStatusTest {

    @Test
    void should_exposeReadyCode_when_code_given_ready() {
        // given // when & then
        assertEquals("ready", FileStatus.READY.code());
    }

    @Test
    void should_parseEnum_when_fromCode_given_ready() {
        // given // when & then
        assertEquals(FileStatus.READY, FileStatus.fromCode("ready"));
    }

    @Test
    void should_throwException_when_fromCode_given_blankOrUnknownCode() {
        // given // when // then
        assertThrows(IllegalArgumentException.class, () -> FileStatus.fromCode(null));
        assertThrows(IllegalArgumentException.class, () -> FileStatus.fromCode(" "));
        assertThrows(IllegalArgumentException.class, () -> FileStatus.fromCode("uploading"));
    }
}
