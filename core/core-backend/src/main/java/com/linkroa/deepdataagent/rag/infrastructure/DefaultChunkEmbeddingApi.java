package com.linkroa.deepdataagent.rag.infrastructure;

import com.linkroa.deepdataagent.knowledgebase.api.ChunkEmbeddingApi;
import com.linkroa.deepdataagent.knowledgebase.api.KnowledgeBaseApi;
import com.linkroa.deepdataagent.rag.domain.port.EmbeddingClient;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * {@link ChunkEmbeddingApi} 契约的默认实现（防腐层 / 契约适配器）。
 * <p>本类只做契约适配与向量字面量编码，不含业务规则：先经知识库契约
 * {@link KnowledgeBaseApi#findEmbeddingModelProfileIdByKbId(Long)} 解析该库生效的嵌入模型
 * profileId（未配置时零远程调用直接返回「无向量」），再经向量端口
 * {@link EmbeddingClient#embedBatch(String, List)} 对切片正文取向量，编码为 pgvector 字面量回传。</p>
 *
 * <p>消费方为知识库 BC 的人工切片变更链路，进程内调用，<b>必须在数据库事务之外</b>调用
 * （向量化为远程调用，事务内禁止；调用方取得字面量后再开启事务写入切片行与表示行）。</p>
 *
 * <p>异常口径：向量化上游异常原样上抛（可归因，由调用方使本次切片变更整体失败且零写入）；
 * 上游返回数量与请求不一致时抛 {@link IllegalStateException} 并携带数量对比。</p>
 *
 * @author DeepDataAgent
 */
@Component
public class DefaultChunkEmbeddingApi implements ChunkEmbeddingApi {

    /** pgvector 字面量起始符 */
    private static final String VECTOR_LITERAL_PREFIX = "[";

    /** pgvector 字面量结束符 */
    private static final String VECTOR_LITERAL_SUFFIX = "]";

    /** pgvector 字面量分量分隔符 */
    private static final String VECTOR_COMPONENT_SEPARATOR = ",";

    /** 本契约单次请求的正文条数（按单条切片正文取向量） */
    private static final int SINGLE_TEXT_COUNT = 1;

    /** 返回列表中首个向量的下标 */
    private static final int FIRST_VECTOR_INDEX = 0;

    /** 知识库服务契约（解析生效的嵌入模型 profileId，跨 BC 只读投影）。 */
    private final KnowledgeBaseApi knowledgeBaseApi;

    /** 向量计算端口（事务外调用，一次请求一条正文）。 */
    private final EmbeddingClient embeddingClient;

    /**
     * 构造切片向量化契约实现。
     *
     * @param knowledgeBaseApi 知识库服务契约（嵌入模型 profileId 的唯一真相源）
     * @param embeddingClient  向量计算端口
     */
    public DefaultChunkEmbeddingApi(KnowledgeBaseApi knowledgeBaseApi, EmbeddingClient embeddingClient) {
        this.knowledgeBaseApi = knowledgeBaseApi;
        this.embeddingClient = embeddingClient;
    }

    /**
     * 按切片正文同步取得该切片的向量表示字面量（pgvector 文本格式）。
     * <p>流程：解析生效嵌入模型 profileId → 空白即返回「无向量」且零远程调用 →
     * 单条正文调用 {@link EmbeddingClient#embedBatch(String, List)} → 校验返回数量为 1 →
     * 取首个向量编码为字面量。上游返回空向量（长度 0）时按「无向量」降级返回 {@code null}，
     * 与摄入侧 {@code ChunkPersistenceService} 的空向量口径一致。</p>
     *
     * @param kbId         目标知识库主键，必填
     * @param chunkContent 待向量化的切片正文，必填
     * @return pgvector 向量字面量；未配置嵌入模型或上游返回空向量时返回 {@code null}（「无向量」）
     * @throws IllegalArgumentException kbId 为空或切片正文空白（Fail-fast，避免静默丢失向量召回）
     * @throws IllegalStateException    上游返回结果为空或数量不等于 1（携带数量对比）
     */
    @Override
    public String embedLiteral(Long kbId, String chunkContent) {
        if (ObjectUtils.isEmpty(kbId)) {
            throw new IllegalArgumentException("知识库ID不能为空");
        }
        if (StringUtils.isBlank(chunkContent)) {
            throw new IllegalArgumentException("切片正文不能为空");
        }
        String embeddingModelProfileId = knowledgeBaseApi.findEmbeddingModelProfileIdByKbId(kbId);
        if (StringUtils.isBlank(embeddingModelProfileId)) {
            return null;
        }
        List<float[]> vectors = embeddingClient.embedBatch(embeddingModelProfileId, List.of(chunkContent));
        if (CollectionUtils.isEmpty(vectors) || vectors.size() != SINGLE_TEXT_COUNT) {
            throw new IllegalStateException("嵌入批量返回数量与请求不一致: "
                    + (CollectionUtils.isEmpty(vectors) ? 0 : vectors.size()) + "/" + SINGLE_TEXT_COUNT);
        }
        float[] vector = vectors.get(FIRST_VECTOR_INDEX);
        return ObjectUtils.isEmpty(vector) ? null : formatVectorLiteral(vector);
    }

    /**
     * 将浮点向量编码为 pgvector 字面量：分量经 BigDecimal 定点展开
     * （去尾零、禁用科学计数法），逗号拼接、方括号包裹，形如 {@code [0.5,0.25,0]}。
     * <p>与摄入侧 {@code ChunkPersistenceService#formatVectorLiteral} 逐字同口径，
     * 保证人工切片与摄入切片产出的字面量形态一致。</p>
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
}