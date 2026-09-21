package com.linkroa.deepdataagent.rag.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkroa.deepdataagent.knowledgebase.api.KnowledgeBaseApi;
import com.linkroa.deepdataagent.knowledgebase.domain.model.Chunk;
import com.linkroa.deepdataagent.knowledgebase.domain.model.FusionStrategyConfig;
import com.linkroa.deepdataagent.knowledgebase.domain.model.RetrievalStrategyConfig;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.FusionStrategyType;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.RetrievalChannel;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.RetrievalStrategyType;
import com.linkroa.deepdataagent.rag.application.contract.RetrievalChunkView;
import com.linkroa.deepdataagent.rag.application.contract.RetrievalQuery;
import com.linkroa.deepdataagent.rag.application.contract.RetrievalResult;
import com.linkroa.deepdataagent.rag.domain.model.ContextBudget;
import com.linkroa.deepdataagent.rag.domain.model.KgContext;
import com.linkroa.deepdataagent.rag.domain.model.KgSearchResult;
import com.linkroa.deepdataagent.rag.domain.model.KeywordPair;
import com.linkroa.deepdataagent.rag.domain.model.RankedChunk;
import com.linkroa.deepdataagent.rag.domain.model.RetrievalCandidate;
import com.linkroa.deepdataagent.rag.domain.service.AnswerGenerator;
import com.linkroa.deepdataagent.rag.domain.service.ContextBuilder;
import com.linkroa.deepdataagent.rag.domain.service.FusionService;
import com.linkroa.deepdataagent.rag.domain.service.MultimodalMetaKeys;
import com.linkroa.deepdataagent.rag.domain.service.QueryUnderstandingService;
import com.linkroa.deepdataagent.rag.domain.service.RecallService;
import com.linkroa.deepdataagent.rag.domain.service.Reranker;
import com.linkroa.deepdataagent.rag.domain.service.RetrievalConstants;
import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 检索编排应用服务（Stage 0~5 全链路入口）。
 *
 * <p>三个编排入口按固定顺序编排同一组六个领域服务：
 * 查询理解（Stage 0）→ 三路召回（Stage 1）→ 粗排融合（Stage 2）→ 精排（Stage 3）
 * → 上下文构建（Stage 4）→ 答案生成（Stage 5），差异只在尾部阶段裁剪：
 * {@link #search(RetrievalQuery)} 跑完 Stage 0~5 并回取明细（完整结果，供进程内契约消费）；
 * {@link #generateAnswer(RetrievalQuery)} 同样跑完 Stage 0~5 但不回取明细（仅答案文本）；
 * {@link #retrieveChunks(RetrievalQuery)} 跑至 Stage 3 即止（仅精排全量切片，零作答开销）。
 * 前置校验经 {@link KnowledgeBaseApi} 完成库可用性判定与库级策略快照读取（只读、无事务）。</p>
 *
 * <p><b>Stage 0 产物贯穿</b>：改写后 query 与提取的双层关键词重建不可变的
 * {@link RetrievalQuery}（effective），Stage 1 召回、Stage 3~5 的查询文本与
 * 预算均消费 effective，不再回读原始请求。</p>
 *
 * <p><b>通路 A（检索附图）</b>：请求携带 {@code images} 且多模态开关
 * {@code queryImageTranscribe} 为 true（缺省）时，在 Stage 0 之前经 {@link QueryImageTranscriber}
 * 把附图转写为描述文本、拼成增强查询，增强查询作为 Stage 0 改写与关键词提取的输入贯穿检索链路
 * （Stage 1~4）；<b>原始 query 保留</b>并送入 Stage 5 作答（引用与语言规则锚定用户原话）。
 * 未携带附图的请求不触发转译调用，行为与基线逐字段一致；附件零持久化。
 * 开关显式置 false时忽略全部附图并输出 WARN，
 * 按纯文本口径继续（宽容式，不拒绝请求）；开关经 effective 重建随契约下传至作答层供通路 B 消费。</p>
 *
 * <p><b>关键词失败终止</b>：MIX 模式下双层关键词皆空
 * 且改写后 query 长度 ≥ {@value #KEYWORD_FAIL_QUERY_MIN_LENGTH} 时跳过 Stage 1~4，
 * 直接以空 {@link KgContext} 进入 Stage 5（AnswerGenerator 内部按
 * "无可利用上下文"直出 fail_response），该路径属既定业务分支、不标记 degraded。</p>
 *
 * <p><b>降级矩阵</b>（Stage 1~5 各自捕获异常，置 {@code degraded=true} 后
 * 取该 Stage 入参兜底继续，主返回不中断）：</p>
 * <ul>
 *   <li>Stage 0 改写异常 → 回退原始 query 继续；提取异常 → 空关键词继续；</li>
 *   <li>Stage 1 召回异常 → 空候选 + 空图谱结果继续；</li>
 *   <li>Stage 2 融合异常 → 候选按原始顺序直接映射 RankedChunk
 *       （取前 {@link RetrievalConstants#RERANK_TOP_K_DEFAULT} 条）继续；</li>
 *   <li>Stage 3 精排异常 → 直出粗排结果继续；</li>
 *   <li>Stage 4 上下文异常 → 空 KgContext（承载图谱结果）继续答案生成；</li>
 *   <li>Stage 5 答案异常 → answer 为 null，仍返回检索产物。</li>
 * </ul>
 *
 * @author DeepDataAgent
 */
@Service
public class RetrievalApplicationService {

    /** 日志器 */
    private static final Logger log = LoggerFactory.getLogger(RetrievalApplicationService.class);

    /**
     * 关键词兜底失败判定的 query 长度阈值：与 QueryUnderstandingService 落地规则 4
     * （「hl、ll 皆空且查询长度 ≥ 50 → 终止流程」）对齐，编排层据此跳过 Stage 1~4。
     */
    private static final int KEYWORD_FAIL_QUERY_MIN_LENGTH = 50;

    /** raw_data 键：命中实体结构化列表（携带图谱属性，仅内部契约，不外露 REST） */
    private static final String RAW_KEY_ENTITIES = "entities";

    /** raw_data 键：命中关系结构化列表（关系视图：关系路命中 ∪ 关联边，仅内部契约，不外露 REST） */
    private static final String RAW_KEY_RELATIONS = "relations";

    /** raw_data 键：全局有序 chunk 列表 */
    private static final String RAW_KEY_CHUNKS = "chunks";

    /** 切片媒体引用解析器（仅用于读取 {@code s3_file} / {@code original_item} 两列 JSON，无全局配置） */
    private static final ObjectMapper MEDIA_REF_MAPPER = new ObjectMapper();

    /** {@code chunk.s3_file} JSON 字段名：对象键（对齐 {@code document.s3_file} 形态；桶概念已退役） */
    private static final String S3_FIELD_OBJECT_KEY = "objectKey";

    /** 空图谱结果常量（召回降级与关键词失败终止路径复用，record 不可变可安全共享） */
    private static final KgSearchResult EMPTY_KG_RESULT =
            new KgSearchResult(List.of(), List.of(), List.of());

    /** 空上下文产物常量（关键词失败终止路径承载，Stage 5 据此走 fail_response 兜底） */
    private static final KgContext EMPTY_KG_CONTEXT =
            new KgContext(StringUtils.EMPTY, List.of(), EMPTY_KG_RESULT);

    /**
     * 库未配置检索策略时的 NAIVE 兜底配置常量（不改写问题、其余取记录默认）。
     * <p>record 各分量为不可变/空值形态，可安全共享（口径同 {@link #EMPTY_KG_RESULT}）。</p>
     */
    private static final RetrievalStrategyConfig FALLBACK_NAIVE_STRATEGY_CONFIG =
            new RetrievalStrategyConfig(RetrievalStrategyType.NAIVE, false, null, null, null, null);

    /**
     * 库级配置未携带 fusionConfig 时的 RRF 兜底融合配置常量（k 取
     * {@link FusionStrategyConfig#DEFAULT_RRF_K}）。record 不可变，可安全共享。
     */
    private static final FusionStrategyConfig FALLBACK_RRF_FUSION_CONFIG =
            new FusionStrategyConfig(FusionStrategyType.RRF, FusionStrategyConfig.DEFAULT_RRF_K, null);

    /** 知识库跨 BC 只读契约（可用性校验与库级检索策略快照读取） */
    private final KnowledgeBaseApi knowledgeBaseApi;

    /** 查询理解服务（Stage 0：问题改写 + 双层关键词提取） */
    private final QueryUnderstandingService queryUnderstandingService;

    /** 召回服务（Stage 1：VECTOR / BM25 / GRAPH 三路召回） */
    private final RecallService recallService;

    /** 粗排融合服务（Stage 2：RRF / 加权求和） */
    private final FusionService fusionService;

    /** 精排服务（Stage 3：库级 reranker 重排） */
    private final Reranker reranker;

    /** 上下文构建服务（Stage 4：动态 token 预算渲染） */
    private final ContextBuilder contextBuilder;

    /** 答案生成服务（Stage 5：模板选择 + 答案缓存 + 兜底） */
    private final AnswerGenerator answerGenerator;

    /** 检索附图转译器（通路 A：Stage 0 前置，把提问附图转写为描述文本并入查询） */
    private final QueryImageTranscriber queryImageTranscriber;

    /**
     * 构造检索编排服务。
     *
     * @param knowledgeBaseApi        知识库跨 BC 只读契约
     * @param queryUnderstandingService 查询理解服务（Stage 0）
     * @param recallService           召回服务（Stage 1）
     * @param fusionService           粗排融合服务（Stage 2）
     * @param reranker                精排服务（Stage 3）
     * @param contextBuilder          上下文构建服务（Stage 4）
     * @param answerGenerator         答案生成服务（Stage 5）
     * @param queryImageTranscriber   检索附图转译器（通路 A，Stage 0 前置；无附图请求不触发调用）
     */
    public RetrievalApplicationService(KnowledgeBaseApi knowledgeBaseApi,
                                       QueryUnderstandingService queryUnderstandingService,
                                       RecallService recallService,
                                       FusionService fusionService,
                                       Reranker reranker,
                                       ContextBuilder contextBuilder,
                                       AnswerGenerator answerGenerator,
                                       QueryImageTranscriber queryImageTranscriber) {
        this.knowledgeBaseApi = knowledgeBaseApi;
        this.queryUnderstandingService = queryUnderstandingService;
        this.recallService = recallService;
        this.fusionService = fusionService;
        this.reranker = reranker;
        this.contextBuilder = contextBuilder;
        this.answerGenerator = answerGenerator;
        this.queryImageTranscriber = queryImageTranscriber;
    }

    /**
     * 执行一次完整检索（Stage 0~5）。
     * <p>流程：① kbId/query 非空校验 → ② 知识库 ACTIVE 校验（不通过直接拒绝）
     * → ③ 读取库级策略快照（null 按 NAIVE 默认策略兜底）→ ④ 附图转译（仅请求携带
     * {@code images} 且 {@code multimodal.queryImageTranscribe()} 为 true 时执行，产出增强查询喂
     * Stage 0；无附图零调用；开关为 false 且带附图时忽略附图并 WARN，按纯文本继续）
     * → ⑤ Stage 0 查询理解并重建 effective 请求（附图不下传、多模态开关分量原样携带）
     * → ⑥ 依序执行 Stage 1~5（关键词失败终止时跳过 Stage 1~4；附图请求的 Stage 5 送入原始 query；
     * 通路 B 的 {@code answerImageDirectRead} 开关由 Stage 5 作答层自行消费）
     * → ⑦ 组装 {@link RetrievalResult}（references 与上下文引用列表一致，
     * rawData 由图谱结果展开 entities/relations/chunks，chunkViews 按上下文序回取
     * 切片正文与多模态图片引用；回取失败仅置空明细，不影响主返回与 {@code degraded}）。</p>
     * <p>各 Stage 异常按类注释降级矩阵兜底，仅以 {@code degraded} 标记，不向调用方抛出；
     * 唯一抛出的业务异常为知识库不可用（前置校验）。</p>
     *
     * @param q 检索内部执行请求（kbId 与 query 必填，预算字段由紧凑构造器归一，
     *          {@code images} 为已校验解码的附图、可空；{@code multimodal} 为多模态通路开关，
     *          缺省双 true，null 由紧凑构造器归一为全开默认）
     * @return 检索结果（answer 可为 null——Stage 5 异常时；context 永不为 null）
     * @throws IllegalArgumentException   请求对象为空
     * @throws DeepDataAgentException 知识库不存在或不处于 ACTIVE 状态，拒绝检索
     */
    public RetrievalResult search(RetrievalQuery q) {
        return doSearch(q, RetrievalOutputMode.FULL);
    }

    /**
     * 执行一次检索并只产出 LLM 最终答案（REST 答案形态入口，Stage 0~5 全跑）。
     * <p>与 {@link #search(RetrievalQuery)} 的链路行为逐字段一致，仅跳过响应外的旁路开销：
     * 不回取切片明细（省下一次批量切片表查询）。各阶段降级仍按降级矩阵兜底，
     * 答案生成异常时本方法返回 {@code null}，不向调用方抛出。</p>
     *
     * @param q 检索内部执行请求（kbId 与 query 必填，其余字段由紧凑构造器归一）
     * @return 最终答案文本；答案生成降级时为 {@code null}
     * @throws IllegalArgumentException 请求对象为空
     * @throws DeepDataAgentException   知识库不存在或不处于 ACTIVE 状态，拒绝检索
     */
    public String generateAnswer(RetrievalQuery q) {
        return doSearch(q, RetrievalOutputMode.ANSWER_ONLY).answer();
    }

    /**
     * 执行一次检索并只产出命中切片（REST 切片形态入口，仅跑至 Stage 3）。
     * <p>Stage 0~3（查询理解 → 三路召回 → 融合 → 精排）与完整形态完全同口径；
     * 之后跳过 Stage 4 上下文构建与 Stage 5 答案生成——两者只服务作答，
     * 且上下文 token 预算会把召回结果无谓截断。故本方法返回<b>精排后全量</b>切片
     * （按精排序），条数不受 {@code maxTotalTokens} 等作答预算约束，
     * 请求中的预算字段与通路 B 开关在本形态下不参与决策。</p>
     * <p>关键词失败终止（MIX 双层皆空且改写后 query 过长）时跳过 Stage 1~3，
     * 返回空列表，属既定业务分支、不标记降级。</p>
     *
     * @param q 检索内部执行请求（kbId 与 query 必填，其余字段由紧凑构造器归一）
     * @return 命中切片明细（按精排序）；无命中时为空表，永不为 {@code null}
     * @throws IllegalArgumentException 请求对象为空
     * @throws DeepDataAgentException   知识库不存在或不处于 ACTIVE 状态，拒绝检索
     */
    public List<RetrievalChunkView> retrieveChunks(RetrievalQuery q) {
        return doSearch(q, RetrievalOutputMode.CHUNKS_ONLY).chunkViews();
    }

    /**
     * 检索编排主实现（Stage 0~5，按输出形态裁剪尾部阶段）。
     * <p>三种形态共用同一条骨干链路（前置校验 → 通路 A 转译 → Stage 0 → Stage 1~3），
     * 形态差异集中于三处旁路：切片形态跳过 Stage 4~5、答案形态跳过明细回取、
     * 切片明细的取数集合随形态切换；骨干链路本身与 {@code degraded} 累积口径
     * 不随形态变化，故 {@link #search(RetrievalQuery)} 的既有行为零漂移。</p>
     *
     * @param q    检索内部执行请求
     * @param mode 输出形态（决定尾部阶段裁剪与切片取数口径）
     * @return 检索结果（形态未产出的分量为空值：答案形态明细为空表、切片形态 answer 为空）
     */
    private RetrievalResult doSearch(RetrievalQuery q, RetrievalOutputMode mode) {
        if (ObjectUtils.isEmpty(q)) {
            throw new IllegalArgumentException("检索请求不能为空");
        }
        if (!knowledgeBaseApi.isActive(q.kbId())) {
            throw new DeepDataAgentException("知识库不存在或不可用，拒绝检索: kbId=" + q.kbId());
        }
        RetrievalStrategyConfig cfg = resolveStrategyConfig(q.kbId());

        boolean degraded = false;
        // 收尾摘要观测量：Stage0~5 耗时、三通道候选数、LLM 缓存命中数
        AtomicInteger cacheHits = new AtomicInteger();
        long stageMark = System.nanoTime();
        long stage0Ms = 0L;
        long stage1Ms = 0L;
        long stage2Ms = 0L;
        long stage3Ms = 0L;
        long stage4Ms = 0L;
        long stage5Ms = 0L;
        int vectorCount = 0;
        int bm25Count = 0;
        int graphCount = 0;

        // ---- Stage 0 前置：通路 A 检索附图转译----
        // 增强查询（原问题 + 附图描述 + 尾部指导句）只喂检索链路（Stage 0 改写与关键词提取）；
        // 原始 query 全程保留，Stage 5 作答仍引用原始问题（语言规则与引用锚定不受描述文本影响）。
        // 无附图请求不触发任何转译调用，行为与基线逐字段一致。
        // queryImageTranscribe=false：显式关闭通路 A，
        // 请求仍带附图时忽略全部附图并 WARN 留痕（宽容式，不拒绝请求），按纯文本口径继续；
        // 开关为 true 时行为与基线逐字节一致。本开关与通路 B 开关互不感知、无交叉分支。
        String originalQuery = q.query();
        String understandQuery = originalQuery;
        boolean imageEnhanced = false;
        if (CollectionUtils.isNotEmpty(q.images())) {
            if (q.multimodal().queryImageTranscribe()) {
                String transcribed = queryImageTranscriber.transcribe(q.kbId(), originalQuery, q.images());
                if (StringUtils.isNotBlank(transcribed) && !StringUtils.equals(transcribed, originalQuery)) {
                    understandQuery = transcribed;
                    imageEnhanced = true;
                }
            } else {
                log.warn("queryImageTranscribe 开关关闭，忽略请求携带的 {} 张附图并按纯文本检索: kbId={}",
                        q.images().size(), q.kbId());
            }
        }

        // ---- Stage 0：问题改写（异常回退当前查询文本；附图请求的输入为增强查询）----
        String rewritten = understandQuery;
        try {
            rewritten = StringUtils.defaultIfBlank(
                    queryUnderstandingService.rewrite(understandQuery, cfg, q.kbId(), cacheHits), understandQuery);
        } catch (Exception e) {
            degraded = true;
            rewritten = understandQuery;
            log.error("Stage 0 问题改写异常，回退送入检索的 query 继续: kbId={}", q.kbId(), e);
        }
        // ---- Stage 0：双层关键词提取（异常取空关键词继续）----
        KeywordPair pair = new KeywordPair(List.of(), List.of());
        try {
            pair = ObjectUtils.defaultIfNull(
                    queryUnderstandingService.extract(rewritten, q.hlKeywords(), q.llKeywords(), cfg, q.kbId(),
                            cacheHits),
                    pair);
        } catch (Exception e) {
            degraded = true;
            pair = new KeywordPair(List.of(), List.of());
            log.error("Stage 0 关键词提取异常，按空关键词继续: kbId={}", q.kbId(), e);
        }
        stage0Ms = elapsedMillis(stageMark);
        stageMark = System.nanoTime();
        List<String> hl = CollectionUtils.isEmpty(pair.hl()) ? List.of() : pair.hl();
        List<String> ll = CollectionUtils.isEmpty(pair.ll()) ? List.of() : pair.ll();
        // 改写 query 与提取关键词重建执行请求（RetrievalQuery 不可变），后续 Stage 1/5 均消费 effective；
        // 检索附图不进 effective（附件仅由 Stage 0 前置的转译消费，零持久化、不向下游传播）；
        // 多模态开关分量原样携带（通路 B 生效点在作答层，
        // 开关丢失会导致后半链路按默认执行，属必须防住的回归点）；
        // 图谱召回上限分量同样原样携带（丢失即回落默认值，Stage 1 消费的将是本请求的显式取值）
        RetrievalQuery effective = new RetrievalQuery(q.kbId(), rewritten, hl, ll,
                q.topK(), q.chunkTopK(), q.maxTotalTokens(), q.maxEntityTokens(), q.maxRelationTokens(),
                List.of(), q.multimodal(), q.graphEdgeTop(), q.graphEdgeChunkLimit());

        boolean keywordFailed = cfg.strategyType() == RetrievalStrategyType.MIX
                && CollectionUtils.isEmpty(hl) && CollectionUtils.isEmpty(ll)
                && rewritten.length() >= KEYWORD_FAIL_QUERY_MIN_LENGTH;

        KgContext kgContext = EMPTY_KG_CONTEXT;
        List<RankedChunk> ranked = List.of();
        if (keywordFailed) {
            log.warn("Stage 0 关键词兜底失败（MIX 双层皆空且 query 长度 {} ≥ {}），跳过 Stage 1~4: kbId={}",
                    rewritten.length(), KEYWORD_FAIL_QUERY_MIN_LENGTH, q.kbId());
        } else {
            // ---- Stage 1：三路召回（异常取空候选 + 空图谱结果）----
            List<RetrievalCandidate> candidates = List.of();
            KgSearchResult kgResult = EMPTY_KG_RESULT;
            try {
                RecallService.RecallOutcome outcome = recallService.recall(effective, cfg);
                if (ObjectUtils.isNotEmpty(outcome)) {
                    candidates = CollectionUtils.isEmpty(outcome.candidates())
                            ? List.of() : outcome.candidates();
                    kgResult = ObjectUtils.defaultIfNull(outcome.kgResult(), EMPTY_KG_RESULT);
                }
            } catch (Exception e) {
                degraded = true;
                candidates = List.of();
                kgResult = EMPTY_KG_RESULT;
                log.error("Stage 1 召回异常，按空候选继续: kbId={}", q.kbId(), e);
            }
            stage1Ms = elapsedMillis(stageMark);
            stageMark = System.nanoTime();
            vectorCount = countChannel(candidates, RetrievalChannel.VECTOR);
            bm25Count = countChannel(candidates, RetrievalChannel.BM25);
            graphCount = countChannel(candidates, RetrievalChannel.GRAPH);
            // ---- Stage 2：粗排融合（异常按候选原始顺序直排兜底）----
            List<RankedChunk> fused;
            try {
                Map<RetrievalChannel, List<RetrievalCandidate>> channelResults = candidates.stream()
                        .filter(candidate -> ObjectUtils.isNotEmpty(candidate)
                                && ObjectUtils.isNotEmpty(candidate.channel()))
                        .collect(Collectors.groupingBy(RetrievalCandidate::channel));
                fused = fusionService.fuse(channelResults, resolveFusionConfig(cfg));
            } catch (Exception e) {
                degraded = true;
                fused = candidates.stream()
                        .filter(ObjectUtils::isNotEmpty)
                        .limit(RetrievalConstants.RERANK_TOP_K_DEFAULT)
                        .map(candidate -> new RankedChunk(candidate.chunkId(), candidate.score(),
                                candidate.sourceFile()))
                        .toList();
                log.error("Stage 2 融合异常，降级为候选原始顺序直排: kbId={} fallbackCount={}",
                        q.kbId(), fused.size(), e);
            }
            stage2Ms = elapsedMillis(stageMark);
            stageMark = System.nanoTime();
            // ---- Stage 3：精排（异常直出粗排结果）----
            ranked = fused;
            try {
                ranked = reranker.rerank(rewritten, fused, cfg, q.kbId());
            } catch (Exception e) {
                degraded = true;
                log.error("Stage 3 精排异常，直出粗排结果: kbId={}", q.kbId(), e);
            }
            stage3Ms = elapsedMillis(stageMark);
            stageMark = System.nanoTime();
            if (mode == RetrievalOutputMode.CHUNKS_ONLY) {
                // 切片形态：上下文构建与答案生成均只服务作答，且作答 token 预算会裁剪召回结果，
                // 整段跳过（省一次上下文渲染与一次作答 LLM 调用）。以空上下文承载图谱结果，
                // 使 references / rawData 的下游取值口径与 Stage 4 降级路径同形。
                kgContext = new KgContext(StringUtils.EMPTY, List.of(), kgResult);
            } else {
                // ---- Stage 4：上下文构建（必须走带 kbId 的完整入口；异常取空上下文承载图谱结果）----
                try {
                    kgContext = contextBuilder.build(q.kbId(), ranked, kgResult,
                            new ContextBudget(effective.maxTotalTokens(), rewritten,
                                    effective.maxEntityTokens(), effective.maxRelationTokens()));
                } catch (Exception e) {
                    degraded = true;
                    kgContext = new KgContext(StringUtils.EMPTY, List.of(), kgResult);
                    log.error("Stage 4 上下文构建异常，按空上下文继续答案生成: kbId={}", q.kbId(), e);
                }
            }
            stage4Ms = elapsedMillis(stageMark);
        }

        // ---- Stage 5：答案生成（异常置 answer 为 null，主返回不中断）----
        // 附图请求送入作答的问题文本维持原始 query（增强文本只服务检索链路，
        // 作答的语言规则与引用仍锚定用户原话）；无附图请求沿用改写后 query，与基线逐字段一致。
        stageMark = System.nanoTime();
        String answerQuery = imageEnhanced ? originalQuery : rewritten;
        String answer = null;
        if (mode != RetrievalOutputMode.CHUNKS_ONLY) {
            try {
                answer = answerGenerator.generate(answerQuery, kgContext, cfg, effective, cacheHits);
            } catch (Exception e) {
                degraded = true;
                log.error("Stage 5 答案生成异常，返回检索产物（answer 为空）: kbId={}", q.kbId(), e);
            }
        }
        stage5Ms = elapsedMillis(stageMark);
        List<String> references = CollectionUtils.isEmpty(kgContext.referenceList())
                ? List.of() : kgContext.referenceList();
        List<RetrievalChunkView> chunkViews = resolveChunkViews(q.kbId(), mode, kgContext, ranked);
        log.info("检索摘要 | kbId={} mode={} stage0Ms={} stage1Ms={} stage2Ms={} stage3Ms={} stage4Ms={} "
                        + "stage5Ms={} vectorCount={} bm25Count={} graphCount={} chunkCount={} degraded={} "
                        + "llmCacheHits={}",
                q.kbId(), mode, stage0Ms, stage1Ms, stage2Ms, stage3Ms, stage4Ms, stage5Ms,
                vectorCount, bm25Count, graphCount, chunkViews.size(), degraded, cacheHits.get());
        return new RetrievalResult(answer, kgContext, references, toRawData(kgContext.kgResult()),
                chunkViews, degraded);
    }

    /**
     * 按输出形态解析命中切片明细（形态差异的第二处旁路）。
     * <ul>
     *   <li>答案形态：响应不含明细，连明细回取的批量切片查询一并跳过；</li>
     *   <li>切片形态：取 Stage 3 精排<b>全量</b>结果，不经 Stage 4 token 预算裁剪；</li>
     *   <li>完整形态：沿用上下文序明细（与进程内契约历史口径一致，保持不变）。</li>
     * </ul>
     *
     * @param kbId      知识库主键（单库隔离维度）
     * @param mode      输出形态
     * @param kgContext 上下文产物（完整形态的明细取数来源）
     * @param ranked    Stage 3 精排结果（切片形态的明细取数来源）
     * @return 切片明细列表，永不为 {@code null}
     */
    private List<RetrievalChunkView> resolveChunkViews(Long kbId, RetrievalOutputMode mode,
                                                       KgContext kgContext, List<RankedChunk> ranked) {
        if (mode == RetrievalOutputMode.ANSWER_ONLY) {
            return List.of();
        }
        List<RankedChunk> source = mode == RetrievalOutputMode.CHUNKS_ONLY ? ranked : kgContext.kgResult().chunks();
        return buildChunkViews(kbId, source);
    }

    /**
     * 按上下文序组装命中切片明细（正文全量 + 多模态图片引用），供 REST 响应投影。
     * <p>正文与引用经 {@link KnowledgeBaseApi} 只读通道按 {@code kbId} 等值过滤批量回取
     * （单库隔离，跨库 ID 不命中；物理删体系下已删切片天然缺列）。回取异常按空索引继续，
     * 仅使明细正文与引用为空，不改写 {@code degraded}（属响应组装旁路，非检索链路降级）。</p>
     *
     * @param kbId      知识库主键（单库隔离维度）
     * @param chunks    有序切片集合（取数口径由 {@link #resolveChunkViews} 按形态决定，可空）
     * @return 切片明细列表（与入参同序）；无切片或回取全部未命中时为空表，永不为空指针
     */
    private List<RetrievalChunkView> buildChunkViews(Long kbId, List<RankedChunk> chunks) {
        if (CollectionUtils.isEmpty(chunks)) {
            return List.of();
        }
        List<Long> chunkIds = chunks.stream()
                .filter(ObjectUtils::isNotEmpty)
                .map(RankedChunk::chunkId)
                .filter(ObjectUtils::isNotEmpty)
                .toList();
        Map<Long, Chunk> chunkById = fetchChunksById(kbId, chunkIds);
        return chunks.stream()
                .filter(ObjectUtils::isNotEmpty)
                .map(ranked -> toChunkView(ranked, chunkById.get(ranked.chunkId())))
                .toList();
    }

    /**
     * 批量回取切片并按主键索引；回取失败按空索引返回（明细降级，不中断主返回）。
     *
     * @param kbId     知识库主键
     * @param chunkIds 切片主键集合（非空）
     * @return chunkId → 切片的索引；无命中或异常时为空 Map
     */
    private Map<Long, Chunk> fetchChunksById(Long kbId, List<Long> chunkIds) {
        if (CollectionUtils.isEmpty(chunkIds)) {
            return Map.of();
        }
        try {
            List<Chunk> found = knowledgeBaseApi.findChunksByKbIdAndChunkIds(kbId, chunkIds);
            if (CollectionUtils.isEmpty(found)) {
                return Map.of();
            }
            return found.stream()
                    .filter(chunk -> ObjectUtils.isNotEmpty(chunk) && ObjectUtils.isNotEmpty(chunk.id()))
                    .collect(Collectors.toMap(Chunk::id, chunk -> chunk, (first, second) -> first));
        } catch (Exception e) {
            log.error("检索明细回取切片失败，正文与多模态引用按空返回: kbId={} chunkCount={}",
                    kbId, chunkIds.size(), e);
            return Collections.emptyMap();
        }
    }

    /**
     * 单个有序切片 + 切片行 → 明细值对象（切片缺失时正文与引用为空）。
     *
     * @param ranked 图谱有序切片
     * @param chunk  回取到的切片行，可为 {@code null}（已删除或回取失败）
     * @return 明细值对象
     */
    private RetrievalChunkView toChunkView(RankedChunk ranked, Chunk chunk) {
        String content = ObjectUtils.isEmpty(chunk) ? null : chunk.chunkContent();
        String sourceFile = ObjectUtils.isNotEmpty(ranked.sourceFile()) ? ranked.sourceFile()
                : (ObjectUtils.isEmpty(chunk) ? null : chunk.sourceFileName());
        MediaReference media = ObjectUtils.isEmpty(chunk) ? MediaReference.EMPTY : resolveMediaReference(chunk);
        return new RetrievalChunkView(ranked.chunkId(), content, sourceFile,
                media.objectKey(), ranked.score());
    }

    /**
     * 解析切片的多模态图片对象引用（双源回退；桶概念已退役，仅按对象键定位）。
     * <p>优先读一等列 {@code chunk.s3_file}（{@code {objectKey}} 形态），缺失时回退
     * {@code chunk.original_item} 的媒体注入键（{@code mediaObjectKey}）——
     * 与摄入落库的双写口径一致。对象键非空白才视为有引用。</p>
     *
     * @param chunk 切片行
     * @return 媒体引用；两源均无有效引用时为 {@link MediaReference#EMPTY}
     */
    private MediaReference resolveMediaReference(Chunk chunk) {
        MediaReference fromS3File = parseMediaReference(chunk.s3File(), S3_FIELD_OBJECT_KEY);
        if (fromS3File.isPresent()) {
            return fromS3File;
        }
        return parseMediaReference(chunk.originalItem(), MultimodalMetaKeys.META_KEY_MEDIA_OBJECT_KEY);
    }

    /**
     * 按指定键名解析对象引用 JSON（空白、非对象、缺键或解析异常均按无引用返回，绝不抛）。
     *
     * @param json           待解析 JSON 串，可为空
     * @param objectKeyField 对象键
     * @return 媒体引用；无有效引用时为 {@link MediaReference#EMPTY}
     */
    private MediaReference parseMediaReference(String json, String objectKeyField) {
        if (StringUtils.isBlank(json)) {
            return MediaReference.EMPTY;
        }
        try {
            JsonNode root = MEDIA_REF_MAPPER.readTree(json);
            if (ObjectUtils.isEmpty(root) || !root.isObject()) {
                return MediaReference.EMPTY;
            }
            String objectKey = textValue(root, objectKeyField);
            if (StringUtils.isBlank(objectKey)) {
                return MediaReference.EMPTY;
            }
            return new MediaReference(objectKey);
        } catch (Exception e) {
            log.warn("切片媒体引用解析失败，按无多模态资源返回: {}", e.getMessage());
            return MediaReference.EMPTY;
        }
    }

    /**
     * 读取 JSON 文本字段（缺失或非文本返回 null）。
     *
     * @param root JSON 根节点
     * @param field 字段名
     * @return 字段文本值或 null
     */
    private static String textValue(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return ObjectUtils.isEmpty(node) || !node.isTextual() ? null : node.asText();
    }

    /**
     * 检索输出形态（编排链路的尾部阶段裁剪口径，仅服务内部使用，不对外暴露）。
     * <p>三种形态共用 Stage 0~3 骨干链路，差异只在尾部旁路：是否构建上下文与作答、
     * 是否回取切片明细、明细取精排集还是上下文集。裁剪属纯旁路——被跳过阶段本就不产出
     * 该形态所需字段，不改变任一已产出字段的口径。</p>
     */
    private enum RetrievalOutputMode {

        /** 完整形态：Stage 0~5 全跑 + 上下文序明细回取（进程内契约 {@code RetrievalApi} 消费） */
        FULL,

        /** 答案形态：Stage 0~5 全跑，跳过切片明细回取（REST 答案端点消费） */
        ANSWER_ONLY,

        /** 切片形态：仅跑至 Stage 3，跳过上下文构建与答案生成，明细取精排全量（REST 切片端点消费） */
        CHUNKS_ONLY
    }

    /**
     * 切片多模态图片引用中间态（仅对象键，桶概念已退役；缺失即空引用）。
     *
     * @param objectKey 对象键
     */
    private record MediaReference(String objectKey) {

        /** 无引用常量（文本切片即此形态） */
        private static final MediaReference EMPTY = new MediaReference(null);

        /**
         * 是否构成有效引用（对象键非空白）。
         *
         * @return 有效返回真
         */
        boolean isPresent() {
            return StringUtils.isNotBlank(objectKey);
        }
    }

    /**
     * 计算自计时点起经过的毫秒数（收尾摘要耗时口径，四舍五入）。
     *
     * @param sinceNanos {@link System#nanoTime()} 计时点
     * @return 经过的毫秒数
     */
    private static long elapsedMillis(long sinceNanos) {
        return Math.round((System.nanoTime() - sinceNanos) / 1_000_000.0);
    }

    /**
     * 统计指定召回通道的候选数（收尾摘要三通道口径）。
     *
     * @param candidates 三路召回合并候选列表（可空）
     * @param channel    待统计通道
     * @return 该通道候选数（空列表或空元素不计入）
     */
    private static int countChannel(List<RetrievalCandidate> candidates, RetrievalChannel channel) {
        if (CollectionUtils.isEmpty(candidates)) {
            return 0;
        }
        return (int) candidates.stream()
                .filter(candidate -> ObjectUtils.isNotEmpty(candidate) && channel.equals(candidate.channel()))
                .count();
    }

    /**
     * 读取库级检索策略快照；未配置（返回 null）时按 NAIVE 默认策略兜底。
     * <p>兜底口径：rewriteQuestion=false（不改写）、strategyType=NAIVE（不做关键词提取、
     * 不触发图谱通道）；resultChunkCount / similarThreshold / rerankConfig / fusionConfig
     * 传 null——两数值字段的判空语义已由下游服务承接（精排不截断、向量通道按默认阈值口径），
     * 融合配置缺失由编排层兜底 RRF。</p>
     *
     * @param kbId 知识库主键
     * @return 策略配置快照，永不为空
     */
    private RetrievalStrategyConfig resolveStrategyConfig(Long kbId) {
        RetrievalStrategyConfig cfg = knowledgeBaseApi.findRetrievalStrategyByKbId(kbId);
        if (ObjectUtils.isNotEmpty(cfg)) {
            return cfg;
        }
        log.warn("知识库未配置检索策略，按 NAIVE 默认策略兜底: kbId={}", kbId);
        return FALLBACK_NAIVE_STRATEGY_CONFIG;
    }

    /**
     * 解析融合策略配置；库级配置未携带 fusionConfig 时兜底默认 RRF（k={@link FusionStrategyConfig#DEFAULT_RRF_K}）。
     *
     * @param cfg 检索策略配置快照
     * @return 融合策略配置，永不为空
     */
    private FusionStrategyConfig resolveFusionConfig(RetrievalStrategyConfig cfg) {
        if (ObjectUtils.isNotEmpty(cfg.fusionConfig())) {
            return cfg.fusionConfig();
        }
        return FALLBACK_RRF_FUSION_CONFIG;
    }

    /**
     * 图谱检索结果展开为结构化原始数据（entities / relations / chunks 三键，保序）。
     * <p>entities/relations 直接放结构化命中列表（{@code EntityHit}/{@code RelationHit}），
     * 与上下文渲染同源；该映射仅经进程内契约 {@code RetrievalResult#rawData()} 传递，
     * REST 两形态均不外露，故值对象形态变化不影响对外契约。</p>
     *
     * @param kgResult 图谱检索结果，可为空（按空集合展开）
     * @return 原始数据映射，永不为空且各值非 null
     */
    private Map<String, Object> toRawData(KgSearchResult kgResult) {
        KgSearchResult safe = ObjectUtils.isEmpty(kgResult) ? EMPTY_KG_RESULT : kgResult;
        Map<String, Object> rawData = new LinkedHashMap<>(4);
        rawData.put(RAW_KEY_ENTITIES, CollectionUtils.isEmpty(safe.entities()) ? List.of() : safe.entities());
        rawData.put(RAW_KEY_RELATIONS, CollectionUtils.isEmpty(safe.relations()) ? List.of() : safe.relations());
        rawData.put(RAW_KEY_CHUNKS, CollectionUtils.isEmpty(safe.chunks()) ? List.of() : safe.chunks());
        return rawData;
    }
}
