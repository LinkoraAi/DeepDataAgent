package com.linkroa.deepdataagent.runtime.application.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link UserIds} owner 解析口径单测（四份重复实现收敛后的唯一事实源）。
 * <p>非法（空白 / 非十进制）必须收敛为 null——调用方据此走「端口门禁按不存在处理」的
 * 404 语义，不得抛异常打断请求。</p>
 */
class UserIdsTest {

    @Test
    void should_parseNumericUserId_when_parse_given_digitsWithSurroundingSpaces() {
        // given & when
        Long userId = UserIds.parse(" 12 ");

        // then
        assertEquals(12L, userId.longValue());
    }

    @Test
    void should_returnNull_when_parse_given_blankOrNull() {
        // given & when & then
        assertNull(UserIds.parse(null));
        assertNull(UserIds.parse(""));
        assertNull(UserIds.parse("   "));
    }

    @Test
    void should_returnNull_when_parse_given_nonNumericOrOverflowingText() {
        // given & when & then（历史非数字 userId 与超界数字一律收敛 null，不抛异常）
        assertNull(UserIds.parse("u-1"));
        assertNull(UserIds.parse("12.5"));
        assertNull(UserIds.parse("99999999999999999999999"));
    }

    @Test
    void should_parseNegativeUserId_when_parse_given_signedDigits() {
        // given & when（解析口径不做业务正负校验，由调用方 owner 比对兜底）
        Long userId = UserIds.parse("-3");

        // then
        assertEquals(-3L, userId);
    }
}
