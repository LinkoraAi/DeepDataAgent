package com.linkroa.deepdataagent.memory.domain.model.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link MemoryVersionAction} 记忆版本动作枚举单测。
 */
class MemoryVersionActionTest {

    @Test
    void should_returnLowerValue_when_getValue_given_eachConstant() {
        // given // when // then
        assertEquals("created", MemoryVersionAction.CREATED.getValue());
        assertEquals("updated", MemoryVersionAction.UPDATED.getValue());
        assertEquals("deleted", MemoryVersionAction.DELETED.getValue());
    }

    @Test
    void should_parseCaseInsensitively_when_fromValue_given_uppercase() {
        // given // when // then
        assertEquals(MemoryVersionAction.CREATED, MemoryVersionAction.fromValue("CREATED"));
        assertEquals(MemoryVersionAction.UPDATED, MemoryVersionAction.fromValue("Updated"));
        assertEquals(MemoryVersionAction.DELETED, MemoryVersionAction.fromValue(" deleted "));
    }

    @Test
    void should_throwIllegalArgumentException_when_fromValue_given_unknownOrBlank() {
        // given // when // then
        assertThrows(IllegalArgumentException.class, () -> MemoryVersionAction.fromValue("redacted"));
        assertThrows(IllegalArgumentException.class, () -> MemoryVersionAction.fromValue(" "));
        assertThrows(IllegalArgumentException.class, () -> MemoryVersionAction.fromValue(null));
    }
}
