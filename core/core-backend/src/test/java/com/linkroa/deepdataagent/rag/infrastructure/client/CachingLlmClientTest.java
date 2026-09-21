package com.linkroa.deepdataagent.rag.infrastructure.client;

import com.linkroa.deepdataagent.rag.domain.enums.CacheType;
import com.linkroa.deepdataagent.rag.domain.model.LlmCacheEntry;
import com.linkroa.deepdataagent.rag.domain.port.LlmChatRequest;
import com.linkroa.deepdataagent.rag.domain.port.LlmChatResult;
import com.linkroa.deepdataagent.rag.domain.port.LlmClient;
import com.linkroa.deepdataagent.rag.domain.port.LlmImage;
import com.linkroa.deepdataagent.rag.domain.repository.ChunkExtractCacheRepository;
import com.linkroa.deepdataagent.rag.domain.repository.LlmCacheRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link CachingLlmClient} 单元测试。
 * <p>覆盖读、写缓存三元组 {@code (kbId, cacheType, cacheKey)}
 * 由请求显式声明的 {@link CacheType} 决定分区，命中回放标记 {@code cacheHit=true}、未命中回写按声明分类落库、
 * 不同分区互不串扰。委托 {@link LlmClient}、{@link LlmCacheRepository}、{@link ModelProfileAccess} 全部 Mock。</p>
 * <p>另覆盖的缓存键图片维度：无图请求的键与引入图片维度前<b>逐字节一致</b>
 * （既有缓存条目仍可命中）、同文本不同图必产生不同键、同文本同图产生同键、多图按列表顺序追加摘要。</p>
 * <p>以及抽取缓存归属登记维度（spec extract-cache-attribution / R1、R3）：
 * 未命中回写后登记、<b>命中回放后同样登记</b>（核心场景）、无归因字段的调用不登记、
 * 登记抛异常不影响 LLM 结果返回且只累加失败计数、归因字段不进入缓存键（键逐字节不漂移）。</p>
 * <p>键算法已迁至 {@link DefaultLlmCacheKeyProvider} 并由本类委托计算：
 * 被测对象装配<b>真实的</b>键计算器（仅模型解析端口为 Mock），
 * 使本套件的手工复算基线仍是端到端断言——键计算口径若有漂移立即红灯。</p>
 *
 * @author DeepDataAgent
 */
@ExtendWith(MockitoExtension.class)
class CachingLlmClientTest {

    /** 测试知识库ID */
    private static final Long KB_ID = 1001L;

    /** 测试模型 profileId */
    private static final String PROFILE_ID = "chat-profile-1";

    /** 解析后的模型名称（参与缓存键与回写 model 列） */
    private static final String MODEL_NAME = "gpt-4o-mini";

    /** 合法缓存键：32 位 MD5 hex（构造命中条目用） */
    private static final String CACHE_KEY = "0123456789abcdef0123456789abcdef";

    /** 测试分块标识（归因登记用） */
    private static final Long CHUNK_ID = 501L;

    /** 缓存键要素连接符（与被测实现同字面量，用于测试侧手工复算基线键） */
    private static final String KEY_PART_SEPARATOR = "\n\u0001";

    /** 图片摘要要素前缀（与被测实现同字面量） */
    private static final String IMAGE_DIGEST_PREFIX = "sha256:";

    /** 被缓存包装的真实 LLM 客户端 Mock */
    @Mock
    private LlmClient delegate;

    /** LLM 缓存仓储 Mock */
    @Mock
    private LlmCacheRepository llmCacheRepository;

    /** 模型配置解析器 Mock */
    @Mock
    private ModelProfileAccess modelProfileAccess;

    /** 抽取缓存归属仓储 Mock（归属登记断言用） */
    @Mock
    private ChunkExtractCacheRepository chunkExtractCacheRepository;

    /** 被测客户端 */
    private CachingLlmClient cachingLlmClient;

    /**
     * 以显式构造器装配被测客户端（委托、缓存仓储、模型解析端口、归属仓储均为 Mock；
     * 键计算器为被测实现委托的真实实例，键算法单一来源由本装配保证）。
     */
    @BeforeEach
    void setUp() {
        cachingLlmClient = new CachingLlmClient(delegate, llmCacheRepository, modelProfileAccess,
                chunkExtractCacheRepository, new DefaultLlmCacheKeyProvider(modelProfileAccess));
    }

