package com.linkroa.deepdataagent.rag.domain.service;

import com.linkroa.deepdataagent.knowledgebase.api.KnowledgeBaseApi;
import com.linkroa.deepdataagent.knowledgebase.application.contract.ChunkScoreDTO;
import com.linkroa.deepdataagent.knowledgebase.domain.model.RetrievalStrategyConfig;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.RetrievalChannel;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.RetrievalStrategyType;
import com.linkroa.deepdataagent.rag.application.contract.RetrievalQuery;
import com.linkroa.deepdataagent.rag.domain.model.ChunkText;
import com.linkroa.deepdataagent.rag.domain.model.EntityHit;
import com.linkroa.deepdataagent.rag.domain.model.GraphEdgeHit;
import com.linkroa.deepdataagent.rag.domain.model.KgSearchResult;
import com.linkroa.deepdataagent.rag.domain.model.RankedChunk;
import com.linkroa.deepdataagent.rag.domain.model.RelationHit;
import com.linkroa.deepdataagent.rag.domain.model.RetrievalCandidate;
import com.linkroa.deepdataagent.rag.domain.port.EmbeddingClient;
import com.linkroa.deepdataagent.rag.domain.repository.RetrievalRepository;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.stream.Collectors;

/**
 * 三路召回默认实现（Stage 1）。
 *
 * <p>通道组合：{@code NAIVE} 走向量 + 全文双通道（GRAPH 不参与）；{@code MIX} 走
 * VECTOR / BM25 / GRAPH 三路。批量预计算embedding按固定文本顺序
 * {@code [query, ll连接文本, hl连接文本]} 一次 {@code embedBatch} 打包
 * （NAIVE 只打包 query；无关键词则不打包对应文本，避免空文本），按下标解包，
 * 避免逐条远程调用放大延迟。</p>
 *
 * <p>通道语义：</p>
 * <ul>
 *     <li>VECTOR：{@code KnowledgeBaseApi.searchByVector}，LIMIT = chunkTopK
 *     （{@link RetrievalQuery} 紧凑构造器已保证未指定时回退 topK），
 *     查询向量以 pgvector 字面量字符串跨边界传入；</li>
 *     <li>BM25：{@code KnowledgeBaseApi.searchByKeywords}，以改写后 query 检索，
 *     LIMIT = chunkTopK；</li>
 *     <li>GRAPH（仅 MIX）：实体路以 ll 向量命中 {@code searchEntities}、
 *     关系路以 hl 向量命中 {@code searchRelations}（各路 LIMIT = topK），命中记录携带图谱属性；
 *     ll/hl 单侧为空时仅退化对应子路（实体路/关系路），入口打一条可区分 WARN；
 *     <b>实体候选集</b>由「实体路命中序」与「关系路端点序（每命中贡献源、目标）」
 *     <b>逐位交错</b>合并（ll 先手、各源路内保相似度序、去重保序，RR#1）；
 *     对实体候选集批量取 1 跳关联边（LIMIT =
 *     {@link RetrievalQuery#graphEdgeTop()}，缺省回落
 *     {@link RetrievalConstants#GRAPH_EDGE_TOP}），边记录与关系路命中按归一端点对去重后
 *     <b>并入关系视图</b>（关系路命中优先保留其相似度分）；
 *     chunk 按「实体列表 → 关系视图列表」逐源抽取（每源 ≤
 *     {@link RetrievalQuery#graphEdgeChunkLimit()}，缺省回落
 *     {@link RetrievalConstants#GRAPH_EDGE_CHUNK_LIMIT}；边源直取边记录自带账本，
 *     <b>不再依赖本次关系路命中反查</b>），chunkId 全局去重先到先得保留来源分数，
 *     通道内按「被引用背书次数」降序稳定重排（E2，同次数保首现序），
 *     再按入场配额（E1 = {@link RetrievalQuery#chunkTopK()}）截断通道序前缀，
 *     经 {@code chunksOf} 回正文后组装 {@link RetrievalCandidate}（channel=GRAPH）与
 *     {@link KgSearchResult}。</li>
 * </ul>
 *
 * <p>降级口径：</p>
 * <ul>
 *     <li>embedding 批量预计算失败，或知识库未配置嵌入模型（profileId 空白）→
 *     整体短路，返回空候选与空图谱结果，不向调用方抛异常（批量调用失败短路）；</li>
 *     <li>单通道执行异常 → 记录告警后该通道按空结果继续，其余通道不受影响；</li>
 *     <li>实体路与关系路均无命中 → GRAPH 通道 MISSING（空候选 + 空集合
 *     {@link KgSearchResult}），VECTOR/BM25 不受影响。</li>
 * </ul>
 *
 * <p><b>关键假设（与仓储实现契约联动）</b>：</p>
 * <ol>
 *     <li>阈值口径：下游各端口 javadoc 均定义为余弦距离阈值（距离 &lt; 该值命中），
 *     本类统一以 {@code 1 - similarThreshold} 换算传入；similarThreshold 缺失按 0 处理
 *     （距离阈值 1.0，等效不过滤）。</li>
 *     <li>命中记录的图谱属性与 chunk 账本由仓储层一次取全（展示属性取图表、账本以向量表为
 *     权威并单列 COALESCE 兜底图行 {@code sourceIds}），图行缺失的命中在仓储层即已
 *     WARN 剔除，本层不再做任何兜底查询；关系命中端点为向量行原生双字段
 *     （字典序归一），本层不做名称拼接与拆分。</li>
 *     <li>实体候选集中「仅由关系路端点贡献、未被实体路命中」的名称，其图谱属性经
 *     {@link RetrievalRepository#findEntityAttributes} 一次批量点查图表补齐（类型/描述/来源文件/
 *     创建时间与节点账本 {@code properties.sourceIds}），分数取其来源关系命中相似度；
 *     回查不到的名称（端点可以不存在——悬空端点属正常态）记录 DEBUG 并保持
 *     「名称 + 来源关系相似度」裸名降级。
 *     同一实体既被实体路命中又作端点时，交错合并的先到先得已保留实体路富命中，不参与回查。</li>
 *     <li>embedding profileId：以知识库 {@code embedding_config} 为唯一真相源，经
 *     {@link KnowledgeBaseApi#findEmbeddingModelProfileIdByKbId(Long)} 按 kbId 现取，
 *     与摄入侧切片向量化同源（{@code IngestionWorker} 取同一配置的
 *     {@code modelProfileId}），不存在跨库或写入 / 检索两侧的模型错配；库未配置
 *     （投影返回空）时向量召回显式降级，不回退任何全局默认值。</li>
 * </ol>
 *
 * @author DeepDataAgent
 */
