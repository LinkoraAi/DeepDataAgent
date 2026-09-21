package com.linkroa.deepdataagent.rag.domain.model;

import com.linkroa.deepdataagent.rag.application.contract.RetrievalQuery;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link ContextBudget} 单元测试（P3 截断份额扩展后的值对象归一口径）。
 * <p>覆盖：① 实体/关系截断份额非正值（0 与负数）归一为
 * {@link RetrievalQuery} 同名默认常量；② 正数值原样承载；
 * ③ 既有 {@code maxTotalTokens}/{@code query} 口径不变（值对象不做任何加工）。</p>
 *
 * @author DeepDataAgent
 */
class ContextBudgetTest {

    /**
     * 场景：实体/关系截断份额传 0 或负数（含「预算 0」的显式入参）。
     * 预期：构造期归一为契约默认常量（2000/3000），不产生「零份额 → 图谱段全空」的意外语义。
     */
    @Test
    void should_fallbackToQueryDefaults_when_newContextBudget_given_nonPositiveShares() {
        // given & when：0 与负数两类非正形态
        ContextBudget zero = new ContextBudget(4096, "q", 0, 0);
        ContextBudget negative = new ContextBudget(4096, "q", -1, -3000);

        // then
        assertEquals(RetrievalQuery.DEFAULT_MAX_ENTITY_TOKENS, zero.maxEntityTokens());
        assertEquals(RetrievalQuery.DEFAULT_MAX_RELATION_TOKENS, zero.maxRelationTokens());
        assertEquals(RetrievalQuery.DEFAULT_MAX_ENTITY_TOKENS, negative.maxEntityTokens());
        assertEquals(RetrievalQuery.DEFAULT_MAX_RELATION_TOKENS, negative.maxRelationTokens());
    }

    /**
     * 场景：截断份额传正数值（REST 小值透传形态）。
     * 预期：原样承载，构造器不改写正值。
     */
    @Test
    void should_keepExplicitShares_when_newContextBudget_given_positiveShares() {
        // given & when
        ContextBudget budget = new ContextBudget(4096, "q", 50, 80);

        // then
        assertEquals(50, budget.maxEntityTokens());
        assertEquals(80, budget.maxRelationTokens());
    }

    /**
     * 场景：maxTotalTokens 为非正值且 query 为任意文本（既有两字段的扩展前口径）。
     * 预期：值对象不做任何归一加工，逐分量原样承载（归一职责在编排/契约层）。
     */
    @Test
    void should_keepLegacyFieldsUntouched_when_newContextBudget_given_anyTotalTokensAndQuery() {
        // given & when
        ContextBudget budget = new ContextBudget(0, "改写后问题", 10, 10);

        // then
        assertEquals(0, budget.maxTotalTokens());
        assertEquals("改写后问题", budget.query());
    }
}