    /**
     * 场景：三元组命中缓存。
     * 预期：直返缓存内容且 {@code cacheHit=true}、token 取缓存值，绝不调用委托客户端。
     */
    @Test
    void should_returnCachedResultWithCacheHitFlag_when_chat_given_cacheHit() {
        // given：解析模型端口，缓存查询命中一条 ANSWER 分区条目
        LlmChatRequest request = new LlmChatRequest(KB_ID, PROFILE_ID, "SYS", "USER PROMPT", null, CacheType.ANSWER);
        stubModelResolve();
        LlmCacheEntry cached = LlmCacheEntry.restore(9L, KB_ID, CACHE_KEY, CacheType.ANSWER, MODEL_NAME,
                "p", "CACHED ANSWER", 55, null, null);
        when(llmCacheRepository.findByKbIdAndCacheKey(eq(KB_ID), eq(CacheType.ANSWER), anyString()))
                .thenReturn(Optional.of(cached));

        // when
        LlmChatResult result = cachingLlmClient.chat(request);

        // then：回放内容、命中标志为真、token 来自缓存；委托客户端与回写均未触达
        assertEquals("CACHED ANSWER", result.text());
        assertEquals(55, result.totalTokens());
        assertTrue(result.cacheHit(), "缓存命中回放应标记 cacheHit=true");
        verify(llmCacheRepository).findByKbIdAndCacheKey(eq(KB_ID), eq(CacheType.ANSWER), anyString());
        verify(delegate, never()).chat(any(LlmChatRequest.class));
        verify(llmCacheRepository, never()).saveIfAbsent(any(LlmCacheEntry.class));
    }

    /**
     * 场景：未命中缓存且请求显式声明 {@link CacheType#ENTITY_DESC} 分区。
     * 预期：调用委托客户端，成功后按声明的 ENTITY_DESC 分类回写（回写条目 cacheType 与请求一致），
     * 返回委托结果且 {@code cacheHit=false}。
     */
    @Test
    void should_writeCacheWithDeclaredType_when_chat_given_cacheMiss() {
        // given：查询未命中，委托返回真实结果
        LlmChatRequest request = new LlmChatRequest(KB_ID, PROFILE_ID, null, "DESC PROMPT", 0.3, CacheType.ENTITY_DESC);
        stubModelResolve();
        when(llmCacheRepository.findByKbIdAndCacheKey(eq(KB_ID), eq(CacheType.ENTITY_DESC), anyString()))
                .thenReturn(Optional.empty());
        when(delegate.chat(request)).thenReturn(new LlmChatResult("REAL SUMMARY", 12));

        // when
        LlmChatResult result = cachingLlmClient.chat(request);

        // then：结果为委托值且非命中；回写条目分区归位到请求声明的 ENTITY_DESC
        assertEquals("REAL SUMMARY", result.text());
        assertFalse(result.cacheHit(), "未命中回源结果 cacheHit 应为 false");
        ArgumentCaptor<LlmCacheEntry> entryCaptor = ArgumentCaptor.forClass(LlmCacheEntry.class);
        verify(llmCacheRepository, times(1)).saveIfAbsent(entryCaptor.capture());
        LlmCacheEntry saved = entryCaptor.getValue();
        assertEquals(CacheType.ENTITY_DESC, saved.cacheType(), "回写必须落在请求显式声明的缓存分区");
        assertEquals(KB_ID, saved.kbId());
        assertEquals(MODEL_NAME, saved.model());
        assertEquals("REAL SUMMARY", saved.response());
    }

    /**
     * 场景：请求未显式声明缓存分区（便捷构造器缺省 ANSWER）。
     * 预期：读、写两侧均归入 {@link CacheType#ANSWER} 分区。
     */
    @Test
    void should_defaultToAnswerPartition_when_chat_given_requestWithoutCacheType() {
        // given：五参便捷构造器（不声明 cacheType）
        LlmChatRequest request = new LlmChatRequest(KB_ID, PROFILE_ID, null, "GENERAL PROMPT", null);
        stubModelResolve();
        when(llmCacheRepository.findByKbIdAndCacheKey(anyLong(), any(CacheType.class), anyString()))
                .thenReturn(Optional.empty());
        when(delegate.chat(request)).thenReturn(new LlmChatResult("GENERAL ANSWER", 7));

        // when
        cachingLlmClient.chat(request);

        // then：读用 ANSWER 分区，回写条目分类亦为 ANSWER
        verify(llmCacheRepository).findByKbIdAndCacheKey(eq(KB_ID), eq(CacheType.ANSWER), anyString());
        ArgumentCaptor<LlmCacheEntry> entryCaptor = ArgumentCaptor.forClass(LlmCacheEntry.class);
        verify(llmCacheRepository).saveIfAbsent(entryCaptor.capture());
        assertEquals(CacheType.ANSWER, entryCaptor.getValue().cacheType());
    }

