package com.linkroa.deepdataagent.memory.retrieval;

import com.linkroa.deepdataagent.memory.config.MemoryProperties;
import com.linkroa.deepdataagent.memory.model.MemorySearchResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MockitoExtension.class)
class TemporalRerankerTest {

    @Test
    void should_rankSemanticKnowledgeHigher_when_rerank_given_oldSemanticAndEpisodicMemories() {
        // given
        TemporalReranker reranker = new TemporalReranker(new MemoryProperties());
        Instant old = Instant.now().minus(30, ChronoUnit.DAYS);
        MemorySearchResult semantic = result("semantic", "fact", old);
        MemorySearchResult episodic = result("episodic", "event", old);

        // when
        var results = reranker.rerank(List.of(episodic, semantic));

        // then
        assertEquals("semantic", results.getFirst().layer());
        assertTrue(results.getFirst().finalScore() > results.getLast().finalScore());
    }

    @Test
    void should_boostFrequentlyRecalledMemory_when_rerank_given_higherAccessCount() {
        // given
        MemoryProperties properties = new MemoryProperties();
        properties.getTemporal().setRecallBoostFactor(0.5);
        TemporalReranker reranker = new TemporalReranker(properties);
        Instant createdAt = Instant.now().minus(2, ChronoUnit.DAYS);
        MemorySearchResult neverAccessed = result("semantic", "fact", createdAt);
        MemorySearchResult oftenAccessed = new MemorySearchResult(
                "chunk-hot",
                "mem-hot",
                "MEMORY.md",
                "semantic",
                "fact",
                1,
                2,
                0.5,
                0.5,
                0.8,
                createdAt,
                5
        );

        // when
        var results = reranker.rerank(List.of(neverAccessed, oftenAccessed));

        // then
        assertEquals("chunk-hot", results.getFirst().chunkId());
        assertTrue(results.getFirst().finalScore() > neverAccessed.score());
    }

    @Test
    void should_applyDecayAcrossAllCategories_when_rerank_given_allSupportedMemoryLayers() {
        // given
        TemporalReranker reranker = new TemporalReranker(new MemoryProperties());
        Instant old = Instant.now().minus(7, ChronoUnit.DAYS);

        // when
        var results = reranker.rerank(List.of(
                result("semantic", "preference", old),
                result("semantic", "rule", old),
                result("skills", "pattern", old),
                result("skills", "skill", old),
                result("episodic", "failure", old),
                result("unknown", "general", old),
                result(null, null, old)
        ));

        // then
        assertEquals(7, results.size());
        assertTrue(results.stream().allMatch(result -> result.finalScore() > 0.0));
        assertTrue(results.stream().anyMatch(result -> "preference".equals(result.subCategory())));
        assertTrue(results.stream().anyMatch(result -> result.layer() == null && result.subCategory() == null));
    }

    @Test
    void should_applyMinimumImportanceAndIgnoreNegativeAccessCount_when_rerank_given_edgeCaseScores() {
        // given
        TemporalReranker reranker = new TemporalReranker(new MemoryProperties());
        MemorySearchResult weakOldFailure = new MemorySearchResult(
                "chunk-weak",
                "mem-weak",
                "episodic/old.md",
                "episodic",
                "failure",
                1,
                2,
                0.5,
                0.5,
                0.0,
                Instant.now().minus(365, ChronoUnit.DAYS),
                -3
        );

        // when
        var results = reranker.rerank(List.of(weakOldFailure));

        // then
        assertEquals("chunk-weak", results.getFirst().chunkId());
        assertEquals(0.025, results.getFirst().finalScore(), 0.0001);
    }

    private static MemorySearchResult result(String layer, String subCategory, Instant createdAt) {
        return new MemorySearchResult(
                "chunk-" + layer + "-" + subCategory + "-" + 0,
                "mem-" + layer,
                "MEMORY.md",
                layer,
                subCategory,
                1,
                2,
                0.5,
                0.5,
                0.8,
                createdAt,
                0
        );
    }
}
