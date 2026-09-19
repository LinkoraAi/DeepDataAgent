package com.linkroa.deepdataagent.agent.domain.model;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EnvironmentListFilter} 运行环境游标查询条件不变量单测（游标位置成对约束）。
 */
class EnvironmentListFilterTest {

    private static final OffsetDateTime NOW = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));

    @Test
    void should_acceptFirstPage_when_construct_given_noCursor() {
        // given // when（首页：游标位置双双缺省，可带时间区间过滤）
        EnvironmentListFilter filter = new EnvironmentListFilter(null, NOW, null, null, null, false);

        // then
        assertFalse(filter.reverse());
    }

    @Test
    void should_acceptCursorPair_when_construct_given_createdAtAndRowId() {
        // given // when（keyset 位点成对提供，reverse=before 方向）
        EnvironmentListFilter filter = new EnvironmentListFilter(null, null, null, NOW, 7L, true);

        // then
        assertTrue(filter.reverse());
    }

    @Test
    void should_throw_when_construct_given_cursorPairPartial() {
        // given（仅游标时间无行号 / 仅行号无游标时间 → 违反成对约束）
        // when // then
        assertThrows(IllegalArgumentException.class,
                () -> new EnvironmentListFilter(null, null, null, NOW, null, false));
        assertThrows(IllegalArgumentException.class,
                () -> new EnvironmentListFilter(null, null, null, null, 7L, false));
    }
}
