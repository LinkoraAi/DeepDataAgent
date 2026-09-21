package com.linkroa.deepdataagent.rag.domain.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JtokkitTokenCounter} 单元测试。
 * <p>覆盖真实 encode 计数主路径、空/null/空白边界，以及 {@code truncate} 的「未超限原样返回、
 * 超限按 token 精确切片、非正上限返回空串」三分支。纯真实编码直测，不使用 Mockito；
 * 启发式兜底路径已随实现删除，故不再有降级相关用例。</p>
 */
class JtokkitTokenCounterTest {

    /** 被测计数器（jtokkit cl100k_base 真实 encode）。 */
    private final TokenCounter counter = new JtokkitTokenCounter();

    @Test
    void should_countZero_when_count_givenBlankOrNullText() {
        // given

        // when
        int emptyCount = counter.count("");
        int blankCount = counter.count("   ");
        int nullCount = counter.count(null);

        // then
        assertEquals(0, emptyCount);
        assertEquals(0, blankCount);
        assertEquals(0, nullCount);
    }

    @Test
    void should_countPositiveAndMonotonic_when_count_givenNormalText() {
        // given
        String shortText = "Hello";
        String longText = "Hello, world! This is a RAG ingestion pipeline test with more tokens.";

        // when
        int shortCount = counter.count(shortText);
        int longCount = counter.count(longText);

        // then 真实编码计数为正，且更长文本 token 不少于更短文本
        assertTrue(shortCount > 0, "非空文本真实编码计数应为正");
        assertTrue(longCount >= shortCount, "更长文本的 token 数不应少于更短文本");
    }

    @Test
    void should_countCjk_when_count_givenChineseText() {
        // given
        String text = "中文测试文本";

        // when
        int count = counter.count(text);

        // then
        assertTrue(count > 0, "中文文本真实编码计数应为正");
    }

    @Test
    void should_returnOriginal_when_truncate_given_withinBudget() {
        // given
        String text = "short text";
        int maxTokens = counter.count(text) + 10;

        // when
        String result = counter.truncate(text, maxTokens);

        // then 未超预算原样返回
        assertEquals(text, result);
    }

    @Test
    void should_truncateToPrefix_when_truncate_given_exceedBudget() {
        // given
        String text = "jtokkit cl100k base tokenizer truncates long text "
                + "to an exact token budget boundary without heuristic estimation.";
        int maxTokens = 8;

        // when
        String result = counter.truncate(text, maxTokens);

        // then 结果为原文前缀且真实 token 数不超过上限
        assertTrue(text.startsWith(result), "截断结果必须是原文前缀");
        assertTrue(result.length() < text.length(), "超限文本必须被截短");
        assertTrue(counter.count(result) <= maxTokens, "截断结果 token 数不得超过上限");
    }

    @Test
    void should_returnEmpty_when_truncate_given_nonPositiveMaxTokens() {
        // given
        String text = "any text";

        // when
        String zeroResult = counter.truncate(text, 0);
        String negativeResult = counter.truncate(text, -1);
        String blankResult = counter.truncate("  ", 5);

        // then
        assertEquals("", zeroResult);
        assertEquals("", negativeResult);
        assertEquals("", blankResult);
    }
}
