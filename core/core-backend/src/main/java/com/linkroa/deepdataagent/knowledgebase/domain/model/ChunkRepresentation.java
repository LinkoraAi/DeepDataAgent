package com.linkroa.deepdataagent.knowledgebase.domain.model;

import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import java.time.OffsetDateTime;

/**
 * 切片派生表示（1:1 影子记录）。
 * <p>与 {@link Chunk} 一一对应，承载检索侧计算完成后回写的向量与全文检索表示，
 * 分别落在 chunk_vector 与 chunk_tsv 两张 1:1 影子表上。</p>
 * <p>向量字面量由消费方（RAG）计算后传入（知识库侧不做任何远程调用）；
 * 全文表示自起<b>不再携带预分词字面量</b>——
 * {@code chunkContent} 为切片原文，tsvector 由写入 SQL 内 {@code to_tsvector} 现算。
 * 两者均为空时视为无需落任何派生行。</p>
 *
 * @param id              主键，新建时为 null
 * @param kbId            所属知识库 ID
 * @param documentId      所属文档 ID
 * @param chunkId         对应切片 ID（1:1）
 * @param embeddingVector 向量字面量（形如 "[0.1,0.2,...]"），为空表示不落向量行
 * @param chunkContent    切片原文（全文表示现算的输入），为空表示不落全文行
 * @param createdAt       创建时间
 * @param updatedAt       更新时间
 */
public record ChunkRepresentation(
        Long id,
        Long kbId,
        Long documentId,
        Long chunkId,
        String embeddingVector,
        String chunkContent,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    public ChunkRepresentation {
        if (ObjectUtils.isEmpty(kbId)) {
            throw new IllegalArgumentException("知识库 ID 不能为空");
        }
        if (ObjectUtils.isEmpty(documentId)) {
            throw new IllegalArgumentException("文档 ID 不能为空");
        }
        if (ObjectUtils.isEmpty(chunkId)) {
            throw new IllegalArgumentException("切片 ID 不能为空");
        }
    }

    /**
     * 创建切片派生表示（不含主键与时间）。
     *
     * @param kbId            所属知识库 ID
     * @param documentId      所属文档 ID
     * @param chunkId         对应切片 ID
     * @param embeddingVector 向量字面量，可为空
     * @param chunkContent    切片原文（全文表示现算输入），可为空
     * @return 切片派生表示
     */
    public static ChunkRepresentation create(Long kbId, Long documentId, Long chunkId,
                                             String embeddingVector, String chunkContent) {
        return new ChunkRepresentation(null, kbId, documentId, chunkId, embeddingVector, chunkContent, null, null);
    }

    /**
     * 从持久化记录还原。
     *
     * @param id              主键
     * @param kbId            所属知识库 ID
     * @param documentId      所属文档 ID
     * @param chunkId         对应切片 ID
     * @param embeddingVector 向量字面量
     * @param chunkContent    切片原文（全文表示现算输入）
     * @param createdAt       创建时间
     * @param updatedAt       更新时间
     * @return 切片派生表示
     */
    public static ChunkRepresentation restore(Long id, Long kbId, Long documentId, Long chunkId,
                                              String embeddingVector, String chunkContent,
                                              OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        return new ChunkRepresentation(id, kbId, documentId, chunkId, embeddingVector, chunkContent, createdAt, updatedAt);
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
     * 是否需要在 chunk_tsv 表落行。
     *
     * @return true 表示携带非空白切片原文（tsvector 由写入 SQL 现算）
     */
    public boolean hasChunkContent() {
        return StringUtils.isNotBlank(chunkContent);
    }

    /**
     * 是否两种派生表示都缺失。
     *
     * @return true 表示无需落任何派生行
     */
    public boolean isEmptyRepresentation() {
        return !hasEmbeddingVector() && !hasChunkContent();
    }
}
