package com.linkroa.deepdataagent.knowledgebase.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DocumentGraphConvergenceResult} 单元测试。
 * <p>覆盖全零结果共享单例语义（converge-rag-hot-path-object-creation / 5.6）：
 * {@code empty()} 多次调用返回同一不可变实例（紧凑构造器的 {@code List.copyOf}
 * 只应在常量初始化时发生一次），各计数为零、两份待收口清单为空。</p>
 *
 * @author DeepDataAgent
 */
class DocumentGraphConvergenceResultTest {

    /**
     * 场景：多次调用 {@link DocumentGraphConvergenceResult#empty()}。
     * 预期：每次返回同一实例，六项计数全零、两份向量收口清单为空。
     */
    @Test
    void should_returnSameAllZeroInstance_when_empty_given_repeatedCalls() {
        // when
        DocumentGraphConvergenceResult first = DocumentGraphConvergenceResult.empty();
        DocumentGraphConvergenceResult second = DocumentGraphConvergenceResult.empty();

        // then：共享单例且形态全零
        assertSame(first, second, "全零结果应共享单例（List.copyOf 仅发生在常量初始化）");
        assertEquals(0, first.prunedEntries());
        assertEquals(0, first.rebuiltEntries());
        assertEquals(0, first.degradedEntries());
        assertEquals(0, first.missingEntries());
        assertEquals(0, first.reclaimedAttributionRows());
        assertEquals(0, first.reclaimedCacheRows());
        assertTrue(first.pendingVectorSyncEntityNames().isEmpty());
        assertTrue(first.pendingVectorSyncRelationPairs().isEmpty());
    }
}
