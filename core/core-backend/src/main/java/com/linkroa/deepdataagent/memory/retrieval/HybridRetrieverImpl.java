package com.linkroa.deepdataagent.memory.retrieval;

import com.linkroa.deepdataagent.memory.index.MemoryIndexManager;
import com.linkroa.deepdataagent.memory.model.MemoryChunk;
import com.linkroa.deepdataagent.memory.model.MemorySearchResult;
import com.linkroa.deepdataagent.memory.model.RetrieveOptions;
import com.linkroa.deepdataagent.memory.model.ScoredChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class HybridRetrieverImpl implements HybridRetriever {

    private final MemoryIndexManager indexManager;
    private final TemporalReranker temporalReranker;

    public HybridRetrieverImpl(MemoryIndexManager indexManager, TemporalReranker temporalReranker) {
        this.indexManager = indexManager;
        this.temporalReranker = temporalReranker;
    }

    @Override
    public List<MemorySearchResult> hybridSearch(String query, RetrieveOptions options) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        int candidateSize = Math.max(options.maxResults() * 3, options.maxResults());
        List<ScoredChunk> keywordResults = indexManager.keywordSearch(query, candidateSize);
        List<ScoredChunk> semanticResults = indexManager.vectorSearch(query, candidateSize);
        if (semanticResults.isEmpty()) {
            semanticResults = indexManager.scanSearch(query, candidateSize);
        }

        Map<String, Double> fusedScores = new LinkedHashMap<>();
        Map<String, MemoryChunk> chunksById = new LinkedHashMap<>();
        mergeRrf(fusedScores, chunksById, keywordResults, options.rrfK());
        mergeRrf(fusedScores, chunksById, semanticResults, options.rrfK());

        List<MemorySearchResult> results = fusedScores.entrySet().stream()
                .map(entry -> MemorySearchResult.fromChunk(
                        chunksById.get(entry.getKey()),
                        entry.getValue(),
                        entry.getValue()
                ))
                .filter(result -> result.score() >= options.minScore())
                .sorted(Comparator.comparingDouble(MemorySearchResult::score).reversed())
                .limit(candidateSize)
                .toList();

        if (options.enableTemporalDecay()) {
            results = temporalReranker.rerank(results);
        }
        results = results.stream().limit(options.maxResults()).toList();
        indexManager.updateAccessCounts(results.stream().map(MemorySearchResult::chunkId).toList());
        return results;
    }

    private static void mergeRrf(
            Map<String, Double> fusedScores,
            Map<String, MemoryChunk> chunksById,
            List<ScoredChunk> chunks,
            int rrfK
    ) {
        List<ScoredChunk> ranked = new ArrayList<>(chunks);
        ranked.sort(Comparator.comparingDouble(ScoredChunk::score).reversed());
        int rank = 1;
        for (ScoredChunk scored : ranked) {
            String chunkId = scored.chunk().id();
            chunksById.putIfAbsent(chunkId, scored.chunk());
            fusedScores.merge(chunkId, (1.0 / (Math.max(rrfK, 1) + rank)) * Math.max(scored.score(), 0.01), Double::sum);
            rank++;
        }
    }
}
