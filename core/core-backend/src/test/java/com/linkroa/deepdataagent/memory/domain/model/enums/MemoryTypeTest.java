package com.linkroa.deepdataagent.memory.domain.model.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class MemoryTypeTest {

    @Test
    void should_containShortAndLongTerm_when_enumerateMemoryType() {
        // given // when
        MemoryType[] values = MemoryType.values();

        // then
        assertEquals(2, values.length);
        assertNotNull(MemoryType.valueOf("SHORT_TERM"));
        assertNotNull(MemoryType.valueOf("LONG_TERM"));
    }
}