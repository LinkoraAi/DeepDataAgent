package com.linkroa.deepdataagent.knowledgebase.domain.model;

import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

/**
 * 切片派生表示（1:1 影子记录）。
 * <p>与 {@link Chunk} 一一对应，承载检索侧计算完成后回写的向量表示，落在
 * {@code chunk_vector} 影子表上。全文检索不再有派生表示：BM25 索引直接建在
 * {@code chunk.chunk_content} 上，切片落库即自动进入索引。</p>
 * <p>向量字面量由消费方（RAG）计算后传入（知识库侧不做任何远程调用）；
 * 为空表示无需落向量行。</p>
 *
 * @param kbId            所属知识库 ID
 * @param documentId      所属文档 ID
 * @param chunkId         对应切片 ID（1:1）
 * @param embeddingVector 向量字面量（形如 "[0.1,0.2,...]"），为空表示不落向量行
 */
public record ChunkRepresentation(
        Long kbId,
        Long documentId,
        Long chunkId,
        String embeddingVector
) {

    public ChunkRepresentation {
        if (ObjectUtils.isEmpty(kbId)) {
            throw new IllegalArgumentException("知识库ID不能为空");
        }
        if (ObjectUtils.isEmpty(documentId)) {
            throw new IllegalArgumentException("文档ID不能为空");
        }
        if (ObjectUtils.isEmpty(chunkId)) {
            throw new IllegalArgumentException("切片ID不能为空");
        }
    }

    /**
     * 创建切片派生表示。
     *
     * @param kbId            所属知识库 ID
     * @param documentId      所属文档 ID
     * @param chunkId         对应切片 ID
     * @param embeddingVector 向量字面量，可为空
     * @return 切片派生表示
     */
    public static ChunkRepresentation create(Long kbId, Long documentId, Long chunkId,
                                             String embeddingVector) {
        return new ChunkRepresentation(kbId, documentId, chunkId, embeddingVector);
    }

    /**
     * 是否需要在 chunk_vector 表落行。
     *
     * @return true 表示携带有效向量字面量
     */
    public boolean hasEmbeddingVector() {
        return StringUtils.isNotBlank(embeddingVector);
    }

    /**
     * 是否无需落任何派生行。
     *
     * @return true 表示向量缺失（全文索引由主表自动维护，不依赖派生表示）
     */
    public boolean isEmptyRepresentation() {
        return !hasEmbeddingVector();
    }
}