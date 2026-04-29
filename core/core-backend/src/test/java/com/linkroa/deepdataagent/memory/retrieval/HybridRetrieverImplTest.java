package com.linkroa.deepdataagent.memory.retrieval;

import com.linkroa.deepdataagent.memory.index.MemoryIndexManager;
import com.linkroa.deepdataagent.memory.model.MemoryChunk;
import com.linkroa.deepdataagent.memory.model.MemorySearchResult;
import com.linkroa.deepdataagent.memory.model.RetrieveOptions;
import com.linkroa.deepdataagent.memory.model.ScoredChunk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HybridRetrieverImplTest {

    @Mock
    private MemoryIndexManager indexManager;

    @Mock
    private TemporalReranker temporalReranker;

    @InjectMocks
    private HybridRetrieverImpl retriever;

    @Test
    void should_returnEmptyAndSkipDependencies_when_hybridSearch_given_blankQuery() {
        // given
        RetrieveOptions options = new RetrieveOptions(5, 1000, 60, 0.0, true);

        // when
        List<MemorySearchResult> blankResults = retriever.hybridSearch("", options);
        List<MemorySearchResult> nullResults = retriever.hybridSearch(null, options);

        // then
        assertEquals(List.of(), blankResults);
        assertEquals(List.of(), nullResults);
        verify(indexManager, never()).keywordSearch(any(), any(Integer.class));
        verify(indexManager, never()).vectorSearch(any(), any(Integer.class));
        verify(indexManager, never()).scanSearch(any(), any(Integer.class));
        verify(indexManager, never()).updateAccessCounts(any());
    }

    @Test
    void should_mergeRankAndUpdateAccessCounts_when_hybridSearch_given_keywordAndVectorMatches() {
        // given
        MemoryChunk chunk1 = chunk("chunk-1", "mem-yaml", "USER.md", "Prefer YAML");
        MemoryChunk chunk2 = chunk("chunk-2", "mem-rule", "MEMORY.md", "Use health checks");
        RetrieveOptions options = new RetrieveOptions(2, 1000, 60, 0.0, true);
        when(indexManager.keywordSearch("YAML 配置", 6)).thenReturn(List.of(
                new ScoredChunk(chunk1, 0.9),
                new ScoredChunk(chunk2, 0.4)
        ));
        when(indexManager.vectorSearch("YAML 配置", 6)).thenReturn(List.of(
                new ScoredChunk(chunk2, 0.8)
        ));
        when(temporalReranker.rerank(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        List<MemorySearchResult> results = retriever.hybridSearch("YAML 配置", options);

        // then
        assertEquals(2, results.size());
        assertEquals("chunk-2", results.getFirst().chunkId());
        assertFalse(results.isEmpty());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MemorySearchResult>> rerankCaptor = ArgumentCaptor.forClass(List.class);
        verify(temporalReranker).rerank(rerankCaptor.capture());
        assertEquals(2, rerankCaptor.getValue().size());
        verify(indexManager, never()).scanSearch(any(), any(Integer.class));
        verify(indexManager).updateAccessCounts(List.of("chunk-2", "chunk-1"));
    }

    @Test
    void should_fallBackToScanSearch_when_hybridSearch_given_emptyVectorMatches() {
        // given
        MemoryChunk chunk = chunk("chunk-1", "mem-yaml", "USER.md", "Prefer YAML");
        when(indexManager.keywordSearch(eq("YAML 配置"), eq(3))).thenReturn(List.of());
        when(indexManager.vectorSearch(eq("YAML 配置"), eq(3))).thenReturn(List.of());
        when(indexManager.scanSearch(eq("YAML 配置"), eq(3))).thenReturn(List.of(new ScoredChunk(chunk, 0.6)));

        // when
        List<MemorySearchResult> results = retriever.hybridSearch("YAML 配置", new RetrieveOptions(1, 1000, 60, 0.0, false));

        // then
        assertEquals(1, results.size());
        assertEquals("chunk-1", results.getFirst().chunkId());
        verify(indexManager).scanSearch("YAML 配置", 3);
    }

    @Test
    void should_returnEmpty_when_hybridSearch_given_resultsBelowMinScore() {
        // given
        MemoryChunk chunk = chunk("chunk-1", "mem-yaml", "USER.md", "Prefer YAML");
        when(indexManager.keywordSearch(eq("YAML 配置"), eq(3))).thenReturn(List.of(new ScoredChunk(chunk, 0.1)));
        when(indexManager.vectorSearch(eq("YAML 配置"), eq(3))).thenReturn(List.of());
        when(indexManager.scanSearch(eq("YAML 配置"), eq(3))).thenReturn(List.of());

        // when
        List<MemorySearchResult> results = retriever.hybridSearch("YAML 配置", new RetrieveOptions(1, 1000, 60, 1.0, true));

        // then
        assertTrue(results.isEmpty());
    }

    @Test
    void should_bypassRerank_when_hybridSearch_given_temporalDecayDisabled() {
        // given
        MemoryChunk chunk = chunk("chunk-1", "mem-yaml", "USER.md", "Prefer YAML");
        when(indexManager.keywordSearch(eq("YAML 配置"), eq(3))).thenReturn(List.of(new ScoredChunk(chunk, 0.1)));
        when(indexManager.vectorSearch(eq("YAML 配置"), eq(3))).thenReturn(List.of());
        when(indexManager.scanSearch(eq("YAML 配置"), eq(3))).thenReturn(List.of());

        // when
        List<MemorySearchResult> results = retriever.hybridSearch("YAML 配置", new RetrieveOptions(1, 1000, 60, 0.0, false));

        // then
        assertEquals(1, results.size());
        assertEquals(results.getFirst().score(), results.getFirst().finalScore());
        verify(temporalReranker, never()).rerank(any());
    }

    private static MemoryChunk chunk(String chunkId, String memoryId, String filePath, String content) {
        return new MemoryChunk(
                chunkId,
                memoryId,
                filePath,
                "semantic",
                "preference",
                1,
                4,
                content,
                0.8,
                Instant.parse("2026-04-21T00:00:00Z"),
                0,
                1L
        );
    }
}
