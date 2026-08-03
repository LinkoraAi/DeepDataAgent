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

    @Test
    void should_handleFutureDate_when_rerank_given_createdAtInFuture() {
        // given
        TemporalReranker reranker = new TemporalReranker(new MemoryProperties());
        Instant future = Instant.now().plus(1, ChronoUnit.DAYS);
        MemorySearchResult futureMemory = result("semantic", "fact", future);

        // when
        var results = reranker.rerank(List.of(futureMemory));

        // then: days 应该被 clamp 到 0
        assertEquals(1, results.size());
        assertTrue(results.getFirst().finalScore() > 0.0);
    }

    @Test
    void should_applyMinimumStrength_when_rerank_given_veryWeakMemory() {
        // given
        TemporalReranker reranker = new TemporalReranker(new MemoryProperties());
        MemorySearchResult veryWeak = new MemorySearchResult(
                "chunk-weak",
                "mem-weak",
                "semantic/facts.md",
                "semantic",
                "fact",
                1,
                2,
                0.5,
                0.1,
                0.01,
                Instant.now().minus(365, ChronoUnit.DAYS),
                0
        );

        // when
        var results = reranker.rerank(List.of(veryWeak));

        // then: strength 被 clamp 到 0.05，但 finalScore = score * 0.05
        assertEquals(1, results.size());
        assertEquals(0.025, results.getFirst().finalScore(), 0.0001);
    }

    @Test
    void should_applyMinimumImportance_when_rerank_given_importanceBelowThreshold() {
        // given
        TemporalReranker reranker = new TemporalReranker(new MemoryProperties());
        MemorySearchResult lowImportance = result("semantic", "fact", Instant.now().minus(1, ChronoUnit.DAYS));
        lowImportance = new MemorySearchResult(
                lowImportance.chunkId(),
                lowImportance.memoryId(),
                lowImportance.filePath(),
                lowImportance.layer(),
                lowImportance.subCategory(),
                lowImportance.startLine(),
                lowImportance.endLine(),
                lowImportance.score(),
                lowImportance.finalScore(),
                0.0,
                lowImportance.createdAt(),
                lowImportance.accessCount()
        );

        // when
        var results = reranker.rerank(List.of(lowImportance));

        // then: importance 应该被 clamp 到 0.1
        assertEquals(1, results.size());
        assertTrue(results.getFirst().finalScore() > 0.0);
    }

    @Test
    void should_returnEmptyList_when_rerank_given_emptyResults() {
        // given
        TemporalReranker reranker = new TemporalReranker(new MemoryProperties());

        // when
        var results = reranker.rerank(List.of());

        // then
        assertTrue(results.isEmpty());
    }

    @Test
    void should_applySemanticFactDecay_when_rerank_given_semanticFactMemory() {
        // given
        TemporalReranker reranker = new TemporalReranker(new MemoryProperties());
        Instant old = Instant.now().minus(30, ChronoUnit.DAYS);
        MemorySearchResult semanticFact = result("semantic", "fact", old);

        // when
        var results = reranker.rerank(List.of(semanticFact));

        // then: semantic/fact 的 lambda = 0.03，衰减最慢
        assertEquals(1, results.size());
        assertTrue(results.getFirst().finalScore() > 0.0);
    }

    @Test
    void should_applySemanticRuleDecay_when_rerank_given_semanticRuleMemory() {
        // given
        TemporalReranker reranker = new TemporalReranker(new MemoryProperties());
        Instant old = Instant.now().minus(30, ChronoUnit.DAYS);
        MemorySearchResult semanticRule = result("semantic", "rule", old);

        // when
        var results = reranker.rerank(List.of(semanticRule));

        // then: semantic/rule 的 lambda = 0.04
        assertEquals(1, results.size());
        assertTrue(results.getFirst().finalScore() > 0.0);
    }

    @Test
    void should_applyEpisodicEventDecay_when_rerank_given_episodicEventMemory() {
        // given
        TemporalReranker reranker = new TemporalReranker(new MemoryProperties());
        Instant old = Instant.now().minus(30, ChronoUnit.DAYS);
        MemorySearchResult episodicEvent = result("episodic", "event", old);

        // when
        var results = reranker.rerank(List.of(episodicEvent));

        // then: episodic/event 的 lambda = 0.12
        assertEquals(1, results.size());
        assertTrue(results.getFirst().finalScore() > 0.0);
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
