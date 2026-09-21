package com.linkroa.deepdataagent.rag.domain.model;

/**
 * chunk 正文回取值对象（经 knowledgebase 只读通道回取的正文快照）。
 *
 * @param chunkId        chunk 唯一标识
 * @param content        chunk 正文内容
 * @param sourceFileName 来源文件名（供引用列表显示，可为 null）
 */
public record ChunkText(
        Long chunkId,
        String content,
        String sourceFileName
) {
}