package com.linkroa.deepdataagent.knowledgebase.infrastructure.repository;

import com.linkroa.deepdataagent.knowledgebase.domain.model.ChunkRepresentation;
import com.linkroa.deepdataagent.knowledgebase.domain.model.ChunkScoreHit;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.DocumentStatus;
import com.linkroa.deepdataagent.knowledgebase.domain.repository.ChunkRepresentationRepository;
import com.linkroa.deepdataagent.knowledgebase.infrastructure.persistence.KbAuditFieldUtils;
import com.linkroa.deepdataagent.knowledgebase.infrastructure.persistence.entity.ChunkTsvEntity;
import com.linkroa.deepdataagent.knowledgebase.infrastructure.persistence.entity.ChunkVectorEntity;
import com.linkroa.deepdataagent.knowledgebase.infrastructure.persistence.mapper.ChunkTsvMapper;
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
 * 切片派生表示仓储的关系型实现（chunk_vector / chunk_tsv 两张 1:1 影子表）。
 * <p>写入按 {@code 500} 条/批分片，向量与全文表示分别收集后各自批量落库；
 * 向量为空字面量、原文为空的派生行会被跳过；全文 tsvector 由插入 SQL 内
 * {@code to_tsvector} 按 {@link ChunkTsvMapper#FULLTEXT_TS_CONFIG} 现算（DB 侧分词）。</p>
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

    private final ChunkTsvMapper tsvMapper;

    public JdbcChunkRepresentationRepository(ChunkVectorMapper vectorMapper, ChunkTsvMapper tsvMapper) {
        this.vectorMapper = vectorMapper;
        this.tsvMapper = tsvMapper;
    }

    @Override
    public void saveBatch(List<ChunkRepresentation> representations, String operator) {
        if (ObjectUtils.isEmpty(representations)) {
            return;
        }
        List<ChunkVectorEntity> vectorEntities = new ArrayList<>(representations.size());
        List<ChunkTsvEntity> tsvEntities = new ArrayList<>(representations.size());
        for (ChunkRepresentation representation : representations) {
            collectVectorRow(representation, operator, vectorEntities);
            collectTsvRow(representation, operator, tsvEntities);
        }
        for (List<ChunkVectorEntity> batch : BatchSplitter.split(vectorEntities, INSERT_BATCH_SIZE)) {
            vectorMapper.insertBatch(batch);
        }
        for (List<ChunkTsvEntity> batch : BatchSplitter.split(tsvEntities, INSERT_BATCH_SIZE)) {
            tsvMapper.insertBatch(batch, ChunkTsvMapper.FULLTEXT_TS_CONFIG);
        }
    }

    /**
     * 单条写入（UPSERT）切片 1:1 派生表示：不存在则插入、存在则覆盖。
     * <p>同一次调用内固定序「先向量、后全文」（与 {@link #deleteByChunkIds} 的表顺序一致）；
     * 复用 {@link #collectVectorRow} / {@link #collectTsvRow} 组装实体——向量字面量空白时
     * 不落向量行、切片原文为空时不落全文行，其余表示照常写入。
     * audit 字段由上述收集方法经 {@code KbAuditFieldUtils.fillInsert} 显式补齐（自定义注解 SQL
     * 不触发 MetaObjectHandler），插入即最新态，{@code updated_at} 由 SQL 的
     * {@code EXCLUDED.updated_at} 带到覆盖分支。</p>
     * <p>不开启事务，由调用方在同一事务内调用，保证表示行与切片行同生共死。</p>
     *
     * @param representation 派生表示（为空或两种表示均缺失时零 DB 交互）
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
        List<ChunkTsvEntity> tsvRows = new ArrayList<>(1);
        collectTsvRow(representation, operator, tsvRows);
        if (ObjectUtils.isNotEmpty(tsvRows)) {
            tsvMapper.upsert(tsvRows.get(0), ChunkTsvMapper.FULLTEXT_TS_CONFIG);
        }
    }

    @Override
    public void deleteByChunkIds(List<Long> chunkIds) {
        vectorMapper.deleteByChunkIds(chunkIds);
        tsvMapper.deleteByChunkIds(chunkIds);
    }

    @Override
    public void deleteByDocumentId(Long documentId) {
        vectorMapper.deleteByDocumentId(documentId);
        tsvMapper.deleteByDocumentId(documentId);
    }

    @Override
    public void deleteByKbId(Long kbId) {
        vectorMapper.deleteByKbId(kbId);
        tsvMapper.deleteByKbId(kbId);
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
        return tsvMapper.searchByKeywords(kbId, query, limit, ChunkTsvMapper.FULLTEXT_TS_CONFIG,
                        RETRIEVAL_VISIBLE_DOCUMENT_STATUS).stream()
                .map(JdbcChunkRepresentationRepository::toTsvHit)
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
     * 全文影子行 → 检索命中投影。
     *
     * @param entity 全文影子行（含 SQL 计算出的 BM25 相关度分）
     * @return 检索命中投影
     */
    private static ChunkScoreHit toTsvHit(ChunkTsvEntity entity) {
        return new ChunkScoreHit(entity.getChunkId(), ObjectUtils.defaultIfNull(entity.getScore(), 0.0D));
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

    /**
     * 收集全文检索影子行（切片原文为空则跳过；tsvector 由插入 SQL 现算）。
     *
     * @param representation 派生表示
     * @param operator       操作人标识
     * @param collector      收集容器
     */
    private void collectTsvRow(ChunkRepresentation representation, String operator,
                               List<ChunkTsvEntity> collector) {
        if (!representation.hasChunkContent()) {
            return;
        }
        ChunkTsvEntity entity = new ChunkTsvEntity();
        entity.setKbId(representation.kbId());
        entity.setDocumentId(representation.documentId());
        entity.setChunkId(representation.chunkId());
        entity.setChunkContent(representation.chunkContent());
        KbAuditFieldUtils.fillInsert(entity, operator);
        collector.add(entity);
    }
}
