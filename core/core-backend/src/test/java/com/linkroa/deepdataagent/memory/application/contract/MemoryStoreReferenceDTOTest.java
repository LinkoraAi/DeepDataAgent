package com.linkroa.deepdataagent.memory.application.contract;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MemoryStoreReferenceDTOTest {

    @Test
    void should_createDto_when_constructed_given_validFields() {
        // given // when
        MemoryStoreReferenceDTO dto = new MemoryStoreReferenceDTO("mem-1", "会话记忆", "SHORT_TERM", "ws-default");

        // then
        assertEquals("mem-1", dto.memoryStoreId());
        assertEquals("会话记忆", dto.name());
        assertEquals("SHORT_TERM", dto.type());
        assertEquals("ws-default", dto.workspaceId());
    }

    @Test
    void should_throwException_when_constructed_given_blankMemoryStoreId() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> new MemoryStoreReferenceDTO(" ", "会话记忆", "SHORT_TERM", null));
    }

    @Test
    void should_throwException_when_constructed_given_blankName() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> new MemoryStoreReferenceDTO("mem-1", " ", "SHORT_TERM", null));
    }

    @Test
    void should_throwException_when_constructed_given_blankType() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> new MemoryStoreReferenceDTO("mem-1", "会话记忆", " ", null));
    }
}