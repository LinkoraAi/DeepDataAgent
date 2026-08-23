package com.linkroa.deepdataagent.memory.domain.model;

import com.linkroa.deepdataagent.memory.domain.model.enums.MemoryType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MemoryStoreTest {

    @Test
    void should_createMemoryStore_when_create_given_validFields() {
        // given // when
        MemoryStore store = MemoryStore.create("mem-1", "会话记忆", MemoryType.SHORT_TERM, "ws-default");

        // then
        assertEquals("mem-1", store.memoryId());
        assertEquals("会话记忆", store.name());
        assertEquals(MemoryType.SHORT_TERM, store.type());
        assertEquals("ws-default", store.workspaceId());
    }

    @Test
    void should_throwException_when_create_given_blankName() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> MemoryStore.create("mem-1", " ", MemoryType.SHORT_TERM, "ws-default"));
    }

    @Test
    void should_throwException_when_create_given_nameExceeds64Chars() {
        // given
        String longName = "记".repeat(70);

        // when // then
        assertThrows(IllegalArgumentException.class,
                () -> MemoryStore.create("mem-1", longName, MemoryType.SHORT_TERM, "ws-default"));
    }

    @Test
    void should_throwException_when_create_given_invalidNamePattern() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> MemoryStore.create("mem-1", "1abc!", MemoryType.SHORT_TERM, "ws-default"));
    }

    @Test
    void should_throwException_when_create_given_nullType() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> MemoryStore.create("mem-1", "会话记忆", null, "ws-default"));
    }
}