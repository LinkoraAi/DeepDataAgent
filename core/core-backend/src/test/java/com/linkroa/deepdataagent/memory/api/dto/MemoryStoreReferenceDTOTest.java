package com.linkroa.deepdataagent.memory.api.dto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MemoryStoreReferenceDTOTest {

    @Test
    void should_createDto_when_constructed_given_validFields() {
        // given // when
        MemoryStoreReferenceDTO dto = new MemoryStoreReferenceDTO("ms-1", "会话记忆");

        // then
        assertEquals("ms-1", dto.storeId());
        assertEquals("会话记忆", dto.name());
    }

    @Test
    void should_throwException_when_constructed_given_blankStoreId() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> new MemoryStoreReferenceDTO(" ", "会话记忆"));
    }

    @Test
    void should_throwException_when_constructed_given_blankName() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> new MemoryStoreReferenceDTO("ms-1", " "));
    }
}