@Service
public class DefaultRecallService implements RecallService {

    private static final Logger log = LoggerFactory.getLogger(DefaultRecallService.class);

    /** embedding 批量预计算固定顺序下标：query 向量恒为下标 0 */
    private static final int VECTOR_INDEX_QUERY = 0;

    /** embedding 批量预计算固定顺序下标：ll（低层关键词连接文本）向量打包时恒为下标 1 */
    private static final int VECTOR_INDEX_LL = 1;

    /** pgvector 向量字面量分量分隔符（与摄入侧 ChunkPersistenceService 口径一致） */
    private static final String VECTOR_COMPONENT_SEPARATOR = ",";

    /** pgvector 向量字面量前缀 */
    private static final String VECTOR_LITERAL_PREFIX = "[";

    /** pgvector 向量字面量后缀 */
    private static final String VECTOR_LITERAL_SUFFIX = "]";

    /** 关键词连接分隔符：ll/hl 各以空格连接为单条文本向量化（对齐参考实现 " ".join 口径） */
    private static final String KEYWORD_JOIN_SEPARATOR = " ";

    /** 余弦相似度 → 余弦距离换算基准（距离阈值 = 1 - similarThreshold，全链路口径） */
    private static final double COSINE_DISTANCE_OFFSET = 1.0D;

    /** 相似度阈值缺失时的兜底取值：0，即距离阈值 1.0（等效不过滤，防御拆箱 NPE） */
    private static final float DEFAULT_SIMILAR_THRESHOLD = 0.0F;

    /** 候选列表初始容量：双通道各 topK 级别的保守估计 */
    private static final int CANDIDATES_INITIAL_CAPACITY = 64;

    /** 关系命中贡献的端点数（源、目标各一，用于端点序列预分配容量） */
    private static final int ENDPOINTS_PER_RELATION = 2;

    /**
     * 空集合图谱结果常量（NAIVE 与 MISSING 统一口径，RecallService javadoc 约定）。
     * <p>record 不可变、各字段均为不可变空列表，可安全共享（口径对齐
     * {@code RetrievalApplicationService} 既有 {@code EMPTY_KG_RESULT}）。</p>
     */
    private static final KgSearchResult EMPTY_KG_RESULT =
            new KgSearchResult(List.of(), List.of(), List.of());

    private final EmbeddingClient embeddingClient;

    private final RetrievalRepository retrievalRepository;

    private final KnowledgeBaseApi knowledgeBaseApi;

    /**
     * 构造器注入三路召回所需依赖。
     *
     * @param embeddingClient     embedding 端口（query/ll/hl 向量批量预计算）
     * @param retrievalRepository 检索侧只读仓储端口（实体路/关系路/关联边/chunk 回正文）
     * @param knowledgeBaseApi    知识库服务契约（VECTOR/BM25 通道只读检索 + 按 kbId
     *                            只读投影嵌入模型 profileId，与摄入侧切片向量化同源）
     */
    public DefaultRecallService(EmbeddingClient embeddingClient,
                                RetrievalRepository retrievalRepository,
                                KnowledgeBaseApi knowledgeBaseApi) {
        this.embeddingClient = embeddingClient;
        this.retrievalRepository = retrievalRepository;
        this.knowledgeBaseApi = knowledgeBaseApi;
    }