    /**
     * 场景：同一批调用分别声明 ENTITY_DESC 与 ANSWER 分区。
     * 预期：缓存读检索按各自声明的 {@link CacheType} 查询，读写分区互不串扰。
     */
    @Test
    void should_queryEachPartitionIndependently_when_chat_given_mixedCacheTypes() {
        // given：两次调用均 miss，委托分别返回结果
        LlmChatRequest descRequest = new LlmChatRequest(KB_ID, PROFILE_ID, null, "SAME PROMPT", null, CacheType.ENTITY_DESC);
        LlmChatRequest answerRequest = new LlmChatRequest(KB_ID, PROFILE_ID, null, "SAME PROMPT", null, CacheType.ANSWER);
        stubModelResolve();
        when(llmCacheRepository.findByKbIdAndCacheKey(anyLong(), any(CacheType.class), anyString()))
                .thenReturn(Optional.empty());
        when(delegate.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult("OUT", 1));

        // when
        cachingLlmClient.chat(descRequest);
        cachingLlmClient.chat(answerRequest);

        // then：读检索分别命中 ENTITY_DESC 与 ANSWER 分区；回写条目分类各归各位
        verify(llmCacheRepository).findByKbIdAndCacheKey(eq(KB_ID), eq(CacheType.ENTITY_DESC), anyString());
        verify(llmCacheRepository).findByKbIdAndCacheKey(eq(KB_ID), eq(CacheType.ANSWER), anyString());
        ArgumentCaptor<LlmCacheEntry> entryCaptor = ArgumentCaptor.forClass(LlmCacheEntry.class);
        verify(llmCacheRepository, times(2)).saveIfAbsent(entryCaptor.capture());
        assertEquals(CacheType.ENTITY_DESC, entryCaptor.getAllValues().get(0).cacheType(), "首次调用回写 ENTITY_DESC");
        assertEquals(CacheType.ANSWER, entryCaptor.getAllValues().get(1).cacheType(), "次次调用回写 ANSWER");
    }

    /**
     * 场景：抽取调用与查询答案恰好产生相同提示词要素（同缓存键），EXTRACT 与 ANSWER
     * 两分区各存在一行（spec llm-cache-partitioning / 同键不同分区不互读）。
     * 预期：各调用方只回放自己声明分区的行，互不串回放，均不触达委托客户端。
     */
    @Test
    void should_replayOwnPartitionOnly_when_chat_given_sameKeyInExtractAndAnswerPartitions() {
        // given：同文提示词的两个请求分别声明 EXTRACT 与 ANSWER 分区，两分区各命中一行
        LlmChatRequest extractRequest =
                new LlmChatRequest(KB_ID, PROFILE_ID, "SYS", "SAME PROMPT", null, CacheType.EXTRACT);
        LlmChatRequest answerRequest =
                new LlmChatRequest(KB_ID, PROFILE_ID, "SYS", "SAME PROMPT", null, CacheType.ANSWER);
        stubModelResolve();
        when(llmCacheRepository.findByKbIdAndCacheKey(eq(KB_ID), eq(CacheType.EXTRACT), anyString()))
                .thenReturn(Optional.of(LlmCacheEntry.restore(11L, KB_ID, CACHE_KEY, CacheType.EXTRACT,
                        MODEL_NAME, "p", "EXTRACT ROW", 21, null, null)));
        when(llmCacheRepository.findByKbIdAndCacheKey(eq(KB_ID), eq(CacheType.ANSWER), anyString()))
                .thenReturn(Optional.of(LlmCacheEntry.restore(12L, KB_ID, CACHE_KEY, CacheType.ANSWER,
                        MODEL_NAME, "p", "ANSWER ROW", 22, null, null)));

        // when
        LlmChatResult extractResult = cachingLlmClient.chat(extractRequest);
        LlmChatResult answerResult = cachingLlmClient.chat(answerRequest);

        // then：EXTRACT 调用只回放 EXTRACT 分区行、答案只回放 ANSWER 分区行，均不回源
        assertEquals("EXTRACT ROW", extractResult.text(), "抽取调用必须只回放抽取分区行");
        assertTrue(extractResult.cacheHit());
        assertEquals("ANSWER ROW", answerResult.text(), "答案生成必须只回放答案分区行");
        assertTrue(answerResult.cacheHit());
        verify(delegate, never()).chat(any(LlmChatRequest.class));
    }

