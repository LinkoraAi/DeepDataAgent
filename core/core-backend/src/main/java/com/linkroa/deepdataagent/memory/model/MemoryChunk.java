package com.linkroa.deepdataagent.memory.model;

import java.time.Instant;

/**
 * Markdown 文件的索引分块。
 *
 * <p>记录分块文本及其在源 Markdown 文件中的行号范围。content 是索引副本，
 * 最终展示仍应通过 filePath/startLine/endLine 回读源文件。</p>
 */
public record MemoryChunk(
        String id,
        String memoryId,
        String filePath,
        String layer,
        String subCategory,
        int startLine,
        int endLine,
        String content,
        double importance,
        Instant createdAt,
        int accessCount,
        long updatedAt
) {
}
