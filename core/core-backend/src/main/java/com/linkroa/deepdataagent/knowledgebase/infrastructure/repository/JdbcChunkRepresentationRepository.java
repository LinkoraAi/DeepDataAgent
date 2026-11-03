package com.linkroa.deepdataagent.knowledgebase.infrastructure.repository;

import com.linkroa.deepdataagent.knowledgebase.domain.model.ChunkRepresentation;
import com.linkroa.deepdataagent.knowledgebase.domain.model.ChunkScoreHit;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.DocumentStatus;
import com.linkroa.deepdataagent.knowledgebase.domain.repository.ChunkRepresentationRepository;
import com.linkroa.deepdataagent.knowledgebase.infrastructure.persistence.KbAuditFieldUtils;
import com.linkroa.deepdataagent.knowledgebase.infrastructure.persistence.entity.ChunkBm25Hit;
import com.linkroa.deepdataagent.knowledgebase.infrastructure.persistence.entity.ChunkVectorEntity;
import com.linkroa.deepdataagent.knowledgebase.infrastructure.persistence.mapper.ChunkMapper;
import com.linkroa.deepdataagent.knowledgebase.infrastructure.persistence.mapper.ChunkVectorMapper;
import com.linkroa.deepdataagent.shared.util.BatchSplitter;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 切片派生表示仓储的关系型实现（仅 chunk_vector 一张 1:1 影子表）。
 * <p>写入按 {@code 500} 条/批分片，向量字面量为空的派生行会被跳过。</p>
 * <p><b>全文检索</b>：不再有派生列或影子表——BM25 索引直接建在
 * {@code chunk.chunk_content} 上，切片落库 / 更新 / 删除即由索引自动维护；
 * 检索 SQL 落在 {@link ChunkMapper}，本类仅做委托与领域投影转换。</p>
 * <p><b>读侧可见性</b>：两条检索通道（向量 / 全文）均只返回「已处理」文档的切片，
 * 摄入中、失败与删除链状态的文档其切片不进入召回结果。</p>
 */
@Repository
public class JdbcChunkRepresentationRepository implements ChunkRepresentationRepository {

    /** 批量写入分片大小（符合事务规范 500~1000 条/批） */
    private static final int INSERT_BATCH_SIZE = 500;

    /**
     * 检索可见性过滤取值：仅「已处理」文档的切片可被召回。
     * <p>取值来源为 {@link DocumentStatus#PROCESSED} 的枚举名（单一真相源，避免 SQL 内硬编码字面量）：
     * 文档处于摄入中（{@code PENDING / PROCESSING}，含重新解析窗口）、已失败（{@code FAILED}）
     * 或删除链（{@code DELETING / DELETE_FAILED}）时，其切片 SHALL NOT 进入检索结果。</p>
     */
    private static final String RETRIEVAL_VISIBLE_DOCUMENT_STATUS = DocumentStatus.PROCESSED.name();

    private final ChunkVectorMapper vectorMapper;

    private final ChunkMapper chunkMapper;

    public JdbcChunkRepresentationRepository(ChunkVectorMapper vectorMapper, ChunkMapper chunkMapper) {
        this.vectorMapper = vectorMapper;
        this.chunkMapper = chunkMapper;
    }

    @Override
    public void saveBatch(List<ChunkRepresentation> representations, String operator) {
        if (ObjectUtils.isEmpty(representations)) {
            return;
        }
        List<ChunkVectorEntity> vectorEntities = new ArrayList<>(representations.size());
        for (ChunkRepresentation representation : representations) {
            collectVectorRow(representation, operator, vectorEntities);
        }
        for (List<ChunkVectorEntity> batch : BatchSplitter.split(vectorEntities, INSERT_BATCH_SIZE)) {
            vectorMapper.insertBatch(batch);
        }
    }