    /**
     * 执行三路召回（MIX）或向量 + 全文双通道召回（NAIVE）。
     *
     * @param q   检索内部执行请求（kbId 单库隔离，chunkTopK 已由紧凑构造器归一）
     * @param cfg 检索策略配置（strategyType 决定通道组合，similarThreshold 决定命中阈值）
     * @return 召回复合结果（候选按 VECTOR → BM25 → GRAPH 顺序合并；NAIVE 或图谱 MISSING 时
     *         图谱结果为空集合 {@link KgSearchResult}；embedding 批量失败时返回空结果）
     * @throws IllegalArgumentException 当 {@code q} 或 {@code cfg} 为 null 时
     */
    @Override
    public RecallOutcome recall(RetrievalQuery q, RetrievalStrategyConfig cfg) {
        if (ObjectUtils.isEmpty(q) || ObjectUtils.isEmpty(cfg)) {
            throw new IllegalArgumentException("召回请求与检索策略配置不能为空");
        }
        boolean mix = RetrievalStrategyType.MIX.equals(cfg.strategyType());
        List<String> texts = buildEmbeddingTexts(q, mix);
        List<float[]> vectors = embedBatchQuietly(q, texts);
        if (ObjectUtils.isEmpty(vectors) || vectors.size() < texts.size()) {
            // embedding 批量失败（含返回数量不足）：整体短路，空上下文不抛异常
            return new RecallOutcome(List.of(), EMPTY_KG_RESULT);
        }
        double distanceThreshold = resolveDistanceThreshold(cfg);
        List<RetrievalCandidate> candidates = new ArrayList<>(CANDIDATES_INITIAL_CAPACITY);
        candidates.addAll(recallVectorSafely(q, distanceThreshold, vectors.get(VECTOR_INDEX_QUERY)));
        candidates.addAll(recallBm25Safely(q));
        if (!mix) {
            return new RecallOutcome(candidates, EMPTY_KG_RESULT);
        }
        GraphOutcome graph = recallGraphSafely(q, distanceThreshold, vectors);
        candidates.addAll(graph.candidates());
        return new RecallOutcome(candidates, graph.kgResult());
    }

    /**
     * 组装批量预计算文本列表：固定顺序 {@code [query, ll连接文本, hl连接文本]}，
     * NAIVE 只打包 query，无关键词则不打包对应文本（避免空文本）。
     *
     * @param q   检索请求
     * @param mix 是否 MIX 策略
     * @return 待向量化文本列表（顺序即 embedBatch 返回顺序）
     */
    private List<String> buildEmbeddingTexts(RetrievalQuery q, boolean mix) {
        List<String> texts = new ArrayList<>(3);
        texts.add(q.query());
        if (!mix) {
            return texts;
        }
        if (CollectionUtils.isNotEmpty(q.llKeywords())) {
            texts.add(String.join(KEYWORD_JOIN_SEPARATOR, q.llKeywords()));
        }
        if (CollectionUtils.isNotEmpty(q.hlKeywords())) {
            texts.add(String.join(KEYWORD_JOIN_SEPARATOR, q.hlKeywords()));
        }
        return texts;
    }

    /**
     * 批量向量化（失败不外抛）：embedding profileId 按 kbId 从知识库
     * {@code embedding_config} 现取（与摄入侧切片向量化同源）；该库未配置嵌入模型时
     * 显式降级（不调用 embedding 端口），——两者均按
     * 无可用上下文处理。
     *
     * @param q     检索请求（kbId 为模型配置真相源定位键，亦用于告警定位）
     * @param texts 待向量化文本列表
     * @return 向量列表；知识库未配置嵌入模型或调用失败返回空列表
     */
    private List<float[]> embedBatchQuietly(RetrievalQuery q, List<String> texts) {
        String embeddingProfileId = knowledgeBaseApi.findEmbeddingModelProfileIdByKbId(q.kbId());
        if (StringUtils.isBlank(embeddingProfileId)) {
            log.warn("知识库未配置嵌入模型，向量召回降级返回空上下文: kbId={}", q.kbId());
            return List.of();
        }
        try {
            return embeddingClient.embedBatch(embeddingProfileId, texts);
        } catch (Exception e) {
            log.warn("embedding 批量预计算失败，召回短路返回空上下文: kbId={}", q.kbId(), e);
            return List.of();
        }
    }

    /**
     * 解析余弦距离阈值：{@code 1 - similarThreshold}（全链路统一口径，见类注释假设 1）；
     * 配置缺失按 0 处理（距离阈值 1.0，等效不过滤）。
     *
     * @param cfg 检索策略配置
     * @return 余弦距离阈值（命中条件为余弦距离小于该值）
     */
    private double resolveDistanceThreshold(RetrievalStrategyConfig cfg) {
        float similarThreshold = ObjectUtils.isEmpty(cfg.similarThreshold())
                ? DEFAULT_SIMILAR_THRESHOLD
                : cfg.similarThreshold();
        return COSINE_DISTANCE_OFFSET - similarThreshold;
    }

