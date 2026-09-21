package com.linkroa.deepdataagent.rag.application.service;

import com.linkroa.deepdataagent.knowledgebase.api.DocumentGraphConvergenceResult;
import com.linkroa.deepdataagent.knowledgebase.api.KnowledgeBaseApi;
import com.linkroa.deepdataagent.knowledgebase.domain.model.EntityType;
import com.linkroa.deepdataagent.rag.application.service.DocumentGraphConvergenceService.ConvergencePlan;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link DocumentGraphConvergenceService} 两段式收敛服务单测（OpenSpec rebuild-kg-on-document-delete
 * / 组 6，spec graph-contribution-rebuild R5「删除链顺序与幂等重入」全部 Scenario 与
 * extract-cache-attribution R2「按引用计数清理缓存行」全部 Scenario 的收敛层覆盖）。
 * <p>覆盖：prepare 全程零写入（任何收缩/删除/回收交互 never，远程计算经重建服务阶段 A）、
 * apply 单事务四步固定序（重建写回 → 账本收缩 → 展示列收缩 → 缓存回收四步严格顺序）、
 * 缓存回收「先追溯 → 删归属 → 引用判定 → 只删归零」与共用键保留 / 归零回收 / 无归属零删除 /
 * 仅限本 kbId、事务内失败整体上抛（回滚语义由事务模板保证）、重复执行收敛（第二次起零影响）、
 * 重建能力缺失降级（跳过重建但收缩与回收照常）、收口仅在有清单时触达。</p>
 * <p><b>并发 Scenario 的落地点说明</b>：spec「两个文档共享条目并发删除最终不残留 / 删除与摄入
 * 并发账本等于真实存活集合」由组 4 写回纪律（加锁重读 → 锁定账本重算绝对值）保证，
 * {@code GraphContributionRebuildServiceTest} 已以确定性 mock 编排覆盖；本层以
 * {@link #should_keepSharedKeyThenReclaimAfterSecondBatch_given_twoBatchesSharingOneKey} 做
 * 「两批顺序删除 → 共用键先保留后回收」的「锁定账本重算」近似覆盖（纯单测无法真实并发）。</p>
 *
 * @author DeepDataAgent
 */
@ExtendWith(MockitoExtension.class)
class DocumentGraphConvergenceServiceTest {

    /** 测试用知识库ID（扫描面与回收隔离维度） */
    private static final Long KB_ID = 7L;

    /** 测试用审计锚点文档ID */
    private static final Long DOC_ID = 100L;

    /** 被清退的切片ID批次（升序去重形态） */
    private static final List<Long> REMOVED_CHUNK_IDS = List.of(101L, 102L);

    /** 共用缓存键K（与抽取键形态同构的 32 位 hex） */
    private static final String KEY_K = "0123456789abcdef0123456789abcdef";

    /** 归零可回收缓存键 */
    private static final String KEY_GONE = "89abcdef0123456789abcdef01234567";

    /** 摘要模型 profileId（库级配置桩值） */
    private static final String SUMMARY_PROFILE = "kb-llm-profile-1";

    /** 向量模型 profileId（库级配置桩值） */
    private static final String EMBED_PROFILE = "embed-profile-1";

    /** 操作人 */
    private static final String OPERATOR = "tester";

    @Mock
    private GraphContributionRebuildService rebuildService;
    @Mock
    private EntityInfoVectorRepository entityInfoVectorRepository;
    @Mock
    private RelationInfoVectorRepository relationInfoVectorRepository;
    @Mock
    private EntityNodeGraphRepository entityNodeGraphRepository;
    @Mock
    private RelationEdgeGraphRepository relationEdgeGraphRepository;
    @Mock
    private ChunkExtractCacheRepository chunkExtractCacheRepository;
    @Mock
    private LlmCacheRepository llmCacheRepository;
    @Mock
    private KnowledgeBaseApi knowledgeBaseApi;
    @Mock
    private TransactionTemplate transactionTemplate;

    private DocumentGraphConvergenceService service;

    /**
     * 事务模板透传桩 + 应用级配置注入；lenient 兼容短路用例（不开事务、不读配置）。
     */
    @BeforeEach
    void setUp() {
        service = new DocumentGraphConvergenceService(rebuildService, entityInfoVectorRepository,
                relationInfoVectorRepository, entityNodeGraphRepository, relationEdgeGraphRepository,
                chunkExtractCacheRepository, llmCacheRepository, knowledgeBaseApi, transactionTemplate,
                new ObjectMapper());
        ReflectionTestUtils.setField(service, "extractionJsonMode", true);
        ReflectionTestUtils.setField(service, "graphSourceIdsLimit", 200);
        ReflectionTestUtils.setField(service, "graphSourceIdsTruncation", "KEEP");
        ReflectionTestUtils.setField(service, "graphSourceFilePathsLimit", 75);
        ReflectionTestUtils.setField(service, "graphSourceFilePathsPlaceholder", "…等");
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        lenient().when(knowledgeBaseApi.findMediaModelProfileIdByKbId(KB_ID)).thenReturn(SUMMARY_PROFILE);
        lenient().when(knowledgeBaseApi.findEmbeddingModelProfileIdByKbId(KB_ID)).thenReturn(EMBED_PROFILE);
        lenient().when(knowledgeBaseApi.findLanguageByKbId(KB_ID)).thenReturn("Chinese");
        lenient().when(knowledgeBaseApi.findEntityTypesByKbId(KB_ID))
                .thenReturn(List.of(new EntityType("Person")));
        lenient().when(knowledgeBaseApi.findRagEngineConfigJsonByKbId(KB_ID)).thenReturn(null);
    }

    /**
     * 场景（converge-rag-hot-path-object-creation / 5.6）：{@code @Value} 注入完成后多次取
     * 应用级图合并参数基底（与摄入端同口径的收敛侧基底）。
     * 预期：基底与注入配置等值且多次调用返回同一实例（底层不重复构造）；
     * 缓存成形后字段再变化仍返回同一缓存实例（钉死「应用级配置启动后不变、不支持热更新」前提）。
     */
    @Test
    void should_reuseSingleAppLevelParamsInstance_when_appLevelGraphMergeParams_given_repeatedCallsAfterInjection() {
        // given：setUp 已完成 @Value 注入态模拟（200 / KEEP / 75 / …等）

        // when：重复取应用级基底
        GraphMergeParams first = ReflectionTestUtils.invokeMethod(service, "appLevelGraphMergeParams");
        GraphMergeParams second = ReflectionTestUtils.invokeMethod(service, "appLevelGraphMergeParams");

        // then：与注入配置等值，且复用同一实例（不重复构造）
        assertEquals(200, first.applySourceIdsLimit());
        assertEquals("KEEP", first.sourceIdsTruncation());
        assertEquals(75, first.sourceFilePathsLimit());
        assertEquals("…等", first.sourceFilePathsPlaceholder());
        assertSame(first, second, "收敛侧应用级基底应注入后构造一次并缓存复用");

        // when：缓存成形后修改 @Value 字段（热更新场景本实现不支持）
        ReflectionTestUtils.setField(service, "graphSourceIdsLimit", 1);
        GraphMergeParams third = ReflectionTestUtils.invokeMethod(service, "appLevelGraphMergeParams");

        // then：仍返回缓存实例，不随字段修改重建
        assertSame(first, third, "应用级配置以启动后不变为前提，缓存不随字段漂移重建");
    }

    /**
     * 构造真实重建上下文（组 4 值对象，字段形态与生产一致）。
     *
     * @return 重建上下文
     */
    private GraphRebuildContext context() {
        return new GraphRebuildContext(KB_ID, DOC_ID, List.of(new EntityType("Person")), true,
                SUMMARY_PROFILE, EMBED_PROFILE, "Chinese", GraphMergeParams.defaults(), OPERATOR, null);
    }

    /**
     * 构造就绪句柄（含上下文与计划）。
     *
     * @param plan 重建计划
     * @return 收敛计划句柄
     */
    private ConvergencePlan readyPlan(GraphRebuildPlan plan) {
        return new ConvergencePlan(KB_ID, REMOVED_CHUNK_IDS, context(), plan);
    }

    // ================================================================ prepare（事务外零写入）

    /**
     * 场景（spec R5「重建计算后崩溃可重试」+「阶段A 全程零写入」）：prepare 完成重建计算。
     * 预期：库级配置经 KnowledgeBaseApi 只读通道取齐并组装进上下文；重建服务只被调用 compute；
     * 事务模板与全部收缩/删除/回收仓储零交互——此时崩溃零写入残留。
     */
    @Test
    void should_assembleContextAndComputeWithZeroWrites_when_prepare_given_nonEmptyBatch() {
        // given
        when(rebuildService.compute(any(), eq(REMOVED_CHUNK_IDS))).thenReturn(GraphRebuildPlan.empty(REMOVED_CHUNK_IDS));

        // when
        ConvergencePlan plan = service.prepare(KB_ID, DOC_ID, REMOVED_CHUNK_IDS, OPERATOR);

        // then：上下文由只读通道组装（模型引用/语言/实体类型/JSON模式/参数），批次升序归一透传
        ArgumentCaptor<GraphRebuildContext> ctxCaptor = ArgumentCaptor.forClass(GraphRebuildContext.class);
        verify(rebuildService).compute(ctxCaptor.capture(), eq(REMOVED_CHUNK_IDS));
        GraphRebuildContext ctx = ctxCaptor.getValue();
        assertEquals(KB_ID, ctx.kbId());
        assertEquals(DOC_ID, ctx.deletedDocumentId());
        assertEquals(SUMMARY_PROFILE, ctx.summaryModelProfileId());
        assertEquals(EMBED_PROFILE, ctx.embeddingModelProfileId());
        assertEquals("Chinese", ctx.language());
        assertEquals(List.of(new EntityType("Person")), ctx.entityTypes());
        assertTrue(ctx.jsonMode());
        assertEquals(OPERATOR, ctx.operator());
        assertFalse(plan.rebuildSkipped());
        // then：零写入、零事务（阶段 A 与事务之间崩溃即无任何残留，可重跑 prepare 重推同一批）
        verifyNoInteractions(transactionTemplate, entityInfoVectorRepository, relationInfoVectorRepository,
                entityNodeGraphRepository, relationEdgeGraphRepository, chunkExtractCacheRepository,
                llmCacheRepository);
        verify(rebuildService, never()).apply(any(), any());
    }

    /**
     * 场景（spec R5「重建计算后崩溃可重试」的重推面）：prepare 以同一批次重复执行。
     * 预期：两次均以相同（上下文，升序批次）调用 compute——受影响集合从「账本 ∩ 被删分块」
     * 重推导，不依赖句柄或任何日志；全程仍零写入。
     */
    @Test
    void should_recomputeSameAffectedBatch_when_prepare_given_retryAfterCrashBeforeApply() {
        // given
        when(rebuildService.compute(any(), anyList())).thenReturn(GraphRebuildPlan.empty(REMOVED_CHUNK_IDS));

        // when：崩溃重入——同参再跑一次 prepare
        ConvergencePlan first = service.prepare(KB_ID, DOC_ID, REMOVED_CHUNK_IDS, OPERATOR);
        ConvergencePlan second = service.prepare(KB_ID, DOC_ID, REMOVED_CHUNK_IDS, OPERATOR);

        // then：compute 恰两次、入参批次一致；无任何写入与事务交互
        ArgumentCaptor<List<Long>> batchCaptor = ArgumentCaptor.forClass(List.class);
        verify(rebuildService, times(2)).compute(any(), batchCaptor.capture());
        assertEquals(batchCaptor.getAllValues().get(0), batchCaptor.getAllValues().get(1));
        assertEquals(REMOVED_CHUNK_IDS, batchCaptor.getAllValues().get(0));
        assertFalse(first.rebuildSkipped() || second.rebuildSkipped());
        verifyNoInteractions(transactionTemplate, chunkExtractCacheRepository, llmCacheRepository);
    }

    /**
     * 场景（批次形态恒定）：入参含重复与 null 元素、乱序。
     * 预期：上下文组装照常，compute 收到去 null 去重升序后的批次——重入推导恒同集。
     */
    @Test
    void should_normalizeBatchAsc_when_prepare_given_dirtyUnorderedIds() {
        // given
        when(rebuildService.compute(any(), anyList())).thenReturn(GraphRebuildPlan.empty(List.of()));

        // when
        service.prepare(KB_ID, DOC_ID, Arrays.asList(102L, 101L, 102L, null), OPERATOR);

        // then
        ArgumentCaptor<List<Long>> batchCaptor = ArgumentCaptor.forClass(List.class);
        verify(rebuildService).compute(any(), batchCaptor.capture());
        assertEquals(REMOVED_CHUNK_IDS, batchCaptor.getValue());
    }

    /**
     * 场景（重建能力缺失降级）：库级摘要模型引用未配置（上下文构造抛参数异常）。
     * 预期：不上抛、产出跳过重建句柄；compute 零触达；收缩与回收不受影响（apply 用例另行断言）。
     */
    @Test
    void should_degradeToSkipRebuild_when_prepare_given_missingModelProfile() {
        // given
        when(knowledgeBaseApi.findMediaModelProfileIdByKbId(KB_ID)).thenReturn(null);

        // when
        ConvergencePlan plan = service.prepare(KB_ID, DOC_ID, REMOVED_CHUNK_IDS, OPERATOR);

        // then
        assertTrue(plan.rebuildSkipped());
        verify(rebuildService, never()).compute(any(), any());
        verifyNoInteractions(transactionTemplate, chunkExtractCacheRepository, llmCacheRepository);
    }

    /**
     * 场景（审计锚点缺失）：deletedDocumentId 为 null（并发下投影全缺）。
     * 预期：跳过重建句柄、零远程；删除链不阻断（apply 仍执行收缩与回收）。
     */
    @Test
    void should_degradeToSkipRebuild_when_prepare_given_nullAnchorDocument() {
        // when
        ConvergencePlan plan = service.prepare(KB_ID, null, REMOVED_CHUNK_IDS, OPERATOR);

        // then
        assertTrue(plan.rebuildSkipped());
        verifyNoInteractions(rebuildService, transactionTemplate, chunkExtractCacheRepository,
                llmCacheRepository, knowledgeBaseApi);
    }

    /**
     * 场景（防御短路）：kbId 为 null 或批次为空。
     * 预期：零句柄；不读任何库级配置、不触任何仓储与事务。
     */
    @Test
    void should_returnInertHandleWithZeroReads_when_prepare_given_nullKbIdOrEmptyBatch() {
        // when
        ConvergencePlan nullKb = service.prepare(null, DOC_ID, REMOVED_CHUNK_IDS, OPERATOR);
        ConvergencePlan emptyBatch = service.prepare(KB_ID, DOC_ID, List.of(), OPERATOR);

        // then
        assertTrue(nullKb.rebuildSkipped() && emptyBatch.rebuildSkipped());
        verifyNoInteractions(rebuildService, transactionTemplate, entityInfoVectorRepository,
                relationInfoVectorRepository, entityNodeGraphRepository, relationEdgeGraphRepository,
                chunkExtractCacheRepository, llmCacheRepository, knowledgeBaseApi);
    }

    // ================================================================ apply（事务内固定序）

    /**
     * 场景（spec R5 顺序不变量 + 6.1/6.2 执行序）：就绪句柄正常应用。
     * 预期：恰一个事务内按固定序执行——重建阶段 B → 两侧账本收缩 → 两侧展示列收缩 →
     * 缓存回收四步（追溯 → 删归属 → 引用判定 → 删归零行）；顺序任何颠倒即失败。
     */
    @Test
    void should_executeRebuildThenShrinkThenReclaimInFixedOrder_when_apply_given_readyPlan() {
        // given：重建写回有删除、追溯集含归零键与共用键（共用键判定仍被引用 → 保留）
        ConvergencePlan plan = readyPlan(new GraphRebuildPlan(REMOVED_CHUNK_IDS, List.of(), List.of(),
                List.of("张三"), List.of()));
        when(rebuildService.apply(any(), any())).thenReturn(new GraphRebuildReport(2, 1, 1, 0,
                List.of(), List.of()));
        when(entityInfoVectorRepository.removeChunkContributionsAndPrune(KB_ID, REMOVED_CHUNK_IDS)).thenReturn(0);
        when(relationInfoVectorRepository.removeChunkContributionsAndPrune(KB_ID, REMOVED_CHUNK_IDS)).thenReturn(0);
        when(entityNodeGraphRepository.shrinkDisplaySourceIds(KB_ID, REMOVED_CHUNK_IDS)).thenReturn(3);
        when(relationEdgeGraphRepository.shrinkDisplaySourceIds(KB_ID, REMOVED_CHUNK_IDS)).thenReturn(4);
        when(chunkExtractCacheRepository.findCacheKeysByChunkIds(KB_ID, CacheType.EXTRACT, REMOVED_CHUNK_IDS))
                .thenReturn(Map.of(101L, Set.of(KEY_K, KEY_GONE), 102L, Set.of(KEY_K)));
        when(chunkExtractCacheRepository.deleteByChunkIds(KB_ID, REMOVED_CHUNK_IDS)).thenReturn(3);
        when(chunkExtractCacheRepository.findReferencedKeys(KB_ID, CacheType.EXTRACT, Set.of(KEY_K, KEY_GONE)))
                .thenReturn(Set.of(KEY_K));
        when(llmCacheRepository.deleteByKbIdAndCacheKeys(KB_ID, CacheType.EXTRACT, List.of(KEY_GONE)))
                .thenReturn(1);

        // when
        DocumentGraphConvergenceResult result = service.apply(plan);

        // then：四步固定序 + 回收四步内部序（InOrder 断言回调体内真实执行序）
        InOrder order = inOrder(transactionTemplate, rebuildService, entityInfoVectorRepository,
                relationInfoVectorRepository, entityNodeGraphRepository, relationEdgeGraphRepository,
                chunkExtractCacheRepository, llmCacheRepository);
        order.verify(transactionTemplate, times(1)).execute(any());
        order.verify(rebuildService).apply(any(), any());
        order.verify(entityInfoVectorRepository).removeChunkContributionsAndPrune(KB_ID, REMOVED_CHUNK_IDS);
        order.verify(relationInfoVectorRepository).removeChunkContributionsAndPrune(KB_ID, REMOVED_CHUNK_IDS);
        order.verify(entityNodeGraphRepository).shrinkDisplaySourceIds(KB_ID, REMOVED_CHUNK_IDS);
        order.verify(relationEdgeGraphRepository).shrinkDisplaySourceIds(KB_ID, REMOVED_CHUNK_IDS);
        order.verify(chunkExtractCacheRepository).findCacheKeysByChunkIds(KB_ID, CacheType.EXTRACT, REMOVED_CHUNK_IDS);
        order.verify(chunkExtractCacheRepository).deleteByChunkIds(KB_ID, REMOVED_CHUNK_IDS);
        order.verify(chunkExtractCacheRepository).findReferencedKeys(KB_ID, CacheType.EXTRACT, Set.of(KEY_K, KEY_GONE));
        order.verify(llmCacheRepository).deleteByKbIdAndCacheKeys(KB_ID, CacheType.EXTRACT, List.of(KEY_GONE));
        // then：计数口径——收敛删除 = 重建转删除 1 + 兜底 0；共用键 K 保留、归零键回收 1 行
        assertEquals(1, result.prunedEntries());
        assertEquals(2, result.rebuiltEntries());
        assertEquals(1, result.degradedEntries());
        assertEquals(3, result.reclaimedAttributionRows());
        assertEquals(1, result.reclaimedCacheRows());
    }

    /**
     * 场景（spec R6「写回事务中崩溃整体回滚」的收敛层断言）：账本收缩语句抛运行时异常。
     * 预期：异常自事务回调整体上抛（回滚由事务模板承担）；其后的缓存回收零触达——
     * 失败批次不会留下「账本未收缩但缓存已清」的半态。
     */
    @Test
    void should_propagateFailureWithoutReclaim_when_apply_given_ledgerShrinkThrows() {
        // given
        ConvergencePlan plan = readyPlan(GraphRebuildPlan.empty(REMOVED_CHUNK_IDS));
        when(rebuildService.apply(any(), any())).thenReturn(GraphRebuildReport.empty());
        when(entityInfoVectorRepository.removeChunkContributionsAndPrune(KB_ID, REMOVED_CHUNK_IDS))
                .thenReturn(2);
        when(relationInfoVectorRepository.removeChunkContributionsAndPrune(KB_ID, REMOVED_CHUNK_IDS))
                .thenThrow(new RuntimeException("deadlock detected"));

        // when & then
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> service.apply(plan));
        assertEquals("deadlock detected", thrown.getMessage());
        verifyNoInteractions(chunkExtractCacheRepository, llmCacheRepository);
        verify(entityNodeGraphRepository, never()).shrinkDisplaySourceIds(any(), any());
    }

    /**
     * 场景（spec R6「重复执行收敛」）：同一句柄重复 apply 两次，第二次起语句零影响。
     * 预期：委托语句同式重放各两次；第二次全零计数（幂等真正由 Mapper SQL 交集谓词与归属表
     * 已清空保证，本层断言调用面零额外语义偏移）。
     */
    @Test
    void should_convergeToZero_when_apply_given_repeatedExecution() {
        // given：首次有删有收，第二次起零影响
        ConvergencePlan plan = readyPlan(GraphRebuildPlan.empty(REMOVED_CHUNK_IDS));
        when(rebuildService.apply(any(), any())).thenReturn(GraphRebuildReport.empty());
        when(entityInfoVectorRepository.removeChunkContributionsAndPrune(KB_ID, REMOVED_CHUNK_IDS))
                .thenReturn(2, 0);
        when(relationInfoVectorRepository.removeChunkContributionsAndPrune(KB_ID, REMOVED_CHUNK_IDS))
                .thenReturn(0, 0);
        when(entityNodeGraphRepository.shrinkDisplaySourceIds(KB_ID, REMOVED_CHUNK_IDS)).thenReturn(3, 0);
        when(relationEdgeGraphRepository.shrinkDisplaySourceIds(KB_ID, REMOVED_CHUNK_IDS)).thenReturn(4, 0);
        when(chunkExtractCacheRepository.findCacheKeysByChunkIds(KB_ID, CacheType.EXTRACT, REMOVED_CHUNK_IDS))
                .thenReturn(Map.of(101L, Set.of(KEY_GONE)), Map.of());
        when(chunkExtractCacheRepository.deleteByChunkIds(KB_ID, REMOVED_CHUNK_IDS)).thenReturn(1, 0);
        when(chunkExtractCacheRepository.findReferencedKeys(eq(KB_ID), eq(CacheType.EXTRACT), anyCollection()))
                .thenReturn(Set.of());
        when(llmCacheRepository.deleteByKbIdAndCacheKeys(eq(KB_ID), eq(CacheType.EXTRACT), anyList()))
                .thenReturn(1);

        // when
        DocumentGraphConvergenceResult first = service.apply(plan);
        DocumentGraphConvergenceResult second = service.apply(plan);

        // then
        assertEquals(2, first.prunedEntries());
        assertEquals(1, first.reclaimedCacheRows());
        assertEquals(0, second.prunedEntries());
        assertEquals(0, second.reclaimedAttributionRows());
        assertEquals(0, second.reclaimedCacheRows());
        verify(entityInfoVectorRepository, times(2)).removeChunkContributionsAndPrune(KB_ID, REMOVED_CHUNK_IDS);
        verify(chunkExtractCacheRepository, times(2)).deleteByChunkIds(KB_ID, REMOVED_CHUNK_IDS);
    }

    /**
     * 场景（重建能力缺失句柄）：apply 降级句柄。
     * 预期：重建阶段 B 零触达（对图谱零读写），但账本收缩、展示列收缩与缓存回收照常执行
     * ——删除链不因重建能力缺失而阻断。
     */
    @Test
    void should_skipRebuildButShrinkAndReclaim_when_apply_given_inertPlan() {
        // given
        ConvergencePlan plan = ConvergencePlan.inert(KB_ID, REMOVED_CHUNK_IDS);
        when(entityInfoVectorRepository.removeChunkContributionsAndPrune(KB_ID, REMOVED_CHUNK_IDS)).thenReturn(1);
        when(relationInfoVectorRepository.removeChunkContributionsAndPrune(KB_ID, REMOVED_CHUNK_IDS)).thenReturn(0);
        when(chunkExtractCacheRepository.findCacheKeysByChunkIds(eq(KB_ID), eq(CacheType.EXTRACT), anyList()))
                .thenReturn(Map.of());
        when(chunkExtractCacheRepository.deleteByChunkIds(KB_ID, REMOVED_CHUNK_IDS)).thenReturn(0);

        // when
        DocumentGraphConvergenceResult result = service.apply(plan);

        // then
        verifyNoInteractions(rebuildService);
        verify(entityNodeGraphRepository).shrinkDisplaySourceIds(KB_ID, REMOVED_CHUNK_IDS);
        verify(relationEdgeGraphRepository).shrinkDisplaySourceIds(KB_ID, REMOVED_CHUNK_IDS);
        assertEquals(1, result.prunedEntries());
    }

    /**
     * 场景（防御短路）：零句柄（kbId 缺失或批次为空）应用。
     * 预期：返回全零结果，不开事务、零 DB 交互。
     */
    @Test
    void should_returnEmptyResultWithoutTransaction_when_apply_given_inertZeroHandles() {
        // when
        DocumentGraphConvergenceResult nullKb = service.apply(ConvergencePlan.inert(null, List.of()));
        DocumentGraphConvergenceResult emptyBatch = service.apply(ConvergencePlan.inert(KB_ID, List.of()));

        // then
        assertEquals(DocumentGraphConvergenceResult.empty(), nullKb);
        assertEquals(DocumentGraphConvergenceResult.empty(), emptyBatch);
        verifyNoInteractions(transactionTemplate, rebuildService, entityInfoVectorRepository,
                relationInfoVectorRepository, entityNodeGraphRepository, relationEdgeGraphRepository,
                chunkExtractCacheRepository, llmCacheRepository);
    }

    /**
     * 场景（契约防御）：apply 入参句柄为 null。
     * 预期：抛非法参数异常，零事务零交互。
     */
    @Test
    void should_throwIllegalArgument_when_apply_given_nullPlan() {
        // when & then
        assertThrows(IllegalArgumentException.class, () -> service.apply(null));
        verifyNoInteractions(transactionTemplate, rebuildService);
    }

    // ================================================================ 缓存回收口径（spec R2）

    /**
     * 场景（spec「共用缓存行不误删」）：键 K 同时被本批分块 101 与存活分块 777 引用。
     * 预期：引用判定命中 K 仍被引用 → 缓存行保留（批量删除 never 或传入集不含 K）。
     */
    @Test
    void should_keepSharedCacheKey_when_apply_given_keyStillReferencedBySurvivingChunk() {
        // given
        ConvergencePlan plan = ConvergencePlan.inert(KB_ID, REMOVED_CHUNK_IDS);
        when(entityInfoVectorRepository.removeChunkContributionsAndPrune(eq(KB_ID), anyList())).thenReturn(0);
        when(relationInfoVectorRepository.removeChunkContributionsAndPrune(eq(KB_ID), anyList())).thenReturn(0);
        when(chunkExtractCacheRepository.findCacheKeysByChunkIds(KB_ID, CacheType.EXTRACT, REMOVED_CHUNK_IDS))
                .thenReturn(Map.of(101L, Set.of(KEY_K), 102L, Set.of(KEY_K)));
        when(chunkExtractCacheRepository.deleteByChunkIds(KB_ID, REMOVED_CHUNK_IDS)).thenReturn(2);
        when(chunkExtractCacheRepository.findReferencedKeys(KB_ID, CacheType.EXTRACT, Set.of(KEY_K)))
                .thenReturn(Set.of(KEY_K));

        // when
        DocumentGraphConvergenceResult result = service.apply(plan);

        // then：追溯命中、归属删除、判定仍引用 → 缓存行零删除
        verify(chunkExtractCacheRepository).findCacheKeysByChunkIds(KB_ID, CacheType.EXTRACT, REMOVED_CHUNK_IDS);
        verify(chunkExtractCacheRepository).deleteByChunkIds(KB_ID, REMOVED_CHUNK_IDS);
        verify(chunkExtractCacheRepository).findReferencedKeys(KB_ID, CacheType.EXTRACT, Set.of(KEY_K));
        verify(llmCacheRepository, never()).deleteByKbIdAndCacheKeys(any(), any(), any());
        assertEquals(2, result.reclaimedAttributionRows());
        assertEquals(0, result.reclaimedCacheRows());
    }

    /**
     * 场景（spec「不猜测性删除无归属缓存行」）：追溯集为空（本批无任何归属记录）。
     * 预期：引用判定与缓存批量删除均零触达——历史无归属缓存行 MUST 原样保留。
     */
    @Test
    void should_neverTouchCacheRows_when_apply_given_emptyAttributionTrace() {
        // given
        ConvergencePlan plan = ConvergencePlan.inert(KB_ID, REMOVED_CHUNK_IDS);
        when(entityInfoVectorRepository.removeChunkContributionsAndPrune(eq(KB_ID), anyList())).thenReturn(0);
        when(relationInfoVectorRepository.removeChunkContributionsAndPrune(eq(KB_ID), anyList())).thenReturn(0);
        when(chunkExtractCacheRepository.findCacheKeysByChunkIds(KB_ID, CacheType.EXTRACT, REMOVED_CHUNK_IDS))
                .thenReturn(Map.of());
        when(chunkExtractCacheRepository.deleteByChunkIds(KB_ID, REMOVED_CHUNK_IDS)).thenReturn(0);

        // when
        service.apply(plan);

        // then
        verify(chunkExtractCacheRepository, never()).findReferencedKeys(any(), any(), any());
        verify(llmCacheRepository, never()).deleteByKbIdAndCacheKeys(any(), any(), any());
    }

    /**
     * 场景（spec「两批顺序删除共用键」近似并发正确性）：键 K 由批次一与批次二（模拟两文档
     * 分块）共享——第一批删除后 K 仍被引用保留；第二批删除后引用归零被回收。
     * 预期：两批各自完成「追溯 → 删归属 → 判定」，仅第二批触发缓存删除且传入集恰为 {K}。
     */
    @Test
    void should_keepSharedKeyThenReclaimAfterSecondBatch_given_twoBatchesSharingOneKey() {
        // given：第一批 {101}（K 仍被 205 引用）、第二批 {205}（K 归零）
        ConvergencePlan first = ConvergencePlan.inert(KB_ID, List.of(101L));
        ConvergencePlan second = ConvergencePlan.inert(KB_ID, List.of(205L));
        when(entityInfoVectorRepository.removeChunkContributionsAndPrune(eq(KB_ID), anyList())).thenReturn(0);
        when(relationInfoVectorRepository.removeChunkContributionsAndPrune(eq(KB_ID), anyList())).thenReturn(0);
        when(chunkExtractCacheRepository.findCacheKeysByChunkIds(KB_ID, CacheType.EXTRACT, List.of(101L)))
                .thenReturn(Map.of(101L, Set.of(KEY_K)));
        when(chunkExtractCacheRepository.findCacheKeysByChunkIds(KB_ID, CacheType.EXTRACT, List.of(205L)))
                .thenReturn(Map.of(205L, Set.of(KEY_K)));
        when(chunkExtractCacheRepository.deleteByChunkIds(eq(KB_ID), anyList())).thenReturn(1);
        when(chunkExtractCacheRepository.findReferencedKeys(KB_ID, CacheType.EXTRACT, Set.of(KEY_K)))
                .thenReturn(Set.of(KEY_K), Set.of());
        when(llmCacheRepository.deleteByKbIdAndCacheKeys(KB_ID, CacheType.EXTRACT, List.of(KEY_K)))
                .thenReturn(1);

        // when
        DocumentGraphConvergenceResult firstResult = service.apply(first);
        DocumentGraphConvergenceResult secondResult = service.apply(second);

        // then：第一批保留、第二批回收——条目最终不残留（近似断言见类 Javadoc）
        assertEquals(0, firstResult.reclaimedCacheRows());
        assertEquals(1, secondResult.reclaimedCacheRows());
        verify(llmCacheRepository, times(1)).deleteByKbIdAndCacheKeys(KB_ID, CacheType.EXTRACT, List.of(KEY_K));
    }

    /**
     * 场景（spec「清理范围限定在本知识库」）：任意一次应用。
     * 预期：追溯 / 删归属 / 引用判定 / 缓存删除全部以入参 kbId 精确传参——跨库键不进入回收集
     * （仓储按 (kb_id, cache_type) 交集限定，本层锁定传参面）。
     */
    @Test
    void should_scopeAllReclaimCallsToKbId_when_apply_given_preparedPlan() {
        // given
        ConvergencePlan plan = ConvergencePlan.inert(KB_ID, REMOVED_CHUNK_IDS);
        when(entityInfoVectorRepository.removeChunkContributionsAndPrune(eq(KB_ID), anyList())).thenReturn(0);
        when(relationInfoVectorRepository.removeChunkContributionsAndPrune(eq(KB_ID), anyList())).thenReturn(0);
        when(chunkExtractCacheRepository.findCacheKeysByChunkIds(KB_ID, CacheType.EXTRACT, REMOVED_CHUNK_IDS))
                .thenReturn(Map.of(101L, Set.of(KEY_GONE)));
        when(chunkExtractCacheRepository.deleteByChunkIds(KB_ID, REMOVED_CHUNK_IDS)).thenReturn(1);
        when(chunkExtractCacheRepository.findReferencedKeys(KB_ID, CacheType.EXTRACT, Set.of(KEY_GONE)))
                .thenReturn(Set.of());
        when(llmCacheRepository.deleteByKbIdAndCacheKeys(KB_ID, CacheType.EXTRACT, List.of(KEY_GONE)))
                .thenReturn(1);

        // when
        service.apply(plan);

        // then：四步全部带 KB_ID 与 EXTRACT 分类，缓存删除入参集恰为归零键
        InOrder order = inOrder(chunkExtractCacheRepository, llmCacheRepository);
        order.verify(chunkExtractCacheRepository)
                .findCacheKeysByChunkIds(eq(KB_ID), eq(CacheType.EXTRACT), eq(REMOVED_CHUNK_IDS));
        order.verify(chunkExtractCacheRepository).deleteByChunkIds(eq(KB_ID), eq(REMOVED_CHUNK_IDS));
        order.verify(chunkExtractCacheRepository)
                .findReferencedKeys(eq(KB_ID), eq(CacheType.EXTRACT), eq(Set.of(KEY_GONE)));
        order.verify(llmCacheRepository)
                .deleteByKbIdAndCacheKeys(eq(KB_ID), eq(CacheType.EXTRACT), eq(List.of(KEY_GONE)));
    }

    // ================================================================ 提交后收口

    /**
     * 场景（阶段 C 触发条件）：结果含待收口清单 → 以句柄上下文交重建服务收口；清单为空 → 零触达。
     */
    @Test
    void should_delegateReconcileOnlyWhenPendingListNonEmpty_when_reconcile_given_resultVariants() {
        // given
        GraphRebuildContext ctx = context();
        ConvergencePlan plan = new ConvergencePlan(KB_ID, REMOVED_CHUNK_IDS, ctx,
                GraphRebuildPlan.empty(REMOVED_CHUNK_IDS));
        DocumentGraphConvergenceResult withPending = new DocumentGraphConvergenceResult(0, 1, 0, 0, 0, 0,
                List.of("张三"), List.of(List.of("A", "B")));
        DocumentGraphConvergenceResult noPending = DocumentGraphConvergenceResult.empty();

        // when
        service.reconcilePendingVectorContent(plan, noPending);
        verifyNoInteractions(rebuildService);
        service.reconcilePendingVectorContent(plan, withPending);

        // then：以同一上下文与清单收口
        ArgumentCaptor<GraphRebuildContext> ctxCaptor = ArgumentCaptor.forClass(GraphRebuildContext.class);
        ArgumentCaptor<GraphRebuildReport> reportCaptor = ArgumentCaptor.forClass(GraphRebuildReport.class);
        verify(rebuildService).reconcilePendingVectorContent(ctxCaptor.capture(), reportCaptor.capture());
        assertSame(ctx, ctxCaptor.getValue());
        assertEquals(List.of("张三"), reportCaptor.getValue().pendingVectorSyncEntityNames());
        assertEquals(List.of(List.of("A", "B")), reportCaptor.getValue().pendingVectorSyncRelationPairs());
    }

    /**
     * 场景（收口防御面）：null 句柄 / 降级句柄 / null 结果均零操作。
     */
    @Test
    void should_doNothing_when_reconcile_given_nullOrDegradedInputs() {
        // when
        service.reconcilePendingVectorContent(null, DocumentGraphConvergenceResult.empty());
        service.reconcilePendingVectorContent(ConvergencePlan.inert(KB_ID, REMOVED_CHUNK_IDS),
                new DocumentGraphConvergenceResult(0, 0, 0, 0, 0, 0, List.of("张三"), List.of()));
        service.reconcilePendingVectorContent(readyPlan(GraphRebuildPlan.empty(REMOVED_CHUNK_IDS)), null);

        // then
        verifyNoInteractions(rebuildService);
        verifyNoMoreInteractions(rebuildService);
    }
}
