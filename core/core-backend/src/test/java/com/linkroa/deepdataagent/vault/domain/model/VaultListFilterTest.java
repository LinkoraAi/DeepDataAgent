package com.linkroa.deepdataagent.vault.domain.model;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link VaultListFilter} 保管库游标查询条件不变量单测（游标位置成对约束）。
 */
class VaultListFilterTest {

    private static final OffsetDateTime NOW = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));

    @Test
    void should_acceptFirstPage_when_construct_given_noCursor() {
        // given // when（首页：游标位置双双缺省）
        VaultListFilter filter = new VaultListFilter(null, "{\"team\":\"data\"}", Boolean.FALSE, null, null, false);

        // then
        assertFalse(filter.reverse());
    }

    @Test
    void should_acceptCursorPair_when_construct_given_createdAtAndRowId() {
        // given // when（keyset 位点成对提供）
        VaultListFilter filter = new VaultListFilter(null, null, null, NOW, 7L, true);

        // then
        assertTrue(filter.reverse());
    }

    @Test
    void should_throw_when_construct_given_cursorPairPartial() {
        // given（仅游标时间无行号 → 违反成对约束）
        // when // then
        assertThrows(IllegalArgumentException.class,
                () -> new VaultListFilter(null, null, null, NOW, null, false));
        // given（仅行号无游标时间）
        // when // then
        assertThrows(IllegalArgumentException.class,
                () -> new VaultListFilter(null, null, null, null, 7L, false));
    }
}