    /**
     * VECTOR 通道召回，通道级异常隔离：失败记录告警后按空结果降级。
     *
     * @param q                检索请求
     * @param distanceThreshold 余弦距离阈值
     * @param queryVector      query 向量（固定下标解包产物）
     * @return VECTOR 通道候选（可为空）
     */
    private List<RetrievalCandidate> recallVectorSafely(RetrievalQuery q, double distanceThreshold, float[] queryVector) {
        try {
            List<ChunkScoreDTO> hits = knowledgeBaseApi.searchByVector(q.kbId(), distanceThreshold,
                    toVectorLiteral(queryVector), q.chunkTopK());
            return toCandidates(hits, RetrievalChannel.VECTOR);
        } catch (Exception e) {
            log.warn("VECTOR 通道召回失败，按空结果降级: kbId={}", q.kbId(), e);
            return List.of();
        }
    }

    /**
     * BM25 通道召回，通道级异常隔离：失败记录告警后按空结果降级。
     *
     * @param q 检索请求（以改写后 query 做全文检索）
     * @return BM25 通道候选（可为空）
     */
    private List<RetrievalCandidate> recallBm25Safely(RetrievalQuery q) {
        try {
            List<ChunkScoreDTO> hits = knowledgeBaseApi.searchByKeywords(q.kbId(), q.query(), q.chunkTopK());
            return toCandidates(hits, RetrievalChannel.BM25);
        } catch (Exception e) {
            log.warn("BM25 通道召回失败，按空结果降级: kbId={}", q.kbId(), e);
            return List.of();
        }
    }

    /**
     * chunk 命中契约列表 → 带通道标记候选列表（跳过 chunkId 缺失的脏数据）。
     *
     * @param hits    命中列表（可为空）
     * @param channel 归属通道
     * @return 候选列表
     */
    private List<RetrievalCandidate> toCandidates(List<ChunkScoreDTO> hits, RetrievalChannel channel) {
        if (CollectionUtils.isEmpty(hits)) {
            return List.of();
        }
        List<RetrievalCandidate> candidates = new ArrayList<>(hits.size());
        for (ChunkScoreDTO hit : hits) {
            if (ObjectUtils.isEmpty(hit) || ObjectUtils.isEmpty(hit.chunkId())) {
                continue;
            }
            candidates.add(new RetrievalCandidate(hit.chunkId(), channel, hit.score(), null));
        }
        return candidates;
    }

    /**
     * GRAPH 通道召回（仅 MIX），通道级异常隔离：失败记录告警后按 MISSING 降级。
     *
     * @param q                 检索请求
     * @param distanceThreshold 余弦距离阈值
     * @param vectors           批量预计算向量（固定顺序 {@code [query, ll, hl]}，已保证数量充足）
     * @return 图谱通道产物（候选 + 结构化图谱结果）
     */
    private GraphOutcome recallGraphSafely(RetrievalQuery q, double distanceThreshold, List<float[]> vectors) {
        try {
            boolean hasLl = CollectionUtils.isNotEmpty(q.llKeywords());
            boolean hasHl = CollectionUtils.isNotEmpty(q.hlKeywords());
            // MIX 下 ll/hl 单侧为空（异或）只退化一条图谱子路：剩单路照常执行，此处仅留可区分的告警
            // 用于定位「实体路/关系路为何空」，不改变任何行为。双侧皆空属既有 keywordFailed 短路口径
            // （由应用层决定是否跳过 Stage 1~4），不落入单侧告警分支
            if (!hasLl && hasHl) {
                log.warn("MIX 图谱实体路退化（ll 关键词为空，跳过实体向量命中）: kbId={}", q.kbId());
            }
            if (!hasHl && hasLl) {
                log.warn("MIX 图谱关系路退化（hl 关键词为空，跳过关系向量命中）: kbId={}", q.kbId());
            }
            // 固定顺序解包：ll 打包时恒为下标 1；hl 紧随 ll（ll 未打包则前移一位）
            int hlIndex = VECTOR_INDEX_LL + (hasLl ? 1 : 0);
            List<EntityHit> entityHits = hasLl
                    ? nullSafeList(retrievalRepository.searchEntities(q.kbId(), vectors.get(VECTOR_INDEX_LL),
                            distanceThreshold, q.topK()))
                    : List.of();
            List<RelationHit> relationHits = hasHl
                    ? nullSafeList(retrievalRepository.searchRelations(q.kbId(), vectors.get(hlIndex),
                            distanceThreshold, q.topK()))
                    : List.of();
            return recallGraph(q, entityHits, relationHits);
        } catch (Exception e) {
            log.warn("GRAPH 通道召回失败，按 MISSING 降级: kbId={}", q.kbId(), e);
            return new GraphOutcome(List.of(), EMPTY_KG_RESULT);
        }
    }