    /**
     * 场景：缓存读仓储抛异常（依赖失败）。
     * 预期：当前实现未做降级兜底，异常原样上抛、委托客户端不会被触达（现状见类注释）。
     */
    @Test
    void should_propagateRepositoryFailure_when_chat_given_cacheReadThrows() {
        // given
        LlmChatRequest request = new LlmChatRequest(KB_ID, PROFILE_ID, null, "PROMPT", null, CacheType.ANSWER);
        stubModelResolve();
        when(llmCacheRepository.findByKbIdAndCacheKey(anyLong(), any(CacheType.class), anyString()))
                .thenThrow(new RuntimeException("缓存读失败"));

        // when & then
        assertThrows(RuntimeException.class, () -> cachingLlmClient.chat(request));
        verify(delegate, never()).chat(any(LlmChatRequest.class));
    }

    /**
     * 场景：委托回源成功但缓存回写仓储抛异常（依赖失败）。
     * 预期：当前实现未做降级兜底，异常原样上抛；委托客户端已被调用一次。
     */
    @Test
    void should_propagateRepositoryFailure_when_chat_given_cacheWriteThrows() {
        // given
        LlmChatRequest request = new LlmChatRequest(KB_ID, PROFILE_ID, null, "PROMPT", null, CacheType.ENTITY_DESC);
        stubModelResolve();
        when(llmCacheRepository.findByKbIdAndCacheKey(anyLong(), any(CacheType.class), anyString()))
                .thenReturn(Optional.empty());
        when(delegate.chat(request)).thenReturn(new LlmChatResult("OUT", 3));
        doThrow(new RuntimeException("缓存写失败")).when(llmCacheRepository).saveIfAbsent(any(LlmCacheEntry.class));

        // when & then
        assertThrows(RuntimeException.class, () -> cachingLlmClient.chat(request));
        verify(delegate, times(1)).chat(request);
    }

    /**
     * 场景：入参请求为 null。
     * 预期：快速失败抛 {@link IllegalArgumentException}，不触达任何协作端口。
     */
    @Test
    void should_throwIllegalArgument_when_chat_given_nullRequest() {
        // given / when / then
        assertThrows(IllegalArgumentException.class, () -> cachingLlmClient.chat(null));
        verifyNoInteractions(modelProfileAccess, llmCacheRepository, delegate);
    }

    /**
     * 场景：无图片的纯文本请求（引入多模态组件前的既有形态）。
     * 预期：缓存键与旧要素串 {@code md5(model+system+user+temperature)} 手工复算值逐字节一致，
     * 既有 {@code llm_cache} 条目仍能命中回放（键不漂移）。
     */
    @Test
    void should_keepLegacyCacheKey_when_chat_given_requestWithoutImages() {
        // given：手工复算引入图片维度前的基线键
        String baselineKey = expectedCacheKey(MODEL_NAME, "SYS", "BASELINE PROMPT", "default");
        LlmChatRequest request =
                new LlmChatRequest(KB_ID, PROFILE_ID, "SYS", "BASELINE PROMPT", null, CacheType.ANSWER);
        stubModelResolve();
        when(llmCacheRepository.findByKbIdAndCacheKey(eq(KB_ID), eq(CacheType.ANSWER), eq(baselineKey)))
                .thenReturn(Optional.empty());
        when(delegate.chat(request)).thenReturn(new LlmChatResult("OUT", 1));

        // when
        cachingLlmClient.chat(request);

        // then：读检索与回写条目都落在基线键上
        verify(llmCacheRepository).findByKbIdAndCacheKey(eq(KB_ID), eq(CacheType.ANSWER), eq(baselineKey));
        ArgumentCaptor<LlmCacheEntry> entryCaptor = ArgumentCaptor.forClass(LlmCacheEntry.class);
        verify(llmCacheRepository).saveIfAbsent(entryCaptor.capture());
        assertEquals(baselineKey, entryCaptor.getValue().cacheKey(), "无图请求的缓存键不得因图片维度引入而漂移");
    }

