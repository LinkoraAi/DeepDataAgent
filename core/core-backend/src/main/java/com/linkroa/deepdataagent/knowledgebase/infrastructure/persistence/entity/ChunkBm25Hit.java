package com.linkroa.deepdataagent.knowledgebase.infrastructure.persistence.entity;

import lombok.Data;

/**
 * 切片 BM25 检索命中投影（只读，非表实体）。
 * <p>承载全文检索 SQL 的切片主键与 BM25 相关度分；分值已在 SQL 出口取反为
 * 「越大越相关」的非负值，供上层直接参与融合与排序。</p>
 */
@Data
public class ChunkBm25Hit {

    /** 命中切片主键（对应 chunk.id）。 */
    private Long chunkId;

    /** BM25 相关度分（已取反，越大越相关）。 */
    private Double score;
}