    /**
     * GRAPH 通道核心组装（对齐《realign-rag-retrieval-with-lightrag》D-3/D-4/E1/E2）：
     * 实体候选集逐位交错合并 → 1 跳关联边 → 边并入关系视图 → 三源 chunk 抽取
     * （背书频次重排 + 入场配额截断）→ 回正文。
     *
     * @param q            检索请求
     * @param entityHits   实体路命中（可为空）
     * @param relationHits 关系路命中（可为空）
     * @return 图谱通道产物；两路均无命中时返回 MISSING（空候选 + 空集合图谱结果）
     */
    private GraphOutcome recallGraph(RetrievalQuery q, List<EntityHit> entityHits,
                                     List<RelationHit> relationHits) {
        List<EntityHit> validEntityHits = entityHits.stream().filter(this::isValidEntityHit).toList();
        List<RelationHit> validRelationHits = relationHits.stream().filter(this::isValidRelationHit).toList();
        if (validEntityHits.isEmpty() && validRelationHits.isEmpty()) {
            // 图谱空 → GRAPH 通道 MISSING（降级空上下文），不再发起关联边/chunk 查询
            return new GraphOutcome(List.of(), EMPTY_KG_RESULT);
        }
        // 实体候选集：ll 命中序与关系路端点序逐位交错（RR#1，去重保序）
        Map<String, EntityHit> entityCandidates = interleaveEntityCandidates(validEntityHits, validRelationHits);
        // 端点属性回查（2.6b）：仅对「未被实体路命中」的裸名端点批量点查图表补齐，零缺口零查询
        enrichEndpointEntities(q.kbId(), entityCandidates, validEntityHits);
        // 1 跳关联边（③）：实体候选集为空时无边可取，跳过查询
        List<GraphEdgeHit> edges = entityCandidates.isEmpty()
                ? List.of()
                : nullSafeList(retrievalRepository.relatedEdges(q.kbId(),
                        new ArrayList<>(entityCandidates.keySet()), q.graphEdgeTop()));
        // 关系视图 = 关系路命中 ∪ 关联边转换记录（按归一对去重，关系路命中优先）
        List<RelationHit> relationView = toRelationView(validRelationHits, edges);
        KgSearchResult kgResult = new KgSearchResult(new ArrayList<>(entityCandidates.values()),
                relationView, List.of());
        Map<Long, Double> chunkScores = collectGraphChunks(q, entityCandidates.values(), relationView);
        if (chunkScores.isEmpty()) {
            // 有图谱命中但无任何关联 chunk：GRAPH 候选为空，结构化实体/关系结果仍供上下文模板使用
            return new GraphOutcome(List.of(), kgResult);
        }
        return assembleGraphCandidates(q, chunkScores, kgResult);
    }

    /**
     * 实体候选集逐位交错合并（RR#1）：{@code ll₁, hl₁, ll₂, hl₂…}——ll 先手、
     * 关系路端点（每命中按源、目标顺序贡献两个名称）随后，各源路内保持相似度序，
     * 去重保留首次出现序。
     * <p>动机：上下文按列表序截断（P3）时，「ll 全体 → hl 端点追加」的两段式排队会使
     * 端点实体系统性最先被砍，逐位交错使两路损失均衡。</p>
     *
     * @param entityHits   实体路命中（已过滤无效）
     * @param relationHits 关系路命中（已过滤无效）
     * @return 名称 → 实体命中记录（有序）
     */
    private Map<String, EntityHit> interleaveEntityCandidates(List<EntityHit> entityHits,
                                                              List<RelationHit> relationHits) {
        List<EndpointRef> endpoints = flattenEndpoints(relationHits);
        Map<String, EntityHit> candidates = new LinkedHashMap<>();
        int llCursor = 0;
        int hlCursor = 0;
        while (llCursor < entityHits.size() || hlCursor < endpoints.size()) {
            if (llCursor < entityHits.size()) {
                EntityHit hit = entityHits.get(llCursor++);
                candidates.putIfAbsent(hit.name(), hit);
            }
            if (hlCursor < endpoints.size()) {
                EndpointRef endpoint = endpoints.get(hlCursor++);
                candidates.putIfAbsent(endpoint.name(), endpoint.toEntityHit());
            }
        }
        return candidates;
    }

    /**
     * 关系路命中展平为端点序列（每命中按「源、目标」顺序贡献两条，保持关系相似度序）。
     *
     * @param relationHits 关系路命中（已过滤无效）
     * @return 端点引用序列
     */
    private List<EndpointRef> flattenEndpoints(List<RelationHit> relationHits) {
        List<EndpointRef> endpoints = new ArrayList<>(relationHits.size() * ENDPOINTS_PER_RELATION);
        for (RelationHit hit : relationHits) {
            endpoints.add(new EndpointRef(hit.sourceName(), hit.score()));
            endpoints.add(new EndpointRef(hit.targetName(), hit.score()));
        }
        return endpoints;
    }

