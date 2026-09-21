package com.linkroa.deepdataagent.rag.domain.service;

import com.linkroa.deepdataagent.knowledgebase.api.KnowledgeBaseApi;
import com.linkroa.deepdataagent.knowledgebase.domain.model.Chunk;
import com.linkroa.deepdataagent.rag.domain.model.ContentBlockVO;
import com.linkroa.deepdataagent.rag.domain.model.EntityInfoVector;
import com.linkroa.deepdataagent.rag.domain.model.EntityLedgerSnapshot;
import com.linkroa.deepdataagent.rag.domain.model.EntityNode;
import com.linkroa.deepdataagent.rag.domain.model.EntityProperties;
import com.linkroa.deepdataagent.rag.domain.model.GraphSourceFilePaths;
import com.linkroa.deepdataagent.rag.domain.model.RelationEdge;
import com.linkroa.deepdataagent.rag.domain.model.RelationInfoVector;
import com.linkroa.deepdataagent.rag.domain.model.RelationLedgerSnapshot;
import com.linkroa.deepdataagent.rag.domain.model.RelationProperties;
import com.linkroa.deepdataagent.rag.domain.port.EmbeddingClient;
import com.linkroa.deepdataagent.rag.domain.repository.EntityInfoVectorRepository;
import com.linkroa.deepdataagent.rag.domain.repository.EntityNodeGraphRepository;
import com.linkroa.deepdataagent.rag.domain.repository.RelationEdgeGraphRepository;
import com.linkroa.deepdataagent.rag.domain.repository.RelationInfoVectorRepository;
import com.linkroa.deepdataagent.shared.util.BatchSplitter;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * 图谱贡献重建领域服务（OpenSpec rebuild-kg-on-document-delete / 组 4，spec R1~R4、design D3/D6/D9/D10）。
 *
 * <p><b>职责</b>：文档删除链中，以「条目账本 ∩ 本批被删分块 ≠ ∅」为谓词发现受影响条目并分类
 * （剔后仍有存活来源 → 重建；剔后无存活来源 → 直接物理删除），用存活分块的抽取缓存重放
 * （{@link EntityExtractionService#replayExtractedChunks}，与抽取同形、零 LLM）为唯一语义来源，
 * 将条目的描述 / 实体类型 / 关系权重 / 关键词 / 来源文件路径整体重算为<b>存活记录集合的绝对值
 * 函数</b>，并在写回事务内以锁定账本为准落库。未被触及的条目零读写；全部被删分块都不产生图谱
 * 贡献时（账本重叠查询零命中）返回空计划、对图谱零读写。</p>
 *
 * <p><b>两段式 API 契约（design D3 + 事务规范 1.1，本类最重要的结构约束）</b>：</p>
 * <ol>
 *   <li>{@link #compute}——<b>阶段 A，事务外执行</b>：纯读（账本重叠查询、归属/缓存重放、
 *       分块只读回取）+ 远程（描述摘要、向量化），产出内存值对象 {@link GraphRebuildPlan}，
 *       全程<b>零写入</b>（任何 lock/upsert/delete 都不出现）；</li>
 *   <li>{@link #apply}——<b>阶段 B，由调用方在其单事务内调用</b>：纯数据库 CRUD
 *       （加锁重读 → 以锁定账本重算存活集合 → 绝对值写入 / 存活为空转删除），
 *       全程<b>零远程调用</b>（重放、摘要、向量化均不进入本阶段；存活集合与快照不一致时改用
 *       确定性聚合在内存重算，见 design D9 的既定取舍）；</li>
 *   <li>{@link #reconcilePendingVectorContent}——<b>阶段 C（收尾），事务提交后、事务外执行</b>：
 *       对阶段 B 中因并发偏差无法在事务内成对写入（内容,向量）的条目，复用
 *       {@link VectorContentReconciler} 既有收口原语（tasks 4.6），把向量行内容修复为
 *       「已提交最终描述的函数」（spec R4 场景「向量内容与最终描述一致」）。</li>
 * </ol>
 *
 * <p><b>事务边界归属</b>：本服务自身<b>不加 {@code @Transactional}</b>——重建的摘要与向量化是
 * 远程调用，严禁进入任何数据库事务（事务规范 1.1）；阶段 B 只含纯 DB CRUD，事务边界由调用方
 * （组 6 文档删除链收敛服务）以编程式事务承担。阶段 A/B 之间进程崩溃不留下任何已写入的重建
 * 结果，重试从「账本 ∩ 被删分块」重推同一批受影响条目（幂等重入，不依赖任何日志或待办记录）。</p>
 *
 * <p><b>写回纪律（spec R4 / design D9）</b>：阶段 B 逐条目「加锁重读 → 以锁定账本剔除被删分块
 * 得存活集合 → 绝对值写入（MUST NOT 增量）→ 存活为空转删除（图行与向量行成对、图先行，与既有
 * 四步收敛①②同序）→ 条目已不存在且存活为空则保持不存在（MUST NOT 复活、MUST NOT 插入）」。
 * 跨实例正确性由本纪律承担，<b>不引入全局互斥</b>（把锁横跨阶段 A 的远程调用会让整库摄入在删除
 * 期间停摆，亦违反事务规范；上游 LightRAG 的全局 pipeline 独占是其跨存储无事务的产物，本项目
 * 有行锁可用）。条目级并发互斥由数据库行锁（实体按名 FOR UPDATE、关系按归一端点对）与固定加锁序
 * （清单按名/端点对升序装载）保证；进程内不再叠条纹锁——阶段 B 单事务顺序处理、阶段 A 零写入。</p>
 *
 * <p><b>语义降级与运行失败严格分开（design D7）</b>：降级=条目任一存活分块无可用重放数据
 * （归属缺失/缓存行缺失/响应不可解析/分块经只读通道不可见），或该条目在全部存活重放记录中
 * 无任何迹可循 → <b>保留原语义字段、仅修账本与来源文件路径</b>，计入
 * {@link GraphRebuildReport#degradedEntries()}，MUST NOT 中断删除链；运行失败=阶段 A 远程调用
 * 异常或阶段 B 任何 SQL 异常 → <b>照实上抛</b>，由调用方事务回滚语义处理、保持可重试。
 * 两者绝不混同（存储故障不得被误吞成「降级」）。</p>
 *
 * <p><b>并发闸门（tasks 4.7）</b>：阶段 A 的条目级远程计算复用图合并并发闸门
 * {@link GraphMergeParams#concurrencyGate()}（{@code llm_model_max_async × 2}），与
 * {@link GraphMergeService} 同族形态（虚拟线程扇出 + Semaphore 限流 + 阶段级 fail-closed）。
 * 分批沿用删除链既有批次（每批 ≤500 分块）；同一文档跨批时条目可能被重建多次、最终以最后一批
 * 收敛（每批只剔该批来源，design D10），本服务不引入跨批状态。</p>
 *
 * @author DeepDataAgent
 */
@Service
public class GraphContributionRebuildService {
    /** 日志器 */
    private static final Logger log = LoggerFactory.getLogger(GraphContributionRebuildService.class);

    /** 确定性描述聚合的分段连接符（与 {@link DescriptionSummarizer} 非 LLM 分支的换行拼接同口径） */
    private static final String DESCRIPTION_FRAGMENT_JOINER = "\n";

    /** 关系摘要名称连接符（仅用于 Prompt 展示，与 {@code GraphMergeService} 同字面值） */
    private static final String RELATION_NAME_CONNECTOR = "~";

    /** 关系类型标签的关键词拼接分隔符（与合并端关键词 join 口径一致） */
    private static final String KEYWORD_JOIN_SEPARATOR = ",";

    /** 端点对聚合键分隔符（NUL 字符，实体名不可能含该字符，与抽取汇聚器同族约定） */
    private static final String PAIR_KEY_SEPARATOR = "\u0000";

    /** 取消中断异常文案（删除链当前无取消器，检查器命中即按运行失败上抛） */
    private static final String CANCELLED_MESSAGE = "图谱贡献重建已取消，中止重建计算";

    /** 阶段名——实体重建计算（fail-closed 异常消息定位） */
    private static final String PHASE_ENTITY_COMPUTE = "实体重建计算";

    /** 阶段名——关系重建计算 */
    private static final String PHASE_RELATION_COMPUTE = "关系重建计算";

    /** 阶段 B 加锁重读的单批条目标识数（沿用项目 500 分批惯例，避免超长 IN 列表） */
    private static final int LOCK_QUERY_BATCH_SIZE = 500;

    /**
     * 分块 {@code original_item} meta 映射的反序列化类型常量（Jackson 3 {@code tools.jackson}，
     * 与本类 {@code ObjectMapper} 同栈）。
     * <p>{@code TypeReference} 不可变、线程安全，泛型父类解析在构造期完成，静态复用避免
     * 删除链逐存活分块解析时反复构造匿名子类；注意与 {@code com.fasterxml}（Jackson 2）的
     * 同名类不是同一个类，不得跨栈混用。</p>
     */
    private static final tools.jackson.core.type.TypeReference<Map<String, Object>> META_MAP_TYPE =
            new tools.jackson.core.type.TypeReference<>() {
            };

    /** 抽取缓存重放入口（组 3 落地，纯读缓存 + 解析、零 LLM） */
    private final EntityExtractionService entityExtractionService;

    /** 知识库跨 BC 只读契约（存活分块正文与块 meta 的回取通道，与检索链路同源） */
    private final KnowledgeBaseApi knowledgeBaseApi;

    /** 实体图行仓储（阶段 B 加锁重读与绝对值写回 / 转删除） */
    private final EntityNodeGraphRepository entityNodeRepository;

    /** 关系图行仓储 */
    private final RelationEdgeGraphRepository relationEdgeRepository;

    /** 实体向量仓储（账本权威侧；阶段 A 受影响条目发现、阶段 B 加锁与写回） */
    private final EntityInfoVectorRepository entityInfoVectorRepository;

    /** 关系向量仓储（账本权威侧） */
    private final RelationInfoVectorRepository relationInfoVectorRepository;

    /** 描述摘要器（阶段 A 事务外远程；条数与 token 阈值在此路径真实生效） */
    private final DescriptionSummarizer descriptionSummarizer;

    /** 向量化端口（阶段 A 事务外远程；阶段 B 零触碰） */
    private final EmbeddingClient embeddingClient;

    /** Token 计数器（向量内容截断口径与合并/收口路径同源） */
    private final TokenCounter tokenCounter;

    /** 向量内容收口原语（阶段 C 提交后修复内容-描述一致性，tasks 4.6 复用点） */
    private final VectorContentReconciler vectorContentReconciler;

    /** JSON 解析器（分块 {@code original_item} 块 meta 轻解析，仅取多模态定形主实体名） */
    private final ObjectMapper objectMapper;

    /** 摄入扇出执行器（阶段 A 条目级并发载体，与合并/抽取同池） */
    private final Executor ingestionExecutor;

    /**
     * 构造图谱贡献重建服务。
     *
     * @param entityExtractionService    抽取缓存重放入口
     * @param knowledgeBaseApi           知识库跨 BC 只读契约（存活分块回取）
     * @param entityNodeRepository       实体图行仓储
     * @param relationEdgeRepository     关系图行仓储
     * @param entityInfoVectorRepository 实体向量仓储（账本权威）
     * @param relationInfoVectorRepository 关系向量仓储（账本权威）
     * @param descriptionSummarizer      描述摘要器（事务外）
     * @param embeddingClient            向量化端口（事务外）
     * @param tokenCounter               Token 计数器
     * @param vectorContentReconciler    向量内容收口原语（提交后）
     * @param objectMapper               JSON 解析器
     * @param ingestionExecutor          虚拟线程扇出执行器（ragFanoutExecutor）
     */
    public GraphContributionRebuildService(EntityExtractionService entityExtractionService,
                                           KnowledgeBaseApi knowledgeBaseApi,
                                           EntityNodeGraphRepository entityNodeRepository,
                                           RelationEdgeGraphRepository relationEdgeRepository,
                                           EntityInfoVectorRepository entityInfoVectorRepository,
                                           RelationInfoVectorRepository relationInfoVectorRepository,
                                           DescriptionSummarizer descriptionSummarizer,
                                           EmbeddingClient embeddingClient,
                                           TokenCounter tokenCounter,
                                           VectorContentReconciler vectorContentReconciler,
                                           ObjectMapper objectMapper,
                                           @Qualifier("ragFanoutExecutor") Executor ingestionExecutor) {
        this.entityExtractionService = entityExtractionService;
        this.knowledgeBaseApi = knowledgeBaseApi;
        this.entityNodeRepository = entityNodeRepository;
        this.relationEdgeRepository = relationEdgeRepository;
        this.entityInfoVectorRepository = entityInfoVectorRepository;
        this.relationInfoVectorRepository = relationInfoVectorRepository;
        this.descriptionSummarizer = descriptionSummarizer;
        this.embeddingClient = embeddingClient;
        this.tokenCounter = tokenCounter;
        this.vectorContentReconciler = vectorContentReconciler;
        this.objectMapper = objectMapper;
        this.ingestionExecutor = ingestionExecutor;
    }
    // ================================================================ 阶段 A：重建计算（事务外）

    /**
     * 阶段 A：计算本批被删分块触发的重建计划（<b>纯读 + 远程，零写入</b>，design D3 步骤②）。
     *
     * <p>流程：账本重叠查询发现受影响条目 → 逐条分类（重建 / 直接删除）→ 回取存活分块并经
     * 归属表重放抽取缓存（零 LLM）→ 条目级并行「摘要 + 向量化」（并发闸门
     * {@link GraphMergeParams#concurrencyGate()}）→ 组装不可变计划。</p>
     *
     * <p><b>分类权威与零读写纪律</b>：受影响集合以向量表 {@code chunk_ids} 为唯一账本推出
     * （「账本 ∩ 本批 ≠ ∅」）；剔后无存活来源的条目进入直接删除清单、MUST NOT 参与重放；
     * 未被触及的条目不进入任何清单、零读零写。全部被删分块都不产生图谱贡献时（两次重叠查询
     * 均无命中）不读任何分块与缓存、返回空计划。</p>
     *
     * <p><b>降级判定口径（spec R3）</b>：条目任一存活分块无可用重放数据（无归属键、缓存行
     * 缺失、响应解析为空，或分块经只读通道不可见——文档非「已处理」态时按可得部分处置），
     * 或该条目在全部存活重放记录中无任何迹可循 → 标记降级：阶段 B 保留原语义字段、仅修账本
     * 与来源路径。降级是计划条目的数据标记，不是异常，MUST NOT 中断删除链。</p>
     *
     * @param ctx             重建上下文（库级配置 + 模型引用 + 参数；不可为 null）
     * @param deletedChunkIds 本批被删分块标识（null / 空 / 仅含 null → 空计划，零 DB 交互）
     * @return 重建计划（含重建条目清单与直接删除清单；无贡献时为空计划）
     * @throws IllegalArgumentException 上下文为 null
     * @throws IllegalStateException    已取消，或阶段 A 任一远程/构建任务失败（fail-closed 首异常上抛）
     */
    public GraphRebuildPlan compute(GraphRebuildContext ctx, Collection<Long> deletedChunkIds) {
        if (ObjectUtils.isEmpty(ctx)) {
            throw new IllegalArgumentException("图谱重建上下文不能为空");
        }
        if (ctx.isCancelled()) {
            throw new IllegalStateException(CANCELLED_MESSAGE);
        }
        List<Long> deleted = normalizeChunkIds(deletedChunkIds);
        if (deleted.isEmpty()) {
            return GraphRebuildPlan.empty(List.of());
        }
        Set<Long> deletedSet = new HashSet<>(deleted);
        // ① 受影响条目发现（账本权威=向量表 chunk_ids）② 逐条分类（重建 / 直接删除）
        Map<String, List<Long>> entitySurvivors = new LinkedHashMap<>();
        Map<String, EntityLedgerSnapshot> entitySnapshots = new LinkedHashMap<>();
        List<String> prunedEntityNames = new ArrayList<>();
        classifyEntities(ctx.kbId(), deleted, deletedSet, entitySurvivors, entitySnapshots, prunedEntityNames);
        Map<String, List<Long>> relationSurvivors = new LinkedHashMap<>();
        Map<String, RelationLedgerSnapshot> relationSnapshots = new LinkedHashMap<>();
        List<List<String>> prunedRelationPairs = new ArrayList<>();
        classifyRelations(ctx.kbId(), deleted, deletedSet, relationSurvivors, relationSnapshots,
                prunedRelationPairs);
        // ③ 存活分块统一回取 + 批量重放（仅重建条目需要；无重建需求时零分块读取、零重放）
        Set<Long> survivorUnion = new TreeSet<>();
        entitySurvivors.values().forEach(survivorUnion::addAll);
        relationSurvivors.values().forEach(survivorUnion::addAll);
        ReplayBundle replay = loadAndReplay(ctx, survivorUnion);
        // ④ 条目级「摘要 + 向量化」并行（阶段 A 唯一远程段；阶段级 fail-closed）
        GraphMergeContext mergeCtx = new GraphMergeContext(ctx.kbId(), ctx.deletedDocumentId(),
                ctx.summaryModelProfileId(), ctx.embeddingModelProfileId(), ctx.language(),
                ctx.params(), ctx.operator(), ctx.cancellationCheck());
        Semaphore gate = new Semaphore(ctx.params().concurrencyGate());
        List<GraphRebuildPlan.EntityRebuildItem> entityItems = runComputePhase(PHASE_ENTITY_COMPUTE,
                new ArrayList<>(entitySnapshots.keySet()),
                name -> buildEntityItem(ctx, mergeCtx, entitySnapshots.get(name), entitySurvivors.get(name),
                        replay, name),
                gate);
        List<GraphRebuildPlan.RelationRebuildItem> relationItems = runComputePhase(PHASE_RELATION_COMPUTE,
                new ArrayList<>(relationSnapshots.keySet()),
                pairKey -> buildRelationItem(ctx, mergeCtx, relationSnapshots.get(pairKey),
                        relationSurvivors.get(pairKey), replay, pairKey),
                gate);
        log.info("图谱重建计算完成：kbId=[{}]，documentId=[{}]，被删分块数=[{}]，重建实体数=[{}]，"
                        + "重建关系数=[{}]，直接删除条目数=[{}]，降级条目数=[{}]，重放分块数=[{}]",
                ctx.kbId(), ctx.deletedDocumentId(), deleted.size(), entityItems.size(), relationItems.size(),
                prunedEntityNames.size() + prunedRelationPairs.size(),
                countDegraded(entityItems, relationItems), survivorUnion.size());
        return new GraphRebuildPlan(deleted, entityItems, relationItems,
                prunedEntityNames.stream().distinct().sorted().toList(), sortPairs(prunedRelationPairs));
    }

    /**
     * 受影响实体发现与分类（账本重叠查询 → 逐条「剔除被删分块」）。
     *
     * @param kbId            所属知识库ID
     * @param deleted         本批被删分块（升序去重，谓词入参形态恒定保证重入同集）
     * @param deletedSet      本批被删分块集合（剔账本用）
     * @param entitySurvivors 出参：实体名 → 快照存活账本（升序）
     * @param entitySnapshots 出参：实体名 → 账本重叠快照
     * @param prunedNames     出参：剔后无存活来源的实体名（直接删除清单）
     */
    private void classifyEntities(Long kbId, List<Long> deleted, Set<Long> deletedSet,
                                  Map<String, List<Long>> entitySurvivors,
                                  Map<String, EntityLedgerSnapshot> entitySnapshots, List<String> prunedNames) {
        for (EntityLedgerSnapshot snapshot : entityInfoVectorRepository
                .findLedgerSnapshotsWithChunkOverlap(kbId, deleted)) {
            if (ObjectUtils.isEmpty(snapshot)) {
                continue;
            }
            List<Long> survivors = subtractSorted(snapshot.chunkIds(), deletedSet);
            if (survivors.isEmpty()) {
                prunedNames.add(snapshot.entityName());
                continue;
            }
            entitySnapshots.put(snapshot.entityName(), snapshot);
            entitySurvivors.put(snapshot.entityName(), survivors);
        }
    }

    /**
     * 受影响关系发现与分类（端点对以「源\u0000目标」聚合键承载，与快照一一关联且升序稳定）。
     *
     * @param kbId              所属知识库ID
     * @param deleted           本批被删分块（升序去重，谓词入参形态恒定保证重入同集）
     * @param deletedSet        本批被删分块集合（剔账本用）
     * @param relationSurvivors 出参：端点对聚合键 → 快照存活账本（升序）
     * @param relationSnapshots 出参：端点对聚合键 → 账本重叠快照
     * @param prunedPairs       出参：剔后无存活来源的归一端点对（直接删除清单）
     */
    private void classifyRelations(Long kbId, List<Long> deleted, Set<Long> deletedSet,
                                   Map<String, List<Long>> relationSurvivors,
                                   Map<String, RelationLedgerSnapshot> relationSnapshots,
                                   List<List<String>> prunedPairs) {
        for (RelationLedgerSnapshot snapshot : relationInfoVectorRepository
                .findLedgerSnapshotsWithChunkOverlap(kbId, deleted)) {
            if (ObjectUtils.isEmpty(snapshot)) {
                continue;
            }
            List<Long> survivors = subtractSorted(snapshot.chunkIds(), deletedSet);
            if (survivors.isEmpty()) {
                prunedPairs.add(snapshot.normalizedPair());
                continue;
            }
            String pairKey = snapshot.sourceName() + PAIR_KEY_SEPARATOR + snapshot.targetName();
            relationSnapshots.put(pairKey, snapshot);
            relationSurvivors.put(pairKey, survivors);
        }
    }
    /**
     * 存活分块回取 + 抽取缓存批量重放（组 3 入口，零 LLM）。
     * <p>不可见分块（所属文档非「已处理」态或行已被并发清退）无法构造重放输入，天然落入
     * 「无可用重放数据」集；可用集 = 重放结果含任一实体或关系的分块（重放对缓存缺失/解析失败
     * 返回空记录集，与「该块本就无产出」在本口径下统一按不可用处理——保守降级、保留原语义，
     * 不误信残缺记录集）。来源文件路径按可得集记录（不可见分块缺键）。</p>
     *
     * @param ctx           重建上下文
     * @param survivorUnion 全部重建条目的存活分块并集（升序去重）
     * @return 重放数据束（分块记录集 + 可用分块集 + 分块路径表）
     */
    private ReplayBundle loadAndReplay(GraphRebuildContext ctx, Set<Long> survivorUnion) {
        if (CollectionUtils.isEmpty(survivorUnion)) {
            return new ReplayBundle(Map.of(), Set.of(), Map.of());
        }
        Map<Long, Chunk> chunkById = new LinkedHashMap<>();
        for (Chunk chunk : knowledgeBaseApi.findChunksByKbIdAndChunkIds(ctx.kbId(), survivorUnion)) {
            if (ObjectUtils.isNotEmpty(chunk) && ObjectUtils.isNotEmpty(chunk.id())) {
                chunkById.put(chunk.id(), chunk);
            }
        }
        Map<Long, String> pathByChunk = new LinkedHashMap<>();
        List<ReplayChunkInput> inputs = new ArrayList<>(chunkById.size());
        for (Long chunkId : survivorUnion) {
            Chunk chunk = chunkById.get(chunkId);
            if (ObjectUtils.isEmpty(chunk)) {
                continue;
            }
            if (StringUtils.isNotBlank(chunk.sourceFileName())) {
                pathByChunk.put(chunkId, chunk.sourceFileName());
            }
            ContentBlockVO block = toBlock(chunk);
            inputs.add(new ReplayChunkInput(chunkId, chunk.chunkContent(), block, mediaPrimaryEntityName(block)));
        }
        Map<Long, EntityExtractionResult> replay = entityExtractionService.replayExtractedChunks(
                new ChunkReplayContext(ctx.kbId(), ctx.entityTypes(), ctx.jsonMode()), inputs);
        Set<Long> usableChunks = new HashSet<>();
        replay.forEach((chunkId, result) -> {
            if (ObjectUtils.isNotEmpty(result)
                    && (CollectionUtils.isNotEmpty(result.nodes()) || CollectionUtils.isNotEmpty(result.edges()))) {
                usableChunks.add(chunkId);
            }
        });
        return new ReplayBundle(replay, usableChunks, pathByChunk);
    }

    /**
     * 构建单个实体的重建计划条目（收集存活记录 → 降级判定 → 绝对值重算：描述摘要 + 票数众数）。
     *
     * @param ctx       重建上下文
     * @param mergeCtx  图合并上下文（摘要器入参形态，承载模型引用/语言/参数/取消检查）
     * @param snapshot  账本重叠快照
     * @param survivors 快照存活账本（升序）
     * @param replay    重放数据束
     * @param name      实体名
     * @return 实体重建计划条目
     */
    private GraphRebuildPlan.EntityRebuildItem buildEntityItem(GraphRebuildContext ctx, GraphMergeContext mergeCtx,
                                                               EntityLedgerSnapshot snapshot, List<Long> survivors,
                                                               ReplayBundle replay, String name) {
        if (ctx.isCancelled()) {
            throw new IllegalStateException(CANCELLED_MESSAGE);
        }
        List<EntityNode> records = collectEntityRecords(survivors, name, replay);
        List<Long> unusableSources = survivors.stream()
                .filter(chunkId -> !replay.usableChunks().contains(chunkId))
                .toList();
        Map<Long, String> survivorPaths = survivorPaths(survivors, replay);
        // 降级判定（spec R3）：任一存活来源无可用重放数据，或条目在全部存活记录中无迹可循
        if (CollectionUtils.isNotEmpty(unusableSources) || records.isEmpty()) {
            log.warn("实体重建降级（保留语义字段、仅修账本与来源路径）：kbId=[{}]，实体=[{}]，"
                            + "无可用重放的存活来源数=[{}]，存活来源数=[{}]",
                    ctx.kbId(), name, unusableSources.size(), survivors.size());
            return new GraphRebuildPlan.EntityRebuildItem(name, survivors, List.of(), survivorPaths,
                    snapshot.properties(), true, null, null, Map.of(), null, null);
        }
        List<String> fragments = distinctDescriptions(records.stream()
                .map(node -> node.properties().description()).toList());
        Map<String, Integer> votes = countEntityVotes(records);
        String entityType = StringUtils.defaultIfBlank(EntityProperties.primaryEntityType(votes),
                records.get(0).properties().entityType());
        String summary = descriptionSummarizer.summarize(mergeCtx, name, entityType, fragments);
        // 空描述兜底口径归模型所有（EntityNode.effectiveDescription），此处取其结果保证内容-描述同源
        String finalDescription = new EntityNode(ctx.kbId(), name, new EntityProperties(entityType, summary,
                survivors, List.of(), votes, List.of())).effectiveDescription();
        String content = tokenCounter.truncate(EntityInfoVector.buildContent(name, finalDescription),
                ctx.params().embeddingTokenLimit());
        float[] vector = reuseOrEmbedEntity(ctx, snapshot, content);
        return new GraphRebuildPlan.EntityRebuildItem(name, survivors, records, survivorPaths,
                snapshot.properties(), false, finalDescription, entityType, votes, content, vector);
    }

    /**
     * 构建单个关系的重建计划条目（绝对值重算：权重=存活记录全额求和（D6 回扣）、
     * 关键词=拆分去重后字典序、描述=存活碎片交摘要处理）。
     *
     * @param ctx       重建上下文
     * @param mergeCtx  图合并上下文
     * @param snapshot  账本重叠快照
     * @param survivors 快照存活账本（升序）
     * @param replay    重放数据束
     * @param pairKey   端点对聚合键（{@code 源\u0000目标}）
     * @return 关系重建计划条目
     */
    private GraphRebuildPlan.RelationRebuildItem buildRelationItem(GraphRebuildContext ctx,
                                                                   GraphMergeContext mergeCtx,
                                                                   RelationLedgerSnapshot snapshot,
                                                                   List<Long> survivors,
                                                                   ReplayBundle replay, String pairKey) {
        if (ctx.isCancelled()) {
            throw new IllegalStateException(CANCELLED_MESSAGE);
        }
        int split = pairKey.indexOf(PAIR_KEY_SEPARATOR);
        String source = pairKey.substring(0, split);
        String target = pairKey.substring(split + 1);
        List<RelationEdge> records = collectRelationRecords(survivors, source, target, replay);
        List<Long> unusableSources = survivors.stream()
                .filter(chunkId -> !replay.usableChunks().contains(chunkId))
                .toList();
        Map<Long, String> survivorPaths = survivorPaths(survivors, replay);
        if (CollectionUtils.isNotEmpty(unusableSources) || records.isEmpty()) {
            log.warn("关系重建降级（保留语义字段、仅修账本与来源路径）：kbId=[{}]，端点对=[{}~{}]，"
                            + "无可用重放的存活来源数=[{}]，存活来源数=[{}]",
                    ctx.kbId(), source, target, unusableSources.size(), survivors.size());
            return new GraphRebuildPlan.RelationRebuildItem(source, target, survivors, List.of(), survivorPaths,
                    snapshot.properties(), true, 0.0, List.of(), null, null, null);
        }
        // 权重回扣（D6）：重算为「存活来源记录的全额权重之和」，与合并端「逐来源全额累加」严格互逆
        double weight = records.stream().mapToDouble(edge -> edge.properties().weight()).sum();
        List<String> keywords = mergeKeywords(records);
        List<String> fragments = distinctDescriptions(records.stream()
                .map(edge -> edge.properties().description()).toList());
        String summary = descriptionSummarizer.summarize(mergeCtx, source + RELATION_NAME_CONNECTOR + target,
                String.join(KEYWORD_JOIN_SEPARATOR, keywords), fragments);
        String finalDescription = StringUtils.defaultIfBlank(summary, records.get(0).properties().description());
        String content = tokenCounter.truncate(RelationInfoVector.buildContent(keywords, source, target,
                finalDescription), ctx.params().embeddingTokenLimit());
        float[] vector = reuseOrEmbedRelation(ctx, snapshot, content);
        return new GraphRebuildPlan.RelationRebuildItem(source, target, survivors, records, survivorPaths,
                snapshot.properties(), false, weight, keywords, finalDescription, content, vector);
    }
    /**
     * 阶段 A 实体收集（tasks 4.2）：按存活分块升序收集该实体的全部重放记录。
     * <p>重放出口「分块 → 记录集」与抽取出口同形，实体记录名已在重放侧归一，等值匹配即可。</p>
     *
     * @param survivors 存活分块升序列表
     * @param name      目标实体名
     * @param replay    重放数据束
     * @return 该实体的存活记录列表（按分块升序，每分块至多一条）
     */
    private static List<EntityNode> collectEntityRecords(List<Long> survivors, String name, ReplayBundle replay) {
        List<EntityNode> records = new ArrayList<>();
        for (Long chunkId : survivors) {
            EntityExtractionResult result = replay.replayByChunk().get(chunkId);
            if (ObjectUtils.isEmpty(result)) {
                continue;
            }
            result.nodes().stream()
                    .filter(node -> ObjectUtils.isNotEmpty(node) && StringUtils.equals(node.entityName(), name))
                    .findFirst()
                    .ifPresent(records::add);
        }
        return records;
    }

    /**
     * 阶段 A 关系收集：按存活分块升序收集该归一端点对的全部重放边记录。
     *
     * @param survivors 存活分块升序列表
     * @param source    归一端点对之源
     * @param target    归一端点对之目标
     * @param replay    重放数据束
     * @return 该端点对的存活记录列表（按分块升序，每条边 sourceIds 为单分块全额）
     */
    private static List<RelationEdge> collectRelationRecords(List<Long> survivors, String source, String target,
                                                             ReplayBundle replay) {
        List<RelationEdge> records = new ArrayList<>();
        List<String> pair = List.of(source, target);
        for (Long chunkId : survivors) {
            EntityExtractionResult result = replay.replayByChunk().get(chunkId);
            if (ObjectUtils.isEmpty(result)) {
                continue;
            }
            result.edges().stream()
                    .filter(edge -> ObjectUtils.isNotEmpty(edge) && pair.equals(edge.normalizedPair()))
                    .findFirst()
                    .ifPresent(records::add);
        }
        return records;
    }

    /**
     * 阶段级 fail-closed 的条目并行执行（并发闸门复用图合并 {@code concurrencyGate()}，形态与
     * {@code GraphMergeService#runPhase} 一致：虚拟线程扇出、任一失败即上抛首异常、其余 suppressed）。
     *
     * @param phaseName 阶段名（异常消息定位）
     * @param items     待处理条目键列表（装载时已排序，返回与入参同序）
     * @param builder   单条计划构建函数（内部含远程调用）
     * @param gate      并发闸门
     * @param <K>       条目键类型
     * @param <R>       计划条目类型
     * @return 计划条目列表（与入参键同序）
     * @throws IllegalStateException 任一任务失败（含取消检查命中）
     */
    private <K, R> List<R> runComputePhase(String phaseName, List<K> items, Function<K, R> builder,
                                           Semaphore gate) {
        if (CollectionUtils.isEmpty(items)) {
            return List.of();
        }
        List<CompletableFuture<R>> futures = new ArrayList<>(items.size());
        for (K key : items) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    gate.acquire();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(phaseName + "等待并发闸门被中断", interrupted);
                }
                try {
                    return builder.apply(key);
                } finally {
                    gate.release();
                }
            }, ingestionExecutor));
        }
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        List<R> results = new ArrayList<>(items.size());
        for (CompletableFuture<R> future : futures) {
            try {
                results.add(future.join());
            } catch (Exception failed) {
                failures.add(unwrapCompletionFailure(failed));
            }
        }
        if (CollectionUtils.isNotEmpty(failures)) {
            List<Throwable> errors = new ArrayList<>(failures);
            Throwable first = errors.get(0);
            for (int index = 1; index < errors.size(); index++) {
                first.addSuppressed(errors.get(index));
            }
            throw new IllegalStateException(phaseName + "失败：" + first.getMessage(), first);
        }
        return results;
    }

    /**
     * 解包 {@link CompletableFuture#join()} 的完成异常包装，取真实业务异常用于聚合与消息定位。
     *
     * @param failed join 抛出的异常（通常为 CompletionException）
     * @return 真实原因；无 cause 时原样返回
     */
    private static Throwable unwrapCompletionFailure(Exception failed) {
        Throwable cause = failed.getCause();
        return ObjectUtils.isNotEmpty(cause) ? cause : failed;
    }
    // ================================================================ 阶段 B：写回（调用方事务内）

    /**
     * 阶段 B：把重建计划落库（<b>纯 DB CRUD、零远程调用</b>；MUST 由调用方在其单事务内调用，
     * 本方法不开启、不接管事务——事务边界归组 6 删除链收敛服务）。
     *
     * <p>写回纪律（spec R4 / design D9）逐条目执行：加锁重读（实体按名、关系按归一端点对，
     * 图行先于向量行，与既有四步收敛的加锁序一致）→ <b>以锁定账本重算存活集合</b>（不采信
     * 阶段 A 快照）→ 存活为空转删除（图行与向量行成对物理删；条目已不存在且存活为空则保持
     * 不存在，MUST NOT 复活、MUST NOT 插入）→ 存活非空做绝对值写入（MUST NOT 增量）。</p>
     *
     * <p><b>与阶段 A 快照不一致时</b>（并发合并/并发删除已改变账本）：在内存中用计划携带的
     * 逐分块记录按锁定存活集合过滤重算绝对值（纯内存、零远程）；描述此时改走确定性聚合
     * （去重碎片换行拼接，与 {@link DescriptionSummarizer} 非 LLM 分支同口径）而非事务内远程
     * 摘要——按 design D9 的既定取舍接受「与账本可能有一轮短暂偏差，该条目下次合并/删除整体
     * 重建即收敛」。重算后的内容若与锁定向量行现值不一致，向量分量无法在事务内补算（远程
     * 禁令），按新内容落库并置空向量、记入报告收口清单，由阶段 C 收口。</p>
     *
     * <p><b>运行失败</b>（任何 SQL 异常）照实上抛、由调用方事务回滚（design D7：与语义降级严格
     * 分开）。空计划零 DB 交互。幂等重入：重复执行时账本已收缩、条目已消失，全部语句零影响。</p>
     *
     * @param ctx  重建上下文（提供 kbId/操作人/截断参数）
     * @param plan 阶段 A 产出的重建计划
     * @return 重建结果报告（重建/降级/删除/缺失计数 + 待收口清单）
     * @throws IllegalArgumentException 上下文或计划为 null
     */
    public GraphRebuildReport apply(GraphRebuildContext ctx, GraphRebuildPlan plan) {
        if (ObjectUtils.isEmpty(ctx)) {
            throw new IllegalArgumentException("图谱重建上下文不能为空");
        }
        if (ObjectUtils.isEmpty(plan)) {
            throw new IllegalArgumentException("图谱重建计划不能为空（无需求时请传空计划）");
        }
        if (plan.isEmpty()) {
            log.info("图谱重建写回跳过（空计划）：kbId=[{}]，本批被删分块不产生图谱贡献", ctx.kbId());
            return GraphRebuildReport.empty();
        }
        MutableReport counts = new MutableReport();
        Set<Long> deletedSet = new HashSet<>(plan.deletedChunkIds());
        applyEntities(ctx, plan, deletedSet, counts);
        applyRelations(ctx, plan, deletedSet, counts);
        log.info("图谱重建写回完成：kbId=[{}]，documentId=[{}]，重算写回条目数=[{}]，降级条目数=[{}]，"
                        + "物理删除条目数=[{}]，写回时已缺失条目数=[{}]，待向量收口数=[{}]",
                ctx.kbId(), ctx.deletedDocumentId(), counts.rebuilt.get(), counts.degraded.get(),
                counts.pruned.get(), counts.missing.get(),
                counts.pendingEntities.size() + counts.pendingRelations.size());
        return new GraphRebuildReport(counts.rebuilt.get(), counts.degraded.get(), counts.pruned.get(),
                counts.missing.get(), List.copyOf(counts.pendingEntities), List.copyOf(counts.pendingRelations));
    }

    /**
     * 实体侧写回：重建条目与直接删除条目并集按名升序逐条处理（固定加锁顺序，事务规范 3.1）。
     *
     * @param ctx        重建上下文
     * @param plan       重建计划
     * @param deletedSet 本批被删分块集合
     * @param counts     可变计数
     */
    private void applyEntities(GraphRebuildContext ctx, GraphRebuildPlan plan, Set<Long> deletedSet,
                               MutableReport counts) {
        Map<String, GraphRebuildPlan.EntityRebuildItem> itemByName = new LinkedHashMap<>();
        for (GraphRebuildPlan.EntityRebuildItem item : plan.entities()) {
            itemByName.put(item.entityName(), item);
        }
        List<String> names = new ArrayList<>(itemByName.keySet());
        names.addAll(plan.prunedEntityNames());
        if (names.isEmpty()) {
            return;
        }
        names = names.stream().distinct().sorted().toList();
        Map<String, EntityNode> lockedByName = new LinkedHashMap<>();
        // 加锁重读按既有 500 分批；清单已全局升序，逐片升序加锁即维持整批固定加锁顺序（事务规范 3.1）
        for (List<String> batch : BatchSplitter.split(names, LOCK_QUERY_BATCH_SIZE)) {
            entityNodeRepository.lockByKbIdAndNames(ctx.kbId(), batch)
                    .forEach(node -> lockedByName.putIfAbsent(node.entityName(), node));
        }
        for (String name : names) {
            EntityNode lockedNode = lockedByName.get(name);
            EntityInfoVector lockedVector = entityInfoVectorRepository
                    .lockByKbIdAndName(ctx.kbId(), name).orElse(null);
            List<Long> survivors = ObjectUtils.isEmpty(lockedVector)
                    ? List.of() : subtractSorted(lockedVector.chunkIds(), deletedSet);
            GraphRebuildPlan.EntityRebuildItem item = itemByName.get(name);
            if (survivors.isEmpty()) {
                pruneEntity(ctx, name, lockedNode, lockedVector, counts);
                continue;
            }
            writeEntity(ctx, name, item, survivors, lockedNode, lockedVector, counts);
        }
    }

    /**
     * 单实体写回分支（存活非空；纪律见 {@link #apply}）。
     * <p>{@code item} 为 null 表示该条目分类为「直接删除」但写回时被并发并入了新来源——
     * 无从重算语义（未重放），按降级纪律处理：保留锁定行现语义、仅修账本（来源路径无从修正，
     * 保持现值，偏差由该条目下次整体重建收敛）。</p>
     *
     * @param ctx          重建上下文
     * @param name         实体名
     * @param item         重建计划条目（可为 null，见上）
     * @param survivors    锁定账本推出的存活集合（升序非空）
     * @param lockedNode   加锁重读图行（可为 null）
     * @param lockedVector 加锁重读向量行（非 null——survivors 由其账本而来）
     * @param counts       可变计数
     */
    private void writeEntity(GraphRebuildContext ctx, String name, GraphRebuildPlan.EntityRebuildItem item,
                             List<Long> survivors, EntityNode lockedNode, EntityInfoVector lockedVector,
                             MutableReport counts) {
        GraphMergeParams params = ctx.params();
        EntityProperties current = ObjectUtils.isNotEmpty(lockedNode)
                ? lockedNode.properties() : (ObjectUtils.isNotEmpty(item)
                        ? item.currentProperties() : EntityProperties.empty());
        boolean degraded = ObjectUtils.isEmpty(item) || item.degraded();
        String description;
        String entityType;
        Map<String, Integer> votes;
        String content;
        float[] vector;
        boolean degradedCounted = degraded;
        if (!degraded && survivors.equals(item.snapshotSurvivors())) {
            // 常规路径：锁定账本与快照一致，直接采用阶段 A 候选（内容与向量成对且新鲜）
            description = item.finalDescription();
            entityType = item.finalEntityType();
            votes = item.finalVotes();
            content = item.finalContent();
            vector = item.finalVector();
        } else if (!degraded) {
            // 并发偏差路径：以锁定存活集合在内存过滤重算；描述走确定性聚合（事务内禁远程摘要，
            // 与账本可能有一轮短暂偏差，下次合并/删除整体重建即收敛——design D9 既定取舍）
            List<EntityNode> kept = filterEntityRecordsBySurvivors(item.survivorRecords(), survivors);
            if (kept.isEmpty()) {
                // 锁定存活全部无记录可用 → 按降级纪律保留现语义
                degradedCounted = true;
                description = current.description();
                entityType = current.entityType();
                votes = current.entityTypeVotes();
            } else {
                votes = countEntityVotes(kept);
                entityType = StringUtils.defaultIfBlank(EntityProperties.primaryEntityType(votes),
                        current.entityType());
                description = deterministicDescription(distinctDescriptions(kept.stream()
                        .map(node -> node.properties().description()).toList()), current.description());
            }
            description = new EntityNode(ctx.kbId(), name, new EntityProperties(entityType, description,
                    survivors, List.of(), votes, List.of())).effectiveDescription();
            content = tokenCounter.truncate(EntityInfoVector.buildContent(name, description),
                    params.embeddingTokenLimit());
            vector = reusableVector(content, lockedVector.content(), lockedVector.vector());
            if (ObjectUtils.isEmpty(vector)) {
                counts.pendingEntities.add(name);
            }
        } else {
            // 降级路径：保留原语义字段与现内容-向量对（描述未变 → 内容未变），仅修账本与来源路径
            description = current.description();
            entityType = current.entityType();
            votes = current.entityTypeVotes();
            content = lockedVector.content();
            vector = lockedVector.vector();
        }
        List<String> filePaths = filePathsFor(ObjectUtils.isNotEmpty(item) ? item.survivorFilePaths() : Map.of(),
                survivors, current.filePaths(), params);
        EntityNode node = new EntityNode(ctx.kbId(), name, new EntityProperties(entityType, description,
                truncateDisplaySourceIds(survivors, params, ctx.kbId(), "实体[" + name + "]"),
                filePaths, votes,
                StringUtils.isNotBlank(description) ? List.of(description) : List.of()));
        entityNodeRepository.upsert(node, ctx.operator());
        entityInfoVectorRepository.upsert(EntityInfoVector.create(ctx.kbId(), name, content, survivors, vector),
                ctx.operator());
        if (degradedCounted) {
            counts.degraded.incrementAndGet();
        } else {
            counts.rebuilt.incrementAndGet();
        }
    }

    /**
     * 单实体删除分支（锁定存活为空：分类直接删除、或写回时存活转空的转删除；不复活纪律的观测点）。
     *
     * @param ctx          重建上下文
     * @param name         实体名
     * @param lockedNode   加锁重读图行（可为 null）
     * @param lockedVector 加锁重读向量行（可为 null）
     * @param counts       可变计数
     */
    private void pruneEntity(GraphRebuildContext ctx, String name, EntityNode lockedNode,
                             EntityInfoVector lockedVector, MutableReport counts) {
        if (ObjectUtils.isEmpty(lockedNode) && ObjectUtils.isEmpty(lockedVector)) {
            // 条目已不存在且存活为空 → 保持不存在（MUST NOT 复活、MUST NOT 插入）
            counts.missing.incrementAndGet();
            return;
        }
        // 固定删除序：图行先、向量行后（与既有四步收敛①②同序；账本缺失时图行亦不得独存）
        if (ObjectUtils.isNotEmpty(lockedNode)) {
            entityNodeRepository.deleteByKbIdAndName(ctx.kbId(), name);
        }
        if (ObjectUtils.isNotEmpty(lockedVector)) {
            entityInfoVectorRepository.deleteByKbIdAndName(ctx.kbId(), name);
        }
        counts.pruned.incrementAndGet();
    }
    /**
     * 关系侧写回（端点对按（源、目标）升序逐条处理；纪律与实体侧镜像）。
     *
     * @param ctx        重建上下文
     * @param plan       重建计划
     * @param deletedSet 本批被删分块集合
     * @param counts     可变计数
     */
    private void applyRelations(GraphRebuildContext ctx, GraphRebuildPlan plan, Set<Long> deletedSet,
                                MutableReport counts) {
        Map<String, GraphRebuildPlan.RelationRebuildItem> itemByPair = new LinkedHashMap<>();
        List<List<String>> pairs = new ArrayList<>();
        for (GraphRebuildPlan.RelationRebuildItem item : plan.relations()) {
            pairs.add(item.normalizedPair());
            itemByPair.put(item.sourceName() + PAIR_KEY_SEPARATOR + item.targetName(), item);
        }
        pairs.addAll(plan.prunedRelationPairs());
        if (pairs.isEmpty()) {
            return;
        }
        for (List<String> pair : sortPairs(pairs)) {
            String source = pair.get(0);
            String target = pair.get(1);
            List<RelationEdge> lockedEdges = relationEdgeRepository
                    .lockByKbIdAndUnorderedPair(ctx.kbId(), source, target);
            RelationEdge keepRow = pickNormalizedDirectionRow(lockedEdges, source, target);
            RelationInfoVector lockedVector = relationInfoVectorRepository
                    .lockByKbIdAndUnorderedPair(ctx.kbId(), source, target).orElse(null);
            List<Long> survivors = ObjectUtils.isEmpty(lockedVector)
                    ? List.of() : subtractSorted(lockedVector.chunkIds(), deletedSet);
            GraphRebuildPlan.RelationRebuildItem item =
                    itemByPair.get(source + PAIR_KEY_SEPARATOR + target);
            if (survivors.isEmpty()) {
                pruneRelation(ctx, source, target, keepRow, lockedVector, counts);
                continue;
            }
            writeRelation(ctx, source, target, item, survivors, keepRow, lockedVector, counts);
        }
    }

    /**
     * 单关系写回分支（存活非空；权重=存活记录全额求和的绝对值写入即「权重回扣」的落库点，
     * design D6 最语义敏感项）。
     *
     * @param ctx          重建上下文
     * @param source       归一端点对之源（来自有序清单，与 {@code item} 是否缺失无关）
     * @param target       归一端点对之目标
     * @param item         重建计划条目（null=直接删除被并发并入新来源，按降级保留处理）
     * @param survivors    锁定存活集合（升序非空）
     * @param keepRow      加锁重读的保留方向图边行（可为 null）
     * @param lockedVector 加锁重读向量行（非 null）
     * @param counts       可变计数
     */
    private void writeRelation(GraphRebuildContext ctx, String source, String target,
                               GraphRebuildPlan.RelationRebuildItem item,
                               List<Long> survivors, RelationEdge keepRow, RelationInfoVector lockedVector,
                               MutableReport counts) {
        GraphMergeParams params = ctx.params();
        RelationProperties current = ObjectUtils.isNotEmpty(keepRow)
                ? keepRow.properties() : (ObjectUtils.isNotEmpty(item)
                        ? item.currentProperties() : RelationProperties.empty());
        boolean degraded = ObjectUtils.isEmpty(item) || item.degraded();
        double weight;
        String description;
        List<String> keywords;
        String content;
        float[] vector;
        boolean degradedCounted = degraded;
        if (!degraded && survivors.equals(item.snapshotSurvivors())) {
            weight = item.finalWeight();
            description = item.finalDescription();
            keywords = item.finalKeywords();
            content = item.finalContent();
            vector = item.finalVector();
        } else if (!degraded) {
            List<RelationEdge> kept = filterRelationRecordsBySurvivors(item.survivorRecords(), survivors);
            if (kept.isEmpty()) {
                degradedCounted = true;
                weight = current.weight();
                description = current.description();
                keywords = current.keywords();
            } else {
                // 并发偏差路径的全额求和仍是纯内存计算：锁定存活记录的权重之和（绝对值，零远程）
                weight = kept.stream().mapToDouble(edge -> edge.properties().weight()).sum();
                keywords = mergeKeywords(kept);
                description = deterministicDescription(distinctDescriptions(kept.stream()
                        .map(edge -> edge.properties().description()).toList()), current.description());
            }
            content = tokenCounter.truncate(RelationInfoVector.buildContent(keywords, source,
                    target, description), params.embeddingTokenLimit());
            vector = reusableVector(content, lockedVector.content(), lockedVector.vector());
            if (ObjectUtils.isEmpty(vector)) {
                counts.pendingRelations.add(List.of(source, target));
            }
        } else {
            weight = current.weight();
            description = current.description();
            keywords = current.keywords();
            content = lockedVector.content();
            vector = lockedVector.vector();
        }
        List<String> filePaths = filePathsFor(ObjectUtils.isNotEmpty(item) ? item.survivorFilePaths() : Map.of(),
                survivors, current.filePaths(), params);
        RelationEdge edge = new RelationEdge(ctx.kbId(), source, target,
                new RelationProperties(weight, description, keywords,
                        truncateDisplaySourceIds(survivors, params, ctx.kbId(),
                                "关系[" + source + "#" + target + "]"),
                        filePaths));
        relationEdgeRepository.upsertAll(List.of(edge), ctx.operator());
        relationInfoVectorRepository.upsertAll(List.of(RelationInfoVector.create(ctx.kbId(), source,
                target, content, survivors, vector)), ctx.operator());
        if (degradedCounted) {
            counts.degraded.incrementAndGet();
        } else {
            counts.rebuilt.incrementAndGet();
        }
    }

    /**
     * 单关系删除分支（锁定存活为空；双向行一次清尽、图边先删向量后删）。
     *
     * @param ctx          重建上下文
     * @param source       归一端点对之源
     * @param target       归一端点对之目标
     * @param keepRow      加锁重读图边行（可为 null）
     * @param lockedVector 加锁重读向量行（可为 null）
     * @param counts       可变计数
     */
    private void pruneRelation(GraphRebuildContext ctx, String source, String target, RelationEdge keepRow,
                               RelationInfoVector lockedVector, MutableReport counts) {
        if (ObjectUtils.isEmpty(keepRow) && ObjectUtils.isEmpty(lockedVector)) {
            counts.missing.incrementAndGet();
            return;
        }
        if (ObjectUtils.isNotEmpty(keepRow)) {
            relationEdgeRepository.deleteByKbIdAndUnorderedPair(ctx.kbId(), source, target);
        }
        if (ObjectUtils.isNotEmpty(lockedVector)) {
            relationInfoVectorRepository.deleteByKbIdAndUnorderedPair(ctx.kbId(), source, target);
        }
        counts.pruned.incrementAndGet();
    }

    // ================================================================ 阶段 C：提交后向量收口

    /**
     * 阶段 C：对报告中的「待向量收口」条目复用 {@link VectorContentReconciler} 既有收口原语
     * （MUST 在写回事务<b>提交后、事务外</b>调用——收口内部含向量化远程调用，且其
     * 「读已提交描述 → 比对 → 窄更新」的语义只有在提交后才有意义）。
     *
     * <p>触发场景：阶段 B 加锁重读发现存活集合与阶段 A 快照不一致（并发合并/并发删除），
     * 描述改走确定性聚合重算，事务内无法补算对应向量（远程禁令），故按新内容落库并置空向量、
     * 记入报告清单；本方法把这些条目的向量行修复为「内容与向量都是已提交最终描述的函数」，
     * 达成 spec R4「向量内容与最终描述一致」。收口原语自身吞异常降级（仅 WARN），MUST NOT
     * 影响已提交的删除链。清单为空时零操作。</p>
     *
     * @param ctx    重建上下文（提供 embedding 模型引用与 token 上限）
     * @param report {@link #apply} 产出的报告
     */
    public void reconcilePendingVectorContent(GraphRebuildContext ctx, GraphRebuildReport report) {
        if (ObjectUtils.isEmpty(ctx) || ObjectUtils.isEmpty(report)) {
            return;
        }
        if (report.pendingVectorSyncEntityNames().isEmpty()
                && report.pendingVectorSyncRelationPairs().isEmpty()) {
            return;
        }
        int embeddingTokenLimit = ctx.params().embeddingTokenLimit();
        for (String entityName : report.pendingVectorSyncEntityNames()) {
            vectorContentReconciler.reconcileEntity(ctx.kbId(), entityName, ctx.embeddingModelProfileId(),
                    embeddingTokenLimit);
        }
        for (List<String> pair : report.pendingVectorSyncRelationPairs()) {
            vectorContentReconciler.reconcileRelation(ctx.kbId(), pair.get(0), pair.get(1),
                    ctx.embeddingModelProfileId(), embeddingTokenLimit);
        }
        log.info("图谱重建向量收口完成：kbId=[{}]，实体收口数=[{}]，关系收口数=[{}]", ctx.kbId(),
                report.pendingVectorSyncEntityNames().size(), report.pendingVectorSyncRelationPairs().size());
    }
    // ================================================================ 阶段 A 远程计算小工具

    /**
     * 阶段 A 实体向量获取：期望内容与快照内容一致则复用快照向量（省一次远程），否则补算
     * （向量化失败属运行失败，照实上抛）。
     *
     * @param ctx      重建上下文
     * @param snapshot 账本重叠快照
     * @param content  期望内容
     * @return 向量分量数组
     */
    private float[] reuseOrEmbedEntity(GraphRebuildContext ctx, EntityLedgerSnapshot snapshot, String content) {
        if (ObjectUtils.isNotEmpty(snapshot.vector()) && StringUtils.equals(snapshot.content(), content)) {
            return snapshot.vector();
        }
        return embeddingClient.embed(ctx.embeddingModelProfileId(), content);
    }

    /**
     * 阶段 A 关系向量获取（同实体侧口径）。
     *
     * @param ctx      重建上下文
     * @param snapshot 账本重叠快照
     * @param content  期望内容
     * @return 向量分量数组
     */
    private float[] reuseOrEmbedRelation(GraphRebuildContext ctx, RelationLedgerSnapshot snapshot,
                                         String content) {
        if (ObjectUtils.isNotEmpty(snapshot.vector()) && StringUtils.equals(snapshot.content(), content)) {
            return snapshot.vector();
        }
        return embeddingClient.embed(ctx.embeddingModelProfileId(), content);
    }

    /**
     * 阶段 B 内容-向量成对裁决：重算内容与锁定向量行现值一致 → 复用锁定向量（零远程）；
     * 不一致 → 返回 {@code null}（向量置空落库、转入报告收口清单由阶段 C 补算——事务内禁远程）。
     *
     * @param expectedContent 写回期望内容
     * @param currentContent  锁定向量行现内容
     * @param currentVector   锁定向量行现向量
     * @return 可复用向量；不可复用返回 {@code null}
     */
    private static float[] reusableVector(String expectedContent, String currentContent, float[] currentVector) {
        return StringUtils.equals(currentContent, expectedContent) ? currentVector : null;
    }

    // ================================================================ 收集与重算共用小工具

    /**
     * 存活来源路径映射（已知部分：分块经只读通道可得且有来源文件名）。
     *
     * @param survivors 存活分块升序列表
     * @param replay    重放数据束
     * @return chunkId → 来源文件路径（按存活升序）
     */
    private static Map<Long, String> survivorPaths(List<Long> survivors, ReplayBundle replay) {
        Map<Long, String> paths = new LinkedHashMap<>();
        for (Long chunkId : survivors) {
            String path = replay.pathByChunk().get(chunkId);
            if (StringUtils.isNotBlank(path)) {
                paths.put(chunkId, path);
            }
        }
        return paths;
    }

    /**
     * 描述碎片去重（保插入序、剔除空白项——与 {@code GraphMergeService#addIfPresent} 同口径）。
     *
     * @param descriptions 原始碎片序列
     * @return 去重后的碎片列表
     */
    private static List<String> distinctDescriptions(List<String> descriptions) {
        Set<String> distinct = new LinkedHashSet<>();
        for (String description : descriptions) {
            if (StringUtils.isNotBlank(description) && !distinct.contains(description)) {
                distinct.add(description);
            }
        }
        return List.copyOf(distinct);
    }

    /**
     * 实体类型票聚合（tasks 4.3）：每条存活记录对其类型投一票（LinkedHashMap 保持首现序），
     * 众数口径复用 {@link EntityProperties#primaryEntityType}——频次降序、同频后现者优先。
     *
     * @param records 存活实体记录
     * @return 类型 → 票数
     */
    private static Map<String, Integer> countEntityVotes(List<EntityNode> records) {
        Map<String, Integer> votes = new LinkedHashMap<>();
        for (EntityNode record : records) {
            String type = record.properties().entityType();
            if (StringUtils.isNotBlank(type)) {
                votes.merge(type, 1, Integer::sum);
            }
        }
        return votes;
    }

    /**
     * 关系关键词归并（tasks 4.3）：各存活记录关键词拆分去重后按字典序排列
     * （spec R2「合作, 雇佣, 任职」口径；TreeSet 天然去重+字典序）。
     *
     * @param records 存活关系记录
     * @return 去重字典序关键词列表
     */
    private static List<String> mergeKeywords(List<RelationEdge> records) {
        Set<String> keywords = new TreeSet<>();
        for (RelationEdge record : records) {
            List<String> rawKeywords = ObjectUtils.isEmpty(record.properties().keywords())
                    ? List.of() : record.properties().keywords();
            for (String keyword : rawKeywords) {
                if (StringUtils.isNotBlank(keyword)) {
                    keywords.add(keyword.trim());
                }
            }
        }
        return List.copyOf(keywords);
    }

    /**
     * 确定性描述聚合（阶段 B 并发偏差专用、非 LLM）：去重碎片换行拼接；碎片为空回落保留原描述。
     *
     * @param fragments 去重后的存活描述碎片
     * @param fallback  原描述（可为 null）
     * @return 聚合描述或回落值
     */
    private static String deterministicDescription(List<String> fragments, String fallback) {
        if (CollectionUtils.isEmpty(fragments)) {
            return fallback;
        }
        return String.join(DESCRIPTION_FRAGMENT_JOINER, fragments);
    }

    /**
     * 阶段 B 实体记录过滤：仅保留来源仍在锁定存活集合中的记录（按存活升序，每分块至多一条）。
     *
     * @param records   计划携带的逐分块实体记录
     * @param survivors 锁定存活集合（升序）
     * @return 过滤后的记录列表
     */
    private static List<EntityNode> filterEntityRecordsBySurvivors(List<EntityNode> records, List<Long> survivors) {
        List<EntityNode> kept = new ArrayList<>();
        for (Long chunkId : survivors) {
            for (EntityNode record : records) {
                if (Objects.equals(chunkId, recordChunkId(record.properties().sourceIds()))) {
                    kept.add(record);
                    break;
                }
            }
        }
        return kept;
    }

    /**
     * 阶段 B 关系记录过滤（同实体侧口径）。
     *
     * @param records   计划携带的逐分块关系边
     * @param survivors 锁定存活集合（升序）
     * @return 过滤后的边列表（按存活升序）
     */
    private static List<RelationEdge> filterRelationRecordsBySurvivors(List<RelationEdge> records,
                                                                       List<Long> survivors) {
        List<RelationEdge> kept = new ArrayList<>();
        for (Long chunkId : survivors) {
            for (RelationEdge record : records) {
                if (Objects.equals(chunkId, recordChunkId(record.properties().sourceIds()))) {
                    kept.add(record);
                    break;
                }
            }
        }
        return kept;
    }

    /**
     * 记录来源列表取单元素来源（重放出口恒为「一记录一分块」）；非单元素返回 null 使该记录
     * 不参与按分块过滤（防御旁路形态，fail-open 交由降级路径兜底）。
     *
     * @param sourceIds 记录来源列表
     * @return 单元素来源；否则 {@code null}
     */
    private static Long recordChunkId(List<Long> sourceIds) {
        if (ObjectUtils.isEmpty(sourceIds) || sourceIds.size() != 1) {
            return null;
        }
        return sourceIds.get(0);
    }
    /**
     * 写回来源文件路径：按锁定存活集合升序取「已知路径」，与合并路径共用
     * {@link GraphSourceFilePaths#normalize} 统一截断口径（D8「重建路径 MUST 用这个」）。
     * <p><b>取不到任何路径时保持原有值、MUST NOT 清空</b>（spec graph-source-file-paths R3
     * 场景「重建路径无可用路径时保持既有值」）：无论已知映射为空（降级无从重取、或直接删除被
     * 并发复活的计划外条目），还是映射非空但与锁定存活集合无交集（并发删除已把原存活分块剔出
     * 账本），一律原样保留现值——路径列表是展示线索，宁缺勿清空。</p>
     * <p>存活集合与快照不一致时，并发并入分块的路径不在已知映射内——按可得集产出并接受一轮偏差
     * （D9 同款取舍，条目下次整体重建收敛）。</p>
     *
     * @param knownPaths   计划携带的「chunkId → 来源文件路径」已知映射（可为空映射）
     * @param survivors    锁定存活集合（升序）
     * @param currentPaths 现值路径列表（无可用路径时的保持值）
     * @param params       合并参数（路径上限与占位词）
     * @return 归一后的路径列表，或无可用路径时的现值列表
     */
    private static List<String> filePathsFor(Map<Long, String> knownPaths, List<Long> survivors,
                                             List<String> currentPaths, GraphMergeParams params) {
        List<String> ordered = new ArrayList<>(survivors.size());
        for (Long chunkId : survivors) {
            String path = knownPaths.get(chunkId);
            if (StringUtils.isNotBlank(path)) {
                ordered.add(path);
            }
        }
        if (CollectionUtils.isEmpty(ordered)) {
            return currentPaths;
        }
        return GraphSourceFilePaths.normalize(ordered, params.sourceFilePathsLimit(),
                params.sourceFilePathsPlaceholder());
    }

    /**
     * 账本剔除被删分块：去 null、剔除被删集、去重、升序（写回绝对值与存活判定的统一归一）。
     *
     * @param ledger     账本原列表（快照或锁定读值）
     * @param deletedSet 被删分块集合
     * @return 存活分块升序列表
     */
    private static List<Long> subtractSorted(List<Long> ledger, Set<Long> deletedSet) {
        if (CollectionUtils.isEmpty(ledger)) {
            return List.of();
        }
        return ledger.stream()
                .filter(Objects::nonNull)
                .filter(chunkId -> !deletedSet.contains(chunkId))
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * 入参分块集合归一（去 null、去重、升序——计划中的被删集与处理序恒定，保证幂等重入）。
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

    /**
     * 端点对清单排序（先源后目标字典序；直接删除清单与写回处理序共用，保证固定加锁顺序）。
     *
     * @param pairs 端点对列表
     * @return 排序后的新列表
     */
    private static List<List<String>> sortPairs(List<List<String>> pairs) {
        return pairs.stream()
                .sorted(Comparator.comparing((List<String> pair) -> pair.get(0)).thenComparing(pair -> pair.get(1)))
                .toList();
    }

    /**
     * 从加锁读的双向边行集合中选归一方向行（属性读取基准）；干净环境恒命中归一方向单行，
     * 脏环境优先归一方向、回落取首行（与 {@code VectorContentReconciler} 同口径）。
     *
     * @param rows   加锁重读图边行集合
     * @param source 归一端点对之源
     * @param target 归一端点对之目标
     * @return 保留行；集合为空返回 {@code null}
     */
    private static RelationEdge pickNormalizedDirectionRow(List<RelationEdge> rows, String source, String target) {
        if (CollectionUtils.isEmpty(rows)) {
            return null;
        }
        return rows.stream()
                .filter(row -> StringUtils.equals(row.sourceName(), source)
                        && StringUtils.equals(row.targetName(), target))
                .findFirst()
                .orElseGet(() -> rows.get(0));
    }

    /**
     * source_ids 展示列截断（与 {@code GraphMergeService#truncateSourceIds} 同口径：
     * KEEP 留前 N / FIFO 留后 N，截断仅作用于图行展示列，向量账本恒全量）。
     *
     * @param sourceIds   存活来源列表（升序全量）
     * @param params      合并参数（上限与策略）
     * @param kbId        知识库ID（日志定位）
     * @param objectLabel 对象标识（日志定位）
     * @return 截断后的不可变列表
     */
    private static List<Long> truncateDisplaySourceIds(List<Long> sourceIds, GraphMergeParams params,
                                                       Long kbId, String objectLabel) {
        if (CollectionUtils.isEmpty(sourceIds)) {
            return List.of();
        }
        int limit = params.applySourceIdsLimit();
        if (sourceIds.size() <= limit) {
            return List.copyOf(sourceIds);
        }
        List<Long> kept;
        if (GraphMergeParams.SOURCE_IDS_TRUNCATION_FIFO.equals(params.sourceIdsTruncation())) {
            kept = List.copyOf(sourceIds.subList(sourceIds.size() - limit, sourceIds.size()));
        } else {
            kept = List.copyOf(sourceIds.subList(0, limit));
        }
        log.info("重建写回source_ids截断：kbId=[{}]，对象=[{}]，原条数=[{}]，保留=[{}]，丢弃=[{}]",
                kbId, objectLabel, sourceIds.size(), kept.size(), sourceIds.size() - kept.size());
        return kept;
    }

    /**
     * 统计计划中的降级条目数（INFO 日志留痕口径）。
     *
     * @param entityItems   实体计划条目
     * @param relationItems 关系计划条目
     * @return 降级条目数
     */
    private static int countDegraded(List<GraphRebuildPlan.EntityRebuildItem> entityItems,
                                     List<GraphRebuildPlan.RelationRebuildItem> relationItems) {
        return (int) entityItems.stream().filter(GraphRebuildPlan.EntityRebuildItem::degraded).count()
                + (int) relationItems.stream().filter(GraphRebuildPlan.RelationRebuildItem::degraded).count();
    }

    /**
     * 分块行 → 内容块（重放输入所需最小形态）：类型取 {@code chunk_content_type} 列
     * （与 {@link ContentBlockVO} 类型常量同名），meta 仅解析 {@code original_item} 中
     * 重建路径消费的键；解析失败按空 meta 承载（多模态块缺主实体名时重放侧 WARN 跳过
     * sidecar，与本服务「取不到则传 null」的约定一致），MUST NOT 因脏 meta 中断删除链。
     *
     * @param chunk 存活分块行
     * @return 内容块（非 null）
     */
    private ContentBlockVO toBlock(Chunk chunk) {
        String type = ObjectUtils.isNotEmpty(chunk.chunkContentType())
                ? chunk.chunkContentType().name() : ContentBlockVO.TYPE_TEXT;
        return new ContentBlockVO(type, chunk.chunkContent(), parseMeta(chunk));
    }

    /**
     * 解析 {@code original_item} 为 meta 映射（仅对象形态采纳，脏数据宽容归空）。
     *
     * @param chunk 存活分块行
     * @return meta 映射（可为空映射）
     */
    private Map<String, Object> parseMeta(Chunk chunk) {
        if (StringUtils.isBlank(chunk.originalItem())) {
            return Map.of();
        }
        try {
            JsonNode root = objectMapper.readTree(chunk.originalItem());
            if (!root.isObject()) {
                return Map.of();
            }
            return objectMapper.convertValue(root, META_MAP_TYPE);
        } catch (JacksonException e) {
            log.debug("分块 original_item 解析失败，按空 meta 处理：chunkId=[{}]", chunk.id(), e);
            return Map.of();
        }
    }

    /**
     * 从块 meta 读取多模态定形主实体名（分流预置键；取不到返回 null，重放侧对多模态块
     * WARN 跳过 sidecar 注入——组 3 契约约定口径）。
     *
     * @param block 内容块
     * @return 定形主实体名或 {@code null}
     */
    private static String mediaPrimaryEntityName(ContentBlockVO block) {
        return StringUtils.trimToNull(MultimodalMetaKeys.metaString(block,
                MultimodalMetaKeys.META_KEY_ENTITY_NAME));
    }

    /**
     * 重放数据束（阶段 A 内部传递：分块记录集 + 可用分块集 + 分块来源路径表）。
     *
     * @param replayByChunk 分块主键 → 该分块抽取记录集合
     * @param usableChunks  有可用重放数据的分块集合（记录集非空）
     * @param pathByChunk   分块主键 → 来源文件路径（仅可得分块）
     */
    private record ReplayBundle(Map<Long, EntityExtractionResult> replayByChunk, Set<Long> usableChunks,
                                Map<Long, String> pathByChunk) {

        /**
         * 紧凑构造器：空值归一。
         */
        ReplayBundle {
            replayByChunk = ObjectUtils.isEmpty(replayByChunk) ? Map.of() : replayByChunk;
            usableChunks = ObjectUtils.isEmpty(usableChunks) ? Set.of() : usableChunks;
            pathByChunk = ObjectUtils.isEmpty(pathByChunk) ? Map.of() : pathByChunk;
        }
    }

    /**
     * 阶段 B 可变计数（写回为单事务顺序处理，普通容器即可；快照进报告后不可变）。
     */
    private static final class MutableReport {

        /** 绝对值重算写回条目数 */
        private final AtomicInteger rebuilt = new AtomicInteger();

        /** 降级写回条目数 */
        private final AtomicInteger degraded = new AtomicInteger();

        /** 物理删除条目数（直接删除 + 写回时存活转空的转删除） */
        private final AtomicInteger pruned = new AtomicInteger();

        /** 写回时条目已缺失数（保持不存在、零写入） */
        private final AtomicInteger missing = new AtomicInteger();

        /** 待阶段 C 收口的实体名清单 */
        private final List<String> pendingEntities = new ArrayList<>();

        /** 待阶段 C 收口的关系端点对清单 */
        private final List<List<String>> pendingRelations = new ArrayList<>();
    }
}
