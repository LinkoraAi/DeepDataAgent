package com.linkroa.deepdataagent.rag.application.service;

import com.linkroa.deepdataagent.knowledgebase.api.KnowledgeBaseApi;
import com.linkroa.deepdataagent.knowledgebase.domain.model.Chunk;
import com.linkroa.deepdataagent.knowledgebase.domain.model.FusionStrategyConfig;
import com.linkroa.deepdataagent.knowledgebase.domain.model.RetrievalStrategyConfig;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.ChunkContentType;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.ChunkSource;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.FusionStrategyType;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.RetrievalChannel;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.RetrievalStrategyType;
import com.linkroa.deepdataagent.rag.application.contract.RetrievalChunkView;
import com.linkroa.deepdataagent.rag.application.contract.RetrievalMultimodalOptions;
import com.linkroa.deepdataagent.rag.application.contract.RetrievalQuery;
import com.linkroa.deepdataagent.rag.application.contract.RetrievalResult;
import com.linkroa.deepdataagent.rag.domain.model.ContextBudget;
import com.linkroa.deepdataagent.rag.domain.model.EntityHit;
import com.linkroa.deepdataagent.rag.domain.model.KgContext;
import com.linkroa.deepdataagent.rag.domain.model.KgSearchResult;
import com.linkroa.deepdataagent.rag.domain.model.KeywordPair;
import com.linkroa.deepdataagent.rag.domain.model.RankedChunk;
import com.linkroa.deepdataagent.rag.domain.model.RelationHit;
import com.linkroa.deepdataagent.rag.domain.model.RetrievalCandidate;
import com.linkroa.deepdataagent.rag.domain.port.LlmImage;
import com.linkroa.deepdataagent.rag.domain.service.AnswerGenerator;
import com.linkroa.deepdataagent.rag.domain.service.ContextBuilder;
import com.linkroa.deepdataagent.rag.domain.service.DefaultContextBuilder;
import com.linkroa.deepdataagent.rag.domain.service.FusionService;
import com.linkroa.deepdataagent.rag.domain.service.JtokkitTokenCounter;
import com.linkroa.deepdataagent.rag.domain.service.QueryUnderstandingService;
import com.linkroa.deepdataagent.rag.domain.service.RecallService;
import com.linkroa.deepdataagent.rag.domain.service.Reranker;
import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link RetrievalApplicationService} 单元测试。
 *
 * <p>以 Mockito 离线驱动六个领域服务与 {@link KnowledgeBaseApi}，不启动 Spring 容器。
 * 覆盖场景：① MIX 全链路 Stage 0~5 调用顺序与 build 以 kbId 首参调用；
 * ② NAIVE 链路完整且空关键词不触发失败终止；③ 关键词失败终止跳过 Stage 1~4；
 * ④ 召回异常降级不阻断（degraded=true）；⑤ 知识库不可用拒绝检索；
 * ⑥ 策略快照 null 按 NAIVE 兜底；⑦ fusionConfig null 兜底默认 RRF；
 * ⑧ 改写后 query 贯穿 effective 请求；⑨ 融合异常按候选原始顺序直排；
 * ⑩ 上下文异常以空上下文承载图谱结果继续；⑪ 答案生成异常主返回不抛；⑫ 空请求非法；
 * ⑬ 三处 LLM 编排调用共享同一个缓存命中计数器实例；
 * ⑭ 无附图请求不触发通路 A 转译（行为与基线逐字段一致）；
 * ⑮ 附图请求增强文本喂 Stage 0、作答仍送原始 query；
 * ⑯ 转译回退原文时检索链路照常完成；
 * ⑰ 回归：Stage 4 降级/关键词终止上下文装入集为空表
 * （通路 B 回落图谱集），四参上下文携带图谱外装入 id 时 raw_data/RetrievalResult
 * 对外字段形态零变化；
 * ⑱ 通路 A 开关矩阵：A=false×带图（零转译、产物同无图基线）、
 * A=false×无图（零差异）、effective 保留多模态开关（Stage 1/Stage 5 断言，附图仍不下传）；
 * ⑲ 答案形态（generateAnswer）：Stage 0~5 全跑但零明细回取、作答异常兜底 null、
 * 附图请求作答仍锚定原始 query、前置校验与空请求口径同完整形态；
 * ⑳ 切片形态（retrieveChunks）：跑至 Stage 3 即止（上下文构建与作答零交互）、
 * 取数为精排全量而非图谱/上下文子集、召回降级与关键词终止均返回空表、
 * 明细回取失败保序不抛、附图转译照常前置生效；
 * ㉑ P3 行为激活：Stage 4 换用真实 DefaultContextBuilder（jtokkit 真实 encode），
 * maxEntityTokens 小值经 effective 贯穿编排层后实体段行数缩减、chunk 装入集零受影响，
 * 且缺省请求下预算捕获断言截断份额随契约默认常量直传。</p>
 *
 * <p>所有对 {@link QueryUnderstandingService} 与 {@link AnswerGenerator} 的打桩与校验
 * 均使用带 {@code AtomicInteger cacheHits} 的新重载，与被测编排实际调用保持一致。</p>
 *
 * @author DeepDataAgent
 */
@ExtendWith(MockitoExtension.class)
class RetrievalApplicationServiceTest {

    /** 测试知识库ID */
    private static final Long KB_ID = 7L;

    /** 用户原始问题（短于关键词失败终止阈值） */
    private static final String QUERY = "订单发货超时的处理规则是什么";

    /** 改写后问题（贯穿 Stage 1~5 的查询文本） */
    private static final String REWRITTEN = "订单发货超时违约处理规则";

    /** 长问题（14 字 × 5 = 70 字，≥ 关键词失败终止阈值 50） */
    private static final String LONG_QUERY = "订单延迟发货违约赔偿标准说明".repeat(5);

    /** 测试答案文本 */
    private static final String ANSWER = "按平台规则第 8 条处理";

    /** fail_response 兜底字面量（关键词失败终止路径由 AnswerGenerator 内部产出，此处仅作 mock 返回值） */
    private static final String FAIL_ANSWER = "fail_response";

    /** VECTOR 通道候选 */
    private static final RetrievalCandidate VECTOR_CANDIDATE =
            new RetrievalCandidate(101L, RetrievalChannel.VECTOR, 0.82D, "manual.pdf");

    /** GRAPH 通道候选 */
    private static final RetrievalCandidate GRAPH_CANDIDATE =
            new RetrievalCandidate(102L, RetrievalChannel.GRAPH, 0.91D, "policy.pdf");

    /** 融合精排后的全局有序结果 */
    private static final List<RankedChunk> RANKED = List.of(
            new RankedChunk(102L, 0.033D, "policy.pdf"),
            new RankedChunk(101L, 0.016D, "manual.pdf"));

    /** 图谱召回结构化结果（实体候选集 / 关系视图均为携带图谱属性的结构化记录） */
    private static final KgSearchResult KG_RESULT = new KgSearchResult(
            List.of(new EntityHit("订单", 0.91D, List.of(102L), "业务概念", "订单实体描述",
                    List.of("docs/order.md"), null)),
            List.of(new RelationHit("订单", "发货超时", 0.88D, List.of(102L), "发货超时关系描述",
                    List.of("超时"), 1.0D, List.of("docs/relation.md"), null)),
            List.of(new RankedChunk(102L, 0.91D, "policy.pdf")));

    /** Stage 4 上下文产物 */
    private static final KgContext KG_CONTEXT = new KgContext(
            "渲染后的上下文文本", List.of("[1] policy.pdf", "[2] manual.pdf"), KG_RESULT);

    /**
     * P3 行为激活夹具：1 条轻量实体（小份额 50 下首条必可装入）+ 4 条富描述实体
     * （单条知识计重约百 token：默认份额 2000 全装入，小份额装入首条后即停）。
     */
    private static final KgSearchResult RICH_ENTITY_KG_RESULT = new KgSearchResult(
            List.of(new EntityHit("轻量实体", 0.95D, List.of(), null, null, null, null),
                    new EntityHit("富实体甲", 0.9D, List.of(), "业务概念", "详细规则说明内容段落。".repeat(12),
                            List.of("docs/a.md"), null),
                    new EntityHit("富实体乙", 0.85D, List.of(), "业务概念", "违约赔付计算口径段落。".repeat(12),
                            List.of("docs/b.md"), null),
                    new EntityHit("富实体丙", 0.8D, List.of(), "业务概念", "发货时限判定标准段落。".repeat(12),
                            List.of("docs/c.md"), null),
                    new EntityHit("富实体丁", 0.75D, List.of(), "业务概念", "履约责任归属条款段落。".repeat(12),
                            List.of("docs/d.md"), null)),
            List.of(), RANKED);

