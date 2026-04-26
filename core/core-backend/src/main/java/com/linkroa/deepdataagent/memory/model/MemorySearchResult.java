package com.linkroa.deepdataagent.memory.model;

import java.time.Instant;

/**
 * 记忆检索结果。
 *
 * <p>携带分数、来源文件、行号和元数据，供 DeepLongMemory 回读 Markdown 原文并格式化为提示上下文。</p>
 */
public record MemorySearchResult(
        String chunkId,
        String memoryId,
        String filePath,
        String layer,
        String subCategory,
        int startLine,
        int endLine,
        double score,
        double finalScore,
        double importance,
        Instant createdAt,
        int accessCount
) {

    public static MemorySearchResult fromChunk(MemoryChunk chunk, double score, double finalScore) {
        return new MemorySearchResult(
                chunk.id(),
                chunk.memoryId(),
                chunk.filePath(),
                chunk.layer(),
                chunk.subCategory(),
                chunk.startLine(),
                chunk.endLine(),
                score,
                finalScore,
                chunk.importance(),
                chunk.createdAt(),
                chunk.accessCount()
        );
    }
}
