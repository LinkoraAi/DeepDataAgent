package com.linkroa.deepdataagent.rag.domain.service;

import com.linkroa.deepdataagent.knowledgebase.api.KnowledgeBaseApi;
import com.linkroa.deepdataagent.knowledgebase.domain.model.Chunk;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.ChunkContentType;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.ChunkSource;
import com.linkroa.deepdataagent.rag.domain.model.ContentBlockVO;
import com.linkroa.deepdataagent.rag.domain.model.EntityInfoVector;
import com.linkroa.deepdataagent.rag.domain.model.EntityLedgerSnapshot;
import com.linkroa.deepdataagent.rag.domain.model.EntityNode;
import com.linkroa.deepdataagent.rag.domain.model.EntityProperties;
import com.linkroa.deepdataagent.rag.domain.model.RelationEdge;
import com.linkroa.deepdataagent.rag.domain.model.RelationInfoVector;
import com.linkroa.deepdataagent.rag.domain.model.RelationLedgerSnapshot;
import com.linkroa.deepdataagent.rag.domain.model.RelationProperties;
import com.linkroa.deepdataagent.rag.domain.port.EmbeddingClient;
import com.linkroa.deepdataagent.rag.domain.port.LlmChatRequest;
import com.linkroa.deepdataagent.rag.domain.port.LlmChatResult;
import com.linkroa.deepdataagent.rag.domain.port.LlmClient;
import com.linkroa.deepdataagent.rag.domain.repository.EntityInfoVectorRepository;
import com.linkroa.deepdataagent.rag.domain.repository.EntityNodeGraphRepository;
import com.linkroa.deepdataagent.rag.domain.repository.RelationEdgeGraphRepository;
import com.linkroa.deepdataagent.rag.domain.repository.RelationInfoVectorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link GraphContributionRebuildService} 单元测试（spec graph-contribution-rebuild R1~R4，
 * tasks 4.9；纯领域离线测试，全端口 Mockito 模拟，不启动容器/数据库）。
 *
 * <p>覆盖场景：账本重叠分类（重建 / 直接删除 / 未触及零读写 / 全人工分块整体跳过）、
 * 描述重算（含 8 条碎片真实触发 LLM 摘要——以真实 {@link DescriptionSummarizer} + Mock
 * {@link LlmClient} 端到端锁定「阈值在本路径真实生效」）、实体类型票数众数、
 * <b>权重回扣（账本 {101,205} 删 101 → 1.0）</b>、关键词去重字典序、来源路径统一归一、
 * 降级（缓存缺失保留语义字段 + 计入降级报告 + 不抛异常）、写回纪律（加锁重读以锁定账本重算、
 * 并发并入来源不被覆盖、存活转空删除、已不存在不复活、绝对值零增量）、
 * 阶段 A 零写入 / 阶段 B 零远程、语义降级与运行失败严格分开（存储异常照实上抛）、
 * 空/null 入参短路、向量内容-最终描述一致与收口清单、提交后收口委托。</p>
 *
 * @author DeepDataAgent
 */
@ExtendWith(MockitoExtension.class)
class GraphContributionRebuildServiceTest {

    /** 测试知识库ID */
    private static final Long KB_ID = 1L;

    /** 被删文档ID（重建上下文审计锚点） */
    private static final Long DELETED_DOC_ID = 11L;

    /** 存活分块所属文档ID */
    private static final Long SURVIVING_DOC_ID = 22L;

    /** 摘要模型 profileId */
    private static final String SUMMARY_PROFILE = "model-summary";

    /** 向量化模型 profileId */
    private static final String EMBED_PROFILE = "model-embed";

    /** 操作人 */
    private static final String OPERATOR = "tester";

    /** 实体名 Alice（字典序小于 Bob） */
    private static final String ALICE = "Alice";

    /** 实体名 Bob */
    private static final String BOB = "Bob";

    /** 未被触及的实体名 */
    private static final String GHOST = "Ghost";

    /** 被删分块ID */
    private static final Long DELETED_CHUNK = 101L;

    /** 存活分块ID */
    private static final Long SURVIVOR_CHUNK = 205L;

    /** 第二个存活分块ID */
    private static final Long SURVIVOR_CHUNK_2 = 206L;

    /** 并发并入的存活分块ID */
    private static final Long CONCURRENT_CHUNK = 300L;

    /** 被删文档来源路径 */
    private static final String DELETED_FILE = "doc-a.txt";

    /** 存活文档来源路径 */
    private static final String SURVIVING_FILE = "doc-b.txt";

    /** 必须保持不被清空的既有来源路径（spec graph-source-file-paths R3 场景） */
    private static final String KEEP_FILE = "keep-me.txt";

    /** 向量化桩返回向量 */
    private static final float[] EMBEDDED = new float[]{0.1f, 0.2f};

    /** 快照/锁定向量行现值向量 */
    private static final float[] OLD_VECTOR = new float[]{0.9f, 0.8f};

    /** 抽取缓存重放入口 Mock */
    @Mock
    private EntityExtractionService entityExtractionService;

    /** 知识库只读契约 Mock */
    @Mock
    private KnowledgeBaseApi knowledgeBaseApi;

    /** 实体图行仓储 Mock */
    @Mock
    private EntityNodeGraphRepository entityNodeRepository;

    /** 关系图行仓储 Mock */
    @Mock
    private RelationEdgeGraphRepository relationEdgeRepository;

    /** 实体向量仓储（账本权威）Mock */
    @Mock
    private EntityInfoVectorRepository entityInfoVectorRepository;

    /** 关系向量仓储（账本权威）Mock */
    @Mock
    private RelationInfoVectorRepository relationInfoVectorRepository;

    /** 描述摘要器 Mock */
    @Mock
    private DescriptionSummarizer descriptionSummarizer;

    /** 向量化端口 Mock */
    @Mock
    private EmbeddingClient embeddingClient;

    /** Token 计数器 Mock */
    @Mock
    private TokenCounter tokenCounter;

    /** 向量内容收口原语 Mock */
    @Mock
    private VectorContentReconciler vectorContentReconciler;

    /** LLM 对话端口 Mock（真实摘要器场景使用） */
    @Mock
    private LlmClient llmClient;

    /** 被测重建服务（默认装配 Mock 摘要器） */
    private GraphContributionRebuildService service;

    /** 当前装配的摘要器（默认 Mock；阈值真实生效用例替换为真实实例） */
    private DescriptionSummarizer summarizerUnderTest;

    /** 同步执行器（扇出任务当前线程执行，断言确定性） */
    private final Executor directExecutor = task -> task.run();