    /**
     * 场景：提示词完全相同、仅图片字节不同（缓存互串风险点）。
     * 预期：两张图分别产生不同的缓存键，回写条目键值各自等于「要素串 + 对应图片摘要」的手工复算值。
     */
    @Test
    void should_produceDifferentCacheKeys_when_chat_given_sameTextDifferentImages() {
        // given
        byte[] imageA = new byte[]{1, 2, 3};
        byte[] imageB = new byte[]{4, 5, 6};
        LlmChatRequest requestA = imageRequest("SAME PROMPT", imageA);
        LlmChatRequest requestB = imageRequest("SAME PROMPT", imageB);
        stubModelResolve();
        when(llmCacheRepository.findByKbIdAndCacheKey(anyLong(), any(CacheType.class), anyString()))
                .thenReturn(Optional.empty());
        when(delegate.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult("OUT", 1));

        // when
        cachingLlmClient.chat(requestA);
        cachingLlmClient.chat(requestB);

        // then：键不同且各自含对应图片摘要
        ArgumentCaptor<LlmCacheEntry> entryCaptor = ArgumentCaptor.forClass(LlmCacheEntry.class);
        verify(llmCacheRepository, times(2)).saveIfAbsent(entryCaptor.capture());
        String keyA = entryCaptor.getAllValues().get(0).cacheKey();
        String keyB = entryCaptor.getAllValues().get(1).cacheKey();
        assertNotEquals(keyA, keyB, "同文本不同图必须产生不同缓存键");
        assertEquals(expectedCacheKey(MODEL_NAME, "SYS", "SAME PROMPT", "default", imageDigestPart(imageA)), keyA);
        assertEquals(expectedCacheKey(MODEL_NAME, "SYS", "SAME PROMPT", "default", imageDigestPart(imageB)), keyB);
    }

    /**
     * 场景：提示词相同、图片内容相同但为不同字节数组实例。
     * 预期：缓存键一致（图片摘要按内容计算，重复摄入可稳定回放）。
     */
    @Test
    void should_produceSameCacheKey_when_chat_given_sameTextAndSameImageContent() {
        // given
        LlmChatRequest first = imageRequest("SAME PROMPT", new byte[]{7, 7});
        LlmChatRequest second = imageRequest("SAME PROMPT", new byte[]{7, 7});
        stubModelResolve();
        when(llmCacheRepository.findByKbIdAndCacheKey(anyLong(), any(CacheType.class), anyString()))
                .thenReturn(Optional.empty());
        when(delegate.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult("OUT", 1));

        // when
        cachingLlmClient.chat(first);
        cachingLlmClient.chat(second);

        // then
        ArgumentCaptor<LlmCacheEntry> entryCaptor = ArgumentCaptor.forClass(LlmCacheEntry.class);
        verify(llmCacheRepository, times(2)).saveIfAbsent(entryCaptor.capture());
        assertEquals(entryCaptor.getAllValues().get(0).cacheKey(), entryCaptor.getAllValues().get(1).cacheKey(),
                "同文本同图必须产生同一缓存键");
    }

    /**
     * 场景：一次请求携带多张图片（通路 A 允许 1-3 图）。
     * 预期：摘要按图片列表顺序逐张追加到要素串尾部；顺序交换即产生不同键。
     */
    @Test
    void should_appendImageDigestsInListOrder_when_chat_given_multipleImages() {
        // given
        byte[] firstImage = new byte[]{(byte) 0xAA};
        byte[] secondImage = new byte[]{(byte) 0xBB};
        LlmChatRequest ordered = new LlmChatRequest(KB_ID, PROFILE_ID, "SYS", "MULTI IMAGE PROMPT", null,
                CacheType.ANSWER, List.of(new LlmImage("image/png", firstImage),
                        new LlmImage("image/jpeg", secondImage)));
        LlmChatRequest swapped = new LlmChatRequest(KB_ID, PROFILE_ID, "SYS", "MULTI IMAGE PROMPT", null,
                CacheType.ANSWER, List.of(new LlmImage("image/png", secondImage),
                        new LlmImage("image/jpeg", firstImage)));
        stubModelResolve();
        when(llmCacheRepository.findByKbIdAndCacheKey(anyLong(), any(CacheType.class), anyString()))
                .thenReturn(Optional.empty());
        when(delegate.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult("OUT", 1));

        // when
        cachingLlmClient.chat(ordered);
        cachingLlmClient.chat(swapped);

        // then：键等于「四要素 + 依序两张图摘要」的手工复算值；交换顺序即不同键（不做集合归一）
        ArgumentCaptor<LlmCacheEntry> entryCaptor = ArgumentCaptor.forClass(LlmCacheEntry.class);
        verify(llmCacheRepository, times(2)).saveIfAbsent(entryCaptor.capture());
        String orderedKey = entryCaptor.getAllValues().get(0).cacheKey();
        String swappedKey = entryCaptor.getAllValues().get(1).cacheKey();
        assertEquals(expectedCacheKey(MODEL_NAME, "SYS", "MULTI IMAGE PROMPT", "default",
                imageDigestPart(firstImage), imageDigestPart(secondImage)), orderedKey, "图片摘要必须按列表顺序追加");
        assertEquals(expectedCacheKey(MODEL_NAME, "SYS", "MULTI IMAGE PROMPT", "default",
                imageDigestPart(secondImage), imageDigestPart(firstImage)), swappedKey);
        assertNotEquals(orderedKey, swappedKey, "图片顺序不同不得归一为同一缓存键");
    }

