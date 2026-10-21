package com.linkroa.deepdataagent.rag.domain.service;

import com.linkroa.deepdataagent.knowledgebase.api.KnowledgeBaseApi;
import com.linkroa.deepdataagent.knowledgebase.domain.model.RetrievalStrategyConfig;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.RetrievalStrategyType;
import com.linkroa.deepdataagent.rag.application.contract.RetrievalQuery;
import com.linkroa.deepdataagent.rag.domain.enums.CacheType;
import com.linkroa.deepdataagent.rag.domain.model.KgContext;
import com.linkroa.deepdataagent.rag.domain.model.KgSearchResult;
import com.linkroa.deepdataagent.rag.domain.model.LlmCacheEntry;
import com.linkroa.deepdataagent.rag.domain.port.LlmChatRequest;
import com.linkroa.deepdataagent.rag.domain.port.LlmStreamClient;
import com.linkroa.deepdataagent.rag.domain.port.LlmStreamDelta;
import com.linkroa.deepdataagent.rag.domain.repository.LlmCacheRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DefaultAnswerGenerator} 流式入口单元测试。
 *
 * <p>专注 {@code generateStream} 的增量语义：缓存命中单次全文、未命中按序累积并在正常完成时
 * 回写缓存、流内异常的两条降级路径（未产出→兜底文案 / 已产出→保留内容）、
 * 回写失败不影响输出，以及订阅取消不回写缓存。同步形态的完整语义由
 * {@link DefaultAnswerGeneratorTest} 覆盖。</p>
 *
 * @author DeepDataAgent
 */
@ExtendWith(MockitoExtension.class)
class DefaultAnswerGeneratorStreamTest {

    /** 测试知识库主键 */
    private static final Long KB_ID = 1L;

    /** 测试用户问题 */
    private static final String QUERY = "公司营收是多少？";

    /** 测试 CHAT 模型 profileId */
    private static final String CHAT_PROFILE_ID = "model-chat-001";

    /** 上下文构建产物正文 */
    private static final String CONTEXT_DATA = "CTX";

    /** 流式 LLM 客户端 Mock */
    @Mock
    private LlmStreamClient llmStreamClient;

    /** LLM 缓存仓储 Mock */
    @Mock
    private LlmCacheRepository llmCacheRepository;

    /** 知识库跨 BC 只读契约 Mock */
    @Mock
    private KnowledgeBaseApi knowledgeBaseApi;

    /** 作答侧原图直读解析器 Mock（纯文本场景不触发） */
    @Mock
    private AnswerImageResolver answerImageResolver;

    /** 被测答案生成器 */
    private DefaultAnswerGenerator answerGenerator;

    /**
     * 每个用例重建被测对象，并打桩「知识库已配置 CHAT 模型 profileId」这一夹具前置条件。
     */
    @BeforeEach
    void setUp() {
        answerGenerator = new DefaultAnswerGenerator(llmStreamClient, llmCacheRepository,
                knowledgeBaseApi, answerImageResolver, new ObjectMapper());
        when(knowledgeBaseApi.findMediaModelProfileIdByKbId(KB_ID)).thenReturn(CHAT_PROFILE_ID);
    }

    /**
     * 场景：答案缓存命中。
     * 预期：不发起模型调用，一次性产出缓存全文，命中计数器递增一次。
     */
    @Test
    void should_emitCachedAnswerOnce_when_generateStream_given_cacheHit() {
        // given
        LlmCacheEntry entry = LlmCacheEntry.create(KB_ID, "0123456789abcdef0123456789abcdef", CacheType.ANSWER,
                CHAT_PROFILE_ID, "prompt", "缓存答案", 12);
        when(llmCacheRepository.findByKbIdAndCacheKey(anyLong(), any(), anyString()))
                .thenReturn(Optional.of(entry));
        AtomicInteger cacheHits = new AtomicInteger();

        // when
        List<String> parts = answerGenerator.generateStream(QUERY, kgContext(), strategyConfig(), retrievalQuery(),
                cacheHits).collectList().block();

        // then
        assertNotNull(parts);
        assertEquals(List.of("缓存答案"), parts);
        assertEquals(1, cacheHits.get());
        verify(llmStreamClient, never()).stream(any(LlmChatRequest.class));
    }

