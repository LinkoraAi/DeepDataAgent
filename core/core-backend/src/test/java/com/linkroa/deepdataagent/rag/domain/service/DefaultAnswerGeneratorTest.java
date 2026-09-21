package com.linkroa.deepdataagent.rag.domain.service;

import com.linkroa.deepdataagent.knowledgebase.api.KnowledgeBaseApi;
import com.linkroa.deepdataagent.knowledgebase.domain.model.Chunk;
import com.linkroa.deepdataagent.knowledgebase.domain.model.RerankModelConfig;
import com.linkroa.deepdataagent.knowledgebase.domain.model.RetrievalStrategyConfig;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.ChunkSource;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.RetrievalStrategyType;
import com.linkroa.deepdataagent.rag.application.contract.RetrievalMultimodalOptions;
import com.linkroa.deepdataagent.rag.application.contract.RetrievalQuery;
import com.linkroa.deepdataagent.rag.domain.enums.CacheType;
import com.linkroa.deepdataagent.rag.domain.model.EntityHit;
import com.linkroa.deepdataagent.rag.domain.model.KgContext;
import com.linkroa.deepdataagent.rag.domain.model.KgSearchResult;
import com.linkroa.deepdataagent.rag.domain.model.LlmCacheEntry;
import com.linkroa.deepdataagent.rag.domain.model.RankedChunk;
import com.linkroa.deepdataagent.rag.domain.model.RelationHit;
import com.linkroa.deepdataagent.rag.domain.port.LlmChatRequest;
import com.linkroa.deepdataagent.rag.domain.port.LlmChatResult;
import com.linkroa.deepdataagent.rag.domain.port.LlmClient;
import com.linkroa.deepdataagent.rag.domain.port.LlmImage;
import com.linkroa.deepdataagent.rag.domain.repository.LlmCacheRepository;
import com.linkroa.deepdataagent.rag.infrastructure.prompts.catalog.PromptCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link DefaultAnswerGenerator} 单元测试。
 *
 * <p>LLM 客户端与答案缓存仓储 Mockito 模拟，不启动 Spring 容器、不触碰数据库与远程模型服务；
 * 渲染已切换为静态 {@link PromptCatalog}，本测试不再 mock
 * 渲染端口：模板路由与变量注入断言改为对被测类同口径渲染的期望全文全等（期望常量在测试类加载
 * 期以目录真实模板渲染一次求得），渲染异常降级分支以 {@link MockedStatic} 受控触发。
 * 知识库级通用 LLM（作答与附图转译共用）profileId 以知识库 {@code multi_model_config} 为唯一真相源，
 * 故夹具默认打桩 {@code knowledgeBaseApi.findMediaModelProfileIdByKbId(KB_ID)} 为
 * {@link #CHAT_PROFILE_ID}
 * （未配置 / 空白场景由用例就地改桩，验证 fail_response 降级语义不变）。</p>
 *
 * <p>覆盖场景：① MIX 选 rag_response 且模板变量与请求组织正确；② NAIVE 选
 * naive_rag_response（{content_data}）；③ ANSWER 缓存命中直返且不触发 LLM；
 * ④ 未命中生成后回写（cacheType=ANSWER、key 为 32 位 hex、prompt/model/response 齐备）；
 * ⑤ resultChunkCount=0 直出 fail_response；⑥ 上下文全空直出 fail_response；
 * ⑦ CHAT profileId 空白视为 LLM 不可用；⑧ LLM 调用异常降级 fail_response；
 * ⑨ 上下文产物为 null；⑩ 策略配置为 null；⑪ 模板渲染异常降级 fail_response；
 * ⑫ fail_response 自身渲染失败返回内置兜底文案；⑬ LLM 返回空结果降级 fail_response；
 * ⑭ 缓存回写异常仅告警、已生成答案照常返回，⑮ ANSWER 缓存命中计数器的递增与不递增口径，
 * 以及旧四参重载委托空计数器的兼容性；
 * ⑯~㉑ 通路 B 引用提取/回落/截断；㉒~㉔
 * 装入集全通道候选（双源回退）、缓存命中零对象读取与未命中解析-同键回写闭环、
 * 缓存键恒为 14 要素基线形态（图片要素休眠）；
 * ㉕~㉗ 通路 B 开关矩阵：B=false×含图引用（零回取零解析、
 * 纯文本请求发出）、B=true 显式携带时的直读回归、B=false 时 ANSWER 缓存键与历史纯文本键
 * 逐字节一致。</p>
 *
 * @author DeepDataAgent
 */
@ExtendWith(MockitoExtension.class)
class DefaultAnswerGeneratorTest {

    /** 测试知识库主键 */
    private static final Long KB_ID = 1L;

    /** 测试用户问题（改写后 query） */
    private static final String QUERY = "公司 2025 年的营收是多少？";

    /** 测试 CHAT 模型 profileId */
    private static final String CHAT_PROFILE_ID = "model-chat-001";

    /** MIX 答案模板名 */
    private static final String RAG_TEMPLATE = "rag_response";

    /** NAIVE 答案模板名 */
    private static final String NAIVE_TEMPLATE = "naive_rag_response";

    /** 兜底模板名 */
    private static final String FAIL_TEMPLATE = "fail_response";

    /** 渲染语言（与 RetrievalConstants.DEFAULT_LANGUAGE 一致，全名口径） */
    private static final String LANGUAGE = "Chinese";

    /** 上下文构建产物正文 */
    private static final String CONTEXT_DATA = "CTX";

    /** rag_response 按被测类同口径（response_type/user_prompt/context_data）渲染的期望全文 */
    private static final String RENDERED_RAG_PROMPT = PromptCatalog.render(RAG_TEMPLATE, LANGUAGE,
            Map.of("response_type", "Multiple Paragraphs", "user_prompt", "n/a", "context_data", CONTEXT_DATA));

    /** naive_rag_response 按被测类同口径（response_type/user_prompt/content_data）渲染的期望全文 */
    private static final String RENDERED_NAIVE_PROMPT = PromptCatalog.render(NAIVE_TEMPLATE, LANGUAGE,
            Map.of("response_type", "Multiple Paragraphs", "user_prompt", "n/a", "content_data", CONTEXT_DATA));

    /** fail_response（无占位符）渲染的期望全文 */
    private static final String RENDERED_FAIL_TEXT = PromptCatalog.render(FAIL_TEMPLATE, LANGUAGE, Map.of());

    /** fail_response 渲染失败时的内置兜底文案（与正文一致） */
    private static final String FALLBACK_FAIL_TEXT =
            "Sorry, I'm not able to provide an answer to that question.[no-context]";

    /** LLM 返回答案 */
    private static final String LLM_ANSWER = "根据年报，公司 2025 年营收 1.2 亿元。";

    /** 缓存键格式（32 位小写 MD5 hex） */
    private static final String CACHE_KEY_HEX_PATTERN = "^[0-9a-f]{32}$";

    /** 合法的 32 位缓存键（命中场景桩数据） */
    private static final String VALID_CACHE_KEY = "0f1e2d3c4b5a69788796a5b4c3d2e1f0";

    /** 通路 B 媒体对象键 A */
    private static final String MEDIA_OBJECT_KEY_A = "rag/1/10/images/a.png";

    /** 通路 B 媒体对象键 B */
    private static final String MEDIA_OBJECT_KEY_B = "rag/1/10/images/b.gif";

    /** 通路 B 媒体对象键 C */
    private static final String MEDIA_OBJECT_KEY_C = "rag/1/10/images/c.jpg";

    /** 裸 LLM 客户端 Mock */
    @Mock
    private LlmClient llmClient;

    /** LLM 缓存仓储 Mock */
    @Mock
    private LlmCacheRepository llmCacheRepository;

    /** 知识库跨 BC 只读契约 Mock（chunk original_item 回取） */
    @Mock
    private KnowledgeBaseApi knowledgeBaseApi;

    /** 作答侧原图直读解析器 Mock */
    @Mock
    private AnswerImageResolver answerImageResolver;

    /** JSON 解析器（真实实例，供 chunk original_item 解析） */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 被测答案生成器（在线配置齐备） */
    private DefaultAnswerGenerator answerGenerator;

    /**
     * 每个用例重建被测对象，保证测试相互独立；并打桩「知识库已配置 CHAT 模型 profileId」
     * 这一夹具前置条件（以 {@code lenient()} 避免入参缺失等短路分支的不必要打桩告警，
     * 未配置 / 空白场景由用例就地改桩）。
     */
    @BeforeEach
    void setUp() {
        answerGenerator = new DefaultAnswerGenerator(llmClient, llmCacheRepository,
                knowledgeBaseApi, answerImageResolver, objectMapper);
        lenient().when(knowledgeBaseApi.findMediaModelProfileIdByKbId(KB_ID)).thenReturn(CHAT_PROFILE_ID);
    }

    /**
     * 改桩知识库级通用 LLM 模型 profileId 投影返回值（覆盖夹具默认值）。
     *
     * @param chatProfileId 投影返回值（空白表示该库未配置 LLM）
     */
    private void stubChatProfile(String chatProfileId) {
        when(knowledgeBaseApi.findMediaModelProfileIdByKbId(KB_ID)).thenReturn(chatProfileId);
    }

    /**
     * ① MIX 策略选 rag_response 模板，模板变量与 LLM 请求组织符合。
     */
    @Test
    void should_returnAnswerWithRagTemplate_when_generate_given_mixStrategy() {
        // given
        when(llmCacheRepository.findByKbIdAndCacheKey(eq(KB_ID), eq(CacheType.ANSWER), anyString()))
                .thenReturn(Optional.empty());
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(LLM_ANSWER, 42));

        // when
        String answer = answerGenerator.generate(QUERY, kgContext(), strategyConfig(RetrievalStrategyType.MIX, 5),
                retrievalQuery());

        // then：系统提示词与 rag_response 期望渲染全文全等（同时证明未选 NAIVE/兜底模板、
        // 变量已按口径注入），用户问题为改写后 query 原文
        assertEquals(LLM_ANSWER, answer);
        ArgumentCaptor<LlmChatRequest> requestCaptor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(llmClient).chat(requestCaptor.capture());
        LlmChatRequest sent = requestCaptor.getValue();
        assertEquals(KB_ID, sent.kbId());
        assertEquals(CHAT_PROFILE_ID, sent.modelProfileId());
        assertEquals(RENDERED_RAG_PROMPT, sent.systemPrompt());
        assertTrue(sent.systemPrompt().contains(CONTEXT_DATA));
        assertEquals(QUERY, sent.userPrompt());
        assertNull(sent.temperature());
    }

    /**
     * ② NAIVE 策略选 naive_rag_response 模板，上下文注入 {content_data}。
     */
    @Test
    void should_useNaiveTemplate_when_generate_given_naiveStrategy() {
        // given
        when(llmCacheRepository.findByKbIdAndCacheKey(eq(KB_ID), eq(CacheType.ANSWER), anyString()))
                .thenReturn(Optional.empty());
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(LLM_ANSWER, 10));

        // when
        String answer = answerGenerator.generate(QUERY, kgContext(),
                strategyConfig(RetrievalStrategyType.NAIVE, 5), retrievalQuery());

        // then：系统提示词与 naive_rag_response 期望渲染全文全等（同时证明未选 MIX 模板）
        assertEquals(LLM_ANSWER, answer);
        ArgumentCaptor<LlmChatRequest> requestCaptor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(llmClient).chat(requestCaptor.capture());
        assertEquals(RENDERED_NAIVE_PROMPT, requestCaptor.getValue().systemPrompt());
        assertTrue(requestCaptor.getValue().systemPrompt().contains(CONTEXT_DATA));
    }

    /**
     * 两侧一致性（批次一）：Stage 5 送入的系统提示词必须由 {@link AnswerProfile} 单点供给
     * （模板名 + 默认变量 + 内容槽），与 Stage 4 预算扣减所取的模板同源，杜绝形态错配。
     */
    @Test
    void should_renderWithAnswerProfile_when_generate_given_mixAndNaiveStrategy() {
        // given
        when(llmCacheRepository.findByKbIdAndCacheKey(eq(KB_ID), eq(CacheType.ANSWER), anyString()))
                .thenReturn(Optional.empty());
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(LLM_ANSWER, 10));

        // when：同一上下文分别走 MIX 与 NAIVE
        answerGenerator.generate(QUERY, kgContext(), strategyConfig(RetrievalStrategyType.MIX, 5),
                retrievalQuery());
        answerGenerator.generate(QUERY, kgContext(), strategyConfig(RetrievalStrategyType.NAIVE, 5),
                retrievalQuery());

        // then：两次系统提示词逐字等于按对应 profile 渲染的全文（模板名/默认变量/内容槽全同源）
        ArgumentCaptor<LlmChatRequest> captor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(llmClient, times(2)).chat(captor.capture());
        AnswerProfile mix = AnswerProfile.forKgMode(true);
        AnswerProfile naive = AnswerProfile.forKgMode(false);
        assertEquals(PromptCatalog.render(mix.responseTemplateName(), LANGUAGE, mix.responseVars(CONTEXT_DATA)),
                captor.getAllValues().get(0).systemPrompt());
        assertEquals(PromptCatalog.render(naive.responseTemplateName(), LANGUAGE, naive.responseVars(CONTEXT_DATA)),
                captor.getAllValues().get(1).systemPrompt());
        // then：profile 所指的模板名与本类既有字面量一致（收编前后行为不变）
        assertEquals(RAG_TEMPLATE, mix.responseTemplateName());
        assertEquals(NAIVE_TEMPLATE, naive.responseTemplateName());
    }

    /**
     * ③ ANSWER 缓存命中：直返缓存内容，零 LLM 交互且不回写。
     */
    @Test
    void should_returnCachedAnswer_when_generate_given_cacheHit() {
        // given
        when(llmCacheRepository.findByKbIdAndCacheKey(eq(KB_ID), eq(CacheType.ANSWER), anyString()))
                .thenReturn(Optional.of(LlmCacheEntry.create(KB_ID, VALID_CACHE_KEY, CacheType.ANSWER,
                        CHAT_PROFILE_ID, RENDERED_RAG_PROMPT, "缓存答案", 7)));

        // when
        String answer = answerGenerator.generate(QUERY, kgContext(), strategyConfig(RetrievalStrategyType.MIX, 5),
                retrievalQuery());

        // then
        assertEquals("缓存答案", answer);
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmCacheRepository).findByKbIdAndCacheKey(eq(KB_ID), eq(CacheType.ANSWER), keyCaptor.capture());
        assertTrue(keyCaptor.getValue().matches(CACHE_KEY_HEX_PATTERN));
        verifyNoInteractions(llmClient);
        verify(llmCacheRepository, never()).saveIfAbsent(any());
    }

    /**
     * ④ 缓存未命中：生成后回写 ANSWER 条目，缓存键为 32 位 MD5 hex。
     */
    @Test
    void should_saveAnswerCache_when_generate_given_cacheMiss() {
        // given
        when(llmCacheRepository.findByKbIdAndCacheKey(eq(KB_ID), eq(CacheType.ANSWER), anyString()))
                .thenReturn(Optional.empty());
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(LLM_ANSWER, 42));

        // when
        answerGenerator.generate(QUERY, kgContext(), strategyConfig(RetrievalStrategyType.MIX, 5), retrievalQuery());

        // then
        ArgumentCaptor<LlmCacheEntry> entryCaptor = ArgumentCaptor.forClass(LlmCacheEntry.class);
        verify(llmCacheRepository).saveIfAbsent(entryCaptor.capture());
        LlmCacheEntry saved = entryCaptor.getValue();
        assertEquals(KB_ID, saved.kbId());
        assertEquals(CacheType.ANSWER, saved.cacheType());
        assertTrue(saved.cacheKey().matches(CACHE_KEY_HEX_PATTERN));
        assertEquals(CHAT_PROFILE_ID, saved.model());
        assertEquals(RENDERED_RAG_PROMPT, saved.prompt());
        assertEquals(LLM_ANSWER, saved.response());
        assertEquals(42, saved.totalTokens().intValue());
    }

    /**
     * ⑤ resultChunkCount=0：直出 fail_response，不读缓存也不调用 LLM。
     */
    @Test
    void should_returnFailResponse_when_generate_given_resultChunkCountZero() {
        // when
        String answer = answerGenerator.generate(QUERY, kgContext(), strategyConfig(RetrievalStrategyType.MIX, 0),
                retrievalQuery());

        // then：产物与 fail_response 期望渲染全文全等；答案模板未被走到
        assertEquals(RENDERED_FAIL_TEXT, answer);
        verifyNoInteractions(llmClient, llmCacheRepository);
    }

    /**
     * ⑥ 上下文全空（contextData 空白且实体/关系/chunk 均空）：直出 fail_response。
     */
    @Test
    void should_returnFailResponse_when_generate_given_emptyContext() {
        // given
        KgContext emptyContext = new KgContext("", List.of(), new KgSearchResult(List.of(), List.of(), List.of()));

        // when
        String answer = answerGenerator.generate(QUERY, emptyContext, strategyConfig(RetrievalStrategyType.MIX, 5),
                retrievalQuery());

        // then
        assertEquals(RENDERED_FAIL_TEXT, answer);
        verifyNoInteractions(llmClient, llmCacheRepository);
    }

    /**
     * ⑦ 知识库聊天模型 profileId 空白：视为 LLM 与视觉能力均不可用，直出 fail_response；
     * 通路 B 前置短路——不回取 chunk、不解析原图（零对象存储交互）。
     */
    @Test
    void should_returnFailResponse_when_generate_given_blankProfileId() {
        // given
        stubChatProfile("  ");

        // when
        String answer = answerGenerator.generate(QUERY, kgContext(), strategyConfig(RetrievalStrategyType.MIX, 5),
                retrievalQuery());

        // then
        assertEquals(RENDERED_FAIL_TEXT, answer);
        verifyNoInteractions(llmClient, llmCacheRepository, answerImageResolver);
        verify(knowledgeBaseApi, never()).findChunksByKbIdAndChunkIds(any(), anyCollection());
    }

    /**
     * ⑦-b 知识库未配置对话模型（只读投影返回 null，不回退任何全局默认值）：与空白同口径
     * 直出 fail_response，不抛异常、不触达缓存与 LLM。
     */
    @Test
    void should_returnFailResponse_when_generate_given_kbChatProfileNotConfigured() {
        // given
        stubChatProfile(null);

        // when
        String answer = answerGenerator.generate(QUERY, kgContext(), strategyConfig(RetrievalStrategyType.MIX, 5),
                retrievalQuery());

        // then
        assertEquals(RENDERED_FAIL_TEXT, answer);
        verify(knowledgeBaseApi).findMediaModelProfileIdByKbId(KB_ID);
        verifyNoInteractions(llmClient, llmCacheRepository, answerImageResolver);
    }

    /**
     * ⑦-c 送入的 profileId 必须来自该知识库投影本身（LLM 请求与缓存条目 model 列同源）。
     */
    @Test
    void should_useKbChatProfileId_when_generate_given_kbConfiguredOwnProfile() {
        // given：该库返回与夹具默认值不同的专属 profileId
        String kbOwnProfile = "kb-chat-profile-88";
        stubChatProfile(kbOwnProfile);
        when(llmCacheRepository.findByKbIdAndCacheKey(eq(KB_ID), eq(CacheType.ANSWER), anyString()))
                .thenReturn(Optional.empty());
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(LLM_ANSWER, 42));

        // when
        String answer = answerGenerator.generate(QUERY, kgContext(), strategyConfig(RetrievalStrategyType.MIX, 5),
                retrievalQuery());

        // then：LLM 请求模型与缓存条目 model 列均为该库投影值
        assertEquals(LLM_ANSWER, answer);
        ArgumentCaptor<LlmChatRequest> requestCaptor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(llmClient).chat(requestCaptor.capture());
        assertEquals(kbOwnProfile, requestCaptor.getValue().modelProfileId());
        ArgumentCaptor<LlmCacheEntry> entryCaptor = ArgumentCaptor.forClass(LlmCacheEntry.class);
        verify(llmCacheRepository).saveIfAbsent(entryCaptor.capture());
        assertEquals(kbOwnProfile, entryCaptor.getValue().model());
    }

    /**
     * ⑧ LLM 调用异常：记录告警并降级为 fail_response，不向上抛出。
     */
    @Test
    void should_returnFailResponse_when_generate_given_llmCallError() {
        // given
        when(llmCacheRepository.findByKbIdAndCacheKey(eq(KB_ID), eq(CacheType.ANSWER), anyString()))
                .thenReturn(Optional.empty());
        when(llmClient.chat(any(LlmChatRequest.class))).thenThrow(new RuntimeException("上游模型连接超时"));

        // when
        String answer = answerGenerator.generate(QUERY, kgContext(), strategyConfig(RetrievalStrategyType.MIX, 5),
                retrievalQuery());

        // then
        assertEquals(RENDERED_FAIL_TEXT, answer);
        verify(llmCacheRepository, never()).saveIfAbsent(any());
    }

    /**
     * ⑨ 上下文产物为 null：等同无可利用上下文，直出 fail_response。
     */
    @Test
    void should_returnFailResponse_when_generate_given_nullContext() {
        // when
        String answer = answerGenerator.generate(QUERY, null, strategyConfig(RetrievalStrategyType.MIX, 5),
                retrievalQuery());

        // then
        assertEquals(RENDERED_FAIL_TEXT, answer);
        verifyNoInteractions(llmClient, llmCacheRepository);
    }

    /**
     * ⑩ 策略配置为 null（参数非法）：直出 fail_response。
     */
    @Test
    void should_returnFailResponse_when_generate_given_nullStrategyConfig() {
        // when
        String answer = answerGenerator.generate(QUERY, kgContext(), null, retrievalQuery());

        // then
        assertEquals(RENDERED_FAIL_TEXT, answer);
        verifyNoInteractions(llmClient, llmCacheRepository);
    }

    /**
     * ⑪ 答案模板渲染异常：降级为 fail_response，不读缓存也不调用 LLM。
     * <p>渲染为静态门面正常不抛，经 {@link MockedStatic} 对 rag_response 定向抛出以覆盖防御分支。</p>
     */
    @Test
    void should_returnFailResponse_when_generate_given_templateRenderError() {
        // when：rag_response 渲染抛异常、fail_response 渲染正常返回
        String answer;
        try (MockedStatic<PromptCatalog> catalog = mockStatic(PromptCatalog.class)) {
            catalog.when(() -> PromptCatalog.render(eq(RAG_TEMPLATE), anyString(), any()))
                    .thenThrow(new IllegalArgumentException("缺少占位符变量: {response_type}"));
            catalog.when(() -> PromptCatalog.render(eq(FAIL_TEMPLATE), anyString(), any()))
                    .thenReturn(RENDERED_FAIL_TEXT);
            answer = answerGenerator.generate(QUERY, kgContext(), strategyConfig(RetrievalStrategyType.MIX, 5),
                    retrievalQuery());
        }

        // then
        assertEquals(RENDERED_FAIL_TEXT, answer);
        verifyNoInteractions(llmClient, llmCacheRepository);
    }

    /**
     * ⑫ fail_response 自身渲染失败：返回内置兜底文案并记录错误，不抛异常。
     * <p>经 {@link MockedStatic} 对 fail_response 定向抛出以覆盖最终兜底分支。</p>
     */
    @Test
    void should_returnFallbackText_when_generate_given_failTemplateRenderError() {
        // when
        String answer;
        try (MockedStatic<PromptCatalog> catalog = mockStatic(PromptCatalog.class)) {
            catalog.when(() -> PromptCatalog.render(eq(FAIL_TEMPLATE), anyString(), any()))
                    .thenThrow(new IllegalStateException("模板资产未加载"));
            answer = answerGenerator.generate(QUERY, kgContext(), strategyConfig(RetrievalStrategyType.MIX, 0),
                    retrievalQuery());
        }

        // then
        assertEquals(FALLBACK_FAIL_TEXT, answer);
        verifyNoInteractions(llmClient, llmCacheRepository);
    }

    /**
     * ⑬ LLM 返回空结果：降级为 fail_response，不回写缓存。
     */
    @Test
    void should_returnFailResponse_when_generate_given_llmNullResult() {
        // given
        when(llmCacheRepository.findByKbIdAndCacheKey(eq(KB_ID), eq(CacheType.ANSWER), anyString()))
                .thenReturn(Optional.empty());
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(null);

        // when
        String answer = answerGenerator.generate(QUERY, kgContext(), strategyConfig(RetrievalStrategyType.MIX, 5),
                retrievalQuery());

        // then
        assertEquals(RENDERED_FAIL_TEXT, answer);
        verify(llmCacheRepository, never()).saveIfAbsent(any());
    }

    /**
     * ⑭ 缓存回写异常：仅记录告警，已生成的答案照常返回。
     */
    @Test
    void should_returnAnswer_when_generate_given_cacheSaveError() {
        // given
        when(llmCacheRepository.findByKbIdAndCacheKey(eq(KB_ID), eq(CacheType.ANSWER), anyString()))
                .thenReturn(Optional.empty());
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(LLM_ANSWER, 42));
        doThrow(new RuntimeException("缓存表写入失败"))
                .when(llmCacheRepository).saveIfAbsent(any(LlmCacheEntry.class));

        // when
        String answer = answerGenerator.generate(QUERY, kgContext(), strategyConfig(RetrievalStrategyType.MIX, 5),
                retrievalQuery());

        // then：答案照常返回且未走兜底模板
        assertEquals(LLM_ANSWER, answer);
        verify(llmCacheRepository).saveIfAbsent(any(LlmCacheEntry.class));
        assertNoFailTemplateRendered();
    }

    /**
     * ⑮-a ANSWER 缓存命中：带计数器新重载必须递增一次命中计数，供检索收尾摘要汇总。
     */
    @Test
    void should_incrementCacheHitsByOne_when_generate_given_cacheHitAndCounterProvided() {
        // given
        when(llmCacheRepository.findByKbIdAndCacheKey(eq(KB_ID), eq(CacheType.ANSWER), anyString()))
                .thenReturn(Optional.of(LlmCacheEntry.create(KB_ID, VALID_CACHE_KEY, CacheType.ANSWER,
                        CHAT_PROFILE_ID, RENDERED_RAG_PROMPT, "缓存答案", 7)));
        AtomicInteger cacheHits = new AtomicInteger(0);

        // when
        String answer = answerGenerator.generate(QUERY, kgContext(), strategyConfig(RetrievalStrategyType.MIX, 5),
                retrievalQuery(), cacheHits);

        // then
        assertEquals("缓存答案", answer);
        assertEquals(1, cacheHits.get());
        verifyNoInteractions(llmClient);
        verify(llmCacheRepository, never()).saveIfAbsent(any());
    }

    /**
     * ⑮-b 缓存未命中：走 LLM 生成路径，命中计数保持为零，避免摘要日志虚报命中率。
     */
    @Test
    void should_notIncrementCacheHits_when_generate_given_cacheMissAndCounterProvided() {
        // given
        when(llmCacheRepository.findByKbIdAndCacheKey(eq(KB_ID), eq(CacheType.ANSWER), anyString()))
                .thenReturn(Optional.empty());
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(LLM_ANSWER, 42));
        AtomicInteger cacheHits = new AtomicInteger(0);

        // when
        String answer = answerGenerator.generate(QUERY, kgContext(), strategyConfig(RetrievalStrategyType.MIX, 5),
                retrievalQuery(), cacheHits);

        // then
        assertEquals(LLM_ANSWER, answer);
        assertEquals(0, cacheHits.get());
        verify(llmClient).chat(any(LlmChatRequest.class));
    }

    /**
     * ⑮-c 旧四参重载委托空计数器：缓存命中路径不得因计数器缺位抛出空指针，答案仍正常回放。
     */
    @Test
    void should_returnCachedAnswerWithoutNpe_when_generate_given_legacyFourArgOverload() {
        // given
        when(llmCacheRepository.findByKbIdAndCacheKey(eq(KB_ID), eq(CacheType.ANSWER), anyString()))
                .thenReturn(Optional.of(LlmCacheEntry.create(KB_ID, VALID_CACHE_KEY, CacheType.ANSWER,
                        CHAT_PROFILE_ID, RENDERED_RAG_PROMPT, "缓存答案", 7)));

        // when
        String answer = assertDoesNotThrow(() -> answerGenerator.generate(QUERY, kgContext(),
                strategyConfig(RetrievalStrategyType.MIX, 5), retrievalQuery()));

        // then
        assertEquals("缓存答案", answer);
        verifyNoInteractions(llmClient);
    }

    /**
     * ⑯ 上下文含 1 个有效原图引用且视觉已配置：chat 请求携带 1 张图片（通路 B 直读）。
     */
    @Test
    void should_sendOneImageToChat_when_generate_given_contextWithOneValidReference() {
        // given
        when(llmCacheRepository.findByKbIdAndCacheKey(eq(KB_ID), eq(CacheType.ANSWER), anyString()))
                .thenReturn(Optional.empty());
        when(knowledgeBaseApi.findChunksByKbIdAndChunkIds(eq(KB_ID), anyCollection()))
                .thenReturn(List.of(mediaChunk(1L, MEDIA_OBJECT_KEY_A)));
        when(answerImageResolver.resolve(anyList())).thenReturn(oneImage());
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(LLM_ANSWER, 42));

        // when
        String answer = answerGenerator.generate(QUERY, kgContextWithChunks(1L),
                strategyConfig(RetrievalStrategyType.MIX, 5), retrievalQuery());

        // then
        assertEquals(LLM_ANSWER, answer);
        ArgumentCaptor<LlmChatRequest> requestCaptor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(llmClient).chat(requestCaptor.capture());
        assertEquals(1, requestCaptor.getValue().images().size());
        assertEquals(RENDERED_RAG_PROMPT, requestCaptor.getValue().systemPrompt());
    }

    /**
     * ⑰ 悬空引用（解析后无有效图片）：回落纯文本作答，chat 请求图片为空。
     */
    @Test
    void should_fallbackToPlainText_when_generate_given_danglingReferenceResolvedEmpty() {
        // given
        when(llmCacheRepository.findByKbIdAndCacheKey(eq(KB_ID), eq(CacheType.ANSWER), anyString()))
                .thenReturn(Optional.empty());
        when(knowledgeBaseApi.findChunksByKbIdAndChunkIds(eq(KB_ID), anyCollection()))
                .thenReturn(List.of(mediaChunk(1L, MEDIA_OBJECT_KEY_A)));
        when(answerImageResolver.resolve(anyList())).thenReturn(List.of());
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(LLM_ANSWER, 42));

        // when
        String answer = answerGenerator.generate(QUERY, kgContextWithChunks(1L),
                strategyConfig(RetrievalStrategyType.MIX, 5), retrievalQuery());

        // then：回落纯文本，图片为空
        assertEquals(LLM_ANSWER, answer);
        ArgumentCaptor<LlmChatRequest> requestCaptor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(llmClient).chat(requestCaptor.capture());
        assertTrue(requestCaptor.getValue().images().isEmpty());
    }

    /**
     * ⑱ 上下文无媒体引用（chunk 列表为空）：零知识库回取、零原图解析，纯文本路径与基线一致。
     */
    @Test
    void should_notTouchStorage_when_generate_given_contextWithoutReference() {
        // given
        when(llmCacheRepository.findByKbIdAndCacheKey(eq(KB_ID), eq(CacheType.ANSWER), anyString()))
                .thenReturn(Optional.empty());
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(LLM_ANSWER, 42));

        // when
        String answer = answerGenerator.generate(QUERY, kgContext(),
                strategyConfig(RetrievalStrategyType.MIX, 5), retrievalQuery());

        // then
        assertEquals(LLM_ANSWER, answer);
        verifyNoInteractions(answerImageResolver);
        verify(knowledgeBaseApi, never()).findChunksByKbIdAndChunkIds(any(), anyCollection());
        ArgumentCaptor<LlmChatRequest> requestCaptor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(llmClient).chat(requestCaptor.capture());
        assertTrue(requestCaptor.getValue().images().isEmpty());
    }

    /**
     * ⑲ 上下文 3 个有效引用：按相关性序全部提取交解析器（截断由解析器负责，返回 2 图），chat 携带 2 图。
     */
    @Test
    void should_passOrderedReferencesAndCapImages_when_generate_given_threeImageContextChunks() {
        // given
        when(llmCacheRepository.findByKbIdAndCacheKey(eq(KB_ID), eq(CacheType.ANSWER), anyString()))
                .thenReturn(Optional.empty());
        when(knowledgeBaseApi.findChunksByKbIdAndChunkIds(eq(KB_ID), anyCollection()))
                .thenReturn(List.of(mediaChunk(1L, MEDIA_OBJECT_KEY_A), mediaChunk(2L, MEDIA_OBJECT_KEY_B),
                        mediaChunk(3L, MEDIA_OBJECT_KEY_C)));
        when(answerImageResolver.resolve(anyList())).thenReturn(twoImages());
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(LLM_ANSWER, 42));

        // when
        answerGenerator.generate(QUERY, kgContextWithChunks(1L, 2L, 3L),
                strategyConfig(RetrievalStrategyType.MIX, 5), retrievalQuery());

        // then：解析器收到 3 条按上下文序的引用，chat 携带解析器返回的 2 图
        ArgumentCaptor<List<AnswerImageResolver.MediaReference>> refCaptor = ArgumentCaptor.forClass(List.class);
        verify(answerImageResolver).resolve(refCaptor.capture());
        List<AnswerImageResolver.MediaReference> references = refCaptor.getValue();
        assertEquals(3, references.size());
        assertEquals(MEDIA_OBJECT_KEY_A, references.get(0).objectKey());
        assertEquals(MEDIA_OBJECT_KEY_B, references.get(1).objectKey());
        assertEquals(MEDIA_OBJECT_KEY_C, references.get(2).objectKey());
        ArgumentCaptor<LlmChatRequest> requestCaptor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(llmClient).chat(requestCaptor.capture());
        assertEquals(2, requestCaptor.getValue().images().size());
    }

    /**
     * ⑳：缓存键在图片解析之前以空图片列表计算，
     * 第 15 图片要素在本层休眠——带图与无图作答键同为 14 要素基线形态，两次查询键逐字节一致。
     */
    @Test
    void should_useSameBaselineKeyWithoutImageElement_when_generate_given_imageVsNoImageContext() {
        // given
        when(llmCacheRepository.findByKbIdAndCacheKey(eq(KB_ID), eq(CacheType.ANSWER), anyString()))
                .thenReturn(Optional.empty());
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(LLM_ANSWER, 42));
        when(knowledgeBaseApi.findChunksByKbIdAndChunkIds(eq(KB_ID), anyCollection()))
                .thenReturn(List.of(mediaChunk(1L, MEDIA_OBJECT_KEY_A)));
        when(answerImageResolver.resolve(anyList())).thenReturn(oneImage());

        // when：先无图（chunk 列表空），再带图（1 引用 1 图）
        answerGenerator.generate(QUERY, kgContext(), strategyConfig(RetrievalStrategyType.MIX, 5), retrievalQuery());
        answerGenerator.generate(QUERY, kgContextWithChunks(1L), strategyConfig(RetrievalStrategyType.MIX, 5),
                retrievalQuery());

        // then：两次缓存键相同（图片要素不入键），均为 32 位 hex
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmCacheRepository, times(2))
                .findByKbIdAndCacheKey(eq(KB_ID), eq(CacheType.ANSWER), keyCaptor.capture());
        List<String> keys = keyCaptor.getAllValues();
        assertTrue(keys.get(0).matches(CACHE_KEY_HEX_PATTERN));
        assertTrue(keys.get(1).matches(CACHE_KEY_HEX_PATTERN));
        assertEquals(keys.get(0), keys.get(1), "缓存键恒为 14 要素基线形态，图片集合不再入键");
    }

    /**
     * ㉑ 带图直读调用抛异常：回落纯文本二次调用并正常返回，回落不影响回答产物。
     */
    @Test
    void should_fallbackToTextChat_when_generate_given_imageChatThrows() {
        // given
        when(llmCacheRepository.findByKbIdAndCacheKey(eq(KB_ID), eq(CacheType.ANSWER), anyString()))
                .thenReturn(Optional.empty());
        when(knowledgeBaseApi.findChunksByKbIdAndChunkIds(eq(KB_ID), anyCollection()))
                .thenReturn(List.of(mediaChunk(1L, MEDIA_OBJECT_KEY_A)));
        when(answerImageResolver.resolve(anyList())).thenReturn(oneImage());
        when(llmClient.chat(any(LlmChatRequest.class)))
                .thenThrow(new RuntimeException("视觉模型网关超时"))
                .thenReturn(new LlmChatResult(LLM_ANSWER, 42));

        // when
        String answer = answerGenerator.generate(QUERY, kgContextWithChunks(1L),
                strategyConfig(RetrievalStrategyType.MIX, 5), retrievalQuery());

        // then：先带图（异常）后纯文本（成功），共两次调用
        assertEquals(LLM_ANSWER, answer);
        ArgumentCaptor<LlmChatRequest> requestCaptor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(llmClient, times(2)).chat(requestCaptor.capture());
        assertEquals(1, requestCaptor.getAllValues().get(0).images().size());
        assertTrue(requestCaptor.getAllValues().get(1).images().isEmpty());
    }

    /**
     * ㉒-a：候选源为装入集全通道 chunkId（BM25 命中的图片块），
     * 图谱结果 chunk 为空（MIX 图谱 MISSING 形态）→ 仍完成回取并走多模态直读作答。
     */
    @Test
    void should_readImageFromBm25Channel_when_generate_given_retainedIdsContainImageChunk() {
        // given：装入集 [201(BM25 图片块), 202(VECTOR 文本块)]，图谱有序集为空
        when(llmCacheRepository.findByKbIdAndCacheKey(eq(KB_ID), eq(CacheType.ANSWER), anyString()))
                .thenReturn(Optional.empty());
        when(knowledgeBaseApi.findChunksByKbIdAndChunkIds(eq(KB_ID), eq(List.of(201L, 202L))))
                .thenReturn(List.of(mediaChunk(201L, MEDIA_OBJECT_KEY_A)));
        when(answerImageResolver.resolve(anyList())).thenReturn(oneImage());
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(LLM_ANSWER, 42));

        // when
        String answer = answerGenerator.generate(QUERY, kgContextWithRetained(List.of(201L, 202L)),
                strategyConfig(RetrievalStrategyType.MIX, 5), retrievalQuery());

        // then：按装入序回取仅携带图块引用的解析结果，chat 为携带 1 图的多模态请求
        assertEquals(LLM_ANSWER, answer);
        ArgumentCaptor<List<AnswerImageResolver.MediaReference>> refCaptor = ArgumentCaptor.forClass(List.class);
        verify(answerImageResolver).resolve(refCaptor.capture());
        assertEquals(1, refCaptor.getValue().size());
        assertEquals(MEDIA_OBJECT_KEY_A, refCaptor.getValue().get(0).objectKey());
        assertEquals(201L, refCaptor.getValue().get(0).chunkId());
        ArgumentCaptor<LlmChatRequest> requestCaptor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(llmClient).chat(requestCaptor.capture());
        assertEquals(1, requestCaptor.getValue().images().size());
    }

    /**
     * ㉒-b：装入集为空表（三参构造/退化路径）→ 回落图谱有序切片集，
     * 行为与基线一致（回取按图谱序、引用按图谱序交解析器）。
     */
    @Test
    void should_fallbackToGraphChunks_when_extractMediaReferences_given_emptyRetainedIds() {
        // given：三参兼容构造器产物（装入集恒空表）+ 图谱有序 chunk [1, 2]
        when(llmCacheRepository.findByKbIdAndCacheKey(eq(KB_ID), eq(CacheType.ANSWER), anyString()))
                .thenReturn(Optional.empty());
        when(knowledgeBaseApi.findChunksByKbIdAndChunkIds(eq(KB_ID), eq(List.of(1L, 2L))))
                .thenReturn(List.of(mediaChunk(1L, MEDIA_OBJECT_KEY_A), mediaChunk(2L, MEDIA_OBJECT_KEY_B)));
        when(answerImageResolver.resolve(anyList())).thenReturn(twoImages());
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(LLM_ANSWER, 42));

        // when
        answerGenerator.generate(QUERY, kgContextWithChunks(1L, 2L),
                strategyConfig(RetrievalStrategyType.MIX, 5), retrievalQuery());

        // then：回退命中图谱有序集且保序
        ArgumentCaptor<List<AnswerImageResolver.MediaReference>> refCaptor = ArgumentCaptor.forClass(List.class);
        verify(answerImageResolver).resolve(refCaptor.capture());
        assertEquals(List.of(MEDIA_OBJECT_KEY_A, MEDIA_OBJECT_KEY_B),
                refCaptor.getValue().stream().map(AnswerImageResolver.MediaReference::objectKey).toList());
    }

    /**
     * ㉒-c：图谱集含图块 1L，
     * 但其因 token 预算未装入（装入集仅 [2L]）→ 回取与引用解析均不触及 1L。
     */
    @Test
    void should_skipBudgetTruncatedImage_when_generate_given_chunkNotRetained() {
        // given：装入集 [2L]（1L 被预算截断未装入）
        when(llmCacheRepository.findByKbIdAndCacheKey(eq(KB_ID), eq(CacheType.ANSWER), anyString()))
                .thenReturn(Optional.empty());
        when(knowledgeBaseApi.findChunksByKbIdAndChunkIds(eq(KB_ID), eq(List.of(2L))))
                .thenReturn(List.of(mediaChunk(2L, MEDIA_OBJECT_KEY_B)));
        when(answerImageResolver.resolve(anyList())).thenReturn(oneImage());
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(LLM_ANSWER, 42));

        // when
        answerGenerator.generate(QUERY, kgContextWithRetained(List.of(2L), 1L, 2L),
                strategyConfig(RetrievalStrategyType.MIX, 5), retrievalQuery());

        // then：候选仅装入集 [2L]，截断图块 1L 不进入回取与引用
        verify(knowledgeBaseApi).findChunksByKbIdAndChunkIds(eq(KB_ID), eq(List.of(2L)));
        ArgumentCaptor<List<AnswerImageResolver.MediaReference>> refCaptor = ArgumentCaptor.forClass(List.class);
        verify(answerImageResolver).resolve(refCaptor.capture());
        assertEquals(1, refCaptor.getValue().size());
        assertEquals(MEDIA_OBJECT_KEY_B, refCaptor.getValue().get(0).objectKey());
    }

    /**
     * ㉓-a：ANSWER 缓存命中回放零对象读取——不触发 chunk 回取、
     * 不触发原图解析、不触碰 LLM，回放内容与缓存一致。
     */
    @Test
    void should_returnCachedAnswerWithoutObjectRead_when_generate_given_answerCacheHit() {
        // given：装入集含图片块（若解析必产生回取+对象读取），但缓存命中
        when(llmCacheRepository.findByKbIdAndCacheKey(eq(KB_ID), eq(CacheType.ANSWER), anyString()))
                .thenReturn(Optional.of(LlmCacheEntry.create(KB_ID, VALID_CACHE_KEY, CacheType.ANSWER,
                        CHAT_PROFILE_ID, RENDERED_RAG_PROMPT, "缓存答案", 7)));

        // when
        String answer = answerGenerator.generate(QUERY, kgContextWithRetained(List.of(1L), 1L),
                strategyConfig(RetrievalStrategyType.MIX, 5), retrievalQuery());

        // then
        assertEquals("缓存答案", answer);
        verifyNoInteractions(answerImageResolver, llmClient);
        verify(knowledgeBaseApi, never()).findChunksByKbIdAndChunkIds(any(), anyCollection());
    }

    /**
     * ㉓-b：缓存未命中才解析原图并直读作答，回写键与查询键逐字节一致
     * （同键闭环保证后续命中免解析回放）。
     */
    @Test
    void should_resolveImagesAndWriteBack_when_generate_given_cacheMiss() {
        // given
        when(llmCacheRepository.findByKbIdAndCacheKey(eq(KB_ID), eq(CacheType.ANSWER), anyString()))
                .thenReturn(Optional.empty());
        when(knowledgeBaseApi.findChunksByKbIdAndChunkIds(eq(KB_ID), eq(List.of(1L))))
                .thenReturn(List.of(mediaChunk(1L, MEDIA_OBJECT_KEY_A)));
        when(answerImageResolver.resolve(anyList())).thenReturn(oneImage());
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(LLM_ANSWER, 42));

        // when
        String answer = answerGenerator.generate(QUERY, kgContextWithRetained(List.of(1L), 1L),
                strategyConfig(RetrievalStrategyType.MIX, 5), retrievalQuery());

        // then：未命中分支完成解析与带图作答
        assertEquals(LLM_ANSWER, answer);
        verify(answerImageResolver).resolve(anyList());
        ArgumentCaptor<LlmChatRequest> requestCaptor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(llmClient).chat(requestCaptor.capture());
        assertEquals(1, requestCaptor.getValue().images().size());
        // 回写与查询同一缓存键
        ArgumentCaptor<String> queryKeyCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmCacheRepository).findByKbIdAndCacheKey(eq(KB_ID), eq(CacheType.ANSWER), queryKeyCaptor.capture());
        ArgumentCaptor<LlmCacheEntry> entryCaptor = ArgumentCaptor.forClass(LlmCacheEntry.class);
        verify(llmCacheRepository).saveIfAbsent(entryCaptor.capture());
        assertEquals(queryKeyCaptor.getValue(), entryCaptor.getValue().cacheKey());
    }

    /**
     * ㉔（端到端）：NAIVE 策略下图谱零命中、图片块经 BM25 通道装入上下文 →
     * 仍走多模态作答且引用列表不受影响；首查未命中回写后，同键二次请求命中缓存、
     * 零回取零解析（14 要素键的回写-回放闭环）。
     */
    @Test
    void should_answerMultimodalAndReplayWithoutParsing_when_generate_given_naiveChannelImageRoundTrip() {
        // given：NAIVE 上下文（图谱结果全空）+ 装入集 [301L]（BM25 图片块）
        KgContext naiveContext = new KgContext(CONTEXT_DATA, List.of("[1] 年报.pdf"),
                new KgSearchResult(List.of(), List.of(), List.of()), List.of(301L));
        when(llmCacheRepository.findByKbIdAndCacheKey(eq(KB_ID), eq(CacheType.ANSWER), anyString()))
                .thenReturn(Optional.empty());
        when(knowledgeBaseApi.findChunksByKbIdAndChunkIds(eq(KB_ID), eq(List.of(301L))))
                .thenReturn(List.of(mediaChunk(301L, MEDIA_OBJECT_KEY_A)));
        when(answerImageResolver.resolve(anyList())).thenReturn(oneImage());
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(LLM_ANSWER, 42));

        // when：第一次请求（未命中 → 解析 → 带图作答 → 回写）
        String firstAnswer = answerGenerator.generate(QUERY, naiveContext,
                strategyConfig(RetrievalStrategyType.NAIVE, 5), retrievalQuery());

        // then：多模态直读生成，模板走 NAIVE 且上下文注入 content_data（引用列表不参与请求构造、不受影响）
        assertEquals(LLM_ANSWER, firstAnswer);
        ArgumentCaptor<LlmChatRequest> requestCaptor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(llmClient).chat(requestCaptor.capture());
        assertEquals(RENDERED_NAIVE_PROMPT, requestCaptor.getValue().systemPrompt());
        assertEquals(1, requestCaptor.getValue().images().size());
        assertEquals(List.of("[1] 年报.pdf"), naiveContext.referenceList());
        ArgumentCaptor<String> queryKeyCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmCacheRepository).findByKbIdAndCacheKey(eq(KB_ID), eq(CacheType.ANSWER), queryKeyCaptor.capture());
        assertTrue(queryKeyCaptor.getValue().matches(CACHE_KEY_HEX_PATTERN));

        // when：以回写键打桩命中，发起第二次同参请求
        ArgumentCaptor<LlmCacheEntry> entryCaptor = ArgumentCaptor.forClass(LlmCacheEntry.class);
        verify(llmCacheRepository).saveIfAbsent(entryCaptor.capture());
        when(llmCacheRepository.findByKbIdAndCacheKey(eq(KB_ID), eq(CacheType.ANSWER),
                eq(entryCaptor.getValue().cacheKey())))
                .thenReturn(Optional.of(LlmCacheEntry.create(KB_ID, entryCaptor.getValue().cacheKey(),
                        CacheType.ANSWER, CHAT_PROFILE_ID, RENDERED_NAIVE_PROMPT, LLM_ANSWER, 42)));
        String secondAnswer = answerGenerator.generate(QUERY, naiveContext,
                strategyConfig(RetrievalStrategyType.NAIVE, 5), retrievalQuery());

        // then：同键命中回放（键逐字节一致），第二次请求零回取零解析、LLM 交互不再增加
        assertEquals(LLM_ANSWER, secondAnswer);
        assertEquals(queryKeyCaptor.getValue(), entryCaptor.getValue().cacheKey());
        verify(knowledgeBaseApi, times(1)).findChunksByKbIdAndChunkIds(eq(KB_ID), anyCollection());
        verify(answerImageResolver, times(1)).resolve(anyList());
        verify(llmClient, times(1)).chat(any(LlmChatRequest.class));
    }

    /**
     * ㉕-a：B=false × 上下文含可解析图片引用且视觉已配置 →
     * 零知识库回取、零原图解析（零对象存储交互），作答由纯文本请求发出。
     */
    @Test
    void should_skipImageResolutionAndSendPlainTextChat_when_generate_given_answerImageDirectReadDisabled() {
        // given：图谱结果含图片块引用（若开关开必触发回取与解析），显式关闭通路 B
        when(llmCacheRepository.findByKbIdAndCacheKey(eq(KB_ID), eq(CacheType.ANSWER), anyString()))
                .thenReturn(Optional.empty());
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(LLM_ANSWER, 42));

        // when
        String answer = answerGenerator.generate(QUERY, kgContextWithChunks(1L),
                strategyConfig(RetrievalStrategyType.MIX, 5),
                retrievalQuery(new RetrievalMultimodalOptions(true, false)));

        // then：纯文本作答产物不变，图片解析链零交互
        assertEquals(LLM_ANSWER, answer);
        verifyNoInteractions(answerImageResolver);
        verify(knowledgeBaseApi, never()).findChunksByKbIdAndChunkIds(any(), anyCollection());
        ArgumentCaptor<LlmChatRequest> requestCaptor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(llmClient).chat(requestCaptor.capture());
        assertTrue(requestCaptor.getValue().images().isEmpty());
    }

    /**
     * ㉕-b：B=true 显式携带 × 含引用 → 直读行为与基线回归一致
     * （显式全开与缺省全开产物等价）。
     */
    @Test
    void should_directReadImages_when_generate_given_answerImageDirectReadExplicitlyTrue() {
        // given
        when(llmCacheRepository.findByKbIdAndCacheKey(eq(KB_ID), eq(CacheType.ANSWER), anyString()))
                .thenReturn(Optional.empty());
        when(knowledgeBaseApi.findChunksByKbIdAndChunkIds(eq(KB_ID), anyCollection()))
                .thenReturn(List.of(mediaChunk(1L, MEDIA_OBJECT_KEY_A)));
        when(answerImageResolver.resolve(anyList())).thenReturn(oneImage());
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(LLM_ANSWER, 42));

        // when
        String answer = answerGenerator.generate(QUERY, kgContextWithChunks(1L),
                strategyConfig(RetrievalStrategyType.MIX, 5),
                retrievalQuery(RetrievalMultimodalOptions.defaults()));

        // then：与 ⑯ 基线断言一致——回取+解析发生，chat 携带 1 图
        assertEquals(LLM_ANSWER, answer);
        verify(knowledgeBaseApi).findChunksByKbIdAndChunkIds(eq(KB_ID), anyCollection());
        verify(answerImageResolver).resolve(anyList());
        ArgumentCaptor<LlmChatRequest> requestCaptor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(llmClient).chat(requestCaptor.capture());
        assertEquals(1, requestCaptor.getValue().images().size());
    }

    /**
     * ㉖：B=false 时 ANSWER 缓存键与历史纯文本键逐字节一致
     * （键不追加图片要素，关闭开关后与同语义纯文本请求互 hit 属可接受回放，防串扰口径不劣化）。
     */
    @Test
    void should_useSamePlainTextCacheKey_when_generate_given_directReadDisabledVsPlainTextHistory() {
        // given
        when(llmCacheRepository.findByKbIdAndCacheKey(eq(KB_ID), eq(CacheType.ANSWER), anyString()))
                .thenReturn(Optional.empty());
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(LLM_ANSWER, 42));

        // when：第一次为历史纯文本请求（缺省开关、无图上下文），第二次带图引用上下文但关闭通路 B
        answerGenerator.generate(QUERY, kgContext(), strategyConfig(RetrievalStrategyType.MIX, 5),
                retrievalQuery());
        answerGenerator.generate(QUERY, kgContextWithChunks(1L), strategyConfig(RetrievalStrategyType.MIX, 5),
                retrievalQuery(new RetrievalMultimodalOptions(true, false)));

        // then：两次查询缓存键逐字节一致，且第二次未产生任何对象存储侧交互
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmCacheRepository, times(2))
                .findByKbIdAndCacheKey(eq(KB_ID), eq(CacheType.ANSWER), keyCaptor.capture());
        List<String> keys = keyCaptor.getAllValues();
        assertEquals(keys.get(0), keys.get(1), "B=false 的缓存键必须与历史纯文本键逐字节一致");
        verifyNoInteractions(answerImageResolver);
        verify(knowledgeBaseApi, never()).findChunksByKbIdAndChunkIds(any(), anyCollection());
    }

    /**
     * 断言未走 fail_response 兜底：以捕获的最后一次 chat 请求系统提示词为 MIX 期望渲染佐证。
     * <p>渲染已静态化，无法 verify 渲染端口；正常作答路径 systemPrompt 必为 rag_response 产物。</p>
     */
    private void assertNoFailTemplateRendered() {
        ArgumentCaptor<LlmChatRequest> requestCaptor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(llmClient).chat(requestCaptor.capture());
        assertEquals(RENDERED_RAG_PROMPT, requestCaptor.getValue().systemPrompt());
    }

    /**
     * 构造携带装入集（retainedChunkIds）的上下文产物。
     *
     * @param retainedChunkIds token 预算内实际装入上下文的全通道 chunkId（按上下文序）
     * @param graphChunkIds    图谱结果有序 chunkId（可为空，用于验证回落/共存语义）
     * @return 四参上下文产物
     */
    private KgContext kgContextWithRetained(List<Long> retainedChunkIds, Long... graphChunkIds) {
        List<RankedChunk> ranked = new ArrayList<>(graphChunkIds.length);
        for (Long chunkId : graphChunkIds) {
            ranked.add(new RankedChunk(chunkId, 0.9D, "doc.pdf"));
        }
        return new KgContext(CONTEXT_DATA, List.of("[1] doc.pdf"),
                new KgSearchResult(graphEntities(), graphRelations(), ranked), retainedChunkIds);
    }

    /**
     * 构造携带媒体图片引用的切片（{@code original_item} 仅含 mediaObjectKey 一键，桶概念已退役）。
     *
     * @param id        切片主键
     * @param objectKey 媒体对象键
     * @return 切片领域模型
     */
    private Chunk mediaChunk(Long id, String objectKey) {
        String originalItem = "{\"" + MultimodalMetaKeys.META_KEY_MEDIA_OBJECT_KEY + "\":\"" + objectKey + "\"}";
        return Chunk.restore(id, KB_ID, 10L, 1, 100, "切片正文", originalItem, null, "doc.pdf",
                null, ChunkSource.PARSED, null, null);
    }

    /**
     * 构造图谱结果携带指定 chunkId 序（按相关性序）的上下文。
     *
     * @param chunkIds chunk 主键（顺序即上下文相关性序）
     * @return 上下文产物
     */
    private KgContext kgContextWithChunks(Long... chunkIds) {
        List<RankedChunk> ranked = new ArrayList<>(chunkIds.length);
        for (Long chunkId : chunkIds) {
            ranked.add(new RankedChunk(chunkId, 0.9D, "doc.pdf"));
        }
        return new KgContext(CONTEXT_DATA, List.of("[1] doc.pdf"),
                new KgSearchResult(graphEntities(), graphRelations(), ranked));
    }

    /**
     * 图谱实体命中夹具（本类只消费「是否非空」判据，属性取固定值即可）。
     *
     * @return 实体候选集（单条）
     */
    private static List<EntityHit> graphEntities() {
        return List.of(new EntityHit("公司", 0.9D, List.of(), "企业", "公司实体描述", List.of("doc.pdf"), null));
    }

    /**
     * 图谱关系视图夹具（端点为原生双字段，不再有「源 - 目标」拼接串）。
     *
     * @return 关系视图（单条）
     */
    private static List<RelationHit> graphRelations() {
        return List.of(new RelationHit("公司", "营收", 0.9D, List.of(), "公司营收关系描述",
                List.of("营收"), 1.0D, List.of("doc.pdf"), null));
    }

    /**
     * 构造 1 张测试图片载荷。
     *
     * @return 单图列表
     */
    private List<LlmImage> oneImage() {
        return List.of(new LlmImage("image/png", "IMAGE-A".getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * 构造 2 张测试图片载荷。
     *
     * @return 两图列表
     */
    private List<LlmImage> twoImages() {
        return List.of(new LlmImage("image/png", "IMAGE-A".getBytes(StandardCharsets.UTF_8)),
                new LlmImage("image/gif", "IMAGE-B".getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * 构造检索策略配置（融合配置与答案生成无关，测试传 null）。
     *
     * @param strategyType     策略类型
     * @param resultChunkCount 结果返回数量
     * @return 策略配置
     */
    private RetrievalStrategyConfig strategyConfig(RetrievalStrategyType strategyType, Integer resultChunkCount) {
        return new RetrievalStrategyConfig(strategyType, Boolean.TRUE, resultChunkCount, 0.2F,
                new RerankModelConfig(Boolean.FALSE, "model-rerank-001", 50), null);
    }

    /**
     * 构造检索内部执行请求（缓存键要素齐备且取值确定）。
     *
     * @return 检索请求
     */
    private RetrievalQuery retrievalQuery() {
        return new RetrievalQuery(KB_ID, QUERY, List.of("营收"), List.of("公司", "年报"),
                20, 10, 4096, 2000, 3000);
    }

    /**
     * 构造指定多模态开关的检索请求（通路 B 开关矩阵形态，
     * 缓存键要素与 {@link #retrievalQuery()} 逐字段一致，仅开关分量不同）。
     *
     * @param options 多模态通路开关
     * @return 检索请求
     */
    private RetrievalQuery retrievalQuery(RetrievalMultimodalOptions options) {
        return new RetrievalQuery(KB_ID, QUERY, List.of("营收"), List.of("公司", "年报"),
                20, 10, 4096, 2000, 3000, List.of(), options, null, null);
    }

    /**
     * 构造含上下文的 Stage 4 产物。
     *
     * @return 上下文产物
     */
    private KgContext kgContext() {
        return new KgContext(CONTEXT_DATA, List.of("[1] 年报.pdf"),
                new KgSearchResult(graphEntities(), graphRelations(), List.of()));
    }
}
