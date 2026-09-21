package com.linkroa.deepdataagent.knowledgebase.infrastructure;

import com.linkroa.deepdataagent.knowledgebase.application.contract.ChunkDraft;
import com.linkroa.deepdataagent.knowledgebase.application.service.ChunkApplicationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DefaultChunkBatchWriter} 契约适配器单测：验证纯委托语义。
 */
@ExtendWith(MockitoExtension.class)
class DefaultChunkBatchWriterTest {

    @Mock
    private ChunkApplicationService chunkApplicationService;

    @InjectMocks
    private DefaultChunkBatchWriter writer;

    @Test
    void should_delegateReplace_when_replaceForDocument_given_drafts() {
        // given：应用服务回传「序号→主键」映射
        List<ChunkDraft> drafts = List.of(
                new ChunkDraft(1, "切片内容-1", 30, null, "TEXT", null));
        Map<Integer, Long> identityMap = Map.of(1, 9001L);
        when(chunkApplicationService.replaceForDocument(100L, drafts, 7L)).thenReturn(identityMap);

        // when
        Map<Integer, Long> result = writer.replaceForDocument(100L, drafts, 7L);

        // then：参数与返回映射均原样透传，适配器不含额外逻辑
        verify(chunkApplicationService).replaceForDocument(100L, drafts, 7L);
        assertEquals(identityMap, result);
    }

    @Test
    void should_returnTrue_when_markProcessing_given_claimHit() {
        // given：出队领取命中（PENDING→PROCESSING 条件更新成功）
        when(chunkApplicationService.markProcessing(100L)).thenReturn(true);

        // when
        boolean claimed = writer.markProcessing(100L);

        // then：领取结果原样透传
        assertTrue(claimed);
        verify(chunkApplicationService).markProcessing(100L);
    }

    @Test
    void should_returnFalse_when_markProcessing_given_claimMiss() {
        // given：CAS 未命中（文档已被删除链改写或已被启动清理置 FAILED）
        when(chunkApplicationService.markProcessing(100L)).thenReturn(false);

        // when
        boolean claimed = writer.markProcessing(100L);

        // then
        assertFalse(claimed);
    }

    @Test
    void should_delegateMarkProcessed_when_markProcessed_given_pipelineCompleted() {
        // given：摄入管线完整结束，成功终态条件更新命中
        when(chunkApplicationService.markProcessed(100L)).thenReturn(true);

        // when
        boolean marked = writer.markProcessed(100L);

        // then：置态结果原样透传，适配器不含额外逻辑
        assertTrue(marked);
        verify(chunkApplicationService).markProcessed(100L);
    }

    @Test
    void should_returnFalse_when_markProcessed_given_casMiss() {
        // given：文档状态已被删除链 / 用户重新解析推进，条件更新未命中
        when(chunkApplicationService.markProcessed(100L)).thenReturn(false);

        // when
        boolean marked = writer.markProcessed(100L);

        // then
        assertFalse(marked);
    }

    @Test
    void should_delegateMarkFailed_when_markFailed_given_errorMessage() {
        // when
        writer.markFailed(100L, "解析超时");

        // then
        verify(chunkApplicationService).markFailed(100L, "解析超时");
    }

    @Test
    void should_returnFailedCount_when_failNonTerminal_given_delegatedResult() {
        // given：启动清理批量收敛非终态，影响行数透传
        when(chunkApplicationService.failNonTerminal("[STARTUP] 服务重启中断，请重新解析")).thenReturn(3);

        // when
        int failed = writer.failNonTerminal("[STARTUP] 服务重启中断，请重新解析");

        // then
        assertEquals(3, failed);
        verify(chunkApplicationService).failNonTerminal("[STARTUP] 服务重启中断，请重新解析");
    }
}
