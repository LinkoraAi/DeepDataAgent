package com.linkroa.deepdataagent.rag.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkroa.deepdataagent.knowledgebase.api.ChunkBatchWriter;
import com.linkroa.deepdataagent.knowledgebase.application.contract.ChunkDraft;
import com.linkroa.deepdataagent.rag.domain.model.ChunkVO;
import com.linkroa.deepdataagent.rag.domain.model.ContentBlockVO;
import com.linkroa.deepdataagent.rag.domain.port.EmbeddingClient;
import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 纯文本管线 Stage 1 切片落库应用服务（/ 参考）。
 * <p>职责：把分块产物 {@link ChunkVO} 组装为知识库切片契约 {@link ChunkDraft} 草案列表，
 * 预趟分批计算向量与全文表示后，经 {@link ChunkBatchWriter#replaceForDocument} 完成
 * 「整篇替换」落库——同一事务内清旧切片、写新切片、更新 {@code document.chunk_count}。</p>
 *
 * <p><b>本服务不写文档状态</b>：切片写入成功不等于摄入完成，其后的实体关系抽取与图合并
 * 仍属管线未完成部分；成功终态由摄入管线收尾经 {@code ChunkBatchWriter#markProcessed}
 * 统一写入（详见该契约的状态语义）。</p>
 *
 * <p>批次语义：整篇替换为一次原子调用，<b>单文档切片数量不设上限</b>（超大文档的向量化远程调用
 * 由本服务按批分片，事务内 500~1000 条/批的批量写入由知识库侧 {@code saveBatch} 承担，
 * 事务规范 3.3），本服务不控制库内批次。</p>
 *
 * <p>不变量：本方法在事务外调用（远程/耗时计算与 DB 操作分离）；向量化远程调用
 * 在本方法内、事务外<b>预趟分批</b>完成（单批条数 {@code app.rag.ingestion.embed.batch-size}，
 * 缺省 32，配置小于 1 时按 1 即逐条）——本类即产出 pgvector 字面量并随草案一并落库，
 * 不再交由后续 Stage 计算回写。全文表示（tsvector）自起由知识库
 * 落库 SQL 内 {@code to_tsvector} 对草案原文现算，本服务不再传递预分词字面量。
 * {@code embeddingModelProfileId} 为空白时降级：跳过向量化远程调用、向量字面量置 null
 * （全文表示不受影响，始终随原文生成）；embedBatch 抛出的异常原样上抛
 * （一批失败即整篇落库失败，重跑整篇，爆炸半径与逐条时代一致）。</p>
 */
@Service
public class ChunkPersistenceService {

    /** 块元数据 JSON 序列化器（块 meta → chunk.original_item JSONB）。 */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** pgvector 字面量起始符 */
    private static final String VECTOR_LITERAL_PREFIX = "[";

    /** pgvector 字面量结束符 */
    private static final String VECTOR_LITERAL_SUFFIX = "]";

    /** pgvector 字面量分量分隔符 */
    private static final String VECTOR_COMPONENT_SEPARATOR = ",";

    /** 切片整篇替换契约（知识库属权，RAG 仅经契约写入）。 */
    private final ChunkBatchWriter chunkBatchWriter;

    /** 向量计算端口（事务外分批调用 embedBatch 产出 pgvector 字面量）。 */
    private final EmbeddingClient embeddingClient;

    /** 向量化远程调用单批条数（{@code app.rag.ingestion.embed.batch-size}，缺省 32；小于 1 时按 1 即逐条）。 */
    @Value("${app.rag.ingestion.embed.batch-size:32}")
    private int embedBatchSize;

    /**
     * 构造 Stage 1 落库服务。
     *
     * @param chunkBatchWriter 切片整篇替换契约
     * @param embeddingClient  向量计算端口
     */
    public ChunkPersistenceService(ChunkBatchWriter chunkBatchWriter, EmbeddingClient embeddingClient) {
        this.chunkBatchWriter = chunkBatchWriter;
        this.embeddingClient = embeddingClient;
    }

    /**
     * Stage 1 整篇替换落库：chunk 永远先于抽取持久化。
     * <p>落库成功只回写 {@code document.chunk_count}，<b>不写文档状态</b>——文档在整条管线
     * （含随后的抽取与图合并）结束前恒为 {@code PROCESSING}，终态由管线收尾统一置入。</p>
     * <p>先在事务外<b>预趟分批</b>计算全部切片向量，再组装草案一次性整批替换：</p>
     * <ul>
     *   <li>向量：按 {@code app.rag.ingestion.embed.batch-size} 切批经
     *       {@link EmbeddingClient#embedBatch(String, List)} 计算后编码为 pgvector 字面量
     *       （形如 {@code [0.1,0.2]}）；{@code embeddingModelProfileId} 为空白时降级——
     *       不发起远程调用、向量字面量置 null；embedBatch 抛出的异常原样上抛
     *       （事务外，无部分落库，一批失败即整篇失败）。</li>
     *   <li>全文：草案仅携带切片原文，tsvector 由知识库落库 SQL 内现算（DB 侧分词），
     *       与向量降级无关，始终生成。</li>
     * </ul>
     *
     * @param documentId              目标文档主键
     * @param chunks                  分块结果（sequence 从 1 升序且不重复），非空；数量不设上限
     * @param embeddingModelProfileId 向量模型配置ID；空白时本阶段跳过向量化（向量字面量降级为 null）
     * @param operatorId              操作人ID，可空（空则由知识库侧审计字段兜底）
     * @return 本批切片的「序号 → 落库主键」映射（落库事务内回传，恒非 null）；
     *         摄入管线据此直取主键回填抽取产物溯源，MUST NOT 再按 documentId 回查数据库
     * @throws DeepDataAgentException 分块为空（400）
     */
    public Map<Integer, Long> replaceForDocument(Long documentId, List<ChunkVO> chunks,
                                   String embeddingModelProfileId, Long operatorId) {
        if (ObjectUtils.isEmpty(chunks)) {
            throw new DeepDataAgentException("分块结果不能为空");
        }
        List<float[]> vectors = embedAll(chunks, embeddingModelProfileId);
        List<ChunkDraft> drafts = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            drafts.add(toDraft(chunks.get(i), vectors.get(i)));
        }
        return chunkBatchWriter.replaceForDocument(documentId, drafts, operatorId);
    }

    /**
     * 全部切片的向量化预趟：按单批条数切批调用端口，返回列表与入参切片<b>按固定下标对齐</b>
     * （元素为 null 表示该切片无向量：画像空白整体降级，或端口返回了空向量）。
     *
     * @param chunks                  切片列表（非空）
     * @param embeddingModelProfileId 向量模型配置ID（空白时不发起任何远程调用）
     * @return 与 chunks 下标对齐的向量列表（元素可为 null/空数组，转换字面量时按 null 处理）
     * @throws RuntimeException embedBatch 远程调用失败或返回数量与请求不一致时上抛（整篇落库失败）
     */
    private List<float[]> embedAll(List<ChunkVO> chunks, String embeddingModelProfileId) {
        if (StringUtils.isBlank(embeddingModelProfileId)) {
            return Collections.nCopies(chunks.size(), null);
        }
        int batchSize = Math.max(embedBatchSize, 1);
        List<float[]> vectors = new ArrayList<>(chunks.size());
        for (int from = 0; from < chunks.size(); from += batchSize) {
            int to = Math.min(from + batchSize, chunks.size());
            List<String> texts = new ArrayList<>(to - from);
            for (ChunkVO chunk : chunks.subList(from, to)) {
                texts.add(chunk.text());
            }
            List<float[]> batchVectors = embeddingClient.embedBatch(embeddingModelProfileId, texts);
            if (CollectionUtils.isEmpty(batchVectors) || batchVectors.size() != texts.size()) {
                throw new IllegalStateException("嵌入批量返回数量与请求不一致: "
                        + (CollectionUtils.isEmpty(batchVectors) ? 0 : batchVectors.size()) + "/" + texts.size());
            }
            vectors.addAll(batchVectors);
        }
        return vectors;
    }

    /**
     * 转换单个分块为知识库切片契约草案（含向量字面量；全文由落库 SQL 现算）。
     *
     * @param chunk  分块结果
     * @param vector 该切片的向量（预趟产出，可为 null/空数组——字面量降级为 null）
     * @return 切片草案
     */
    private ChunkDraft toDraft(ChunkVO chunk, float[] vector) {
        ContentBlockVO block = chunk.block();
        String content = chunk.text();
        String contentType = ObjectUtils.isEmpty(block) ? null : block.type();
        String vectorLiteral = ObjectUtils.isEmpty(vector) ? null : formatVectorLiteral(vector);
        return new ChunkDraft(chunk.sequence(), content, chunk.tokens(),
                serializeMeta(block), contentType, vectorLiteral);
    }

    /**
     * 将浮点向量编码为 pgvector 字面量：分量经 BigDecimal 定点展开
     * （去尾零、禁用科学计数法），逗号拼接、方括号包裹，形如 {@code [0.1,0.2,0]}。
     *
     * @param vector 非空浮点向量
     * @return pgvector 字面量
     */
    private static String formatVectorLiteral(float[] vector) {
        StringBuilder literal = new StringBuilder(VECTOR_LITERAL_PREFIX);
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                literal.append(VECTOR_COMPONENT_SEPARATOR);
            }
            literal.append(BigDecimal.valueOf(vector[i]).stripTrailingZeros().toPlainString());
        }
        return literal.append(VECTOR_LITERAL_SUFFIX).toString();
    }

    /**
     * 序列化来源块的扩展元数据为 JSON 字符串（原样透传落 chunk.original_item，DB 列为 JSONB）。
     *
     * @param block 来源内容块，可为 null
     * @return 元数据 JSON 字符串，块为空或无元数据返回 null
     * @throws DeepDataAgentException 序列化失败
     */
    private String serializeMeta(ContentBlockVO block) {
        if (ObjectUtils.isEmpty(block)) {
            return null;
        }
        Map<String, Object> meta = block.meta();
        if (ObjectUtils.isEmpty(meta)) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(meta);
        } catch (JsonProcessingException e) {
            throw new DeepDataAgentException("切片元数据 JSON 序列化失败: " + e.getMessage());
        }
    }
}