    /**
     * 场景：缓存未命中且模型正常产出多个增量。
     * 预期：增量按到达顺序逐段下发，且正常完成后以累积全文回写缓存。
     */
    @Test
    void should_emitDeltasInOrderAndSaveCache_when_generateStream_given_multiDeltaSuccess() {
        // given
        when(llmCacheRepository.findByKbIdAndCacheKey(anyLong(), any(), anyString()))
                .thenReturn(Optional.empty());
        when(llmStreamClient.stream(any(LlmChatRequest.class)))
                .thenReturn(Flux.just(new LlmStreamDelta("AB", null),
                        new LlmStreamDelta("CD", 9)));

        // when
        List<String> parts = answerGenerator.generateStream(QUERY, kgContext(), strategyConfig(), retrievalQuery(),
                null).collectList().block();

        // then
        assertNotNull(parts);
        assertEquals(List.of("AB", "CD"), parts);
        assertTrue(parts.stream().mapToInt(String::length).sum() == 4);
        verify(llmCacheRepository).saveIfAbsent(any(LlmCacheEntry.class));
    }

    /**
     * 场景：模型流在尚未产出任何增量时失败。
     * 预期：产出一次兜底文案，且不回写缓存。
     */
    @Test
    void should_emitFallbackText_when_generateStream_given_errorBeforeAnyDelta() {
        // given
        when(llmCacheRepository.findByKbIdAndCacheKey(anyLong(), any(), anyString()))
                .thenReturn(Optional.empty());
        when(llmStreamClient.stream(any(LlmChatRequest.class)))
                .thenReturn(Flux.error(new IllegalStateException("上游不可用")));

        // when
        List<String> parts = answerGenerator.generateStream(QUERY, kgContext(), strategyConfig(), retrievalQuery(),
                null).collectList().block();

        // then
        assertNotNull(parts);
        assertEquals(1, parts.size());
        assertFalse(parts.get(0).isBlank(), "失败时必须给出可读兜底文案");
        verify(llmCacheRepository, never()).saveIfAbsent(any(LlmCacheEntry.class));
    }

    /**
     * 场景：模型流已产出部分增量后失败。
     * 预期：保留已产出内容并正常结束，不追加兜底文案，也不回写缓存。
     */
    @Test
    void should_keepEmittedPart_when_generateStream_given_errorAfterDelta() {
        // given
        when(llmCacheRepository.findByKbIdAndCacheKey(anyLong(), any(), anyString()))
                .thenReturn(Optional.empty());
        when(llmStreamClient.stream(any(LlmChatRequest.class)))
                .thenReturn(Flux.concat(Flux.just(new LlmStreamDelta("已产出")),
                        Flux.error(new IllegalStateException("连接中断"))));

        // when
        List<String> parts = answerGenerator.generateStream(QUERY, kgContext(), strategyConfig(), retrievalQuery(),
                null).collectList().block();

        // then
        assertNotNull(parts);
        assertEquals(List.of("已产出"), parts);
        verify(llmCacheRepository, never()).saveIfAbsent(any(LlmCacheEntry.class));
    }

    /**
     * 场景：流正常完成但缓存回写抛出异常。
     * 预期：已下发内容不受影响，答案照常返回。
     */
    @Test
    void should_keepAnswer_when_generateStream_given_cacheSaveFailure() {
        // given
        when(llmCacheRepository.findByKbIdAndCacheKey(anyLong(), any(), anyString()))
                .thenReturn(Optional.empty());
        when(llmStreamClient.stream(any(LlmChatRequest.class)))
                .thenReturn(Flux.just(new LlmStreamDelta("完整答案", 5)));
        org.mockito.Mockito.doThrow(new RuntimeException("仓储故障"))
                .when(llmCacheRepository).saveIfAbsent(any(LlmCacheEntry.class));

        // when
        List<String> parts = answerGenerator.generateStream(QUERY, kgContext(), strategyConfig(), retrievalQuery(),
                null).collectList().block();

        // then
        assertNotNull(parts);
        assertEquals("完整答案", String.join("", parts));
    }

