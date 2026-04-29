package com.linkroa.deepdataagent.memory.vector;

import com.linkroa.deepdataagent.memory.config.MemoryProperties;
import com.linkroa.deepdataagent.memory.embedding.MemoryEmbeddingModel;
import com.linkroa.deepdataagent.memory.model.MemoryChunk;
import dev.langchain4j.community.store.embedding.jvector.JVectorEmbeddingStore;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import io.github.jbellis.jvector.vector.VectorSimilarityFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

public class JVectorMemoryStore implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(JVectorMemoryStore.class);

    private final MemoryProperties properties;
    private final MemoryEmbeddingModel embeddingModel;
    private final Path persistenceBasePath;
    private JVectorEmbeddingStore store;

    public JVectorMemoryStore(MemoryProperties properties, MemoryEmbeddingModel embeddingModel) {
        this.properties = properties;
        this.embeddingModel = embeddingModel;
        this.persistenceBasePath = persistenceBasePath(properties);
        this.store = newStore();
    }

    public synchronized void rebuild(Collection<MemoryChunk> chunks) {
        if (!properties.getVector().isEnabled()) {
            closeCurrentStore();
            purgePersistenceFiles();
            store = newStore();
            return;
        }
        Collection<MemoryChunk> safeChunks = chunks == null ? List.of() : chunks;
        closeCurrentStore();
        purgePersistenceFiles();
        store = newStore();
        if (safeChunks.isEmpty()) {
            return;
        }
        List<String> ids = new ArrayList<>(safeChunks.size());
        List<Embedding> embeddings = new ArrayList<>(safeChunks.size());
        List<TextSegment> segments = new ArrayList<>(safeChunks.size());
        for (MemoryChunk chunk : safeChunks) {
            ids.add(chunk.id());
            embeddings.add(embeddingModel.embed(chunk.content()));
            segments.add(TextSegment.from(chunk.content()));
        }
        store.addAll(ids, embeddings, segments);
        save();
    }

    public synchronized List<VectorSearchHit> search(String query, int limit) {
        if (!properties.getVector().isEnabled() || query == null || query.isBlank()) {
            return List.of();
        }
        Embedding queryEmbedding = embeddingModel.embed(query);
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .query(query)
                .queryEmbedding(queryEmbedding)
                .maxResults(Math.max(limit, 1))
                .minScore(0.0)
                .build();
        try {
            return store.search(request).matches().stream()
                    .map(match -> new VectorSearchHit(match.embeddingId(), match.score()))
                    .toList();
        } catch (RuntimeException e) {
            log.warn("JVector memory search failed; vector results will be skipped: {}", e.getMessage());
            log.debug("JVector memory search failure", e);
            return List.of();
        }
    }

    public synchronized void clear() {
        closeCurrentStore();
        purgePersistenceFiles();
        store = newStore();
    }

    @Override
    public synchronized void close() {
        closeCurrentStore();
    }

    private void save() {
        try {
            store.save();
        } catch (IllegalStateException e) {
            purgePersistenceFiles();
        } catch (RuntimeException e) {
            log.warn("Failed to save JVector memory index at {}: {}", persistenceBasePath, e.getMessage());
            log.debug("JVector memory index save failure", e);
        }
    }

    private JVectorEmbeddingStore newStore() {
        return JVectorEmbeddingStore.builder()
                .dimension(Math.max(properties.getVector().getDimension(), 1))
                .maxDegree(Math.max(properties.getVector().getMaxDegree(), 1))
                .beamWidth(Math.max(properties.getVector().getBeamWidth(), 1))
                .neighborOverflow(Math.max(properties.getVector().getNeighborOverflow(), 1.01f))
                .alpha(Math.max(properties.getVector().getAlpha(), 0.01f))
                .similarityFunction(similarityFunction(properties.getVector().getDistanceMetric()))
                .persistencePath(persistenceBasePath.toString())
                .rebuildThreshold(Math.max(properties.getVector().getRebuildThreshold(), 0))
                .build();
    }

    private void closeCurrentStore() {
        try {
            store.removeAll();
        } catch (RuntimeException e) {
            log.debug("Failed to close current JVector memory store cleanly", e);
        }
    }

    private void purgePersistenceFiles() {
        try {
            Files.deleteIfExists(Path.of(persistenceBasePath + ".graph"));
            Files.deleteIfExists(Path.of(persistenceBasePath + ".metadata"));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to clear JVector memory index: " + persistenceBasePath, e);
        }
    }

    private static Path persistenceBasePath(MemoryProperties properties) {
        String configured = properties.getVector().getPersistencePath();
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        return Path.of(properties.getRootPath())
                .resolve(".index")
                .resolve("jvector")
                .resolve("memory-vectors")
                .toAbsolutePath()
                .normalize();
    }

    private static VectorSimilarityFunction similarityFunction(String metric) {
        String normalized = metric == null ? "" : metric.strip().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "dot", "dot-product", "dot_product" -> VectorSimilarityFunction.DOT_PRODUCT;
            case "euclidean", "l2" -> VectorSimilarityFunction.EUCLIDEAN;
            default -> VectorSimilarityFunction.COSINE;
        };
    }
}
