package com.linkroa.deepdataagent.rag.domain.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import com.linkroa.deepdataagent.rag.domain.model.RelationHit;
import com.linkroa.deepdataagent.rag.domain.model.RetrievalCandidate;
import com.linkroa.deepdataagent.rag.domain.port.EmbeddingClient;
import com.linkroa.deepdataagent.rag.domain.repository.RetrievalRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link DefaultRecallService} 单元测试。
 *
 * <p>以 Mock 的 {@link EmbeddingClient} / {@link RetrievalRepository} /
 * {@link KnowledgeBaseApi} 离线驱动三路召回，不启动 Spring 容器；
 * 检索侧 embedding profileId 以知识库 {@code embedding_config} 为唯一真相源，
 * 故一律以 {@code knowledgeBaseApi.findEmbeddingModelProfileIdByKbId(kbId)} 打桩驱动
 * （与摄入侧切片向量化同源，不再有进程级全局配置）。</p>
 *
 * <p>覆盖场景：① MIX 三路候选通道标记与图谱结果组装（含批量预计算固定顺序）；
 * ② 图谱无命中 → GRAPH MISSING、VECTOR/BM25 不受影响；③ 全部下游调用携带 kbId
 * 与各自 limit；④ 关系路端点与实体路命中逐位交错并入实体候选集（relatedEdges 收到合并去重列表）、
 * 关联边并入关系视图（与关系路命中按归一对去重）；
 * ⑤ NAIVE 不触碰图谱仓储且仅打包 query 一条文本、阈值缺失等效不过滤；
 * ⑥ 单通道（VECTOR）抛异常其余通道仍返回；⑦ embedding 批量失败整体短路；
 * ⑧ null 入参非法；⑨ 检索 profileId 与知识库投影同源（含跨库隔离）；
 * ⑩ 知识库未配置嵌入模型 → 向量召回降级且零 embedding 调用；
 * ⑪ MIX 下 ll/hl 单侧为空 → 对应子路退化 WARN 可区分、剩单路行为不变、双侧皆空不落单侧告警；
 * ⑫ 关联边 Top 上限与每源 chunk 上限由请求侧覆盖生效（缺省仍取常量默认值）；
 * ⑬ 任务 2.9 新行为（对齐 spec「图谱通道召回与关联边扩展」）：命中携带图谱属性进视图、
 * ll/hl 逐位交错序（含端点双名展开）、关联边并入关系视图且边源 chunk 不依赖关系路命中、
 * 归一对去重关系路优先保分、E2 多背书块优先（同数保首现序）、E1 配额截断前缀且回正文入参同步收窄、
 * 缺口端点属性回查补齐/回查缺失裸名降级 WARN、每源上限+配额组合下 E1/E2 不回归。</p>
 *
 * <p><b>批次二机械适配说明</b>：值对象由 {@code GraphCommunityHit}/{@code EdgePair} 换为
 * 结构化的 {@link EntityHit}/{@link RelationHit}/{@link GraphEdgeHit}（新行为专项测试见任务 2.9），
 * 实体断言一律以 {@link #entityNames(KgSearchResult)} 取名称、关系断言以
 * {@link #relationPairs(KgSearchResult)} 取「源-目标」串，保持与适配前逐字段等价的可读断言。</p>
 *
 * @author DeepDataAgent
 */
@ExtendWith(MockitoExtension.class)
class DefaultRecallServiceTest {

    /** 浮点断言容差 */
    private static final double DELTA = 1e-9;

    /** 测试知识库ID */
    private static final Long KB_ID = 7L;

    /** 第二个测试知识库ID（跨库隔离用例） */
    private static final Long OTHER_KB_ID = 8L;

    /** 测试检索问题 */
    private static final String QUERY = "订单发货超时的处理规则是什么";

    /** KB_ID 配置的 embedding 模型 profileId（知识库投影打桩值） */
    private static final String EMBED_PROFILE = "embed-profile-1";

    /** OTHER_KB_ID 配置的 embedding 模型 profileId（证明跨库不共享进程级单值） */
    private static final String EMBED_PROFILE_OTHER = "embed-profile-2";

    /** 每通道向量召回数量（图谱实体路/关系路 limit） */
    private static final int TOP_K = 10;

    /** chunk 召回数量（VECTOR/BM25 通道 limit） */
    private static final int CHUNK_TOP_K = 5;

    /** 配置侧余弦相似度阈值（MIX 场景） */
    private static final float SIMILAR_THRESHOLD = 0.5F;

    /** 换算后的余弦距离阈值：1 - 0.5 = 0.5 */
    private static final double DISTANCE_THRESHOLD = 0.5D;

    /** 相似度阈值缺失换算后的余弦距离阈值：1 - 0 = 1.0（等效不过滤） */
    private static final double DEFAULT_DISTANCE_THRESHOLD = 1.0D;

    /** query 向量（批量预计算固定下标 0） */
    private static final float[] QUERY_VECTOR = {0.1F, 0.2F};

    /** ll 连接文本向量（批量预计算固定下标 1，实体路查询向量） */
    private static final float[] LL_VECTOR = {0.3F, 0.4F};

    /** hl 连接文本向量（批量预计算固定下标 2，关系路查询向量） */
    private static final float[] HL_VECTOR = {0.5F, 0.6F};

    /** query 向量的 pgvector 字面量（跨 knowledgebase 边界契约格式） */
    private static final String QUERY_VECTOR_LITERAL = "[0.1,0.2]";

    /** VECTOR 通道命中 chunkId */
    private static final Long CHUNK_VECTOR = 101L;

    /** BM25 通道命中 chunkId */
    private static final Long CHUNK_BM25 = 102L;

    /** GRAPH 通道命中 chunkId（实体源） */
    private static final Long CHUNK_GRAPH_1 = 201L;

    /** GRAPH 通道命中 chunkId（实体源） */
    private static final Long CHUNK_GRAPH_2 = 202L;

    /** GRAPH 通道命中 chunkId（关系源） */
    private static final Long CHUNK_GRAPH_3 = 203L;

    /** 实体路命中实体名 */
    private static final String ENTITY_USER = "用户";

    /** 关系路命中反查并入的实体名 */
    private static final String ENTITY_ORDER = "订单";

    /** 关系路命中关系的「源-目标」串表示（仅测试断言可读性用，值对象已改为原生双端点承载） */
    private static final String RELATION_NAME = "用户-订单";

    /** 低层关键词（实体路方向，空格连接后为单条向量化文本） */
    private static final List<String> LL_KEYWORDS = List.of(ENTITY_USER, ENTITY_ORDER);

    /** 高层关键词（关系路方向） */
    private static final List<String> HL_KEYWORDS = List.of("下单流程");

    /** 请求侧显式覆盖的关联边 Top 上限（与 RetrievalConstants.GRAPH_EDGE_TOP 常量值必须不同） */
    private static final int CUSTOM_EDGE_TOP = 12;

    /** 请求侧显式覆盖的每源 chunk 上限（与 RetrievalConstants.GRAPH_EDGE_CHUNK_LIMIT 必须不同） */
    private static final int CUSTOM_CHUNK_LIMIT = 2;

    /** 图谱单侧退化 WARN 正文标识（实体路与关系路共用前缀） */
    private static final String GRAPH_PATH_DEGRADE_MARKER = "路退化";

    /** 图谱命中创建时间夹具（固定时刻，保证值对象装配可复现） */
    private static final OffsetDateTime CREATED_AT = OffsetDateTime.parse("2024-05-01T08:30:00+08:00");

    /** embedding 端口 Mock */
    @Mock
    private EmbeddingClient embeddingClient;

    /** 检索侧只读仓储端口 Mock */
    @Mock
    private RetrievalRepository retrievalRepository;

    /** 知识库服务契约 Mock */
    @Mock
    private KnowledgeBaseApi knowledgeBaseApi;

    /** 被测三路召回服务 */
    @InjectMocks
    private DefaultRecallService recallService;

    @Test
    void should_returnThreeChannelCandidatesAndKgResult_when_recall_given_mixStrategy() {
        // given：MIX 全链路命中——VECTOR/BM25 各 1 条，实体路 1 命中（2 chunk）、
        // 关系路 1 命中（1 chunk），关联边与关系命中可反查（chunk 去重不新增）
        stubMixFullRecall();

        // when
        RecallService.RecallOutcome outcome = recallService.recall(query(), mixConfig());

        // then：一次批量预计算且文本按固定顺序 [query, ll连接, hl连接] 打包
        ArgumentCaptor<List<String>> textsCaptor = ArgumentCaptor.forClass(List.class);
        verify(embeddingClient).embedBatch(eq(EMBED_PROFILE), textsCaptor.capture());
        assertEquals(List.of(QUERY, "用户 订单", "下单流程"), textsCaptor.getValue());
        // then：候选按 VECTOR → BM25 → GRAPH 顺序合并，通道标记与通道内分数正确
        List<RetrievalCandidate> candidates = outcome.candidates();
        assertEquals(5, candidates.size());
        assertEquals(CHUNK_VECTOR, candidates.get(0).chunkId());
        assertEquals(RetrievalChannel.VECTOR, candidates.get(0).channel());
        assertEquals(0.92D, candidates.get(0).score(), DELTA);
        assertNull(candidates.get(0).sourceFile());
        assertEquals(CHUNK_BM25, candidates.get(1).chunkId());
        assertEquals(RetrievalChannel.BM25, candidates.get(1).channel());
        assertEquals(3.5D, candidates.get(1).score(), DELTA);
        assertEquals(CHUNK_GRAPH_1, candidates.get(2).chunkId());
        assertEquals(RetrievalChannel.GRAPH, candidates.get(2).channel());
        assertEquals(0.88D, candidates.get(2).score(), DELTA);
        assertEquals("graph-1.md", candidates.get(2).sourceFile());
        assertEquals(CHUNK_GRAPH_2, candidates.get(3).chunkId());
        assertEquals(RetrievalChannel.GRAPH, candidates.get(3).channel());
        assertEquals(CHUNK_GRAPH_3, candidates.get(4).chunkId());
        assertEquals(RetrievalChannel.GRAPH, candidates.get(4).channel());
        assertEquals(0.77D, candidates.get(4).score(), DELTA);
        // then：图谱结果为实体候选集（ll 命中与关系路端点逐位交错）+ 关系视图 + 有序 chunk
        //（实体源在前、关系源在后）
        assertEquals(List.of(ENTITY_USER, ENTITY_ORDER), entityNames(outcome.kgResult()));
        assertEquals(List.of(RELATION_NAME), relationPairs(outcome.kgResult()));
        assertEquals(List.of(CHUNK_GRAPH_1, CHUNK_GRAPH_2, CHUNK_GRAPH_3),
                outcome.kgResult().chunks().stream().map(chunk -> chunk.chunkId()).toList());
        assertEquals(0.88D, outcome.kgResult().chunks().get(0).score(), DELTA);
        assertEquals(0.77D, outcome.kgResult().chunks().get(2).score(), DELTA);
    }

    @Test
    void should_keepVectorAndBm25Candidates_when_recall_given_graphNoHits() {
        // given：VECTOR/BM25 正常命中，实体路与关系路均无命中（图谱 MISSING）
        stubEmbeddingProfile(KB_ID, EMBED_PROFILE);
        when(embeddingClient.embedBatch(eq(EMBED_PROFILE), anyList()))
                .thenReturn(List.of(QUERY_VECTOR, LL_VECTOR, HL_VECTOR));
        when(knowledgeBaseApi.searchByVector(eq(KB_ID), eq(DISTANCE_THRESHOLD),
                eq(QUERY_VECTOR_LITERAL), eq(CHUNK_TOP_K)))
                .thenReturn(List.of(new ChunkScoreDTO(CHUNK_VECTOR, 0.92D)));
        when(knowledgeBaseApi.searchByKeywords(eq(KB_ID), eq(QUERY), eq(CHUNK_TOP_K)))
                .thenReturn(List.of(new ChunkScoreDTO(CHUNK_BM25, 3.5D)));
        when(retrievalRepository.searchEntities(eq(KB_ID), eq(LL_VECTOR),
                eq(DISTANCE_THRESHOLD), eq(TOP_K))).thenReturn(List.of());
        when(retrievalRepository.searchRelations(eq(KB_ID), eq(HL_VECTOR),
                eq(DISTANCE_THRESHOLD), eq(TOP_K))).thenReturn(List.of());

        // when
        RecallService.RecallOutcome outcome = recallService.recall(query(), mixConfig());

        // then：仅 VECTOR/BM25 两候选，图谱 MISSING（空集合结果），不再发起关联边/chunk 查询
        List<RetrievalCandidate> candidates = outcome.candidates();
        assertEquals(2, candidates.size());
        assertEquals(RetrievalChannel.VECTOR, candidates.get(0).channel());
        assertEquals(RetrievalChannel.BM25, candidates.get(1).channel());
        assertTrue(outcome.kgResult().entities().isEmpty());
        assertTrue(outcome.kgResult().relations().isEmpty());
        assertTrue(outcome.kgResult().chunks().isEmpty());
        verify(retrievalRepository, never()).relatedEdges(any(), anyList(), anyInt());
        verify(retrievalRepository, never()).chunksOf(any(), anyList());
    }

    @Test
    void should_passKbIdAndChannelLimits_when_recall_given_mixStrategy() {
        // given：与场景①相同的全链路命中数据
        stubMixFullRecall();

        // when
        RecallService.RecallOutcome outcome = recallService.recall(query(), mixConfig());

        // then：全部下游检索调用均携带 kbId 单库隔离，limit 各按口径
        // （VECTOR/BM25 → chunkTopK；实体路/关系路 → topK；关联边 → GRAPH_EDGE_TOP）
        assertEquals(5, outcome.candidates().size());
        verify(knowledgeBaseApi).searchByVector(eq(KB_ID), eq(DISTANCE_THRESHOLD),
                eq(QUERY_VECTOR_LITERAL), eq(CHUNK_TOP_K));
        verify(knowledgeBaseApi).searchByKeywords(eq(KB_ID), eq(QUERY), eq(CHUNK_TOP_K));
        verify(retrievalRepository).searchEntities(eq(KB_ID), eq(LL_VECTOR),
                eq(DISTANCE_THRESHOLD), eq(TOP_K));
        verify(retrievalRepository).searchRelations(eq(KB_ID), eq(HL_VECTOR),
                eq(DISTANCE_THRESHOLD), eq(TOP_K));
        verify(retrievalRepository).relatedEdges(eq(KB_ID), anyList(),
                eq(RetrievalConstants.GRAPH_EDGE_TOP));
        verify(retrievalRepository).chunksOf(eq(KB_ID), anyList());
    }

    @Test
    void should_mergeRelationEndpointsIntoEntityCandidates_when_recall_given_relationPairHit() {
        // given：实体路命中「甲」（chunk 201），关系路命中「乙-丙」（chunk 202）；
        // 关联边返回「甲-乙」（关系路未命中，账本空）与「乙-丙」（与关系路命中同归一对，账本空）
        stubEmbeddingProfile(KB_ID, EMBED_PROFILE);
        when(embeddingClient.embedBatch(eq(EMBED_PROFILE), anyList()))
                .thenReturn(List.of(QUERY_VECTOR, LL_VECTOR, HL_VECTOR));
        when(knowledgeBaseApi.searchByVector(eq(KB_ID), eq(DISTANCE_THRESHOLD),
                eq(QUERY_VECTOR_LITERAL), eq(CHUNK_TOP_K))).thenReturn(List.of());
        when(knowledgeBaseApi.searchByKeywords(eq(KB_ID), eq(QUERY), eq(CHUNK_TOP_K)))
                .thenReturn(List.of());
        when(retrievalRepository.searchEntities(eq(KB_ID), eq(LL_VECTOR),
                eq(DISTANCE_THRESHOLD), eq(TOP_K)))
                .thenReturn(List.of(entityHit("甲", 0.9D, List.of(CHUNK_GRAPH_1))));
        when(retrievalRepository.searchRelations(eq(KB_ID), eq(HL_VECTOR),
                eq(DISTANCE_THRESHOLD), eq(TOP_K)))
                .thenReturn(List.of(relationHit("乙", "丙", 0.8D, List.of(CHUNK_GRAPH_2))));
        when(retrievalRepository.relatedEdges(eq(KB_ID), anyList(),
                eq(RetrievalConstants.GRAPH_EDGE_TOP)))
                .thenReturn(List.of(graphEdge("甲", "乙"), graphEdge("乙", "丙")));
        when(retrievalRepository.chunksOf(eq(KB_ID), anyList())).thenReturn(Map.of(
                CHUNK_GRAPH_1, graphChunkText(CHUNK_GRAPH_1, "graph-1.md"),
                CHUNK_GRAPH_2, graphChunkText(CHUNK_GRAPH_2, "graph-2.md")));

        // when
        RecallService.RecallOutcome outcome = recallService.recall(
                query(List.of("甲"), List.of("乙", "丙")), mixConfig());

        // then：relatedEdges 收到「实体路命中 ∪ 关系路端点」逐位交错去重列表
        ArgumentCaptor<List<String>> namesCaptor = ArgumentCaptor.forClass(List.class);
        verify(retrievalRepository).relatedEdges(eq(KB_ID), namesCaptor.capture(),
                eq(RetrievalConstants.GRAPH_EDGE_TOP));
        assertEquals(List.of("甲", "乙", "丙"), namesCaptor.getValue());
        // then：图谱实体候选集含反查端点；关系视图 = 关系路命中 ∪ 关联边（乙-丙 去重不重复出现）
        assertEquals(List.of("甲", "乙", "丙"), entityNames(outcome.kgResult()));
        assertEquals(List.of("乙-丙", "甲-乙"), relationPairs(outcome.kgResult()));
        // then：GRAPH 候选按实体源→关系源顺序组装（边账本为空，不新增候选）
        List<RetrievalCandidate> candidates = outcome.candidates();
        assertEquals(2, candidates.size());
        assertEquals(CHUNK_GRAPH_1, candidates.get(0).chunkId());
        assertEquals(RetrievalChannel.GRAPH, candidates.get(0).channel());
        assertEquals(0.9D, candidates.get(0).score(), DELTA);
        assertEquals(CHUNK_GRAPH_2, candidates.get(1).chunkId());
        assertEquals(0.8D, candidates.get(1).score(), DELTA);
    }

    @Test
    void should_logEntityPathDegradedWarning_when_recall_given_mixWithOnlyHlKeywords() {
        // given：MIX 且 ll 为空、hl 非空（实体路无检索锚）；关系路命中「用户-订单」仍照常并入端点
        ch.qos.logback.classic.Logger recallLogger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(DefaultRecallService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        recallLogger.addAppender(appender);
        try {
            stubEmbeddingProfile(KB_ID, EMBED_PROFILE);
            when(embeddingClient.embedBatch(eq(EMBED_PROFILE), anyList()))
                    .thenReturn(List.of(QUERY_VECTOR, HL_VECTOR));
            when(knowledgeBaseApi.searchByVector(eq(KB_ID), eq(DISTANCE_THRESHOLD),
                    eq(QUERY_VECTOR_LITERAL), eq(CHUNK_TOP_K))).thenReturn(List.of());
            when(knowledgeBaseApi.searchByKeywords(eq(KB_ID), eq(QUERY), eq(CHUNK_TOP_K)))
                    .thenReturn(List.of());
            when(retrievalRepository.searchRelations(eq(KB_ID), eq(HL_VECTOR),
                    eq(DISTANCE_THRESHOLD), eq(TOP_K)))
                    .thenReturn(List.of(relationHit(ENTITY_USER, ENTITY_ORDER, 0.77D, List.of(CHUNK_GRAPH_2))));
            when(retrievalRepository.relatedEdges(eq(KB_ID), anyList(), anyInt())).thenReturn(List.of());
            when(retrievalRepository.chunksOf(eq(KB_ID), anyList())).thenReturn(Map.of(
                    CHUNK_GRAPH_2, graphChunkText(CHUNK_GRAPH_2, "graph-2.md")));

            // when：仅 hl 有值（ll 传空表）
            RecallService.RecallOutcome outcome = assertDoesNotThrow(
                    () -> recallService.recall(query(List.of(), HL_KEYWORDS), mixConfig()));

            // then：实体路零查询、关系路与 GRAPH 候选照常产出（单侧退化不改行为）
            verify(retrievalRepository, never()).searchEntities(any(), any(), anyDouble(), anyInt());
            assertEquals(List.of(ENTITY_USER, ENTITY_ORDER), entityNames(outcome.kgResult()));
            assertEquals(List.of(RELATION_NAME), relationPairs(outcome.kgResult()));
            assertEquals(1, outcome.candidates().size());
            // then：仅打一条指明实体路退化的 WARN（关系路未退化不得误报）
            assertEquals(1, countGraphPathWarnings(appender));
            assertTrue(appender.list.stream().anyMatch(event -> event.getLevel() == Level.WARN
                    && event.getFormattedMessage().contains("实体路退化")));
            assertTrue(appender.list.stream().noneMatch(event ->
                    event.getFormattedMessage().contains("关系路退化")));
        } finally {
            recallLogger.detachAppender(appender);
        }
    }

    @Test
    void should_logRelationPathDegradedWarning_when_recall_given_mixWithOnlyLlKeywords() {
        // given：MIX 且 hl 为空、ll 非空（关系路无检索锚）；实体路命中仍照常产出图谱上下文
        ch.qos.logback.classic.Logger recallLogger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(DefaultRecallService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        recallLogger.addAppender(appender);
        try {
            stubEmbeddingProfile(KB_ID, EMBED_PROFILE);
            when(embeddingClient.embedBatch(eq(EMBED_PROFILE), anyList()))
                    .thenReturn(List.of(QUERY_VECTOR, LL_VECTOR));
            when(knowledgeBaseApi.searchByVector(eq(KB_ID), eq(DISTANCE_THRESHOLD),
                    eq(QUERY_VECTOR_LITERAL), eq(CHUNK_TOP_K))).thenReturn(List.of());
            when(knowledgeBaseApi.searchByKeywords(eq(KB_ID), eq(QUERY), eq(CHUNK_TOP_K)))
                    .thenReturn(List.of());
            when(retrievalRepository.searchEntities(eq(KB_ID), eq(LL_VECTOR),
                    eq(DISTANCE_THRESHOLD), eq(TOP_K)))
                    .thenReturn(List.of(entityHit(ENTITY_USER, 0.88D, List.of(CHUNK_GRAPH_1))));
            when(retrievalRepository.relatedEdges(eq(KB_ID), anyList(), anyInt())).thenReturn(List.of());
            when(retrievalRepository.chunksOf(eq(KB_ID), anyList())).thenReturn(Map.of(
                    CHUNK_GRAPH_1, graphChunkText(CHUNK_GRAPH_1, "graph-1.md")));

            // when：仅 ll 有值（hl 传空表）
            RecallService.RecallOutcome outcome = assertDoesNotThrow(
                    () -> recallService.recall(query(LL_KEYWORDS, List.of()), mixConfig()));

            // then：关系路零查询，实体路与关联边照常执行
            verify(retrievalRepository, never()).searchRelations(any(), any(), anyDouble(), anyInt());
            verify(retrievalRepository).relatedEdges(eq(KB_ID), anyList(), anyInt());
            assertEquals(List.of(ENTITY_USER), entityNames(outcome.kgResult()));
            assertEquals(1, outcome.candidates().size());
            // then：仅打一条指明关系路退化的 WARN
            assertEquals(1, countGraphPathWarnings(appender));
            assertTrue(appender.list.stream().anyMatch(event -> event.getLevel() == Level.WARN
                    && event.getFormattedMessage().contains("关系路退化")));
            assertTrue(appender.list.stream().noneMatch(event ->
                    event.getFormattedMessage().contains("实体路退化")));
        } finally {
            recallLogger.detachAppender(appender);
        }
    }

    @Test
    void should_notLogSinglePathWarning_when_recall_given_mixWithBothKeywordsEmpty() {
        // given：ll/hl 双侧皆空（属既有 keywordFailed 短路口径，不落单侧告警）
        ch.qos.logback.classic.Logger recallLogger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(DefaultRecallService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        recallLogger.addAppender(appender);
        try {
            stubEmbeddingProfile(KB_ID, EMBED_PROFILE);
            when(embeddingClient.embedBatch(eq(EMBED_PROFILE), anyList()))
                    .thenReturn(List.of(QUERY_VECTOR));
            when(knowledgeBaseApi.searchByVector(eq(KB_ID), eq(DISTANCE_THRESHOLD),
                    eq(QUERY_VECTOR_LITERAL), eq(CHUNK_TOP_K))).thenReturn(List.of());
            when(knowledgeBaseApi.searchByKeywords(eq(KB_ID), eq(QUERY), eq(CHUNK_TOP_K)))
                    .thenReturn(List.of());

            // when
            RecallService.RecallOutcome outcome = recallService.recall(query(List.of(), List.of()), mixConfig());

            // then：图谱 MISSING 且零单侧告警（两路本就不执行）
            assertTrue(outcome.kgResult().entities().isEmpty());
            assertEquals(0, countGraphPathWarnings(appender));
        } finally {
            recallLogger.detachAppender(appender);
        }
    }

    @Test
    void should_useRequestGraphLimits_when_recall_given_explicitGraphLimits() {
        // given：请求侧显式覆盖关联边 Top 上限（每源 chunk 上限走默认），仅实体路命中
        stubEmbeddingProfile(KB_ID, EMBED_PROFILE);
        when(embeddingClient.embedBatch(eq(EMBED_PROFILE), anyList()))
                .thenReturn(List.of(QUERY_VECTOR, LL_VECTOR, HL_VECTOR));
        when(knowledgeBaseApi.searchByVector(eq(KB_ID), eq(DISTANCE_THRESHOLD),
                eq(QUERY_VECTOR_LITERAL), eq(CHUNK_TOP_K))).thenReturn(List.of());
        when(knowledgeBaseApi.searchByKeywords(eq(KB_ID), eq(QUERY), eq(CHUNK_TOP_K)))
                .thenReturn(List.of());
        when(retrievalRepository.searchEntities(eq(KB_ID), eq(LL_VECTOR),
                eq(DISTANCE_THRESHOLD), eq(TOP_K)))
                .thenReturn(List.of(entityHit(ENTITY_USER, 0.88D, List.of(CHUNK_GRAPH_1))));
        when(retrievalRepository.searchRelations(eq(KB_ID), eq(HL_VECTOR),
                eq(DISTANCE_THRESHOLD), eq(TOP_K))).thenReturn(List.of());
        when(retrievalRepository.relatedEdges(eq(KB_ID), anyList(), eq(CUSTOM_EDGE_TOP)))
                .thenReturn(List.of());
        when(retrievalRepository.chunksOf(eq(KB_ID), anyList())).thenReturn(Map.of(
                CHUNK_GRAPH_1, graphChunkText(CHUNK_GRAPH_1, "graph-1.md")));

        // when
        recallService.recall(queryWithGraphLimits(CUSTOM_EDGE_TOP, null), mixConfig());

        // then：relatedEdges 收到请求侧上限（而非 RetrievalConstants 常量），常量值从未被使用
        verify(retrievalRepository).relatedEdges(eq(KB_ID), anyList(), eq(CUSTOM_EDGE_TOP));
        verify(retrievalRepository, never()).relatedEdges(eq(KB_ID), anyList(),
                eq(RetrievalConstants.GRAPH_EDGE_TOP));
    }

    @Test
    void should_capChunksPerSource_when_collectGraphChunks_given_smallerChunkLimit() {
        // given：实体路命中自带 3 个 chunk，但请求侧每源上限收紧为 2
        stubEmbeddingProfile(KB_ID, EMBED_PROFILE);
        when(embeddingClient.embedBatch(eq(EMBED_PROFILE), anyList()))
                .thenReturn(List.of(QUERY_VECTOR, LL_VECTOR, HL_VECTOR));
        when(knowledgeBaseApi.searchByVector(eq(KB_ID), eq(DISTANCE_THRESHOLD),
                eq(QUERY_VECTOR_LITERAL), eq(CHUNK_TOP_K))).thenReturn(List.of());
        when(knowledgeBaseApi.searchByKeywords(eq(KB_ID), eq(QUERY), eq(CHUNK_TOP_K)))
                .thenReturn(List.of());
        when(retrievalRepository.searchEntities(eq(KB_ID), eq(LL_VECTOR),
                eq(DISTANCE_THRESHOLD), eq(TOP_K)))
                .thenReturn(List.of(entityHit(ENTITY_USER, 0.88D,
                        List.of(CHUNK_GRAPH_1, CHUNK_GRAPH_2, CHUNK_GRAPH_3))));
        when(retrievalRepository.searchRelations(eq(KB_ID), eq(HL_VECTOR),
                eq(DISTANCE_THRESHOLD), eq(TOP_K))).thenReturn(List.of());
        when(retrievalRepository.relatedEdges(eq(KB_ID), anyList(), anyInt())).thenReturn(List.of());
        when(retrievalRepository.chunksOf(eq(KB_ID), anyList())).thenReturn(Map.of(
                CHUNK_GRAPH_1, graphChunkText(CHUNK_GRAPH_1, "graph-1.md"),
                CHUNK_GRAPH_2, graphChunkText(CHUNK_GRAPH_2, "graph-2.md"),
                CHUNK_GRAPH_3, graphChunkText(CHUNK_GRAPH_3, "graph-3.md")));

        // when
        RecallService.RecallOutcome outcome = recallService.recall(
                queryWithGraphLimits(null, CUSTOM_CHUNK_LIMIT), mixConfig());

        // then：每源仅取前 2 个 chunk（第 3 个不入候选，回取参数同样只含 2 个 id）
        assertEquals(List.of(CHUNK_GRAPH_1, CHUNK_GRAPH_2),
                outcome.kgResult().chunks().stream().map(chunk -> chunk.chunkId()).toList());
        ArgumentCaptor<List<Long>> idsCaptor = ArgumentCaptor.forClass(List.class);
        verify(retrievalRepository).chunksOf(eq(KB_ID), idsCaptor.capture());
        assertEquals(List.of(CHUNK_GRAPH_1, CHUNK_GRAPH_2), idsCaptor.getValue());
    }

    @Test
    void should_skipGraphRepositoryAndEmbedQueryOnly_when_recall_given_naiveStrategy() {
        // given：NAIVE 策略且相似度阈值缺失（距离阈值按 1.0 等效不过滤）
        stubEmbeddingProfile(KB_ID, EMBED_PROFILE);
        when(embeddingClient.embedBatch(eq(EMBED_PROFILE), anyList()))
                .thenReturn(List.of(QUERY_VECTOR));
        when(knowledgeBaseApi.searchByVector(eq(KB_ID), eq(DEFAULT_DISTANCE_THRESHOLD),
                eq(QUERY_VECTOR_LITERAL), eq(CHUNK_TOP_K)))
                .thenReturn(List.of(new ChunkScoreDTO(CHUNK_VECTOR, 0.92D)));
        when(knowledgeBaseApi.searchByKeywords(eq(KB_ID), eq(QUERY), eq(CHUNK_TOP_K)))
                .thenReturn(List.of(new ChunkScoreDTO(CHUNK_BM25, 3.5D)));
        RetrievalQuery q = query();

        // when
        RecallService.RecallOutcome outcome = recallService.recall(q, naiveConfig());

        // then：NAIVE 仅打包 query 一条文本（不预计算关键词向量）
        ArgumentCaptor<List<String>> textsCaptor = ArgumentCaptor.forClass(List.class);
        verify(embeddingClient).embedBatch(eq(EMBED_PROFILE), textsCaptor.capture());
        assertEquals(List.of(QUERY), textsCaptor.getValue());
        // then：仅 VECTOR/BM25 双通道，图谱仓储零交互，图谱结果为空集合
        List<RetrievalCandidate> candidates = outcome.candidates();
        assertEquals(2, candidates.size());
        assertEquals(RetrievalChannel.VECTOR, candidates.get(0).channel());
        assertEquals(RetrievalChannel.BM25, candidates.get(1).channel());
        assertTrue(outcome.kgResult().entities().isEmpty());
        assertTrue(outcome.kgResult().relations().isEmpty());
        assertTrue(outcome.kgResult().chunks().isEmpty());
        verifyNoInteractions(retrievalRepository);
    }

    /**
     * 场景（converge-rag-hot-path-object-creation / 5.6）：NAIVE 策略连续两次召回，
     * 空图谱结果经常量 {@code EMPTY_KG_RESULT} 返回。
     * 预期：两次返回同一不可变实例（record 可安全共享、热路径零重复分配）。
     */
    @Test
    void should_shareSameEmptyKgResultInstance_when_recall_given_repeatedNaiveCalls() {
        // given：NAIVE 可正常走完的最小桩
        stubEmbeddingProfile(KB_ID, EMBED_PROFILE);
        when(embeddingClient.embedBatch(eq(EMBED_PROFILE), anyList()))
                .thenReturn(List.of(QUERY_VECTOR));
        when(knowledgeBaseApi.searchByVector(eq(KB_ID), eq(DEFAULT_DISTANCE_THRESHOLD),
                eq(QUERY_VECTOR_LITERAL), eq(CHUNK_TOP_K)))
                .thenReturn(List.of(new ChunkScoreDTO(CHUNK_VECTOR, 0.92D)));
        when(knowledgeBaseApi.searchByKeywords(eq(KB_ID), eq(QUERY), eq(CHUNK_TOP_K)))
                .thenReturn(List.of(new ChunkScoreDTO(CHUNK_BM25, 3.5D)));

        // when：连续两次 NAIVE 召回
        RecallService.RecallOutcome first = recallService.recall(query(), naiveConfig());
        RecallService.RecallOutcome second = recallService.recall(query(), naiveConfig());

        // then：空图谱结果为共享常量实例
        assertTrue(first.kgResult().entities().isEmpty());
        assertSame(first.kgResult(), second.kgResult(), "NAIVE 空图谱结果应复用同一不可变常量实例");
    }

    @Test
    void should_returnOtherChannels_when_recall_given_vectorChannelThrows() {
        // given：VECTOR 通道远程异常，BM25 与 GRAPH 正常（实体路 1 命中含 chunk 201）
        stubEmbeddingProfile(KB_ID, EMBED_PROFILE);
        when(embeddingClient.embedBatch(eq(EMBED_PROFILE), anyList()))
                .thenReturn(List.of(QUERY_VECTOR, LL_VECTOR, HL_VECTOR));
        when(knowledgeBaseApi.searchByVector(eq(KB_ID), eq(DISTANCE_THRESHOLD),
                anyString(), eq(CHUNK_TOP_K))).thenThrow(new RuntimeException("向量检索服务不可用"));
        when(knowledgeBaseApi.searchByKeywords(eq(KB_ID), eq(QUERY), eq(CHUNK_TOP_K)))
                .thenReturn(List.of(new ChunkScoreDTO(CHUNK_BM25, 3.5D)));
        when(retrievalRepository.searchEntities(eq(KB_ID), eq(LL_VECTOR),
                eq(DISTANCE_THRESHOLD), eq(TOP_K)))
                .thenReturn(List.of(entityHit(ENTITY_USER, 0.9D, List.of(CHUNK_GRAPH_1))));
        when(retrievalRepository.searchRelations(eq(KB_ID), eq(HL_VECTOR),
                eq(DISTANCE_THRESHOLD), eq(TOP_K))).thenReturn(List.of());
        when(retrievalRepository.relatedEdges(eq(KB_ID), anyList(),
                eq(RetrievalConstants.GRAPH_EDGE_TOP))).thenReturn(List.of());
        when(retrievalRepository.chunksOf(eq(KB_ID), anyList())).thenReturn(Map.of(
                CHUNK_GRAPH_1, graphChunkText(CHUNK_GRAPH_1, "graph-1.md")));

        // when
        RecallService.RecallOutcome outcome = recallService.recall(query(), mixConfig());

        // then：VECTOR 降级为空，BM25 与 GRAPH 候选仍完整返回，不向调用方抛异常
        List<RetrievalCandidate> candidates = outcome.candidates();
        assertEquals(2, candidates.size());
        assertEquals(CHUNK_BM25, candidates.get(0).chunkId());
        assertEquals(RetrievalChannel.BM25, candidates.get(0).channel());
        assertEquals(CHUNK_GRAPH_1, candidates.get(1).chunkId());
        assertEquals(RetrievalChannel.GRAPH, candidates.get(1).channel());
        assertEquals(0.9D, candidates.get(1).score(), DELTA);
        assertEquals(List.of(ENTITY_USER), entityNames(outcome.kgResult()));
    }

    @Test
    void should_returnEmptyOutcomeWithoutDownstream_when_recall_given_embeddingFails() {
        // given：知识库已配置嵌入模型，但 embedding 批量调用抛异常（远程服务不可用）
        stubEmbeddingProfile(KB_ID, EMBED_PROFILE);
        when(embeddingClient.embedBatch(eq(EMBED_PROFILE), anyList()))
                .thenThrow(new RuntimeException("embedding 服务不可用"));

        // when
        RecallService.RecallOutcome outcome = recallService.recall(query(), mixConfig());

        // then：整体短路返回空上下文，BM25/图谱等下游均不触达（仅读取过一次模型配置投影）
        assertTrue(outcome.candidates().isEmpty());
        assertTrue(outcome.kgResult().entities().isEmpty());
        assertTrue(outcome.kgResult().relations().isEmpty());
        assertTrue(outcome.kgResult().chunks().isEmpty());
        verify(knowledgeBaseApi, never()).searchByVector(any(), anyDouble(), any(), anyInt());
        verify(knowledgeBaseApi, never()).searchByKeywords(any(), any(), anyInt());
        verifyNoInteractions(retrievalRepository);
    }

    @Test
    void should_passSameProfileIdAsKbConfig_when_embedBatch_given_kbEmbeddingConfigured() {
        // given：知识库投影返回唯一 profileId，NAIVE 策略仅需 query 一条文本
        stubEmbeddingProfile(KB_ID, EMBED_PROFILE);
        when(embeddingClient.embedBatch(any(), anyList())).thenReturn(List.of(QUERY_VECTOR));

        // when
        recallService.recall(query(), naiveConfig());

        // then：送入 embedding 端口的 profileId 与知识库投影返回值同源一致（写入/检索不同源缺陷的回归锁）
        ArgumentCaptor<String> profileCaptor = ArgumentCaptor.forClass(String.class);
        verify(embeddingClient).embedBatch(profileCaptor.capture(), anyList());
        assertEquals(EMBED_PROFILE, profileCaptor.getValue());
    }

    @Test
    void should_skipEmbeddingAndReturnEmptyOutcome_when_recall_given_kbEmbeddingProfileNotConfigured() {
        // given：知识库未配置嵌入模型（只读投影返回 null，不回退任何全局默认值）
        stubEmbeddingProfile(KB_ID, null);

        // when
        RecallService.RecallOutcome outcome = recallService.recall(query(), mixConfig());

        // then：向量召回显式降级，不发起任何 embedding 调用，整体短路返回空上下文
        assertTrue(outcome.candidates().isEmpty());
        assertTrue(outcome.kgResult().entities().isEmpty());
        verify(embeddingClient, never()).embedBatch(any(), anyList());
        verify(knowledgeBaseApi, never()).searchByVector(any(), anyDouble(), any(), anyInt());
        verify(knowledgeBaseApi, never()).searchByKeywords(any(), any(), anyInt());
        verifyNoInteractions(retrievalRepository);
    }

    @Test
    void should_skipEmbeddingAndReturnEmptyOutcome_when_recall_given_kbEmbeddingProfileBlank() {
        // given：嵌入配置存在但 profileId 为空白串（边界值，同样视为未配置）
        stubEmbeddingProfile(KB_ID, "   ");

        // when
        RecallService.RecallOutcome outcome = recallService.recall(query(), mixConfig());

        // then：与未配置同口径降级，零 embedding 调用
        assertTrue(outcome.candidates().isEmpty());
        verify(embeddingClient, never()).embedBatch(any(), anyList());
        verifyNoInteractions(retrievalRepository);
    }

    @Test
    void should_useEachKbOwnProfileId_when_recall_given_differentKbEmbeddingConfigs() {
        // given：两个知识库分别配置不同嵌入模型，均按 NAIVE 只打包 query
        stubEmbeddingProfile(KB_ID, EMBED_PROFILE);
        stubEmbeddingProfile(OTHER_KB_ID, EMBED_PROFILE_OTHER);
        when(embeddingClient.embedBatch(any(), anyList())).thenReturn(List.of(QUERY_VECTOR));

        // when：同一进程内先检索 KB_ID、再检索 OTHER_KB_ID
        recallService.recall(query(), naiveConfig());
        recallService.recall(query(OTHER_KB_ID), naiveConfig());

        // then：两次 embedBatch 分别拿到各自库的 profileId（不再共享进程级单值）
        ArgumentCaptor<String> profileCaptor = ArgumentCaptor.forClass(String.class);
        verify(embeddingClient, times(2)).embedBatch(profileCaptor.capture(), anyList());
        assertEquals(List.of(EMBED_PROFILE, EMBED_PROFILE_OTHER), profileCaptor.getAllValues());
        verify(knowledgeBaseApi).findEmbeddingModelProfileIdByKbId(KB_ID);
        verify(knowledgeBaseApi).findEmbeddingModelProfileIdByKbId(OTHER_KB_ID);
    }

    @Test
    void should_throwIllegalArgument_when_recall_given_nullInput() {
        // given：合法检索请求与策略配置各备一份
        RetrievalQuery q = query();
        RetrievalStrategyConfig cfg = mixConfig();

        // when & then：null 入参快速失败，不触达任何下游
        assertThrows(IllegalArgumentException.class, () -> recallService.recall(null, cfg));
        assertThrows(IllegalArgumentException.class, () -> recallService.recall(q, null));
        verifyNoInteractions(embeddingClient, knowledgeBaseApi, retrievalRepository);
    }

    // ===== 任务 2.9 新行为专项（对齐 spec「图谱通道召回与关联边扩展」16 场景） =====

    @Test
    void should_carryGraphAttributesIntoView_when_recall_given_richEntityAndRelationHits() {
        // given：实体路命中「张三」与关系路命中「张三-李四」均自带完整图表属性
        stubGraphBase();
        when(retrievalRepository.searchEntities(eq(KB_ID), eq(LL_VECTOR),
                eq(DISTANCE_THRESHOLD), eq(TOP_K)))
                .thenReturn(List.of(entityHit("张三", 0.88D, List.of(CHUNK_GRAPH_1))));
        when(retrievalRepository.searchRelations(eq(KB_ID), eq(HL_VECTOR),
                eq(DISTANCE_THRESHOLD), eq(TOP_K)))
                .thenReturn(List.of(relationHit("张三", "李四", 0.77D, List.of(CHUNK_GRAPH_3))));
        when(retrievalRepository.relatedEdges(eq(KB_ID), anyList(), anyInt())).thenReturn(List.of());
        when(retrievalRepository.chunksOf(eq(KB_ID), anyList())).thenReturn(Map.of(
                CHUNK_GRAPH_1, graphChunkText(CHUNK_GRAPH_1, "graph-1.md"),
                CHUNK_GRAPH_3, graphChunkText(CHUNK_GRAPH_3, "graph-3.md")));

        // when
        RecallService.RecallOutcome outcome = recallService.recall(query(), mixConfig());

        // then：实体命中携带类型/描述/来源文件/时间进视图（非裸名）
        EntityHit zhang = outcome.kgResult().entities().stream()
                .filter(hit -> "张三".equals(hit.name())).findFirst().orElseThrow();
        assertEquals("类型-张三", zhang.entityType());
        assertEquals("描述-张三", zhang.description());
        assertEquals(List.of("docs/张三.md"), zhang.filePaths());
        assertEquals(CREATED_AT, zhang.createdAt());
        // then：关系命中携带描述/关键词/权重/来源文件列表/时间进视图
        RelationHit relation = outcome.kgResult().relations().get(0);
        assertEquals("关系描述", relation.description());
        assertEquals(List.of("关系关键词"), relation.keywords());
        assertEquals(1.0D, relation.weight(), DELTA);
        assertEquals(List.of("docs/relation.md"), relation.filePaths());
        assertEquals(CREATED_AT, relation.createdAt());
    }

    @Test
    void should_interleaveEntityCandidatesPositionByPosition_when_recall_given_llAndRelationEndpoints() {
        // given：实体路命中 E1、E2（相似度降序），关系路命中两条（各贡献源/目标两端点）
        stubGraphBase();
        when(retrievalRepository.searchEntities(eq(KB_ID), eq(LL_VECTOR),
                eq(DISTANCE_THRESHOLD), eq(TOP_K)))
                .thenReturn(List.of(entityHit("E1", 0.9D, List.of()), entityHit("E2", 0.8D, List.of())));
        when(retrievalRepository.searchRelations(eq(KB_ID), eq(HL_VECTOR),
                eq(DISTANCE_THRESHOLD), eq(TOP_K)))
                .thenReturn(List.of(relationHit("R1a", "R1b", 0.7D, List.of()),
                        relationHit("R2a", "R2b", 0.6D, List.of())));
        when(retrievalRepository.relatedEdges(eq(KB_ID), anyList(), anyInt())).thenReturn(List.of());

        // when
        RecallService.RecallOutcome outcome = recallService.recall(query(), mixConfig());

        // then：候选集逐位交错 ll₁,hl₁,ll₂,hl₂…（每关系命中按源、目标顺序展开为端点流）
        List<String> expectedOrder = List.of("E1", "R1a", "E2", "R1b", "R2a", "R2b");
        ArgumentCaptor<List<String>> namesCaptor = ArgumentCaptor.forClass(List.class);
        verify(retrievalRepository).relatedEdges(eq(KB_ID), namesCaptor.capture(), anyInt());
        assertEquals(expectedOrder, namesCaptor.getValue());
        assertEquals(expectedOrder, entityNames(outcome.kgResult()));
    }

    @Test
    void should_produceEdgeSourceChunkAndIncludeEdgeInRelationView_when_recall_given_edgeWithoutRelationHit() {
        // given：仅实体路命中 A（chunk 201），关系路零命中；关联边 A-B（关系路未命中该对）自带账本 205
        stubGraphBase();
        when(retrievalRepository.searchEntities(eq(KB_ID), eq(LL_VECTOR),
                eq(DISTANCE_THRESHOLD), eq(TOP_K)))
                .thenReturn(List.of(entityHit("A", 0.9D, List.of(CHUNK_GRAPH_1))));
        when(retrievalRepository.searchRelations(eq(KB_ID), eq(HL_VECTOR),
                eq(DISTANCE_THRESHOLD), eq(TOP_K))).thenReturn(List.of());
        when(retrievalRepository.relatedEdges(eq(KB_ID), anyList(), anyInt()))
                .thenReturn(List.of(graphEdgeWithChunks("A", "B", List.of(205L))));
        when(retrievalRepository.chunksOf(eq(KB_ID), anyList())).thenReturn(Map.of(
                CHUNK_GRAPH_1, graphChunkText(CHUNK_GRAPH_1, "graph-1.md"),
                205L, graphChunkText(205L, "edge.md")));

        // when
        RecallService.RecallOutcome outcome = recallService.recall(query(), mixConfig());

        // then：边以关系记录并入关系视图（关系路本无该对命中）
        assertEquals(1, outcome.kgResult().relations().size());
        assertEquals(List.of("A", "B"), outcome.kgResult().relations().get(0).normalizedPair());
        // then：边源 chunk（205）不依赖关系路命中仍产出，进入 GRAPH 候选
        List<Long> graphChunks = outcome.candidates().stream()
                .filter(candidate -> candidate.channel() == RetrievalChannel.GRAPH)
                .map(candidate -> candidate.chunkId()).toList();
        assertTrue(graphChunks.contains(CHUNK_GRAPH_1));
        assertTrue(graphChunks.contains(205L));
    }

    @Test
    void should_keepRelationHitScore_when_recall_given_edgeDuplicatesNormalizedPair() {
        // given：关系路命中 C-D（相似度 0.8，账本 203），关联边 C-D 权重 5.0（账本 207），归一对相同
        stubGraphBase();
        when(retrievalRepository.searchEntities(eq(KB_ID), eq(LL_VECTOR),
                eq(DISTANCE_THRESHOLD), eq(TOP_K)))
                .thenReturn(List.of(entityHit("A", 0.9D, List.of(CHUNK_GRAPH_1))));
        when(retrievalRepository.searchRelations(eq(KB_ID), eq(HL_VECTOR),
                eq(DISTANCE_THRESHOLD), eq(TOP_K)))
                .thenReturn(List.of(relationHit("C", "D", 0.8D, List.of(CHUNK_GRAPH_3))));
        when(retrievalRepository.relatedEdges(eq(KB_ID), anyList(), anyInt()))
                .thenReturn(List.of(new GraphEdgeHit("C", "D", 2, 5.0D, List.of(207L),
                        "边描述", List.of(), List.of("edge.md"), CREATED_AT)));
        when(retrievalRepository.chunksOf(eq(KB_ID), anyList())).thenReturn(Map.of(
                CHUNK_GRAPH_1, graphChunkText(CHUNK_GRAPH_1, "graph-1.md"),
                CHUNK_GRAPH_3, graphChunkText(CHUNK_GRAPH_3, "graph-3.md")));

        // when
        RecallService.RecallOutcome outcome = recallService.recall(query(), mixConfig());

        // then：归一对去重后关系视图仅一条，且保留关系路相似度分（非边权重）
        List<RelationHit> relations = outcome.kgResult().relations().stream()
                .filter(relation -> List.of("C", "D").equals(relation.normalizedPair())).toList();
        assertEquals(1, relations.size());
        assertEquals(0.8D, relations.get(0).score(), DELTA);
        // then：被去重丢弃的边账本（207）不进入 GRAPH 候选
        List<Long> graphChunks = outcome.candidates().stream()
                .map(candidate -> candidate.chunkId()).toList();
        assertTrue(graphChunks.contains(CHUNK_GRAPH_3));
        assertFalse(graphChunks.contains(207L));
    }

    @Test
    void should_rankEndorsedChunkFirst_when_recall_given_chunkReferencedByMultipleSources() {
        // given：实体 A 引用 [201,202]，关系 C-D 引用 [201,203]——201 被两源背书（2 次）优先，
        //        202 与 203 各 1 次按首现序保留（A 先贡献 202）
        stubGraphBase();
        when(retrievalRepository.searchEntities(eq(KB_ID), eq(LL_VECTOR),
                eq(DISTANCE_THRESHOLD), eq(TOP_K)))
                .thenReturn(List.of(entityHit("A", 0.9D, List.of(CHUNK_GRAPH_1, CHUNK_GRAPH_2))));
        when(retrievalRepository.searchRelations(eq(KB_ID), eq(HL_VECTOR),
                eq(DISTANCE_THRESHOLD), eq(TOP_K)))
                .thenReturn(List.of(relationHit("C", "D", 0.7D, List.of(CHUNK_GRAPH_1, CHUNK_GRAPH_3))));
        when(retrievalRepository.relatedEdges(eq(KB_ID), anyList(), anyInt())).thenReturn(List.of());
        when(retrievalRepository.chunksOf(eq(KB_ID), anyList())).thenReturn(Map.of(
                CHUNK_GRAPH_1, graphChunkText(CHUNK_GRAPH_1, "a.md"),
                CHUNK_GRAPH_2, graphChunkText(CHUNK_GRAPH_2, "b.md"),
                CHUNK_GRAPH_3, graphChunkText(CHUNK_GRAPH_3, "c.md")));

        // when
        RecallService.RecallOutcome outcome = recallService.recall(query(), mixConfig());

        // then：通道内次序 = 多背书块 201 先于单背书块 202、203（后两者同次数保首现序）
        assertEquals(List.of(CHUNK_GRAPH_1, CHUNK_GRAPH_2, CHUNK_GRAPH_3),
                outcome.kgResult().chunks().stream().map(chunk -> chunk.chunkId()).toList());
    }

    @Test
    void should_truncateGraphCandidatesToQuotaPrefix_when_recall_given_candidatesExceedChunkTopK() {
        // given：入场配额（chunkTopK）收紧为 2，实体 A 账本 [201,202,203] 三块均单背书
        stubGraphBase();
        when(retrievalRepository.searchEntities(eq(KB_ID), eq(LL_VECTOR),
                eq(DISTANCE_THRESHOLD), eq(TOP_K)))
                .thenReturn(List.of(entityHit("A", 0.9D, List.of(CHUNK_GRAPH_1, CHUNK_GRAPH_2, CHUNK_GRAPH_3))));
        when(retrievalRepository.searchRelations(eq(KB_ID), eq(HL_VECTOR),
                eq(DISTANCE_THRESHOLD), eq(TOP_K))).thenReturn(List.of());
        when(retrievalRepository.relatedEdges(eq(KB_ID), anyList(), anyInt())).thenReturn(List.of());
        when(retrievalRepository.chunksOf(eq(KB_ID), anyList())).thenReturn(Map.of(
                CHUNK_GRAPH_1, graphChunkText(CHUNK_GRAPH_1, "a.md"),
                CHUNK_GRAPH_2, graphChunkText(CHUNK_GRAPH_2, "b.md"),
                CHUNK_GRAPH_3, graphChunkText(CHUNK_GRAPH_3, "c.md")));

        // when
        RecallService.RecallOutcome outcome = recallService.recall(queryWith(2, null, null), mixConfig());

        // then：GRAPH 候选截断为配额内前缀 [201,202]，第 3 块丢弃
        List<Long> graphChunks = outcome.candidates().stream()
                .map(candidate -> candidate.chunkId()).toList();
        assertEquals(List.of(CHUNK_GRAPH_1, CHUNK_GRAPH_2), graphChunks);
        // then：回正文入参同步收窄为配额内前缀
        ArgumentCaptor<List<Long>> idsCaptor = ArgumentCaptor.forClass(List.class);
        verify(retrievalRepository).chunksOf(eq(KB_ID), idsCaptor.capture());
        assertEquals(List.of(CHUNK_GRAPH_1, CHUNK_GRAPH_2), idsCaptor.getValue());
    }

    @Test
    void should_enrichGapEndpointWithAttributes_when_recall_given_findEntityAttributesHit() {
        // given：实体路命中 A，关系路命中 A-B（0.8，账本 202）——B 为缺口端点，回查命中携带描述与账本
        stubGraphBase();
        when(retrievalRepository.searchEntities(eq(KB_ID), eq(LL_VECTOR),
                eq(DISTANCE_THRESHOLD), eq(TOP_K)))
                .thenReturn(List.of(entityHit("A", 0.9D, List.of(CHUNK_GRAPH_1))));
        when(retrievalRepository.searchRelations(eq(KB_ID), eq(HL_VECTOR),
                eq(DISTANCE_THRESHOLD), eq(TOP_K)))
                .thenReturn(List.of(relationHit("A", "B", 0.8D, List.of(CHUNK_GRAPH_2))));
        when(retrievalRepository.findEntityAttributes(eq(KB_ID), eq(List.of("B"))))
                .thenReturn(Map.of("B", new EntityHit("B", 0.0D, List.of(CHUNK_GRAPH_3),
                        "类型B", "描述B", List.of("docs/B.md"), CREATED_AT)));
        when(retrievalRepository.relatedEdges(eq(KB_ID), anyList(), anyInt())).thenReturn(List.of());
        when(retrievalRepository.chunksOf(eq(KB_ID), anyList())).thenReturn(Map.of(
                CHUNK_GRAPH_1, graphChunkText(CHUNK_GRAPH_1, "a.md"),
                CHUNK_GRAPH_2, graphChunkText(CHUNK_GRAPH_2, "rel.md"),
                CHUNK_GRAPH_3, graphChunkText(CHUNK_GRAPH_3, "b.md")));

        // when
        RecallService.RecallOutcome outcome = recallService.recall(query(), mixConfig());

        // then：缺口端点 B 经回查获得描述与账本，分数取来源关系相似度（0.8）
        EntityHit b = outcome.kgResult().entities().stream()
                .filter(hit -> "B".equals(hit.name())).findFirst().orElseThrow();
        assertEquals("描述B", b.description());
        assertEquals("类型B", b.entityType());
        assertEquals(0.8D, b.score(), DELTA);
        assertEquals(List.of(CHUNK_GRAPH_3), b.chunkIds());
        // then：回查补齐的端点账本参与三源抽取，B 的 chunk（203）进入候选
        assertTrue(outcome.candidates().stream()
                .anyMatch(candidate -> CHUNK_GRAPH_3.equals(candidate.chunkId())));
    }

    /**
     * 场景：关系路命中 A-B，端点 B 未被实体路命中且属性回查缺失——悬空端点自
     * 图合并「不补建占位节点」起是正常态。
     * 预期：端点 B 保持裸名降级（无描述、无账本），缺失留痕为 DEBUG（MUST NOT 按异常 WARN 上报）。
     */
    @Test
    void should_keepBareEndpointAndDebugTrace_when_recall_given_findEntityAttributesMiss() {
        // given：关系路命中 A-B，端点 B 未被实体路命中；回查返回空（悬空端点正常态）
        stubGraphBase();
        when(retrievalRepository.searchEntities(eq(KB_ID), eq(LL_VECTOR),
                eq(DISTANCE_THRESHOLD), eq(TOP_K))).thenReturn(List.of());
        when(retrievalRepository.searchRelations(eq(KB_ID), eq(HL_VECTOR),
                eq(DISTANCE_THRESHOLD), eq(TOP_K)))
                .thenReturn(List.of(relationHit("A", "B", 0.7D, List.of(CHUNK_GRAPH_2))));
        when(retrievalRepository.findEntityAttributes(eq(KB_ID), anyList())).thenReturn(Map.of());
        when(retrievalRepository.relatedEdges(eq(KB_ID), anyList(), anyInt())).thenReturn(List.of());
        when(retrievalRepository.chunksOf(eq(KB_ID), anyList())).thenReturn(Map.of(
                CHUNK_GRAPH_2, graphChunkText(CHUNK_GRAPH_2, "rel.md")));
        ch.qos.logback.classic.Logger recallLogger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(DefaultRecallService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Level originalLevel = recallLogger.getLevel();
        recallLogger.setLevel(Level.DEBUG);
        recallLogger.addAppender(appender);
        try {
            // when
            RecallService.RecallOutcome outcome = recallService.recall(query(), mixConfig());

            // then：端点 B 保持裸名降级（无描述、无账本）
            EntityHit b = outcome.kgResult().entities().stream()
                    .filter(hit -> "B".equals(hit.name())).findFirst().orElseThrow();
            assertNull(b.description());
            assertTrue(b.chunkIds().isEmpty());
            // then：回查缺失仅记 DEBUG 留痕，不再产生该内容的 WARN（悬空端点不按异常上报）
            assertTrue(appender.list.stream().anyMatch(event -> event.getLevel() == Level.DEBUG
                    && event.getFormattedMessage().contains("回查缺失")));
            assertTrue(appender.list.stream().noneMatch(event -> event.getLevel() == Level.WARN
                    && event.getFormattedMessage().contains("回查缺失")));
        } finally {
            recallLogger.detachAppender(appender);
            recallLogger.setLevel(originalLevel);
        }
    }

    @Test
    void should_notRegressEndorsementAndQuota_when_recall_given_perSourceAndEdgeLimitsCombined() {
        // given：每源上限收紧为 2（实体 A 三块仅取 201/202），入场配额 2；关系 C-D 二次背书 201
        stubGraphBase();
        when(retrievalRepository.searchEntities(eq(KB_ID), eq(LL_VECTOR),
                eq(DISTANCE_THRESHOLD), eq(TOP_K)))
                .thenReturn(List.of(entityHit("A", 0.9D,
                        List.of(CHUNK_GRAPH_1, CHUNK_GRAPH_2, CHUNK_GRAPH_3))));
        when(retrievalRepository.searchRelations(eq(KB_ID), eq(HL_VECTOR),
                eq(DISTANCE_THRESHOLD), eq(TOP_K)))
                .thenReturn(List.of(relationHit("C", "D", 0.7D, List.of(CHUNK_GRAPH_1))));
        when(retrievalRepository.relatedEdges(eq(KB_ID), anyList(), anyInt())).thenReturn(List.of());
        when(retrievalRepository.chunksOf(eq(KB_ID), anyList())).thenReturn(Map.of(
                CHUNK_GRAPH_1, graphChunkText(CHUNK_GRAPH_1, "a.md"),
                CHUNK_GRAPH_2, graphChunkText(CHUNK_GRAPH_2, "b.md")));

        // when：每源上限 2、配额 2、关联边上限走默认
        RecallService.RecallOutcome outcome = recallService.recall(queryWith(2, null, 2), mixConfig());

        // then：每源截断先于背书（201 被 A+关系双背书→排首），配额取前缀 [201,202]；
        //        A 的第 3 块 203 因每源上限被排除
        List<Long> graphChunks = outcome.candidates().stream()
                .map(candidate -> candidate.chunkId()).toList();
        assertEquals(List.of(CHUNK_GRAPH_1, CHUNK_GRAPH_2), graphChunks);
        verify(retrievalRepository).relatedEdges(eq(KB_ID), anyList(), eq(RetrievalConstants.GRAPH_EDGE_TOP));
    }

    /**
     * 桩化 GRAPH 通道基础环境：嵌入配置、批量预计算三向量、VECTOR/BM25 通道空命中。
     * <p>各新行为用例仅需再打桩实体路/关系路/关联边/回查/回正文，聚焦图谱组装语义。</p>
     */
    private void stubGraphBase() {
        stubEmbeddingProfile(KB_ID, EMBED_PROFILE);
        when(embeddingClient.embedBatch(eq(EMBED_PROFILE), anyList()))
                .thenReturn(List.of(QUERY_VECTOR, LL_VECTOR, HL_VECTOR));
        when(knowledgeBaseApi.searchByVector(eq(KB_ID), eq(DISTANCE_THRESHOLD),
                eq(QUERY_VECTOR_LITERAL), anyInt())).thenReturn(List.of());
        when(knowledgeBaseApi.searchByKeywords(eq(KB_ID), eq(QUERY), anyInt())).thenReturn(List.of());
    }

    /**
     * 构造带账本的关联边夹具。
     *
     * @param sourceName 归一端点一
     * @param targetName 归一端点二
     * @param chunkIds   边账本
     * @return 关联边记录
     */
    private static GraphEdgeHit graphEdgeWithChunks(String sourceName, String targetName, List<Long> chunkIds) {
        return new GraphEdgeHit(sourceName, targetName, 3, 1.0D, chunkIds, "边描述",
                List.of("边关键词"), List.of("docs/edge.md"), CREATED_AT);
    }

    /**
     * 构造指定图谱上限与入场配额的检索请求（REST 不承载图谱上限，仅内部契约可设）。
     *
     * @param chunkTopK           chunk 召回数量 / GRAPH 入场配额（可空走默认）
     * @param graphEdgeTop        关联边 Top 上限（可空走默认）
     * @param graphEdgeChunkLimit 每源 chunk 上限（可空走默认）
     * @return 检索请求
     */
    private static RetrievalQuery queryWith(Integer chunkTopK, Integer graphEdgeTop, Integer graphEdgeChunkLimit) {
        return new RetrievalQuery(KB_ID, QUERY, HL_KEYWORDS, LL_KEYWORDS,
                TOP_K, chunkTopK, null, null, null, List.of(), null,
                graphEdgeTop, graphEdgeChunkLimit);
    }

    /**
     * 桩化 MIX 全链路命中（场景①/③共用）：批量预计算 3 向量、VECTOR/BM25 各 1 命中、
     * 实体路命中（chunk 201/202）、关系路命中「用户-订单」（chunk 203）、
     * 关联边与关系路命中同归一对（视图中去重、边账本为空不新增候选）。
     */
    private void stubMixFullRecall() {
        stubEmbeddingProfile(KB_ID, EMBED_PROFILE);
        when(embeddingClient.embedBatch(eq(EMBED_PROFILE), anyList()))
                .thenReturn(List.of(QUERY_VECTOR, LL_VECTOR, HL_VECTOR));
        when(knowledgeBaseApi.searchByVector(eq(KB_ID), eq(DISTANCE_THRESHOLD),
                eq(QUERY_VECTOR_LITERAL), eq(CHUNK_TOP_K)))
                .thenReturn(List.of(new ChunkScoreDTO(CHUNK_VECTOR, 0.92D)));
        when(knowledgeBaseApi.searchByKeywords(eq(KB_ID), eq(QUERY), eq(CHUNK_TOP_K)))
                .thenReturn(List.of(new ChunkScoreDTO(CHUNK_BM25, 3.5D)));
        when(retrievalRepository.searchEntities(eq(KB_ID), eq(LL_VECTOR),
                eq(DISTANCE_THRESHOLD), eq(TOP_K)))
                .thenReturn(List.of(entityHit(ENTITY_USER, 0.88D,
                        List.of(CHUNK_GRAPH_1, CHUNK_GRAPH_2))));
        when(retrievalRepository.searchRelations(eq(KB_ID), eq(HL_VECTOR),
                eq(DISTANCE_THRESHOLD), eq(TOP_K)))
                .thenReturn(List.of(relationHit(ENTITY_USER, ENTITY_ORDER, 0.77D,
                        List.of(CHUNK_GRAPH_3))));
        when(retrievalRepository.relatedEdges(eq(KB_ID), anyList(),
                eq(RetrievalConstants.GRAPH_EDGE_TOP)))
                .thenReturn(List.of(graphEdge(ENTITY_USER, ENTITY_ORDER)));
        when(retrievalRepository.chunksOf(eq(KB_ID), anyList())).thenReturn(Map.of(
                CHUNK_GRAPH_1, graphChunkText(CHUNK_GRAPH_1, "graph-1.md"),
                CHUNK_GRAPH_2, graphChunkText(CHUNK_GRAPH_2, "graph-2.md"),
                CHUNK_GRAPH_3, graphChunkText(CHUNK_GRAPH_3, "graph-3.md")));
    }

    /**
     * 构造实体路命中夹具（图谱属性与账本按被测口径齐备，chunk 账本由入参指定）。
     *
     * @param name     实体名
     * @param score    向量相似度分
     * @param chunkIds chunk 账本
     * @return 实体命中记录
     */
    private static EntityHit entityHit(String name, double score, List<Long> chunkIds) {
        return new EntityHit(name, score, chunkIds, "类型-" + name, "描述-" + name,
                List.of("docs/" + name + ".md"), CREATED_AT);
    }

    /**
     * 构造关系路命中夹具（图行属性齐备，端点为字典序归一对）。
     *
     * @param sourceName 源实体名
     * @param targetName 目标实体名
     * @param score      向量相似度分
     * @param chunkIds   chunk 账本
     * @return 关系命中记录
     */
    private static RelationHit relationHit(String sourceName, String targetName, double score,
                                          List<Long> chunkIds) {
        return new RelationHit(sourceName, targetName, score, chunkIds, "关系描述",
                List.of("关系关键词"), 1.0D, List.of("docs/relation.md"), CREATED_AT);
    }

    /**
     * 构造关联边命中夹具（边账本默认空：边并入关系视图，但不由该夹具贡献 chunk 候选）。
     *
     * @param sourceName 归一端点一
     * @param targetName 归一端点二
     * @return 关联边记录
     */
    private static GraphEdgeHit graphEdge(String sourceName, String targetName) {
        return new GraphEdgeHit(sourceName, targetName, 3, 1.0D, List.of(), "边描述",
                List.of("边关键词"), List.of("docs/edge.md"), CREATED_AT);
    }

    /**
     * 取图谱结果中的实体候选集名称（结构化记录 → 名称，供与适配前等价的可读断言）。
     *
     * @param kgResult 图谱结果
     * @return 实体名称列表（有序）
     */
    private static List<String> entityNames(KgSearchResult kgResult) {
        return kgResult.entities().stream().map(EntityHit::name).toList();
    }

    /**
     * 取图谱结果中关系视图的「源-目标」串列表（结构化记录 → 名称，供与适配前等价的可读断言）。
     *
     * @param kgResult 图谱结果
     * @return 关系「源-目标」串列表（有序）
     */
    private static List<String> relationPairs(KgSearchResult kgResult) {
        return kgResult.relations().stream()
                .map(relation -> relation.sourceName() + "-" + relation.targetName())
                .toList();
    }

    /**
     * 桩化知识库嵌入模型 profileId 只读投影（检索侧模型选型的唯一真相源）。
     *
     * @param kbId      知识库主键
     * @param profileId 投影返回值（{@code null} 表示该库未配置嵌入模型）
     */
    private void stubEmbeddingProfile(Long kbId, String profileId) {
        when(knowledgeBaseApi.findEmbeddingModelProfileIdByKbId(kbId)).thenReturn(profileId);
    }

    /**
     * 以默认 {@link #LL_KEYWORDS} / {@link #HL_KEYWORDS} 构建检索请求。
     *
     * @return 检索请求
     */
    private static RetrievalQuery query() {
        return query(LL_KEYWORDS, HL_KEYWORDS);
    }

    /**
     * 构建指定知识库的检索请求（跨库隔离用例）。
     *
     * @param kbId 知识库主键
     * @return 检索请求
     */
    private static RetrievalQuery query(Long kbId) {
        return new RetrievalQuery(kbId, QUERY, HL_KEYWORDS, LL_KEYWORDS,
                TOP_K, CHUNK_TOP_K, null, null, null);
    }

    /**
     * 构建检索请求（注意 {@link RetrievalQuery} 紧凑构造器参数顺序为 hl 在前、ll 在后）。
     *
     * @param llKeywords 低层关键词（实体路）
     * @param hlKeywords 高层关键词（关系路）
     * @return 检索请求
     */
    private static RetrievalQuery query(List<String> llKeywords, List<String> hlKeywords) {
        return new RetrievalQuery(KB_ID, QUERY, hlKeywords, llKeywords,
                TOP_K, CHUNK_TOP_K, null, null, null);
    }

    /**
     * 构建显式携带图谱召回上限的检索请求（REST 契约不承载该两参数，仅内部契约可设）。
     *
     * @param graphEdgeTop        关联边 Top 上限（可空走默认）
     * @param graphEdgeChunkLimit 每源 chunk 上限（可空走默认）
     * @return 检索请求
     */
    private static RetrievalQuery queryWithGraphLimits(Integer graphEdgeTop, Integer graphEdgeChunkLimit) {
        return new RetrievalQuery(KB_ID, QUERY, HL_KEYWORDS, LL_KEYWORDS,
                TOP_K, CHUNK_TOP_K, null, null, null, List.of(), null,
                graphEdgeTop, graphEdgeChunkLimit);
    }

    /**
     * 统计捕获事件中的图谱单侧退化 WARN 条数（实体路 / 关系路两类合计）。
     *
     * @param appender 已挂载并收集事件的 ListAppender
     * @return WARN 条数
     */
    private static long countGraphPathWarnings(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .filter(event -> event.getFormattedMessage().contains(GRAPH_PATH_DEGRADE_MARKER))
                .count();
    }

    /**
     * MIX 策略配置（相似度阈值 0.5 → 距离阈值 0.5）。
     *
     * @return 检索策略配置
     */
    private static RetrievalStrategyConfig mixConfig() {
        return new RetrievalStrategyConfig(RetrievalStrategyType.MIX,
                null, null, SIMILAR_THRESHOLD, null, null);
    }

    /**
     * NAIVE 策略配置（相似度阈值缺失 → 距离阈值 1.0 等效不过滤）。
     *
     * @return 检索策略配置
     */
    private static RetrievalStrategyConfig naiveConfig() {
        return new RetrievalStrategyConfig(RetrievalStrategyType.NAIVE,
                null, null, null, null, null);
    }

    /**
     * 构建图谱通道 chunk 正文快照。
     *
     * @param chunkId chunk 标识
     * @param file    来源文件名
     * @return chunk 正文值对象
     */
    private static ChunkText graphChunkText(Long chunkId, String file) {
        return new ChunkText(chunkId, "图谱正文-" + chunkId, file);
    }
}
