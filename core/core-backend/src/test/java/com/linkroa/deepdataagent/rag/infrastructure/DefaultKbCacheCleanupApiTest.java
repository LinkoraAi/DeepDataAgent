package com.linkroa.deepdataagent.rag.infrastructure;

import com.linkroa.deepdataagent.rag.domain.repository.LlmCacheRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * {@link DefaultKbCacheCleanupApi} 契约适配器单测：
 * 覆盖按库整清委托（含无条目静默成功）、空值防御与异常原样上抛（留痕与重试策略归调用方收敛链）。
 */
@ExtendWith(MockitoExtension.class)
class DefaultKbCacheCleanupApiTest {

    /** 测试用知识库ID */
    private static final Long KB_ID = 10L;

    @Mock
    private LlmCacheRepository llmCacheRepository;

    @InjectMocks
    private DefaultKbCacheCleanupApi api;

    @Test
    void should_delegateWholeKbDelete_when_deleteCachesByKnowledgeBase_given_kbId() {
        // given：void 端口默认无操作（条件删除成功；无条目时 0 行受影响亦静默成功）

        // when
        assertDoesNotThrow(() -> api.deleteCachesByKnowledgeBase(KB_ID));

        // then：kbId 原样透传，仅一次整清调用
        verify(llmCacheRepository, times(1)).deleteByKbId(KB_ID);
        verifyNoMoreInteractions(llmCacheRepository);
    }

    @Test
    void should_skipWithoutTouchingRepository_when_deleteCachesByKnowledgeBase_given_nullKbId() {
        // when：空值防御，不触达仓储
        assertDoesNotThrow(() -> api.deleteCachesByKnowledgeBase(null));

        // then
        verify(llmCacheRepository, never()).deleteByKbId(any());
    }

    @Test
    void should_propagateFailure_when_deleteCachesByKnowledgeBase_given_repositoryThrows() {
        // given：仓储清退失败（如数据库不可用）
        RuntimeException failure = new RuntimeException("db down");
        doThrow(failure).when(llmCacheRepository).deleteByKbId(KB_ID);

        // when & then：适配器不吞异常，由调用方（knowledgebase 收敛链）决定 WARN 留痕与重试
        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> api.deleteCachesByKnowledgeBase(KB_ID));
        assertSame(failure, thrown);
        verify(llmCacheRepository, times(1)).deleteByKbId(KB_ID);
    }
}
