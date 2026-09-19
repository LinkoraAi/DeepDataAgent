package com.linkroa.deepdataagent.memory.domain.model.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link MemoryStoreStatus} 记忆库状态枚举单测。
 */
class MemoryStoreStatusTest {

    @Test
    void should_returnLowerValue_when_getValue_given_eachConstant() {
        // given // when // then
        assertEquals("active", MemoryStoreStatus.ACTIVE.getValue());
        assertEquals("archived", MemoryStoreStatus.ARCHIVED.getValue());
    }

    @Test
    void should_parseCaseInsensitively_when_fromValue_given_uppercase() {
        // given // when // then
        assertEquals(MemoryStoreStatus.ACTIVE, MemoryStoreStatus.fromValue("ACTIVE"));
        assertEquals(MemoryStoreStatus.ARCHIVED, MemoryStoreStatus.fromValue("Archived"));
        assertEquals(MemoryStoreStatus.ACTIVE, MemoryStoreStatus.fromValue(" active "));
    }

    @Test
    void should_throwIllegalArgumentException_when_fromValue_given_unknownOrBlank() {
        // given // when // then
        assertThrows(IllegalArgumentException.class, () -> MemoryStoreStatus.fromValue("deleted"));
        assertThrows(IllegalArgumentException.class, () -> MemoryStoreStatus.fromValue(" "));
        assertThrows(IllegalArgumentException.class, () -> MemoryStoreStatus.fromValue(null));
    }
}