    // ------------------------------------------------------------------ 抽取缓存归属登记（spec extract-cache-attribution）

    /**
     * 场景：携带归因字段的抽取调用未命中缓存。
     * 预期：回源并回写缓存行后，按「本次实际使用的缓存键」登记该分块与缓存行的归属
     * （分区取请求声明的 EXTRACT），登记键与回写键必须完全一致。
     */
    @Test
    void should_registerAttribution_when_chat_given_attributionRequestCacheMiss() {
        // given：EXTRACT 分区抽取请求携带分块标识，缓存查询未命中
        LlmChatRequest request = attributionRequest("EXTRACT PROMPT", CHUNK_ID);
        stubModelResolve();
        when(llmCacheRepository.findByKbIdAndCacheKey(eq(KB_ID), eq(CacheType.EXTRACT), anyString()))
                .thenReturn(Optional.empty());
        when(delegate.chat(request)).thenReturn(new LlmChatResult("EXTRACT OUT", 9));

        // when
        LlmChatResult result = cachingLlmClient.chat(request);

        // then：结果取委托值；回写与归属登记使用同一缓存键
        assertEquals("EXTRACT OUT", result.text());
        assertFalse(result.cacheHit());
        ArgumentCaptor<LlmCacheEntry> entryCaptor = ArgumentCaptor.forClass(LlmCacheEntry.class);
        verify(llmCacheRepository).saveIfAbsent(entryCaptor.capture());
        String cacheKey = entryCaptor.getValue().cacheKey();
        verify(chunkExtractCacheRepository).registerAll(KB_ID, CHUNK_ID, CacheType.EXTRACT, List.of(cacheKey));
    }

    /**
     * 场景（核心）：携带归因字段的抽取调用命中缓存并回放。
     * 预期：回放内容与命中标志不变、绝不回源也不回写（缓存行保持不可变）；
     * 归属登记仍以本次计算的缓存键落库——回放路径是归属关系最容易漏的一条。
     */
    @Test
    void should_registerAttribution_when_chat_given_attributionRequestCacheHit() {
        // given：缓存查询命中一行 EXTRACT 条目（捕获本次计算出的键）
        LlmChatRequest request = attributionRequest("EXTRACT PROMPT", CHUNK_ID);
        stubModelResolve();
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        when(llmCacheRepository.findByKbIdAndCacheKey(eq(KB_ID), eq(CacheType.EXTRACT), keyCaptor.capture()))
                .thenReturn(Optional.of(LlmCacheEntry.restore(11L, KB_ID, CACHE_KEY, CacheType.EXTRACT,
                        MODEL_NAME, "p", "CACHED EXTRACT", 21, null, null)));

        // when
        LlmChatResult result = cachingLlmClient.chat(request);

        // then：回放命中、零回源零回写，归属登记使用本次缓存键
        assertEquals("CACHED EXTRACT", result.text());
        assertTrue(result.cacheHit());
        verify(delegate, never()).chat(any(LlmChatRequest.class));
        verify(llmCacheRepository, never()).saveIfAbsent(any(LlmCacheEntry.class));
        verify(chunkExtractCacheRepository).registerAll(KB_ID, CHUNK_ID, CacheType.EXTRACT,
                List.of(keyCaptor.getValue()));
    }

