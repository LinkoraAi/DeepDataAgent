package com.linkroa.deepdataagent.memory.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryStoreTest {

    @Test
    void should_createMemoryStore_when_create_given_validFields() {
        // given // when
        MemoryStore store = MemoryStore.create("ms-1", "会话记忆", "默认会话记忆", 1L);

        // then
        assertEquals("ms-1", store.storeId());
        assertEquals("会话记忆", store.name());
        assertEquals("默认会话记忆", store.description());
        assertFalse(store.archived());
    }

    @Test
    void should_throwException_when_create_given_blankName() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> MemoryStore.create("ms-1", " ", null, 1L));
    }

    @Test
    void should_throwException_when_create_given_nameExceeds64Chars() {
        // given
        String longName = "记".repeat(70);

        // when // then
        assertThrows(IllegalArgumentException.class,
                () -> MemoryStore.create("ms-1", longName, null, 1L));
    }

    @Test
    void should_throwException_when_create_given_invalidNamePattern() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> MemoryStore.create("ms-1", "1abc!", null, 1L));
    }

    @Test
    void should_throwException_when_create_given_nullOwnerId() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> MemoryStore.create("ms-1", "会话记忆", null, null));
    }

    @Test
    void should_archive_when_archive_given_activeStore() {
        // given
        MemoryStore store = MemoryStore.create("ms-1", "会话记忆", null, 1L);

        // when
        MemoryStore archived = store.archive();

        // then
        assertTrue(archived.archived());
        assertNotNull(archived.archivedAt());
    }
}