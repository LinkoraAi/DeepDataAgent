package com.linkroa.deepdataagent.rag.domain.service;

import com.linkroa.deepdataagent.rag.domain.enums.CacheType;
import com.linkroa.deepdataagent.rag.domain.port.LlmChatRequest;
import com.linkroa.deepdataagent.rag.domain.port.LlmChatResult;
import com.linkroa.deepdataagent.rag.domain.port.LlmClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DescriptionSummarizer} 单元测试。
 * <p>重点覆盖摘要 LLM 调用点显式声明
 * {@link CacheType#ENTITY_DESC} 缓存分区（断言发往 {@link LlmClient} 的请求参数）；
 * 并覆盖缓存命中计数器口径与四级分级的空/单条边界分支。
 * {@link TokenCounter} / {@link LlmClient} Mock（Prompt 渲染经 {@code PromptCatalog}
 * 静态门面实测，语言注入口径以发往 {@link LlmClient} 的最终提示词内容断言），
 * ObjectMapper 用真实实例（仅做逐条 JSON 转义）。</p>
 *
 * @author DeepDataAgent
 */
@ExtendWith(MockitoExtension.class)
class DescriptionSummarizerTest {

    /** 测试知识库ID */
    private static final Long KB_ID = 8L;

    /** 测试文档ID */
    private static final Long DOC_ID = 99L;

    /** 摘要模型 profileId */
    private static final String SUMMARY_PROFILE_ID = "chat-summary-1";

    /** 向量化模型 profileId（本用例不触达，仅满足上下文必填校验） */
    private static final String EMBEDDING_PROFILE_ID = "embedding-1";

    /**
     * 强制走单次 LLM 摘要的参数：条数阈值=2（两条即触发 LLM）、单次上限 500（总 token 20 落在单次分支）。
     */
    private static final GraphMergeParams FORCE_LLM_PARAMS =
            new GraphMergeParams(4, 10, 8192, 12000, 500, 2, GraphMergeParams.DEFAULT_SOURCE_IDS_TRUNCATION,
                    GraphMergeParams.DEFAULT_SOURCE_FILE_PATHS_LIMIT,
                    GraphMergeParams.DEFAULT_SOURCE_FILE_PATHS_PLACEHOLDER);

    /** Token 计数器 Mock */
    @Mock
    private TokenCounter tokenCounter;

    /** LLM 对话端口 Mock */
    @Mock
    private LlmClient llmClient;

    /** 被测摘要器 */
    private DescriptionSummarizer summarizer;

    /**
     * 以 Mock 端口与真实 ObjectMapper 装配被测摘要器（Prompt 渲染走 {@code PromptCatalog} 静态门面实测）。
     */
    @BeforeEach
    void setUp() {
        summarizer = new DescriptionSummarizer(tokenCounter, llmClient, new ObjectMapper());
    }

    /**
     * 场景：两次不同描述的摘要触发 LLM。
     * 预期：发往 {@link LlmClient} 的请求显式携带 {@link CacheType#ENTITY_DESC} 分区，
     * 且 kbId/模型引用穿透上下文；摘要结果原样返回。
     */
    @Test
    void should_sendRequestWithEntityDescCacheType_when_summarize_given_llmSummaryBranch() {
        // given：两条描述、每条 10 token（总 20）落在单次 LLM 摘要分支
        GraphMergeContext ctx = context(FORCE_LLM_PARAMS, null);
        stubForSingleLlm();
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult("MERGED DESC", 5));

        // when
        String summary = summarizer.summarize(ctx, "Alice", "Person", List.of("desc one", "desc two"));

        // then：返回摘要文本；请求缓存分区归位 ENTITY_DESC
        assertEquals("MERGED DESC", summary);
        ArgumentCaptor<LlmChatRequest> captor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(llmClient, times(1)).chat(captor.capture());
        LlmChatRequest sent = captor.getValue();
        assertEquals(CacheType.ENTITY_DESC, sent.cacheType(), "描述摘要必须走 ENTITY_DESC 缓存分区");
        assertEquals(KB_ID, sent.kbId());
        assertEquals(SUMMARY_PROFILE_ID, sent.modelProfileId());
    }

    /**
     * 场景：上下文挂载缓存命中计数器，摘要回放命中缓存（{@code cacheHit=true}）。
     * 预期：每次回放使计数器递增一次（摄入收尾日志口径）。
     */
    @Test
    void should_incrementCacheHitCounter_when_summarize_given_cacheHitResult() {
        // given
        AtomicInteger cacheHits = new AtomicInteger(0);
        GraphMergeContext ctx = context(FORCE_LLM_PARAMS, cacheHits);
        stubForSingleLlm();
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult("CACHED DESC", 5, true));

        // when
        summarizer.summarize(ctx, "Bob", "Person", List.of("d1", "d2"));

        // then
        assertEquals(1, cacheHits.get(), "缓存命中回放应递增计数器一次");
    }

    /**
     * 场景：上下文挂载计数器，但摘要为回源结果（{@code cacheHit=false}）。
     * 预期：计数器不递增。
     */
    @Test
    void should_notIncrementCounter_when_summarize_given_cacheMissResult() {
        // given
        AtomicInteger cacheHits = new AtomicInteger(0);
        GraphMergeContext ctx = context(FORCE_LLM_PARAMS, cacheHits);
        stubForSingleLlm();
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult("FRESH DESC", 5, false));

        // when
        summarizer.summarize(ctx, "Carol", "Person", List.of("d1", "d2"));

        // then
        assertEquals(0, cacheHits.get(), "未命中回放不应递增计数器");
    }

    /**
     * 场景：空描述列表。
     * 预期：直接返回 {@code null}（调用方兜底），不触达 LLM。
     */
    @Test
    void should_returnNull_when_summarize_given_emptyDescriptions() {
        // given
        GraphMergeContext ctx = context(FORCE_LLM_PARAMS, null);

        // when
        String summary = summarizer.summarize(ctx, "Alice", "Person", List.of());

        // then
        assertNull(summary);
        verify(llmClient, never()).chat(any(LlmChatRequest.class));
    }

    /**
     * 场景：单条描述。
     * 预期：原样返回该条，不产生任何远程调用（无写放大）。
     */
    @Test
    void should_returnSingleDescriptionUnchanged_when_summarize_given_singleDescription() {
        // given
        GraphMergeContext ctx = context(FORCE_LLM_PARAMS, null);

        // when
        String summary = summarizer.summarize(ctx, "Alice", "Person", List.of("only one desc"));

        // then
        assertEquals("only one desc", summary);
        verify(llmClient, never()).chat(any(LlmChatRequest.class));
    }

    /**
     * 场景：——语言全名 {@code Chinese} 的图合并上下文触发单次 LLM 摘要。
     * 预期：{@link LlmClient} 收到的最终提示词中 {language} 实测为全名 {@code Chinese}
     * （旧「中文」标签映射已删除）。
     */
    @Test
    void should_injectLanguageFullName_when_summarize_given_chineseContextLanguage() {
        // given
        GraphMergeContext ctx = new GraphMergeContext(KB_ID, DOC_ID, SUMMARY_PROFILE_ID,
                EMBEDDING_PROFILE_ID, "Chinese", FORCE_LLM_PARAMS, "tester", null, null);
        stubForSingleLlm();
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult("MERGED DESC", 5));

        // when
        summarizer.summarize(ctx, "张三", "Person", List.of("描述一", "描述二"));

        // then
        String prompt = capturedSummaryPrompt();
        assertTrue(prompt.contains("The entire output must be written in Chinese."),
                "{language} 必须注入知识库语言全名 Chinese");
        assertFalse(prompt.contains("written in 中文"), "旧「中文」标签映射已删除");
    }

    /**
     * 场景：默认阈值下 3 条、总 300 token 的小规模描述（spec「小规模描述免摘要」）。
     * 预期：条数 < 8 且总 token < 1200 且 ≤ 拼接上限——直接换行拼接，零 LLM 调用。
     */
    @Test
    void should_joinWithoutLlm_when_summarize_given_threeDescriptionsUnderBothThresholds() {
        // given：默认参数（force=8、maxTokens=1200、context=12000），每条 100 token
        GraphMergeContext ctx = context(GraphMergeParams.defaults(), null);
        when(tokenCounter.count(anyString())).thenReturn(100);

        // when
        String summary = summarizer.summarize(ctx, "Alice", "Person", List.of("d1", "d2", "d3"));

        // then：换行直拼、零远程调用
        assertEquals("d1\nd2\nd3", summary);
        verify(llmClient, never()).chat(any(LlmChatRequest.class));
    }

    /**
     * 场景：默认阈值下 8 条小 token 描述（spec「片段数达阈值即强制摘要」）。
     * 预期：条数达 force=8 即触发单次全量 LLM 摘要，不走直接拼接。
     */
    @Test
    void should_triggerLlm_when_summarize_given_descriptionsReachForceThreshold() {
        // given：8 条 × 10 token（总 80 < 1200，但条数不满足 < 8）
        GraphMergeContext ctx = context(GraphMergeParams.defaults(), null);
        when(tokenCounter.count(anyString())).thenReturn(10);
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult("FORCED SUMMARY", 5));

        // when
        String summary = summarizer.summarize(ctx, "Alice", "Person",
                List.of("a", "b", "c", "d", "e", "f", "g", "h"));

        // then：单次全量 LLM 摘要（恰一次调用、非分批）
        assertEquals("FORCED SUMMARY", summary);
        verify(llmClient, times(1)).chat(any(LlmChatRequest.class));
    }

    /**
     * 场景：3 条、总 1500 token（超输出上限 1200 但未超拼接上限 12000，
     * spec「总 token 达阈值触发摘要」）。
     * 预期：以单次全量 LLM 摘要完成，MUST NOT 分批（恰一次调用）。
     */
    @Test
    void should_singleFullLlm_when_summarize_given_totalTokensOverOutputLimitButUnderContextLimit() {
        // given：每条 500 token，总 1500
        GraphMergeContext ctx = context(GraphMergeParams.defaults(), null);
        when(tokenCounter.count(anyString())).thenReturn(500);
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult("SINGLE FULL", 5));

        // when
        String summary = summarizer.summarize(ctx, "Alice", "Person", List.of("d1", "d2", "d3"));

        // then：单次全量摘要，不进入 Map-Reduce 分级
        assertEquals("SINGLE FULL", summary);
        verify(llmClient, times(1)).chat(any(LlmChatRequest.class));
    }

    /**
     * 场景：130 条 × 100 token 共 13000 token，超拼接上限 12000（spec「超拼接上限走分级摘要」）。
     * 预期：进入 Map-Reduce——逐组多次摘要后递归归并（调用次数显著多于单次全量形态）。
     */
    @Test
    void should_enterMapReduce_when_summarize_given_totalTokensOverContextLimit() {
        // given：130 条描述（组内总额 ≤1200 → 约 11 组，归并轮再摘要一次）
        GraphMergeContext ctx = context(GraphMergeParams.defaults(), null);
        when(tokenCounter.count(anyString())).thenReturn(100);
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult("PARTIAL", 5));
        List<String> descriptions = new java.util.ArrayList<>();
        for (int i = 0; i < 130; i++) {
            descriptions.add("desc-" + i);
        }

        // when
        summarizer.summarize(ctx, "Alice", "Person", descriptions);

        // then：分级摘要产生多轮 LLM 调用（单次全量分支不可能出现 >1 次调用）
        verify(llmClient, org.mockito.Mockito.atLeast(2)).chat(any(LlmChatRequest.class));
    }

    /**
     * 场景：语言全名 {@code Japanese} 的图合并上下文触发单次 LLM 摘要。
     * 预期：最终提示词中 {language} 实测为 {@code Japanese}（非旧口径的 English 标签回落）。
     */
    @Test
    void should_injectJapaneseFullName_when_summarize_given_japaneseContextLanguage() {
        // given
        GraphMergeContext ctx = new GraphMergeContext(KB_ID, DOC_ID, SUMMARY_PROFILE_ID,
                EMBEDDING_PROFILE_ID, "Japanese", FORCE_LLM_PARAMS, "tester", null, null);
        stubForSingleLlm();
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult("MERGED DESC", 5));

        // when
        summarizer.summarize(ctx, "Alice", "Person", List.of("desc one", "desc two"));

        // then
        String prompt = capturedSummaryPrompt();
        assertTrue(prompt.contains("The entire output must be written in Japanese."),
                "{language} 应注入 Japanese，不回退历史 English 标签");
    }

    // ------------------------------------------------------------------ 测试辅助

    /**
     * 构造图合并上下文。
     *
     * @param params     图合并参数
     * @param cacheHits  缓存命中计数器（可空）
     * @return 上下文实例
     */
    private GraphMergeContext context(GraphMergeParams params, AtomicInteger cacheHits) {
        return new GraphMergeContext(KB_ID, DOC_ID, SUMMARY_PROFILE_ID, EMBEDDING_PROFILE_ID,
                "en", params, "tester", null, cacheHits);
    }

    /**
     * 桩化触发单次 LLM 摘要所需的计数端口（渲染经静态门面实测，无需打桩）。
     */
    private void stubForSingleLlm() {
        when(tokenCounter.count(anyString())).thenReturn(10);
    }

    /**
     * 捕获发往 {@link LlmClient} 的摘要请求提示词（摘要以单条用户提示词形态发出）。
     *
     * @return 摘要请求的 userPrompt
     */
    private String capturedSummaryPrompt() {
        ArgumentCaptor<LlmChatRequest> captor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(llmClient).chat(captor.capture());
        return captor.getValue().userPrompt();
    }
}
