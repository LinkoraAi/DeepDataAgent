package com.linkroa.deepdataagent.rag.domain.service;

import com.linkroa.deepdataagent.knowledgebase.api.KnowledgeBaseApi;
import com.linkroa.deepdataagent.knowledgebase.domain.model.Chunk;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.ChunkContentType;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.ChunkSource;
import com.linkroa.deepdataagent.rag.application.contract.RetrievalQuery;
import com.linkroa.deepdataagent.rag.domain.model.ContextBudget;
import com.linkroa.deepdataagent.rag.domain.model.EntityHit;
import com.linkroa.deepdataagent.rag.domain.model.KgContext;
import com.linkroa.deepdataagent.rag.domain.model.KgSearchResult;
import com.linkroa.deepdataagent.rag.domain.model.RankedChunk;
import com.linkroa.deepdataagent.rag.domain.model.RelationHit;
import com.linkroa.deepdataagent.rag.infrastructure.prompts.catalog.PromptCatalog;
import com.linkroa.deepdataagent.rag.infrastructure.prompts.catalog.PromptTemplates;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link DefaultContextBuilder} 单元测试。
 *
 * <p>渲染已切换为静态 {@link PromptCatalog}，本测试不再
 * mock 渲染端口：TokenCounter 打桩为按字符长度计数的确定性口径、知识库只读契约打桩批量回取；
 * 「模板路由与变量注入」断言改为与被测类同口径渲染的期望全文全等（测试内直接调用
 * {@link PromptCatalog#render} 复现框架空算与最终渲染，含真实模板正文），
 * 预算常量按模板空算真实字符长度动态计算，保证截断语义确定。ObjectMapper 用真实实例（@Spy），
 * 保证逐行 JSON 转义口径与线上一致。全程离线，不启动 Spring 容器；
 * 仅 P1+P3 组合回归用例改用真实 {@link JtokkitTokenCounter}（与被测线上一致的 encode 口径）。</p>
 *
 * <p>覆盖场景：① token 累加超预算即停（保留此前已装入 chunk）；② 引用列表 ≤5 条、
 * 格式 {@code [n] 文件名} 且同文件去重共享编号；③ MIX 模板选择与实体/关系
 * <b>逐行精简字段集 JSON</b>（{@code entity/type/description/created_at/file_path} 与
 * {@code entity1/entity2/description/created_at/file_path}，时间以 ISO-8601 文本呈现）；
 * ④ 空输入返回仅图谱/空上下文产物且不抛异常（NAIVE 模板）；⑤ 接口方法（无 kbId）退化路径
 * 跳过正文回取；⑥ 预算入参为空快速失败；⑦ 单条实体序列化失败跳行不中断；
 * ⑧ 预算公式逐常量可验（NAIVE / MIX 各一条：四项扣减后恰可用 55 → 只装 5 条；MIX 侧另以
 * 「修正前旧口径的同额预算在新公式下份额归零」反证回答系统模板空算项被真实扣减）；
 * ⑨ 上下文骨架模板名取自 {@link AnswerProfile} 单点
 * （与 Stage 5 回答模板同源）；</p>
 *
 * <p>P3 截断（批次三）覆盖：⑩ 实体列表恰好等预算全保留、超出 1 token 丢尾部、
 * 首条自身超份额整段舍弃不留半条；⑪ 实体/关系两侧份额完全独立互不侵占；
 * ⑫ 计重剔除验证——{@code created_at}/{@code file_path} 超长不占截断份额、展示仍保留全字段；
 * ⑬ P1+P3 组合回归（真实 jtokkit encode）：富实体 20 条 + 关系 50 条 + 大量 chunk 下，
 * 实体/关系知识字段计重各 ≤ 份额上限、contextData 与「回答模板渲染 contextData + query」
 * 总量 ≤ maxTotalTokens（端到端公式成立）、GRAPH 富记录不挤爆 chunk 份额（装入量 &gt; 0）、
 * 图谱结果原样透传不被截断回缩。</p>
 *
 * @author DeepDataAgent
 */
@ExtendWith(MockitoExtension.class)
class DefaultContextBuilderTest {

    /** 测试知识库主键 */
    private static final Long KB_ID = 1L;

    /** 测试文档主键 */
    private static final Long DOCUMENT_ID = 100L;

    /** 测试 chunkId：11 */
    private static final Long CHUNK_11 = 11L;

    /** 测试 chunkId：12 */
    private static final Long CHUNK_12 = 12L;

    /** 测试 chunkId：13 */
    private static final Long CHUNK_13 = 13L;

    /** 预算公式用例 chunk 主键起点（与 {@link #tenTokenChunks(int)} 正文夹具对应） */
    private static final long BUDGET_CHUNK_ID_BASE = 301L;

    /** 截断场景单条正文长度（TokenCounter 打桩为字符长度，即 100 token/条） */
    private static final int CHUNK_BODY_LENGTH = 100;

    /** NAIVE 模板框架空算 token 数（全空变量渲染的字符长度，与被测类字符计数口径一致） */
    private static final int NAIVE_FRAMEWORK_TOKENS = frameworkTokens(PromptTemplates.NAIVE_QUERY_CONTEXT);

    /** NAIVE 回答系统模板空算 token 数（默认变量 + 内容槽空串渲染，预算公式新增扣减项） */
    private static final int NAIVE_ANSWER_TOKENS = answerTemplateTokens(AnswerProfile.NAIVE);

    /** 截断场景预算上限：扣除回答模板空算、框架空算、query 1 字与缓冲 200 后可用 250，仅容纳 2 条 100 token */
    private static final int TRUNCATION_MAX_TOKENS = NAIVE_ANSWER_TOKENS + NAIVE_FRAMEWORK_TOKENS
            + 1 + RetrievalConstants.BUFFER_TOKENS + 250;

    /** 宽松场景预算上限：扣除回答模板空算、框架空算、query 1 字与缓冲 200 后可用 1000，容纳全部测试 chunk */
    private static final int GENEROUS_MAX_TOKENS = NAIVE_ANSWER_TOKENS + NAIVE_FRAMEWORK_TOKENS
            + 1 + RetrievalConstants.BUFFER_TOKENS + 1000;

    /** 序列化失败场景的实体名（打桩为该名称抛异常，验证跳行不中断） */
    private static final String BAD_SERIALIZATION_ENTITY = "坏实体";

    /** 图谱记录创建时间夹具（固定时刻，含非零偏移，保证渲染可复现） */
    private static final OffsetDateTime CREATED_AT = OffsetDateTime.parse("2024-05-01T08:30:00+08:00");

    /** 图谱记录创建时间的 ISO-8601 期望文本（锁定 ObjectMapper 默认输出形态，非 epoch 数字） */
    private static final String CREATED_AT_ISO_TEXT = "2024-05-01T08:30:00+08:00";

    /** P3 截断记录在上下文中的实体行前缀（逐行 JSON 以 entity 字段起头） */
    private static final String ENTITY_LINE_PREFIX = "{\"entity\":\"";

    /** P3 截断记录在上下文中的关系行前缀（逐行 JSON 以 entity1 字段起头） */
    private static final String RELATION_LINE_PREFIX = "{\"entity1\":\"";

    /** Token 计数器 Mock（打桩为字符长度计数） */
    @Mock
    private TokenCounter tokenCounter;

    /** 知识库跨 BC 只读契约 Mock */
    @Mock
    private KnowledgeBaseApi knowledgeBaseApi;

    /** 真实 JSON 序列化器（保证逐行转义口径与线上一致） */
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    /** 被测上下文构建器（构造注入上述依赖） */
    @InjectMocks
    private DefaultContextBuilder contextBuilder;

    @Test
    void should_keepOnlyChunksWithinBudget_when_build_given_cumulativeTokensExceedAvailable()
            throws JacksonException {
        // given：可用预算 250，三条 100 token 正文 → 仅前两条装入
        stubCountByLength();
        when(knowledgeBaseApi.findChunksByKbIdAndChunkIds(eq(KB_ID), any())).thenReturn(List.of(
                chunk(CHUNK_11, "a".repeat(CHUNK_BODY_LENGTH), "a.md"),
                chunk(CHUNK_12, "b".repeat(CHUNK_BODY_LENGTH), "b.md"),
                chunk(CHUNK_13, "c".repeat(CHUNK_BODY_LENGTH), "c.md")));
        String expectedChunks = jsonLine("1", "a".repeat(CHUNK_BODY_LENGTH)) + "\n"
                + jsonLine("2", "b".repeat(CHUNK_BODY_LENGTH));

        // when：走带 kbId 的完整构建入口
        KgContext result = contextBuilder.build(KB_ID,
                List.of(ranked(CHUNK_11), ranked(CHUNK_12), ranked(CHUNK_13)),
                emptyKg(), defaultShareBudget(TRUNCATION_MAX_TOKENS, "q"));

        // then：第三条超预算即停，切片两行、引用两条；批量回取按 kbId 隔离一次到位
        assertEquals(renderContext(PromptTemplates.NAIVE_QUERY_CONTEXT, "", "", expectedChunks,
                "[1] a.md\n[2] b.md"), result.contextData());
        assertEquals(List.of("[1] a.md", "[2] b.md"), result.referenceList());
        verify(knowledgeBaseApi).findChunksByKbIdAndChunkIds(eq(KB_ID), eq(List.of(CHUNK_11, CHUNK_12, CHUNK_13)));
    }

    @Test
    void should_capReferencesAtFiveAndShareIdForSameFile_when_build_given_sevenChunksSixSources()
            throws JacksonException {
        // given：预算宽松全装入；7 条 chunk 来自 6 个来源（a.md 出现两次去重共享编号 1）
        stubCountByLength();
        when(knowledgeBaseApi.findChunksByKbIdAndChunkIds(eq(KB_ID), any())).thenReturn(List.of(
                chunk(CHUNK_11, "a".repeat(CHUNK_BODY_LENGTH), "a.md"),
                chunk(CHUNK_12, "b".repeat(CHUNK_BODY_LENGTH), "a.md"),
                chunk(CHUNK_13, "c".repeat(CHUNK_BODY_LENGTH), "b.md"),
                chunk(14L, "d".repeat(CHUNK_BODY_LENGTH), "c.md"),
                chunk(15L, "e".repeat(CHUNK_BODY_LENGTH), "d.md"),
                chunk(16L, "f".repeat(CHUNK_BODY_LENGTH), "e.md"),
                chunk(17L, "g".repeat(CHUNK_BODY_LENGTH), "f.md")));
        List<RankedChunk> chunks = List.of(ranked(CHUNK_11), ranked(CHUNK_12), ranked(CHUNK_13),
                ranked(14L), ranked(15L), ranked(16L), ranked(17L));
        String expectedChunks = String.join("\n",
                jsonLine("1", "a".repeat(CHUNK_BODY_LENGTH)),
                jsonLine("1", "b".repeat(CHUNK_BODY_LENGTH)),
                jsonLine("2", "c".repeat(CHUNK_BODY_LENGTH)),
                jsonLine("3", "d".repeat(CHUNK_BODY_LENGTH)),
                jsonLine("4", "e".repeat(CHUNK_BODY_LENGTH)),
                jsonLine("5", "f".repeat(CHUNK_BODY_LENGTH)),
                jsonLine("6", "g".repeat(CHUNK_BODY_LENGTH)));

        // when
        KgContext result = contextBuilder.build(KB_ID, chunks, emptyKg(),
                defaultShareBudget(GENEROUS_MAX_TOKENS, "q"));

        // then：同文件去重共享 reference_id（切片 7 行全装入）；引用列表按首次出现序截断为 5 条
        assertEquals(renderContext(PromptTemplates.NAIVE_QUERY_CONTEXT, "", "", expectedChunks,
                "[1] a.md\n[2] b.md\n[3] c.md\n[4] d.md\n[5] e.md"), result.contextData());
        assertEquals(5, result.referenceList().size());
        assertEquals("[1] a.md", result.referenceList().get(0));
        assertEquals("[5] e.md", result.referenceList().get(4));
    }

    @Test
    void should_renderKgTemplateWithJsonLines_when_build_given_nonEmptyEntitiesAndRelations()
            throws JacksonException {
        // given：实体/关系任一非空 → MIX，选 kg_query_context；逐记录输出精简字段集 JSON 行
        stubCountByLength();
        KgSearchResult kg = new KgSearchResult(
                List.of(entityHit("张三"), entityHit("李四")),
                List.of(relationHit("张三", "喜欢")), List.of());

        // when
        KgContext result = contextBuilder.build(List.of(), kg,
                defaultShareBudget(GENEROUS_MAX_TOKENS, "查询"));

        // then：MIX 模板路由 + 实体/关系逐行结构化 JSON（中文不转义，等价 ensure_ascii=False）；
        // 全等断言同时排除 NAIVE 模板（期望文本含 Knowledge Graph Data 分节，NAIVE 渲染无此内容）
        assertEquals(renderContext(PromptTemplates.KG_QUERY_CONTEXT,
                        entityLine("张三") + "\n" + entityLine("李四"), relationLine("张三", "喜欢"), "", ""),
                result.contextData());
        // then：created_at 以 ISO-8601 文本呈现（Jackson 默认非 epoch 数字），锁定渲染格式
        assertTrue(result.contextData().contains("\"created_at\":\"" + CREATED_AT_ISO_TEXT + "\""),
                "created_at 必须渲染为 ISO-8601 文本: " + result.contextData());
        assertSame(kg, result.kgResult());
    }

    @Test
    void should_reserveAnswerTemplateTokens_when_build_given_naiveBudgetEqualsFormula() throws JacksonException {
        // given：NAIVE 形态，maxTotalTokens 恰为「回答模板空算 + 框架空算 + query 1 + 缓冲 200 + 55」；
        //        chunk 每条 10 token，可用 55 → 只应装入 5 条（余量 5 不足以再装一条）
        stubCountByLength();
        int answerTokens = answerTemplateTokens(AnswerProfile.NAIVE);
        assertTrue(answerTokens > 0, "NAIVE 回答模板空算份额必须为正，否则预算公式形同未扣");
        int maxTotalTokens = answerTokens + NAIVE_FRAMEWORK_TOKENS + 1 + RetrievalConstants.BUFFER_TOKENS + 55;
        when(knowledgeBaseApi.findChunksByKbIdAndChunkIds(eq(KB_ID), any()))
                .thenReturn(tenTokenChunks(8));

        // when
        KgContext result = contextBuilder.build(KB_ID, rankedChunks(8), emptyKg(),
                defaultShareBudget(maxTotalTokens, "q"));

        // then：逐常量可验——四项扣减后仅 5 条装入
        assertEquals(5, result.retainedChunkIds().size());
    }

    @Test
    void should_deductKgAnswerTemplateTokens_when_build_given_mixMode() throws JacksonException {
        // given：MIX 形态（实体/关系任一非空），扣减项按 MIX 侧模板：
        //        回答模板 = rag_response、上下文框架 = kg_query_context（含实际实体/关系内容）
        stubCountByLength();
        KgSearchResult kg = new KgSearchResult(List.of(entityHit("张三")),
                List.of(relationHit("张三", "喜欢")), List.of());
        int answerTokens = answerTemplateTokens(AnswerProfile.KG);
        int frameworkTokens = renderContext(PromptTemplates.KG_QUERY_CONTEXT,
                entityLine("张三"), relationLine("张三", "喜欢"), "", "").length();
        int maxTotalTokens = answerTokens + frameworkTokens + 1 + RetrievalConstants.BUFFER_TOKENS + 55;
        when(knowledgeBaseApi.findChunksByKbIdAndChunkIds(eq(KB_ID), any()))
                .thenReturn(tenTokenChunks(8));

        // when：新公式预算（四项扣减后恰好可用 55）
        KgContext result = contextBuilder.build(KB_ID, rankedChunks(8), kg,
                defaultShareBudget(maxTotalTokens, "q"));
        // when：以修正前公式口径给出的同额上限（未预留回答模板份额）
        KgContext legacyFormulaBudget = contextBuilder.build(KB_ID, rankedChunks(8), kg,
                defaultShareBudget(frameworkTokens + 1 + RetrievalConstants.BUFFER_TOKENS + 55, "q"));

        // then：新公式恰装 5 条（少扣任一项都会多装，故四项逐常量可验）
        assertEquals(5, result.retainedChunkIds().size());
        // then：回答模板份额被真实扣减——55 的余量小于该份额，旧口径预算在新公式下份额归零
        assertTrue(answerTokens > 55, "夹具前提：MIX 回答模板空算份额须大于 55，否则本断言无区分度");
        assertTrue(legacyFormulaBudget.retainedChunkIds().isEmpty());
    }

    @Test
    void should_renderWithAnswerProfileContextTemplate_when_build_given_eachMode() throws JacksonException {
        // given：两侧一致性——同一形态下，Stage 4 骨架模板名必须取自 AnswerProfile 单点
        stubCountByLength();
        KgSearchResult mixKg = new KgSearchResult(List.of(entityHit("张三")), List.of(), List.of());
        String entitiesStr = entityLine("张三");

        // when
        KgContext mix = contextBuilder.build(List.of(), mixKg,
                defaultShareBudget(GENEROUS_MAX_TOKENS, "q"));
        KgContext naive = contextBuilder.build(List.of(), emptyKg(),
                defaultShareBudget(GENEROUS_MAX_TOKENS, "q"));

        // then：MIX 用 AnswerProfile.KG 的上下文骨架、NAIVE 用 AnswerProfile.NAIVE 的骨架，
        //       与 Stage 5 的回答模板同源于同一 profile 对象（防未来两侧各判各的）
        assertEquals(AnswerProfile.KG.contextTemplateName(), PromptTemplates.KG_QUERY_CONTEXT);
        assertEquals(AnswerProfile.NAIVE.contextTemplateName(), PromptTemplates.NAIVE_QUERY_CONTEXT);
        assertEquals(renderContext(AnswerProfile.KG.contextTemplateName(), entitiesStr, "", "", ""),
                mix.contextData());
        assertEquals(renderContext(AnswerProfile.NAIVE.contextTemplateName(), "", "", "", ""),
                naive.contextData());
    }

    @Test
    void should_returnGraphOnlyContext_when_build_given_emptyChunksAndEmptyKg() {
        // given：空 chunk + 空图谱（NAIVE/MISSING 契约形态）
        stubCountByLength();
        KgSearchResult kg = emptyKg();

        // when：接口方法入口（无 kbId），不应触碰知识库回取
        KgContext result = contextBuilder.build(List.of(), kg,
                defaultShareBudget(GENEROUS_MAX_TOKENS, "q"));

        // then：正常产出空上下文产物，选 NAIVE 模板（全变量为空的期望渲染），引用列表为空，不抛异常
        assertEquals(renderContext(PromptTemplates.NAIVE_QUERY_CONTEXT, "", "", "", ""),
                result.contextData());
        assertTrue(result.referenceList().isEmpty());
        assertSame(kg, result.kgResult());
        verifyNoInteractions(knowledgeBaseApi);
    }

    @Test
    void should_skipChunkFetchAndRenderEmptyChunks_when_build_given_chunksButNoKbId() {
        // given：接口方法签名不承载 kbId → 退化路径：跳过正文回取，仅产出图谱上下文
        stubCountByLength();

        // when
        KgContext result = contextBuilder.build(List.of(ranked(CHUNK_11), ranked(CHUNK_12)),
                emptyKg(), defaultShareBudget(GENEROUS_MAX_TOKENS, "q"));

        // then：切片上下文与引用列表均为空，知识库契约零交互；退化路径不回取正文 → 装入集恒空表
        assertEquals(renderContext(PromptTemplates.NAIVE_QUERY_CONTEXT, "", "", "", ""),
                result.contextData());
        assertTrue(result.referenceList().isEmpty());
        assertEquals(List.of(), result.retainedChunkIds());
        verifyNoInteractions(knowledgeBaseApi);
    }

    @Test
    void should_exportRetainedChunkIdsInContextOrder_when_build_given_mixedChannelChunksAllWithinBudget() {
        // given：全通道命中 chunk（GRAPH/BM25/VECTOR 混合，
        // RankedChunk 不区分通道）以交错输入序 13→11→12 全部在预算内装入
        stubCountByLength();
        when(knowledgeBaseApi.findChunksByKbIdAndChunkIds(eq(KB_ID), any())).thenReturn(List.of(
                chunk(CHUNK_11, "a".repeat(CHUNK_BODY_LENGTH), "a.md"),
                chunk(CHUNK_12, "b".repeat(CHUNK_BODY_LENGTH), "b.md"),
                chunk(CHUNK_13, "c".repeat(CHUNK_BODY_LENGTH), "c.md")));

        // when
        KgContext result = contextBuilder.build(KB_ID,
                List.of(ranked(CHUNK_13), ranked(CHUNK_11), ranked(CHUNK_12)),
                emptyKg(), defaultShareBudget(GENEROUS_MAX_TOKENS, "q"));

        // then：装入集按上下文序如实导出（供通路 B 全通道直读候选）
        assertEquals(List.of(CHUNK_13, CHUNK_11, CHUNK_12), result.retainedChunkIds());
    }

    @Test
    void should_excludeBudgetTruncatedChunkId_when_build_given_cumulativeTokensExceedAvailable() {
        // given：可用预算 250 仅容纳前两条，第三条超预算截断
        stubCountByLength();
        when(knowledgeBaseApi.findChunksByKbIdAndChunkIds(eq(KB_ID), any())).thenReturn(List.of(
                chunk(CHUNK_11, "a".repeat(CHUNK_BODY_LENGTH), "a.md"),
                chunk(CHUNK_12, "b".repeat(CHUNK_BODY_LENGTH), "b.md"),
                chunk(CHUNK_13, "c".repeat(CHUNK_BODY_LENGTH), "c.md")));

        // when
        KgContext result = contextBuilder.build(KB_ID,
                List.of(ranked(CHUNK_11), ranked(CHUNK_12), ranked(CHUNK_13)),
                emptyKg(), defaultShareBudget(TRUNCATION_MAX_TOKENS, "q"));

        // then：超预算截断的 chunkId 不进入装入集
        assertEquals(List.of(CHUNK_11, CHUNK_12), result.retainedChunkIds());
    }

    @Test
    void should_alignRetainedChunkIdsWithActuallyLoadedChunks_when_build_given_fetchMissOnOneChunk() {
        // given：输入序 11→12→13，但回取结果缺失 12（悬空 ranked 条目跳过）
        stubCountByLength();
        when(knowledgeBaseApi.findChunksByKbIdAndChunkIds(eq(KB_ID), any())).thenReturn(List.of(
                chunk(CHUNK_11, "a".repeat(CHUNK_BODY_LENGTH), "a.md"),
                chunk(CHUNK_13, "c".repeat(CHUNK_BODY_LENGTH), "c.md")));

        // when
        KgContext result = contextBuilder.build(KB_ID,
                List.of(ranked(CHUNK_11), ranked(CHUNK_12), ranked(CHUNK_13)),
                emptyKg(), defaultShareBudget(GENEROUS_MAX_TOKENS, "q"));

        // then：装入集与真实装入条目对齐（缺失者不出现）
        assertEquals(List.of(CHUNK_11, CHUNK_13), result.retainedChunkIds());
    }

    @Test
    void should_normalizeAndDefensivelyCopy_when_newKgContext_given_retainedChunkIdsVariants() {
        // given：三参兼容构造器 / null 装入集 / 可变来源列表三类形态
        KgSearchResult kg = emptyKg();

        // when
        KgContext legacy = new KgContext("CTX", List.of(), kg);
        KgContext nullIds = new KgContext("CTX", List.of(), kg, null);
        List<Long> mutableIds = new ArrayList<>(List.of(CHUNK_11, CHUNK_12));
        KgContext copied = new KgContext("CTX", List.of(), kg, mutableIds);
        mutableIds.clear();

        // then：空值归一不可变空表；非空做防御拷贝（外部修改不影响值对象）
        assertEquals(List.of(), legacy.retainedChunkIds());
        assertEquals(List.of(), nullIds.retainedChunkIds());
        assertEquals(List.of(CHUNK_11, CHUNK_12), copied.retainedChunkIds());
    }

    @Test
    void should_throwIllegalArgumentException_when_build_given_nullBudget() {
        // given：预算入参缺失无法确定上限

        // when & then：快速失败
        assertThrows(IllegalArgumentException.class,
                () -> contextBuilder.build(KB_ID, List.of(ranked(CHUNK_11)), emptyKg(), null));
    }

    @Test
    void should_skipFailedEntityLineOnly_when_build_given_entitySerializationFails() throws Exception {
        // given：第二条实体的记录序列化抛错 → 单行跳过并继续，不中断整体构建
        stubCountByLength();
        // Spy 上按实参分流：记录的 entity 字段命中「坏实体」时抛序列化异常，其余走真实序列化，
        // 避免严格桩的参数不匹配告警
        doAnswer(invocation -> {
            if (isEntityLineOf(invocation.getArgument(0), BAD_SERIALIZATION_ENTITY)) {
                throw new JacksonException("模拟序列化失败") {
                };
            }
            return invocation.callRealMethod();
        }).when(objectMapper).writeValueAsString(any());

        // when
        KgContext result = contextBuilder.build(List.of(),
                new KgSearchResult(List.of(entityHit("张三"), entityHit(BAD_SERIALIZATION_ENTITY)),
                        List.of(), List.of()),
                defaultShareBudget(GENEROUS_MAX_TOKENS, "q"));

        // then：仅「张三」一行保留（MIX 模板期望渲染全等验证）
        assertEquals(renderContext(PromptTemplates.KG_QUERY_CONTEXT, entityLine("张三"), "", "", ""),
                result.contextData());
    }

    @Test
    void should_renderEmptyStringsAndDropBlankName_when_build_given_bareAndBlankEntities()
            throws JacksonException {
        // given：一条富实体、一条端点降级裸名实体（属性/时间全 null）、一条空白名实体（应跳过）
        stubCountByLength();
        KgSearchResult kg = new KgSearchResult(
                List.of(entityHit("张三"), bareEntityHit("李四"), new EntityHit("  ", 0.5D,
                        List.of(), null, null, null, null)),
                List.of(), List.of());

        // when
        KgContext result = contextBuilder.build(List.of(), kg,
                defaultShareBudget(GENEROUS_MAX_TOKENS, "查询"));

        // then：裸名实体以空串落位（键恒在）、空白名记录被剔除 → entities_str 恰两行（行数联动）
        String expectedEntities = entityLine("张三") + "\n" + bareEntityLine("李四");
        assertEquals(renderContext(PromptTemplates.KG_QUERY_CONTEXT, expectedEntities, "", "", ""),
                result.contextData());
        // then：空串落位而非 null，且时间缺失渲染为空串（非 epoch）
        assertTrue(result.contextData().contains("\"description\":\"\""));
        assertTrue(result.contextData().contains("\"created_at\":\"\""));
    }

    @Test
    void should_renderFilePathsAsJoinedSingleString_when_build_given_multiDocumentSourcePaths()
            throws JacksonException {
        // given（spec graph-source-file-paths R4）：实体与关系来源路径均为多篇（跨文档共享条目）
        stubCountByLength();
        EntityHit entity = new EntityHit("张三", 0.9D, List.of(), "人物", "实体描述-张三",
                List.of("docs/张三.md", "docs/团队.xlsx"), CREATED_AT);
        RelationHit relation = new RelationHit("张三", "李四", 0.9D, List.of(), "关系描述",
                List.of("关键词"), 1.0D, List.of("docs/a.pdf", "docs/b.pdf"), CREATED_AT);
        KgSearchResult kg = new KgSearchResult(List.of(entity), List.of(relation), List.of());

        // when
        KgContext result = contextBuilder.build(List.of(), kg,
                defaultShareBudget(GENEROUS_MAX_TOKENS, "查询"));

        // then：整体上下文与期望行全等——file_path 为 '|' 拼接单字符串、字段序与列表化之前一致
        Map<String, Object> entityRecord = new LinkedHashMap<>();
        entityRecord.put("entity", "张三");
        entityRecord.put("type", "人物");
        entityRecord.put("description", "实体描述-张三");
        entityRecord.put("created_at", CREATED_AT);
        entityRecord.put("file_path", "docs/张三.md|docs/团队.xlsx");
        Map<String, Object> relationRecord = new LinkedHashMap<>();
        relationRecord.put("entity1", "张三");
        relationRecord.put("entity2", "李四");
        relationRecord.put("description", "关系描述");
        relationRecord.put("created_at", CREATED_AT);
        relationRecord.put("file_path", "docs/a.pdf|docs/b.pdf");
        assertEquals(renderContext(PromptTemplates.KG_QUERY_CONTEXT,
                        objectMapper.writeValueAsString(entityRecord),
                        objectMapper.writeValueAsString(relationRecord), "", ""),
                result.contextData());
        // then：MUST NOT 出现数组形态
        assertFalse(result.contextData().contains("\"file_path\":["), "file_path MUST 渲染为单个字符串");
    }

    @Test
    void should_notConsumeShareByMultiFilePaths_when_build_given_manyOversizedPaths()
            throws JacksonException {
        // given（spec R4 计重口径不变）：实体携带 3 条超长来源路径，份额恰等于两条知识字段计重之和
        //（若拼接串参与计重，首条必然被截断）
        stubCountByLength();
        String oversized = "归档目录/".repeat(500);
        EntityHit first = new EntityHit("张三", 0.9D, List.of(), "人物", "实体描述-张三",
                List.of(oversized + "1", oversized + "2", oversized + "3"), CREATED_AT);
        EntityHit second = entityHit("李四");
        int knowledgeOnlyShare = entityKnowledgeWeight(first) + entityKnowledgeWeight(second);
        KgSearchResult kg = new KgSearchResult(List.of(first, second), List.of(), List.of());

        // when
        KgContext result = contextBuilder.build(List.of(), kg, new ContextBudget(GENEROUS_MAX_TOKENS, "查询",
                knowledgeOnlyShare, RetrievalQuery.DEFAULT_MAX_RELATION_TOKENS));

        // then：两条全保留，首条 file_path 为三路径以 '|' 拼接的单串
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("entity", "张三");
        record.put("type", "人物");
        record.put("description", "实体描述-张三");
        record.put("created_at", CREATED_AT);
        record.put("file_path", oversized + "1|" + oversized + "2|" + oversized + "3");
        assertEquals(renderContext(PromptTemplates.KG_QUERY_CONTEXT,
                        objectMapper.writeValueAsString(record) + "\n" + entityLine("李四"), "", "", ""),
                result.contextData());
    }

    @Test
    void should_keepAllEntityRecords_when_build_given_entityTokensExactlyEqualShare() throws JacksonException {
        // given：实体份额恰等于两条记录的知识字段计重之和（恰好等预算，P3 边界）
        stubCountByLength();
        EntityHit first = entityHit("张三");
        EntityHit second = entityHit("李四");
        int exactShare = entityKnowledgeWeight(first) + entityKnowledgeWeight(second);
        KgSearchResult kg = new KgSearchResult(List.of(first, second), List.of(), List.of());

        // when
        KgContext result = contextBuilder.build(List.of(), kg, new ContextBudget(GENEROUS_MAX_TOKENS, "查询",
                exactShare, RetrievalQuery.DEFAULT_MAX_RELATION_TOKENS));

        // then：恰好等预算 → 两条全保留（与未截断渲染全等）
        assertEquals(renderContext(PromptTemplates.KG_QUERY_CONTEXT,
                        entityLine("张三") + "\n" + entityLine("李四"), "", "", ""),
                result.contextData());
    }

    @Test
    void should_dropTailEntityRecord_when_build_given_entityTokensExceedShareByOne() throws JacksonException {
        // given：份额比两条知识字段计重之和少 1 token（超一即丢尾部，P3 边界另一侧）
        stubCountByLength();
        EntityHit first = entityHit("张三");
        EntityHit second = entityHit("李四");
        int oneShortShare = entityKnowledgeWeight(first) + entityKnowledgeWeight(second) - 1;
        KgSearchResult kg = new KgSearchResult(List.of(first, second), List.of(), List.of());

        // when
        KgContext result = contextBuilder.build(List.of(), kg, new ContextBudget(GENEROUS_MAX_TOKENS, "查询",
                oneShortShare, RetrievalQuery.DEFAULT_MAX_RELATION_TOKENS));

        // then：超即停保前缀——仅首条保留，尾部整条舍弃（不留半条）
        assertEquals(renderContext(PromptTemplates.KG_QUERY_CONTEXT, entityLine("张三"), "", "", ""),
                result.contextData());
    }

    @Test
    void should_renderEmptyEntitiesStr_when_build_given_firstEntityItselfExceedsShare() throws JacksonException {
        // given：份额小于首条记录自身的知识字段计重（单条自身即超上限）
        stubCountByLength();
        EntityHit only = entityHit("张三");
        KgSearchResult kg = new KgSearchResult(List.of(only), List.of(), List.of());

        // when
        KgContext result = contextBuilder.build(List.of(), kg, new ContextBudget(GENEROUS_MAX_TOKENS, "查询",
                entityKnowledgeWeight(only) - 1, RetrievalQuery.DEFAULT_MAX_RELATION_TOKENS));

        // then：该条整体舍弃（不留半条），entities_str 为空段但 MIX 模板路由不变（kgMode 判据在截断前）
        assertEquals(renderContext(PromptTemplates.KG_QUERY_CONTEXT, "", "", "", ""),
                result.contextData());
        assertSame(kg, result.kgResult());
    }

    @Test
    void should_notInvadeRelationShare_when_build_given_entityShareExhausted() throws JacksonException {
        // given：实体份额仅容纳首条（第二条被截断），关系份额取契约默认
        stubCountByLength();
        EntityHit first = entityHit("张三");
        EntityHit second = entityHit("李四");
        KgSearchResult kg = new KgSearchResult(List.of(first, second),
                List.of(relationHit("张三", "喜欢")), List.of());

        // when
        KgContext result = contextBuilder.build(List.of(), kg, new ContextBudget(GENEROUS_MAX_TOKENS, "查询",
                entityKnowledgeWeight(first), RetrievalQuery.DEFAULT_MAX_RELATION_TOKENS));

        // then：实体侧截断不侵占关系侧——relations_str 完整保留
        assertEquals(renderContext(PromptTemplates.KG_QUERY_CONTEXT,
                        entityLine("张三"), relationLine("张三", "喜欢"), "", ""),
                result.contextData());
    }

    @Test
    void should_notInvadeEntityShare_when_build_given_relationShareExhausted() throws JacksonException {
        // given：关系份额比唯一一条关系的计重还少 1（关系侧整体截断），实体份额取契约默认
        stubCountByLength();
        RelationHit relation = relationHit("张三", "喜欢");
        KgSearchResult kg = new KgSearchResult(List.of(entityHit("张三"), entityHit("李四")),
                List.of(relation), List.of());

        // when
        KgContext result = contextBuilder.build(List.of(), kg, new ContextBudget(GENEROUS_MAX_TOKENS, "查询",
                RetrievalQuery.DEFAULT_MAX_ENTITY_TOKENS, relationKnowledgeWeight(relation) - 1));

        // then：关系侧截断不侵占实体侧——entities_str 两条全保留、relations_str 为空段
        assertEquals(renderContext(PromptTemplates.KG_QUERY_CONTEXT,
                        entityLine("张三") + "\n" + entityLine("李四"), "", "", ""),
                result.contextData());
    }

    @Test
    void should_notConsumeShareByDisplayFields_when_build_given_oversizedFilePathAndCreatedAt()
            throws JacksonException {
        // given：两条实体的 file_path 超长（展示字段），知识字段计重不变；份额恰等于两条知识字段计重之和
        stubCountByLength();
        String oversizedPath = "归档目录/".repeat(1000);
        EntityHit first = new EntityHit("张三", 0.9D, List.of(), "人物", "实体描述-张三",
                List.of(oversizedPath), CREATED_AT);
        EntityHit second = new EntityHit("李四", 0.9D, List.of(), "人物", "实体描述-李四",
                List.of(oversizedPath), CREATED_AT);
        int knowledgeOnlyShare = entityKnowledgeWeight(first) + entityKnowledgeWeight(second);
        KgSearchResult kg = new KgSearchResult(List.of(first, second), List.of(), List.of());

        // when
        KgContext result = contextBuilder.build(List.of(), kg, new ContextBudget(GENEROUS_MAX_TOKENS, "查询",
                knowledgeOnlyShare, RetrievalQuery.DEFAULT_MAX_RELATION_TOKENS));

        // then：计重剔除 created_at/file_path——若这两个字段参与计重本用例必然截断；
        //       两条全保留且展示仍为全字段（超长 file_path 与 ISO 时间原样在行内）
        assertEquals(renderContext(PromptTemplates.KG_QUERY_CONTEXT,
                        entityLineWithPath("张三", oversizedPath) + "\n" + entityLineWithPath("李四", oversizedPath),
                        "", "", ""),
                result.contextData());
        assertTrue(result.contextData().contains(oversizedPath), "截断只决定去留，展示保留 file_path");
    }

    @Test
    void should_holdBudgetFormulaEndToEnd_when_build_given_richGraphRecordsAndManyChunks() {
        // given：P1+P3 组合回归——真实 jtokkit encode（与线上同口径），
        //        实体路 20 条富描述 + 关系路 50 条 + 30 条 chunk，份额取契约默认（2000/3000）
        TokenCounter realCounter = new JtokkitTokenCounter();
        ObjectMapper realMapper = new ObjectMapper();
        DefaultContextBuilder realBuilder = new DefaultContextBuilder(realCounter, knowledgeBaseApi, realMapper);
        int entityShare = RetrievalQuery.DEFAULT_MAX_ENTITY_TOKENS;
        int relationShare = RetrievalQuery.DEFAULT_MAX_RELATION_TOKENS;
        int maxTotalTokens = 16000;
        String query = "订单发货超时的处理规则有哪些";
        List<Chunk> chunkBodies = richChunkBodies(30);
        when(knowledgeBaseApi.findChunksByKbIdAndChunkIds(eq(KB_ID), any())).thenReturn(chunkBodies);
        KgSearchResult kg = new KgSearchResult(richEntities(20), richRelations(50), List.of());
        List<RankedChunk> chunks = rankedChunksFrom(501L, chunkBodies.size());

        // when
        KgContext result = realBuilder.build(KB_ID, chunks, kg,
                new ContextBudget(maxTotalTokens, query, entityShare, relationShare));

        // then：截断真实发生（行数缩减），且两侧知识字段计重各自 ≤ 份额上限（互不侵占）
        List<String> entityLines = recordLines(result.contextData(), ENTITY_LINE_PREFIX);
        List<String> relationLines = recordLines(result.contextData(), RELATION_LINE_PREFIX);
        assertEquals(20, kg.entities().size(), "截断不回缩图谱结果（raw_data 透传全量）");
        assertTrue(entityLines.size() < 20, "富实体必然触发截断: " + entityLines.size());
        assertTrue(relationLines.size() < 50, "富关系必然触发截断: " + relationLines.size());
        assertTrue(sumKnowledgeTokens(realCounter, realMapper, entityLines, "entity", "type", "description")
                <= entityShare, "entities_str 知识字段计重必须 ≤ maxEntityTokens");
        assertTrue(sumKnowledgeTokens(realCounter, realMapper, relationLines, "entity1", "entity2", "description")
                <= relationShare, "relations_str 知识字段计重必须 ≤ maxRelationTokens");
        // then：GRAPH 富记录不挤爆 chunk 份额——maxTotalTokens 合理时 chunk 装入量 > 0
        assertFalse(result.retainedChunkIds().isEmpty(), "GRAPH 截断后 chunk 份额必须 > 0");
        // then：端到端公式成立——contextData 总量 ≤ maxTotalTokens；
        //       实际送入 LLM 的总量（MIX 回答模板以 contextData 渲染 + query）亦 ≤ maxTotalTokens
        assertTrue(realCounter.count(result.contextData()) <= maxTotalTokens);
        int totalSentToLlm = realCounter.count(PromptCatalog.render(AnswerProfile.KG.responseTemplateName(),
                RetrievalConstants.DEFAULT_LANGUAGE, AnswerProfile.KG.responseVars(result.contextData())))
                + realCounter.count(query);
        assertTrue(totalSentToLlm <= maxTotalTokens,
                "送入 LLM 总量 " + totalSentToLlm + " 必须 ≤ maxTotalTokens " + maxTotalTokens);
        assertSame(kg, result.kgResult());
    }

    /**
     * 构造端点降级裸名实体夹具（类型/描述/来源文件/时间均为 null，渲染侧以空串落位）。
     *
     * @param name 实体名
     * @return 裸名实体命中记录
     */
    private static EntityHit bareEntityHit(String name) {
        return new EntityHit(name, 0.5D, List.of(), null, null, null, null);
    }

    /**
     * 构造裸名实体的期望渲染行（null 文本/时间以空串落位，键序同 {@link #entityLine(String)}）。
     *
     * @param name 实体名
     * @return 单行 JSON 文本
     * @throws JacksonException 序列化失败（测试内不预期）
     */
    private String bareEntityLine(String name) throws JacksonException {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("entity", name);
        record.put("type", "");
        record.put("description", "");
        record.put("created_at", "");
        record.put("file_path", "");
        return objectMapper.writeValueAsString(record);
    }

    /**
     * 判断待序列化对象是否为指定实体名的记录行（渲染与 P3 计重均为 entity 起头的 Map）。
     *
     * @param value 待序列化值（实体记录为 LinkedHashMap）
     * @param name  实体名
     * @return true 表示该值为该实体的记录行
     */
    private static boolean isEntityLineOf(Object value, String name) {
        return value instanceof Map<?, ?> record && name.equals(record.get("entity"));
    }

    /**
     * 构造实体命中夹具（除名称外属性固定，时间取 {@link #CREATED_AT}）。
     *
     * @param name 实体名
     * @return 实体命中记录
     */
    private static EntityHit entityHit(String name) {
        return new EntityHit(name, 0.9D, List.of(), "人物", "实体描述-" + name,
                List.of("docs/" + name + ".md"), CREATED_AT);
    }

    /**
     * 构造关系命中夹具（除端点外属性固定，时间取 {@link #CREATED_AT}）。
     *
     * @param sourceName 源实体名
     * @param targetName 目标实体名
     * @return 关系命中记录
     */
    private static RelationHit relationHit(String sourceName, String targetName) {
        return new RelationHit(sourceName, targetName, 0.9D, List.of(), "关系描述",
                List.of("关键词"), 1.0D, List.of("docs/relation.md"), CREATED_AT);
    }

    /**
     * 构造与被测类同口径的期望实体记录 JSON 行
     * （字段顺序 entity、type、description、created_at、file_path）。
     *
     * @param name 实体名
     * @return 单行 JSON 文本
     * @throws JacksonException 序列化失败（测试内不预期）
     */
    private String entityLine(String name) throws JacksonException {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("entity", name);
        record.put("type", "人物");
        record.put("description", "实体描述-" + name);
        record.put("created_at", CREATED_AT);
        record.put("file_path", "docs/" + name + ".md");
        return objectMapper.writeValueAsString(record);
    }

    /**
     * 构造与被测类同口径、指定 file_path 的期望实体记录 JSON 行（P3 剔除计重夹具用）。
     *
     * @param name     实体名
     * @param filePath 来源文件路径（展示字段）
     * @return 单行 JSON 文本
     * @throws JacksonException 序列化失败（测试内不预期）
     */
    private String entityLineWithPath(String name, String filePath) throws JacksonException {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("entity", name);
        record.put("type", "人物");
        record.put("description", "实体描述-" + name);
        record.put("created_at", CREATED_AT);
        record.put("file_path", filePath);
        return objectMapper.writeValueAsString(record);
    }

    /**
     * 构造与被测类同口径的期望关系记录 JSON 行
     * （字段顺序 entity1、entity2、description、created_at、file_path）。
     *
     * @param sourceName 源实体名
     * @param targetName 目标实体名
     * @return 单行 JSON 文本
     * @throws JacksonException 序列化失败（测试内不预期）
     */
    private String relationLine(String sourceName, String targetName) throws JacksonException {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("entity1", sourceName);
        record.put("entity2", targetName);
        record.put("description", "关系描述");
        record.put("created_at", CREATED_AT);
        record.put("file_path", "docs/relation.md");
        return objectMapper.writeValueAsString(record);
    }

    /**
     * 按被测 P3 计重口径计算单条实体的知识字段权重（打桩「字符长度计数」下即 JSON 长度）：
     * 仅 {@code entity/type/description} 三项参与，{@code created_at/file_path} 剔除。
     *
     * @param hit 实体命中记录
     * @return 该条实体的截断计重
     * @throws JacksonException 序列化失败（测试内不预期）
     */
    private int entityKnowledgeWeight(EntityHit hit) throws JacksonException {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("entity", StringUtils.defaultString(hit.name()));
        record.put("type", StringUtils.defaultString(hit.entityType()));
        record.put("description", StringUtils.defaultString(hit.description()));
        return objectMapper.writeValueAsString(record).length();
    }

    /**
     * 按被测 P3 计重口径计算单条关系的知识字段权重（同 {@link #entityKnowledgeWeight(EntityHit)}）。
     *
     * @param hit 关系视图记录
     * @return 该条关系的截断计重
     * @throws JacksonException 序列化失败（测试内不预期）
     */
    private int relationKnowledgeWeight(RelationHit hit) throws JacksonException {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("entity1", StringUtils.defaultString(hit.sourceName()));
        record.put("entity2", StringUtils.defaultString(hit.targetName()));
        record.put("description", StringUtils.defaultString(hit.description()));
        return objectMapper.writeValueAsString(record).length();
    }

    /**
     * 构造截断份额走契约默认常量（2000/3000）的预算夹具：
     * 非 P3 场景在「字符长度计数」与小体量夹具下不触发截断。
     *
     * @param maxTotalTokens 上下文总预算
     * @param query          查询文本
     * @return 预算夹具
     */
    private static ContextBudget defaultShareBudget(int maxTotalTokens, String query) {
        return new ContextBudget(maxTotalTokens, query,
                RetrievalQuery.DEFAULT_MAX_ENTITY_TOKENS, RetrievalQuery.DEFAULT_MAX_RELATION_TOKENS);
    }

    /**
     * 从渲染后的上下文全文中抽取指定前缀的图谱记录行（P3 截断行数与计重断言用）。
     *
     * @param contextData 渲染后的上下文全文
     * @param linePrefix  记录行前缀（如 {@code {"entity":"}）
     * @return 该侧全部记录行（保持上下文序）
     */
    private static List<String> recordLines(String contextData, String linePrefix) {
        List<String> lines = new ArrayList<>();
        for (String line : contextData.split("\n")) {
            if (line.startsWith(linePrefix)) {
                lines.add(line);
            }
        }
        return lines;
    }

    /**
     * 对一组图谱记录 JSON 行按知识字段子集累加真实 encode token（复现被测 P3 计重口径）。
     *
     * @param counter  真实 token 计数器
     * @param mapper   与被测同款的 JSON 序列化器
     * @param lines    记录行列表
     * @param keys     该形态的知识字段名（计重字段集）
     * @return 各行知识字段 dict 的 token 数之和
     */
    private static int sumKnowledgeTokens(TokenCounter counter, ObjectMapper mapper,
                                          List<String> lines, String... keys) {
        int total = 0;
        for (String line : lines) {
            Map<String, Object> parsed = mapper.readValue(line, new TypeReference<Map<String, Object>>() {
            });
            Map<String, Object> knowledge = new LinkedHashMap<>();
            for (String key : keys) {
                knowledge.put(key, parsed.getOrDefault(key, StringUtils.EMPTY));
            }
            total += counter.count(mapper.writeValueAsString(knowledge));
        }
        return total;
    }

    /**
     * 构造 P1+P3 组合回归的富实体夹具（每条描述约数百 token，总量远超默认实体份额）。
     *
     * @param count 条数
     * @return 实体命中列表
     */
    private static List<EntityHit> richEntities(int count) {
        List<EntityHit> entities = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            entities.add(new EntityHit("实体" + index, 0.95D - index * 0.01D, List.of(), "业务概念",
                    ("这是第" + index + "号实体的富描述内容，用于验证渲染前逐条截断。").repeat(8),
                    List.of("docs/entity-" + index + ".md"), CREATED_AT));
        }
        return entities;
    }

    /**
     * 构造 P1+P3 组合回归的富关系夹具（每条描述约百 token，总量远超默认关系份额）。
     *
     * @param count 条数
     * @return 关系视图列表
     */
    private static List<RelationHit> richRelations(int count) {
        List<RelationHit> relations = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            relations.add(new RelationHit("实体" + index, "对象" + index, 0.9D, List.of(),
                    ("这是第" + index + "条关系的富描述内容。").repeat(6),
                    List.of("关键词"), 1.0D, List.of("docs/relation-" + index + ".md"), CREATED_AT));
        }
        return relations;
    }

    /**
     * 构造 P1+P3 组合回归的 chunk 正文夹具（id 自 501 起，每条正文约百余 token）。
     *
     * @param count 条数
     * @return 切片列表（正文回取桩的返回形态）
     */
    private List<Chunk> richChunkBodies(int count) {
        List<Chunk> chunks = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            String content = ("切片正文示例内容，第" + index + "段。").repeat(20);
            chunks.add(chunk(501L + index, content, "rich-" + index % 6 + ".md"));
        }
        return chunks;
    }

    /**
     * 构造自指定起点 id 起的全局有序 chunk 列表（与 {@link #richChunkBodies(int)} 对应）。
     *
     * @param idBase 起点 chunkId
     * @param count  条数
     * @return 有序排序结果列表
     */
    private static List<RankedChunk> rankedChunksFrom(long idBase, int count) {
        List<RankedChunk> chunks = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            chunks.add(new RankedChunk(idBase + index, 0.9D, null));
        }
        return chunks;
    }

    /**
     * 打桩 TokenCounter 为「按字符长度计数」的确定性口径（真实 encode 不在单测依赖内）。
     */
    private void stubCountByLength() {
        when(tokenCounter.count(anyString())).thenAnswer(invocation ->
                ((String) invocation.getArgument(0)).length());
    }

    /**
     * 按「字符长度计数」口径计算指定模板的框架空算 token 数：
     * 全部上下文变量以空串渲染后的字符长度（与被测类框架空算口径一致）。
     *
     * @param templateName 上下文模板名
     * @return 框架空算 token 数
     */
    private static int frameworkTokens(String templateName) {
        return renderContext(templateName, "", "", "", "").length();
    }

    /**
     * 按「字符长度计数」口径计算回答系统模板空算 token 数：
     * 与被测类同源——取 {@link AnswerProfile} 的回答模板名与默认变量表、内容槽空串渲染。
     *
     * @param profile 模板形态（MIX / NAIVE）
     * @return 回答系统模板空算 token 数
     */
    private static int answerTemplateTokens(AnswerProfile profile) {
        return PromptCatalog.render(profile.responseTemplateName(), RetrievalConstants.DEFAULT_LANGUAGE,
                profile.responseVars(StringUtils.EMPTY)).length();
    }

    /**
     * 与被测类同口径渲染期望上下文全文：静态门面 + 全局兜底语言 + 完整变量表，
     * 用于以全等断言同时校验模板路由与变量注入。
     *
     * @param templateName     期望选中的上下文模板名
     * @param entitiesStr      实体逐行 JSON 变量值
     * @param relationsStr     关系逐行 JSON 变量值
     * @param textChunksStr    切片逐行 JSON 变量值
     * @param referenceListStr 引用列表变量值
     * @return 渲染后的期望上下文全文
     */
    private static String renderContext(String templateName, String entitiesStr, String relationsStr,
                                        String textChunksStr, String referenceListStr) {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("entities_str", entitiesStr);
        vars.put("relations_str", relationsStr);
        vars.put("text_chunks_str", textChunksStr);
        vars.put("reference_list_str", referenceListStr);
        return PromptCatalog.render(templateName, RetrievalConstants.DEFAULT_LANGUAGE, vars);
    }

    /**
     * 构造与被测类同口径的单条切片记录 JSON 行（字段顺序 reference_id、content）。
     *
     * @param referenceId 引用编号
     * @param content     切片正文
     * @return 单行 JSON 文本
     * @throws JacksonException 序列化失败（测试内不预期）
     */
    private String jsonLine(String referenceId, String content) throws JacksonException {
        Map<String, String> record = new LinkedHashMap<>();
        record.put("reference_id", referenceId);
        record.put("content", content);
        return objectMapper.writeValueAsString(record);
    }

    /**
     * 构造空图谱结果（NAIVE/MISSING 契约形态：各字段空列表）。
     *
     * @return 空 KgSearchResult
     */
    private KgSearchResult emptyKg() {
        return new KgSearchResult(List.of(), List.of(), List.of());
    }

    /**
     * 构造全局有序 chunk。
     *
     * @param chunkId chunk 标识
     * @return 排序结果值对象
     */
    private RankedChunk ranked(Long chunkId) {
        return new RankedChunk(chunkId, 0.9D, null);
    }

    /**
     * 构造预算公式用例的有序 chunk 列表（id 自 301 起，与正文夹具一一对应）。
     *
     * @param count 条数
     * @return 有序 chunk 列表
     */
    private List<RankedChunk> rankedChunks(int count) {
        List<RankedChunk> chunks = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            chunks.add(ranked(BUDGET_CHUNK_ID_BASE + index));
        }
        return chunks;
    }

    /**
     * 构造正文恰为 10 字符（即打桩口径下 10 token）的切片夹具。
     *
     * @param count 条数（须 ≤ 10，正文以序号保证唯一）
     * @return 切片列表（id 自 301 起）
     */
    private List<Chunk> tenTokenChunks(int count) {
        List<Chunk> chunks = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            String content = "0123456789".substring(0, 9) + index;
            chunks.add(chunk(BUDGET_CHUNK_ID_BASE + index, content, "budget.md"));
        }
        return chunks;
    }

    /**
     * 构造知识库切片（从库恢复口径，正文与来源文件名供上下文/引用使用）。
     *
     * @param id             切片主键
     * @param content        正文
     * @param sourceFileName 来源文件名
     * @return 切片领域模型
     */
    private Chunk chunk(Long id, String content, String sourceFileName) {
        return Chunk.restore(id, KB_ID, DOCUMENT_ID, 0, content.length(), content, null,
                ChunkContentType.TEXT, sourceFileName, null, ChunkSource.PARSED, null, null);
    }
}
