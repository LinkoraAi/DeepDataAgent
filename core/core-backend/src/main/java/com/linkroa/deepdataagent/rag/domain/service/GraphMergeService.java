package com.linkroa.deepdataagent.rag.domain.service;

import com.linkroa.deepdataagent.rag.domain.model.EntityInfoVector;
import com.linkroa.deepdataagent.rag.domain.model.EntityNode;
import com.linkroa.deepdataagent.rag.domain.model.EntityProperties;
import com.linkroa.deepdataagent.rag.domain.model.GraphSourceFilePaths;
import com.linkroa.deepdataagent.rag.domain.model.RelationContribution;
import com.linkroa.deepdataagent.rag.domain.model.RelationEdge;
import com.linkroa.deepdataagent.rag.domain.model.RelationInfoVector;
import com.linkroa.deepdataagent.rag.domain.model.RelationProperties;
import com.linkroa.deepdataagent.rag.domain.port.EmbeddingClient;
import com.linkroa.deepdataagent.rag.domain.repository.EntityInfoVectorRepository;
import com.linkroa.deepdataagent.rag.domain.repository.EntityNodeGraphRepository;
import com.linkroa.deepdataagent.rag.domain.repository.RelationEdgeGraphRepository;
import com.linkroa.deepdataagent.rag.domain.repository.RelationInfoVectorRepository;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * 图合并领域服务（实体/关系去重合并主流程）。
 *
 * <p><b>流程</b>：批次内聚合（同名实体 / 同无向端点对关系先内存归一，自环边丢弃计数）
 * → Phase 1 实体合并（阶段内并行，Semaphore 闸门限流）→ Phase 2 关系合并。
 * 任一阶段存在失败即上抛首个异常（其余 addSuppressed），由上层任务将摄入置败（取消与失败语义）。
 * 条目级事务不回滚；残留由重解析的旧代收敛与文档删除链的收敛承担。</p>
 *
 * <p><b>并发与锁</b>：跨实例并发的正确性由<b>数据库行锁</b>承担——每个条目形如
 * 「事务外普通读（仅为构造 LLM 上下文与复用旧向量，MUST NOT 作为写回基准）→ 事务外远程调用 →
 * 写回事务（{@code FOR UPDATE} 重读当前账本 → 以当前值重算 → upsert 写回）」，
 * 加锁读与写回处于<b>同一事务</b>，行锁因此覆盖整个「读当前值 → 重算 → 写回」窗口；
 * 账本行首次插入的并发竞争由数据库唯一约束拦截，捕获冲突后重试写回事务（含次数上界）。
 * LLM 摘要与 embedding 等远程调用严禁进入事务（事务规范 1.1），故写回事务体内仅含数据库 CRUD。</p>
 *
 * <p>进程内另采用 {@value #LOCK_STRIPE_COUNT} 个固定条纹锁，作为<b>性能优化</b>而非正确性依赖：
 * 锁键为 {@code entity:kbId:name} 与 {@code edge:kbId:a#b}（a≤b）。实体任务与关系任务各持
 * 1 把自身条目锁（关系合并不再触碰实体行，不存在跨条目持锁，天然无锁序问题）。
 * 其作用是降低同实例内的行锁等待与远程调用重复；即便跨实例时完全失效（或哈希碰撞把不同实体
 * 映射到同一锁而降低并发度），账本也 MUST NOT 丢失。</p>
 *
 * <p><b>幂等与账本口径</b>：向量表 chunk_ids 是<b>唯一权威来源账本</b>——幂等短路与权重累加的
 * 过滤基准取自<b>同一份</b>账本（同一写回事务内加锁读到的向量行 chunk_ids），
 * 「账本管幂等、权重管历史强度」：同一来源已记账则既不重复累加权重也可短路，重放不产生写放大；
 * 该判定 MUST NOT 用事务外读到的快照（过期快照可能包含已被收敛删除的来源而误判「已覆盖」，
 * 从而漏并本批贡献）。账本缺失 / 为空 / 入参为空 / 记录含 null 来源时 fail-open
 * （宁可多算一次写入不漏算来源）。图行 source_ids 降级为<b>纯展示列</b>：写入时按
 * {@code apply_source_ids_limit} × {@code source_ids_truncation}（KEEP 留前 N / FIFO 留后 N）
 * 截断，截断只作用于展示列，向量账本恒取截断前的全量去重并集；
 * 实体描述经 {@link DescriptionSummarizer} 收敛为单条后写回，防止 JSONB 膨胀与重复摘要。</p>
 *
 * <p><b>权重口径</b>（与 LightRAG 合并端同构）：批内先聚合为「边骨架 + 贡献记录列表」
 * （{@link BatchEdge}），合并端恒按
 * {@code weight = 已存 weight + Σ{记录全额 | 记录来源 ∉ 向量账本或来源为 null}} 累加
 * （见 {@link RelationEdge#mergeContributions}）；公式形式不变，但「已存 weight 与账本快照」
 * 取<b>写回事务内加锁读</b>的当前值，MUST NOT 取事务外快照。同 (端点对, 来源) 记录已在抽取
 * 汇聚器归一为一条（取最大权重），上游「同来源重复计权」的批内不去重形态在本管线不可达
 * （统一口径见 {@link #aggregateEdges}、{@link RelationEdge} 与抽取侧 {@code ExtractionHolder}
 * 类注释，三处互相引用）。</p>
 *
 * <p><b>方向与端点身份</b>：抽取聚合出口已把关系边端点归一为字典序，所有写入命中同一唯一键
 * {@code (kb_id, source_name, target_name)}，反向残留行无从产生，本服务不再做残留折叠与
 * 反向行物理删除。抽取边引用的未知端点<b>不补建占位节点</b>：本项目关系表零外键、端点为
 * 弱引用，悬挂引用在存储层完全合法；检索侧已按「端点可以不存在」运行并自带裸名降级
 * （本项目刻意偏离上游 LightRAG——上游属性图存储要求两端先存在顶点才允许建边，占位是其
 * 物理约束的产物，此处无该约束、只剩代价）。该名称后续被真实抽取到时按常规实体合并
 * 正常成节点。</p>
 *
 * <p><b>账本与图行的时序前提</b>（MUST NOT 无依据改回）：上游 LightRAG 要求权重过滤基准取
 * 「边自身的 source_id」而非 chunk 跟踪表，理由是 chunk 跟踪表可能「跑在图边前面」。本项目
 * 不适用该顾虑——关系图行与其向量行、实体图行与其向量行均在<b>同一个写回事务</b>内 upsert，
 * 不存在先后偏差，故基准统一取向量账本。若未来拆分两者的写入事务，本口径即失效。</p>
 *
 * @author DeepDataAgent
 */
@Service
public class GraphMergeService {

    /** 日志器 */
    private static final Logger log = LoggerFactory.getLogger(GraphMergeService.class);

    /** 进程内条纹锁数量（固定容量，避免动态锁表膨胀；取 2 的幂使哈希分布均匀） */
    private static final int LOCK_STRIPE_COUNT = 256;

    /** 写回事务的唯一约束冲突重试上界（账本行首次插入的并发竞争；超出即按批次失败处理）。 */
    private static final int WRITE_TRANSACTION_MAX_ATTEMPTS = 3;

    /** PostgreSQL 唯一约束冲突的 SQLState（与 Spring 的 {@code DuplicateKeyException} 互补识别）。 */
    private static final String UNIQUE_VIOLATION_SQL_STATE = "23505";

    /** 实体/关系端点名入库最大长度（对齐 V11 entity_node_graph.entity_name VARCHAR(512)） */
    private static final int MAX_GRAPH_NAME_LENGTH = 512;

    /** 名称截断 WARN 日志中展示的原名前缀长度 */
    private static final int TRUNCATED_NAME_WARN_PREFIX_LENGTH = 64;

    /** 截断日志对象标签——实体前缀 */
    private static final String OBJECT_LABEL_ENTITY = "实体[";

    /** 截断日志对象标签——关系前缀 */
    private static final String OBJECT_LABEL_RELATION = "关系[";

    /** 截断日志对象标签——闭合后缀 */
    private static final String OBJECT_LABEL_SUFFIX = "]";

    /** 名称截断日志对象类别——实体名 */
    private static final String NAME_KIND_ENTITY = "实体名";

    /** 名称截断日志对象类别——关系源端点名 */
    private static final String NAME_KIND_EDGE_SOURCE = "关系源端点名";

    /** 名称截断日志对象类别——关系目标端点名 */
    private static final String NAME_KIND_EDGE_TARGET = "关系目标端点名";

    /** 实体锁键前缀 */
    private static final String ENTITY_LOCK_PREFIX = "entity:";

    /** 边锁键前缀 */
    private static final String EDGE_LOCK_PREFIX = "edge:";

    /** 边锁键端点分隔符 */
    private static final String PAIR_SEPARATOR = "#";

    /** 边聚合键端点分隔符 */
    private static final String EDGE_KEY_SEPARATOR = "#";

    /** 关系摘要名称连接符（仅用于 Prompt 展示） */
    private static final String RELATION_NAME_CONNECTOR = "~";

    /** 图谱实体节点仓储 */
    private final EntityNodeGraphRepository entityNodeRepository;

    /** 图谱关系边仓储 */
    private final RelationEdgeGraphRepository relationEdgeRepository;

    /** 实体向量仓储 */
    private final EntityInfoVectorRepository entityInfoVectorRepository;

    /** 关系向量仓储 */
    private final RelationInfoVectorRepository relationInfoVectorRepository;

    /** 向量化端口（事务外调用） */
    private final EmbeddingClient embeddingClient;

    /** 描述摘要器（事务外调用 LLM） */
    private final DescriptionSummarizer descriptionSummarizer;

    /** Token 计数器（向量内容精确截断） */
    private final TokenCounter tokenCounter;

    /** 编程式事务模板（短事务 A/B，事务规范 2.3） */
    private final TransactionTemplate transactionTemplate;

    /** 向量内容收口原语（写回提交后校验并修复向量内容与权威描述的错位，graph-write-consistency） */
    private final VectorContentReconciler vectorContentReconciler;

    /** 摄入专用执行器（实体/关系阶段并行任务提交入口） */
    private final Executor ingestionExecutor;

    /** 条纹锁数组（构造时一次性填充，之后只读） */
    private final ReentrantLock[] lockStripes;

    /**
     * 构造图合并服务。
     *
     * @param entityNodeRepository       图谱实体节点仓储
     * @param relationEdgeRepository     图谱关系边仓储
     * @param entityInfoVectorRepository 实体向量仓储
     * @param relationInfoVectorRepository 关系向量仓储
     * @param embeddingClient            向量化端口
     * @param descriptionSummarizer      描述摘要器
     * @param tokenCounter               Token 计数器
     * @param transactionTemplate        编程式事务模板
     * @param vectorContentReconciler    向量内容收口原语
     * @param ingestionExecutor          虚拟线程扇出执行器（ragFanoutExecutor，阶段并发仍由 Semaphore 闸门约束）
     */
    public GraphMergeService(EntityNodeGraphRepository entityNodeRepository,
                             RelationEdgeGraphRepository relationEdgeRepository,
                             EntityInfoVectorRepository entityInfoVectorRepository,
                             RelationInfoVectorRepository relationInfoVectorRepository,
                             EmbeddingClient embeddingClient,
                             DescriptionSummarizer descriptionSummarizer,
                             TokenCounter tokenCounter,
                             TransactionTemplate transactionTemplate,
                             VectorContentReconciler vectorContentReconciler,
                             @Qualifier("ragFanoutExecutor") Executor ingestionExecutor) {
        this.entityNodeRepository = entityNodeRepository;
        this.relationEdgeRepository = relationEdgeRepository;
        this.entityInfoVectorRepository = entityInfoVectorRepository;
        this.relationInfoVectorRepository = relationInfoVectorRepository;
        this.embeddingClient = embeddingClient;
        this.descriptionSummarizer = descriptionSummarizer;
        this.tokenCounter = tokenCounter;
        this.transactionTemplate = transactionTemplate;
        this.vectorContentReconciler = vectorContentReconciler;
        this.ingestionExecutor = ingestionExecutor;
        this.lockStripes = new ReentrantLock[LOCK_STRIPE_COUNT];
        for (int i = 0; i < LOCK_STRIPE_COUNT; i++) {
            this.lockStripes[i] = new ReentrantLock();
        }
    }

    /**
     * 合并本批抽取产物到知识图谱（实体先、关系后，两阶段各自并行）。
     *
     * @param ctx            图合并上下文（库/文档身份、模型引用、限额与取消检查）
     * @param extractedNodes 本批聚合前的实体节点列表（可为空）
     * @param extractedEdges 本批聚合前的关系边列表（可为空）
     * @return 合并计数报告
     * @throws IllegalArgumentException 上下文为空
     * @throws IllegalStateException    调用已取消，或任一阶段存在失败（首异常上抛，其余 suppressed）
     */
    public GraphMergeReport mergeNodesAndEdges(GraphMergeContext ctx, List<EntityNode> extractedNodes,
                                               List<RelationEdge> extractedEdges) {
        if (ObjectUtils.isEmpty(ctx)) {
            throw new IllegalArgumentException("图合并上下文不能为空");
        }
        if (ctx.isCancelled()) {
            throw new IllegalStateException("摄入已取消，图合并中止");
        }
        MutableReport report = new MutableReport();
        Map<String, EntityNode> nodeMap = aggregateNodes(normalizeEntityNames(extractedNodes, ctx.kbId()));
        Map<String, BatchEdge> edgeMap = aggregateEdges(ctx.kbId(),
                normalizeEdgeEndpointNames(extractedEdges, ctx.kbId(), report), report);
        Semaphore gate = new Semaphore(ctx.params().concurrencyGate());
        runPhase("实体合并", nodeMap.values(), node -> mergeOneEntity(ctx, node, report), gate);
        runPhase("关系合并", edgeMap.values(), edge -> mergeOneEdge(ctx, edge, report), gate);
        log.info("图合并完成：kbId=[{}]，documentId=[{}]，报告=[{}]", ctx.kbId(), ctx.documentId(), report.toReport());
        return report.toReport();
    }

    /**
     * 实体名入库防御线：名称先经 {@link EntityNameNormalizer} 归一再判空、后截断
     * （&gt; {@value #MAX_GRAPH_NAME_LENGTH}，对齐 {@code entity_node_graph.entity_name
     * VARCHAR(512)}）——「归一 → 判空 → 截断」顺序固定：归一必须先于空判定（无效名归一为
     * 空串后统一按空名跳过）与截断（先截后归一会令引号/空格变体在截断位错位、且归一可能
     * 重新引入超限形态）。抽取层已对同名归一，本防御线依赖归一函数的幂等约束
     * （{@code normalize(normalize(x)) == normalize(x)}）对已归一名称重复执行无副作用；
     * 归一改变过名称的条目重建节点，未变化条目原对象透传（不触碰任何属性访问器）。
     *
     * @param extractedNodes 抽取实体列表（可为空）
     * @param kbId           所属知识库ID（日志定位）
     * @return 归一后的实体列表
     */
    private List<EntityNode> normalizeEntityNames(List<EntityNode> extractedNodes, Long kbId) {
        if (ObjectUtils.isEmpty(extractedNodes)) {
            return List.of();
        }
        List<EntityNode> normalized = new ArrayList<>(extractedNodes.size());
        for (EntityNode node : extractedNodes) {
            if (ObjectUtils.isEmpty(node)) {
                continue;
            }
            String name = EntityNameNormalizer.normalize(node.entityName());
            if (StringUtils.isBlank(name)) {
                log.warn("实体名归一后为空，跳过该条目：kbId=[{}]，原名=[{}]", kbId, node.entityName());
                continue;
            }
            String finalName = truncateNameIfOverLength(kbId, NAME_KIND_ENTITY, name);
            if (StringUtils.equals(finalName, node.entityName())) {
                normalized.add(node);
                continue;
            }
            normalized.add(new EntityNode(node.kbId(), finalName, node.properties()));
        }
        return normalized;
    }

    /**
     * 关系端点名入库防御线：端点先归一再判空、后截断（顺序依据与幂等口径同
     * {@link #normalizeEntityNames}）；归一/截断后两端点同名（原非自环，含两侧变体名
     * 归一后收敛同一值）视为自环丢弃并计数；两端点均未变化条目原对象透传
     * （不触碰 kbId/properties 等其它访问器）。
     *
     * @param extractedEdges 抽取关系列表（可为空）
     * @param kbId           所属知识库ID（重建与日志定位）
     * @param report         可变计数报告
     * @return 归一后的关系列表
     */
    private List<RelationEdge> normalizeEdgeEndpointNames(List<RelationEdge> extractedEdges, Long kbId,
                                                          MutableReport report) {
        if (ObjectUtils.isEmpty(extractedEdges)) {
            return List.of();
        }
        List<RelationEdge> normalized = new ArrayList<>(extractedEdges.size());
        for (RelationEdge edge : extractedEdges) {
            if (ObjectUtils.isEmpty(edge)) {
                continue;
            }
            String source = EntityNameNormalizer.normalize(edge.sourceName());
            String target = EntityNameNormalizer.normalize(edge.targetName());
            if (StringUtils.isBlank(source) || StringUtils.isBlank(target)) {
                log.warn("关系端点名归一后为空，跳过该条目：kbId=[{}]，端点=[{}~{}]", kbId, source, target);
                continue;
            }
            String finalSource = truncateNameIfOverLength(kbId, NAME_KIND_EDGE_SOURCE, source);
            String finalTarget = truncateNameIfOverLength(kbId, NAME_KIND_EDGE_TARGET, target);
            boolean unchanged = StringUtils.equals(finalSource, edge.sourceName())
                    && StringUtils.equals(finalTarget, edge.targetName());
            if (unchanged) {
                normalized.add(edge);
                continue;
            }
            if (StringUtils.equals(finalSource, finalTarget)) {
                log.warn("端点名归一/截断后形成自环，丢弃：kbId=[{}]，entity=[{}]", kbId, finalSource);
                report.selfLoopDropped.incrementAndGet();
                continue;
            }
            normalized.add(new RelationEdge(kbId, finalSource, finalTarget, edge.properties()));
        }
        return normalized;
    }

    /**
     * 名称超长截断：未超限原样返回，超限输出 WARN（含原名前缀）并返回前缀子串。
     *
     * @param kbId      所属知识库ID（日志定位）
     * @param objectLabel 对象标识（日志定位）
     * @param name      原始名称
     * @return 不超过 {@value #MAX_GRAPH_NAME_LENGTH} 的名称
     */
    private static String truncateNameIfOverLength(Long kbId, String objectLabel, String name) {
        if (name.length() <= MAX_GRAPH_NAME_LENGTH) {
            return name;
        }
        log.warn("名称超长截断：kbId=[{}]，对象=[{}]，原名前缀=[{}]，原长度=[{}]，保留=[{}]",
                kbId, objectLabel, name.substring(0, TRUNCATED_NAME_WARN_PREFIX_LENGTH),
                name.length(), MAX_GRAPH_NAME_LENGTH);
        return name.substring(0, MAX_GRAPH_NAME_LENGTH);
    }

    /**
     * 批次内实体聚合：同名归一（保持首现顺序）。
     *
     * @param extractedNodes 抽取实体列表（可为空）
     * @return 名称 → 聚合后实体
     */
    private Map<String, EntityNode> aggregateNodes(List<EntityNode> extractedNodes) {
        Map<String, EntityNode> nodeMap = new LinkedHashMap<>();
        if (ObjectUtils.isEmpty(extractedNodes)) {
            return nodeMap;
        }
        for (EntityNode node : extractedNodes) {
            if (ObjectUtils.isEmpty(node)) {
                continue;
            }
            nodeMap.merge(node.entityName(), node, EntityNode::merge);
        }
        return nodeMap;
    }

    /**
     * 批次内关系聚合：同无向端点对归一（骨架方向恒为字典序归一方向），每条抽取边展开为
     * 「每来源一条全额」贡献记录并批内全额拼接、不自我过滤——上游 LightRAG「同一 chunk
     * 双抽同一对端点 → 重复计权」的批内不去重形态在本管线不可达：同 (端点对, 来源) 记录
     * 已在抽取汇聚器（{@code EntityExtractionService.ExtractionHolder}）归一为一条
     * （取最大权重），本方法逐来源全额累加的公式本身不变（统一口径另见 {@link RelationEdge}
     * 类注释与 {@link BatchEdge}，三处互相引用）；
     * 自环边防御性丢弃并计数（{@link RelationEdge} 构造器已拒自环，此处兜底防未来旁路入口，
     * 扩展流程 5b）。
     *
     * @param kbId           所属知识库ID（日志定位）
     * @param extractedEdges 抽取关系列表（可为空）
     * @param report         可变计数报告
     * @return 无向端点对键 → 批内聚合值对象（骨架 + 贡献记录列表）
     */
    private Map<String, BatchEdge> aggregateEdges(Long kbId, List<RelationEdge> extractedEdges,
                                                   MutableReport report) {
        Map<String, BatchEdge> edgeMap = new LinkedHashMap<>();
        if (ObjectUtils.isEmpty(extractedEdges)) {
            return edgeMap;
        }
        for (RelationEdge edge : extractedEdges) {
            if (ObjectUtils.isEmpty(edge)) {
                continue;
            }
            if (StringUtils.equals(edge.sourceName(), edge.targetName())) {
                log.warn("自环边丢弃：kbId=[{}]，entity=[{}]", kbId, edge.sourceName());
                report.selfLoopDropped.incrementAndGet();
                continue;
            }
            String key = String.join(EDGE_KEY_SEPARATOR, edge.normalizedPair());
            BatchEdge existing = edgeMap.get(key);
            if (ObjectUtils.isEmpty(existing)) {
                edgeMap.put(key, new BatchEdge(edge, new ArrayList<>(RelationEdge.expandContributions(edge))));
            } else {
                existing.records().addAll(RelationEdge.expandContributions(edge));
                edgeMap.put(key, new BatchEdge(existing.edge().mergeSkeleton(edge), existing.records()));
            }
        }
        return edgeMap;
    }

    /**
     * 阶段执行框架：条目级并行提交到摄入执行器，Semaphore 闸门限制远程调用并发；
     * 全部完成后聚合失败——存在失败即上抛首个异常（其余 addSuppressed），实现阶段级 fail-closed。
     *
     * @param phaseName 阶段名（异常消息定位）
     * @param items     待处理条目
     * @param operation 条目处理逻辑（内部自行持锁与短事务）
     * @param gate      并发闸门
     * @param <T>       条目类型
     * @throws IllegalStateException 任一处理任务抛出异常
     */
    private <T> void runPhase(String phaseName, Collection<T> items, Consumer<T> operation, Semaphore gate) {
        if (ObjectUtils.isEmpty(items)) {
            return;
        }
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        List<CompletableFuture<Void>> futures = new ArrayList<>(items.size());
        for (T item : items) {
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    gate.acquire();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    failures.add(e);
                    return;
                }
                try {
                    operation.accept(item);
                } catch (Exception e) {
                    failures.add(e);
                } finally {
                    gate.release();
                }
            }, ingestionExecutor));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        if (ObjectUtils.isNotEmpty(failures)) {
            List<Throwable> errors = new ArrayList<>(failures);
            Throwable first = errors.get(0);
            for (int i = 1; i < errors.size(); i++) {
                first.addSuppressed(errors.get(i));
            }
            throw new IllegalStateException(phaseName + "失败：" + first.getMessage(), first);
        }
    }

    /**
     * 执行写回事务，并在命中唯一约束冲突时重试（账本行首次插入的并发竞争）。
     * <p>并发下两个实例可能同时插入同一条尚不存在的账本行：先到者成功、后到者被数据库唯一约束拦截。
     * 后者 MUST 重试整个写回事务（重读当前值 → 重算 → 写回），MUST NOT 以失败告终、
     * MUST NOT 静默丢弃该批贡献、MUST NOT 退化为「仅插入不合并」。重试次数有上界，
     * 超出上界后按批次失败处理（原样上抛，由 {@link #runPhase} 汇总为阶段失败）。
     * 除唯一约束冲突外的其他异常 MUST NOT 触发重试，沿用既有异常处理。</p>
     *
     * @param writeBody 写回事务体（内部 MUST 仅含数据库 CRUD，MUST NOT 出现远程调用；重试时会被重新执行，
     *                  故其依赖的入参必须来自事务外的不可变值）
     * @param subject   日志定位标识（如 {@code 实体[Alice]}）
     */
    private void runWriteTransactionWithRetry(Runnable writeBody, String subject) {
        for (int attempt = 1; attempt <= WRITE_TRANSACTION_MAX_ATTEMPTS; attempt++) {
            try {
                transactionTemplate.executeWithoutResult(status -> writeBody.run());
                return;
            } catch (RuntimeException e) {
                if (!isUniqueConstraintConflict(e) || attempt == WRITE_TRANSACTION_MAX_ATTEMPTS) {
                    throw e;
                }
                log.warn("图谱账本写回命中唯一约束冲突（账本行首次插入的并发竞争），重试写回事务："
                        + "对象=[{}]，第=[{}]次", subject, attempt);
            }
        }
    }

    /**
     * 判断异常是否为唯一约束冲突（账本行首次插入的并发竞争识别点）。
     * <p>双通道识别：Spring 的 {@link DuplicateKeyException}（DAO 异常转换启用时命中），
     * 以及异常因果链中 PostgreSQL 的 {@code SQLSTATE 23505}（转换未启用时的兜底）。
     * 其他完整性约束异常 MUST NOT 被判为冲突——它们不具备可重试性。</p>
     *
     * @param e 待判定异常
     * @return 命中唯一约束冲突返回 true
     */
    private static boolean isUniqueConstraintConflict(Throwable e) {
        for (Throwable cursor = e; ObjectUtils.isNotEmpty(cursor); cursor = cursor.getCause()) {
            if (cursor instanceof DuplicateKeyException) {
                return true;
            }
            if (cursor instanceof SQLException sqlException
                    && StringUtils.equals(sqlException.getSQLState(), UNIQUE_VIOLATION_SQL_STATE)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 合并单个实体（1 把实体锁 → 事务外普通读构造上下文 → 事务外摘要与向量化 → 写回事务内加锁读-改-写）。
     * <p>写回事务内的加锁读是账本与描述的<b>唯一写回基准</b>；事务外读到的是过期快照，仅用于给 LLM
     * 提供既有描述上下文与判断旧向量可否复用，其结果 MUST NOT 参与写回值的计算——否则并发合并下
     * 后写者会以过期基准覆盖先写者的贡献。</p>
     * <p><b>同事务写入前提</b>：实体图行与其向量账本行在同一写回事务内 upsert，
     * 不存在上游 LightRAG 顾虑的「账本跑在边前面」的先后偏差，幂等判定与账本收缩可放心以
     * 向量账本为唯一权威。</p>
     *
     * @param ctx      图合并上下文
     * @param incoming 聚合后的本批实体
     * @param report   可变计数报告
     */
    private void mergeOneEntity(GraphMergeContext ctx, EntityNode incoming, MutableReport report) {
        String name = incoming.entityName();
        ReentrantLock lock = lockFor(entityLockKey(ctx.kbId(), name));
        lock.lock();
        try {
            GraphMergeParams params = ctx.params();
            // 事务外普通读（不加锁）：仅供构造 LLM 摘要上下文与复用旧向量判断，MUST NOT 作为写回基准
            EntityNode contextNode = entityNodeRepository.findByKbIdAndName(ctx.kbId(), name).orElse(null);
            EntityInfoVector contextVector = entityInfoVectorRepository.findByKbIdAndName(ctx.kbId(), name)
                    .orElse(null);
            // 幂等重放快路径：事务外快照已显示账本覆盖本批来源 → 直接跳过，不付出无谓的 embedding；
            // 权威判定仍在写回事务内以加锁读值重做（此处仅为避免远程调用，不参与任何写回值计算）
            if (isEntityIdempotentShortCircuit(contextNode, contextVector, incoming)) {
                report.entitiesSkipped.incrementAndGet();
                return;
            }
            EntityNode contextMerged = ObjectUtils.isEmpty(contextNode) ? incoming : contextNode.merge(incoming);
            String summary = descriptionSummarizer.summarize(ctx, name, contextMerged.properties().entityType(),
                    contextMerged.properties().descriptions());
            String descriptionCandidate = StringUtils.defaultIfBlank(summary, contextMerged.effectiveDescription());
            String content = tokenCounter.truncate(EntityInfoVector.buildContent(name, descriptionCandidate),
                    params.embeddingTokenLimit());
            float[] vector = reuseEntityVector(contextVector, content, ctx);
            // 写回事务：加锁重读当前账本 → 以当前值重算 → upsert（行锁覆盖整个读-改-写窗口）
            AtomicBoolean skipped = new AtomicBoolean(false);
            runWriteTransactionWithRetry(() -> {
                skipped.set(false);
                EntityNode current = entityNodeRepository.lockByKbIdAndNames(ctx.kbId(), List.of(name))
                        .stream().findFirst().orElse(null);
                EntityInfoVector currentVector = entityInfoVectorRepository.lockByKbIdAndName(ctx.kbId(), name)
                        .orElse(null);
                // 覆盖判定以向量表 chunk_ids 为权威账本，基准为加锁读到的当前值
                if (isEntityIdempotentShortCircuit(current, currentVector, incoming)) {
                    skipped.set(true);
                    return;
                }
                EntityNode merged = ObjectUtils.isEmpty(current) ? incoming : current.merge(incoming);
                // 账本与展示列分叉：向量账本恒取截断前的全量来源并集；截断只作用于图行展示列
                List<Long> fullEntitySources = merged.properties().sourceIds();
                List<Long> keptSources = truncateSourceIds(fullEntitySources, params,
                        ctx.kbId(), OBJECT_LABEL_ENTITY + name + OBJECT_LABEL_SUFFIX);
                String finalDescription = StringUtils.defaultIfBlank(summary, merged.effectiveDescription());
                // 来源文件路径列表落库前归一：去重保序 + 上限截断 + 溢出占位（与重建路径共用同一口径）
                List<String> finalFilePaths = GraphSourceFilePaths.normalize(merged.properties().filePaths(),
                        params.sourceFilePathsLimit(), params.sourceFilePathsPlaceholder());
                EntityNode finalNode = new EntityNode(ctx.kbId(), name, new EntityProperties(
                        merged.properties().entityType(), finalDescription, keptSources,
                        finalFilePaths, merged.properties().entityTypeVotes(),
                        List.of(finalDescription)));
                EntityInfoVector finalVector = buildEntityVector(ctx.kbId(), name, content, fullEntitySources,
                        vector, currentVector);
                entityNodeRepository.upsert(finalNode, ctx.operator());
                entityInfoVectorRepository.upsert(finalVector, ctx.operator());
            }, OBJECT_LABEL_ENTITY + name + OBJECT_LABEL_SUFFIX);
            if (skipped.get()) {
                report.entitiesSkipped.incrementAndGet();
            } else {
                report.entitiesWritten.incrementAndGet();
                // 提交后收口：仅在确有写入时，以「知识库 + 实体名」身份交收口原语校验向量内容与
                // 权威描述是否一致（design D1/D5——原语自读当前图行/向量行，无需合并内部变量）；
                // 被幂等短路跳过的合并未产生写入，MUST NOT 触发收口。
                reconcileEntitySafely(ctx, name, report);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 合并单条关系（1 把边锁 → 事务外普通读构造上下文 → 事务外摘要与向量化
     * → 写回事务内加锁读-改-写）。
     * <p>关系合并不再触碰实体行（不补建占位端点、不回补端点账本），仅持边锁。</p>
     *
     * @param ctx      图合并上下文
     * @param incoming 批内聚合后的本批关系（骨架方向恒为归一方向 + 贡献记录全额列表）
     * @param report   可变计数报告
     */
    private void mergeOneEdge(GraphMergeContext ctx, BatchEdge incoming, MutableReport report) {
        List<String> pair = incoming.edge().normalizedPair();
        ReentrantLock lock = lockFor(edgeLockKey(ctx.kbId(), pair.get(0), pair.get(1)));
        lock.lock();
        try {
            mergeOneEdgeLocked(ctx, incoming, pair, report);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 关系合并主体（已持有边锁）。
     * <p>三段链：事务外普通读（仅构造 LLM 上下文）→ 事务外摘要与向量化 →
     * 写回事务内「加锁重读当前边行与边向量账本 → 以当前值重算 weight 与账本 → 写回」。
     * 权重累加的过滤基准恒为<strong>写回事务内加锁读到</strong>的向量账本 chunk_ids
     * （与幂等短路同一份依据）；公式：{@code keepRow + 批骨架 + 批记录全额}，
     * 新边按「空账本骨架（weight=0、账本=[]）+ 批记录全额」起步——重喂同一批记录增量为 0（幂等）。
     * 不再折叠反向残留伪记录（方向已在抽取聚合出口归一，反向行无从产生）。</p>
     * <p><b>同事务写入前提</b>：关系图行与其向量账本行在同一写回事务内 upsert，
     * 不存在上游 LightRAG 顾虑的「账本跑在图边前面」（chunks 已写、边未写的部分写入），
     * 故过滤基准可放心取向量账本而非图行展示列。</p>
     *
     * @param ctx      图合并上下文
     * @param incoming 批内聚合后的本批关系
     * @param pair     无向归一端点对（字典序）
     * @param report   可变计数报告
     */
    private void mergeOneEdgeLocked(GraphMergeContext ctx, BatchEdge incoming, List<String> pair,
                                    MutableReport report) {
        GraphMergeParams params = ctx.params();
        RelationEdge skeleton = incoming.edge();
        List<RelationContribution> records = incoming.records();
        // 事务外普通读（不加锁）：仅供构造 LLM 摘要上下文、复用旧向量与幂等重放快路径，
        // MUST NOT 作为写回基准
        List<RelationEdge> contextRows = relationEdgeRepository.findByKbIdAndUnorderedPair(
                ctx.kbId(), pair.get(0), pair.get(1));
        RelationInfoVector contextVector = relationInfoVectorRepository.findByKbIdAndUnorderedPair(
                ctx.kbId(), pair.get(0), pair.get(1)).orElse(null);
        List<Long> batchSources = distinctNonNullSources(records);
        // 幂等重放快路径：事务外快照已满足全部短路条件 → 直接跳过，不付出无谓的 LLM 摘要与 embedding；
        // 权威判定仍在写回事务内以加锁读值重做（此处仅为避免远程调用，不参与任何写回值计算）
        if (isRelationIdempotentShortCircuit(skeleton, contextRows, contextVector, records, batchSources)) {
            report.relationsSkipped.incrementAndGet();
            return;
        }
        RelationEdge contextMerged = coalesceEdge(skeleton, contextRows, records,
                ObjectUtils.isEmpty(contextVector) ? List.of() : contextVector.chunkIds());
        List<String> descCandidates = new ArrayList<>();
        for (RelationEdge row : contextRows) {
            addIfPresent(descCandidates, row.properties().description());
        }
        addIfPresent(descCandidates, skeleton.properties().description());
        String summary = descriptionSummarizer.summarize(ctx,
                pair.get(0) + RELATION_NAME_CONNECTOR + pair.get(1),
                String.join(",", safeKeywordsOf(contextMerged)), descCandidates);
        String descriptionCandidate = StringUtils.defaultIfBlank(summary, contextMerged.properties().description());
        String edgeContent = tokenCounter.truncate(RelationInfoVector.buildContent(
                contextMerged.properties().keywords(), pair.get(0), pair.get(1), descriptionCandidate),
                params.embeddingTokenLimit());
        float[] edgeVector = reuseRelationVector(contextVector, edgeContent, ctx);
        // 写回事务：加锁重读当前边行与边向量账本 → 以当前值重算 → 写回（行锁覆盖整个读-改-写窗口）
        AtomicBoolean skipped = new AtomicBoolean(false);
        runWriteTransactionWithRetry(() -> {
            skipped.set(false);
            List<RelationEdge> rows = relationEdgeRepository.lockByKbIdAndUnorderedPair(
                    ctx.kbId(), pair.get(0), pair.get(1));
            RelationInfoVector currentVector = relationInfoVectorRepository.lockByKbIdAndUnorderedPair(
                    ctx.kbId(), pair.get(0), pair.get(1)).orElse(null);
            List<Long> currentLedgerChunkIds = ObjectUtils.isEmpty(currentVector) ? List.of()
                    : currentVector.chunkIds();
            // 幂等短路：基准为加锁读到的当前账本（对事务外快路径的权威复核）
            if (isRelationIdempotentShortCircuit(skeleton, rows, currentVector, records, batchSources)) {
                skipped.set(true);
                return;
            }
            RelationEdge merged = coalesceEdge(skeleton, rows, records, currentLedgerChunkIds);
            String finalDescription = StringUtils.defaultIfBlank(summary, merged.properties().description());
            // 账本与展示列分叉：向量账本恒取截断前的全量来源并集；截断只作用于图行展示列
            List<Long> fullEdgeSources = merged.properties().sourceIds();
            List<Long> keptEdgeSources = truncateSourceIds(fullEdgeSources, params, ctx.kbId(),
                    OBJECT_LABEL_RELATION + pair.get(0) + EDGE_KEY_SEPARATOR + pair.get(1) + OBJECT_LABEL_SUFFIX);
            // 写入方向恒取批内归一方向（字典序 pair），MUST NOT 沿用 merged（可能取自历史反向行）的方向；
            // 方向仅是端点字段顺序，properties 描述「这一对」，对调无损（与已退役的有损折叠机制不同）——
            // 属性（权重/描述/关键词/来源账本/展示来源）仍取合并结果。
            // 来源文件路径列表落库前归一：去重保序 + 上限截断 + 溢出占位（与重建路径共用同一口径）
            List<String> finalEdgeFilePaths = GraphSourceFilePaths.normalize(merged.properties().filePaths(),
                    params.sourceFilePathsLimit(), params.sourceFilePathsPlaceholder());
            RelationEdge finalEdge = new RelationEdge(ctx.kbId(), pair.get(0), pair.get(1),
                    new RelationProperties(merged.properties().weight(), finalDescription,
                            merged.properties().keywords(), keptEdgeSources, finalEdgeFilePaths));
            List<Long> mergedEdgeChunkIds = ObjectUtils.isEmpty(currentVector) ? fullEdgeSources
                    : unionIds(currentVector.chunkIds(), fullEdgeSources);
            RelationInfoVector finalEdgeVector = RelationInfoVector.create(ctx.kbId(), pair.get(0), pair.get(1),
                    edgeContent, mergedEdgeChunkIds, edgeVector);
            relationEdgeRepository.upsertAll(List.of(finalEdge), ctx.operator());
            relationInfoVectorRepository.upsertAll(List.of(finalEdgeVector), ctx.operator());
        }, OBJECT_LABEL_RELATION + pair.get(0) + EDGE_KEY_SEPARATOR + pair.get(1) + OBJECT_LABEL_SUFFIX);
        if (skipped.get()) {
            report.relationsSkipped.incrementAndGet();
        } else {
            report.relationsWritten.incrementAndGet();
            // 提交后收口（关系侧）：仅在确有写入时以「知识库 + 归一端点对」身份触发，异常降级同上。
            reconcileRelationSafely(ctx, pair, report);
        }
    }

    /**
     * 实体侧提交后收口（异常降级）：以「知识库 + 实体名」身份调用收口原语并计数；
     * 收口整体以 try/catch 包裹——错位属质量问题而非数据损坏，向量化/窄更新的任何异常只记 WARN
     * 与本方法计数，MUST NOT 使已提交的合并失败或回滚（design D6）。
     *
     * @param ctx    图合并上下文
     * @param name   实体名（条目身份）
     * @param report 可变计数报告
     */
    private void reconcileEntitySafely(GraphMergeContext ctx, String name, MutableReport report) {
        try {
            VectorContentReconciler.Result result = vectorContentReconciler.reconcileEntity(
                    ctx.kbId(), name, ctx.embeddingModelProfileId(), ctx.params().embeddingTokenLimit());
            countReconcileResult(report, result);
        } catch (RuntimeException e) {
            log.warn("实体向量内容收口异常（降级不影响合并）：kbId=[{}]，实体=[{}]，原因=[{}]",
                    ctx.kbId(), name, e.getMessage());
        }
    }

    /**
     * 关系侧提交后收口（异常降级）：以「知识库 + 无向端点对」身份调用收口原语并计数，降级语义同
     * {@link #reconcileEntitySafely}。
     *
     * @param ctx    图合并上下文
     * @param pair   无向归一端点对（字典序）
     * @param report 可变计数报告
     */
    private void reconcileRelationSafely(GraphMergeContext ctx, List<String> pair, MutableReport report) {
        try {
            VectorContentReconciler.Result result = vectorContentReconciler.reconcileRelation(
                    ctx.kbId(), pair.get(0), pair.get(1), ctx.embeddingModelProfileId(),
                    ctx.params().embeddingTokenLimit());
            countReconcileResult(report, result);
        } catch (RuntimeException e) {
            log.warn("关系向量内容收口异常（降级不影响合并）：kbId=[{}]，端点对=[{}~{}]，原因=[{}]",
                    ctx.kbId(), pair.get(0), pair.get(1), e.getMessage());
        }
    }

    /**
     * 收口结果计数：仅统计本变更的两个观测面计数——「收口成功」与「守卫落空」；
     * 一致 / 跳过 / 失败（或原语未打桩返回 null）不进入计数，失败已在原语内 WARN 记录。
     *
     * @param report 可变计数报告
     * @param result 收口结果
     */
    private static void countReconcileResult(MutableReport report, VectorContentReconciler.Result result) {
        if (result == VectorContentReconciler.Result.RECONCILED) {
            report.reconcilesFixed.incrementAndGet();
        } else if (result == VectorContentReconciler.Result.GUARD_MISSED) {
            report.reconcilesGuardMissed.incrementAndGet();
        }
    }

    /**
     * 以给定图边行集合与<b>向量账本</b>为基准累计本批合并结果（「keepRow → 批骨架 + 批记录全额」）。
     * <p>同一逻辑被用于两处：事务外用过期快照构造 LLM 摘要上下文（结果仅用于描述文案），
     * 以及写回事务内以加锁读到的当前行计算写回值——两处公式必须严格同式。
     * 权重过滤基准恒为向量账本 {@code ledgerChunkIds}（与幂等短路同一份依据），
     * 图行 source_ids 仅为展示列、不再参与计权判定。</p>
     *
     * @param skeleton        批内聚合后的边骨架
     * @param rows            图边行集合（事务外快照或写回事务内加锁读值）
     * @param records         批内贡献记录
     * @param ledgerChunkIds  向量账本（对应场景下向量行的 chunk_ids，可为空列表——fail-open 全额累加）
     * @return 合并后的边（无既有行时以空账本起步）
     */
    private static RelationEdge coalesceEdge(RelationEdge skeleton, List<RelationEdge> rows,
                                             List<RelationContribution> records, List<Long> ledgerChunkIds) {
        RelationEdge keepRow = pickKeepRow(skeleton, rows);
        if (ObjectUtils.isEmpty(keepRow)) {
            return new RelationEdge(skeleton.kbId(), skeleton.sourceName(), skeleton.targetName(),
                    new RelationProperties(0.0, skeleton.properties().description(),
                            skeleton.properties().keywords(), List.of(), skeleton.properties().filePaths()))
                    .mergeContributions(records, ledgerChunkIds);
        }
        return keepRow.mergeSkeleton(skeleton).mergeContributions(records, ledgerChunkIds);
    }

    /**
     * 从图边行集合中选出保留方向的既有行：方向已在抽取聚合出口归一后，同一无向端点对至多
     * 命中一行；优先与批骨架同向者（等值即归一序），兼容读取历史多行时取首行。
     * <p><b>读侧语义，与写入方向无关</b>：本方法的职责仅是「让历史反向行的属性也参与合并」
     * （读侧不丢属性）；写回最终方向恒由 {@link #mergeOneEdgeLocked} 取批内归一方向 {@code pair}
     * 决定（见 graph-write-consistency D7），不沿用此处选中行的方向。</p>
     *
     * @param skeleton 批内聚合后的边骨架
     * @param rows     图边行集合
     * @return 保留行；无既有行时返回 {@code null}
     */
    private static RelationEdge pickKeepRow(RelationEdge skeleton, List<RelationEdge> rows) {
        return rows.stream()
                .filter(row -> StringUtils.equals(row.sourceName(), skeleton.sourceName()))
                .findFirst()
                .orElse(ObjectUtils.isEmpty(rows) ? null : rows.get(0));
    }

    /**
     * 幂等短路判定（实体版）：向量账本覆盖本批全部入参来源即视为已摄入。
     * <p>fail-open 口径——账本为 null/空、入参来源为空或含 null 元素时一律判「未覆盖」。</p>
     *
     * @param existing 既有实体节点（可为 null）
     * @param vector   既有实体向量行（账本来源，可为 null）
     * @param incoming 本批聚合后的实体
     * @return 满足短路条件返回 true
     */
    private static boolean isEntityIdempotentShortCircuit(EntityNode existing, EntityInfoVector vector,
                                                          EntityNode incoming) {
        return ObjectUtils.isNotEmpty(existing) && sourceIdsCovered(
                ObjectUtils.isEmpty(vector) ? null : vector.chunkIds(),
                safeSourceIdsOfEntity(incoming.properties()));
    }

    /**
     * 幂等短路判定（关系版）：向量账本覆盖本批全部来源，且关键词覆盖、描述非空、
     * 全部记录可归因（fail-open——含 null 来源记录即不短路）。
     * <p>不再包含「无反向残留行」「两端点齐备」合取项：方向归一后残留行无从产生；
     * 端点缺失属正常态（不补建占位节点），其存在与否不影响关系条目的幂等判定。</p>
     *
     * @param skeleton     批内聚合后的边骨架
     * @param rows         图边行集合（事务外快照或写回事务内加锁读值）
     * @param vector       关系向量行（账本来源，可为 null）
     * @param records      批内贡献记录
     * @param batchSources 本批非空来源
     * @return 满足短路条件返回 true
     */
    private static boolean isRelationIdempotentShortCircuit(RelationEdge skeleton, List<RelationEdge> rows,
                                                            RelationInfoVector vector,
                                                            List<RelationContribution> records,
                                                            List<Long> batchSources) {
        RelationEdge keepRow = pickKeepRow(skeleton, rows);
        return ObjectUtils.isNotEmpty(keepRow)
                && allRecordsAttributable(records)
                && sourceIdsCovered(ObjectUtils.isEmpty(vector) ? null : vector.chunkIds(), batchSources)
                && safeKeywordsOf(keepRow).containsAll(safeKeywordsOf(skeleton))
                && StringUtils.isNotBlank(keepRow.properties().description());
    }

    /**
     * 批内关系聚合值对象：边骨架（同无向端点对经 {@link RelationEdge#mergeSkeleton} 归一，
     * 方向恒为字典序归一方向）+ 批内贡献记录全额列表（每条抽取边按「每来源一条全额」展开后按抽取顺序拼接，
     * 批内不自我过滤；同 (端点对, 来源) 重复计权形态已在抽取汇聚器归一、本管线不可达，
     * 口径见 {@link #aggregateEdges}）。
     *
     * @param edge    聚合后的边骨架
     * @param records 批内贡献记录列表（可变列表，聚合阶段追加）
     */
    private record BatchEdge(RelationEdge edge, List<RelationContribution> records) {

        /**
         * 紧凑构造器：记录列表空安全兜底为可变空列表。
         */
        BatchEdge {
            records = ObjectUtils.isEmpty(records) ? new ArrayList<>() : records;
        }
    }

    /**
     * 可变计数报告（并发任务下以原子计数聚合，阶段结束转不可变快照）。
     */
    private static final class MutableReport {

        /** 实体写入计数 */
        private final AtomicInteger entitiesWritten = new AtomicInteger();

        /** 实体短路跳过计数 */
        private final AtomicInteger entitiesSkipped = new AtomicInteger();

        /** 关系写入计数 */
        private final AtomicInteger relationsWritten = new AtomicInteger();

        /** 关系短路跳过计数 */
        private final AtomicInteger relationsSkipped = new AtomicInteger();

        /** 自环边丢弃计数 */
        private final AtomicInteger selfLoopDropped = new AtomicInteger();

        /** 向量内容收口成功计数（提交后发现错位并窄更新修复，实体+关系合计，本变更唯一观测面之一） */
        private final AtomicInteger reconcilesFixed = new AtomicInteger();

        /** 向量内容收口守卫落空计数（让位于更晚提交者，实体+关系合计，本变更唯一观测面之一） */
        private final AtomicInteger reconcilesGuardMissed = new AtomicInteger();

        /**
         * 转为不可变报告快照。
         *
         * @return 图合并结果报告
         */
        GraphMergeReport toReport() {
            return new GraphMergeReport(entitiesWritten.get(), entitiesSkipped.get(),
                    relationsWritten.get(), relationsSkipped.get(), selfLoopDropped.get(),
                    reconcilesFixed.get(), reconcilesGuardMissed.get());
        }
    }

    /**
     * 计算锁键对应的条纹下标（无符号哈希取模，保证非负且分布均匀）。
     *
     * @param key 锁键
     * @return 条纹下标（0 ~ {@value #LOCK_STRIPE_COUNT}-1）
     */
    private int stripeIndex(String key) {
        return (key.hashCode() & Integer.MAX_VALUE) % LOCK_STRIPE_COUNT;
    }

    /**
     * 取锁键对应的条纹锁。
     *
     * @param key 锁键
     * @return 条纹锁实例
     */
    private ReentrantLock lockFor(String key) {
        return lockStripes[stripeIndex(key)];
    }

    /**
     * 实体锁键。
     *
     * @param kbId       知识库ID
     * @param entityName 实体名
     * @return 锁键
     */
    private String entityLockKey(Long kbId, String entityName) {
        return ENTITY_LOCK_PREFIX + kbId + ":" + entityName;
    }

    /**
     * 边锁键（调用方保证端点已无向归一）。
     *
     * @param kbId 知识库ID
     * @param nameA 端点一（字典序较小）
     * @param nameB 端点二
     * @return 锁键
     */
    private String edgeLockKey(Long kbId, String nameA, String nameB) {
        return EDGE_LOCK_PREFIX + kbId + ":" + nameA + PAIR_SEPARATOR + nameB;
    }

    /**
     * 重放覆盖判定（账本化，幂等短路依据）：以向量表 chunk_ids 为权威账本，
     * 账本完全覆盖本批入参来源即视为已摄入。
     * <p>fail-open 口径——账本为 null/空、入参来源为空或含 null 元素时一律判「未覆盖」，
     * 宁可多算一次写入也不漏算来源。</p>
     *
     * @param ledgerChunkIds  向量账本（旧向量行 chunk_ids，可为 null）
     * @param incomingSources 本批入参来源列表
     * @return 覆盖返回 true（可短路跳过写入）
     */
    private static boolean sourceIdsCovered(List<Long> ledgerChunkIds, List<Long> incomingSources) {
        if (ObjectUtils.isEmpty(ledgerChunkIds) || ObjectUtils.isEmpty(incomingSources)) {
            return false;
        }
        if (incomingSources.stream().anyMatch(Objects::isNull)) {
            return false;
        }
        return ledgerChunkIds.containsAll(incomingSources);
    }

    /**
     * source_ids 展示列截断：按保留上限与截断策略截取（顺序即 merge 累积的旧优先账本序）。
     * <ul>
     *   <li>KEEP（默认）：保留前 N 条（旧来源优先）；</li>
     *   <li>FIFO：保留后 N 条（新来源优先，先进先出淘汰旧来源）。</li>
     * </ul>
     * 截断<b>只作用于图行展示列</b>，向量账本写入恒取截断前的全量并集，
     * 截断不影响幂等覆盖判定。触发截断时输出 INFO 日志（kbId、对象标识、原条数、
     * 保留数、丢弃数）便于归因。
     *
     * @param sourceIds   来源列表（可为空）
     * @param params      图合并参数（上限与截断策略）
     * @param kbId        知识库ID（日志定位）
     * @param objectLabel 对象标识（日志定位，如 {@code 实体[Alice]} / {@code 关系[A#Bob]}）
     * @return 截断后的不可变列表
     */
    private static List<Long> truncateSourceIds(List<Long> sourceIds, GraphMergeParams params,
                                                Long kbId, String objectLabel) {
        if (ObjectUtils.isEmpty(sourceIds)) {
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
        log.info("source_ids截断：kbId=[{}]，对象=[{}]，原条数=[{}]，保留=[{}]，丢弃=[{}]",
                kbId, objectLabel, sourceIds.size(), kept.size(), sourceIds.size() - kept.size());
        return kept;
    }

    /**
     * 判断贡献记录列表是否全部可归因（非空且每条记录来源均非 null）——
     * 含 null 来源记录时幂等短路 fail-open（无法证明该贡献已被账本覆盖）。
     *
     * @param records 贡献记录列表
     * @return 全部可归因返回 true
     */
    private static boolean allRecordsAttributable(List<RelationContribution> records) {
        if (ObjectUtils.isEmpty(records)) {
            return false;
        }
        for (RelationContribution record : records) {
            if (ObjectUtils.isEmpty(record) || ObjectUtils.isEmpty(record.sourceId())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 提取贡献记录列表中的非空来源（按记录顺序去重）。
     *
     * @param records 贡献记录列表（可为空）
     * @return 去重后的非空来源列表
     */
    private static List<Long> distinctNonNullSources(List<RelationContribution> records) {
        Set<Long> sources = new LinkedHashSet<>();
        if (ObjectUtils.isNotEmpty(records)) {
            for (RelationContribution record : records) {
                if (ObjectUtils.isNotEmpty(record) && ObjectUtils.isNotEmpty(record.sourceId())) {
                    sources.add(record.sourceId());
                }
            }
        }
        return List.copyOf(sources);
    }

    /**
     * 构建实体向量写回行：chunkIds 与旧行去重并集（content/vector 以本次新鲜一致配对为准，
     * 不采用跨行择优，避免内容与向量错配）。
     *
     * @param kbId           知识库ID
     * @param entityName     实体名
     * @param content        向量化内容（已截断）
     * @param chunkIds       本实体截断前的全量来源账本（向量账本不吃图行展示列的截断值）
     * @param vector         向量
     * @param existingVector 旧向量行（可为 null）
     * @return 待 upsert 的实体向量
     */
    private static EntityInfoVector buildEntityVector(Long kbId, String entityName, String content,
                                                      List<Long> chunkIds, float[] vector,
                                                      EntityInfoVector existingVector) {
        List<Long> mergedChunkIds = ObjectUtils.isEmpty(existingVector) ? chunkIds
                : unionIds(existingVector.chunkIds(), chunkIds);
        return EntityInfoVector.create(kbId, entityName, content, mergedChunkIds, vector);
    }

    /**
     * 实体向量复用：旧行内容与本次目标内容一致时复用旧向量，省去一次 embedding 远程调用。
     *
     * @param existing 旧向量行（可为 null）
     * @param content  本次向量化内容
     * @param ctx      图合并上下文
     * @return 向量分量数组
     */
    private float[] reuseEntityVector(EntityInfoVector existing, String content, GraphMergeContext ctx) {
        if (ObjectUtils.isNotEmpty(existing) && ObjectUtils.isNotEmpty(existing.vector())
                && StringUtils.equals(existing.content(), content)) {
            return existing.vector();
        }
        return embeddingClient.embed(ctx.embeddingModelProfileId(), content);
    }

    /**
     * 关系向量复用：旧行内容与本次目标内容一致时复用旧向量。
     *
     * @param existing 旧向量行（可为 null）
     * @param content  本次向量化内容
     * @param ctx      图合并上下文
     * @return 向量分量数组
     */
    private float[] reuseRelationVector(RelationInfoVector existing, String content, GraphMergeContext ctx) {
        if (ObjectUtils.isNotEmpty(existing) && ObjectUtils.isNotEmpty(existing.vector())
                && StringUtils.equals(existing.content(), content)) {
            return existing.vector();
        }
        return embeddingClient.embed(ctx.embeddingModelProfileId(), content);
    }

    /**
     * 分块ID去重并集（旧优先保序）。
     *
     * @param first  旧列表（可为空）
     * @param second 新列表（可为空）
     * @return 不可变并集
     */
    private static List<Long> unionIds(List<Long> first, List<Long> second) {
        Set<Long> union = new LinkedHashSet<>(ObjectUtils.isEmpty(first) ? List.of() : first);
        if (ObjectUtils.isNotEmpty(second)) {
            union.addAll(second);
        }
        return List.copyOf(union);
    }

    /**
     * 实体属性来源列表空安全访问。
     *
     * @param properties 实体属性
     * @return 非空来源列表
     */
    private static List<Long> safeSourceIdsOfEntity(EntityProperties properties) {
        List<Long> sourceIds = properties.sourceIds();
        return ObjectUtils.isEmpty(sourceIds) ? List.of() : sourceIds;
    }

    /**
     * 关系边关键词列表空安全访问。
     *
     * @param edge 关系边
     * @return 非空关键词列表
     */
    private static List<String> safeKeywordsOf(RelationEdge edge) {
        List<String> keywords = edge.properties().keywords();
        return ObjectUtils.isEmpty(keywords) ? List.of() : keywords;
    }

    /**
     * 非空白且未重复时追加到目标列表。
     *
     * @param target 目标列表
     * @param value  候选值（可为空）
     */
    private static void addIfPresent(List<String> target, String value) {
        if (StringUtils.isNotBlank(value) && !target.contains(value)) {
            target.add(value);
        }
    }
}