    /**
     * 场景：无归因字段的调用（查询答案、实体描述摘要等非摄入期抽取）。
     * 预期：不登记任何归属——归因字段为空即零归属副作用。
     */
    @Test
    void should_notRegisterAttribution_when_chat_given_requestWithoutAttribution() {
        // given：五参便捷构造器（无归因字段），未命中回源
        LlmChatRequest request = new LlmChatRequest(KB_ID, PROFILE_ID, null, "GENERAL PROMPT", null);
        stubModelResolve();
        when(llmCacheRepository.findByKbIdAndCacheKey(anyLong(), any(CacheType.class), anyString()))
                .thenReturn(Optional.empty());
        when(delegate.chat(request)).thenReturn(new LlmChatResult("GENERAL ANSWER", 7));

        // when
        cachingLlmClient.chat(request);

        // then
        verify(chunkExtractCacheRepository, never()).registerAll(any(), any(), any(), any());
    }

    /**
     * 场景：未命中路径的归属登记抛异常（归属仓储故障）。
     * 预期：不影响 LLM 调用结果返回（不抛异常、结果与命中标志照常），缓存回写已发生，
     * 仅登记失败计数累加一次。
     */
    @Test
    void should_returnLlmResult_when_chat_given_attributionRegistrationThrowsOnMiss() {
        // given：未命中回源成功，归属登记抛异常
        LlmChatRequest request = attributionRequest("EXTRACT PROMPT", CHUNK_ID);
        stubModelResolve();
        when(llmCacheRepository.findByKbIdAndCacheKey(anyLong(), any(CacheType.class), anyString()))
                .thenReturn(Optional.empty());
        when(delegate.chat(request)).thenReturn(new LlmChatResult("EXTRACT OUT", 9));
        doThrow(new RuntimeException("归属登记失败"))
                .when(chunkExtractCacheRepository).registerAll(any(), any(), any(), any());

        // when
        LlmChatResult result = assertDoesNotThrow(() -> cachingLlmClient.chat(request));

        // then：主链路结果不受影响，仅旁路计数变化
        assertEquals("EXTRACT OUT", result.text());
        assertFalse(result.cacheHit());
        verify(llmCacheRepository).saveIfAbsent(any(LlmCacheEntry.class));
        assertEquals(1L, cachingLlmClient.attributionFailureCount());
    }

    /**
     * 场景：命中回放路径的归属登记抛异常。
     * 预期：回放结果照常返回（不抛异常、命中标志为真），仅登记失败计数累加一次。
     */
    @Test
    void should_returnCachedResult_when_chat_given_attributionRegistrationThrowsOnHit() {
        // given：命中回放，归属登记抛异常
        LlmChatRequest request = attributionRequest("EXTRACT PROMPT", CHUNK_ID);
        stubModelResolve();
        when(llmCacheRepository.findByKbIdAndCacheKey(anyLong(), any(CacheType.class), anyString()))
                .thenReturn(Optional.of(LlmCacheEntry.restore(12L, KB_ID, CACHE_KEY, CacheType.EXTRACT,
                        MODEL_NAME, "p", "CACHED EXTRACT", 21, null, null)));
        doThrow(new RuntimeException("归属登记失败"))
                .when(chunkExtractCacheRepository).registerAll(any(), any(), any(), any());

        // when
        LlmChatResult result = assertDoesNotThrow(() -> cachingLlmClient.chat(request));

        // then
        assertEquals("CACHED EXTRACT", result.text());
        assertTrue(result.cacheHit());
        assertEquals(1L, cachingLlmClient.attributionFailureCount());
    }

