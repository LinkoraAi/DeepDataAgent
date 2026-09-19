package com.linkroa.deepdataagent.memory.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link MemoryDetail} 记忆详情只读模型不变量单测。
 */
class MemoryDetailTest {

    @Test
    void should_holdEntryAndContent_when_construct_given_validFields() {
        // given
        Memory entry = Memory.create("mem_1", "ms_1", "notes/a", "内容", null);

        // when
        MemoryDetail detail = new MemoryDetail(entry, "内容");

        // then
        assertSame(entry, detail.entry());
        assertEquals("内容", detail.content());
    }

    @Test
    void should_allowNullContent_when_construct_given_redactedHeadVersion() {
        // given
        Memory entry = Memory.create("mem_1", "ms_1", "notes/a", "内容", null);

        // when
        MemoryDetail detail = new MemoryDetail(entry, null);

        // then
        assertNull(detail.content());
    }

    @Test
    void should_throwIllegalArgumentException_when_construct_given_nullEntry() {
        // given // when // then
        assertThrows(IllegalArgumentException.class, () -> new MemoryDetail(null, "内容"));
    }
}
