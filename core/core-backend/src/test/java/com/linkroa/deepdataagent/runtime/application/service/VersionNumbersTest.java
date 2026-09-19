package com.linkroa.deepdataagent.runtime.application.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link VersionNumbers} 发布号解析口径单测（命令 / 查询两侧收敛后的唯一事实源）。
 * <p>两种出口口径并存：快照契约侧 {@code null} = 取最新 / 激活版本；
 * 响应摘要侧非法收敛 0 且负号夹到 0。本测试钉住两者不互相串味。</p>
 */
class VersionNumbersTest {

    @Test
    void should_parseReleaseNumber_when_parseOrNull_given_decimalText() {
        // given & when
        Integer version = VersionNumbers.parseOrNull(" 7 ");

        // then
        assertEquals(7, version.intValue());
    }

    @Test
    void should_returnNull_when_parseOrNull_given_blankOrNonNumericVersion() {
        // given & when & then（null 语义 = 契约侧取最新，非零值）
        assertNull(VersionNumbers.parseOrNull(null));
        assertNull(VersionNumbers.parseOrNull("  "));
        assertNull(VersionNumbers.parseOrNull("v1.2.3"));
    }

    @Test
    void should_returnZero_when_parseNumber_given_blankOrNonNumericVersion() {
        // given & when & then（响应摘要口径：非法收敛 0）
        assertEquals(0, VersionNumbers.parseNumber(null));
        assertEquals(0, VersionNumbers.parseNumber("latest"));
    }

    @Test
    void should_clampNegativeToZero_when_parseNumber_given_negativeReleaseNumber() {
        // given & when
        int version = VersionNumbers.parseNumber("-5");

        // then（负号夹到 0，避免越界索引进入响应摘要）
        assertEquals(0, version);
    }

    @Test
    void should_keepPositiveNumber_when_parseNumber_given_positiveReleaseNumber() {
        assertEquals(12, VersionNumbers.parseNumber("12"));
    }
}