    /**
     * 单条写入（UPSERT）切片向量派生表示：不存在则插入、存在则覆盖。
     * <p>向量字面量空白时不落向量行；全文索引无需任何应用侧写入。
     * audit 字段由 {@link #collectVectorRow} 经 {@code KbAuditFieldUtils.fillInsert} 显式补齐
     * （自定义注解 SQL 不触发 MetaObjectHandler），{@code updated_at} 由 SQL 的
     * {@code EXCLUDED.updated_at} 带到覆盖分支。</p>
     * <p>不开启事务，由调用方在同一事务内调用，保证表示行与切片行同生共死。</p>
     *
     * @param representation 派生表示（为空或向量缺失时零 DB 交互）
     * @param operator       操作人标识，用于补齐 created_by / updated_by
     */
    @Override
    public void upsert(ChunkRepresentation representation, String operator) {
        if (ObjectUtils.isEmpty(representation) || representation.isEmptyRepresentation()) {
            return;
        }
        List<ChunkVectorEntity> vectorRows = new ArrayList<>(1);
        collectVectorRow(representation, operator, vectorRows);
        if (ObjectUtils.isNotEmpty(vectorRows)) {
            vectorMapper.upsert(vectorRows.get(0));
        }
    }

    @Override
    public void deleteByChunkIds(List<Long> chunkIds) {
        vectorMapper.deleteByChunkIds(chunkIds);
    }

    @Override
    public void deleteByDocumentId(Long documentId) {
        vectorMapper.deleteByDocumentId(documentId);
    }

    @Override
    public void deleteByKbId(Long kbId) {
        vectorMapper.deleteByKbId(kbId);
    }

    @Override
    public List<ChunkScoreHit> searchByVector(Long kbId, double threshold, String vecLiteral, int limit) {
        if (ObjectUtils.isEmpty(kbId) || StringUtils.isBlank(vecLiteral) || limit <= 0) {
            return Collections.emptyList();
        }
        return vectorMapper.searchByVector(kbId, threshold, vecLiteral, limit,
                        RETRIEVAL_VISIBLE_DOCUMENT_STATUS).stream()
                .map(JdbcChunkRepresentationRepository::toVectorHit)
                .collect(Collectors.toList());
    }

    @Override
    public List<ChunkScoreHit> searchByKeywords(Long kbId, String query, int limit) {
        if (ObjectUtils.isEmpty(kbId) || StringUtils.isBlank(query) || limit <= 0) {
            return Collections.emptyList();
        }
        return chunkMapper.searchByBm25(kbId, query, limit, RETRIEVAL_VISIBLE_DOCUMENT_STATUS).stream()
                .map(JdbcChunkRepresentationRepository::toBm25Hit)
                .collect(Collectors.toList());
    }

    /**
     * 向量影子行 → 检索命中投影。
     *
     * @param entity 向量影子行（含 SQL 计算出的余弦相似度分）
     * @return 检索命中投影
     */
    private static ChunkScoreHit toVectorHit(ChunkVectorEntity entity) {
        return new ChunkScoreHit(entity.getChunkId(), ObjectUtils.defaultIfNull(entity.getScore(), 0.0D));
    }

    /**
     * BM25 命中投影行 → 领域检索命中投影。
     *
     * @param hit BM25 命中行（分已取反为「越大越相关」）
     * @return 检索命中投影
     */
    private static ChunkScoreHit toBm25Hit(ChunkBm25Hit hit) {
        return new ChunkScoreHit(hit.getChunkId(), ObjectUtils.defaultIfNull(hit.getScore(), 0.0D));
    }

    /**
     * 收集向量影子行（字面量为空则跳过）。
     *
     * @param representation 派生表示
     * @param operator       操作人标识
     * @param collector      收集容器
     */
    private void collectVectorRow(ChunkRepresentation representation, String operator,
                                 List<ChunkVectorEntity> collector) {
        if (!representation.hasEmbeddingVector()) {
            return;
        }
        ChunkVectorEntity entity = new ChunkVectorEntity();
        entity.setKbId(representation.kbId());
        entity.setDocumentId(representation.documentId());
        entity.setChunkId(representation.chunkId());
        entity.setChunkVector(representation.embeddingVector());
        KbAuditFieldUtils.fillInsert(entity, operator);
        collector.add(entity);
    }
}