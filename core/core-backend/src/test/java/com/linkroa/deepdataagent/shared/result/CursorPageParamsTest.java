package com.linkroa.deepdataagent.shared.result;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link CursorPageParams} 请求参数解析校验单测（6.1 公共约定：limit 默认 20 / range 1-100、游标互斥）。
 */
class CursorPageParamsTest {

    @Test
    void should_useDefaultLimit_when_parse_given_blankParams() {
        // given & when
        CursorPageParams params = CursorPageParams.parse(null, null, null);

        // then（缺省 20、无游标）
        assertEquals(20, params.limit());
        assertNull(params.afterId());
        assertNull(params.beforeId());
    }

    @Test
    void should_acceptUpperBound_when_parse_given_limit100() {
        // given & when
        CursorPageParams params = CursorPageParams.parse("100", " sess_9 ", null);

        // then（游标 trim 后装配）
        assertEquals(100, params.limit());
        assertEquals("sess_9", params.afterId());
    }

    @Test
    void should_throwIllegalArgument_when_parse_given_zeroOrOverHundredLimit() {
        // given & when & then（limit=0 与 limit>100 → 400 语义由全局异常处理器收敛）
        assertThrows(IllegalArgumentException.class, () -> CursorPageParams.parse("0", null, null));
        assertThrows(IllegalArgumentException.class, () -> CursorPageParams.parse("101", null, null));
        assertThrows(IllegalArgumentException.class, () -> CursorPageParams.parse("-5", null, null));
    }

    @Test
    void should_throwIllegalArgument_when_parse_given_nonNumericLimit() {
        // given & when & then
        assertThrows(IllegalArgumentException.class, () -> CursorPageParams.parse("abc", null, null));
    }

    @Test
    void should_throwIllegalArgument_when_parse_given_bothCursors() {
        // given & when & then（after_id 与 before_id 互斥）
        assertThrows(IllegalArgumentException.class, () -> CursorPageParams.parse("20", "a-1", "b-1"));
    }
}
