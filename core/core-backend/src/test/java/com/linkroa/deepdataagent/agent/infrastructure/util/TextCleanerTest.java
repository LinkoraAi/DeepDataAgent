package com.linkroa.deepdataagent.agent.infrastructure.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * TextCleaner 工具类单元测试
 * <p>覆盖推理过程前缀清理的各种场景：null/空输入、叙述性前缀、SQL 片段、正常报告。</p>
 */
class TextCleanerTest {

    @Test
    void should_returnNull_when_stripReasoningPreamble_given_nullInput() {
        // given
        String input = null;

        // when
        String result = TextCleaner.stripReasoningPreamble(input);

        // then
        assertNull(result);
    }

    @Test
    void should_returnEmpty_when_stripReasoningPreamble_given_emptyInput() {
        // given
        String input = "";

        // when
        String result = TextCleaner.stripReasoningPreamble(input);

        // then
        assertEquals("", result);
    }

    @Test
    void should_returnOriginal_when_stripReasoningPreamble_given_alreadyStructuredReport() {
        // given
        String input = "## 一、分析概述\n这是一份规范的分析报告。";

        // when
        String result = TextCleaner.stripReasoningPreamble(input);

        // then
        assertEquals(input, result);
    }

    @Test
    void should_stripNarrativePrefix_when_stripReasoningPreamble_given_prefixedReport() {
        // given
        String input = "好的，我来帮您分析这份数据。\n## 一、分析概述\n销售额整体呈上升趋势。";

        // when
        String result = TextCleaner.stripReasoningPreamble(input);

        // then
        assertEquals("## 一、分析概述\n销售额整体呈上升趋势。", result);
    }

    @Test
    void should_stripSqlFragment_when_stripReasoningPreamble_given_sqlBeforeReport() {
        // given
        String input = "SELECT * FROM sales WHERE year = 2024;\n"
                + "下面是查询结果的分析报告。\n"
                + "## 一、分析概述\nSQL 片段混入报告的场景。";

        // when
        String result = TextCleaner.stripReasoningPreamble(input);

        // then
        assertEquals("## 一、分析概述\nSQL 片段混入报告的场景。", result);
    }

    @Test
    void should_returnOriginal_when_stripReasoningPreamble_given_plainTextWithoutHeading() {
        // given
        String input = "没有标题的普通文本内容。";

        // when
        String result = TextCleaner.stripReasoningPreamble(input);

        // then
        assertEquals(input, result);
    }
}