    /**
     * 场景：携带归因字段的同一请求连续调用两次（首次未命中、次次命中）。
     * 预期：两次缓存键均与「引入归属前」的手工复算基线逐字节一致（归因字段不进入键要素串），
     * 且第二次命中回放——引入归属不改变键与命中判定口径。
     */
    @Test
    void should_keepCacheKeyUnchanged_when_chat_given_sameRequestWithAttribution() {
        // given：基线键由四要素（模型 + 系统 + 用户 + 温度）手工复算，不含归因字段
        String baselineKey = expectedCacheKey(MODEL_NAME, "SYS", "ATTRIBUTION PROMPT", "default");
        stubModelResolve();
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        when(llmCacheRepository.findByKbIdAndCacheKey(eq(KB_ID), eq(CacheType.EXTRACT), keyCaptor.capture()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(LlmCacheEntry.restore(31L, KB_ID, baselineKey, CacheType.EXTRACT,
                        MODEL_NAME, "p", "CACHED EXTRACT", 5, null, null)));
        when(delegate.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult("EXTRACT OUT", 9));

        // when
        LlmChatResult first = cachingLlmClient.chat(attributionRequest("ATTRIBUTION PROMPT", CHUNK_ID));
        LlmChatResult second = cachingLlmClient.chat(attributionRequest("ATTRIBUTION PROMPT", CHUNK_ID));

        // then
        assertFalse(first.cacheHit());
        assertTrue(second.cacheHit(), "同一提示词第二次调用必须仍命中回放");
        assertEquals(List.of(baselineKey, baselineKey), keyCaptor.getAllValues(),
                "归因字段不得进入缓存键（键与引入归属前逐字节一致）");
    }

    /**
     * 场景：提示词相同、归因分块标识不同（不同分块共用同一提示词）。
     * 预期：两者缓存键完全相同（归因不参与键计算），各自独立登记自身分块的归属。
     */
    @Test
    void should_produceSameCacheKey_when_chat_given_samePromptDifferentAttribution() {
        // given：同提示词、不同分块标识，两次均未命中
        stubModelResolve();
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        when(llmCacheRepository.findByKbIdAndCacheKey(eq(KB_ID), eq(CacheType.EXTRACT), keyCaptor.capture()))
                .thenReturn(Optional.empty());
        when(delegate.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult("EXTRACT OUT", 9));

        // when
        cachingLlmClient.chat(attributionRequest("SHARED PROMPT", CHUNK_ID));
        cachingLlmClient.chat(attributionRequest("SHARED PROMPT", CHUNK_ID + 1));

        // then：键一致；两次归属分别落在各自分块上（多对多关系成立）
        assertEquals(2, keyCaptor.getAllValues().size());
        assertEquals(keyCaptor.getAllValues().get(0), keyCaptor.getAllValues().get(1),
                "归因字段不同不得产生不同的缓存键");
        verify(chunkExtractCacheRepository, times(2)).registerAll(any(), any(), any(), any());
        verify(chunkExtractCacheRepository).registerAll(KB_ID, CHUNK_ID, CacheType.EXTRACT,
                List.of(keyCaptor.getAllValues().get(0)));
        verify(chunkExtractCacheRepository).registerAll(KB_ID, CHUNK_ID + 1, CacheType.EXTRACT,
                List.of(keyCaptor.getAllValues().get(1)));
    }

    /**
     * 构造携带归因分块标识的抽取请求（EXTRACT 分区、纯文本）。
     *
     * @param userPrompt 用户提示词
     * @param chunkId    归因分块标识
     * @return LLM 对话请求
     */
    private LlmChatRequest attributionRequest(String userPrompt, Long chunkId) {
        return new LlmChatRequest(KB_ID, PROFILE_ID, "SYS", userPrompt, null, CacheType.EXTRACT,
                List.of(), chunkId);
    }

    /**
     * 构造携带单张图片的检索期请求（文本要素固定，便于对比图片维度）。
     */
    private LlmChatRequest imageRequest(String userPrompt, byte[] imageBytes) {
        return new LlmChatRequest(KB_ID, PROFILE_ID, "SYS", userPrompt, null, CacheType.ANSWER,
                List.of(new LlmImage("image/png", imageBytes)));
    }

    /**
     * 手工复算缓存键：要素串按固定连接符拼接后取 MD5 hex。
     */
    private String expectedCacheKey(String... parts) {
        return DigestUtils.md5DigestAsHex(String.join(KEY_PART_SEPARATOR, parts).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 手工复算图片键要素：{@code sha256:<内容 SHA-256 小写十六进制>}。
     */
    private String imageDigestPart(byte[] content) {
        try {
            return IMAGE_DIGEST_PREFIX + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 算法不可用", e);
        }
    }

    /**
     * 桩化模型端口解析：任意 profileId 解析出固定模型名的端点三元组。
     */
    private void stubModelResolve() {
        when(modelProfileAccess.resolve(anyString()))
                .thenReturn(new ModelProfileAccess.ResolvedEndpoint("https://api.example/v1", "sk-x", MODEL_NAME, null));
    }
}