    /**
     * 端点实体属性批量回查（2.6b）：为实体候选集中「仅由关系路端点贡献、未被实体路命中」的裸名实体
     * 补齐图表展示属性与节点账本。缺口 = 候选集键集 ∖ 实体路命中名集；无缺口直接返回（零命中零查询）。
     * <p>命中回查的记录以「其来源关系相似度 + 图表属性 + 节点账本（{@code properties.sourceIds}）」
     * 就地替换裸名降级记录（{@link LinkedHashMap#put} 覆盖既有键不改变候选集插入序）；回查不到的名称
     * 记录 DEBUG 并保持裸名（悬空端点属正常态——图合并不补建占位节点）。同一实体既被实体路命中又作端点时，交错合并的
     * 先到先得已保留实体路富命中，其名称落在 {@code entityPathHits} 名集内，不参与本轮回查。</p>
     * <p>异常不外抛：仓储调用失败由 {@code recallGraphSafely} 统一按 GRAPH 通道降级。</p>
     *
     * @param kbId           知识库主键（单库隔离）
     * @param candidates     实体候选集（就地更新，保持有序）
     * @param entityPathHits 实体路命中（已过滤无效，其名称视为已被富命中覆盖，不回查）
     */
    private void enrichEndpointEntities(Long kbId, Map<String, EntityHit> candidates,
                                        List<EntityHit> entityPathHits) {
        Set<String> entityPathNames = entityPathHits.stream()
                .map(EntityHit::name).collect(Collectors.toCollection(HashSet::new));
        List<String> gapNames = candidates.keySet().stream()
                .filter(name -> !entityPathNames.contains(name)).toList();
        if (gapNames.isEmpty()) {
            return;
        }
        Map<String, EntityHit> attributes = nullSafeMap(
                retrievalRepository.findEntityAttributes(kbId, gapNames));
        for (String name : gapNames) {
            EntityHit attribute = attributes.get(name);
            if (ObjectUtils.isEmpty(attribute)) {
                // 悬空端点自图合并「不补建占位节点」起是正常态，DEBUG 留痕、不按异常上报
                log.debug("端点实体图行属性回查缺失，保持裸名降级: kbId={} entityName={}", kbId, name);
                continue;
            }
            EntityHit bare = candidates.get(name);
            // 保留裸名记录的来源关系相似度，仅补齐图表属性与节点账本（覆盖不改变候选集顺序）
            candidates.put(name, new EntityHit(name, bare.score(), attribute.chunkIds(),
                    attribute.entityType(), attribute.description(), attribute.filePaths(),
                    attribute.createdAt()));
        }
    }

    /**
     * 关系视图合并：关系路命中按自身顺序入列，关联边逐条转换为 {@link RelationHit}
     * 并按归一端点对去重并入（同一关系在视图中至多出现一次，关系路命中优先保留其相似度分）。
     * <p><b>边转关系的分数口径</b>：取边 {@code weight}（图谱结构权重，非相似度）。
     * 影响面：该分数只经「边 → 其账本 chunk」这条链落到 GRAPH 通道候选分数上——
     * RRF 融合只消费通道内排名、不消费 score，故无影响；WEIGHTED_SUM 融合在 GRAPH 通道内
     * 做 min-max 归一，边权重量纲（常为 1 / belongs_to 的 10）与余弦相似度 [0,1] 混合，
     * 会使仅由边账本进入的 chunk 归一后靠近上界。此取舍见 design D-3 与
     * {@link RelationHit#score()} 说明。</p>
     *
     * @param relationHits 关系路命中（已过滤无效）
     * @param edges        1 跳关联边记录（可为空）
     * @return 关系视图列表（有序）
     */
    private List<RelationHit> toRelationView(List<RelationHit> relationHits, List<GraphEdgeHit> edges) {
        Map<List<String>, RelationHit> viewByPair = new LinkedHashMap<>();
        for (RelationHit hit : relationHits) {
            viewByPair.putIfAbsent(hit.normalizedPair(), hit);
        }
        for (GraphEdgeHit edge : edges) {
            if (ObjectUtils.isEmpty(edge)) {
                continue;
            }
            List<String> pair = edge.normalizedPair();
            if (pair.isEmpty()) {
                continue;
            }
            viewByPair.putIfAbsent(pair, toRelationHit(edge));
        }
        return new ArrayList<>(viewByPair.values());
    }

    /**
     * 关联边记录 → 关系视图记录（端点/描述/关键词/权重/来源文件/创建时间原样携带，
     * 账本即边的账本；分数口径见 {@link #toRelationView}）。
     *
     * @param edge 关联边记录
     * @return 关系视图记录
     */
    private RelationHit toRelationHit(GraphEdgeHit edge) {
        return new RelationHit(edge.sourceName(), edge.targetName(), edge.weight(), edge.chunkIds(),
                edge.description(), edge.keywords(), edge.weight(), edge.filePaths(), edge.createdAt());
    }

