package com.linkroa.deepdataagent.rag.application.service;

import com.linkroa.deepdataagent.knowledgebase.api.InFlightTaskRegistry;
import com.linkroa.deepdataagent.knowledgebase.api.InFlightTaskType;
import com.linkroa.deepdataagent.knowledgebase.api.KbCacheCleanupApi;
import com.linkroa.deepdataagent.knowledgebase.api.KbCleanupWriter;
import com.linkroa.deepdataagent.knowledgebase.api.KnowledgeBaseApi;
import com.linkroa.deepdataagent.rag.domain.repository.EntityInfoVectorRepository;
import com.linkroa.deepdataagent.rag.domain.repository.EntityNodeGraphRepository;
import com.linkroa.deepdataagent.rag.domain.repository.RelationEdgeGraphRepository;
import com.linkroa.deepdataagent.rag.domain.repository.RelationInfoVectorRepository;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 整库清退编排服务（RAG 消费方唯一编排）。
 * <p>由 {@code DeletionCleanupTaskExecutor} 的<b>删除清退虚拟线程</b>在单个清退任务内按序驱动
 * （原 {@code KbCleanupTaskQueue} 内存有界队列 + 单消费者机器已退役）：</p>
 * <ol>
 *   <li>{@code ASSETS}（关键）：Storage 先删——经 {@link KbCleanupWriter#cleanupAssetsByKnowledgeBase(Long)}
 *       一步完成「桶发现（读该库文档行 {@code s3_file} 引用）→ 整库前缀 {@code rag/{kbId}/} 清完」，
 *       远程 IO 全程事务外，「对象不存在」视为成功，天然幂等可重入；</li>
 *   <li>{@code DERIVED_DATA}（关键）：{@link KbCleanupWriter#cleanupDerivedDataBatch(Long, int)}
 *       循环至返回 0——切片及其 1:1 表示按批物理清退（复用切片物理删除原语）；</li>
 *   <li>{@code DOCUMENTS}（关键）：{@link KbCleanupWriter#cleanupDocumentsBatch(Long, int)}
 *       循环至返回 0——文档行按批物理清退（标准 delete 即物理 DELETE）；</li>
 *   <li>{@code GRAPH}（关键）：图谱四表按 kbId 物理批删（固定语句顺序「图行 → 向量行」：实体图行 →
 *       关系图行 → 实体向量 → 关系向量），该库全部图与向量条目一并清退，无按文档定位的中间步骤；</li>
 *   <li>{@code CACHE}（<b>非关键</b>）：按库整清 LLM 缓存——失败仅 WARN 留痕、<b>不阻断收口</b>
 *       （缓存为可再生派生物，残留不影响 HNSW 检索正确性与生命周期收口）；</li>
 *   <li>{@code FINAL_DELETE}（关键）：经 {@link KnowledgeBaseApi#executeDelete(Long)} 收口——
 *       单条条件物理 DELETE（{@code id = ? AND lifecycle_status IN (DELETING, DELETE_FAILED)}）删除
 *       知识库行（DELETED 不再是持久状态，行缺失即已删除、同名 {@code uk_kb_name} 释放）；
 *       0 行命中＝已收口，按幂等空转处理（{@code executeDelete} 返回 {@code false} 不视为失败）。</li>
 * </ol>
 *
 * <p>步骤分级与失败语义（「零重试」）：①②③④⑥ 为<b>关键步骤</b>，任一步异常即
 * <b>立即终止本任务</b>并回调 {@link KnowledgeBaseApi#markFailed(Long, String)} 置 DELETE_FAILED
 * 留痕（文案形如 {@code [KB-CLEANUP] step=GRAPH: …}，提示用户「知识库不可用，请重新执行删除」）——
 * <b>MUST NOT 做任何步骤级重试与退避等待</b>（原 {@code app.rag.cleanup.retry-max-attempts} /
 *  机器已退役）；已成功批次不回滚，用户重删后全链幂等重放自愈。
 * ⑤ 缓存清退为<b>非关键步骤</b>，失败仅日志、继续推进收口。</p>
 *
 * <p>事务规范：本服务<b>不加任何事务注解</b>——远程 IO（对象存储）与跨 BC 调用一律置于事务外，
 * 各步骤的单语句 / 单批事务由被调契约自行管理（清退批次小事务在 KB 侧、图谱分片批删在 rag 仓储内自提交）。</p>
 *
 * <p>方法永不抛出：清退失败一律以 {@code [KB-CLEANUP]} 留痕落库或 ERROR/WARN 日志收口，保证执行器的在飞登记
 * 与并发配额一定释放；{@link KnowledgeBaseApi} 由容器直接注入——知识库入站适配器
 * （{@code DefaultKnowledgeBaseApi}）已收窄为仅依赖领域仓储，两个 BC 的 Bean 装配图不再成环，
 * 故无需延迟注入。</p>
 *
 * @author DeepDataAgent
 */
@Service
public class KbCleanupOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(KbCleanupOrchestrationService.class);

    /** 步骤名：文件资产回收（桶发现 + 整库前缀清完，关键）。 */
    static final String STEP_ASSETS = "ASSETS";

    /** 步骤名：派生数据（切片 + 1:1 表示）分批清退（关键）。 */
    static final String STEP_DERIVED_DATA = "DERIVED_DATA";

    /** 步骤名：文档分批清退（关键）。 */
    static final String STEP_DOCUMENTS = "DOCUMENTS";

    /** 步骤名：图谱四表按库物理清退（关键）。 */
    static final String STEP_GRAPH = "GRAPH";

    /** 步骤名：LLM 缓存按库整清（非关键）。 */
    static final String STEP_CACHE = "CACHE";

    /** 步骤名：生命周期收口＝条件物理 DELETE 删除知识库行（关键）。 */
    static final String STEP_FINAL_DELETE = "FINAL_DELETE";

    /** 清退失败留痕前缀（与 {@code markFailed} 契约文案口径一致，运维据此归因）。 */
    static final String FAILURE_TRACE_PREFIX = "[KB-CLEANUP]";

    /** 清退批次条数缺省值（{@code app.rag.cleanup.batch-size}）。 */
    static final int DEFAULT_BATCH_SIZE = 500;

    /** 清退批次条数上限（{@code app.rag.cleanup.batch-size}，缺省 500，符合事务规范 500~1000）。 */
    @Value("${app.rag.cleanup.batch-size:500}")
    private int batchSize = DEFAULT_BATCH_SIZE;

    private final KbCleanupWriter kbCleanupWriter;

    private final EntityNodeGraphRepository entityNodeGraphRepository;

    private final RelationEdgeGraphRepository relationEdgeGraphRepository;

    private final EntityInfoVectorRepository entityInfoVectorRepository;

    private final RelationInfoVectorRepository relationInfoVectorRepository;

    private final KbCacheCleanupApi kbCacheCleanupApi;

    private final KnowledgeBaseApi knowledgeBaseApi;

    /** 在飞任务注册表（rag BC 实现）：清退收尾（成功收口或失败留痕已收敛）时移除本实例的在飞成员 */
    private final InFlightTaskRegistry inFlightTaskRegistry;

    /**
     * 构造整库清退编排服务。
     *
     * @param kbCleanupWriter            知识库清退回写契约（资产回收 + 自有数据分批清退）
     * @param entityNodeGraphRepository  实体图行仓储（rag 自有，按 kbId 物理批删）
     * @param relationEdgeGraphRepository 关系图行仓储（rag 自有，按 kbId 物理批删）
     * @param entityInfoVectorRepository 实体向量仓储（rag 自有，按 kbId 物理批删）
     * @param relationInfoVectorRepository 关系向量仓储（rag 自有，按 kbId 物理批删）
     * @param kbCacheCleanupApi          LLM 缓存按库整清契约（非关键步骤）
     * @param knowledgeBaseApi           知识库契约（清退完成后经条件 DELETE 收口物理删行 / 失败留痕）
     * @param inFlightTaskRegistry       在飞任务注册表（清退收尾时移除本实例的整库清退在飞成员）
     */
    public KbCleanupOrchestrationService(KbCleanupWriter kbCleanupWriter,
                                         EntityNodeGraphRepository entityNodeGraphRepository,
                                         RelationEdgeGraphRepository relationEdgeGraphRepository,
                                         EntityInfoVectorRepository entityInfoVectorRepository,
                                         RelationInfoVectorRepository relationInfoVectorRepository,
                                         KbCacheCleanupApi kbCacheCleanupApi,
                                         KnowledgeBaseApi knowledgeBaseApi,
                                         InFlightTaskRegistry inFlightTaskRegistry) {
        this.kbCleanupWriter = kbCleanupWriter;
        this.entityNodeGraphRepository = entityNodeGraphRepository;
        this.relationEdgeGraphRepository = relationEdgeGraphRepository;
        this.entityInfoVectorRepository = entityInfoVectorRepository;
        this.relationInfoVectorRepository = relationInfoVectorRepository;
        this.kbCacheCleanupApi = kbCacheCleanupApi;
        this.knowledgeBaseApi = knowledgeBaseApi;
        this.inFlightTaskRegistry = inFlightTaskRegistry;
    }

    /**
     * 清退单个知识库（删除清退虚拟线程内同步执行，本方法永不抛出）。
     * <p>按「Storage 先删、DB 后删」次序推进；任一关键步失败即时 {@code markFailed} 并终止本任务
     * （零重试），非关键的缓存步骤失败仅日志、继续收口。
     * 收尾处按「状态已收敛」语义移除在飞注册表成员：成功收口后移除；关键步失败仅在留痕成功
     * （或未命中的幂等空转）后移除，留痕回调异常时保留成员由下次启动重试；{@code kbId} 为空提前
     * 返回的分支不移除成员。</p>
     *
     * @param kbId 待清退知识库主键；为空按无操作处理（仅 WARN 留痕）
     */
    public void cleanup(Long kbId) {
        if (ObjectUtils.isEmpty(kbId)) {
            log.warn("[KB-CLEANUP] kbId 为空，放弃本次清退任务");
            return;
        }
        long startedAt = System.currentTimeMillis();
        log.info("[KB-CLEANUP] kbId={} 清退任务开始", kbId);
        if (!runCriticalStep(kbId, STEP_ASSETS, () -> kbCleanupWriter.cleanupAssetsByKnowledgeBase(kbId))) {
            return;
        }
        if (!runCriticalStep(kbId, STEP_DERIVED_DATA, () -> drainDerivedData(kbId))) {
            return;
        }
        if (!runCriticalStep(kbId, STEP_DOCUMENTS, () -> drainDocuments(kbId))) {
            return;
        }
        if (!runCriticalStep(kbId, STEP_GRAPH, () -> cleanupGraph(kbId))) {
            return;
        }
        cleanupCacheQuietly(kbId);
        if (!runCriticalStep(kbId, STEP_FINAL_DELETE, () -> convergeFinalDelete(kbId))) {
            return;
        }
        log.info("[KB-CLEANUP] kbId={} 清退完成，收口条件 DELETE 已删除知识库行, elapsedMillis={}",
                kbId, System.currentTimeMillis() - startedAt);
        // 收口完成（行已物理删除，或条件 DELETE 零行命中＝已收口）→ 状态已收敛，移除在飞成员
        unregisterQuietly(kbId);
    }

    /**
     * 派生数据分批清退：循环调用直至契约返回 0（表示该库切片 + 表示已清空）。
     */
    private void drainDerivedData(Long kbId) {
        int rounds = 0;
        int deleted;
        do {
            deleted = kbCleanupWriter.cleanupDerivedDataBatch(kbId, effectiveBatchSize());
            rounds++;
        } while (deleted > 0);
        log.info("[KB-CLEANUP] kbId={} step={} 派生数据清退轮次={}", kbId, STEP_DERIVED_DATA, rounds);
    }

    /**
     * 文档分批清退：循环调用直至契约返回 0（表示该库文档已清空）。
     */
    private void drainDocuments(Long kbId) {
        int rounds = 0;
        int deleted;
        do {
            deleted = kbCleanupWriter.cleanupDocumentsBatch(kbId, effectiveBatchSize());
            rounds++;
        } while (deleted > 0);
        log.info("[KB-CLEANUP] kbId={} step={} 文档清退轮次={}", kbId, STEP_DOCUMENTS, rounds);
    }

    /**
     * 图谱四表按 kbId 物理批删（固定语句顺序「图行 → 向量行」，与各仓储内部分片自提交循环配合）。
     * <p>编排层 MUST NOT 再包大事务；重复执行第二次起零影响（幂等）。</p>
     */
    private void cleanupGraph(Long kbId) {
        int entityGraphRows = entityNodeGraphRepository.deleteByKbId(kbId);
        int relationGraphRows = relationEdgeGraphRepository.deleteByKbId(kbId);
        int entityVectorRows = entityInfoVectorRepository.deleteByKbId(kbId);
        int relationVectorRows = relationInfoVectorRepository.deleteByKbId(kbId);
        log.info("[KB-CLEANUP] kbId={} step={} 图谱清退实体图行={}, 关系图行={}, 实体向量={}, 关系向量={}",
                kbId, STEP_GRAPH, entityGraphRows, relationGraphRows, entityVectorRows, relationVectorRows);
    }

    /**
     * 静默整清该库 LLM 缓存（非关键步骤：失败仅 WARN 留痕，MUST NOT 阻断收口）。
     *
     * @param kbId 知识库主键
     */
    private void cleanupCacheQuietly(Long kbId) {
        try {
            kbCacheCleanupApi.deleteCachesByKnowledgeBase(kbId);
        } catch (RuntimeException e) {
            log.warn("[KB-CLEANUP] kbId={} step={} LLM 缓存清退失败（非关键步骤，不阻断收口），"
                    + "残留缓存由下次重删续跑清理", kbId, STEP_CACHE, e);
        }
    }

    /**
     * 生命周期收口：条件物理 DELETE 删除知识库行（0 行命中＝已收口，幂等空转不视为失败）。
     *
     * @param kbId 知识库主键
     */
    private void convergeFinalDelete(Long kbId) {
        boolean hit = knowledgeBaseApi.executeDelete(kbId);
        if (!hit) {
            log.info("[KB-CLEANUP] kbId={} step={} 收口条件 DELETE 零行命中（行已不存在＝已收口），幂等空转",
                    kbId, STEP_FINAL_DELETE);
        }
    }

    /**
     * 执行关键步骤（<b>零重试</b>）：一次即定成败，失败即时 {@code markFailed} 留痕并放弃本任务。
     * <p>失败落点：置 DELETE_FAILED + {@code [KB-CLEANUP] step=…} 留痕，用户重删从断点续跑；
     * 已成功批次不回滚。留痕回调自身失败（如数据库整体不可用）仅 ERROR 留痕并保留在飞成员——
     * 库停留 DELETING，由本实例启动恢复按状态一致性校验收敛为 {@code DELETE_FAILED} 或用户重删兜底
     * （不再自动重触发续跑）。</p>
     *
     * @param kbId 知识库主键（留痕与日志归因）
     * @param step 步骤名（留痕与日志归因，运维据此定位失败环节）
     * @param unit 步骤执行体
     * @return {@code true} 表示步骤成功；{@code false} 表示步骤失败（已留痕），调用方须放弃本任务
     */
    private boolean runCriticalStep(Long kbId, String step, Runnable unit) {
        try {
            unit.run();
            return true;
        } catch (RuntimeException e) {
            log.error("[KB-CLEANUP] kbId={} step={} 失败，即时置 DELETE_FAILED 并终止本任务（零重试）", kbId, step, e);
            if (markFailedQuietly(kbId, buildFailureTrace(step, e))) {
                // 状态已收敛（DELETE_FAILED 已写入，或未被 CAS 命中的幂等空转）→ 移除在飞成员
                unregisterQuietly(kbId);
            }
            return false;
        }
    }

    /**
     * 静默回调失败留痕端口（DELETING → DELETE_FAILED + error_message）。
     *
     * @param kbId   知识库主键
     * @param reason 留痕文案（含步骤标识与异常摘要）
     * @return {@code true} 表示状态已收敛（留痕正常返回，含未被 CAS 命中的幂等空转）；
     *         {@code false} 表示留痕回调异常（状态未收敛，调用方 MUST NOT 移除在飞成员）
     */
    private boolean markFailedQuietly(Long kbId, String reason) {
        try {
            knowledgeBaseApi.markFailed(kbId, reason);
            return true;
        } catch (RuntimeException e) {
            log.error("[KB-CLEANUP] kbId={} 失败留痕回调异常，库停留 DELETING，保留在飞成员待下次启动重试, reason={}",
                    kbId, reason, e);
            return false;
        }
    }

    /**
     * 静默移除整库清退任务的在飞成员（异常仅 ERROR 留痕，MUST NOT 上抛）。
     *
     * @param kbId 知识库主键
     */
    private void unregisterQuietly(Long kbId) {
        try {
            inFlightTaskRegistry.unregister(InFlightTaskType.KB_CLEANUP, kbId);
        } catch (RuntimeException e) {
            log.error("[KB-CLEANUP] kbId={} 在飞成员移除失败（残留凭据由下次启动收敛兜底）", kbId, e);
        }
    }

    /**
     * 构造清退失败留痕文案：{@code [KB-CLEANUP] step=<步骤名>: <异常摘要>}。
     *
     * @param step 步骤名
     * @param e    失败异常
     * @return 留痕文案（异常无消息时以异常类名兜底，保证步骤标识恒在）
     */
    private String buildFailureTrace(String step, RuntimeException e) {
        String detail = StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName());
        return FAILURE_TRACE_PREFIX + " step=" + step + ": " + detail;
    }

    /**
     * 解析生效的清退批次上限：非正数回落缺省值。
     *
     * @return 生效批次条数
     */
    private int effectiveBatchSize() {
        return batchSize < 1 ? DEFAULT_BATCH_SIZE : batchSize;
    }
}
