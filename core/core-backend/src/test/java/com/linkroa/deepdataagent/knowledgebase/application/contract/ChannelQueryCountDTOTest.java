package com.linkroa.deepdataagent.knowledgebase.application.contract;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link ChannelQueryCountDTO} 契约紧凑构造器校验单元测试。
 */
class ChannelQueryCountDTOTest {

    @Test
    void should_acceptNullFields_when_construct_given_allNull() {
        // given // when // then：null=未配置，为合法态
        assertDoesNotThrow(() -> new ChannelQueryCountDTO(null, null, null));
    }

    @Test
    void should_carryValues_when_construct_given_validCounts() {
        // given
        ChannelQueryCountDTO dto = new ChannelQueryCountDTO(30, 15, 25);

        // when // then
        assertEquals(30, dto.vectorQueryCount());
        assertEquals(15, dto.bm25QueryCount());
        assertEquals(25, dto.graphQueryCount());
    }

    @Test
    void should_throw_when_construct_given_zeroVectorQueryCount() {
        // given // when // then
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new ChannelQueryCountDTO(0, 15, 25));
        assertEquals("通道召回数量（vectorQueryCount）必须在1到100之间", exception.getMessage());
    }

    @Test
    void should_throw_when_construct_given_bm25QueryCountOverMax() {
        // given // when // then
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new ChannelQueryCountDTO(30, 101, null));
        assertEquals("通道召回数量（bm25QueryCount）必须在1到100之间", exception.getMessage());
    }

    @Test
    void should_throw_when_construct_given_negativeGraphQueryCount() {
        // given // when // then
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new ChannelQueryCountDTO(30, 15, -1));
        assertEquals("通道召回数量（graphQueryCount）必须在1到100之间", exception.getMessage());
    }

    @Test
    void should_acceptBoundaries_when_construct_given_minAndMax() {
        // given // when // then：边界值 1 与 100 合法
        assertDoesNotThrow(() -> new ChannelQueryCountDTO(1, 1, 1));
        assertDoesNotThrow(() -> new ChannelQueryCountDTO(100, 100, 100));
    }
}