    @BeforeEach
    void setUp() {
        summarizerUnderTest = descriptionSummarizer;
        service = newService(summarizerUnderTest);
        lenient().when(tokenCounter.truncate(anyString(), anyInt())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    /**
     * 以给定摘要器装配被测服务（其余端口沿用 Mock；ObjectMapper/执行器用真实轻量实例）。
     *
     * @param summarizer 摘要器实例
     * @return 重建服务
     */
    private GraphContributionRebuildService newService(DescriptionSummarizer summarizer) {
        return new GraphContributionRebuildService(entityExtractionService, knowledgeBaseApi,
                entityNodeRepository, relationEdgeRepository, entityInfoVectorRepository,
                relationInfoVectorRepository, summarizer, embeddingClient, tokenCounter,
                vectorContentReconciler, new ObjectMapper(), directExecutor);
    }

    /** 默认重建上下文（库级配置取默认参数、无取消器）。 */
    private GraphRebuildContext context() {
        return new GraphRebuildContext(KB_ID, DELETED_DOC_ID, List.of(), false, SUMMARY_PROFILE,
                EMBED_PROFILE, "Chinese", GraphMergeParams.defaults(), OPERATOR, null);
    }

    /** 实体账本重叠快照桩。 */
    private EntityLedgerSnapshot entitySnapshot(String name, List<Long> ledger, String content,
                                                float[] vector, EntityProperties properties) {
        return new EntityLedgerSnapshot(KB_ID, name, ledger, content, vector, properties);
    }

    /** 关系账本重叠快照桩。 */
    private RelationLedgerSnapshot relationSnapshot(String source, String target, List<Long> ledger,
                                                    RelationProperties properties) {
        return new RelationLedgerSnapshot(KB_ID, source, target, ledger, "rel-content", OLD_VECTOR, properties);
    }

    /** 存活分块行桩（纯文本块 + 来源文件名）。 */
    private Chunk survivingChunk(long chunkId, String sourceFileName) {
        return new Chunk(chunkId, KB_ID, SURVIVING_DOC_ID, 1, 10, "chunk-text-" + chunkId, null,
                ChunkContentType.TEXT, sourceFileName, null, ChunkSource.PARSED,
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")), OffsetDateTime.now(ZoneId.of("Asia/Shanghai")));
    }

    /**
     * 存活分块行桩（携带 {@code original_item} meta JSON，驱动解析常量路径）。
     *
     * @param chunkId        分块主键
     * @param sourceFileName 来源文件名
     * @param originalItem   块 meta JSON 原文
     * @return 存活分块行
     */
    private Chunk survivingChunkWithMeta(long chunkId, String sourceFileName, String originalItem) {
        return new Chunk(chunkId, KB_ID, SURVIVING_DOC_ID, 1, 10, "chunk-text-" + chunkId, originalItem,
                ChunkContentType.TEXT, sourceFileName, null, ChunkSource.PARSED,
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")), OffsetDateTime.now(ZoneId.of("Asia/Shanghai")));
    }

    /** 重放实体记录桩（与抽取出口同形：sourceIds 单元素、类型票单票、描述单碎片）。 */
    private EntityNode replayEntity(Long chunkId, String name, String type, String description) {
        return new EntityNode(KB_ID, name, new EntityProperties(type, description, List.of(chunkId),
                (String) null, Map.of(type, 1), List.of(description)));
    }

    /** 重放关系边桩（一记录一边、来源单分块、weight 该记录全额）。 */
    private RelationEdge replayEdge(Long chunkId, String source, String target, double weight,
                                    List<String> keywords, String description) {
        return new RelationEdge(KB_ID, source, target, new RelationProperties(weight, description,
                keywords, List.of(chunkId), (String) null));
    }

    /** 图行实体节点桩。 */
    private EntityNode graphNode(String name, EntityProperties properties) {
        return new EntityNode(KB_ID, name, properties);
    }

    /** 向量行实体向量桩（restore 形态携带锁定读值）。 */
    private EntityInfoVector lockedEntityVector(String name, List<Long> chunkIds, String content, float[] vector) {
        return EntityInfoVector.restore(5L, KB_ID, name, content, chunkIds, vector, null, null);
    }

    /**
     * 端到端锁定 spec R2 场景「关系权重按存活记录求和回扣」（账本 {101,205} 删 101 → 1.0）：
     * 阶段 A 重放收集 + 全额求和，阶段 B 以锁定账本一致走绝对值写回。
     */
    @Test
    void should_reduceWeightToSurvivorSum_when_computeAndApply_given_ledger101And205Deleting101() {
        // given：关系 (Alice,Bob) 账本 {101,205}，现值 weight 2.0；存活 205 重放出 1.0 全额边
        GraphRebuildContext ctx = context();
        RelationProperties currentProps = new RelationProperties(2.0, "OLD-REL", List.of("任职", "雇佣"),
                List.of(101L, 205L), List.of(DELETED_FILE, SURVIVING_FILE));
        when(entityInfoVectorRepository.findLedgerSnapshotsWithChunkOverlap(KB_ID, List.of(DELETED_CHUNK)))
                .thenReturn(List.of());
        when(relationInfoVectorRepository.findLedgerSnapshotsWithChunkOverlap(KB_ID, List.of(DELETED_CHUNK)))
                .thenReturn(List.of(relationSnapshot(ALICE, BOB, List.of(DELETED_CHUNK, SURVIVOR_CHUNK),
                        currentProps)));
        when(knowledgeBaseApi.findChunksByKbIdAndChunkIds(eq(KB_ID), anyCollection()))
                .thenReturn(List.of(survivingChunk(205L, SURVIVING_FILE)));
        when(entityExtractionService.replayExtractedChunks(any(ChunkReplayContext.class), anyList()))
                .thenReturn(Map.of(SURVIVOR_CHUNK, resultOfEdge(replayEdge(SURVIVOR_CHUNK, ALICE, BOB,
                        1.0, List.of("雇佣", "任职"), "REL-205"))));
        when(descriptionSummarizer.summarize(any(GraphMergeContext.class), eq(ALICE + "~" + BOB),
                anyString(), anyList())).thenReturn("NEW-REL");
        when(embeddingClient.embed(eq(EMBED_PROFILE), anyString())).thenReturn(EMBEDDED);

        // when：阶段 A
        GraphRebuildPlan plan = service.compute(ctx, List.of(DELETED_CHUNK));

        // then：快照存活 {205}，权重绝对值 = 存活记录全额求和 = 1.0（权重回扣）
        assertEquals(1, plan.relations().size());
        GraphRebuildPlan.RelationRebuildItem item = plan.relations().get(0);
        assertEquals(1.0, item.finalWeight(), 0.0001, "删 101 后权重应为存活来源全额之和 1.0");
        assertEquals(List.of(SURVIVOR_CHUNK), item.snapshotSurvivors());

        // when：阶段 B（锁定账本仍为 {101,205}——分块行尚未删除）
        when(relationEdgeRepository.lockByKbIdAndUnorderedPair(KB_ID, ALICE, BOB))
                .thenReturn(List.of(new RelationEdge(KB_ID, ALICE, BOB, currentProps)));
        when(relationInfoVectorRepository.lockByKbIdAndUnorderedPair(KB_ID, ALICE, BOB))
                .thenReturn(Optional.of(RelationInfoVector.restore(9L, KB_ID, ALICE, BOB, "rel-content",
                        List.of(DELETED_CHUNK, SURVIVOR_CHUNK), OLD_VECTOR, null, null)));
        GraphRebuildReport report = service.apply(ctx, plan);

        // then：绝对值写回——边权重 1.0、账本收缩为 {205}、路径剔掉被删文档、向量内容与最终描述同源成对
        ArgumentCaptor<List<RelationEdge>> edgeCaptor = ArgumentCaptor.forClass(List.class);
        verify(relationEdgeRepository).upsertAll(edgeCaptor.capture(), eq(OPERATOR));
        RelationEdge written = edgeCaptor.getValue().get(0);
        assertEquals(1.0, written.properties().weight(), 0.0001);
        assertEquals("NEW-REL", written.properties().description());
        assertEquals(List.of(SURVIVING_FILE), written.properties().filePaths());
        assertEquals(List.of(SURVIVOR_CHUNK), written.properties().sourceIds());
        ArgumentCaptor<List<RelationInfoVector>> vectorCaptor = ArgumentCaptor.forClass(List.class);
        verify(relationInfoVectorRepository).upsertAll(vectorCaptor.capture(), eq(OPERATOR));
        RelationInfoVector writtenVector = vectorCaptor.getValue().get(0);
        assertEquals(List.of(SURVIVOR_CHUNK), writtenVector.chunkIds());
        assertEquals(RelationInfoVector.buildContent(item.finalKeywords(), ALICE, BOB, "NEW-REL"),
                writtenVector.content());
        assertArrayEquals(EMBEDDED, writtenVector.vector());
        assertEquals(1, report.rebuiltEntries());
        assertEquals(0, report.degradedEntries());
        // 阶段 B 不再触发任何远程调用（embed 仅在阶段 A 发生一次）
        verify(embeddingClient, times(1)).embed(eq(EMBED_PROFILE), anyString());
    }

    /**
     * 锁定 spec R2 场景「描述碎片数量阈值真实生效」：8 条存活碎片达到默认强制摘要阈值（8）
     * → 以真实 {@link DescriptionSummarizer}（仅 Mock LLM/计数端口）端到端触发一次 LLM 摘要，
     * 重建结果取摘要文本而非直接拼接。
     */
    @Test
    void should_triggerLlmSummary_when_compute_given_eightDescriptionFragments() {
        // given：账本 {101, 201..208}，8 个存活分块各出一条描述碎片
        GraphRebuildContext ctx = context();
        List<Long> ledger = List.of(101L, 201L, 202L, 203L, 204L, 205L, 206L, 207L, 208L);
        when(entityInfoVectorRepository.findLedgerSnapshotsWithChunkOverlap(KB_ID, List.of(DELETED_CHUNK)))
                .thenReturn(List.of(entitySnapshot(ALICE, ledger, "snap-content", OLD_VECTOR,
                        EntityProperties.empty())));
        when(relationInfoVectorRepository.findLedgerSnapshotsWithChunkOverlap(KB_ID, List.of(DELETED_CHUNK)))
                .thenReturn(List.of());
        List<Chunk> chunks = new ArrayList<>();
        Map<Long, EntityExtractionResult> replayResults = new LinkedHashMap<>();
        for (long chunkId = 201L; chunkId <= 208L; chunkId++) {
            chunks.add(survivingChunk(chunkId, SURVIVING_FILE));
            replayResults.put(chunkId, resultOf(replayEntity(chunkId, ALICE, "Person", "desc-" + chunkId)));
        }
        when(knowledgeBaseApi.findChunksByKbIdAndChunkIds(eq(KB_ID), anyCollection())).thenReturn(chunks);
        when(entityExtractionService.replayExtractedChunks(any(ChunkReplayContext.class), anyList()))
                .thenReturn(replayResults);
        when(tokenCounter.count(anyString())).thenReturn(10);
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult("LLM-SUMMARY", 3));
        when(embeddingClient.embed(eq(EMBED_PROFILE), anyString())).thenReturn(EMBEDDED);
        // 用真实摘要器重新装配（条数/token 阈值判定不可 Mock 掉）
        service = newService(new DescriptionSummarizer(tokenCounter, llmClient, new ObjectMapper()));

        // when
        GraphRebuildPlan plan = service.compute(ctx, List.of(DELETED_CHUNK));

        // then：8 条碎片触发一次 LLM 摘要，最终描述为摘要文本而非换行拼接
        assertEquals("LLM-SUMMARY", plan.entities().get(0).finalDescription());
        verify(llmClient, times(1)).chat(any(LlmChatRequest.class));
    }

    /** 单实体记录的重放结果便捷桩。 */
    private EntityExtractionResult resultOf(EntityNode node) {
        return new EntityExtractionResult(List.of(node), List.of());
    }

    /** 单关系记录的重放结果便捷桩。 */
    private EntityExtractionResult resultOfEdge(RelationEdge edge) {
        return new EntityExtractionResult(List.of(), List.of(edge));
    }

    /**
     * 锁定 spec R2 场景「实体类型按票数重算」：Person 两票 vs Researcher 一票 → Person
     * （众数口径复用 {@link EntityProperties#primaryEntityType}）。
     */
    @Test
    void should_pickMajorityVotedType_when_compute_given_personTwoVotesResearcherOne() {
        // given
        GraphRebuildContext ctx = context();
        when(entityInfoVectorRepository.findLedgerSnapshotsWithChunkOverlap(KB_ID, List.of(DELETED_CHUNK)))
                .thenReturn(List.of(entitySnapshot(ALICE, List.of(DELETED_CHUNK, 205L, 206L, 207L),
                        "snap-content", OLD_VECTOR, EntityProperties.empty())));
        when(relationInfoVectorRepository.findLedgerSnapshotsWithChunkOverlap(KB_ID, List.of(DELETED_CHUNK)))
                .thenReturn(List.of());
        when(knowledgeBaseApi.findChunksByKbIdAndChunkIds(eq(KB_ID), anyCollection())).thenReturn(List.of(
                survivingChunk(205L, SURVIVING_FILE), survivingChunk(206L, SURVIVING_FILE),
                survivingChunk(207L, SURVIVING_FILE)));
        when(entityExtractionService.replayExtractedChunks(any(ChunkReplayContext.class), anyList()))
                .thenReturn(Map.of(
                        205L, resultOf(replayEntity(205L, ALICE, "Person", "d1")),
                        206L, resultOf(replayEntity(206L, ALICE, "Person", "d2")),
                        207L, resultOf(replayEntity(207L, ALICE, "Researcher", "d3"))));
        when(descriptionSummarizer.summarize(any(GraphMergeContext.class), eq(ALICE), anyString(), anyList()))
                .thenReturn("S");
        when(embeddingClient.embed(eq(EMBED_PROFILE), anyString())).thenReturn(EMBEDDED);

        // when
        GraphRebuildPlan plan = service.compute(ctx, List.of(DELETED_CHUNK));

        // then：类型为票数众数 Person；票统计为 Person=2、Researcher=1
        GraphRebuildPlan.EntityRebuildItem item = plan.entities().get(0);
        assertEquals("Person", item.finalEntityType());
        assertEquals(2, item.finalVotes().get("Person"));
        assertEquals(1, item.finalVotes().get("Researcher"));
        assertEquals(List.of(205L, 206L, 207L), item.snapshotSurvivors(), "存活账本升序且剔掉被删分块");
    }

    /**
     * 锁定 spec R2 场景「关系关键词去重排序」：{@code 雇佣,任职} + {@code 任职,合作}
     * → {@code 合作,雇佣,任职}（拆分去重后字典序）；权重为两条存活记录全额之和。
     */
    @Test
    void should_dedupAndSortKeywords_when_compute_given_overlappingKeywordRecords() {
        // given
        GraphRebuildContext ctx = context();
        when(entityInfoVectorRepository.findLedgerSnapshotsWithChunkOverlap(KB_ID, List.of(DELETED_CHUNK)))
                .thenReturn(List.of());
        when(relationInfoVectorRepository.findLedgerSnapshotsWithChunkOverlap(KB_ID, List.of(DELETED_CHUNK)))
                .thenReturn(List.of(relationSnapshot(ALICE, BOB,
                        List.of(DELETED_CHUNK, SURVIVOR_CHUNK, SURVIVOR_CHUNK_2),
                        new RelationProperties(3.0, "OLD", List.of(), List.of(), List.of()))));
        when(knowledgeBaseApi.findChunksByKbIdAndChunkIds(eq(KB_ID), anyCollection())).thenReturn(List.of(
                survivingChunk(205L, SURVIVING_FILE), survivingChunk(206L, SURVIVING_FILE)));
        when(entityExtractionService.replayExtractedChunks(any(ChunkReplayContext.class), anyList()))
                .thenReturn(Map.of(
                        SURVIVOR_CHUNK, resultOfEdge(replayEdge(SURVIVOR_CHUNK, ALICE, BOB, 1.0,
                                List.of("雇佣", "任职"), "r1")),
                        SURVIVOR_CHUNK_2, resultOfEdge(replayEdge(SURVIVOR_CHUNK_2, ALICE, BOB, 1.0,
                                List.of("任职", "合作"), "r2"))));
        when(descriptionSummarizer.summarize(any(GraphMergeContext.class), anyString(), anyString(), anyList()))
                .thenReturn("S");
        when(embeddingClient.embed(eq(EMBED_PROFILE), anyString())).thenReturn(EMBEDDED);

        // when
        GraphRebuildPlan plan = service.compute(ctx, List.of(DELETED_CHUNK));

        // then：去重 + 字典序（本仓库字典序=Java 字符串自然序，与合并端 TreeSet/Collections.sort
        // 同口径：任U+4EFB < 合U+5408 < 雇U+96C7），权重为两条存活记录全额之和 2.0
        GraphRebuildPlan.RelationRebuildItem item = plan.relations().get(0);
        assertEquals(List.of("任职", "合作", "雇佣"), item.finalKeywords());
        assertEquals(2.0, item.finalWeight(), 0.0001);
    }

    /**
     * 锁定 spec R3 与 design D7「降级不阻断删除链」：存活分块缓存缺失（重放空记录集）→
     * 条目降级——阶段 B 保留描述/类型原值、仅修账本与来源路径，计入降级报告，MUST NOT 抛异常，
     * 且降级条目全程零摘要、零向量化。
     */
    @Test
    void should_keepSemanticsAndCountDegraded_when_computeAndApply_given_missingExtractCache() {
        // given
        GraphRebuildContext ctx = context();
        EntityProperties currentProps = new EntityProperties("Organization", "OLD-DESC",
                List.of(DELETED_CHUNK, SURVIVOR_CHUNK), List.of(DELETED_FILE, SURVIVING_FILE),
                Map.of("Organization", 1), List.of("OLD-DESC"));
        when(entityInfoVectorRepository.findLedgerSnapshotsWithChunkOverlap(KB_ID, List.of(DELETED_CHUNK)))
                .thenReturn(List.of(entitySnapshot(ALICE, List.of(DELETED_CHUNK, SURVIVOR_CHUNK),
                        "C", OLD_VECTOR, currentProps)));
        when(relationInfoVectorRepository.findLedgerSnapshotsWithChunkOverlap(KB_ID, List.of(DELETED_CHUNK)))
                .thenReturn(List.of());
        when(knowledgeBaseApi.findChunksByKbIdAndChunkIds(eq(KB_ID), anyCollection()))
                .thenReturn(List.of(survivingChunk(205L, SURVIVING_FILE)));
        when(entityExtractionService.replayExtractedChunks(any(ChunkReplayContext.class), anyList()))
                .thenReturn(Map.of(SURVIVOR_CHUNK, EntityExtractionResult.empty()));

        // when：阶段 A（降级在计划中标记，不抛异常）
        GraphRebuildPlan plan = service.compute(ctx, List.of(DELETED_CHUNK));
        assertTrue(plan.entities().get(0).degraded(), "缓存缺失应标记为降级条目");

        // when：阶段 B
        when(entityNodeRepository.lockByKbIdAndNames(KB_ID, List.of(ALICE)))
                .thenReturn(List.of(graphNode(ALICE, currentProps)));
        when(entityInfoVectorRepository.lockByKbIdAndName(KB_ID, ALICE))
                .thenReturn(Optional.of(lockedEntityVector(ALICE, List.of(DELETED_CHUNK, SURVIVOR_CHUNK),
                        "C", OLD_VECTOR)));
        GraphRebuildReport report = service.apply(ctx, plan);

        // then：语义字段保持原值，账本/展示列/来源路径收缩为存活集合，降级计数 +1，全程零远程
        ArgumentCaptor<EntityNode> nodeCaptor = ArgumentCaptor.forClass(EntityNode.class);
        verify(entityNodeRepository).upsert(nodeCaptor.capture(), eq(OPERATOR));
        EntityProperties writtenProps = nodeCaptor.getValue().properties();
        assertEquals("OLD-DESC", writtenProps.description(), "降级必须保留原描述");
        assertEquals("Organization", writtenProps.entityType(), "降级必须保留原类型");
        assertEquals(List.of(SURVIVOR_CHUNK), writtenProps.sourceIds());
        assertEquals(List.of(SURVIVING_FILE), writtenProps.filePaths(), "来源路径修正为仅含存活来源");
        ArgumentCaptor<EntityInfoVector> vectorCaptor = ArgumentCaptor.forClass(EntityInfoVector.class);
        verify(entityInfoVectorRepository).upsert(vectorCaptor.capture(), eq(OPERATOR));
        assertEquals("C", vectorCaptor.getValue().content(), "降级保留原内容-向量对");
        assertArrayEquals(OLD_VECTOR, vectorCaptor.getValue().vector());
        assertEquals(List.of(SURVIVOR_CHUNK), vectorCaptor.getValue().chunkIds());
        assertEquals(1, report.degradedEntries());
        assertEquals(0, report.rebuiltEntries());
        verifyNoInteractions(embeddingClient, descriptionSummarizer);
    }

    /**
     * 场景（converge-rag-hot-path-object-creation / 3.4）：存活分块携带 {@code original_item}
     * meta JSON，重建阶段 A 经静态常量 {@code META_MAP_TYPE}（Jackson 3 {@code tools.jackson}）
     * 解析块 meta。
     * 预期：常量类型解析产出与「同一 JSON 经匿名 TypeReference 实例解码」的基准逐键一致，
     * Jackson 3 解码路径结果不因常量化而改变。
     */
    @Test
    void should_parseMetaIdenticallyToAnonymousType_when_compute_given_chunkWithOriginalItemJson() {
        // given：实体账本 {101,205} 删 101，存活 205 携带 meta JSON；重放返回空（降级短路、零远程）
        GraphRebuildContext ctx = context();
        EntityProperties currentProps = new EntityProperties("Organization", "OLD-DESC",
                List.of(DELETED_CHUNK, SURVIVOR_CHUNK), List.of(DELETED_FILE, SURVIVING_FILE),
                Map.of("Organization", 1), List.of("OLD-DESC"));
        String originalItem = "{\"page\":3,\"heading_path\":\"第一章\"}";
        when(entityInfoVectorRepository.findLedgerSnapshotsWithChunkOverlap(KB_ID, List.of(DELETED_CHUNK)))
                .thenReturn(List.of(entitySnapshot(ALICE, List.of(DELETED_CHUNK, SURVIVOR_CHUNK),
                        "C", OLD_VECTOR, currentProps)));
        when(relationInfoVectorRepository.findLedgerSnapshotsWithChunkOverlap(KB_ID, List.of(DELETED_CHUNK)))
                .thenReturn(List.of());
        when(knowledgeBaseApi.findChunksByKbIdAndChunkIds(eq(KB_ID), anyCollection()))
                .thenReturn(List.of(survivingChunkWithMeta(205L, SURVIVING_FILE, originalItem)));
        when(entityExtractionService.replayExtractedChunks(any(ChunkReplayContext.class), anyList()))
                .thenReturn(Map.of(SURVIVOR_CHUNK, EntityExtractionResult.empty()));

        // when：阶段 A 组装重放输入（块 meta 经常量 TypeReference 解析）
        service.compute(ctx, List.of(DELETED_CHUNK));

        // then：捕获重放输入，常量解析结果与匿名实例解码基准逐键一致
        ArgumentCaptor<List<ReplayChunkInput>> inputsCaptor = ArgumentCaptor.forClass(List.class);
        verify(entityExtractionService).replayExtractedChunks(any(ChunkReplayContext.class),
                inputsCaptor.capture());
        Map<String, Object> parsedByConstant = inputsCaptor.getValue().get(0).block().meta();
        Map<String, Object> parsedByAnonymous = new ObjectMapper().readValue(originalItem,
                new tools.jackson.core.type.TypeReference<Map<String, Object>>() {
                });
        assertEquals(parsedByAnonymous, parsedByConstant,
                "META_MAP_TYPE 常量解析应与匿名 TypeReference 实例逐键一致");
    }

    /**
     * 锁定 spec R4 场景「期间被并发合并时以锁定值重算」：写回加锁读到的账本多出并发并入的
     * 300 → 存活集合以锁定账本重算、账本写入保留 300（不覆盖并发来源），描述改走确定性聚合
     * （事务内零远程），内容-向量不成对时转入收口清单。
     */
    @Test
    void should_recomputeWithLockedLedger_when_apply_given_concurrentMergeAddedSource() {
        // given：手工计划——快照存活 {205}，阶段 A 候选描述 LLM-DESC（成对内容/向量）
        GraphRebuildContext ctx = context();
        GraphRebuildPlan plan = new GraphRebuildPlan(List.of(DELETED_CHUNK),
                List.of(new GraphRebuildPlan.EntityRebuildItem(ALICE, List.of(SURVIVOR_CHUNK),
                        List.of(replayEntity(SURVIVOR_CHUNK, ALICE, "Person", "d205")),
                        Map.of(SURVIVOR_CHUNK, SURVIVING_FILE), EntityProperties.empty(), false,
                        "LLM-DESC", "Person", Map.of("Person", 1),
                        EntityInfoVector.buildContent(ALICE, "LLM-DESC"), EMBEDDED)),
                List.of(), List.of(), List.of());
        EntityProperties currentProps = new EntityProperties("Person", "OLD", List.of(205L, 300L),
                List.of(SURVIVING_FILE), Map.of("Person", 1), List.of("OLD"));
        when(entityNodeRepository.lockByKbIdAndNames(KB_ID, List.of(ALICE)))
                .thenReturn(List.of(graphNode(ALICE, currentProps)));
        // 锁定账本 {101,205,300}：300 为并发摄入刚并入的来源
        when(entityInfoVectorRepository.lockByKbIdAndName(KB_ID, ALICE))
                .thenReturn(Optional.of(lockedEntityVector(ALICE,
                        List.of(DELETED_CHUNK, SURVIVOR_CHUNK, CONCURRENT_CHUNK), "C", OLD_VECTOR)));

        // when
        GraphRebuildReport report = service.apply(ctx, plan);

        // then：账本保留并发来源（剔 101 后为 {205,300}）；描述为确定性聚合而非 LLM 候选；
        // 内容不成对 → 向量置空 + 收口清单登记；全程零远程
        ArgumentCaptor<EntityNode> nodeCaptor = ArgumentCaptor.forClass(EntityNode.class);
        verify(entityNodeRepository).upsert(nodeCaptor.capture(), eq(OPERATOR));
        assertEquals("d205", nodeCaptor.getValue().properties().description(), "事务内不得做远程摘要");
        ArgumentCaptor<EntityInfoVector> vectorCaptor = ArgumentCaptor.forClass(EntityInfoVector.class);
        verify(entityInfoVectorRepository).upsert(vectorCaptor.capture(), eq(OPERATOR));
        assertEquals(List.of(SURVIVOR_CHUNK, CONCURRENT_CHUNK), vectorCaptor.getValue().chunkIds(),
                "并发并入来源 MUST NOT 被覆盖丢失");
        assertEquals(EntityInfoVector.buildContent(ALICE, "d205"), vectorCaptor.getValue().content(),
                "内容由最终描述派生");
        assertNull(vectorCaptor.getValue().vector(), "无法成对时向量置空，交阶段 C 收口");
        assertEquals(List.of(ALICE), report.pendingVectorSyncEntityNames());
        assertEquals(1, report.rebuiltEntries());
        verifyNoInteractions(embeddingClient, descriptionSummarizer, entityExtractionService, knowledgeBaseApi);
    }

    /**
     * 锁定 spec R4 场景「重算后存活集合为空则删除」：计划为重建条目，但写回加锁重读时账本
     * 只剩被删分块（并发删除清空了来源）→ 转删除（图行+向量行成对），MUST NOT 写入重建结果。
     */
    @Test
    void should_deleteEntry_when_apply_given_lockedLedgerEmptiedByConcurrentDelete() {
        // given
        GraphRebuildContext ctx = context();
        GraphRebuildPlan plan = new GraphRebuildPlan(List.of(DELETED_CHUNK),
                List.of(new GraphRebuildPlan.EntityRebuildItem(ALICE, List.of(SURVIVOR_CHUNK),
                        List.of(replayEntity(SURVIVOR_CHUNK, ALICE, "Person", "d")), Map.of(),
                        EntityProperties.empty(), false, "D", "Person", Map.of("Person", 1), "C", EMBEDDED)),
                List.of(), List.of(), List.of());
        when(entityNodeRepository.lockByKbIdAndNames(KB_ID, List.of(ALICE)))
                .thenReturn(List.of(graphNode(ALICE, EntityProperties.empty())));
        when(entityInfoVectorRepository.lockByKbIdAndName(KB_ID, ALICE))
                .thenReturn(Optional.of(lockedEntityVector(ALICE, List.of(DELETED_CHUNK), "C", EMBEDDED)));

        // when
        GraphRebuildReport report = service.apply(ctx, plan);

        // then：成对物理删（图先行、向量后——与既有四步收敛①②同序），零 upsert
        InOrder inOrder = inOrder(entityNodeRepository, entityInfoVectorRepository);
        inOrder.verify(entityNodeRepository).deleteByKbIdAndName(KB_ID, ALICE);
        inOrder.verify(entityInfoVectorRepository).deleteByKbIdAndName(KB_ID, ALICE);
        verify(entityNodeRepository, never()).upsert(any(), anyString());
        verify(entityInfoVectorRepository, never()).upsert(any(), anyString());
        assertEquals(1, report.prunedEntries());
    }

    /**
     * 锁定 spec R4 场景「已不存在的条目不被复活」：写回时图行与向量行均已缺失且存活为空 →
     * 保持不存在，MUST NOT 插入任何行。
     */
    @Test
    void should_keepAbsent_when_apply_given_entryAlreadyGone() {
        // given：计划为直接删除条目，但并发收敛链已回收该行
        GraphRebuildContext ctx = context();
        GraphRebuildPlan plan = GraphRebuildPlan.empty(List.of(DELETED_CHUNK));
        GraphRebuildPlan withPrune = new GraphRebuildPlan(List.of(DELETED_CHUNK), List.of(), List.of(),
                List.of(GHOST), List.of());
        when(entityNodeRepository.lockByKbIdAndNames(KB_ID, List.of(GHOST))).thenReturn(List.of());
        when(entityInfoVectorRepository.lockByKbIdAndName(KB_ID, GHOST)).thenReturn(Optional.empty());

        // when
        GraphRebuildReport report = service.apply(ctx, withPrune);

        // then：零删除、零插入，仅记缺失观测面
        assertTrue(plan.isEmpty());
        verify(entityNodeRepository, never()).deleteByKbIdAndName(any(), anyString());
        verify(entityInfoVectorRepository, never()).deleteByKbIdAndName(any(), anyString());
        verify(entityNodeRepository, never()).upsert(any(), anyString());
        assertEquals(1, report.missingEntries());
        assertEquals(0, report.prunedEntries());
    }

    /**
     * 锁定 spec R1 与 tasks 4.1：账本重叠分类——{101,205} 的 Alice 进入重建（存活 {205}）、
     * {101} 的 Bob 直接删除且不参与重放、未触及的 Ghost 零读写；阶段 A 零写入（任何
     * lock/upsert/delete 均未发生）。
     */
    @Test
    void should_classifyRebuildAndPruneWithZeroWrites_when_compute_given_mixedLedgers() {
        // given
        GraphRebuildContext ctx = context();
        // Alice 快照内容即「期望内容」→ 复用快照向量、零 embedding；Bob 剔后为空走直接删除
        when(entityInfoVectorRepository.findLedgerSnapshotsWithChunkOverlap(KB_ID, List.of(DELETED_CHUNK)))
                .thenReturn(List.of(
                        entitySnapshot(ALICE, List.of(DELETED_CHUNK, SURVIVOR_CHUNK),
                                EntityInfoVector.buildContent(ALICE, "S"), EMBEDDED,
                                EntityProperties.empty()),
                        entitySnapshot("Bob", List.of(DELETED_CHUNK), "C2", EMBEDDED,
                                EntityProperties.empty())));
        when(relationInfoVectorRepository.findLedgerSnapshotsWithChunkOverlap(KB_ID, List.of(DELETED_CHUNK)))
                .thenReturn(List.of());
        when(knowledgeBaseApi.findChunksByKbIdAndChunkIds(eq(KB_ID), anyCollection()))
                .thenReturn(List.of(survivingChunk(205L, SURVIVING_FILE)));
        when(entityExtractionService.replayExtractedChunks(any(ChunkReplayContext.class), anyList()))
                .thenReturn(Map.of(SURVIVOR_CHUNK, resultOf(replayEntity(SURVIVOR_CHUNK, ALICE,
                        "Person", "d205"))));
        when(descriptionSummarizer.summarize(any(GraphMergeContext.class), anyString(), anyString(), anyList()))
                .thenReturn("S");

        // when（内容与向量复用：快照内容恰为期望内容 → 零 embedding）
        GraphRebuildPlan plan = service.compute(ctx, List.of(DELETED_CHUNK));

        // then：分类正确；Alice 免补算向量（复用快照对）
        assertEquals(1, plan.entities().size());
        assertEquals(ALICE, plan.entities().get(0).entityName());
        assertEquals(List.of(SURVIVOR_CHUNK), plan.entities().get(0).snapshotSurvivors());
        assertEquals(List.of("Bob"), plan.prunedEntityNames());
        assertEquals(EntityInfoVector.buildContent(ALICE, "S"), plan.entities().get(0).finalContent());
        assertSame(EMBEDDED, plan.entities().get(0).finalVector(), "内容未变应复用快照向量、免一次远程");
        verify(embeddingClient, never()).embed(anyString(), anyString());
        // 直接删除条目（Bob）不被重放：入参分块仅有存活 {205}
        ArgumentCaptor<List<ReplayChunkInput>> inputsCaptor = ArgumentCaptor.forClass(List.class);
        verify(entityExtractionService).replayExtractedChunks(any(ChunkReplayContext.class),
                inputsCaptor.capture());
        assertEquals(List.of(SURVIVOR_CHUNK),
                inputsCaptor.getValue().stream().map(ReplayChunkInput::chunkId).toList());
        // 阶段 A 零写入：任何 lock/upsert/delete 都不出现，未触及条目零读
        verify(entityNodeRepository, never()).upsert(any(), any());
        verify(entityNodeRepository, never()).upsertAll(any(), any());
        verify(entityInfoVectorRepository, never()).upsert(any(), any());
        verify(entityNodeRepository, never()).lockByKbIdAndNames(any(), any());
        verify(entityInfoVectorRepository, never()).lockByKbIdAndName(any(), anyString());
        verify(entityNodeRepository, never()).deleteByKbIdAndName(any(), anyString());
    }

    /**
     * 锁定 spec R1 场景「未被触及的条目零读写」延续：对重建+直接删除清单做阶段 B 写回时，
     * Ghost 的任何行操作都不发生；阶段 B 零远程（重放/摘要/向量化/分块回取全部 no-op）。
     */
    @Test
    void should_pruneDirectDeleteAndNeverTouchUntouched_when_apply_given_classifiedPlan() {
        // given：沿用上一分类结果构造阶段 A 的等价计划（Bob 走直接删除清单）
        GraphRebuildContext ctx = context();
        when(entityInfoVectorRepository.findLedgerSnapshotsWithChunkOverlap(KB_ID, List.of(DELETED_CHUNK)))
                .thenReturn(List.of(
                        entitySnapshot(ALICE, List.of(DELETED_CHUNK, SURVIVOR_CHUNK),
                                EntityInfoVector.buildContent(ALICE, "S"), EMBEDDED,
                                EntityProperties.empty()),
                        entitySnapshot("Bob", List.of(DELETED_CHUNK), "C2", EMBEDDED,
                                EntityProperties.empty())));
        when(relationInfoVectorRepository.findLedgerSnapshotsWithChunkOverlap(KB_ID, List.of(DELETED_CHUNK)))
                .thenReturn(List.of());
        when(knowledgeBaseApi.findChunksByKbIdAndChunkIds(eq(KB_ID), anyCollection()))
                .thenReturn(List.of(survivingChunk(205L, SURVIVING_FILE)));
        when(entityExtractionService.replayExtractedChunks(any(ChunkReplayContext.class), anyList()))
                .thenReturn(Map.of(SURVIVOR_CHUNK, resultOf(replayEntity(SURVIVOR_CHUNK, ALICE,
                        "Person", "d205"))));
        when(descriptionSummarizer.summarize(any(GraphMergeContext.class), anyString(), anyString(), anyList()))
                .thenReturn("S");
        GraphRebuildPlan plan = service.compute(ctx, List.of(DELETED_CHUNK));

        // when：阶段 B（处理序按名升序 [Alice, Bob]；Alice 存活 {205}、Bob 剔后为空）
        when(entityNodeRepository.lockByKbIdAndNames(KB_ID, List.of(ALICE, "Bob"))).thenReturn(List.of(
                graphNode("Bob", new EntityProperties("Person", "B", List.of(101L), List.of(DELETED_FILE),
                        Map.of("Person", 1), List.of("B"))),
                graphNode(ALICE, new EntityProperties("Person", "OLD", List.of(101L, 205L),
                        List.of(DELETED_FILE, SURVIVING_FILE), Map.of("Person", 1), List.of("OLD")))));
        when(entityInfoVectorRepository.lockByKbIdAndName(KB_ID, "Bob"))
                .thenReturn(Optional.of(lockedEntityVector("Bob", List.of(DELETED_CHUNK), "C2", EMBEDDED)));
        when(entityInfoVectorRepository.lockByKbIdAndName(KB_ID, ALICE))
                .thenReturn(Optional.of(lockedEntityVector(ALICE, List.of(DELETED_CHUNK, SURVIVOR_CHUNK),
                        "C", EMBEDDED)));
        GraphRebuildReport report = service.apply(ctx, plan);

        // then：Bob 成对删除、Alice 绝对值写回；Ghost 零读写；阶段 B 零远程
        verify(entityNodeRepository).deleteByKbIdAndName(KB_ID, "Bob");
        verify(entityInfoVectorRepository).deleteByKbIdAndName(KB_ID, "Bob");
        ArgumentCaptor<EntityNode> nodeCaptor = ArgumentCaptor.forClass(EntityNode.class);
        verify(entityNodeRepository).upsert(nodeCaptor.capture(), eq(OPERATOR));
        assertEquals(ALICE, nodeCaptor.getValue().entityName());
        assertEquals(List.of(SURVIVOR_CHUNK), nodeCaptor.getValue().properties().sourceIds());
        assertEquals(1, report.rebuiltEntries());
        assertEquals(1, report.prunedEntries());
        verify(entityInfoVectorRepository, never()).lockByKbIdAndName(KB_ID, GHOST);
        verify(embeddingClient, never()).embed(anyString(), anyString());
        verifyNoInteractions(llmClient);
    }

    /**
     * 锁定 spec R1 场景「抽取类之外的分块不触发重建」：全部被删分块无图谱贡献（两次重叠查询
     * 零命中）→ 计划为空、不读任何分块/缓存/不发远程；写回对图谱零读写。
     */
    @Test
    void should_skipEntirely_when_computeAndApply_given_noGraphContributionAtAll() {
        // given
        GraphRebuildContext ctx = context();
        when(entityInfoVectorRepository.findLedgerSnapshotsWithChunkOverlap(KB_ID, List.of(DELETED_CHUNK)))
                .thenReturn(List.of());
        when(relationInfoVectorRepository.findLedgerSnapshotsWithChunkOverlap(KB_ID, List.of(DELETED_CHUNK)))
                .thenReturn(List.of());

        // when：阶段 A
        GraphRebuildPlan plan = service.compute(ctx, List.of(DELETED_CHUNK));

        // then：空计划，零分块读取、零重放、零远程
        assertTrue(plan.isEmpty());
        verifyNoInteractions(knowledgeBaseApi, entityExtractionService, descriptionSummarizer, embeddingClient);

        // when/then：阶段 B 对图谱零读写（空计划短路，不开任何语句）
        GraphRebuildReport report = service.apply(ctx, plan);
        assertEquals(0, report.rebuiltEntries() + report.degradedEntries() + report.prunedEntries()
                + report.missingEntries());
        verify(entityNodeRepository, never()).lockByKbIdAndNames(any(), any());
        verify(entityInfoVectorRepository, never()).lockByKbIdAndName(any(), anyString());
        verify(entityNodeRepository, never()).upsert(any(), any());
        verify(entityNodeRepository, never()).deleteByKbIdAndName(any(), anyString());
    }

    /**
     * 锁定 tasks 4.3/D8：来源文件路径经 {@link GraphSourceFilePaths#normalize} 统一口径——
     * 两个存活分块同文件时路径去重只记一次，被删文档路径整体剔除。
     */
    @Test
    void should_deduplicateSurvivorFilePaths_when_computeAndApply_given_twoChunksSameFile() {
        // given
        GraphRebuildContext ctx = context();
        when(entityInfoVectorRepository.findLedgerSnapshotsWithChunkOverlap(KB_ID, List.of(DELETED_CHUNK)))
                .thenReturn(List.of(entitySnapshot(ALICE,
                        List.of(DELETED_CHUNK, SURVIVOR_CHUNK, SURVIVOR_CHUNK_2), "C", EMBEDDED,
                        new EntityProperties("Person", "OLD", List.of(), List.of(DELETED_FILE),
                                Map.of(), List.of()))));
        when(relationInfoVectorRepository.findLedgerSnapshotsWithChunkOverlap(KB_ID, List.of(DELETED_CHUNK)))
                .thenReturn(List.of());
        when(knowledgeBaseApi.findChunksByKbIdAndChunkIds(eq(KB_ID), anyCollection())).thenReturn(List.of(
                survivingChunk(205L, SURVIVING_FILE), survivingChunk(206L, SURVIVING_FILE)));
        when(entityExtractionService.replayExtractedChunks(any(ChunkReplayContext.class), anyList()))
                .thenReturn(Map.of(
                        SURVIVOR_CHUNK, resultOf(replayEntity(SURVIVOR_CHUNK, ALICE, "Person", "d1")),
                        SURVIVOR_CHUNK_2, resultOf(replayEntity(SURVIVOR_CHUNK_2, ALICE, "Person", "d2"))));
        when(descriptionSummarizer.summarize(any(GraphMergeContext.class), anyString(), anyString(), anyList()))
                .thenReturn("S");
        GraphRebuildPlan plan = service.compute(ctx, List.of(DELETED_CHUNK));
        when(entityNodeRepository.lockByKbIdAndNames(KB_ID, List.of(ALICE))).thenReturn(List.of());
        when(entityInfoVectorRepository.lockByKbIdAndName(KB_ID, ALICE)).thenReturn(Optional.of(
                lockedEntityVector(ALICE, List.of(DELETED_CHUNK, SURVIVOR_CHUNK, SURVIVOR_CHUNK_2),
                        "C", EMBEDDED)));

        // when
        service.apply(ctx, plan);

        // then：路径列表去重且不含被删文档；展示来源列为全存活账本
        ArgumentCaptor<EntityNode> nodeCaptor = ArgumentCaptor.forClass(EntityNode.class);
        verify(entityNodeRepository).upsert(nodeCaptor.capture(), eq(OPERATOR));
        assertEquals(List.of(SURVIVING_FILE), nodeCaptor.getValue().properties().filePaths());
        assertEquals(List.of(SURVIVOR_CHUNK, SURVIVOR_CHUNK_2), nodeCaptor.getValue().properties().sourceIds());
    }

    /**
     * 边界与入参防御：上下文为 null → 抛异常；被删分块为 null / 空 / 仅含 null → 空计划且
     * 零 DB 交互；阶段 B 对 null 上下文/计划抛异常。
     */
    @Test
    void should_shortCircuitOrThrow_when_computeAndApply_given_nullOrEmptyInputs() {
        GraphRebuildContext ctx = context();
        assertThrows(IllegalArgumentException.class, () -> service.compute(null, List.of(DELETED_CHUNK)));
        assertThrows(IllegalArgumentException.class, () -> service.apply(null, GraphRebuildPlan.empty(List.of())));
        assertThrows(IllegalArgumentException.class, () -> service.apply(ctx, null));

        // when：null / 空 / 仅含 null 元素
        GraphRebuildPlan nullPlan = service.compute(ctx, null);
        GraphRebuildPlan emptyPlan = service.compute(ctx, List.of());
        GraphRebuildPlan dirtyPlan = service.compute(ctx, java.util.Arrays.asList(null, null));

        // then：全部为空计划且零 DB 交互（不开重叠查询）
        assertTrue(nullPlan.isEmpty() && emptyPlan.isEmpty() && dirtyPlan.isEmpty());
        verifyNoInteractions(entityInfoVectorRepository, relationInfoVectorRepository,
                knowledgeBaseApi, entityExtractionService);
    }

    /**
     * 取消检查：上下文取消器命中 → 阶段 A 抛异常（运行失败语义，删除链据此中止）。
     */
    @Test
    void should_throw_when_compute_given_cancelled() {
        // given
        GraphRebuildContext ctx = new GraphRebuildContext(KB_ID, DELETED_DOC_ID, List.of(), false,
                SUMMARY_PROFILE, EMBED_PROFILE, "Chinese", GraphMergeParams.defaults(), OPERATOR,
                () -> true);

        // when & then
        assertThrows(IllegalStateException.class, () -> service.compute(ctx, List.of(DELETED_CHUNK)));
        verifyNoInteractions(entityInfoVectorRepository);
    }

    /**
     * 运行失败上抛（阶段 A）：向量化端口异常 → fail-closed 上抛，不留半成品计划、零写入。
     */
    @Test
    void should_propagateFailure_when_compute_given_embeddingError() {
        // given
        GraphRebuildContext ctx = context();
        when(entityInfoVectorRepository.findLedgerSnapshotsWithChunkOverlap(KB_ID, List.of(DELETED_CHUNK)))
                .thenReturn(List.of(entitySnapshot(ALICE, List.of(DELETED_CHUNK, SURVIVOR_CHUNK),
                        "C", OLD_VECTOR, EntityProperties.empty())));
        when(relationInfoVectorRepository.findLedgerSnapshotsWithChunkOverlap(KB_ID, List.of(DELETED_CHUNK)))
                .thenReturn(List.of());
        when(knowledgeBaseApi.findChunksByKbIdAndChunkIds(eq(KB_ID), anyCollection()))
                .thenReturn(List.of(survivingChunk(205L, SURVIVING_FILE)));
        when(entityExtractionService.replayExtractedChunks(any(ChunkReplayContext.class), anyList()))
                .thenReturn(Map.of(SURVIVOR_CHUNK, resultOf(replayEntity(SURVIVOR_CHUNK, ALICE,
                        "Person", "d205"))));
        when(descriptionSummarizer.summarize(any(GraphMergeContext.class), anyString(), anyString(), anyList()))
                .thenReturn("S");
        when(embeddingClient.embed(eq(EMBED_PROFILE), anyString()))
                .thenThrow(new RuntimeException("embedding unavailable"));

        // when & then：远程失败照实上抛（design D7：运行失败 ≠ 语义降级）
        assertThrows(IllegalStateException.class, () -> service.compute(ctx, List.of(DELETED_CHUNK)));
        verify(entityNodeRepository, never()).upsert(any(), any());
    }

    /**
     * 运行失败上抛（阶段 B）：写回 SQL 异常原样上抛（不吞、不转降级），由调用方事务回滚。
     */
    @Test
    void should_propagateSqlFailure_when_apply_given_repositoryError() {
        // given
        GraphRebuildContext ctx = context();
        GraphRebuildPlan plan = new GraphRebuildPlan(List.of(DELETED_CHUNK),
                List.of(new GraphRebuildPlan.EntityRebuildItem(ALICE, List.of(SURVIVOR_CHUNK),
                        List.of(replayEntity(SURVIVOR_CHUNK, ALICE, "Person", "d")), Map.of(SURVIVOR_CHUNK, SURVIVING_FILE),
                        EntityProperties.empty(), false, "D", "Person", Map.of("Person", 1), "C", EMBEDDED)),
                List.of(), List.of(), List.of());
        when(entityNodeRepository.lockByKbIdAndNames(KB_ID, List.of(ALICE))).thenReturn(List.of());
        when(entityInfoVectorRepository.lockByKbIdAndName(KB_ID, ALICE))
                .thenReturn(Optional.of(lockedEntityVector(ALICE, List.of(DELETED_CHUNK, SURVIVOR_CHUNK),
                        "C", EMBEDDED)));
        org.mockito.Mockito.doThrow(new DataAccessResourceFailureException("db down"))
                .when(entityNodeRepository).upsert(any(), anyString());

        // when & then：原样上抛（异常类型不被包装替换，保证「存储故障不被误吞成降级」）
        assertThrows(DataAccessResourceFailureException.class, () -> service.apply(ctx, plan));
    }

    /**
     * 分类为「直接删除」的条目在写回时被并发并入新来源（剔后存活非空）→ MUST NOT 删除，
     * 按降级纪律保留现语义、仅收缩账本（来源路径无从修正保持现值）。
     */
    @Test
    void should_keepEntryDegraded_when_apply_given_prunedItemRepopulatedByConcurrentMerge() {
        // given
        GraphRebuildContext ctx = context();
        GraphRebuildPlan plan = new GraphRebuildPlan(List.of(DELETED_CHUNK), List.of(), List.of(),
                List.of(ALICE), List.of());
        EntityProperties currentProps = new EntityProperties("Person", "OLD", List.of(205L, 300L),
                List.of(DELETED_FILE, SURVIVING_FILE), Map.of("Person", 1), List.of("OLD"));
        when(entityNodeRepository.lockByKbIdAndNames(KB_ID, List.of(ALICE)))
                .thenReturn(List.of(graphNode(ALICE, currentProps)));
        when(entityInfoVectorRepository.lockByKbIdAndName(KB_ID, ALICE))
                .thenReturn(Optional.of(lockedEntityVector(ALICE, List.of(SURVIVOR_CHUNK, CONCURRENT_CHUNK),
                        "C", OLD_VECTOR)));

        // when
        GraphRebuildReport report = service.apply(ctx, plan);

        // then：保留条目并按降级写回收缩账本；语义与内容-向量对原样保留；删除路径零触碰
        verify(entityNodeRepository, never()).deleteByKbIdAndName(any(), anyString());
        verify(entityInfoVectorRepository, never()).deleteByKbIdAndName(any(), anyString());
        ArgumentCaptor<EntityNode> nodeCaptor = ArgumentCaptor.forClass(EntityNode.class);
        verify(entityNodeRepository).upsert(nodeCaptor.capture(), eq(OPERATOR));
        assertEquals("OLD", nodeCaptor.getValue().properties().description());
        assertEquals(List.of(SURVIVOR_CHUNK, CONCURRENT_CHUNK), nodeCaptor.getValue().properties().sourceIds());
        ArgumentCaptor<EntityInfoVector> vectorCaptor = ArgumentCaptor.forClass(EntityInfoVector.class);
        verify(entityInfoVectorRepository).upsert(vectorCaptor.capture(), eq(OPERATOR));
        assertEquals("C", vectorCaptor.getValue().content());
        assertArrayEquals(OLD_VECTOR, vectorCaptor.getValue().vector());
        assertEquals(1, report.degradedEntries());
        assertEquals(0, report.prunedEntries());
    }

    /**
     * 阶段 C 收口委托：报告登记待收口清单时逐条复用 {@link VectorContentReconciler}；
     * 清单为空时零操作。
     */
    @Test
    void should_delegateToReconciler_when_reconcilePendingVectorContent_given_pendingList() {
        // given
        GraphRebuildContext ctx = context();
        GraphRebuildReport pending = new GraphRebuildReport(0, 0, 0, 0, List.of(ALICE),
                List.of(List.of(ALICE, BOB)));

        // when
        service.reconcilePendingVectorContent(ctx, pending);

        // then：实体与关系各收口一次（模型引用与 token 上限取自上下文）
        verify(vectorContentReconciler).reconcileEntity(KB_ID, ALICE, EMBED_PROFILE,
                GraphMergeParams.DEFAULT_EMBEDDING_TOKEN_LIMIT);
        verify(vectorContentReconciler).reconcileRelation(KB_ID, ALICE, BOB, EMBED_PROFILE,
                GraphMergeParams.DEFAULT_EMBEDDING_TOKEN_LIMIT);

        // when：空清单
        service.reconcilePendingVectorContent(ctx, GraphRebuildReport.empty());

        // then：空清单零新增操作（收口调用总数不变）
        verify(vectorContentReconciler, times(1))
                .reconcileEntity(any(), any(), any(), anyInt());
        verify(vectorContentReconciler, times(1))
                .reconcileRelation(any(), any(), any(), any(), anyInt());
    }

    /**
     * 锁定 spec R2「描述碎片数量阈值真实生效」的下界：碎片条数未达强制摘要阈值（默认 8）
     * 且总 token 未超输出上限 → 真实 {@link DescriptionSummarizer} 直接换行拼接，
     * MUST NOT 发起任何 LLM 调用（阈值判定在重建路径与合并路径同一处生效）。
     */
    @Test
    void should_concatFragmentsWithoutLlm_when_compute_given_fragmentsBelowForceThreshold() {
        // given：3 条存活碎片（< 8），每条桩 10 token（总 30 < summary_max_tokens 1200）
        GraphRebuildContext ctx = context();
        when(entityInfoVectorRepository.findLedgerSnapshotsWithChunkOverlap(KB_ID, List.of(DELETED_CHUNK)))
                .thenReturn(List.of(entitySnapshot(ALICE,
                        List.of(DELETED_CHUNK, SURVIVOR_CHUNK, SURVIVOR_CHUNK_2, 207L),
                        "C", EMBEDDED, EntityProperties.empty())));
        when(relationInfoVectorRepository.findLedgerSnapshotsWithChunkOverlap(KB_ID, List.of(DELETED_CHUNK)))
                .thenReturn(List.of());
        when(knowledgeBaseApi.findChunksByKbIdAndChunkIds(eq(KB_ID), anyCollection())).thenReturn(List.of(
                survivingChunk(205L, SURVIVING_FILE), survivingChunk(206L, SURVIVING_FILE),
                survivingChunk(207L, SURVIVING_FILE)));
        when(entityExtractionService.replayExtractedChunks(any(ChunkReplayContext.class), anyList()))
                .thenReturn(Map.of(
                        SURVIVOR_CHUNK, resultOf(replayEntity(SURVIVOR_CHUNK, ALICE, "Person", "d1")),
                        SURVIVOR_CHUNK_2, resultOf(replayEntity(SURVIVOR_CHUNK_2, ALICE, "Person", "d2")),
                        207L, resultOf(replayEntity(207L, ALICE, "Person", "d3"))));
        when(tokenCounter.count(anyString())).thenReturn(10);
        when(embeddingClient.embed(eq(EMBED_PROFILE), anyString())).thenReturn(EMBEDDED);
        service = newService(new DescriptionSummarizer(tokenCounter, llmClient, new ObjectMapper()));

        // when
        GraphRebuildPlan plan = service.compute(ctx, List.of(DELETED_CHUNK));

        // then：描述为去重碎片的换行拼接，零 LLM 远程调用
        assertEquals("d1\nd2\nd3", plan.entities().get(0).finalDescription());
        verify(llmClient, never()).chat(any(LlmChatRequest.class));
    }

    /**
     * 锁定 spec graph-source-file-paths R3 场景「重建路径无可用路径时保持既有值」：写回时锁定
     * 存活集合与快照不一致且新存活分块无任何已知路径 → filePaths MUST 保持原值、MUST NOT 清空。
     */
    @Test
    void should_keepExistingFilePaths_when_apply_given_noSurvivorPathAvailable() {
        // given：快照存活 {205}（已知路径 doc-b.txt），但锁定时 205 已被并发剔除、仅剩无路径的 300
        GraphRebuildContext ctx = context();
        GraphRebuildPlan plan = new GraphRebuildPlan(List.of(DELETED_CHUNK),
                List.of(new GraphRebuildPlan.EntityRebuildItem(ALICE, List.of(SURVIVOR_CHUNK),
                        List.of(replayEntity(SURVIVOR_CHUNK, ALICE, "Person", "d205")),
                        Map.of(SURVIVOR_CHUNK, SURVIVING_FILE), EntityProperties.empty(), false,
                        "LLM-DESC", "Person", Map.of("Person", 1),
                        EntityInfoVector.buildContent(ALICE, "LLM-DESC"), EMBEDDED)),
                List.of(), List.of(), List.of());
        EntityProperties currentProps = new EntityProperties("Person", "OLD", List.of(CONCURRENT_CHUNK),
                List.of(KEEP_FILE), Map.of("Person", 1), List.of("OLD"));
        when(entityNodeRepository.lockByKbIdAndNames(KB_ID, List.of(ALICE)))
                .thenReturn(List.of(graphNode(ALICE, currentProps)));
        when(entityInfoVectorRepository.lockByKbIdAndName(KB_ID, ALICE))
                .thenReturn(Optional.of(lockedEntityVector(ALICE, List.of(DELETED_CHUNK, CONCURRENT_CHUNK),
                        "C", OLD_VECTOR)));

        // when
        GraphRebuildReport report = service.apply(ctx, plan);

        // then：路径保持既有值不清空；账本按锁定存活收缩；存活记录全不可用 → 计入降级
        ArgumentCaptor<EntityNode> nodeCaptor = ArgumentCaptor.forClass(EntityNode.class);
        verify(entityNodeRepository).upsert(nodeCaptor.capture(), eq(OPERATOR));
        assertEquals(List.of(KEEP_FILE), nodeCaptor.getValue().properties().filePaths(),
                "存活来源取不到任何路径时 MUST NOT 清空来源路径");
        assertEquals(List.of(CONCURRENT_CHUNK), nodeCaptor.getValue().properties().sourceIds());
        assertEquals(1, report.degradedEntries());
        assertEquals(0, report.rebuiltEntries());
        verifyNoInteractions(embeddingClient, descriptionSummarizer);
    }

    /**
     * 锁定 spec R3「降级不阻断其他条目重建」与 design D7：同批一条降级（存活分块无可用重放）、
     * 一条正常重算 → 正常条目完成绝对值写回、降级条目保留语义字段，各计一次且整链不抛异常。
     */
    @Test
    void should_rebuildNormalEntries_when_computeAndApply_given_oneEntryDegradedInSameBatch() {
        // given：Alice 存活 205 可重放；Bob 存活 206 缓存缺失（重放空记录集）
        GraphRebuildContext ctx = context();
        EntityProperties aliceProps = new EntityProperties("Person", "OLD-ALICE",
                List.of(DELETED_CHUNK, SURVIVOR_CHUNK), List.of(DELETED_FILE, SURVIVING_FILE),
                Map.of("Person", 1), List.of("OLD-ALICE"));
        EntityProperties bobProps = new EntityProperties("Organization", "OLD-BOB",
                List.of(DELETED_CHUNK, SURVIVOR_CHUNK_2), List.of(DELETED_FILE, SURVIVING_FILE),
                Map.of("Organization", 1), List.of("OLD-BOB"));
        when(entityInfoVectorRepository.findLedgerSnapshotsWithChunkOverlap(KB_ID, List.of(DELETED_CHUNK)))
                .thenReturn(List.of(
                        entitySnapshot(ALICE, List.of(DELETED_CHUNK, SURVIVOR_CHUNK), "C-A", OLD_VECTOR, aliceProps),
                        entitySnapshot(BOB, List.of(DELETED_CHUNK, SURVIVOR_CHUNK_2), "C-B", OLD_VECTOR, bobProps)));
        when(relationInfoVectorRepository.findLedgerSnapshotsWithChunkOverlap(KB_ID, List.of(DELETED_CHUNK)))
                .thenReturn(List.of());
        when(knowledgeBaseApi.findChunksByKbIdAndChunkIds(eq(KB_ID), anyCollection())).thenReturn(List.of(
                survivingChunk(205L, SURVIVING_FILE), survivingChunk(206L, SURVIVING_FILE)));
        when(entityExtractionService.replayExtractedChunks(any(ChunkReplayContext.class), anyList()))
                .thenReturn(Map.of(
                        SURVIVOR_CHUNK, resultOf(replayEntity(SURVIVOR_CHUNK, ALICE, "Person", "d205")),
                        SURVIVOR_CHUNK_2, EntityExtractionResult.empty()));
        when(descriptionSummarizer.summarize(any(GraphMergeContext.class), eq(ALICE), anyString(), anyList()))
                .thenReturn("NEW-ALICE");
        when(embeddingClient.embed(eq(EMBED_PROFILE), anyString())).thenReturn(EMBEDDED);
        GraphRebuildPlan plan = service.compute(ctx, List.of(DELETED_CHUNK));

        // when：阶段 B（处理序按名升序 [Alice, Bob]）
        when(entityNodeRepository.lockByKbIdAndNames(KB_ID, List.of(ALICE, BOB))).thenReturn(List.of(
                graphNode(ALICE, aliceProps), graphNode(BOB, bobProps)));
        when(entityInfoVectorRepository.lockByKbIdAndName(KB_ID, ALICE))
                .thenReturn(Optional.of(lockedEntityVector(ALICE, List.of(DELETED_CHUNK, SURVIVOR_CHUNK),
                        "C-A", OLD_VECTOR)));
        when(entityInfoVectorRepository.lockByKbIdAndName(KB_ID, BOB))
                .thenReturn(Optional.of(lockedEntityVector(BOB, List.of(DELETED_CHUNK, SURVIVOR_CHUNK_2),
                        "C-B", OLD_VECTOR)));
        GraphRebuildReport report = service.apply(ctx, plan);

        // then：正常条目完成重算写回、降级条目原样保留语义；两条目账本均收缩为各自存活集合
        ArgumentCaptor<EntityNode> nodeCaptor = ArgumentCaptor.forClass(EntityNode.class);
        verify(entityNodeRepository, times(2)).upsert(nodeCaptor.capture(), eq(OPERATOR));
        Map<String, EntityProperties> written = new LinkedHashMap<>();
        nodeCaptor.getAllValues().forEach(node -> written.put(node.entityName(), node.properties()));
        assertEquals("NEW-ALICE", written.get(ALICE).description());
        assertEquals(List.of(SURVIVOR_CHUNK), written.get(ALICE).sourceIds());
        assertEquals("OLD-BOB", written.get(BOB).description(), "降级条目必须保留原语义字段");
        assertEquals("Organization", written.get(BOB).entityType());
        assertEquals(List.of(SURVIVOR_CHUNK_2), written.get(BOB).sourceIds());
        assertEquals(1, report.rebuiltEntries());
        assertEquals(1, report.degradedEntries());
        assertEquals(0, report.prunedEntries());
    }

    /**
     * 锁定关系侧写回纪律（与实体侧「直接删除条目被并发并入新来源」同构）：计划为直接删除端点对，
     * 但写回时锁定账本仍有存活来源 → MUST NOT 删除；无从重算语义 → 按降级保留原语义字段与
     * 内容-向量对，仅收缩账本，且阶段 B 零远程。
     */
    @Test
    void should_keepRelationEntryDegraded_when_apply_given_prunedPairRepopulatedByConcurrentMerge() {
        // given
        GraphRebuildContext ctx = context();
        GraphRebuildPlan plan = new GraphRebuildPlan(List.of(DELETED_CHUNK), List.of(), List.of(),
                List.of(), List.of(List.of(ALICE, BOB)));
        RelationProperties currentProps = new RelationProperties(3.0, "OLD-REL", List.of("任职"),
                List.of(SURVIVOR_CHUNK, CONCURRENT_CHUNK), List.of(SURVIVING_FILE));
        when(relationEdgeRepository.lockByKbIdAndUnorderedPair(KB_ID, ALICE, BOB))
                .thenReturn(List.of(new RelationEdge(KB_ID, ALICE, BOB, currentProps)));
        when(relationInfoVectorRepository.lockByKbIdAndUnorderedPair(KB_ID, ALICE, BOB))
                .thenReturn(Optional.of(RelationInfoVector.restore(9L, KB_ID, ALICE, BOB, "rel-content",
                        List.of(SURVIVOR_CHUNK, CONCURRENT_CHUNK), OLD_VECTOR, null, null)));

        // when
        GraphRebuildReport report = service.apply(ctx, plan);

        // then：不删除；按降级绝对值写回收缩账本，权重/描述/关键词与内容-向量对保持锁读原值
        verify(relationEdgeRepository, never()).deleteByKbIdAndUnorderedPair(any(), any(), any());
        verify(relationInfoVectorRepository, never()).deleteByKbIdAndUnorderedPair(any(), any(), any());
        ArgumentCaptor<List<RelationEdge>> edgeCaptor = ArgumentCaptor.forClass(List.class);
        verify(relationEdgeRepository).upsertAll(edgeCaptor.capture(), eq(OPERATOR));
        RelationProperties written = edgeCaptor.getValue().get(0).properties();
        assertEquals(3.0, written.weight(), 0.0001);
        assertEquals("OLD-REL", written.description());
        assertEquals(List.of("任职"), written.keywords());
        assertEquals(List.of(SURVIVOR_CHUNK, CONCURRENT_CHUNK), written.sourceIds());
        assertEquals(List.of(SURVIVING_FILE), written.filePaths());
        ArgumentCaptor<List<RelationInfoVector>> vectorCaptor = ArgumentCaptor.forClass(List.class);
        verify(relationInfoVectorRepository).upsertAll(vectorCaptor.capture(), eq(OPERATOR));
        assertEquals("rel-content", vectorCaptor.getValue().get(0).content());
        assertArrayEquals(OLD_VECTOR, vectorCaptor.getValue().get(0).vector());
        assertEquals(1, report.degradedEntries());
        assertEquals(0, report.prunedEntries());
        verifyNoInteractions(embeddingClient, descriptionSummarizer, entityExtractionService, knowledgeBaseApi);
    }

}
