package com.linkroa.deepdataagent.runtime.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link MemoryStoreRef} 记忆库引用值对象不变量单测。
 */
class MemoryStoreRefTest {

    @Test
    void should_acceptFormattedValues_when_construct_given_validReference() {
        // given & when
        MemoryStoreRef ref = new MemoryStoreRef("mem-1", "会话记忆", "SHORT_TERM");

        // then
        assertEquals("mem-1", ref.memoryStoreId());
        assertEquals("会话记忆", ref.name());
        assertEquals("SHORT_TERM", ref.type());
    }

    @Test
    void should_reject_when_construct_given_blankMemoryStoreId() {
        // given & when & then
        assertThrows(IllegalArgumentException.class,
                () -> new MemoryStoreRef(" ", "会话记忆", "SHORT_TERM"));
    }

    @Test
    void should_reject_when_construct_given_blankName() {
        // given & when & then
        assertThrows(IllegalArgumentException.class,
                () -> new MemoryStoreRef("mem-1", "", "SHORT_TERM"));
    }

    @Test
    void should_reject_when_construct_given_blankType() {
        // given & when & then
        assertThrows(IllegalArgumentException.class,
                () -> new MemoryStoreRef("mem-1", "会话记忆", " "));
    }
}