    /** 通路 A 附图（已校验解码的图片载荷） */
    private static final LlmImage ATTACHMENT = new LlmImage("image/png", new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47});

    /** 附图转译产出的增强查询（原问题 + 附图描述 + 尾部指导句） */
    private static final String ENHANCED_QUERY = QUERY + "\n\n附图描述：图中是超时规则表格\n\n请全面回答";

    /** 增强查询经 Stage 0 改写后的文本（贯穿 Stage 1~4） */
    private static final String ENHANCED_REWRITTEN = "订单发货超时规则附表";

    @Mock
    private KnowledgeBaseApi knowledgeBaseApi;

    @Mock
    private QueryUnderstandingService queryUnderstandingService;

    @Mock
    private RecallService recallService;

    @Mock
    private FusionService fusionService;

    @Mock
    private Reranker reranker;

    @Mock
    private ContextBuilder contextBuilder;

    @Mock
    private AnswerGenerator answerGenerator;

    @Mock
    private QueryImageTranscriber queryImageTranscriber;

    @InjectMocks
    private RetrievalApplicationService retrievalApplicationService;

    /**
     * 构造 MIX 策略快照（含默认 RRF 融合配置）。
     *
     * @return MIX 策略配置
     */
    private RetrievalStrategyConfig mixConfig() {
        return new RetrievalStrategyConfig(RetrievalStrategyType.MIX, true, 5, 0.5F, null,
                new FusionStrategyConfig(FusionStrategyType.RRF, FusionStrategyConfig.DEFAULT_RRF_K, null));
    }

    /**
     * 构造基础检索请求（预算字段全部走紧凑构造器默认值）。
     *
     * @return 检索请求
     */
    private RetrievalQuery request() {
        return new RetrievalQuery(KB_ID, QUERY, null, null, null, null, null, null, null);
    }

    /**
     * 构造携带一张附图的检索请求（通路 A 入参形态，多模态开关缺省全开）。
     *
     * @return 含附图的检索请求
     */
    private RetrievalQuery requestWithImage() {
        return new RetrievalQuery(KB_ID, QUERY, null, null, null, null, null, null, null, List.of(ATTACHMENT));
    }

    /**
     * 构造携带一张附图且显式指定多模态开关的检索请求（开关矩阵形态）。
     *
     * @param options 多模态通路开关
     * @return 含附图的检索请求
     */
    private RetrievalQuery requestWithImage(RetrievalMultimodalOptions options) {
        return new RetrievalQuery(KB_ID, QUERY, null, null, null, null, null, null, null,
                List.of(ATTACHMENT), options, null, null);
    }

    /**
     * 打桩 MIX 全链路 Stage 0~5 正常返回（改写产物与关键词均由给定查询文本派生）。
     *
     * @param cfg           策略快照
     * @param understandIn  Stage 0 期望收到的输入文本（原文或增强文本）
     * @param rewrittenOut  Stage 0 改写产物
     */
    private void stubMixChain(RetrievalStrategyConfig cfg, String understandIn, String rewrittenOut) {
        when(knowledgeBaseApi.isActive(KB_ID)).thenReturn(true);
        when(knowledgeBaseApi.findRetrievalStrategyByKbId(KB_ID)).thenReturn(cfg);
        when(queryUnderstandingService.rewrite(eq(understandIn), eq(cfg), eq(KB_ID), any(AtomicInteger.class)))
                .thenReturn(rewrittenOut);
        when(queryUnderstandingService.extract(eq(rewrittenOut), any(), any(), eq(cfg), eq(KB_ID),
                any(AtomicInteger.class))).thenReturn(new KeywordPair(List.of("发货超时"), List.of("订单")));
        when(recallService.recall(any(RetrievalQuery.class), eq(cfg)))
                .thenReturn(new RecallService.RecallOutcome(List.of(VECTOR_CANDIDATE, GRAPH_CANDIDATE), KG_RESULT));
        when(fusionService.fuse(anyMap(), any(FusionStrategyConfig.class))).thenReturn(RANKED);
        when(reranker.rerank(anyString(), anyList(), any(), any())).thenReturn(RANKED);
        when(contextBuilder.build(eq(KB_ID), anyList(), any(), any(ContextBudget.class))).thenReturn(KG_CONTEXT);
        when(answerGenerator.generate(anyString(), any(KgContext.class), eq(cfg), any(RetrievalQuery.class),
                any(AtomicInteger.class))).thenReturn(ANSWER);
    }

    @Test
    void should_notTouchTranscriberAndKeepBaselineAnswerQuery_when_search_given_requestWithoutImages() {
        // given：无附图请求（基线形态）
        RetrievalStrategyConfig cfg = mixConfig();
        stubMixChain(cfg, QUERY, REWRITTEN);

        // when
        RetrievalResult result = retrievalApplicationService.search(request());

        // then：转译器零交互，Stage 0 输入为原文、Stage 5 送入改写后 query（与基线逐字段一致）
        assertEquals(ANSWER, result.answer());
        assertFalse(result.degraded());
        verifyNoInteractions(queryImageTranscriber);
        verify(queryUnderstandingService).rewrite(eq(QUERY), eq(cfg), eq(KB_ID), any(AtomicInteger.class));
        verify(answerGenerator).generate(eq(REWRITTEN), eq(KG_CONTEXT), eq(cfg), any(RetrievalQuery.class),
                any(AtomicInteger.class));
    }

    @Test
    void should_feedEnhancedQueryToUnderstandingAndAnswerWithOriginalQuery_when_search_given_attachedImages() {
        // given：附图转译产出增强查询
        RetrievalStrategyConfig cfg = mixConfig();
        when(queryImageTranscriber.transcribe(KB_ID, QUERY, List.of(ATTACHMENT))).thenReturn(ENHANCED_QUERY);
        stubMixChain(cfg, ENHANCED_QUERY, ENHANCED_REWRITTEN);

        // when
        RetrievalResult result = retrievalApplicationService.search(requestWithImage());

        // then：转译发生在 Stage 0 之前，检索链路消费增强文本的改写产物
        InOrder inOrder = inOrder(queryImageTranscriber, queryUnderstandingService);
        inOrder.verify(queryImageTranscriber).transcribe(KB_ID, QUERY, List.of(ATTACHMENT));
        inOrder.verify(queryUnderstandingService).rewrite(eq(ENHANCED_QUERY), eq(cfg), eq(KB_ID),
                any(AtomicInteger.class));
        ArgumentCaptor<RetrievalQuery> effectiveCaptor = ArgumentCaptor.forClass(RetrievalQuery.class);
        verify(recallService).recall(effectiveCaptor.capture(), eq(cfg));
        assertEquals(ENHANCED_REWRITTEN, effectiveCaptor.getValue().query());
        assertTrue(effectiveCaptor.getValue().images().isEmpty());
        // 作答仍送原始 query（原始问题保留用于引用与语言规则）
        verify(answerGenerator).generate(eq(QUERY), eq(KG_CONTEXT), eq(cfg), any(RetrievalQuery.class),
                any(AtomicInteger.class));
        assertEquals(ANSWER, result.answer());
        assertFalse(result.degraded());
    }

    @Test
    void should_completeBaselineChain_when_search_given_transcriberFallsBackToOriginalQuery() {
        // given：转译回退（VLM 未配置 / 全部失败）返回原始 query
        RetrievalStrategyConfig cfg = mixConfig();
        when(queryImageTranscriber.transcribe(KB_ID, QUERY, List.of(ATTACHMENT))).thenReturn(QUERY);
        stubMixChain(cfg, QUERY, REWRITTEN);

        // when
        RetrievalResult result = retrievalApplicationService.search(requestWithImage());

        // then：流程按原文继续，Stage 5 仍取改写后 query（等同基线口径）
        assertEquals(ANSWER, result.answer());
        assertFalse(result.degraded());
        verify(queryImageTranscriber).transcribe(KB_ID, QUERY, List.of(ATTACHMENT));
        verify(queryUnderstandingService).rewrite(eq(QUERY), eq(cfg), eq(KB_ID), any(AtomicInteger.class));
        verify(answerGenerator).generate(eq(REWRITTEN), eq(KG_CONTEXT), eq(cfg), any(RetrievalQuery.class),
                any(AtomicInteger.class));
    }

    @Test
    void should_treatAsBaseline_when_search_given_transcriberReturnsBlank() {
        // given：转译器异常返回空白（编排层兜底原文，不得让空白进入 Stage 0）
        RetrievalStrategyConfig cfg = mixConfig();
        when(queryImageTranscriber.transcribe(KB_ID, QUERY, List.of(ATTACHMENT))).thenReturn("  ");
        stubMixChain(cfg, QUERY, REWRITTEN);

        // when
        RetrievalResult result = retrievalApplicationService.search(requestWithImage());

        // then
        assertEquals(ANSWER, result.answer());
        verify(queryUnderstandingService).rewrite(eq(QUERY), eq(cfg), eq(KB_ID), any(AtomicInteger.class));
    }

    /**
     * 开关矩阵：A=false × 带附图 → 零转译交互，
     * 产物与无附图基线逐字段一致（Stage 0 输入原文、Stage 5 送改写文，宽容式不拒绝）。
     */
    @Test
    void should_ignoreImagesWithZeroTranscribeAndKeepNoImageBaseline_when_search_given_queryImageTranscribeFalse() {
        // given：带一张附图但显式关闭通路 A（通路 B 保持缺省 true）
        RetrievalStrategyConfig cfg = mixConfig();
        stubMixChain(cfg, QUERY, REWRITTEN);

        // when
        RetrievalResult result = retrievalApplicationService.search(
                requestWithImage(new RetrievalMultimodalOptions(false, true)));

        // then：转译器零交互，链路与「无附图基线」逐字段一致
        assertEquals(ANSWER, result.answer());
        assertFalse(result.degraded());
        verifyNoInteractions(queryImageTranscriber);
        verify(queryUnderstandingService).rewrite(eq(QUERY), eq(cfg), eq(KB_ID), any(AtomicInteger.class));
        verify(answerGenerator).generate(eq(REWRITTEN), eq(KG_CONTEXT), eq(cfg), any(RetrievalQuery.class),
                any(AtomicInteger.class));
    }

    /**
     * 开关矩阵：A=false × 无附图 → 与基线零差异
     * （开关对无附图请求不引入任何新分支行为）。
     */
    @Test
    void should_keepNoImageBaselineExactly_when_search_given_queryImageTranscribeFalseWithoutImages() {
        // given：无附图但显式携带开关（A=false），链路与基线同桩
        RetrievalStrategyConfig cfg = mixConfig();
        stubMixChain(cfg, QUERY, REWRITTEN);
        RetrievalQuery request = new RetrievalQuery(KB_ID, QUERY, null, null, null, null, null, null, null,
                List.of(), new RetrievalMultimodalOptions(false, true), null, null);

        // when
        RetrievalResult result = retrievalApplicationService.search(request);

        // then
        assertEquals(ANSWER, result.answer());
        assertFalse(result.degraded());
        verifyNoInteractions(queryImageTranscriber);
        verify(queryUnderstandingService).rewrite(eq(QUERY), eq(cfg), eq(KB_ID), any(AtomicInteger.class));
    }

    /**
     * effective 保留开关（风险项）：
     * Stage 1 与 Stage 5 收到的 effective 请求必须携带与入参一致的多模态开关分量，
     * 附图仍不下传（零持久化口径不变）。
     */
    @Test
    void should_carrySameMultimodalOptionsToEffective_when_search_given_explicitFlagsWithImages() {
        // given：A=true（正常转译）+ B=false（须穿透至作答层生效点）
        RetrievalMultimodalOptions options = new RetrievalMultimodalOptions(true, false);
        RetrievalStrategyConfig cfg = mixConfig();
        when(queryImageTranscriber.transcribe(KB_ID, QUERY, List.of(ATTACHMENT))).thenReturn(ENHANCED_QUERY);
        stubMixChain(cfg, ENHANCED_QUERY, ENHANCED_REWRITTEN);

        // when
        retrievalApplicationService.search(requestWithImage(options));

        // then：Stage 1 与 Stage 5 的 effective 均保留开关，附图被剥离
        ArgumentCaptor<RetrievalQuery> recallCaptor = ArgumentCaptor.forClass(RetrievalQuery.class);
        verify(recallService).recall(recallCaptor.capture(), eq(cfg));
        assertSame(options, recallCaptor.getValue().multimodal());
        assertTrue(recallCaptor.getValue().images().isEmpty());
        ArgumentCaptor<RetrievalQuery> answerCaptor = ArgumentCaptor.forClass(RetrievalQuery.class);
        verify(answerGenerator).generate(eq(QUERY), eq(KG_CONTEXT), eq(cfg), answerCaptor.capture(),
                any(AtomicInteger.class));
        assertSame(options, answerCaptor.getValue().multimodal());
        assertTrue(answerCaptor.getValue().images().isEmpty());
    }

    /**
     * effective 保留图谱召回上限（批次一参数化）：请求侧显式设置的关联边 Top / 每源 chunk 上限
     * 必须原样穿透到 Stage 1 消费的 effective 请求，不得在重建时回落默认值。
     */
    @Test
    void should_carryGraphLimitsToEffective_when_search_given_explicitGraphLimits() {
        // given
        RetrievalStrategyConfig cfg = mixConfig();
        stubMixChain(cfg, QUERY, REWRITTEN);
        RetrievalQuery request = new RetrievalQuery(KB_ID, QUERY, null, null, null, null, null, null, null,
                List.of(), RetrievalMultimodalOptions.defaults(), 12, 2);

        // when
        retrievalApplicationService.search(request);

        // then：Stage 1 与 Stage 5 的 effective 均携带请求侧上限
        ArgumentCaptor<RetrievalQuery> recallCaptor = ArgumentCaptor.forClass(RetrievalQuery.class);
        verify(recallService).recall(recallCaptor.capture(), eq(cfg));
        assertEquals(12, recallCaptor.getValue().graphEdgeTop());
        assertEquals(2, recallCaptor.getValue().graphEdgeChunkLimit());
        ArgumentCaptor<RetrievalQuery> answerCaptor = ArgumentCaptor.forClass(RetrievalQuery.class);
        verify(answerGenerator).generate(eq(REWRITTEN), eq(KG_CONTEXT), eq(cfg), answerCaptor.capture(),
                any(AtomicInteger.class));
        assertEquals(12, answerCaptor.getValue().graphEdgeTop());
        assertEquals(2, answerCaptor.getValue().graphEdgeChunkLimit());
    }

    @Test
    void should_runStage0To5InOrder_when_search_given_mixStrategy() {
        // given
        RetrievalStrategyConfig cfg = mixConfig();
        KeywordPair pair = new KeywordPair(List.of("发货超时"), List.of("订单"));
        when(knowledgeBaseApi.isActive(KB_ID)).thenReturn(true);
        when(knowledgeBaseApi.findRetrievalStrategyByKbId(KB_ID)).thenReturn(cfg);
        when(queryUnderstandingService.rewrite(eq(QUERY), eq(cfg), eq(KB_ID), any(AtomicInteger.class)))
                .thenReturn(REWRITTEN);
        when(queryUnderstandingService.extract(eq(REWRITTEN), any(), any(), eq(cfg), eq(KB_ID),
                any(AtomicInteger.class))).thenReturn(pair);
        when(recallService.recall(any(RetrievalQuery.class), eq(cfg)))
                .thenReturn(new RecallService.RecallOutcome(List.of(VECTOR_CANDIDATE, GRAPH_CANDIDATE), KG_RESULT));
        when(fusionService.fuse(anyMap(), eq(cfg.fusionConfig()))).thenReturn(RANKED);
        when(reranker.rerank(REWRITTEN, RANKED, cfg, KB_ID)).thenReturn(RANKED);
        when(contextBuilder.build(eq(KB_ID), eq(RANKED), eq(KG_RESULT), any(ContextBudget.class)))
                .thenReturn(KG_CONTEXT);
        when(answerGenerator.generate(eq(REWRITTEN), eq(KG_CONTEXT), eq(cfg), any(RetrievalQuery.class),
                any(AtomicInteger.class))).thenReturn(ANSWER);

        // when
        RetrievalResult result = retrievalApplicationService.search(request());

        // then
        assertEquals(ANSWER, result.answer());
        assertFalse(result.degraded());
        assertEquals(List.of("[1] policy.pdf", "[2] manual.pdf"), result.references());
        assertSame(KG_CONTEXT, result.context());
        assertEquals(KG_RESULT.entities(), result.rawData().get("entities"));
        assertEquals(KG_RESULT.relations(), result.rawData().get("relations"));
        assertEquals(KG_RESULT.chunks(), result.rawData().get("chunks"));
        InOrder inOrder = inOrder(queryUnderstandingService, recallService, fusionService,
                reranker, contextBuilder, answerGenerator);
        inOrder.verify(queryUnderstandingService).rewrite(eq(QUERY), eq(cfg), eq(KB_ID), any(AtomicInteger.class));
        inOrder.verify(queryUnderstandingService).extract(eq(REWRITTEN), any(), any(), eq(cfg), eq(KB_ID),
                any(AtomicInteger.class));
        inOrder.verify(recallService).recall(any(RetrievalQuery.class), eq(cfg));
        inOrder.verify(fusionService).fuse(anyMap(), any(FusionStrategyConfig.class));
        inOrder.verify(reranker).rerank(REWRITTEN, RANKED, cfg, KB_ID);
        inOrder.verify(contextBuilder).build(eq(KB_ID), eq(RANKED), eq(KG_RESULT), any(ContextBudget.class));
        inOrder.verify(answerGenerator).generate(eq(REWRITTEN), eq(KG_CONTEXT), eq(cfg),
                any(RetrievalQuery.class), any(AtomicInteger.class));
    }

    /**
     * 一次检索内三处 LLM 编排调用必须共享同一个缓存命中计数器实例，
     * 否则各阶段命中无法汇总到收尾摘要日志。
     */
    @Test
    void should_passSameCacheHitCounterToAllLlmStages_when_search_given_fullMixChain() {
        // given
        RetrievalStrategyConfig cfg = mixConfig();
        KeywordPair pair = new KeywordPair(List.of("发货超时"), List.of("订单"));
        when(knowledgeBaseApi.isActive(KB_ID)).thenReturn(true);
        when(knowledgeBaseApi.findRetrievalStrategyByKbId(KB_ID)).thenReturn(cfg);
        when(queryUnderstandingService.rewrite(eq(QUERY), eq(cfg), eq(KB_ID), any(AtomicInteger.class)))
                .thenReturn(REWRITTEN);
        when(queryUnderstandingService.extract(eq(REWRITTEN), any(), any(), eq(cfg), eq(KB_ID),
                any(AtomicInteger.class))).thenReturn(pair);
        when(recallService.recall(any(RetrievalQuery.class), eq(cfg)))
                .thenReturn(new RecallService.RecallOutcome(List.of(VECTOR_CANDIDATE, GRAPH_CANDIDATE), KG_RESULT));
        when(fusionService.fuse(anyMap(), any(FusionStrategyConfig.class))).thenReturn(RANKED);
        when(reranker.rerank(anyString(), anyList(), any(), any())).thenReturn(RANKED);
        when(contextBuilder.build(eq(KB_ID), anyList(), any(), any(ContextBudget.class))).thenReturn(KG_CONTEXT);
        when(answerGenerator.generate(anyString(), any(KgContext.class), any(), any(RetrievalQuery.class),
                any(AtomicInteger.class))).thenReturn(ANSWER);

        // when
        retrievalApplicationService.search(request());

        // then
        ArgumentCaptor<AtomicInteger> rewriteCounter = ArgumentCaptor.forClass(AtomicInteger.class);
        ArgumentCaptor<AtomicInteger> extractCounter = ArgumentCaptor.forClass(AtomicInteger.class);
        ArgumentCaptor<AtomicInteger> generateCounter = ArgumentCaptor.forClass(AtomicInteger.class);
        verify(queryUnderstandingService).rewrite(eq(QUERY), eq(cfg), eq(KB_ID), rewriteCounter.capture());
        verify(queryUnderstandingService).extract(eq(REWRITTEN), any(), any(), eq(cfg), eq(KB_ID),
                extractCounter.capture());
        verify(answerGenerator).generate(eq(REWRITTEN), eq(KG_CONTEXT), eq(cfg), any(RetrievalQuery.class),
                generateCounter.capture());
        assertNotNull(rewriteCounter.getValue());
        assertSame(rewriteCounter.getValue(), extractCounter.getValue());
        assertSame(rewriteCounter.getValue(), generateCounter.getValue());
    }

    @Test
    void should_groupCandidatesByChannelAndUseDefaultBudget_when_search_given_stage0Rewritten() {
        // given
        RetrievalStrategyConfig cfg = mixConfig();
        KeywordPair pair = new KeywordPair(List.of("发货超时"), List.of("订单"));
        when(knowledgeBaseApi.isActive(KB_ID)).thenReturn(true);
        when(knowledgeBaseApi.findRetrievalStrategyByKbId(KB_ID)).thenReturn(cfg);
        when(queryUnderstandingService.rewrite(eq(QUERY), eq(cfg), eq(KB_ID), any(AtomicInteger.class)))
                .thenReturn(REWRITTEN);
        when(queryUnderstandingService.extract(eq(REWRITTEN), any(), any(), eq(cfg), eq(KB_ID),
                any(AtomicInteger.class))).thenReturn(pair);
        when(recallService.recall(any(RetrievalQuery.class), eq(cfg)))
                .thenReturn(new RecallService.RecallOutcome(List.of(VECTOR_CANDIDATE, GRAPH_CANDIDATE), KG_RESULT));
        when(fusionService.fuse(anyMap(), any(FusionStrategyConfig.class))).thenReturn(RANKED);
        when(reranker.rerank(anyString(), anyList(), any(), any())).thenReturn(RANKED);
        when(contextBuilder.build(eq(KB_ID), anyList(), any(), any(ContextBudget.class))).thenReturn(KG_CONTEXT);
        when(answerGenerator.generate(anyString(), any(KgContext.class), any(), any(RetrievalQuery.class),
                any(AtomicInteger.class))).thenReturn(ANSWER);

        // when
        retrievalApplicationService.search(request());

        // then
        ArgumentCaptor<Map<RetrievalChannel, List<RetrievalCandidate>>> mapCaptor =
                ArgumentCaptor.forClass(Map.class);
        verify(fusionService).fuse(mapCaptor.capture(), any(FusionStrategyConfig.class));
        Map<RetrievalChannel, List<RetrievalCandidate>> captured = mapCaptor.getValue();
        assertEquals(2, captured.size());
        assertEquals(List.of(VECTOR_CANDIDATE), captured.get(RetrievalChannel.VECTOR));
        assertEquals(List.of(GRAPH_CANDIDATE), captured.get(RetrievalChannel.GRAPH));
        ArgumentCaptor<ContextBudget> budgetCaptor = ArgumentCaptor.forClass(ContextBudget.class);
        verify(contextBuilder).build(eq(KB_ID), anyList(), any(), budgetCaptor.capture());
        assertEquals(REWRITTEN, budgetCaptor.getValue().query());
        assertEquals(RetrievalQuery.DEFAULT_MAX_TOTAL_TOKENS, budgetCaptor.getValue().maxTotalTokens());
        // P3 截断份额随 effective 直传至唯一构建点（缺省即契约默认常量）
        assertEquals(RetrievalQuery.DEFAULT_MAX_ENTITY_TOKENS, budgetCaptor.getValue().maxEntityTokens());
        assertEquals(RetrievalQuery.DEFAULT_MAX_RELATION_TOKENS, budgetCaptor.getValue().maxRelationTokens());
    }

    /**
     * P3 行为激活（契约不变量「截断参数真实生效」）：Stage 4 换用真实
     * {@link DefaultContextBuilder}（jtokkit 真实 encode，非 mock），请求以
     * {@code maxEntityTokens=50} 小值贯穿编排层后，contextData 实体段行数较默认份额
     * 显著缩减；chunk 装入集零受影响（截断只作用于图谱视图，不回缩 chunk 候选）。
     */
    @Test
    void should_shrinkEntitySectionAndKeepChunks_when_search_given_smallMaxEntityTokensThroughOrchestration() {
        // given：仅 Stage 4 为真实构建器，其余阶段维持 Mock
        DefaultContextBuilder realContextBuilder = new DefaultContextBuilder(new JtokkitTokenCounter(),
                knowledgeBaseApi, new ObjectMapper());
        RetrievalApplicationService serviceWithRealBuilder = new RetrievalApplicationService(knowledgeBaseApi,
                queryUnderstandingService, recallService, fusionService, reranker,
                realContextBuilder, answerGenerator, queryImageTranscriber);
        RetrievalStrategyConfig cfg = mixConfig();
        when(knowledgeBaseApi.isActive(KB_ID)).thenReturn(true);
        when(knowledgeBaseApi.findRetrievalStrategyByKbId(KB_ID)).thenReturn(cfg);
        when(queryUnderstandingService.rewrite(eq(QUERY), eq(cfg), eq(KB_ID), any(AtomicInteger.class)))
                .thenReturn(REWRITTEN);
        when(queryUnderstandingService.extract(eq(REWRITTEN), any(), any(), eq(cfg), eq(KB_ID),
                any(AtomicInteger.class))).thenReturn(new KeywordPair(List.of("发货超时"), List.of("订单")));
        when(recallService.recall(any(RetrievalQuery.class), eq(cfg)))
                .thenReturn(new RecallService.RecallOutcome(List.of(VECTOR_CANDIDATE, GRAPH_CANDIDATE),
                        RICH_ENTITY_KG_RESULT));
        when(fusionService.fuse(anyMap(), any(FusionStrategyConfig.class))).thenReturn(RANKED);
        when(reranker.rerank(anyString(), anyList(), any(), any())).thenReturn(RANKED);
        when(knowledgeBaseApi.findChunksByKbIdAndChunkIds(eq(KB_ID), anyList())).thenReturn(List.of(
                contextChunk(102L, "发货超时的处理规则见平台条款第八条。".repeat(4)),
                contextChunk(101L, "订单履约时限以支付成功时刻起算。".repeat(4))));
        when(answerGenerator.generate(anyString(), any(KgContext.class), eq(cfg), any(RetrievalQuery.class),
                any(AtomicInteger.class))).thenReturn(ANSWER);

        // when：总预算同额（12000），唯一差异为实体截断份额——缺省 2000 vs 小值 50
        RetrievalResult baseline = serviceWithRealBuilder.search(new RetrievalQuery(KB_ID, QUERY, null, null,
                null, null, 12000, null, null));
        RetrievalResult smallShare = serviceWithRealBuilder.search(new RetrievalQuery(KB_ID, QUERY, null, null,
                null, null, 12000, 50, null));

        // then：默认份额装入全部 5 条实体记录；小值逐条累加截断后仅剩首条轻量记录（实体段缩短）
        assertEquals(5, countEntityRecordLines(baseline.context().contextData()));
        assertEquals(1, countEntityRecordLines(smallShare.context().contextData()));
        // then：chunk 装入集零受影响且非空（图谱截断不回缩 chunk 候选）
        assertFalse(smallShare.context().retainedChunkIds().isEmpty());
        assertEquals(baseline.context().retainedChunkIds(), smallShare.context().retainedChunkIds());
    }

    /**
     * 统计渲染后上下文中实体记录行数（{@code {"entity":} 行前缀，P3 截断行数断言用）。
     *
     * @param contextData 渲染后的上下文全文
     * @return 实体 JSON 记录行数
     */
    private static int countEntityRecordLines(String contextData) {
        return (int) contextData.lines()
                .filter(line -> line.startsWith("{\"entity\":\""))
                .count();
    }

    /**
     * 构造 Stage 4 正文回取用切片夹具（从库恢复口径）。
     *
     * @param id      切片主键
     * @param content 正文
     * @return 切片领域模型
     */
    private static Chunk contextChunk(Long id, String content) {
        return Chunk.restore(id, KB_ID, 900L, 0, content.length(), content, null,
                ChunkContentType.TEXT, "manual.pdf", null, ChunkSource.PARSED, null, null);
    }

    @Test
    void should_completePipelineWithoutKeywordAbort_when_search_given_naiveStrategyAndEmptyKeywords() {
        // given：NAIVE 模式提取返回空关键词对，不得触发关键词失败终止
        RetrievalStrategyConfig cfg =
                new RetrievalStrategyConfig(RetrievalStrategyType.NAIVE, false, null, null, null, null);
        when(knowledgeBaseApi.isActive(KB_ID)).thenReturn(true);
        when(knowledgeBaseApi.findRetrievalStrategyByKbId(KB_ID)).thenReturn(cfg);
        when(queryUnderstandingService.rewrite(eq(QUERY), eq(cfg), eq(KB_ID), any(AtomicInteger.class)))
                .thenReturn(QUERY);
        when(queryUnderstandingService.extract(eq(QUERY), any(), any(), eq(cfg), eq(KB_ID),
                any(AtomicInteger.class))).thenReturn(new KeywordPair(List.of(), List.of()));
        when(recallService.recall(any(RetrievalQuery.class), eq(cfg)))
                .thenReturn(new RecallService.RecallOutcome(List.of(VECTOR_CANDIDATE),
                        new KgSearchResult(List.of(), List.of(), List.of())));
        when(fusionService.fuse(anyMap(), any(FusionStrategyConfig.class))).thenReturn(RANKED);
        when(reranker.rerank(anyString(), anyList(), any(), any())).thenReturn(RANKED);
        when(contextBuilder.build(eq(KB_ID), anyList(), any(), any(ContextBudget.class))).thenReturn(KG_CONTEXT);
        when(answerGenerator.generate(anyString(), any(KgContext.class), any(), any(RetrievalQuery.class),
                any(AtomicInteger.class))).thenReturn(ANSWER);

        // when
        RetrievalResult result = retrievalApplicationService.search(request());

        // then
        assertFalse(result.degraded());
        assertEquals(ANSWER, result.answer());
        verify(recallService).recall(any(RetrievalQuery.class), eq(cfg));
        verify(fusionService).fuse(anyMap(), any(FusionStrategyConfig.class));
        verify(contextBuilder).build(eq(KB_ID), anyList(), any(), any(ContextBudget.class));
    }

    @Test
    void should_skipStage1To4AndGenerateWithEmptyContext_when_search_given_mixEmptyKeywordsAndLongQuery() {
        // given：MIX + 双层关键词皆空 + 改写后 query ≥ 50 字 → 关键词失败终止
        RetrievalStrategyConfig cfg = mixConfig();
        when(knowledgeBaseApi.isActive(KB_ID)).thenReturn(true);
        when(knowledgeBaseApi.findRetrievalStrategyByKbId(KB_ID)).thenReturn(cfg);
        when(queryUnderstandingService.rewrite(eq(QUERY), eq(cfg), eq(KB_ID), any(AtomicInteger.class)))
                .thenReturn(LONG_QUERY);
        when(queryUnderstandingService.extract(eq(LONG_QUERY), any(), any(), eq(cfg), eq(KB_ID),
                any(AtomicInteger.class))).thenReturn(new KeywordPair(List.of(), List.of()));
        when(answerGenerator.generate(eq(LONG_QUERY), any(KgContext.class), eq(cfg), any(RetrievalQuery.class),
                any(AtomicInteger.class))).thenReturn(FAIL_ANSWER);

        // when
        RetrievalResult result = retrievalApplicationService.search(request());

        // then
        ArgumentCaptor<KgContext> contextCaptor = ArgumentCaptor.forClass(KgContext.class);
        verify(answerGenerator).generate(eq(LONG_QUERY), contextCaptor.capture(), eq(cfg),
                any(RetrievalQuery.class), any(AtomicInteger.class));
        KgContext passedContext = contextCaptor.getValue();
        assertEquals("", passedContext.contextData());
        assertTrue(passedContext.referenceList().isEmpty());
        assertTrue(passedContext.kgResult().entities().isEmpty());
        assertTrue(passedContext.kgResult().relations().isEmpty());
        assertTrue(passedContext.kgResult().chunks().isEmpty());
        // 空上下文走三参构造 → 装入集为空（通路 B 回落图谱集口径）
        assertTrue(passedContext.retainedChunkIds().isEmpty(),
                "关键词失败终止的空上下文 retainedChunkIds 应为空表");
        verifyNoInteractions(recallService, fusionService, reranker, contextBuilder);
        assertEquals(FAIL_ANSWER, result.answer());
        assertFalse(result.degraded());
        assertTrue(result.references().isEmpty());
    }

    @Test
    void should_markDegradedAndKeepMainReturn_when_search_given_recallThrows() {
        // given
        RetrievalStrategyConfig cfg = mixConfig();
        KeywordPair pair = new KeywordPair(List.of("发货超时"), List.of("订单"));
        when(knowledgeBaseApi.isActive(KB_ID)).thenReturn(true);
        when(knowledgeBaseApi.findRetrievalStrategyByKbId(KB_ID)).thenReturn(cfg);
        when(queryUnderstandingService.rewrite(eq(QUERY), eq(cfg), eq(KB_ID), any(AtomicInteger.class)))
                .thenReturn(REWRITTEN);
        when(queryUnderstandingService.extract(eq(REWRITTEN), any(), any(), eq(cfg), eq(KB_ID),
                any(AtomicInteger.class))).thenReturn(pair);
        when(recallService.recall(any(RetrievalQuery.class), eq(cfg)))
                .thenThrow(new RuntimeException("向量库连接失败"));
        when(fusionService.fuse(anyMap(), any(FusionStrategyConfig.class))).thenReturn(List.of());
        when(reranker.rerank(anyString(), anyList(), any(), any())).thenReturn(List.of());
        when(contextBuilder.build(eq(KB_ID), anyList(), any(), any(ContextBudget.class))).thenReturn(KG_CONTEXT);
        when(answerGenerator.generate(anyString(), any(KgContext.class), any(), any(RetrievalQuery.class),
                any(AtomicInteger.class))).thenReturn(ANSWER);

        // when
        RetrievalResult result = retrievalApplicationService.search(request());

        // then
        assertTrue(result.degraded());
        assertEquals(ANSWER, result.answer());
        assertSame(KG_CONTEXT, result.context());
        // 召回降级为图谱空结果：Stage 4 仍以空 KgSearchResult 承载继续
        ArgumentCaptor<KgSearchResult> kgCaptor = ArgumentCaptor.forClass(KgSearchResult.class);
        verify(contextBuilder).build(eq(KB_ID), anyList(), kgCaptor.capture(), any(ContextBudget.class));
        assertTrue(kgCaptor.getValue().entities().isEmpty());
    }

    @Test
    void should_fallbackToOriginalCandidateOrder_when_search_given_fusionThrows() {
        // given
        RetrievalStrategyConfig cfg = mixConfig();
        KeywordPair pair = new KeywordPair(List.of("发货超时"), List.of("订单"));
        RetrievalCandidate bm25Candidate = new RetrievalCandidate(103L, RetrievalChannel.BM25, 5.0D, "faq.pdf");
        when(knowledgeBaseApi.isActive(KB_ID)).thenReturn(true);
        when(knowledgeBaseApi.findRetrievalStrategyByKbId(KB_ID)).thenReturn(cfg);
        when(queryUnderstandingService.rewrite(eq(QUERY), eq(cfg), eq(KB_ID), any(AtomicInteger.class)))
                .thenReturn(REWRITTEN);
        when(queryUnderstandingService.extract(eq(REWRITTEN), any(), any(), eq(cfg), eq(KB_ID),
                any(AtomicInteger.class))).thenReturn(pair);
        when(recallService.recall(any(RetrievalQuery.class), eq(cfg)))
                .thenReturn(new RecallService.RecallOutcome(
                        List.of(VECTOR_CANDIDATE, GRAPH_CANDIDATE, bm25Candidate), KG_RESULT));
        when(fusionService.fuse(anyMap(), any(FusionStrategyConfig.class)))
                .thenThrow(new RuntimeException("融合除零异常"));
        when(reranker.rerank(anyString(), anyList(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        when(contextBuilder.build(eq(KB_ID), anyList(), any(), any(ContextBudget.class))).thenReturn(KG_CONTEXT);
        when(answerGenerator.generate(anyString(), any(KgContext.class), any(), any(RetrievalQuery.class),
                any(AtomicInteger.class))).thenReturn(ANSWER);

        // when
        RetrievalResult result = retrievalApplicationService.search(request());

        // then：融合异常按候选原始顺序直排进入精排（chunkId 101/102/103 保持召回序）
        assertTrue(result.degraded());
        ArgumentCaptor<List<RankedChunk>> rankedCaptor = ArgumentCaptor.forClass(List.class);
        verify(reranker).rerank(eq(REWRITTEN), rankedCaptor.capture(), eq(cfg), eq(KB_ID));
        assertEquals(List.of(101L, 102L, 103L),
                rankedCaptor.getValue().stream().map(RankedChunk::chunkId).toList());
    }

    @Test
    void should_passEmptyContextCarryingKgResult_when_search_given_contextBuilderThrows() {
        // given
        RetrievalStrategyConfig cfg = mixConfig();
        KeywordPair pair = new KeywordPair(List.of("发货超时"), List.of("订单"));
        when(knowledgeBaseApi.isActive(KB_ID)).thenReturn(true);
        when(knowledgeBaseApi.findRetrievalStrategyByKbId(KB_ID)).thenReturn(cfg);
        when(queryUnderstandingService.rewrite(eq(QUERY), eq(cfg), eq(KB_ID), any(AtomicInteger.class)))
                .thenReturn(REWRITTEN);
        when(queryUnderstandingService.extract(eq(REWRITTEN), any(), any(), eq(cfg), eq(KB_ID),
                any(AtomicInteger.class))).thenReturn(pair);
        when(recallService.recall(any(RetrievalQuery.class), eq(cfg)))
                .thenReturn(new RecallService.RecallOutcome(List.of(VECTOR_CANDIDATE, GRAPH_CANDIDATE), KG_RESULT));
        when(fusionService.fuse(anyMap(), any(FusionStrategyConfig.class))).thenReturn(RANKED);
        when(reranker.rerank(anyString(), anyList(), any(), any())).thenReturn(RANKED);
        when(contextBuilder.build(eq(KB_ID), anyList(), any(), any(ContextBudget.class)))
                .thenThrow(new RuntimeException("模板渲染失败"));
        when(answerGenerator.generate(anyString(), any(KgContext.class), any(), any(RetrievalQuery.class),
                any(AtomicInteger.class))).thenReturn(ANSWER);

        // when
        RetrievalResult result = retrievalApplicationService.search(request());

        // then：Stage 4 异常 → 空上下文承载图谱结果继续答案
        assertTrue(result.degraded());
        ArgumentCaptor<KgContext> contextCaptor = ArgumentCaptor.forClass(KgContext.class);
        verify(answerGenerator).generate(eq(REWRITTEN), contextCaptor.capture(), eq(cfg),
                any(RetrievalQuery.class), any(AtomicInteger.class));
        assertEquals("", contextCaptor.getValue().contextData());
        assertSame(KG_RESULT, contextCaptor.getValue().kgResult());
        // 降级上下文走三参兼容构造 → 装入集空表，
        // 通路 B 下游按回落语义取图谱 chunks，行为与基线逐字一致
        assertTrue(contextCaptor.getValue().retainedChunkIds().isEmpty(),
                "Stage 4 降级上下文 retainedChunkIds 应为空表（通路 B 回落图谱集）");
        assertEquals(ANSWER, result.answer());
        assertTrue(result.references().isEmpty());
    }

    /**
     * 场景：MIX 正常链路 Stage 4 出口为四参上下文，
     * 装入集含图谱结果之外的 BM25 通道图片块 chunkId（201，进入通路 B 直读候选）。
     * 预期：对外契约形态零变化——raw_data 仍仅承载 entities/relations/chunks 三键且值与图谱结果
     * 分量逐字一致（图谱外图片块不泄漏进对外数据）；RetrievalResult 各字段与基线一致；
     * 上下文原样透传 Stage 5（装入集不因编排层发生增删改）。
     */
    @Test
    void should_keepRawDataAndResultShapeUnchanged_when_search_given_retainedIdsContainOutOfGraphChunk() {
        // given：Stage 4 返回装入集含图谱外 id（201）的四参上下文（其余链路同基线）
        RetrievalStrategyConfig cfg = mixConfig();
        KgContext fourArgContext = new KgContext(
                "渲染后的上下文文本", List.of("[1] policy.pdf", "[2] manual.pdf"), KG_RESULT,
                List.of(102L, 101L, 201L));
        when(knowledgeBaseApi.isActive(KB_ID)).thenReturn(true);
        when(knowledgeBaseApi.findRetrievalStrategyByKbId(KB_ID)).thenReturn(cfg);
        when(queryUnderstandingService.rewrite(eq(QUERY), eq(cfg), eq(KB_ID), any(AtomicInteger.class)))
                .thenReturn(REWRITTEN);
        when(queryUnderstandingService.extract(eq(REWRITTEN), any(), any(), eq(cfg), eq(KB_ID),
                any(AtomicInteger.class))).thenReturn(new KeywordPair(List.of("发货超时"), List.of("订单")));
        when(recallService.recall(any(RetrievalQuery.class), eq(cfg)))
                .thenReturn(new RecallService.RecallOutcome(List.of(VECTOR_CANDIDATE, GRAPH_CANDIDATE), KG_RESULT));
        when(fusionService.fuse(anyMap(), any(FusionStrategyConfig.class))).thenReturn(RANKED);
        when(reranker.rerank(anyString(), anyList(), any(), any())).thenReturn(RANKED);
        when(contextBuilder.build(eq(KB_ID), anyList(), any(), any(ContextBudget.class)))
                .thenReturn(fourArgContext);
        when(answerGenerator.generate(anyString(), any(KgContext.class), eq(cfg), any(RetrievalQuery.class),
                any(AtomicInteger.class))).thenReturn(ANSWER);

        // when
        RetrievalResult result = retrievalApplicationService.search(request());

        // then：raw_data 三键形态与取值零变化（图谱外装入 id 不进入对外数据）
        assertEquals(List.of("entities", "relations", "chunks"), List.copyOf(result.rawData().keySet()),
                "raw_data 键集合应维持 entities/relations/chunks 三键基线形态");
        assertEquals(KG_RESULT.entities(), result.rawData().get("entities"));
        assertEquals(KG_RESULT.relations(), result.rawData().get("relations"));
        assertEquals(KG_RESULT.chunks(), result.rawData().get("chunks"));
        // RetrievalResult 对外字段逐项与基线一致
        assertEquals(ANSWER, result.answer());
        assertSame(fourArgContext, result.context());
        assertEquals(fourArgContext.referenceList(), result.references());
        assertFalse(result.degraded());
        // 上下文原样透传 Stage 5：装入集不因编排层发生增删改
        ArgumentCaptor<KgContext> contextCaptor = ArgumentCaptor.forClass(KgContext.class);
        verify(answerGenerator).generate(eq(REWRITTEN), contextCaptor.capture(), eq(cfg),
                any(RetrievalQuery.class), any(AtomicInteger.class));
        assertEquals(List.of(102L, 101L, 201L), contextCaptor.getValue().retainedChunkIds());
    }

    @Test
    void should_returnNullAnswerWithoutThrowing_when_search_given_answerGeneratorThrows() {
        // given
        RetrievalStrategyConfig cfg = mixConfig();
        KeywordPair pair = new KeywordPair(List.of("发货超时"), List.of("订单"));
        when(knowledgeBaseApi.isActive(KB_ID)).thenReturn(true);
        when(knowledgeBaseApi.findRetrievalStrategyByKbId(KB_ID)).thenReturn(cfg);
        when(queryUnderstandingService.rewrite(eq(QUERY), eq(cfg), eq(KB_ID), any(AtomicInteger.class)))
                .thenReturn(REWRITTEN);
        when(queryUnderstandingService.extract(eq(REWRITTEN), any(), any(), eq(cfg), eq(KB_ID),
                any(AtomicInteger.class))).thenReturn(pair);
        when(recallService.recall(any(RetrievalQuery.class), eq(cfg)))
                .thenReturn(new RecallService.RecallOutcome(List.of(VECTOR_CANDIDATE, GRAPH_CANDIDATE), KG_RESULT));
        when(fusionService.fuse(anyMap(), any(FusionStrategyConfig.class))).thenReturn(RANKED);
        when(reranker.rerank(anyString(), anyList(), any(), any())).thenReturn(RANKED);
        when(contextBuilder.build(eq(KB_ID), anyList(), any(), any(ContextBudget.class))).thenReturn(KG_CONTEXT);
        when(answerGenerator.generate(anyString(), any(KgContext.class), any(), any(RetrievalQuery.class),
                any(AtomicInteger.class))).thenThrow(new RuntimeException("LLM 超时"));

        // when
        RetrievalResult result = retrievalApplicationService.search(request());

        // then
        assertTrue(result.degraded());
        assertNull(result.answer());
        assertSame(KG_CONTEXT, result.context());
        assertEquals(KG_CONTEXT.referenceList(), result.references());
    }

    @Test
    void should_rejectRetrievalWithoutDownstreamCalls_when_search_given_inactiveKnowledgeBase() {
        // given
        when(knowledgeBaseApi.isActive(KB_ID)).thenReturn(false);

        // when & then
        assertThrows(DeepDataAgentException.class, () -> retrievalApplicationService.search(request()));
        verify(knowledgeBaseApi, never()).findRetrievalStrategyByKbId(any());
        verifyNoInteractions(queryUnderstandingService, recallService, fusionService,
                reranker, contextBuilder, answerGenerator);
    }

    @Test
    void should_fallbackToNaiveStrategy_when_search_given_nullStrategySnapshot() {
        // given：策略快照未配置（null）→ NAIVE 默认兜底
        when(knowledgeBaseApi.isActive(KB_ID)).thenReturn(true);
        when(knowledgeBaseApi.findRetrievalStrategyByKbId(KB_ID)).thenReturn(null);
        when(queryUnderstandingService.rewrite(eq(QUERY), any(RetrievalStrategyConfig.class), eq(KB_ID),
                any(AtomicInteger.class))).thenReturn(QUERY);
        when(queryUnderstandingService.extract(eq(QUERY), any(), any(),
                any(RetrievalStrategyConfig.class), eq(KB_ID), any(AtomicInteger.class)))
                .thenReturn(new KeywordPair(List.of(), List.of()));
        when(recallService.recall(any(RetrievalQuery.class), any(RetrievalStrategyConfig.class)))
                .thenReturn(new RecallService.RecallOutcome(List.of(VECTOR_CANDIDATE),
                        new KgSearchResult(List.of(), List.of(), List.of())));
        when(fusionService.fuse(anyMap(), any(FusionStrategyConfig.class))).thenReturn(RANKED);
        when(reranker.rerank(anyString(), anyList(), any(), any())).thenReturn(RANKED);
        when(contextBuilder.build(eq(KB_ID), anyList(), any(), any(ContextBudget.class))).thenReturn(KG_CONTEXT);
        when(answerGenerator.generate(anyString(), any(KgContext.class),
                any(RetrievalStrategyConfig.class), any(RetrievalQuery.class), any(AtomicInteger.class)))
                .thenReturn(ANSWER);

        // when
        RetrievalResult result = retrievalApplicationService.search(request());

        // then
        ArgumentCaptor<RetrievalStrategyConfig> cfgCaptor = ArgumentCaptor.forClass(RetrievalStrategyConfig.class);
        verify(recallService).recall(any(RetrievalQuery.class), cfgCaptor.capture());
        assertEquals(RetrievalStrategyType.NAIVE, cfgCaptor.getValue().strategyType());
        assertFalse(Boolean.TRUE.equals(cfgCaptor.getValue().rewriteQuestion()));
        assertNull(cfgCaptor.getValue().fusionConfig());
        assertFalse(result.degraded());
        assertEquals(ANSWER, result.answer());
    }

    @Test
    void should_fuseWithDefaultRrf_when_search_given_nullFusionConfig() {
        // given：库级策略未携带 fusionConfig → 编排层兜底默认 RRF（k=60）
        RetrievalStrategyConfig cfg = new RetrievalStrategyConfig(
                RetrievalStrategyType.MIX, false, null, null, null, null);
        KeywordPair pair = new KeywordPair(List.of("发货超时"), List.of("订单"));
        when(knowledgeBaseApi.isActive(KB_ID)).thenReturn(true);
        when(knowledgeBaseApi.findRetrievalStrategyByKbId(KB_ID)).thenReturn(cfg);
        when(queryUnderstandingService.rewrite(eq(QUERY), eq(cfg), eq(KB_ID), any(AtomicInteger.class)))
                .thenReturn(QUERY);
        when(queryUnderstandingService.extract(eq(QUERY), any(), any(), eq(cfg), eq(KB_ID),
                any(AtomicInteger.class))).thenReturn(pair);
        when(recallService.recall(any(RetrievalQuery.class), eq(cfg)))
                .thenReturn(new RecallService.RecallOutcome(List.of(VECTOR_CANDIDATE, GRAPH_CANDIDATE), KG_RESULT));
        when(fusionService.fuse(anyMap(), any(FusionStrategyConfig.class))).thenReturn(RANKED);
        when(reranker.rerank(anyString(), anyList(), any(), any())).thenReturn(RANKED);
        when(contextBuilder.build(eq(KB_ID), anyList(), any(), any(ContextBudget.class))).thenReturn(KG_CONTEXT);
        when(answerGenerator.generate(anyString(), any(KgContext.class), any(), any(RetrievalQuery.class),
                any(AtomicInteger.class))).thenReturn(ANSWER);

        // when
        retrievalApplicationService.search(request());

        // then
        ArgumentCaptor<FusionStrategyConfig> fusionCaptor = ArgumentCaptor.forClass(FusionStrategyConfig.class);
        verify(fusionService).fuse(anyMap(), fusionCaptor.capture());
        assertEquals(FusionStrategyType.RRF, fusionCaptor.getValue().fusionType());
        assertEquals(FusionStrategyConfig.DEFAULT_RRF_K, fusionCaptor.getValue().rrfK());
        assertNull(fusionCaptor.getValue().channelDenseWeight());
    }

    /**
     * 场景（converge-rag-hot-path-object-creation / 5.6）：策略快照恒未配置时连续两次检索，
     * NAIVE 策略兜底与 RRF 融合兜底均经常量返回。
     * 预期：两次下发给召回/融合服务的兜底配置为同一不可变实例（record 可安全共享、零重复分配）。
     */
    @Test
    void should_reuseSameFallbackConfigInstances_when_search_given_repeatedNullStrategySnapshots() {
        // given：策略快照恒为 null（两次检索都走 NAIVE + RRF 兜底常量）
        when(knowledgeBaseApi.isActive(KB_ID)).thenReturn(true);
        when(knowledgeBaseApi.findRetrievalStrategyByKbId(KB_ID)).thenReturn(null);
        when(queryUnderstandingService.rewrite(eq(QUERY), any(RetrievalStrategyConfig.class), eq(KB_ID),
                any(AtomicInteger.class))).thenReturn(QUERY);
        when(queryUnderstandingService.extract(eq(QUERY), any(), any(),
                any(RetrievalStrategyConfig.class), eq(KB_ID), any(AtomicInteger.class)))
                .thenReturn(new KeywordPair(List.of(), List.of()));
        when(recallService.recall(any(RetrievalQuery.class), any(RetrievalStrategyConfig.class)))
                .thenReturn(new RecallService.RecallOutcome(List.of(VECTOR_CANDIDATE),
                        new KgSearchResult(List.of(), List.of(), List.of())));
        when(fusionService.fuse(anyMap(), any(FusionStrategyConfig.class))).thenReturn(RANKED);
        when(reranker.rerank(anyString(), anyList(), any(), any())).thenReturn(RANKED);
        when(contextBuilder.build(eq(KB_ID), anyList(), any(), any(ContextBudget.class))).thenReturn(KG_CONTEXT);
        when(answerGenerator.generate(anyString(), any(KgContext.class),
                any(RetrievalStrategyConfig.class), any(RetrievalQuery.class), any(AtomicInteger.class)))
                .thenReturn(ANSWER);

        // when：连续两次检索
        retrievalApplicationService.search(request());
        retrievalApplicationService.search(request());

        // then：两次下发的 NAIVE 兜底与 RRF 兜底均为同一常量实例
        ArgumentCaptor<RetrievalStrategyConfig> cfgCaptor = ArgumentCaptor.forClass(RetrievalStrategyConfig.class);
        verify(recallService, times(2)).recall(any(RetrievalQuery.class), cfgCaptor.capture());
        assertSame(cfgCaptor.getAllValues().get(0), cfgCaptor.getAllValues().get(1),
                "NAIVE 兜底策略配置应复用同一不可变常量实例");
        ArgumentCaptor<FusionStrategyConfig> fusionCaptor = ArgumentCaptor.forClass(FusionStrategyConfig.class);
        verify(fusionService, times(2)).fuse(anyMap(), fusionCaptor.capture());
        assertSame(fusionCaptor.getAllValues().get(0), fusionCaptor.getAllValues().get(1),
                "RRF 兜底融合配置应复用同一不可变常量实例");
    }

    @Test
    void should_propagateRewrittenQueryAndKeywordsToEffectiveRequest_when_search_given_rewriteEnabled() {
        // given
        RetrievalStrategyConfig cfg = mixConfig();
        KeywordPair pair = new KeywordPair(List.of("发货超时"), List.of("订单"));
        when(knowledgeBaseApi.isActive(KB_ID)).thenReturn(true);
        when(knowledgeBaseApi.findRetrievalStrategyByKbId(KB_ID)).thenReturn(cfg);
        when(queryUnderstandingService.rewrite(eq(QUERY), eq(cfg), eq(KB_ID), any(AtomicInteger.class)))
                .thenReturn(REWRITTEN);
        when(queryUnderstandingService.extract(eq(REWRITTEN), any(), any(), eq(cfg), eq(KB_ID),
                any(AtomicInteger.class))).thenReturn(pair);
        when(recallService.recall(any(RetrievalQuery.class), eq(cfg)))
                .thenReturn(new RecallService.RecallOutcome(List.of(VECTOR_CANDIDATE, GRAPH_CANDIDATE), KG_RESULT));
        when(fusionService.fuse(anyMap(), any(FusionStrategyConfig.class))).thenReturn(RANKED);
        when(reranker.rerank(anyString(), anyList(), any(), any())).thenReturn(RANKED);
        when(contextBuilder.build(eq(KB_ID), anyList(), any(), any(ContextBudget.class))).thenReturn(KG_CONTEXT);
        when(answerGenerator.generate(anyString(), any(KgContext.class), any(), any(RetrievalQuery.class),
                any(AtomicInteger.class))).thenReturn(ANSWER);

        // when
        retrievalApplicationService.search(request());

        // then：Stage 1 收到 effective 请求（改写 query + 提取关键词 + 预算透传）
        ArgumentCaptor<RetrievalQuery> queryCaptor = ArgumentCaptor.forClass(RetrievalQuery.class);
        verify(recallService).recall(queryCaptor.capture(), eq(cfg));
        RetrievalQuery effective = queryCaptor.getValue();
        assertEquals(KB_ID, effective.kbId());
        assertEquals(REWRITTEN, effective.query());
        assertEquals(pair.hl(), effective.hlKeywords());
        assertEquals(pair.ll(), effective.llKeywords());
        assertEquals(RetrievalQuery.DEFAULT_TOP_K, effective.topK());
        assertEquals(RetrievalQuery.DEFAULT_TOP_K, effective.chunkTopK());
        assertEquals(RetrievalQuery.DEFAULT_MAX_TOTAL_TOKENS, effective.maxTotalTokens());
        // Stage 5 同样消费改写后 query
        verify(answerGenerator).generate(eq(REWRITTEN), any(KgContext.class), eq(cfg),
                any(RetrievalQuery.class), any(AtomicInteger.class));
    }

    @Test
    void should_throwIllegalArgumentException_when_search_given_nullRequest() {
        // given
        RetrievalQuery nullRequest = null;

        // when & then
        assertThrows(IllegalArgumentException.class, () -> retrievalApplicationService.search(nullRequest));
        verifyNoInteractions(knowledgeBaseApi, queryUnderstandingService, recallService,
                fusionService, reranker, contextBuilder, answerGenerator);
    }

    // ==================== 切片明细（chunkViews）组装 ====================

    @Test
    void should_assembleViewsWithContentAndS3MediaRef_when_search_given_chunksFoundWithS3File() {
        // given：一等列 s3_file 携带图片引用（仅对象键，桶概念已退役）
        stubMixChain();
        when(knowledgeBaseApi.findChunksByKbIdAndChunkIds(eq(KB_ID), eq(List.of(102L))))
                .thenReturn(List.of(chunk(102L, "超时按第 8 条赔付", null,
                        "{\"objectKey\":\"rag/7/42/images/a.png\"}")));

        // when
        RetrievalResult result = retrievalApplicationService.search(request());

        // then：正文全量、引用仅对象键、身份与分值取自图谱有序切片
        assertFalse(result.degraded());
        assertEquals(1, result.chunkViews().size());
        RetrievalChunkView view = result.chunkViews().get(0);
        assertEquals(102L, view.chunkId());
        assertEquals("超时按第 8 条赔付", view.chunkContent());
        assertEquals("policy.pdf", view.sourceFile());
        assertEquals("rag/7/42/images/a.png", view.mediaObjectKey());
        assertEquals(0.91D, view.score());
    }

    @Test
    void should_fallbackToOriginalItemMediaKeys_when_search_given_chunkWithoutValidS3File() {
        // given：s3_file 为非法 JSON（历史切片缺列/脏数据），回退 original_item 媒体注入键
        stubMixChain();
        when(knowledgeBaseApi.findChunksByKbIdAndChunkIds(eq(KB_ID), eq(List.of(102L))))
                .thenReturn(List.of(chunk(102L, "历史图片切片",
                        "{\"mediaObjectKey\":\"rag/7/43/images/b.png\"}",
                        "NOT_A_JSON")));

        // when
        RetrievalChunkView view = retrievalApplicationService.search(request()).chunkViews().get(0);

        // then：兜底源生效，正文不受影响
        assertEquals("rag/7/43/images/b.png", view.mediaObjectKey());
        assertEquals("历史图片切片", view.chunkContent());
    }

    @Test
    void should_keepBlankMediaAndContent_when_search_given_textChunkWithoutAnyMediaRef() {
        // given：纯文本切片（两列均无媒体键）
        stubMixChain();
        when(knowledgeBaseApi.findChunksByKbIdAndChunkIds(eq(KB_ID), eq(List.of(102L))))
                .thenReturn(List.of(chunk(102L, "纯文本正文", "{\"img_path\":\"images/x.png\"}", null)));

        // when
        RetrievalChunkView view = retrievalApplicationService.search(request()).chunkViews().get(0);

        // then
        assertEquals("纯文本正文", view.chunkContent());
        assertNull(view.mediaObjectKey());
    }

    @Test
    void should_returnViewsWithoutContentAndNotMarkDegraded_when_search_given_chunkFetchFailed() {
        // given：明细回取抛异常（旁路降级，不污染检索链路 degraded 口径）
        stubMixChain();
        when(knowledgeBaseApi.findChunksByKbIdAndChunkIds(eq(KB_ID), anyList()))
                .thenThrow(new RuntimeException("切片表不可用"));

        // when
        RetrievalResult result = retrievalApplicationService.search(request());

        // then：明细仍按上下文序产出（正文与引用为空），答案与降级标记零变化
        assertFalse(result.degraded());
        assertEquals(ANSWER, result.answer());
        assertEquals(1, result.chunkViews().size());
        assertEquals(102L, result.chunkViews().get(0).chunkId());
        assertNull(result.chunkViews().get(0).chunkContent());
        assertNull(result.chunkViews().get(0).mediaObjectKey());
    }

    @Test
    void should_returnEmptyChunkViews_when_search_given_keywordFailedTermination() {
        // given：MIX 双层关键词皆空且长 query → 跳过 Stage 1~4，无图谱切片可回取
        RetrievalStrategyConfig cfg = mixConfig();
        when(knowledgeBaseApi.isActive(KB_ID)).thenReturn(true);
        when(knowledgeBaseApi.findRetrievalStrategyByKbId(KB_ID)).thenReturn(cfg);
        when(queryUnderstandingService.rewrite(eq(LONG_QUERY), eq(cfg), eq(KB_ID), any(AtomicInteger.class)))
                .thenReturn(LONG_QUERY);
        when(queryUnderstandingService.extract(eq(LONG_QUERY), any(), any(), eq(cfg), eq(KB_ID),
                any(AtomicInteger.class))).thenReturn(new KeywordPair(List.of(), List.of()));
        when(answerGenerator.generate(eq(LONG_QUERY), any(KgContext.class), eq(cfg),
                any(RetrievalQuery.class), any(AtomicInteger.class))).thenReturn(FAIL_ANSWER);

        // when
        RetrievalResult result = retrievalApplicationService.search(
                new RetrievalQuery(KB_ID, LONG_QUERY, null, null, null, null, null, null, null));

        // then：明细恒为空表且零切片回取
        assertNotNull(result.chunkViews());
        assertTrue(result.chunkViews().isEmpty());
        verify(knowledgeBaseApi, never()).findChunksByKbIdAndChunkIds(any(), anyList());
    }

    // ==================== 答案形态（generateAnswer） ====================

    /**
     * 答案形态：Stage 0~5 全跑并返回答案，切片明细回取整段跳过（响应不含明细，省一次批量查询）。
     */
    @Test
    void should_runStage0To5AndSkipChunkFetch_when_generateAnswer_given_mixChain() {
        // given
        RetrievalStrategyConfig cfg = mixConfig();
        stubMixChain(cfg, QUERY, REWRITTEN);

        // when
        String answer = retrievalApplicationService.generateAnswer(request());

        // then：答案与完整形态同口径，上下文构建与作答均发生，明细零回取
        assertEquals(ANSWER, answer);
        verify(contextBuilder).build(eq(KB_ID), eq(RANKED), eq(KG_RESULT), any(ContextBudget.class));
        verify(answerGenerator).generate(eq(REWRITTEN), eq(KG_CONTEXT), eq(cfg), any(RetrievalQuery.class),
                any(AtomicInteger.class));
        verify(knowledgeBaseApi, never()).findChunksByKbIdAndChunkIds(any(), anyList());
    }

    /**
     * 答案形态：Stage 5 异常按降级矩阵兜底，返回 null 而非抛出。
     */
    @Test
    void should_returnNullAnswerWithoutThrowing_when_generateAnswer_given_answerGeneratorThrows() {
        // given：骨干与 Stage 4 正常，仅作答抛异常
        stubSpineToContext();
        when(answerGenerator.generate(anyString(), any(KgContext.class), any(RetrievalStrategyConfig.class),
                any(RetrievalQuery.class), any(AtomicInteger.class))).thenThrow(new RuntimeException("LLM 超时"));

        // when
        String answer = retrievalApplicationService.generateAnswer(request());

        // then
        assertNull(answer);
    }

    /**
     * 答案形态：附图请求仍走通路 A 转译且作答锚定原始 query（与完整形态同口径）。
     */
    @Test
    void should_answerWithOriginalQuery_when_generateAnswer_given_attachedImages() {
        // given
        RetrievalStrategyConfig cfg = mixConfig();
        when(queryImageTranscriber.transcribe(KB_ID, QUERY, List.of(ATTACHMENT))).thenReturn(ENHANCED_QUERY);
        stubMixChain(cfg, ENHANCED_QUERY, ENHANCED_REWRITTEN);

        // when
        String answer = retrievalApplicationService.generateAnswer(requestWithImage());

        // then
        assertEquals(ANSWER, answer);
        verify(answerGenerator).generate(eq(QUERY), eq(KG_CONTEXT), eq(cfg), any(RetrievalQuery.class),
                any(AtomicInteger.class));
    }

    /**
     * 答案形态：知识库不可用直接拒绝，下游零交互。
     */
    @Test
    void should_rejectWithoutDownstreamCalls_when_generateAnswer_given_inactiveKnowledgeBase() {
        // given
        when(knowledgeBaseApi.isActive(KB_ID)).thenReturn(false);

        // when & then
        assertThrows(DeepDataAgentException.class,
                () -> retrievalApplicationService.generateAnswer(request()));
        verifyNoInteractions(queryUnderstandingService, recallService, fusionService,
                reranker, contextBuilder, answerGenerator);
    }

    /**
     * 答案形态：空请求非法，任何依赖零交互。
     */
    @Test
    void should_throwIllegalArgumentException_when_generateAnswer_given_nullRequest() {
        // given // when & then
        assertThrows(IllegalArgumentException.class,
                () -> retrievalApplicationService.generateAnswer(null));
        verifyNoInteractions(knowledgeBaseApi, queryUnderstandingService, recallService,
                fusionService, reranker, contextBuilder, answerGenerator);
    }

    // ==================== 切片形态（retrieveChunks） ====================

    /**
     * 切片形态：跑至 Stage 3 即止，上下文构建与答案生成零交互（零作答 LLM 调用）。
     */
    @Test
    void should_skipContextBuildAndAnswerGeneration_when_retrieveChunks_given_mixChain() {
        // given
        stubSpineToRanking();
        when(knowledgeBaseApi.findChunksByKbIdAndChunkIds(eq(KB_ID), eq(List.of(102L, 101L))))
                .thenReturn(List.of(chunk(102L, "超时按第 8 条赔付", null, null),
                        chunk(101L, "发货流程说明", null, null)));

        // when
        List<RetrievalChunkView> views = retrievalApplicationService.retrieveChunks(request());

        // then：按精排序返回，正文回取生效；上下文与作答两阶段完全未触碰
        assertEquals(List.of(102L, 101L), views.stream().map(RetrievalChunkView::chunkId).toList());
        assertEquals("超时按第 8 条赔付", views.get(0).chunkContent());
        assertEquals("发货流程说明", views.get(1).chunkContent());
        verifyNoInteractions(contextBuilder, answerGenerator);
    }

    /**
     * 切片形态取数口径：返回精排<b>全量</b>（102、101），而非上下文/图谱集（仅 102）——
     * 切片条数不受作答 token 预算与图谱裁剪影响。
     */
    @Test
    void should_returnFullRankedSetNotGraphSubset_when_retrieveChunks_given_rankedExceedsGraphChunks() {
        // given：RANKED 含 102/101，KG_RESULT.chunks 仅含 102（两者集合不同，据此判别取数口径）
        stubSpineToRanking();
        when(knowledgeBaseApi.findChunksByKbIdAndChunkIds(eq(KB_ID), eq(List.of(102L, 101L))))
                .thenReturn(List.of(chunk(102L, "图谱内切片", null, null),
                        chunk(101L, "图谱外切片", null, null)));

        // when
        List<RetrievalChunkView> views = retrievalApplicationService.retrieveChunks(request());

        // then：两条齐备且分值取自精排结果（0.033 / 0.016，非图谱集的 0.91）
        assertEquals(List.of(102L, 101L), views.stream().map(RetrievalChunkView::chunkId).toList());
        assertEquals(0.033D, views.get(0).score());
        assertEquals(0.016D, views.get(1).score());
    }

    /**
     * 切片形态：召回降级（Stage 1 抛异常）按空候选继续，返回空表且不改写主返回。
     */
    @Test
    void should_returnEmptyChunks_when_retrieveChunks_given_recallThrows() {
        // given
        RetrievalStrategyConfig cfg = mixConfig();
        when(knowledgeBaseApi.isActive(KB_ID)).thenReturn(true);
        when(knowledgeBaseApi.findRetrievalStrategyByKbId(KB_ID)).thenReturn(cfg);
        when(queryUnderstandingService.rewrite(eq(QUERY), eq(cfg), eq(KB_ID), any(AtomicInteger.class)))
                .thenReturn(REWRITTEN);
        when(queryUnderstandingService.extract(eq(REWRITTEN), any(), any(), eq(cfg), eq(KB_ID),
                any(AtomicInteger.class))).thenReturn(new KeywordPair(List.of("发货超时"), List.of("订单")));
        when(recallService.recall(any(RetrievalQuery.class), eq(cfg)))
                .thenThrow(new RuntimeException("向量库连接失败"));
        when(fusionService.fuse(anyMap(), any(FusionStrategyConfig.class))).thenReturn(List.of());
        when(reranker.rerank(anyString(), anyList(), any(), any())).thenReturn(List.of());

        // when
        List<RetrievalChunkView> views = retrievalApplicationService.retrieveChunks(request());

        // then：空表非 null，零明细回取
        assertNotNull(views);
        assertTrue(views.isEmpty());
        verify(knowledgeBaseApi, never()).findChunksByKbIdAndChunkIds(any(), anyList());
        verifyNoInteractions(contextBuilder, answerGenerator);
    }

    /**
     * 切片形态：关键词失败终止跳过 Stage 1~3，返回空表且零明细回取。
     */
    @Test
    void should_returnEmptyChunksWithoutRecall_when_retrieveChunks_given_keywordFailedTermination() {
        // given：MIX 双层关键词皆空 + 改写后 query ≥ 50 字
        RetrievalStrategyConfig cfg = mixConfig();
        when(knowledgeBaseApi.isActive(KB_ID)).thenReturn(true);
        when(knowledgeBaseApi.findRetrievalStrategyByKbId(KB_ID)).thenReturn(cfg);
        when(queryUnderstandingService.rewrite(eq(QUERY), eq(cfg), eq(KB_ID), any(AtomicInteger.class)))
                .thenReturn(LONG_QUERY);
        when(queryUnderstandingService.extract(eq(LONG_QUERY), any(), any(), eq(cfg), eq(KB_ID),
                any(AtomicInteger.class))).thenReturn(new KeywordPair(List.of(), List.of()));

        // when
        List<RetrievalChunkView> views = retrievalApplicationService.retrieveChunks(request());

        // then
        assertTrue(views.isEmpty());
        verifyNoInteractions(recallService, fusionService, reranker, contextBuilder, answerGenerator);
        verify(knowledgeBaseApi, never()).findChunksByKbIdAndChunkIds(any(), anyList());
    }

    /**
     * 切片形态：明细回取失败按空正文继续（旁路降级，条数与顺序不受影响）。
     */
    @Test
    void should_keepRankedOrderWithoutContent_when_retrieveChunks_given_chunkFetchFailed() {
        // given
        stubSpineToRanking();
        when(knowledgeBaseApi.findChunksByKbIdAndChunkIds(eq(KB_ID), anyList()))
                .thenThrow(new RuntimeException("切片表不可用"));

        // when
        List<RetrievalChunkView> views = retrievalApplicationService.retrieveChunks(request());

        // then
        assertEquals(List.of(102L, 101L), views.stream().map(RetrievalChunkView::chunkId).toList());
        assertNull(views.get(0).chunkContent());
    }

    /**
     * 切片形态：通路 A 附图照常转译后喂检索链路，但通路 B 与作答无关分量不参与（无 Stage 5）。
     */
    @Test
    void should_transcribeImagesAndSkipAnswer_when_retrieveChunks_given_attachedImages() {
        // given
        when(queryImageTranscriber.transcribe(KB_ID, QUERY, List.of(ATTACHMENT))).thenReturn(ENHANCED_QUERY);
        stubSpineToRanking(ENHANCED_QUERY, REWRITTEN);
        when(knowledgeBaseApi.findChunksByKbIdAndChunkIds(eq(KB_ID), eq(List.of(102L, 101L))))
                .thenReturn(List.of(chunk(102L, "超时按第 8 条赔付", null, null)));

        // when
        List<RetrievalChunkView> views = retrievalApplicationService.retrieveChunks(requestWithImage());

        // then：转译发生、检索消费增强文本的改写产物，作答零交互
        assertEquals(2, views.size());
        verify(queryImageTranscriber).transcribe(KB_ID, QUERY, List.of(ATTACHMENT));
        ArgumentCaptor<RetrievalQuery> recallCaptor = ArgumentCaptor.forClass(RetrievalQuery.class);
        verify(recallService).recall(recallCaptor.capture(), any(RetrievalStrategyConfig.class));
        assertEquals(REWRITTEN, recallCaptor.getValue().query());
        verifyNoInteractions(contextBuilder, answerGenerator);
    }

    /**
     * 切片形态：知识库不可用直接拒绝，下游零交互。
     */
    @Test
    void should_rejectWithoutDownstreamCalls_when_retrieveChunks_given_inactiveKnowledgeBase() {
        // given
        when(knowledgeBaseApi.isActive(KB_ID)).thenReturn(false);

        // when & then
        assertThrows(DeepDataAgentException.class,
                () -> retrievalApplicationService.retrieveChunks(request()));
        verifyNoInteractions(queryUnderstandingService, recallService, fusionService,
                reranker, contextBuilder, answerGenerator);
    }

    /**
     * 切片形态：空请求非法，任何依赖零交互。
     */
    @Test
    void should_throwIllegalArgumentException_when_retrieveChunks_given_nullRequest() {
        // given // when & then
        assertThrows(IllegalArgumentException.class,
                () -> retrievalApplicationService.retrieveChunks(null));
        verifyNoInteractions(knowledgeBaseApi, queryUnderstandingService, recallService,
                fusionService, reranker, contextBuilder, answerGenerator);
    }

    /**
     * 打点至 Stage 3 精排（切片形态不需要 Stage 4~5，故不打上下文与作答桩）。
     */
    private void stubSpineToRanking() {
        stubSpineToRanking(QUERY, REWRITTEN);
    }

    /**
     * 打点至 Stage 3 精排（指定 Stage 0 输入与改写产物）。
     *
     * @param understandIn Stage 0 期望收到的输入文本（原文或增强文本）
     * @param rewrittenOut Stage 0 改写产物
     */
    private void stubSpineToRanking(String understandIn, String rewrittenOut) {
        RetrievalStrategyConfig cfg = mixConfig();
        KeywordPair pair = new KeywordPair(List.of("发货超时"), List.of("订单"));
        when(knowledgeBaseApi.isActive(KB_ID)).thenReturn(true);
        when(knowledgeBaseApi.findRetrievalStrategyByKbId(KB_ID)).thenReturn(cfg);
        when(queryUnderstandingService.rewrite(eq(understandIn), eq(cfg), eq(KB_ID), any(AtomicInteger.class)))
                .thenReturn(rewrittenOut);
        when(queryUnderstandingService.extract(eq(rewrittenOut), any(), any(), eq(cfg), eq(KB_ID),
                any(AtomicInteger.class))).thenReturn(pair);
        when(recallService.recall(any(RetrievalQuery.class), eq(cfg)))
                .thenReturn(new RecallService.RecallOutcome(List.of(VECTOR_CANDIDATE, GRAPH_CANDIDATE), KG_RESULT));
        when(fusionService.fuse(anyMap(), eq(cfg.fusionConfig()))).thenReturn(RANKED);
        when(reranker.rerank(rewrittenOut, RANKED, cfg, KB_ID)).thenReturn(RANKED);
    }

    /**
     * 打点至 Stage 4 上下文构建（答案形态的异常分支需自行打 Stage 5 桩）。
     */
    private void stubSpineToContext() {
        stubSpineToRanking();
        when(contextBuilder.build(eq(KB_ID), eq(RANKED), eq(KG_RESULT), any(ContextBudget.class)))
                .thenReturn(KG_CONTEXT);
    }

    /**
     * MIX 全链路共用打桩（跑通至 Stage 5，图谱结果为单条切片 102）。
     */
    private void stubMixChain() {
        RetrievalStrategyConfig cfg = mixConfig();
        KeywordPair pair = new KeywordPair(List.of("发货超时"), List.of("订单"));
        when(knowledgeBaseApi.isActive(KB_ID)).thenReturn(true);
        when(knowledgeBaseApi.findRetrievalStrategyByKbId(KB_ID)).thenReturn(cfg);
        when(queryUnderstandingService.rewrite(eq(QUERY), eq(cfg), eq(KB_ID), any(AtomicInteger.class)))
                .thenReturn(REWRITTEN);
        when(queryUnderstandingService.extract(eq(REWRITTEN), any(), any(), eq(cfg), eq(KB_ID),
                any(AtomicInteger.class))).thenReturn(pair);
        when(recallService.recall(any(RetrievalQuery.class), eq(cfg)))
                .thenReturn(new RecallService.RecallOutcome(List.of(VECTOR_CANDIDATE, GRAPH_CANDIDATE), KG_RESULT));
        when(fusionService.fuse(anyMap(), eq(cfg.fusionConfig()))).thenReturn(RANKED);
        when(reranker.rerank(REWRITTEN, RANKED, cfg, KB_ID)).thenReturn(RANKED);
        when(contextBuilder.build(eq(KB_ID), eq(RANKED), eq(KG_RESULT), any(ContextBudget.class)))
                .thenReturn(KG_CONTEXT);
        when(answerGenerator.generate(eq(REWRITTEN), eq(KG_CONTEXT), eq(cfg), any(RetrievalQuery.class),
                any(AtomicInteger.class))).thenReturn(ANSWER);
    }

    /**
     * 构造明细回取用的切片行（仅正文与两列媒体引用参与断言）。
     *
     * @param id           切片主键
     * @param content      切片正文
     * @param originalItem 多模态原始信息 JSON，可为空
     * @param s3File       多模态图片对象引用 JSON，可为空
     * @return 切片领域模型
     */
    private Chunk chunk(Long id, String content, String originalItem, String s3File) {
        return Chunk.restore(id, KB_ID, 42L, 1, content.length(), content, originalItem,
                ChunkContentType.TEXT, "doc-" + id + ".pdf", s3File, ChunkSource.PARSED, null, null);
    }
}