    /**
     * 三源 chunk 抽取（④）：按「实体列表 → 关系视图列表」逐源抽取（每源上限取请求侧
     * {@link RetrievalQuery#graphEdgeChunkLimit()}，缺省回落
     * {@link RetrievalConstants#GRAPH_EDGE_CHUNK_LIMIT}），chunkId 全局去重（先到先得保留
     * 来源分数），并累计「被引用背书次数」。
     * <p>随后施加两道结构性修正：<b>E2</b> 通道内按背书次数降序<b>稳定</b>重排
     * （同次数保持首现序，仅影响通道内次序、一进融合仍是单条，不做分数叠加）；
     * <b>E1</b> 按入场配额（{@link RetrievalQuery#chunkTopK()}）截断通道序前缀，
     * 限制 GRAPH 通道进入融合的候选总数。</p>
     *
     * @param q            检索请求（每源 chunk 上限与入场配额来源）
     * @param entityView   实体候选集（实体路命中 + 端点回查/降级记录，视图内全部实体）
     * @param relationView 关系视图（关系路命中 ∪ 关联边转换记录）
     * @return chunkId → 来源相似度分（已按 E2 重排、E1 截断的通道序）
     */
    private Map<Long, Double> collectGraphChunks(RetrievalQuery q, Collection<EntityHit> entityView,
                                                 List<RelationHit> relationView) {
        int perSourceLimit = q.graphEdgeChunkLimit();
        Map<Long, Double> scoreByChunk = new LinkedHashMap<>();
        Map<Long, Integer> endorsementsByChunk = new LinkedHashMap<>();
        for (EntityHit hit : entityView) {
            endorse(hit.chunkIds(), hit.score(), perSourceLimit, scoreByChunk, endorsementsByChunk);
        }
        for (RelationHit hit : relationView) {
            endorse(hit.chunkIds(), hit.score(), perSourceLimit, scoreByChunk, endorsementsByChunk);
        }
        return reorderAndQuota(scoreByChunk, endorsementsByChunk, q.chunkTopK());
    }

    /**
     * 单源 chunk 登记：最多取该源账本的前 {@code perSourceLimit} 个<b>去重后</b> chunk
     * （同一来源自身列表内重复不重复计背书），分数先到先得。
     *
     * @param chunkIds             该源的 chunk 账本（可为空）
     * @param score                该源的通道内分数
     * @param perSourceLimit       单源 chunk 抽取上限（由请求侧归一，恒为正整数）
     * @param scoreByChunk         chunkId → 来源分数累积器（就地写入）
     * @param endorsementsByChunk  chunkId → 背书次数累积器（就地写入）
     */
    private void endorse(List<Long> chunkIds, double score, int perSourceLimit,
                         Map<Long, Double> scoreByChunk, Map<Long, Integer> endorsementsByChunk) {
        if (CollectionUtils.isEmpty(chunkIds)) {
            return;
        }
        Set<Long> takenByThisSource = new LinkedHashSet<>();
        for (Long chunkId : chunkIds) {
            if (takenByThisSource.size() >= perSourceLimit) {
                break;
            }
            if (ObjectUtils.isEmpty(chunkId) || !takenByThisSource.add(chunkId)) {
                continue;
            }
            scoreByChunk.putIfAbsent(chunkId, score);
            endorsementsByChunk.merge(chunkId, 1, Integer::sum);
        }
    }

    /**
     * E2 背书频次重排 + E1 入场配额截断：按背书次数降序稳定排序（次数相同保持首现序），
     * 再取配额内前缀。
     *
     * @param scoreByChunk        chunkId → 来源分数（首现序）
     * @param endorsementsByChunk chunkId → 背书次数
     * @param entryQuota          GRAPH 通道入场配额（{@code chunkTopK}，恒为正整数）
     * @return 重排并截断后的有序 chunkId → 分数映射
     */
    private Map<Long, Double> reorderAndQuota(Map<Long, Double> scoreByChunk,
                                              Map<Long, Integer> endorsementsByChunk, int entryQuota) {
        List<Long> orderedChunkIds = new ArrayList<>(scoreByChunk.keySet());
        // List.sort 为稳定排序：仅按背书次数降序比较，同次数天然保持首现序
        orderedChunkIds.sort(Comparator.comparingInt(
                (Long chunkId) -> endorsementsByChunk.getOrDefault(chunkId, 0)).reversed());
        List<Long> quotaChunkIds = orderedChunkIds.size() <= entryQuota
                ? orderedChunkIds : new ArrayList<>(orderedChunkIds.subList(0, entryQuota));
        Map<Long, Double> ordered = new LinkedHashMap<>(quotaChunkIds.size());
        for (Long chunkId : quotaChunkIds) {
            ordered.put(chunkId, scoreByChunk.get(chunkId));
        }
        return ordered;
    }

