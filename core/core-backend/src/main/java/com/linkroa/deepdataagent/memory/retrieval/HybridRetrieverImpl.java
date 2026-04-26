package com.linkroa.deepdataagent.memory.retrieval;

import com.linkroa.deepdataagent.memory.index.MemoryIndexManager;
import com.linkroa.deepdataagent.memory.model.MemoryChunk;
import com.linkroa.deepdataagent.memory.model.MemorySearchResult;
import com.linkroa.deepdataagent.memory.model.RetrieveOptions;
import com.linkroa.deepdataagent.memory.model.ScoredChunk;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 默认混合检索实现。
 *
 * <p>当前把 SQLite FTS5 关键词结果和 Java 文本相似度结果做 RRF 融合，
 * 再交给时间衰减重排器排序。后续接入向量检索时，可在这里加入第三路候选结果。</p>
 */
@Component
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
        // 多取几倍候选再融合，避免某一路检索的早期截断让最终 TopK 过窄。
        List<ScoredChunk> keywordResults = indexManager.keywordSearch(query, candidateSize);
        List<ScoredChunk> semanticResults = indexManager.scanSearch(query, candidateSize);

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
        // 访问计数会反哺时间衰减公式，常被命中的稳定记忆会更容易再次被召回。
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
            // RRF 关注名次而非绝对分数，适合融合 bm25、文本相似度、后续向量检索等不同尺度的结果。
            fusedScores.merge(chunkId, (1.0 / (Math.max(rrfK, 1) + rank)) * Math.max(scored.score(), 0.01), Double::sum);
            rank++;
        }
    }
}
