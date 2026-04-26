package com.linkroa.deepdataagent.memory.index;

import com.linkroa.deepdataagent.memory.config.MemoryProperties;
import com.linkroa.deepdataagent.memory.file.MarkdownFileManager;
import com.linkroa.deepdataagent.memory.model.MemoryChunk;
import com.linkroa.deepdataagent.memory.model.ScoredChunk;
import com.linkroa.deepdataagent.memory.util.MemoryText;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static com.linkroa.deepdataagent.memory.config.MemoryIndexJdbcConfiguration.TRANSACTION_TEMPLATE_BEAN;

/**
 * SQLite 索引生命周期管理器。
 *
 * <p>负责初始化 files/chunks/FTS5 schema、从 Markdown 全量重建索引、
 * 对变更文件做增量同步，并提供关键词检索与文本扫描回退。该组件保存的是可丢弃索引，
 * Markdown 文件才是最终数据来源。</p>
 */
@Component
public class MemoryIndexManager {

    private static final Logger log = LoggerFactory.getLogger(MemoryIndexManager.class);

    private final MemoryProperties properties;
    private final MarkdownFileManager fileManager;
    private final MarkdownChunker chunker;
    private final MemoryIndexSchemaInitializer schemaInitializer;
    private final MemoryIndexRepository repository;
    private final TransactionTemplate transactionTemplate;
    private volatile boolean ftsAvailable = true;

    public MemoryIndexManager(
            MemoryProperties properties,
            MarkdownFileManager fileManager,
            MarkdownChunker chunker,
            MemoryIndexSchemaInitializer schemaInitializer,
            MemoryIndexRepository repository,
            @Qualifier(TRANSACTION_TEMPLATE_BEAN) TransactionTemplate transactionTemplate
    ) {
        this.properties = properties;
        this.fileManager = fileManager;
        this.chunker = chunker;
        this.schemaInitializer = schemaInitializer;
        this.repository = repository;
        this.transactionTemplate = transactionTemplate;
    }

    @PostConstruct
    public void initialize() {
        ftsAvailable = schemaInitializer.initializeSchema();
        if (properties.getIndex().isRebuildOnStartup()) {
            rebuildAllIndexes();
        }
    }

    public synchronized void rebuildAllIndexes() {
        transactionTemplate.executeWithoutResult(status -> repository.clearAll(ftsAvailable));
        syncIncremental(fileManager.scanMarkdownFiles());
    }

    public synchronized void syncIncremental(Collection<String> relativePaths) {
        if (relativePaths == null || relativePaths.isEmpty()) {
            return;
        }
        for (String path : relativePaths) {
            syncFile(path);
        }
    }

    public List<ScoredChunk> keywordSearch(String query, int limit) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        if (ftsAvailable) {
            try {
                List<ScoredChunk> ftsResults = repository.ftsSearch(toFtsQuery(query), limit);
                if (!ftsResults.isEmpty()) {
                    return ftsResults;
                }
            } catch (DataAccessException e) {
                log.debug("FTS search failed; falling back to Java scanning: {}", e.getMessage());
            }
        }
        return scanSearch(query, limit);
    }

    public List<ScoredChunk> scanSearch(String query, int limit) {
        return repository.loadAllChunks().stream()
                .map(chunk -> new ScoredChunk(chunk, MemoryText.lexicalScore(query, chunk.content())))
                .filter(scored -> scored.score() > 0.0)
                .sorted((left, right) -> Double.compare(right.score(), left.score()))
                .limit(Math.max(limit, 1))
                .toList();
    }

    public MemoryChunk findChunk(String chunkId) {
        return repository.findChunk(chunkId);
    }

    public void updateAccessCounts(Collection<String> chunkIds) {
        try {
            repository.updateAccessCounts(chunkIds);
        } catch (DataAccessException e) {
            log.debug("Failed to update memory access counts", e);
        }
    }

    private void syncFile(String relativePath) {
        String content = fileManager.readIfExists(relativePath);
        String hash = MemoryText.sha256(content);
        if (content.isBlank()) {
            removeFile(relativePath);
            return;
        }
        if (hash.equals(repository.currentFileHash(relativePath))) {
            return;
        }

        List<MemoryChunk> chunks = chunker.chunk(relativePath, content);
        String layer = chunker.layerOf(relativePath, content);
        String subCategory = chunker.subCategoryOf(relativePath, content);
        int lineCount = content.split("\\R", -1).length;
        long now = System.currentTimeMillis();

        try {
            transactionTemplate.executeWithoutResult(status -> repository.replaceFileChunks(
                    relativePath,
                    layer,
                    subCategory,
                    hash,
                    lineCount,
                    now,
                    chunks,
                    ftsAvailable
            ));
        } catch (DataAccessException e) {
            throw new IllegalStateException("Failed to sync memory index: " + relativePath, e);
        }
    }

    private void removeFile(String relativePath) {
        try {
            transactionTemplate.executeWithoutResult(status -> repository.removeFile(relativePath, ftsAvailable));
        } catch (DataAccessException e) {
            throw new IllegalStateException("Failed to remove memory index: " + relativePath, e);
        }
    }

    private static String toFtsQuery(String query) {
        List<String> terms = new ArrayList<>(MemoryText.tokens(query));
        if (terms.isEmpty()) {
            return "";
        }
        List<String> escaped = new ArrayList<>();
        for (String term : terms) {
            String safe = term.replace("\"", "\"\"");
            if (!safe.isBlank()) {
                escaped.add("\"" + safe + "\"");
            }
        }
        return String.join(" OR ", escaped);
    }
}