    /**
     * 组装 GRAPH 候选与结构化结果：{@code chunksOf} 批量回正文（经 knowledgebase 只读通道），
     * 正文缺失（chunk 已被删除）的候选丢弃。
     *
     * @param q           检索请求
     * @param chunkScores chunkId → 来源相似度分（有序，已按 E1/E2 处理）
     * @param kgResult    已组装的实体/关系结果（chunks 字段在此填充）
     * @return 图谱通道产物
     */
    private GraphOutcome assembleGraphCandidates(RetrievalQuery q, Map<Long, Double> chunkScores,
                                                 KgSearchResult kgResult) {
        Map<Long, ChunkText> chunkTexts = nullSafeMap(
                retrievalRepository.chunksOf(q.kbId(), new ArrayList<>(chunkScores.keySet())));
        List<RetrievalCandidate> candidates = new ArrayList<>(chunkScores.size());
        List<RankedChunk> rankedChunks = new ArrayList<>(chunkScores.size());
        for (Map.Entry<Long, Double> entry : chunkScores.entrySet()) {
            ChunkText text = chunkTexts.get(entry.getKey());
            if (ObjectUtils.isEmpty(text)) {
                continue;
            }
            candidates.add(new RetrievalCandidate(entry.getKey(), RetrievalChannel.GRAPH,
                    entry.getValue(), text.sourceFileName()));
            rankedChunks.add(new RankedChunk(entry.getKey(), entry.getValue(), text.sourceFileName()));
        }
        KgSearchResult enriched = new KgSearchResult(kgResult.entities(), kgResult.relations(), rankedChunks);
        return new GraphOutcome(candidates, enriched);
    }

    /**
     * 实体命中合法性防御：命中对象为 null 或名称空白时视为无效。
     *
     * @param hit 实体命中
     * @return true 表示有效命中
     */
    private boolean isValidEntityHit(EntityHit hit) {
        return ObjectUtils.isNotEmpty(hit) && StringUtils.isNotBlank(hit.name());
    }

    /**
     * 关系命中合法性防御：命中对象为 null 或任一端点空白时视为无效
     * （无效端点对无法参与归一对去重，直接不消费）。
     *
     * @param hit 关系命中
     * @return true 表示有效命中
     */
    private boolean isValidRelationHit(RelationHit hit) {
        return ObjectUtils.isNotEmpty(hit) && !hit.normalizedPair().isEmpty();
    }

    /**
     * 列表 null 防御（仓储实现异常返回 null 时按空列表处理）。
     *
     * @param list 原列表
     * @return 非 null 列表
     */
    private <T> List<T> nullSafeList(List<T> list) {
        return ObjectUtils.isEmpty(list) ? Collections.emptyList() : list;
    }

    /**
     * 映射 null 防御（仓储实现异常返回 null 时按空映射处理）。
     *
     * @param map 原映射
     * @return 非 null 映射
     */
    private <K, V> Map<K, V> nullSafeMap(Map<K, V> map) {
        return ObjectUtils.isEmpty(map) ? Collections.emptyMap() : map;
    }

    /**
     * float[] → pgvector 向量字面量（形如 {@code "[0.1,0.2]"}，本 BC 跨 knowledgebase 边界契约）。
     *
     * @param vector 向量分量数组
     * @return 向量字面量字符串
     */
    private String toVectorLiteral(float[] vector) {
        StringJoiner joiner = new StringJoiner(VECTOR_COMPONENT_SEPARATOR,
                VECTOR_LITERAL_PREFIX, VECTOR_LITERAL_SUFFIX);
        for (float component : vector) {
            joiner.add(String.valueOf(component));
        }
        return joiner.toString();
    }

    /**
     * GRAPH 通道内部产物：候选列表 + 结构化图谱结果。
     *
     * @param candidates GRAPH 通道候选（MISSING 时为空）
     * @param kgResult   结构化图谱结果（MISSING 时各字段为空列表）
     */
    private record GraphOutcome(List<RetrievalCandidate> candidates, KgSearchResult kgResult) {
    }

    /**
     * 关系路命中贡献的端点引用（名称 + 所属关系相似度），用于实体候选集交错合并。
     * <p>交错合并时先以 {@link #toEntityHit()} 的裸名降级记录入候选集；未被实体路命中的端点随后
     * 由 {@link #enrichEndpointEntities} 批量回查图表补齐属性（回查缺失者保持本裸名形态）。</p>
     *
     * @param name  端点实体名
     * @param score 所属关系命中的相似度分
     */
    private record EndpointRef(String name, double score) {

        /**
         * 转为实体候选集的裸名降级记录（仅携带名称与来源关系相似度，属性与账本待 {@code 2.6b} 回查补齐）。
         *
         * @return 实体候选集记录
         */
        EntityHit toEntityHit() {
            return new EntityHit(name, score, List.of(), null, null, null, null);
        }
    }
}
