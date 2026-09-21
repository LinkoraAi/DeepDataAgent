package com.linkroa.deepdataagent.rag.application.service;

import com.linkroa.deepdataagent.knowledgebase.api.DocumentGraphConvergencePlan;
import com.linkroa.deepdataagent.knowledgebase.api.DocumentGraphConvergenceResult;
import com.linkroa.deepdataagent.knowledgebase.api.KnowledgeBaseApi;
import com.linkroa.deepdataagent.knowledgebase.domain.model.EntityType;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.KbLanguage;
import com.linkroa.deepdataagent.rag.domain.enums.CacheType;
import com.linkroa.deepdataagent.rag.domain.repository.ChunkExtractCacheRepository;
import com.linkroa.deepdataagent.rag.domain.repository.EntityInfoVectorRepository;
import com.linkroa.deepdataagent.rag.domain.repository.EntityNodeGraphRepository;
import com.linkroa.deepdataagent.rag.domain.repository.LlmCacheRepository;
import com.linkroa.deepdataagent.rag.domain.repository.RelationEdgeGraphRepository;
import com.linkroa.deepdataagent.rag.domain.repository.RelationInfoVectorRepository;
import com.linkroa.deepdataagent.rag.domain.service.GraphContributionRebuildService;
import com.linkroa.deepdataagent.rag.domain.service.GraphMergeParams;
import com.linkroa.deepdataagent.rag.domain.service.GraphRebuildContext;
import com.linkroa.deepdataagent.rag.domain.service.GraphRebuildPlan;
import com.linkroa.deepdataagent.rag.domain.service.GraphRebuildReport;
import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 图谱贡献收敛应用服务（两段式删除链的事务编排，账本为准、显式驱动；
 * OpenSpec rebuild-kg-on-document-delete / 组 6，design D3/D4/D7/D9）。
 *
 * <p><b>两段式职责</b>：</p>
 * <ol>
 *   <li>{@link #prepare}——<b>事务外段，零写入、零事务</b>：组装 {@link GraphRebuildContext}
 *       （库级配置经 {@link KnowledgeBaseApi} 只读通道取得，与摄入端同源）后调用重建服务
 *       阶段 A {@code compute}——重放抽取缓存、描述摘要与向量化（远程调用）全部完成、结果仅存
 *       内存值对象。远程调用 MUST NOT 进入数据库事务（事务规范 1.1：持有连接时间拉长到 LLM
 *       量级会耗尽连接池），故本段必须先于任何事务开启；</li>
 *   <li>{@link #apply}——<b>事务内段（单个 {@link TransactionTemplate} 事务，传播 REQUIRED，
 *       调用方已有事务时并入不新起）</b>，固定次序：
 *       ① 重建阶段 B 写回（组 4：加锁重读 → 锁定账本重算 → 绝对值写入 / 存活为空转删除，
 *       内含剔空物理删与语义字段/向量写回）→ ② 向量账本收缩（兜底安全网：只读重建未触及却
 *       仍与被删批次有交集的账本行；重建已改写/删除的行对本语句零命中，两步处理集不相交、
 *       不重复计数）→ ③ 图行展示来源列收缩（同为零命中安全网，收缩数仅留痕不计删除口径）→
 *       ④ 抽取缓存回收（{@link #reclaimExtractCache}，四步严格顺序）；全程纯 DB CRUD、零远程。
 *       <b>分块表示与分块行的物理删除由调用方（删除原语）排在本方法之后、同一事务内</b>——
 *       本服务不触碰 {@code chunk} 表（BC 边界）；</li>
 *   <li>{@link #reconcilePendingVectorContent}——<b>事务提交后段，事务外</b>：重建阶段 C 收口
 *       （内含向量化远程调用，MUST NOT 入事务；收口失败仅 WARN，不影响已提交删除）。</li>
 * </ol>
 *
 * <p><b>幂等重入依据（顺序不变量的全部意义）</b>：阶段 A 与写回事务之间崩溃——本服务未发生
 * 任何写入，重试从 {@code prepare} 重跑；写回事务内崩溃/失败——整体回滚且<b>分块行仍在</b>
 * （分块行删除晚于收敛与回收、且同事务），重试可再次从「条目账本 ∩ 被删分块」重推出同一批
 * 受影响条目（升序归一的批次形态保证推导恒定）。整链不引入任何日志表 / 待办记录 / 恢复证明
 * （Non-Goals），可重入性完全由该顺序不变量承担。重复执行第二次起：账本已收缩、条目已消失、
 * 归属已清理、缓存已回收，全部语句零影响。</p>
 *
 * <p><b>固定加锁顺序与三链一致性（事务规范 3.1）</b>：实体按名、关系按归一端点对升序加锁
 * （组 4 写回纪律），收缩/回收语句按 {@code kb_id} 等值 + 批次升序下发；人工块链、文档链、
 * 整库链共用同一删除原语事务内核，本服务的执行次序在三条链上逐字相同，链间交叉不产生
 * 反向加锁序。三链一致性未被本变更破坏：变化仅发生在事务内部（收敛与分块行删除的先后对调），
 * 对任何并发链仍是「先锁图行、后删分块行」的同向次序。</p>
 *
 * <p><b>权重语义（RQ-22 已平账，6.5 订正）</b>：条目权重按存活来源<b>全额重算</b>（回扣已实现，
 * design D6），抽取缓存不可用时按降级保留原值并计入降级报告——此前「weight 不回扣、与上游
 * LightRAG 语义一致」的表述失实（上游会重算，仅缓存缺失时降级保留），已随组 6 更正。</p>
 *
 * <p><b>缓存回收口径（design D4，严格顺序、宁缺勿滥删）</b>：先追溯本批归属键集，再删本批归属行，
 * 再对追溯集做引用判定，<b>只删「追溯到且已归零」的缓存行</b>；MUST NOT 反向以「查不到归属」删行
 * （会误杀历史行与非抽取分类行）。回收全程限定同 {@code kbId} 与 {@code EXTRACT} 分类；回收行数
 * 进入 INFO 日志与 {@link DocumentGraphConvergenceResult}（可观测）。</p>
 *
 * <p><b>重建能力缺失的降级</b>：库级摘要/向量模型引用未配置或配置 JSON 损坏时
 * {@code prepare} 产出「跳过重建」句柄并 WARN（不阻断删除链，删除属清理语义、可用性优先）；
 * 此时条目语义字段保持原值（等同整批降级，降级计数不逐条目产生、以 WARN 留痕），账本收缩与
 * 缓存回收照常执行。远程调用失败（运行失败）不降级、照实上抛（design D7 两分类）。</p>
 *
 * @author DeepDataAgent
 */
@Service
public class DocumentGraphConvergenceService {

    /** 日志器 */
    private static final Logger log = LoggerFactory.getLogger(DocumentGraphConvergenceService.class);

    /** rag_engine_config JSON 字段名：图合并段（驼峰写法，与 IngestionWorker 解析同源） */
    private static final String FIELD_GRAPH_MERGE_CAMEL = "graphMerge";

    /** rag_engine_config JSON 字段名：图合并段（下划线写法，与 IngestionWorker 解析同源） */
    private static final String FIELD_GRAPH_MERGE_SNAKE = "graph_merge";

    /** 库级语言未配置时的兜底值（与摄入端 {@code effectiveLanguage} 同口径） */
    private static final String DEFAULT_LANGUAGE = KbLanguage.Chinese.name();

    /** 图合并段 JSON → 参数映射的类型引用 */
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    /** 图谱贡献重建领域服务（组 4 三段式 API 的编排对象） */
    private final GraphContributionRebuildService rebuildService;

    /** 实体向量仓储端口（账本收缩兜底安全网） */
    private final EntityInfoVectorRepository entityInfoVectorRepository;

    /** 关系向量仓储端口（账本收缩兜底安全网） */
    private final RelationInfoVectorRepository relationInfoVectorRepository;

    /** 实体图行仓储端口（展示来源列收缩） */
    private final EntityNodeGraphRepository entityNodeGraphRepository;

    /** 关系图行仓储端口（展示来源列收缩） */
    private final RelationEdgeGraphRepository relationEdgeGraphRepository;

    /** 抽取缓存归属仓储端口（回收四步中的追溯、删归属与引用判定） */
    private final ChunkExtractCacheRepository chunkExtractCacheRepository;

    /** LLM 缓存仓储端口（回收四步中归零键的批量删除） */
    private final LlmCacheRepository llmCacheRepository;

    /** 知识库跨 BC 契约（重建所需库级配置的只读取数通道） */
    private final KnowledgeBaseApi knowledgeBaseApi;

    /** 编程式事务模板（事务内段单事务；REQUIRED——调用方已有事务时并入） */
    private final TransactionTemplate transactionTemplate;

    /** JSON 解析器（rag_engine_config 图合并段解析，与摄入端同栈） */
    private final ObjectMapper objectMapper;

    /** 是否启用 JSON 输出模式抽取（重放解析分支开关，与抽取期取同一配置值） */
    @Value("${app.rag.ingestion.extraction.json-mode:false}")
    private boolean extractionJsonMode;

    /** 应用级 source_ids 保留上限（知识库级 JSONB 配置可逐项覆盖本值） */
    @Value("${app.rag.graph.source-ids-limit:200}")
    private int graphSourceIdsLimit;

    /** 应用级 source_ids 截断策略（KEEP / FIFO，非法值回落 KEEP） */
    @Value("${app.rag.graph.source-ids-truncation:KEEP}")
    private String graphSourceIdsTruncation;

    /** 应用级来源文件路径列表保留上限 */
    @Value("${app.rag.graph.source-file-paths-limit:75}")
    private int graphSourceFilePathsLimit;

    /** 应用级来源文件路径溢出占位词 */
    @Value("${app.rag.graph.source-file-paths-placeholder:…等}")
    private String graphSourceFilePathsPlaceholder;

    /**
     * 应用级图合并参数基底缓存：<b>@Value 注入完成后</b>首次调用时计算一次并复用
     * （严禁在构造器内求值——构造先于字段注入）。
     * <p><b>钉死前提</b>：{@code app.rag.graph.*} 应用级配置启动后不变、不支持热更新——
     * 原实现虽每次重建参数对象，但读取的同样是注入后不再刷新的字段，二者行为等价。</p>
     */
    private GraphMergeParams cachedAppLevelGraphMergeParams;

    /**
     * 构造图谱贡献收敛应用服务。
     *
     * @param rebuildService             图谱贡献重建领域服务（组 4）
     * @param entityInfoVectorRepository 实体向量仓储端口
     * @param relationInfoVectorRepository 关系向量仓储端口
     * @param entityNodeGraphRepository    实体图行仓储端口
     * @param relationEdgeGraphRepository  关系图行仓储端口
     * @param chunkExtractCacheRepository  抽取缓存归属仓储端口
     * @param llmCacheRepository           LLM 缓存仓储端口
     * @param knowledgeBaseApi             知识库跨 BC 契约（只读取数）
     * @param transactionTemplate          编程式事务模板
     * @param objectMapper                 JSON 解析器
     */
    public DocumentGraphConvergenceService(GraphContributionRebuildService rebuildService,
                                           EntityInfoVectorRepository entityInfoVectorRepository,
                                           RelationInfoVectorRepository relationInfoVectorRepository,
                                           EntityNodeGraphRepository entityNodeGraphRepository,
                                           RelationEdgeGraphRepository relationEdgeGraphRepository,
                                           ChunkExtractCacheRepository chunkExtractCacheRepository,
                                           LlmCacheRepository llmCacheRepository,
                                           KnowledgeBaseApi knowledgeBaseApi,
                                           TransactionTemplate transactionTemplate,
                                           ObjectMapper objectMapper) {
        this.rebuildService = rebuildService;
        this.entityInfoVectorRepository = entityInfoVectorRepository;
        this.relationInfoVectorRepository = relationInfoVectorRepository;
        this.entityNodeGraphRepository = entityNodeGraphRepository;
        this.relationEdgeGraphRepository = relationEdgeGraphRepository;
        this.chunkExtractCacheRepository = chunkExtractCacheRepository;
        this.llmCacheRepository = llmCacheRepository;
        this.knowledgeBaseApi = knowledgeBaseApi;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
    }

    // ================================================================ 事务外段：重建计算

    /**
     * 事务外准备：组装重建上下文并执行重建阶段 A（重放 + 摘要 + 向量化，仅内存、零写入）。
     * <p>MUST 在数据库事务外调用（本方法不开启事务；内部远程调用为设计使然）。kbId 为空或
     * 批次为空产出零句柄（{@link #apply} 零影响、零 DB 交互）。远程失败照实上抛（运行失败）；
     * 仅「重建能力缺失」（模型引用未配置 / 配置 JSON 损坏）与审计锚点缺失降级为跳过重建句柄。</p>
     *
     * @param kbId              被清退分块所属知识库ID（必填；为空产出零句柄）
     * @param deletedDocumentId 审计锚点文档ID（null → 跳过重建的降级句柄，见类 Javadoc）
     * @param removedChunkIds   被清退的切片ID集合（null / 空 → 零句柄）
     * @param operator          操作人标识（重建写回审计透传，可空）
     * @return 收敛计划句柄（rag 侧实现，对消费方不透明）
     */
    public ConvergencePlan prepare(Long kbId, Long deletedDocumentId,
                                   Collection<Long> removedChunkIds, String operator) {
        List<Long> chunkIds = normalizeChunkIds(removedChunkIds);
        if (ObjectUtils.isEmpty(kbId) || chunkIds.isEmpty()) {
            return ConvergencePlan.inert(kbId, chunkIds);
        }
        if (ObjectUtils.isEmpty(deletedDocumentId)) {
            log.warn("缺少审计锚点文档ID，本批跳过图谱重建（仅账本收缩与缓存回收）: kbId={}, 分块数={}",
                    kbId, chunkIds.size());
            return ConvergencePlan.inert(kbId, chunkIds);
        }
        try {
            GraphRebuildContext ctx = buildContext(kbId, deletedDocumentId, operator);
            GraphRebuildPlan plan = rebuildService.compute(ctx, chunkIds);
            return new ConvergencePlan(kbId, chunkIds, ctx, plan);
        } catch (IllegalArgumentException | DeepDataAgentException e) {
            // 重建能力缺失（模型引用未配置 / 库级配置 JSON 损坏）：降级跳过重建，不阻断删除链；
            // 远程失败（IllegalStateException 等运行失败）不落入本分支，照实上抛（design D7）
            log.warn("库级重建能力缺失，本批跳过图谱重建（仅账本收缩与缓存回收）: kbId={}, 分块数={}, 原因={}",
                    kbId, chunkIds.size(), e.getMessage());
            return ConvergencePlan.inert(kbId, chunkIds);
        }
    }

    // ================================================================ 事务内段：写回 + 收缩 + 回收

    /**
     * 事务内应用：重建写回 → 账本收缩 → 展示列收缩 → 缓存回收（单事务原子、纯 DB CRUD、零远程）。
     * <p>计数口径见 {@link DocumentGraphConvergenceResult}；重建与收缩的处理集不相交、不重复
     * 计数（重建已改写/删除的行对收缩语句零命中）。零句柄（kbId 缺失 / 批次为空）不开事务、
     * 返回全零结果。句柄携带的降级标记（重建能力缺失）同样零重建交互，但账本收缩与缓存回收
     * 照常执行。事务内任一步失败整体回滚并上抛——分块行仍在（其删除由调用方排在本方法之后、
     * 同一事务内），重试自 {@link #prepare} 重新推导。</p>
     *
     * @param plan {@link #prepare} 产出的句柄，必填
     * @return 聚合计数结果
     * @throws IllegalArgumentException 句柄为 null
     */
    public DocumentGraphConvergenceResult apply(ConvergencePlan plan) {
        if (ObjectUtils.isEmpty(plan)) {
            throw new IllegalArgumentException("收敛计划句柄不能为空");
        }
        if (ObjectUtils.isEmpty(plan.kbId()) || CollectionUtils.isEmpty(plan.removedChunkIds())) {
            return DocumentGraphConvergenceResult.empty();
        }
        DocumentGraphConvergenceResult result = transactionTemplate.execute(status -> doApply(plan));
        return ObjectUtils.isEmpty(result) ? DocumentGraphConvergenceResult.empty() : result;
    }

    /**
     * 事务体：四步固定序（次序即类 Javadoc 声明的执行序，任何调换即破坏顺序不变量或产生
     * 无谓更新）。
     *
     * @param plan 收敛计划句柄
     * @return 聚合计数结果
     */
    private DocumentGraphConvergenceResult doApply(ConvergencePlan plan) {
        Long kbId = plan.kbId();
        List<Long> chunkIds = plan.removedChunkIds();
        // ① 重建阶段 B：写回重算语义字段与向量（内含剔空物理删；降级句柄整体跳过，零图交互）
        GraphRebuildReport report = GraphRebuildReport.empty();
        if (!plan.rebuildSkipped()) {
            report = rebuildService.apply(plan.rebuildContext(), plan.rebuildPlan());
        }
        // ② 向量账本收缩（兜底安全网：重建未触及却仍与批次有交集的账本行；已改写/已删行零命中）
        int ledgerPruned = entityInfoVectorRepository.removeChunkContributionsAndPrune(kbId, chunkIds)
                + relationInfoVectorRepository.removeChunkContributionsAndPrune(kbId, chunkIds);
        // ③ 图行展示来源列收缩（MUST 置于剔空删图行之后，避免对将删行做无谓更新；收缩数仅留痕）
        int entityShrunk = entityNodeGraphRepository.shrinkDisplaySourceIds(kbId, chunkIds);
        int relationShrunk = relationEdgeGraphRepository.shrinkDisplaySourceIds(kbId, chunkIds);
        // ④ 抽取缓存回收（四步严格顺序，design D4）
        ReclaimOutcome reclaim = reclaimExtractCache(kbId, chunkIds);
        log.info("图谱贡献收敛完成: kbId={}, 被清退切片数={}, 重建条目数={}, 降级条目数={}, "
                        + "收敛删除条目数={}, 写回缺失条目数={}, 兜底账本剔空数={}, 展示列收缩数={}, "
                        + "归属回收行数={}, 缓存回收行数={}, 待向量收口数={}",
                kbId, chunkIds.size(), report.rebuiltEntries(), report.degradedEntries(),
                report.prunedEntries() + ledgerPruned, report.missingEntries(), ledgerPruned,
                entityShrunk + relationShrunk, reclaim.attributionRows(), reclaim.cacheRows(),
                report.pendingVectorSyncEntityNames().size() + report.pendingVectorSyncRelationPairs().size());
        return new DocumentGraphConvergenceResult(report.prunedEntries() + ledgerPruned,
                report.rebuiltEntries(), report.degradedEntries(), report.missingEntries(),
                reclaim.attributionRows(), reclaim.cacheRows(),
                report.pendingVectorSyncEntityNames(), report.pendingVectorSyncRelationPairs());
    }

    /**
     * 抽取缓存回收四步（严格顺序，design D4；事务体内纯 SQL）：
     * <ol>
     *   <li>{@code findCacheKeysByChunkIds}——先追溯「本次被删分块用过的缓存键集合」；</li>
     *   <li>{@code deleteByChunkIds}——再删本批归属行（此后引用判定才不会再计入本批自身）；</li>
     *   <li>{@code findReferencedKeys}——对追溯集判定「仍被其他存活分块引用」的键；</li>
     *   <li>只删「追溯到且引用已归零」的缓存行——MUST NOT 反向以「查不到归属」删任何行。</li>
     * </ol>
     * <p>追溯集为空（本批无归属，含历史数据缺失归属）时第 ③④ 步零操作——历史无归属缓存行
     * MUST 原样保留。共用键（如被存活分块 777 引用的键）在第 ③ 步命中保留、第 ④ 步不删。
     * 全部操作限定同 {@code kbId} 与 {@code EXTRACT} 分类。回收行数返回供 INFO 日志与结果计数。</p>
     *
     * @param kbId      知识库ID（回收隔离维度）
     * @param chunkIds  本批被删分块ID（升序去重）
     * @return 回收行数事实（归属行 / 缓存行）
     */
    private ReclaimOutcome reclaimExtractCache(Long kbId, List<Long> chunkIds) {
        Map<Long, Set<String>> keysByChunk =
                chunkExtractCacheRepository.findCacheKeysByChunkIds(kbId, CacheType.EXTRACT, chunkIds);
        Set<String> tracedKeys = new LinkedHashSet<>();
        if (ObjectUtils.isNotEmpty(keysByChunk)) {
            keysByChunk.values().stream()
                    .filter(Objects::nonNull)
                    .flatMap(Collection::stream)
                    .filter(StringUtils::isNotBlank)
                    .forEach(tracedKeys::add);
        }
        int attributionRows = chunkExtractCacheRepository.deleteByChunkIds(kbId, chunkIds);
        Set<String> stillReferenced = tracedKeys.isEmpty()
                ? Set.of() : chunkExtractCacheRepository.findReferencedKeys(kbId, CacheType.EXTRACT, tracedKeys);
        List<String> orphanKeys = tracedKeys.stream()
                .filter(key -> ObjectUtils.isEmpty(stillReferenced) || !stillReferenced.contains(key))
                .sorted()
                .toList();
        int cacheRows = orphanKeys.isEmpty()
                ? 0 : llmCacheRepository.deleteByKbIdAndCacheKeys(kbId, CacheType.EXTRACT, orphanKeys);
        if (!orphanKeys.isEmpty()) {
            log.info("抽取缓存按引用计数回收: kbId={}, 追溯键数={}, 仍被引用保留数={}, 归零回收缓存行数={}",
                    kbId, tracedKeys.size(), tracedKeys.size() - orphanKeys.size(), cacheRows);
        }
        return new ReclaimOutcome(attributionRows, cacheRows);
    }

    // ================================================================ 事务提交后段：向量收口

    /**
     * 事务提交后收口：把「内容与向量未成对写回」的条目修复为最终一致（重建阶段 C，事务外远程）。
     * <p>降级句柄与空清单零操作；收口原语自身吞异常（仅 WARN），MUST NOT 影响已提交的删除事实。</p>
     *
     * @param plan   {@link #prepare} 产出的句柄（null / 降级句柄零操作）
     * @param result {@link #apply} 的返回结果（提供待收口清单；null 零操作）
     */
    public void reconcilePendingVectorContent(ConvergencePlan plan, DocumentGraphConvergenceResult result) {
        if (ObjectUtils.isEmpty(plan) || plan.rebuildSkipped() || ObjectUtils.isEmpty(result)) {
            return;
        }
        if (result.pendingVectorSyncEntityNames().isEmpty() && result.pendingVectorSyncRelationPairs().isEmpty()) {
            return;
        }
        rebuildService.reconcilePendingVectorContent(plan.rebuildContext(), new GraphRebuildReport(
                result.rebuiltEntries(), result.degradedEntries(), result.prunedEntries(),
                result.missingEntries(), result.pendingVectorSyncEntityNames(),
                result.pendingVectorSyncRelationPairs()));
    }

    // ================================================================ 重建上下文组装（只读取数）

    /**
     * 组装重建上下文：库级配置全部经 {@link KnowledgeBaseApi} 只读通道取得，与摄入端同源——
     * 实体类型清单（entity_type_config）、语言（language 列）、摘要模型引用（multi_model_config，
     * 即该库通用 LLM）、向量模型引用（embedding_config）、JSON 输出模式与图合并参数
     * （应用级 {@code app.rag.graph.*} 基底 + rag_engine_config 图合并段逐项覆盖，优先级与
     * 摄入端 {@code IngestionWorker#resolveGraphMergeParams} 一致）。
     * 模型引用缺失 / 配置 JSON 损坏由 {@link GraphRebuildContext} 紧凑构造器或解析层抛出，
     * 由 {@link #prepare} 统一按「重建能力缺失」降级处置。
     *
     * @param kbId              知识库ID
     * @param deletedDocumentId 审计锚点文档ID（非空已由调用方保证）
     * @param operator          操作人标识（可空）
     * @return 重建上下文（非空）
     */
    private GraphRebuildContext buildContext(Long kbId, Long deletedDocumentId, String operator) {
        String summaryProfileId = knowledgeBaseApi.findMediaModelProfileIdByKbId(kbId);
        String embeddingProfileId = knowledgeBaseApi.findEmbeddingModelProfileIdByKbId(kbId);
        List<EntityType> entityTypes = knowledgeBaseApi.findEntityTypesByKbId(kbId);
        String language = StringUtils.defaultIfBlank(knowledgeBaseApi.findLanguageByKbId(kbId), DEFAULT_LANGUAGE);
        return new GraphRebuildContext(kbId, deletedDocumentId, entityTypes, extractionJsonMode,
                summaryProfileId, embeddingProfileId, language, resolveGraphMergeParams(kbId),
                operator, null);
    }

    /**
     * 解析图合并参数：应用级配置（{@code app.rag.graph.*}）为基底，rag_engine_config 原文根级
     * 探测 {@code graphMerge / graph_merge} 子段逐项覆盖（知识库级 &gt; 应用级 &gt; 内置默认），
     * 子段缺失或解析失败回落应用级基底——与摄入端 {@code IngestionWorker#resolveGraphMergeParams}
     * 同口径，保证重建与合并产出同形（spec R2「同口径」要求的参数侧落点）。
     *
     * @param kbId 知识库ID
     * @return 图合并参数（非空）
     */
    private GraphMergeParams resolveGraphMergeParams(Long kbId) {
        GraphMergeParams appBase = appLevelGraphMergeParams();
        String configJson = knowledgeBaseApi.findRagEngineConfigJsonByKbId(kbId);
        if (StringUtils.isBlank(configJson)) {
            return appBase;
        }
        try {
            JsonNode root = objectMapper.readTree(configJson);
            JsonNode mergeNode = root.path(FIELD_GRAPH_MERGE_CAMEL);
            if (mergeNode.isMissingNode() || mergeNode.isNull()) {
                mergeNode = root.path(FIELD_GRAPH_MERGE_SNAKE);
            }
            if (mergeNode.isMissingNode() || mergeNode.isNull() || !mergeNode.isObject()) {
                return appBase;
            }
            return GraphMergeParams.fromMap(objectMapper.convertValue(mergeNode, MAP_TYPE), appBase);
        } catch (JacksonException | IllegalArgumentException e) {
            log.warn("图合并段配置解析失败，回落应用级参数: kbId={}", kbId, e);
            return appBase;
        }
    }

    /**
     * 取应用级图合并参数基底（首次调用惰性构造后缓存复用）。
     * <p>基底混入 {@code @Value} 应用级配置，编译期常量不成立，故以实例字段缓存一次
     * （钉死前提见 {@link #cachedAppLevelGraphMergeParams} 字段注释）；与摄入端
     * {@code IngestionWorker#appLevelGraphMergeParams} 同口径，保证重建与合并基底同源。</p>
     *
     * @return 应用级参数基底（非空，同值复用同一实例）
     */
    private synchronized GraphMergeParams appLevelGraphMergeParams() {
        if (ObjectUtils.isEmpty(cachedAppLevelGraphMergeParams)) {
            cachedAppLevelGraphMergeParams = new GraphMergeParams(
                    GraphMergeParams.DEFAULT_LLM_MODEL_MAX_ASYNC,
                    graphSourceIdsLimit,
                    GraphMergeParams.DEFAULT_EMBEDDING_TOKEN_LIMIT,
                    GraphMergeParams.DEFAULT_SUMMARY_CONTEXT_SIZE,
                    GraphMergeParams.DEFAULT_SUMMARY_MAX_TOKENS,
                    GraphMergeParams.DEFAULT_FORCE_LLM_SUMMARY_ON_MERGE,
                    graphSourceIdsTruncation,
                    graphSourceFilePathsLimit,
                    graphSourceFilePathsPlaceholder);
        }
        return cachedAppLevelGraphMergeParams;
    }

    /**
     * 入参分块集合归一（去 null、去重、升序）：批次形态恒定保证崩溃重试可推出同一受影响集合。
     *
     * @param chunkIds 原始分块集合（可为 null）
     * @return 升序去重列表
     */
    private static List<Long> normalizeChunkIds(Collection<Long> chunkIds) {
        if (CollectionUtils.isEmpty(chunkIds)) {
            return List.of();
        }
        return chunkIds.stream().filter(Objects::nonNull).distinct().sorted().toList();
    }

    // ================================================================ 内部值对象

    /**
     * 收敛计划句柄（{@link DocumentGraphConvergencePlan} 的 rag 侧实现，包装组 4 的
     * {@link GraphRebuildContext} 与 {@link GraphRebuildPlan}）。
     * <p>{@code rebuildContext / rebuildPlan} 同时为 null 即「跳过重建」降级形态（重建能力缺失
     * 或审计锚点缺失）——{@code apply} 仍执行账本收缩与缓存回收。句柄不承载批次之外的状态，
     * MUST NOT 跨批次复用。</p>
     *
     * @param kbId           知识库ID
     * @param removedChunkIds 本批被清退分块ID（升序去重）
     * @param rebuildContext 重建上下文（null = 跳过重建）
     * @param rebuildPlan    重建计划（null = 跳过重建）
     */
    public record ConvergencePlan(Long kbId, List<Long> removedChunkIds,
                                  GraphRebuildContext rebuildContext, GraphRebuildPlan rebuildPlan)
            implements DocumentGraphConvergencePlan {

        /**
         * 紧凑构造器：批次列表不可变归一。
         */
        public ConvergencePlan {
            removedChunkIds = ObjectUtils.isEmpty(removedChunkIds) ? List.of() : List.copyOf(removedChunkIds);
        }

        /**
         * 零句柄 / 跳过重建句柄（无上下文与计划，apply 仅执行收缩与回收）。
         *
         * @param kbId             知识库ID（可为 null = 防御短路形态）
         * @param removedChunkIds  归一后的批次列表
         * @return 降级句柄
         */
        public static ConvergencePlan inert(Long kbId, List<Long> removedChunkIds) {
            return new ConvergencePlan(kbId, removedChunkIds, null, null);
        }

        /**
         * 是否跳过重建（重建能力缺失或空批次的降级形态）。
         *
         * @return 跳过返回 true
         */
        public boolean rebuildSkipped() {
            return ObjectUtils.isEmpty(rebuildContext) || ObjectUtils.isEmpty(rebuildPlan);
        }
    }

    /**
     * 缓存回收行数事实（内部传递）。
     *
     * @param attributionRows 归属行回收数
     * @param cacheRows       缓存行回收数
     */
    private record ReclaimOutcome(int attributionRows, int cacheRows) {
    }
}