    /**
     * 场景：订阅在流完成前被取消（客户端断开）。
     * 预期：不回写答案缓存。
     */
    @Test
    void should_notSaveCache_when_generateStream_given_subscriptionCancelled() {
        // given
        when(llmCacheRepository.findByKbIdAndCacheKey(anyLong(), any(), anyString()))
                .thenReturn(Optional.empty());
        when(llmStreamClient.stream(any(LlmChatRequest.class)))
                .thenReturn(Flux.concat(Flux.just(new LlmStreamDelta("部分")), Mono.<LlmStreamDelta>never().flux()));
        List<String> received = new ArrayList<>();

        // when
        var disposable = answerGenerator.generateStream(QUERY, kgContext(), strategyConfig(), retrievalQuery(), null)
                .subscribe(received::add);
        disposable.dispose();

        // then
        assertEquals(List.of("部分"), received);
        verify(llmCacheRepository, never()).saveIfAbsent(any(LlmCacheEntry.class));
    }

    /**
     * 场景：模型输出了正文引用标记与 References 段。
     * 预期：产出原样透传——增量不删标记、不截断引用段，拼接结果与模型输出逐字一致。
     */
    @Test
    void should_passthroughCitationsAndReferencesSection_when_generateStream_given_modelOutputWithReferences() {
        // given
        when(llmCacheRepository.findByKbIdAndCacheKey(anyLong(), any(), anyString()))
                .thenReturn(Optional.empty());
        when(llmStreamClient.stream(any(LlmChatRequest.class)))
                .thenReturn(Flux.just(new LlmStreamDelta("答案 [1]", null),
                        new LlmStreamDelta("。\n### References\n* [1] LightRag1.pdf", 5)));

        // when
        List<String> parts = answerGenerator.generateStream(QUERY, kgContext(), strategyConfig(), retrievalQuery(),
                null).collectList().block();

        // then
        assertNotNull(parts);
        String answer = String.join("", parts);
        assertEquals("答案 [1]。\n### References\n* [1] LightRag1.pdf", answer,
                "模型产出（含正文引用标记与 References 段）必须原样透传");
        assertTrue(answer.contains("[1]") && answer.contains("References"),
                "引用标记与 References 段不得被删除");
        ArgumentCaptor<LlmCacheEntry> entryCaptor = ArgumentCaptor.forClass(LlmCacheEntry.class);
        verify(llmCacheRepository).saveIfAbsent(entryCaptor.capture());
        assertEquals(answer, entryCaptor.getValue().response(), "回写内容必须与下发内容逐字一致");
    }

    /**
     * 场景：命中答案缓存，缓存内容含引用标记与 References 段。
     * 预期：回放内容与缓存原文逐字一致（不再二次清洗）。
     */
    @Test
    void should_replayCachedAnswerVerbatim_when_generateStream_given_cacheHitWithCitations() {
        // given：缓存内容为含引用段的模型原始答案
        LlmCacheEntry entry = LlmCacheEntry.create(KB_ID, "0123456789abcdef0123456789abcdef", CacheType.ANSWER,
                CHAT_PROFILE_ID, "prompt", "正文 [1]。\n### References\n* [1] a.pdf", 12);
        when(llmCacheRepository.findByKbIdAndCacheKey(anyLong(), any(), anyString()))
                .thenReturn(Optional.of(entry));

        // when
        List<String> parts = answerGenerator.generateStream(QUERY, kgContext(), strategyConfig(), retrievalQuery(),
                null).collectList().block();

        // then
        assertNotNull(parts);
        String answer = String.join("", parts);
        assertEquals("正文 [1]。\n### References\n* [1] a.pdf", answer, "缓存回放必须与缓存原文逐字一致");
    }

    /**
     * 构造含可用上下文的 Stage 4 产物。
     *
     * @return 上下文产物
     */
    private static KgContext kgContext() {
        return new KgContext(CONTEXT_DATA, List.of("[1] 年报.pdf"),
                new KgSearchResult(List.of(), List.of(), List.of()));
    }

    /**
     * 构造 MIX 策略配置（结果返回数量非 0，避免命中 fail_response 短路口）。
     *
     * @return 策略配置
     */
    private static RetrievalStrategyConfig strategyConfig() {
        return new RetrievalStrategyConfig(RetrievalStrategyType.MIX, Boolean.TRUE, 5, 0.2F, null, null);
    }

    /**
     * 构造检索内部执行请求。
     *
     * @return 检索请求
     */
    private static RetrievalQuery retrievalQuery() {
        return new RetrievalQuery(KB_ID, QUERY, List.of("营收"), List.of("公司", "年报"),
                20, 10, 4096, 2000, 3000);
    }
}