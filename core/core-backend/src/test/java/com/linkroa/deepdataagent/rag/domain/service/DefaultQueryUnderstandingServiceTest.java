package com.linkroa.deepdataagent.rag.domain.service;

import com.linkroa.deepdataagent.knowledgebase.api.KnowledgeBaseApi;
import com.linkroa.deepdataagent.knowledgebase.domain.model.RetrievalStrategyConfig;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.RetrievalStrategyType;
import com.linkroa.deepdataagent.rag.domain.enums.CacheType;
import com.linkroa.deepdataagent.rag.domain.model.KeywordPair;
import com.linkroa.deepdataagent.rag.domain.model.LlmCacheEntry;
import com.linkroa.deepdataagent.rag.domain.port.LlmChatRequest;
import com.linkroa.deepdataagent.rag.domain.port.LlmChatResult;
import com.linkroa.deepdataagent.rag.domain.port.LlmClient;
import com.linkroa.deepdataagent.rag.domain.repository.LlmCacheRepository;
import com.linkroa.deepdataagent.rag.infrastructure.prompts.catalog.PromptCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link DefaultQueryUnderstandingService} 单元测试。
 *
 * <p>以 Mock 的 {@link LlmClient} / {@link LlmCacheRepository} / {@link KnowledgeBaseApi}
 * 离线驱动，不启动 Spring 容器（Prompt 渲染经 {@code PromptCatalog} 静态门面实测，
 * 模板路由与变量注入口径以发往 {@link LlmClient} 的最终提示词内容断言；渲染异常降级分支
 * 仅在必要处以 {@link MockedStatic} 短作用域模拟）；知识库级通用 LLM（问题改写与关键词提取共用）
 * profileId 以知识库 {@code multi_model_config} 为唯一真相源，故测试夹具默认打桩
 * {@code knowledgeBaseApi.findMediaModelProfileIdByKbId(KB_ID)} 为 {@link #PROFILE_ID}
 * （未配置 / 空白场景由用例就地改桩为空白值，验证既有降级语义不变）。知识库语言 Mock 缺省
 * 返回 {@code null}（未配置），即回落 {@code RetrievalConstants.DEFAULT_LANGUAGE} 全名口径。</p>
 *
 * <p>覆盖场景：① NAIVE 策略与显式关键词路径零下游交互；② 空兜底两分支
 * （短查询回退整句、≥50 字符全空返回空对）；③ JSON 容错链（markdown 围栏、
 * 尾逗号坏 JSON、彻底不可解析回退且不回写）；④ 关键词按 {@code [，,;\n]+}
 * 切分规范化；⑤ rewrite 开关关闭 / 缓存命中 / 未命中生成并回写（含缓存键口径与
 * 读写三元组一致性）；⑥ LLM 调用与提示词渲染异常降级；⑦ 知识库未配置聊天模型短路；
 * ⑧ 缓存命中回放与回放内容不可解析的兜底；⑨ 改写产物模型与知识库投影同源。</p>
 *
 * @author DeepDataAgent
 */
@ExtendWith(MockitoExtension.class)
class DefaultQueryUnderstandingServiceTest {

    /** 测试知识库ID */
    private static final Long KB_ID = 7L;

    /** 测试聊天模型 profileId */
    private static final String PROFILE_ID = "chat-profile-1";

    /** 缓存键组成部分连接符（与被测实现口径一致，独立复算键值） */
    private static final String KEY_PART_SEPARATOR = "\n\u0001";

    /** 缓存键语言维度缺省值（库未配置时对齐 RetrievalConstants.DEFAULT_LANGUAGE 全名口径） */
    private static final String LANGUAGE = "Chinese";

    /** keywords_extraction 模板 examples 变量固定内容（实测注入最终提示词） */
    private static final String KEYWORDS_EXAMPLES =
            "{\"high_level_keywords\": [\"<high_level_keyword>\"], "
                    + "\"low_level_keywords\": [\"<low_level_keyword>\"]}";

    /** 测试查询文本（长度小于兜底阈值 50） */
    private static final String QUERY = "订单发货超时的处理规则是什么";

    /** 兜底阈值边界查询：恰为 50 字符（不小于阈值，全空时保持空关键词对） */
    private static final String LONG_QUERY = "查询".repeat(25);

    /** LLM 改写产物（含首尾空白，验证 trim） */
    private static final String REWRITE_TEXT_WITH_BLANKS = "  订单发货超时如何处理  ";

    /** 改写回写后的 token 消耗 */
    private static final Integer REWRITE_TOKENS = 120;

    /** 正常 JSON 响应：双层关键词各一项 */
    private static final String JSON_RESPONSE =
            "{\"high_level_keywords\": [\"订单管理\"], \"low_level_keywords\": [\"发货单\"]}";

    /** markdown 代码围栏包裹的 JSON 响应（容错链第一级剥离） */
    private static final String FENCED_JSON_RESPONSE = "```json\n" + JSON_RESPONSE + "\n```";

    /** 含尾逗号的坏 JSON 响应（容错链第三级基础清理修复） */
    private static final String TRAILING_COMMA_RESPONSE =
            "{\"high_level_keywords\": [\"订单管理\",], \"low_level_keywords\": [\"发货单\",]}";

    /** 无任何 JSON 主体的响应（五级容错链全失败） */
    private static final String UNPARSEABLE_RESPONSE = "很抱歉，我无法从该问题中提取关键词。";

    /** 关键词字段全为空的 JSON 响应 */
    private static final String EMPTY_ARRAYS_RESPONSE =
            "{\"high_level_keywords\": [], \"low_level_keywords\": []}";

    /** 含多分隔符复合串的 JSON 响应（验证切分规范化，\\n 为 JSON 转义换行） */
    private static final String SPLIT_RESPONSE =
            "{\"high_level_keywords\": [\"订单、物流，配送;时效\\n预约\"], "
                    + "\"low_level_keywords\": [\" 用户 \", \"\", \"发货单 \"]}";

    /** 期望的高层关键词（切分规范化后） */
    private static final List<String> EXPECTED_HL = List.of("订单", "物流", "配送", "时效", "预约");

    /** 期望的低层关键词（切分规范化后） */
    private static final List<String> EXPECTED_LL = List.of("用户", "发货单");

    /** 期望的高层关键词（常规 JSON） */
    private static final List<String> SIMPLE_HL = List.of("订单管理");

    /** 期望的低层关键词（常规 JSON） */
    private static final List<String> SIMPLE_LL = List.of("发货单");

    /** 裸 LLM 客户端 Mock（被测服务不走 @Primary 缓存装饰器） */
    @Mock
    private LlmClient llmClient;

    /** LLM 缓存仓储端口 Mock */
    @Mock
    private LlmCacheRepository llmCacheRepository;

    /** 知识库服务契约 Mock（按 kbId 只读语言投影；缺省返回 null = 库未配置） */
    @Mock
    private KnowledgeBaseApi knowledgeBaseApi;

    /** 被测查询理解服务（知识库聊天模型配置齐备的夹具默认态） */
    private DefaultQueryUnderstandingService service;

    /**
     * 构建被测服务，并打桩「知识库已配置聊天模型 profileId」这一夹具前置条件。
     * <p>夹具以 {@code lenient()} 打桩：短路分支（改写开关关闭 / 显式关键词 / NAIVE 策略）
     * 按设计不读取知识库配置，避免不必要打桩告警；未配置场景由用例就地以
     * {@link #stubChatProfile(String)} 改桩为空白值。</p>
     */
    @BeforeEach
    void setUp() {
        service = new DefaultQueryUnderstandingService(llmClient, llmCacheRepository, knowledgeBaseApi);
        lenient().when(knowledgeBaseApi.findMediaModelProfileIdByKbId(KB_ID)).thenReturn(PROFILE_ID);
    }

    // ==================== rewrite ====================

    @Test
    void should_returnOriginalQuery_when_rewrite_given_rewriteSwitchDisabled() {
        // given：改写开关关闭，不应触碰缓存与 LLM
        RetrievalStrategyConfig cfg = mixConfig(Boolean.FALSE);

        // when
        String rewritten = service.rewrite(QUERY, cfg, KB_ID);

        // then
        assertEquals(QUERY, rewritten);
        verifyNoInteractions(llmCacheRepository, llmClient);
    }

    @Test
    void should_returnOriginalQuery_when_rewrite_given_nullConfig() {
        // when
        String rewritten = service.rewrite(QUERY, null, KB_ID);

        // then
        assertEquals(QUERY, rewritten);
        verifyNoInteractions(llmCacheRepository, llmClient);
    }

    @Test
    void should_returnOriginalQuery_when_rewrite_given_blankQuery() {
        // given：开关开启但 query 空白
        RetrievalStrategyConfig cfg = mixConfig(Boolean.TRUE);

        // when
        String rewritten = service.rewrite("   ", cfg, KB_ID);

        // then
        assertEquals("   ", rewritten);
        verifyNoInteractions(llmCacheRepository, llmClient);
    }

    @Test
    void should_returnOriginalQuery_when_rewrite_given_blankChatProfileId() {
        // given：知识库聊天模型 profileId 空白视为 LLM 不可用
        stubChatProfile("   ");

        // when
        String rewritten = service.rewrite(QUERY, mixConfig(Boolean.TRUE), KB_ID);

        // then
        assertEquals(QUERY, rewritten);
        verifyNoInteractions(llmCacheRepository, llmClient);
    }

    @Test
    void should_returnOriginalQuery_when_rewrite_given_kbChatProfileNotConfigured() {
        // given：知识库未配置聊天模型（只读投影返回 null，不回退任何全局默认值）
        stubChatProfile(null);

        // when
        String rewritten = service.rewrite(QUERY, mixConfig(Boolean.TRUE), KB_ID);

        // then：降级语义与空白一致——原样返回且不触碰缓存与 LLM
        assertEquals(QUERY, rewritten);
        verifyNoInteractions(llmCacheRepository, llmClient);
    }

    @Test
    void should_useKbChatProfileId_when_rewrite_given_kbConfiguredOwnProfile() {
        // given：该库投影返回库专属 profileId（夹具默认值之外），缓存未命中
        String kbOwnProfile = "kb-chat-profile-77";
        stubChatProfile(kbOwnProfile);
        String cacheKey = cacheKey(QUERY, LANGUAGE, kbOwnProfile);
        when(llmCacheRepository.findByKbIdAndCacheKey(KB_ID, CacheType.QUERY_REWRITE, cacheKey))
                .thenReturn(Optional.empty());
        when(llmClient.chat(any(LlmChatRequest.class)))
                .thenReturn(new LlmChatResult(REWRITE_TEXT_WITH_BLANKS, REWRITE_TOKENS));

        // when
        service.rewrite(QUERY, mixConfig(Boolean.TRUE), KB_ID);

        // then：送入 LLM 的模型、缓存条目 model 列与缓存键模型要素均为该库投影值（三者同源）
        ArgumentCaptor<LlmChatRequest> requestCaptor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(llmClient).chat(requestCaptor.capture());
        assertEquals(kbOwnProfile, requestCaptor.getValue().modelProfileId());
        ArgumentCaptor<LlmCacheEntry> entryCaptor = ArgumentCaptor.forClass(LlmCacheEntry.class);
        verify(llmCacheRepository).saveIfAbsent(entryCaptor.capture());
        assertEquals(kbOwnProfile, entryCaptor.getValue().model());
        assertEquals(cacheKey, entryCaptor.getValue().cacheKey());
    }

    @Test
    void should_returnCachedResponseAndSkipLlm_when_rewrite_given_cacheHit() {
        // given：规格键缓存命中
        String cacheKey = cacheKey(QUERY);
        when(llmCacheRepository.findByKbIdAndCacheKey(KB_ID, CacheType.QUERY_REWRITE, cacheKey))
                .thenReturn(Optional.of(cacheEntry(cacheKey, CacheType.QUERY_REWRITE, "缓存中的改写问题")));

        // when
        String rewritten = service.rewrite(QUERY, mixConfig(Boolean.TRUE), KB_ID);

        // then：直返缓存响应，读写三元组与查询侧一致，不触发渲染与 LLM
        assertEquals("缓存中的改写问题", rewritten);
        verify(llmCacheRepository).findByKbIdAndCacheKey(KB_ID, CacheType.QUERY_REWRITE, cacheKey);
        verify(llmCacheRepository, never()).saveIfAbsent(any());
        verifyNoInteractions(llmClient);
    }

    @Test
    void should_rewriteAndSaveCache_when_rewrite_given_cacheMiss() {
        // given：缓存未命中，LLM 返回带首尾空白的改写文本
        String cacheKey = cacheKey(QUERY);
        when(llmCacheRepository.findByKbIdAndCacheKey(KB_ID, CacheType.QUERY_REWRITE, cacheKey))
                .thenReturn(Optional.empty());
        when(llmClient.chat(any(LlmChatRequest.class)))
                .thenReturn(new LlmChatResult(REWRITE_TEXT_WITH_BLANKS, REWRITE_TOKENS));

        // when
        String rewritten = service.rewrite(QUERY, mixConfig(Boolean.TRUE), KB_ID);

        // then：返回 trim 后文本
        assertEquals("订单发货超时如何处理", rewritten);
        // then：请求携带裸客户端 profileId、kbId；提示词为 query_rewrite 中文模板套实测渲染，
        // 模板变量口径（{query} / {language}）以最终内容断言
        ArgumentCaptor<LlmChatRequest> requestCaptor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(llmClient).chat(requestCaptor.capture());
        assertEquals(KB_ID, requestCaptor.getValue().kbId());
        assertEquals(PROFILE_ID, requestCaptor.getValue().modelProfileId());
        String renderedPrompt = requestCaptor.getValue().userPrompt();
        assertTrue(renderedPrompt.contains("问题改写专家"), "缺省语言 Chinese 应渲染中文模板套");
        assertTrue(renderedPrompt.contains("User Query: " + QUERY), "{query} 应注入用户问题原文");
        assertTrue(renderedPrompt.contains("必须使用 Chinese"), "{language} 应注入生效语言全名");
        // then：按 QUERY_REWRITE 规格键回写，读写三元组一致（prompt 即实际发出的渲染产物）
        ArgumentCaptor<LlmCacheEntry> entryCaptor = ArgumentCaptor.forClass(LlmCacheEntry.class);
        verify(llmCacheRepository).saveIfAbsent(entryCaptor.capture());
        LlmCacheEntry saved = entryCaptor.getValue();
        assertEquals(KB_ID, saved.kbId());
        assertEquals(CacheType.QUERY_REWRITE, saved.cacheType());
        assertEquals(cacheKey, saved.cacheKey());
        assertEquals(PROFILE_ID, saved.model());
        assertEquals(renderedPrompt, saved.prompt());
        assertEquals(REWRITE_TEXT_WITH_BLANKS, saved.response());
        assertEquals(REWRITE_TOKENS, saved.totalTokens());
    }

    @Test
    void should_keepOriginalQuery_when_rewrite_given_llmChatFails() {
        // given：缓存未命中且 LLM 远程调用异常
        String cacheKey = cacheKey(QUERY);
        when(llmCacheRepository.findByKbIdAndCacheKey(KB_ID, CacheType.QUERY_REWRITE, cacheKey))
                .thenReturn(Optional.empty());
        when(llmClient.chat(any(LlmChatRequest.class))).thenThrow(new RuntimeException("LLM 连接超时"));

        // when
        String rewritten = service.rewrite(QUERY, mixConfig(Boolean.TRUE), KB_ID);

        // then：降级返回原始 query，不回写缓存
        assertEquals(QUERY, rewritten);
        verify(llmCacheRepository, never()).saveIfAbsent(any());
    }

    @Test
    void should_keepOriginalQuery_when_rewrite_given_promptRenderFails() {
        // given：缓存未命中；以短作用域静态 Mock 使 PromptCatalog.render 抛异常，
        // 验证渲染失败仍走降级分支（静态目录编译期保证模板存在，此处仅覆盖防御性 catch）
        String cacheKey = cacheKey(QUERY);
        when(llmCacheRepository.findByKbIdAndCacheKey(KB_ID, CacheType.QUERY_REWRITE, cacheKey))
                .thenReturn(Optional.empty());
        String rewritten;
        try (MockedStatic<PromptCatalog> catalog = mockStatic(PromptCatalog.class)) {
            catalog.when(() -> PromptCatalog.render(anyString(), anyString(), any()))
                    .thenThrow(new IllegalStateException("Prompt 模板不存在: query_rewrite"));

            // when
            rewritten = service.rewrite(QUERY, mixConfig(Boolean.TRUE), KB_ID);
        }

        // then：降级返回原始 query，不触发 LLM 与回写
        assertEquals(QUERY, rewritten);
        verify(llmClient, never()).chat(any());
        verify(llmCacheRepository, never()).saveIfAbsent(any());
    }

    @Test
    void should_keepOriginalQuery_when_rewrite_given_llmReturnsBlankText() {
        // given：LLM 返回空白正文
        String cacheKey = cacheKey(QUERY);
        when(llmCacheRepository.findByKbIdAndCacheKey(KB_ID, CacheType.QUERY_REWRITE, cacheKey))
                .thenReturn(Optional.empty());
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult("   ", 5));

        // when
        String rewritten = service.rewrite(QUERY, mixConfig(Boolean.TRUE), KB_ID);

        // then：视为无效产物，降级原始 query 且不污染缓存
        assertEquals(QUERY, rewritten);
        verify(llmCacheRepository, never()).saveIfAbsent(any());
    }

    // ==================== extract ====================

    @Test
    void should_returnEmptyPairAndSkipLlm_when_extract_given_naiveStrategy() {
        // given：NAIVE 策略不做关键词提取
        RetrievalStrategyConfig cfg = new RetrievalStrategyConfig(
                RetrievalStrategyType.NAIVE, Boolean.TRUE, null, null, null, null);

        // when
        KeywordPair pair = service.extract(QUERY, List.of(), List.of(), cfg, KB_ID);

        // then
        assertEquals(new KeywordPair(List.of(), List.of()), pair);
        verifyNoInteractions(llmCacheRepository, llmClient);
    }

    @Test
    void should_returnExplicitKeywords_when_extract_given_explicitHighLevelProvided() {
        // given：显式高层关键词非空，低层为 null
        List<String> explicitHl = List.of("客户投诉");

        // when
        KeywordPair pair = service.extract(QUERY, explicitHl, null, mixConfig(Boolean.TRUE), KB_ID);

        // then：显式优先直返，不触发 LLM 与缓存，也不读取知识库 LLM / 语言投影（短路先于配置读取）
        assertEquals(new KeywordPair(explicitHl, List.of()), pair);
        verifyNoInteractions(llmCacheRepository, llmClient);
        verify(knowledgeBaseApi, never()).findMediaModelProfileIdByKbId(any());
        verify(knowledgeBaseApi, never()).findLanguageByKbId(any());
    }

    @Test
    void should_returnExplicitKeywords_when_extract_given_explicitLowLevelProvided() {
        // given：仅显式低层关键词非空

        // when
        KeywordPair pair = service.extract(QUERY, List.of(), List.of("发货单"), mixConfig(Boolean.FALSE), KB_ID);

        // then
        assertEquals(new KeywordPair(List.of(), List.of("发货单")), pair);
        verifyNoInteractions(llmCacheRepository, llmClient);
    }

    @Test
    void should_returnEmptyPair_when_extract_given_blankQuery() {
        // given：MIX 策略但 query 空白
        // when
        KeywordPair pair = service.extract("  ", List.of(), List.of(), mixConfig(Boolean.TRUE), KB_ID);

        // then：不做兜底（无整句可回退），零下游交互
        assertEquals(new KeywordPair(List.of(), List.of()), pair);
        verifyNoInteractions(llmCacheRepository, llmClient);
    }

    @Test
    void should_fallbackWholeQuery_when_extract_given_blankChatProfileId() {
        // given：知识库聊天模型 profileId 空白视为 LLM 不可用，短查询走空兜底
        stubChatProfile("");

        // when
        KeywordPair pair = service.extract(QUERY, List.of(), List.of(), mixConfig(Boolean.TRUE), KB_ID);

        // then
        assertEquals(new KeywordPair(List.of(), List.of(QUERY)), pair);
        verifyNoInteractions(llmCacheRepository, llmClient);
    }

    @Test
    void should_replayCachedKeywordsAndSkipLlm_when_extract_given_cacheHit() {
        // given：KEYWORD_EXTRACT 缓存命中（回放内容按同口径重解析）
        String cacheKey = cacheKey(QUERY);
        when(llmCacheRepository.findByKbIdAndCacheKey(KB_ID, CacheType.KEYWORD_EXTRACT, cacheKey))
                .thenReturn(Optional.of(cacheEntry(cacheKey, CacheType.KEYWORD_EXTRACT, JSON_RESPONSE)));

        // when
        KeywordPair pair = service.extract(QUERY, List.of(), List.of(), mixConfig(Boolean.TRUE), KB_ID);

        // then
        assertEquals(new KeywordPair(SIMPLE_HL, SIMPLE_LL), pair);
        verify(llmCacheRepository).findByKbIdAndCacheKey(KB_ID, CacheType.KEYWORD_EXTRACT, cacheKey);
        verify(llmCacheRepository, never()).saveIfAbsent(any());
        verifyNoInteractions(llmClient);
    }

    @Test
    void should_fallbackWholeQuery_when_extract_given_cachedResponseUnparseable() {
        // given：缓存命中但内容不可回放
        String cacheKey = cacheKey(QUERY);
        when(llmCacheRepository.findByKbIdAndCacheKey(KB_ID, CacheType.KEYWORD_EXTRACT, cacheKey))
                .thenReturn(Optional.of(cacheEntry(cacheKey, CacheType.KEYWORD_EXTRACT, UNPARSEABLE_RESPONSE)));

        // when
        KeywordPair pair = service.extract(QUERY, List.of(), List.of(), mixConfig(Boolean.TRUE), KB_ID);

        // then：与生成路径同口径回退整句，且不回写
        assertEquals(new KeywordPair(List.of(), List.of(QUERY)), pair);
        verify(llmCacheRepository, never()).saveIfAbsent(any());
        verifyNoInteractions(llmClient);
    }

    @Test
    void should_extractKeywordsAndSaveCache_when_extract_given_cacheMissAndFencedJson() {
        // given：缓存未命中，LLM 返回 markdown 围栏包裹的 JSON（容错链第一级）
        String cacheKey = cacheKey(QUERY);
        when(llmCacheRepository.findByKbIdAndCacheKey(KB_ID, CacheType.KEYWORD_EXTRACT, cacheKey))
                .thenReturn(Optional.empty());
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(FENCED_JSON_RESPONSE, 88));

        // when
        KeywordPair pair = service.extract(QUERY, List.of(), List.of(), mixConfig(Boolean.TRUE), KB_ID);

        // then
        assertEquals(new KeywordPair(SIMPLE_HL, SIMPLE_LL), pair);
        // then：模板变量口径（{query} / {examples} / {language}）以最终用户提示词实测
        ArgumentCaptor<LlmChatRequest> requestCaptor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(llmClient).chat(requestCaptor.capture());
        String renderedPrompt = requestCaptor.getValue().userPrompt();
        assertTrue(renderedPrompt.contains(QUERY), "{query} 应注入用户问题原文");
        assertTrue(renderedPrompt.contains(KEYWORDS_EXAMPLES), "{examples} 应注入 JSON 输出格式模板");
        assertTrue(renderedPrompt.contains(LANGUAGE), "{language} 应注入生效语言全名 Chinese");
        // then：按 KEYWORD_EXTRACT 规格键回写原始文本，供回放重解析
        ArgumentCaptor<LlmCacheEntry> entryCaptor = ArgumentCaptor.forClass(LlmCacheEntry.class);
        verify(llmCacheRepository).saveIfAbsent(entryCaptor.capture());
        LlmCacheEntry saved = entryCaptor.getValue();
        assertEquals(KB_ID, saved.kbId());
        assertEquals(CacheType.KEYWORD_EXTRACT, saved.cacheType());
        assertEquals(cacheKey, saved.cacheKey());
        assertEquals(PROFILE_ID, saved.model());
        assertEquals(renderedPrompt, saved.prompt());
        assertEquals(FENCED_JSON_RESPONSE, saved.response());
        assertEquals(Integer.valueOf(88), saved.totalTokens());
    }

    @Test
    void should_repairTrailingComma_when_extract_given_malformedJson() {
        // given：含尾逗号的坏 JSON（容错链第三级基础清理修复）
        stubCacheMiss(CacheType.KEYWORD_EXTRACT);
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(TRAILING_COMMA_RESPONSE, 30));

        // when
        KeywordPair pair = service.extract(QUERY, List.of(), List.of(), mixConfig(Boolean.TRUE), KB_ID);

        // then：修复后正常提取并回写
        assertEquals(new KeywordPair(SIMPLE_HL, SIMPLE_LL), pair);
        verify(llmCacheRepository).saveIfAbsent(any(LlmCacheEntry.class));
    }

    @Test
    void should_splitAndNormalizeKeywords_when_extract_given_multiSeparatorValues() {
        // given：单个关键词值内含顿号/中英文逗号/分号/换行与空白项
        stubCacheMiss(CacheType.KEYWORD_EXTRACT);
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(SPLIT_RESPONSE, 40));

        // when
        KeywordPair pair = service.extract(QUERY, List.of(), List.of(), mixConfig(Boolean.TRUE), KB_ID);

        // then：按 [，,;\n]+ 切分、逐项 strip、去空白项
        assertEquals(EXPECTED_HL, pair.hl());
        assertEquals(EXPECTED_LL, pair.ll());
    }

    @Test
    void should_fallbackWholeQueryAsLowLevel_when_extract_given_emptyKeywordsAndShortQuery() {
        // given：解析成功但两层级均空，query 长度小于兜底阈值
        stubCacheMiss(CacheType.KEYWORD_EXTRACT);
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(EMPTY_ARRAYS_RESPONSE, 20));

        // when
        KeywordPair pair = service.extract(QUERY, List.of(), List.of(), mixConfig(Boolean.TRUE), KB_ID);

        // then：规则 4 前半——整句作为 ll
        assertEquals(new KeywordPair(List.of(), List.of(QUERY)), pair);
    }

    @Test
    void should_returnEmptyPair_when_extract_given_emptyKeywordsAndQueryNotShorterThanThreshold() {
        // given：解析成功但两层级均空，query 恰为 50 字符（不小于阈值）
        String cacheKey = cacheKey(LONG_QUERY);
        when(llmCacheRepository.findByKbIdAndCacheKey(KB_ID, CacheType.KEYWORD_EXTRACT, cacheKey))
                .thenReturn(Optional.empty());
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(EMPTY_ARRAYS_RESPONSE, 20));

        // when
        KeywordPair pair = service.extract(LONG_QUERY, List.of(), List.of(), mixConfig(Boolean.TRUE), KB_ID);

        // then：规则 4 后半——返回空对，终止判定交由编排层
        assertTrue(pair.hl().isEmpty());
        assertTrue(pair.ll().isEmpty());
    }

    @Test
    void should_fallbackWholeQueryAndSkipSave_when_extract_given_jsonUnparseable() {
        // given：LLM 返回完全无 JSON 主体的文本（五级容错链全失败）
        stubCacheMiss(CacheType.KEYWORD_EXTRACT);
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(UNPARSEABLE_RESPONSE, 15));

        // when
        KeywordPair pair = service.extract(QUERY, List.of(), List.of(), mixConfig(Boolean.TRUE), KB_ID);

        // then：回退整句作为 ll，且不回写缓存（避免不可回放内容污染）
        assertEquals(new KeywordPair(List.of(), List.of(QUERY)), pair);
        verify(llmCacheRepository, never()).saveIfAbsent(any());
    }

    @Test
    void should_fallbackWholeQuery_when_extract_given_llmChatFails() {
        // given：渲染正常但 LLM 调用异常
        stubCacheMiss(CacheType.KEYWORD_EXTRACT);
        when(llmClient.chat(any(LlmChatRequest.class))).thenThrow(new RuntimeException("LLM 服务不可用"));

        // when
        KeywordPair pair = service.extract(QUERY, List.of(), List.of(), mixConfig(Boolean.TRUE), KB_ID);

        // then：降级整句 ll，不回写
        assertEquals(new KeywordPair(List.of(), List.of(QUERY)), pair);
        verify(llmCacheRepository, never()).saveIfAbsent(any());
    }

    @Test
    void should_fallbackWholeQuery_when_extract_given_promptRenderFails() {
        // given：缓存未命中；以短作用域静态 Mock 使 PromptCatalog.render 抛异常，
        // 验证渲染失败仍走「整句回退」降级分支（静态目录编译期保证模板存在，此处仅覆盖防御性 catch）
        String cacheKey = cacheKey(QUERY);
        when(llmCacheRepository.findByKbIdAndCacheKey(KB_ID, CacheType.KEYWORD_EXTRACT, cacheKey))
                .thenReturn(Optional.empty());
        KeywordPair pair;
        try (MockedStatic<PromptCatalog> catalog = mockStatic(PromptCatalog.class)) {
            catalog.when(() -> PromptCatalog.render(anyString(), anyString(), any()))
                    .thenThrow(new IllegalStateException("Prompt 模板不存在: keywords_extraction"));

            // when
            pair = service.extract(QUERY, List.of(), List.of(), mixConfig(Boolean.TRUE), KB_ID);
        }

        // then：降级整句 ll，不触发 LLM 与回写
        assertEquals(new KeywordPair(List.of(), List.of(QUERY)), pair);
        verify(llmClient, never()).chat(any());
        verify(llmCacheRepository, never()).saveIfAbsent(any());
    }

    @Test
    void should_keepHighLevelAndFallbackWholeQuery_when_extract_given_lowLevelFieldMissing() {
        // given：LLM 仅返回高层字段（字段缺失按空列表处理后触发空兜底）
        String response = "{\"high_level_keywords\": [\"订单管理\"]}";
        stubCacheMiss(CacheType.KEYWORD_EXTRACT);
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(response, 25));

        // when
        KeywordPair pair = service.extract(QUERY, List.of(), List.of(), mixConfig(Boolean.TRUE), KB_ID);

        // then：hl 保留、ll 缺失回退整句
        assertEquals(new KeywordPair(SIMPLE_HL, List.of(QUERY)), pair);
    }

    // ==================== 知识库语言同源 ====================

    @Test
    void should_injectKbLanguageAndUseItInCacheKey_when_rewrite_given_kbLanguageJapanese() {
        // given：日文库（只读投影返回 Japanese），缓存未命中
        when(knowledgeBaseApi.findLanguageByKbId(KB_ID)).thenReturn("Japanese");
        String japaneseCacheKey = cacheKey(QUERY, "Japanese");
        when(llmCacheRepository.findByKbIdAndCacheKey(KB_ID, CacheType.QUERY_REWRITE, japaneseCacheKey))
                .thenReturn(Optional.empty());
        when(llmClient.chat(any(LlmChatRequest.class)))
                .thenReturn(new LlmChatResult(REWRITE_TEXT_WITH_BLANKS, REWRITE_TOKENS));

        // when
        service.rewrite(QUERY, mixConfig(Boolean.TRUE), KB_ID);

        // then：{language} 注入 Japanese 且模板套与语言同源（非 Chinese → 英文模板套实测渲染）
        ArgumentCaptor<LlmChatRequest> requestCaptor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(llmClient).chat(requestCaptor.capture());
        String renderedPrompt = requestCaptor.getValue().userPrompt();
        assertTrue(renderedPrompt.contains("expert query rewriter"), "Japanese 库应渲染英文模板套");
        assertTrue(renderedPrompt.contains("MUST be in Japanese"), "{language} 应注入 Japanese");
        // then：缓存键语言要素为 Japanese（读与写均按同键，独立复算键验证）
        verify(llmCacheRepository).findByKbIdAndCacheKey(KB_ID, CacheType.QUERY_REWRITE, japaneseCacheKey);
        ArgumentCaptor<LlmCacheEntry> entryCaptor = ArgumentCaptor.forClass(LlmCacheEntry.class);
        verify(llmCacheRepository).saveIfAbsent(entryCaptor.capture());
        assertEquals(japaneseCacheKey, entryCaptor.getValue().cacheKey());
    }

    @Test
    void should_injectEnglish_when_extract_given_englishKbAndChineseQuery() {
        // given：英文库收到中文提问（query 本身即中文），MIX 策略缓存未命中
        when(knowledgeBaseApi.findLanguageByKbId(KB_ID)).thenReturn("English");
        when(llmCacheRepository.findByKbIdAndCacheKey(KB_ID, CacheType.KEYWORD_EXTRACT, cacheKey(QUERY, "English")))
                .thenReturn(Optional.empty());
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(JSON_RESPONSE, 88));

        // when
        KeywordPair pair = service.extract(QUERY, List.of(), List.of(), mixConfig(Boolean.TRUE), KB_ID);

        // then：检索词语言与图谱同源——{language} 注入 English（英文模板套实测渲染），
        // 最终回答语言规则不受本服务影响
        assertEquals(new KeywordPair(SIMPLE_HL, SIMPLE_LL), pair);
        ArgumentCaptor<LlmChatRequest> requestCaptor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(llmClient).chat(requestCaptor.capture());
        String renderedPrompt = requestCaptor.getValue().userPrompt();
        assertTrue(renderedPrompt.contains("MUST be in English"), "{language} 应注入 English");
        assertTrue(renderedPrompt.contains(QUERY), "{query} 应注入用户问题原文");
        assertTrue(renderedPrompt.contains(KEYWORDS_EXAMPLES), "{examples} 应注入输出模板");
    }

    @Test
    void should_fallbackDefaultLanguage_when_rewrite_given_kbLanguageNotConfigured() {
        // given：库未配置语言（只读投影返回 null），Mock 缺省即为 null
        String defaultKeyCache = cacheKey(QUERY, LANGUAGE);
        when(llmCacheRepository.findByKbIdAndCacheKey(KB_ID, CacheType.QUERY_REWRITE, defaultKeyCache))
                .thenReturn(Optional.empty());
        when(llmClient.chat(any(LlmChatRequest.class)))
                .thenReturn(new LlmChatResult(REWRITE_TEXT_WITH_BLANKS, REWRITE_TOKENS));

        // when
        service.rewrite(QUERY, mixConfig(Boolean.TRUE), KB_ID);

        // then：回落全局兜底语言全名 Chinese（中文模板套实测渲染，与升级前中文库行为等价）
        verify(knowledgeBaseApi).findLanguageByKbId(KB_ID);
        ArgumentCaptor<LlmChatRequest> requestCaptor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(llmClient).chat(requestCaptor.capture());
        assertTrue(requestCaptor.getValue().userPrompt().contains("问题改写专家"),
                "缺省语言应回落 Chinese 并渲染中文模板套");
    }

    @Test
    void should_fallbackDefaultLanguage_when_rewrite_given_kbLanguageReadFails() {
        // given：库语言读取抛异常（数据损坏 / 瞬时故障），按缺省语言降级不阻断检索
        when(knowledgeBaseApi.findLanguageByKbId(KB_ID)).thenThrow(new IllegalStateException("数据损坏"));
        when(llmCacheRepository.findByKbIdAndCacheKey(KB_ID, CacheType.QUERY_REWRITE, cacheKey(QUERY, LANGUAGE)))
                .thenReturn(Optional.empty());
        when(llmClient.chat(any(LlmChatRequest.class)))
                .thenReturn(new LlmChatResult(REWRITE_TEXT_WITH_BLANKS, REWRITE_TOKENS));

        // when
        String rewritten = service.rewrite(QUERY, mixConfig(Boolean.TRUE), KB_ID);

        // then：回落 Chinese 且正常产出改写结果（中文模板套 + 语言全名实测），不向编排层抛异常
        assertEquals("订单发货超时如何处理", rewritten);
        ArgumentCaptor<LlmChatRequest> requestCaptor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(llmClient).chat(requestCaptor.capture());
        assertTrue(requestCaptor.getValue().userPrompt().contains("必须使用 Chinese"),
                "语言读取失败应回落 Chinese 并注入最终提示词");
    }

    // ==================== 缓存命中计数 ====================

    @Test
    void should_incrementCacheHitsByOne_when_rewrite_given_cacheHitAndCounterProvided() {
        // given：规格键缓存命中，调用带计数器的新重载
        String cacheKey = cacheKey(QUERY);
        when(llmCacheRepository.findByKbIdAndCacheKey(KB_ID, CacheType.QUERY_REWRITE, cacheKey))
                .thenReturn(Optional.of(cacheEntry(cacheKey, CacheType.QUERY_REWRITE, "缓存中的改写问题")));
        AtomicInteger cacheHits = new AtomicInteger(0);

        // when
        String rewritten = service.rewrite(QUERY, mixConfig(Boolean.TRUE), KB_ID, cacheHits);

        // then：命中一次即递增一次，且不触发 LLM
        assertEquals("缓存中的改写问题", rewritten);
        assertEquals(1, cacheHits.get());
        verifyNoInteractions(llmClient);
    }

    @Test
    void should_notIncrementCacheHits_when_rewrite_given_cacheMissAndCounterProvided() {
        // given：缓存未命中并正常生成改写
        String cacheKey = cacheKey(QUERY);
        when(llmCacheRepository.findByKbIdAndCacheKey(KB_ID, CacheType.QUERY_REWRITE, cacheKey))
                .thenReturn(Optional.empty());
        when(llmClient.chat(any(LlmChatRequest.class)))
                .thenReturn(new LlmChatResult(REWRITE_TEXT_WITH_BLANKS, REWRITE_TOKENS));
        AtomicInteger cacheHits = new AtomicInteger(0);

        // when
        String rewritten = service.rewrite(QUERY, mixConfig(Boolean.TRUE), KB_ID, cacheHits);

        // then：未命中不计数
        assertEquals("订单发货超时如何处理", rewritten);
        assertEquals(0, cacheHits.get());
    }

    @Test
    void should_returnCachedResponseWithoutNpe_when_rewrite_given_legacyThreeArgOverload() {
        // given：旧签名委托 null 计数器，缓存命中路径不得抛空指针
        String cacheKey = cacheKey(QUERY);
        when(llmCacheRepository.findByKbIdAndCacheKey(KB_ID, CacheType.QUERY_REWRITE, cacheKey))
                .thenReturn(Optional.of(cacheEntry(cacheKey, CacheType.QUERY_REWRITE, "缓存中的改写问题")));

        // when
        String rewritten = service.rewrite(QUERY, mixConfig(Boolean.TRUE), KB_ID);

        // then：语义与新重载一致
        assertEquals("缓存中的改写问题", rewritten);
        verifyNoInteractions(llmClient);
    }

    @Test
    void should_incrementCacheHitsByOne_when_extract_given_cacheHitAndCounterProvided() {
        // given：KEYWORD_EXTRACT 缓存命中，调用带计数器的新重载
        String cacheKey = cacheKey(QUERY);
        when(llmCacheRepository.findByKbIdAndCacheKey(KB_ID, CacheType.KEYWORD_EXTRACT, cacheKey))
                .thenReturn(Optional.of(cacheEntry(cacheKey, CacheType.KEYWORD_EXTRACT, JSON_RESPONSE)));
        AtomicInteger cacheHits = new AtomicInteger(0);

        // when
        KeywordPair pair = service.extract(QUERY, List.of(), List.of(), mixConfig(Boolean.TRUE), KB_ID, cacheHits);

        // then
        assertEquals(new KeywordPair(SIMPLE_HL, SIMPLE_LL), pair);
        assertEquals(1, cacheHits.get());
        verifyNoInteractions(llmClient);
    }

    @Test
    void should_notIncrementCacheHits_when_extract_given_cacheMissAndCounterProvided() {
        // given：缓存未命中并由 LLM 正常产出关键词
        stubCacheMiss(CacheType.KEYWORD_EXTRACT);
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(JSON_RESPONSE, 88));
        AtomicInteger cacheHits = new AtomicInteger(0);

        // when
        KeywordPair pair = service.extract(QUERY, List.of(), List.of(), mixConfig(Boolean.TRUE), KB_ID, cacheHits);

        // then
        assertEquals(new KeywordPair(SIMPLE_HL, SIMPLE_LL), pair);
        assertEquals(0, cacheHits.get());
    }

    @Test
    void should_replayCachedKeywordsWithoutNpe_when_extract_given_legacyFiveArgOverload() {
        // given：旧签名委托 null 计数器
        String cacheKey = cacheKey(QUERY);
        when(llmCacheRepository.findByKbIdAndCacheKey(KB_ID, CacheType.KEYWORD_EXTRACT, cacheKey))
                .thenReturn(Optional.of(cacheEntry(cacheKey, CacheType.KEYWORD_EXTRACT, JSON_RESPONSE)));

        // when
        KeywordPair pair = service.extract(QUERY, List.of(), List.of(), mixConfig(Boolean.TRUE), KB_ID);

        // then
        assertEquals(new KeywordPair(SIMPLE_HL, SIMPLE_LL), pair);
        verifyNoInteractions(llmClient);
    }

    // ==================== helpers ====================

    /**
     * 改桩知识库级通用 LLM 模型 profileId 投影返回值（覆盖夹具默认值）。
     *
     * @param chatProfileId 投影返回值（空白表示该库未配置 LLM）
     */
    private void stubChatProfile(String chatProfileId) {
        when(knowledgeBaseApi.findMediaModelProfileIdByKbId(KB_ID)).thenReturn(chatProfileId);
    }

    /**
     * 桩化「规格键缓存未命中」（模板渲染经静态门面实测无需打桩），
     * LLM 返回由各测试用例另行桩化。
     *
     * @param cacheType 缓存分类
     */
    private void stubCacheMiss(CacheType cacheType) {
        when(llmCacheRepository.findByKbIdAndCacheKey(KB_ID, cacheType, cacheKey(QUERY)))
                .thenReturn(Optional.empty());
    }

    /**
     * 独立复算规格缓存键：{@code MD5(model + query + language + llmIdentity)}，
     * model 与 llmIdentity 均取聊天模型 profileId。
     *
     * @param query 用户查询文本
     * @return 32 位 MD5 hex 缓存键
     */
    private static String cacheKey(String query) {
        return cacheKey(query, LANGUAGE);
    }

    /**
     * 独立复算规格缓存键：{@code MD5(model + query + language + llmIdentity)}，
     * model 与 llmIdentity 均取聊天模型 profileId（夹具默认值）。
     *
     * @param query    用户查询文本
     * @param language 缓存键语言要素（生效语言全名）
     * @return 32 位 MD5 hex 缓存键
     */
    private static String cacheKey(String query, String language) {
        return cacheKey(query, language, PROFILE_ID);
    }

    /**
     * 独立复算指定语言与聊天模型要素的规格缓存键（库专属 profileId 用例断言模型要素入键）。
     *
     * @param query         用户查询文本
     * @param language      缓存键语言要素（生效语言全名）
     * @param chatProfileId 缓存键模型与 llmIdentity 要素（知识库投影值）
     * @return 32 位 MD5 hex 缓存键
     */
    private static String cacheKey(String query, String language, String chatProfileId) {
        String raw = String.join(KEY_PART_SEPARATOR, chatProfileId, query, language, chatProfileId);
        return DigestUtils.md5DigestAsHex(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 构造 MIX 策略配置。
     *
     * @param rewriteQuestion 问题改写开关
     * @return 检索策略配置
     */
    private static RetrievalStrategyConfig mixConfig(Boolean rewriteQuestion) {
        return new RetrievalStrategyConfig(RetrievalStrategyType.MIX, rewriteQuestion, null, null, null, null);
    }

    /**
     * 构造缓存条目。
     *
     * @param cacheKey 缓存键
     * @param type     缓存分类
     * @param response 模型返回原文
     * @return 缓存条目
     */
    private static LlmCacheEntry cacheEntry(String cacheKey, CacheType type, String response) {
        return LlmCacheEntry.create(KB_ID, cacheKey, type, PROFILE_ID, "PROMPT", response, 10);
    }
}
