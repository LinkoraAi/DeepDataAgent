package com.linkroa.deepdataagent.knowledgebase.domain.repository;

import com.linkroa.deepdataagent.knowledgebase.domain.model.ChunkRepresentation;
import com.linkroa.deepdataagent.knowledgebase.domain.model.ChunkScoreHit;

import java.util.List;

/**
 * 切片派生表示仓储接口（chunk_vector / chunk_tsv 两张 1:1 影子表）。
 * <p>生命周期与切片严格绑定：切片被清退时，其派生表示必须在同一事务内同步清退；
 * 检索侧只读能力（VECTOR / BM25 两路召回）也由本接口暴露，供上层经只读消费面调起。</p>
 */
public interface ChunkRepresentationRepository {

    /**
     * 批量写入切片派生表示。
     * <p>内部按批次分片提交，向量与全文表示分别落到各自的影子表；
     * 字面量为空的派生行会被跳过。</p>
     *
     * @param representations 派生表示集合
     * @param operator        操作人标识，用于补齐 updated_by / created_by
     */
    void saveBatch(List<ChunkRepresentation> representations, String operator);

    /**
     * 单条写入切片派生表示（UPSERT 语义）：不存在则插入、存在则覆盖。
     * <p>人工切片新增/编辑的同步表示写入契约：向量字面量为空则跳过向量行、全文表示照写；
     * 切片原文为空则跳过全文行；两种表示均缺失或入参为空时不做任何写入。
     * 全文 tsvector 由写入 SQL 对切片原文现算（分词职责在存储侧）。</p>
     * <p>本契约自身<b>不开启事务</b>，必须由调用方在同一事务内调用，
     * 以保证表示行与切片行同生共死（一起提交或整体回滚）。</p>
     *
     * @param representation 派生表示（为空或两种表示均缺失时零写入）
     * @param operator       操作人标识，用于补齐 created_by / updated_by（空白回落系统账号）
     */
    void upsert(ChunkRepresentation representation, String operator);

    /**
     * 按切片 ID 集合清退派生表示。
     *
     * @param chunkIds 切片 ID 集合
     */
    void deleteByChunkIds(List<Long> chunkIds);

    /**
     * 按文档 ID 清退该文档全部切片的派生表示。
     *
     * @param documentId 文档 ID
     */
    void deleteByDocumentId(Long documentId);

    /**
     * 按知识库 ID 清退该知识库全部切片的派生表示。
     *
     * @param kbId 知识库 ID
     */
    void deleteByKbId(Long kbId);

    /**
     * VECTOR 通道只读检索：按余弦距离阈值命中切片并返回相关度分。
     * <p>查询向量以字面量字符串传入（本 BC 无 float[] → 字面量转换器，由消费方
     * rag BC 在跨边界前自行转换）；阈值约定为余弦距离阈值（= {@code 1 - similarThreshold}）。
     * 只读不加锁，不区分知识库生命周期状态。</p>
     * <p><b>可见性约束</b>：仅命中所属文档处于「已处理」（{@code PROCESSED}）状态的切片，
     * 摄入中、失败与删除链状态的文档其切片 MUST NOT 进入召回结果（不占用 {@code limit} 名额）。</p>
     *
     * @param kbId       知识库主键，可为空（空则返回空列表）
     * @param threshold  余弦距离阈值
     * @param vecLiteral 查询向量字面量，形如 "[0.1,0.2,...]"，可为空（空则返回空列表）
     * @param limit      结果数量上限，非正数返回空列表
     * @return 命中切片及相关度分（按余弦距离升序，即相似度降序）；无命中或入参非法返回空列表
     */
    List<ChunkScoreHit> searchByVector(Long kbId, double threshold, String vecLiteral, int limit);

    /**
     * BM25 通道只读检索：按关键词做全文检索并返回相关度分。
     * <p>关键词按 websearch_to_tsquery 语法解析，命中返回 ts_rank_cd 相关度分。
     * 只读不加锁，不区分知识库生命周期状态。</p>
     * <p><b>可见性约束</b>：仅命中所属文档处于「已处理」（{@code PROCESSED}）状态的切片，
     * 摄入中、失败与删除链状态的文档其切片 MUST NOT 进入召回结果（不占用 {@code limit} 名额）。</p>
     *
     * @param kbId  知识库主键，可为空（空则返回空列表）
     * @param query 检索关键词文本，可为空（空则返回空列表）
     * @param limit 结果数量上限，非正数返回空列表
     * @return 命中切片及相关度分（按相关度降序）；无命中或入参非法返回空列表
     */
    List<ChunkScoreHit> searchByKeywords(Long kbId, String query, int limit);
}
