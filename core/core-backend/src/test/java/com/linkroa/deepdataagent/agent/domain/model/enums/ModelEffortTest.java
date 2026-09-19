package com.linkroa.deepdataagent.agent.domain.model.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link ModelEffort} 单元测试：线格式解析与词汇校验。
 */
class ModelEffortTest {

    @Test
    void should_returnEnum_when_from_given_validValues() {
        // given & when & then
        assertEquals(ModelEffort.NONE, ModelEffort.from("none"));
        assertEquals(ModelEffort.LOW, ModelEffort.from("low"));
        assertEquals(ModelEffort.MEDIUM, ModelEffort.from("medium"));
        assertEquals(ModelEffort.HIGH, ModelEffort.from("high"));
        assertEquals(ModelEffort.XHIGH, ModelEffort.from("xhigh"));
        assertEquals(ModelEffort.MAX, ModelEffort.from("max"));
    }

    @Test
    void should_returnNull_when_from_given_blank() {
        // given & when & then
        assertNull(ModelEffort.from(null));
        assertNull(ModelEffort.from("  "));
    }

    @Test
    void should_throw_when_from_given_illegalValue() {
        // given & when & then
        assertThrows(IllegalArgumentException.class, () -> ModelEffort.from("ultra"));
    }
}
