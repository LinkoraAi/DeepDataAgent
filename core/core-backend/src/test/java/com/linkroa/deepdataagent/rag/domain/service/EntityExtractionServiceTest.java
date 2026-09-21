package com.linkroa.deepdataagent.rag.domain.service;

import com.linkroa.deepdataagent.knowledgebase.domain.model.EntityType;
import com.linkroa.deepdataagent.rag.domain.enums.CacheType;
import com.linkroa.deepdataagent.rag.domain.model.ChunkVO;
import com.linkroa.deepdataagent.rag.domain.model.ContentBlockVO;
import com.linkroa.deepdataagent.rag.domain.model.EntityNode;
import com.linkroa.deepdataagent.rag.domain.model.LlmCacheEntry;
import com.linkroa.deepdataagent.rag.domain.model.PersistedChunkVO;
import com.linkroa.deepdataagent.rag.domain.model.RelationEdge;
import com.linkroa.deepdataagent.rag.domain.port.LlmChatRequest;
import com.linkroa.deepdataagent.rag.domain.port.LlmChatResult;
import com.linkroa.deepdataagent.rag.domain.port.LlmClient;
import com.linkroa.deepdataagent.rag.domain.repository.ChunkExtractCacheRepository;
import com.linkroa.deepdataagent.rag.domain.repository.LlmCacheRepository;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static com.linkroa.deepdataagent.rag.domain.service.EntityExtractionService.BELONGS_TO_WEIGHT;
import static com.linkroa.deepdataagent.rag.domain.service.EntityExtractionService.COMPLETION_DELIMITER;
import static com.linkroa.deepdataagent.rag.domain.service.EntityExtractionService.TUPLE_DELIMITER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link EntityExtractionService} 单元测试：以同步执行器（Runnable::run）与 Mock 的
 * {@link LlmClient} 离线驱动（Prompt 渲染经 {@code PromptCatalog} 静态门面实测，
 *），覆盖文本/JSON 双模式解析
 * （含 {@code relationship} 前缀容错与非法前缀跳过）、gleaning 补漏三停止条件
 * （完成信号/零新增即停/轮数上限）、类型过滤、多模态 sidecar 归属边注入（只建归属边、
 * 不构造主实体节点，主实体名仅消费 ctx.mediaPrimaryEntityName，空白名 WARN 跳过注入）、
 * 关系端点方向恒字典序归一、同 (端点对, 来源) 重复记录不追加取最大权重、
 * 跨块聚合的逐来源贡献记录化（「一记录一边」出口）、sourceIds 真实分块主键直接回填
 * （入参为 {@link PersistedChunkVO}，构造即真值、无占位与重写环节）、
 * 部分失败与取消语义；语言例句与注入变量口径改以对 {@link LlmClient} 收到的
 * 最终提示词内容断言。另覆盖缓存重放入口 {@code replayExtractedChunks}
 * （rebuild-kg-on-document-delete / tasks 3.x：多轮响应全量纳入且按创建时间升序合并、
 * 同块同名取描述更长者、类型过滤与 sidecar 注入在重放路径同样生效、缓存缺失返回空、
 * 分块间记录不串扰、重放全程零 LLM 调用）；以及扇出中断完整性
 * （fix-rag-concurrency-integrity / D1、D2：闸门等待被中断计为阶段失败且 cause 携带真实
 * 原因、单块业务异常的部分成功语义不扩散至中断、循环中取消先收口已提交任务再上抛）。
 *
 * @author DeepDataAgent
 */
@ExtendWith(MockitoExtension.class)
class EntityExtractionServiceTest {

    /** 测试知识库ID */
    private static final Long KB_ID = 7L;

    /** 测试文档ID */
    private static final Long DOC_ID = 42L;

    /** 测试模型 profileId */
    private static final String PROFILE_ID = "chat-profile-1";

    /** 测试文件路径 */
    private static final String FILE_PATH = "docs/sample.md";

    /** 落库态分块主键基准：chunkId = 基准 + sequence，与序号数值可区分（断言来源键为真值而非占位） */
    private static final long CHUNK_ID_BASE = 1000L;

    /** 重放缓存键样例：首轮响应行（32 位 MD5 hex 形态） */
    private static final String CACHE_KEY_FIRST = "0123456789abcdef0123456789abcdef";

    /** 重放缓存键样例：续抽响应行 */
    private static final String CACHE_KEY_GLEAN = "89abcdef0123456789abcdef01234567";

    /** 重放缓存键样例：另一分块独立响应行 */
    private static final String CACHE_KEY_OTHER = "fedadedeadbeef0123456789abcdef01";

    /** 重放缓存行创建时间基准（固定时刻，保证排序断言可重复） */
    private static final OffsetDateTime BASE_TIME = OffsetDateTime.parse("2026-01-01T08:00:00+08:00");

    /** 异步观测类用例的等待上限（毫秒） */
    private static final long ASYNC_TIMEOUT_MILLIS = 3000L;

    /** LLM 对话端口 Mock */
    @Mock
    private LlmClient llmClient;

    /** 抽取缓存归属仓储端口 Mock */
    @Mock
    private ChunkExtractCacheRepository chunkExtractCacheRepository;

    /** LLM 调用缓存仓储端口 Mock */
    @Mock
    private LlmCacheRepository llmCacheRepository;

    /** 被测服务 */
    private EntityExtractionService service;

    /**
     * 以同步执行器与真实 ObjectMapper 构建被测服务（Prompt 渲染走静态目录实测，
     * 仓储端口为 Mock——重放用例只经仓储读缓存，不触真实 SQL）。
     */
    @BeforeEach
    void setUp() {
        service = new EntityExtractionService(llmClient, Runnable::run, new ObjectMapper(),
                chunkExtractCacheRepository, llmCacheRepository);
    }

    @Test
    void should_extractNodesAndEdges_when_extract_given_textModeSingleChunk() {
        // given：一段含两实体一关系加完成信号的文本模式响应
        String response = entityRow("Alice", "Person", "Alice is a researcher.") + "\n"
                + entityRow("ACME Corp", "Organization", "ACME Corp is a company.") + "\n"
                + relationRow("Alice", "ACME Corp", "employment", "Alice works at ACME Corp.") + "\n"
                + COMPLETION_DELIMITER;
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(response, 100));

        // when
        EntityExtractionResult result = service.extract(defaultContext(), List.of(textChunk("some text")));

        // then：两节点一边，字段与权重正确
        assertEquals(2, result.nodes().size());
        assertEquals(1, result.edges().size());
        assertEquals("Person", nodeByName(result, "Alice").properties().entityType());
        assertEquals("Alice is a researcher.", nodeByName(result, "Alice").properties().description());
        RelationEdge edge = result.edges().get(0);
        // 端点方向恒归一为字典序：'C'(67) < 'l'(108)，故 "ACME Corp" < "Alice"，ACME Corp 为源
        assertEquals("ACME Corp", edge.sourceName());
        assertEquals("Alice", edge.targetName());
        assertEquals(1.0, edge.properties().weight(), 0.001);
        assertEquals(List.of("employment"), edge.properties().keywords());
        verify(llmClient, times(1)).chat(any(LlmChatRequest.class));
    }

    @Test
    void should_aggregateAcrossChunks_when_extract_given_sameEntityInTwoChunks() {
        // given：两个 chunk 抽取到同名实体与同一对关系，描述一短一长
        String first = entityRow("Alice", "Person", "Short.") + "\n"
                + entityRow("Bob", "Person", "Bob is an engineer.") + "\n"
                + relationRow("Alice", "Bob", "knows", "First desc.") + "\n"
                + COMPLETION_DELIMITER;
        String second = entityRow("Alice", "Person", "A much longer description about Alice.") + "\n"
                + relationRow("Alice", "Bob", "friend", "A much longer relationship description.") + "\n"
                + COMPLETION_DELIMITER;
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(first, 50))
                .thenReturn(new LlmChatResult(second, 50));

        // when
        EntityExtractionResult result = service.extract(defaultContext(),
                List.of(textChunk("chunk one"), persisted(new ChunkVO(2, "chunk two", 10, null))));

        // then：同名实体取长描述；同端点对关系不再压成单边 2.0，而是逐来源展开为两条
        // 「一记录一边」的边（各 weight=1.0，sourceIds 只含自身真实分块主键），关键词并集排序、描述取长
        assertEquals("A much longer description about Alice.",
                nodeByName(result, "Alice").properties().description());
        List<RelationEdge> aliceBobEdges = edgesByPair(result, "Alice", "Bob");
        assertEquals(2, aliceBobEdges.size());
        for (RelationEdge edge : aliceBobEdges) {
            assertEquals(1.0, edge.properties().weight(), 0.001);
            assertEquals(List.of("friend", "knows"), edge.properties().keywords());
            assertEquals("A much longer relationship description.", edge.properties().description());
        }
        assertEquals(List.of(1001L), aliceBobEdges.get(0).properties().sourceIds());
        assertEquals(List.of(1002L), aliceBobEdges.get(1).properties().sourceIds());
        verify(llmClient, times(2)).chat(any(LlmChatRequest.class));
    }

    /**
     * 场景：多 chunk 同关系保留逐条贡献记录。
     * 预期：出口为 3 条「一记录一边」的边，各 weight=1.0 全额、sourceIds 只含自身真实分块主键，
     * 而非压成一条 weight=3.0 的文档级标量边。
     */
    @Test
    void should_keepPerChunkContributionRecords_when_extract_given_sameRelationInThreeChunks() {
        // given：3 个 chunk 各返回同一无向端点对关系（描述长度不同）
        String one = entityRow("Alice", "Person", "Alice desc.") + "\n"
                + entityRow("Bob", "Person", "Bob desc.") + "\n"
                + relationRow("Alice", "Bob", "knows", "Chunk one desc.") + "\n"
                + COMPLETION_DELIMITER;
        String two = relationRow("Alice", "Bob", "knows", "Chunk two desc.") + "\n"
                + COMPLETION_DELIMITER;
        String three = relationRow("Alice", "Bob", "knows", "A much longer desc from chunk three.") + "\n"
                + COMPLETION_DELIMITER;
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(one, 10))
                .thenReturn(new LlmChatResult(two, 10))
                .thenReturn(new LlmChatResult(three, 10));

        // when
        EntityExtractionResult result = service.extract(defaultContext(), List.of(textChunk("c1"),
                persisted(new ChunkVO(2, "c2", 10, null)), persisted(new ChunkVO(3, "c3", 10, null))));

        // then：3 条独立贡献边，按收集顺序分别携带真实主键 1001/1002/1003（非序号 1/2/3）
        List<RelationEdge> edges = edgesByPair(result, "Alice", "Bob");
        assertEquals(3, edges.size());
        assertEquals(List.of(1001L), edges.get(0).properties().sourceIds());
        assertEquals(List.of(1002L), edges.get(1).properties().sourceIds());
        assertEquals(List.of(1003L), edges.get(2).properties().sourceIds());
        for (RelationEdge edge : edges) {
            assertEquals(1.0, edge.properties().weight(), 0.001);
            assertEquals("A much longer desc from chunk three.", edge.properties().description());
        }
        verify(llmClient, times(3)).chat(any(LlmChatRequest.class));
    }

    /**
     * 场景：同 chunk 首轮与补漏轮重复抽取同一端点对关系，同 (端点对, 来源) 不追加记录、取权重较大者。
     * 预期：只产出一条贡献记录（单边、weight=1.0、sourceIds 单元素；两轮权重均为 1.0，
     * 后到等权不覆盖既有记录），关键词并集与描述取长仍生效。
     */
    @Test
    void should_keepSingleContribution_when_extract_given_sameRelationInFirstAndGleaningRound() {
        // given：首轮与补漏轮返回同一关系（关键词与描述不同），补漏 1 轮
        String first = entityRow("Alice", "Person", "Alice desc.") + "\n"
                + entityRow("Bob", "Person", "Bob desc.") + "\n"
                + relationRow("Alice", "Bob", "knows", "First desc.") + "\n";
        String gleaned = relationRow("Alice", "Bob", "friend", "A much longer relation desc.") + "\n"
                + COMPLETION_DELIMITER;
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(first, 10))
                .thenReturn(new LlmChatResult(gleaned, 10));

        // when
        EntityExtractionResult result = service.extract(contextWithGleaning(1), List.of(textChunk("body")));

        // then：同来源不追加记录 → 单边全额 1.0，关键词并集排序、描述取长
        List<RelationEdge> edges = edgesByPair(result, "Alice", "Bob");
        assertEquals(1, edges.size());
        assertEquals(1.0, edges.get(0).properties().weight(), 0.001);
        assertEquals(List.of(1001L), edges.get(0).properties().sourceIds());
        assertEquals(List.of("friend", "knows"), edges.get(0).properties().keywords());
        assertEquals("A much longer relation desc.", edges.get(0).properties().description());
    }

    /**
     * 场景：同名实体分别出现在绑定真实主键 1001 与 1003 的两块中（sequence 1/3，主键可区分）。
     * 预期：实体 {@code properties.sourceIds} 与关系边 {@code sourceIds} 均等于绑定时的真实
     * chunkId 升序列表——占位值在类型层面不可能进入产物。
     */
    @Test
    void should_backfillRealChunkIdsIntoSourceIds_when_extract_given_sameEntityInChunksOneAndThree() {
        // given：同名实体与关系分别出现在 sequence 1 与 3（chunkId 1001 与 1003）的块中
        String first = entityRow("Alice", "Person", "Alice desc.") + "\n"
                + entityRow("Bob", "Person", "Bob desc.") + "\n"
                + relationRow("Alice", "Bob", "knows", "Alice knows Bob.") + "\n"
                + COMPLETION_DELIMITER;
        String third = entityRow("Alice", "Person", "Alice appears again in a long desc.") + "\n"
                + entityRow("Carol", "Person", "Carol desc.") + "\n"
                + relationRow("Alice", "Carol", "mentors", "Alice mentors Carol.") + "\n"
                + COMPLETION_DELIMITER;
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(first, 10))
                .thenReturn(new LlmChatResult(third, 10));

        // when
        EntityExtractionResult result = service.extract(defaultContext(),
                List.of(textChunk("chunk one"), persisted(new ChunkVO(3, "chunk three", 10, null))));

        // then：sourceIds 为出现过的真实分块主键升序列表（构造即真值，无占位与重写环节）
        assertEquals(List.of(1001L, 1003L), nodeByName(result, "Alice").properties().sourceIds());
        assertEquals(List.of(1001L), nodeByName(result, "Bob").properties().sourceIds());
        assertEquals(List.of(1003L), nodeByName(result, "Carol").properties().sourceIds());
        assertEquals(List.of(1001L), edgeByPair(result, "Alice", "Bob").properties().sourceIds());
        assertEquals(List.of(1003L), edgeByPair(result, "Alice", "Carol").properties().sourceIds());
    }

    @Test
    void should_keepLongerDescription_when_extract_given_gleaningRoundReturnsLongerDesc() {
        // given：首轮短描述且无完成信号，补漏轮返回更长描述
        String first = entityRow("Alice", "Person", "Short.") + "\n";
        String gleaned = entityRow("Alice", "Person", "A far more complete description.") + "\n"
                + COMPLETION_DELIMITER;
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(first, 10))
                .thenReturn(new LlmChatResult(gleaned, 10));

        // when
        EntityExtractionResult result = service.extract(contextWithGleaning(1), List.of(textChunk("body")));

        // then：描述取更长者，续抽提示词含单轮适配的历轮内容注入段
        assertEquals("A far more complete description.",
                nodeByName(result, "Alice").properties().description());
        ArgumentCaptor<LlmChatRequest> captor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(llmClient, times(2)).chat(captor.capture());
        String continuePrompt = captor.getAllValues().get(1).userPrompt();
        assertTrue(continuePrompt.contains("---Previously Extracted Content---"));
        assertTrue(continuePrompt.contains(first.trim()));
        assertTrue(continuePrompt.contains("body"));
    }

    @Test
    void should_stopGleaning_when_extract_given_maxGleaningReached() {
        // given：两轮响应均无完成信号，补漏上限 1 轮
        String first = entityRow("Alice", "Person", "Alice desc.") + "\n";
        String gleaned = entityRow("Bob", "Person", "Bob desc.") + "\n";
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(first, 10))
                .thenReturn(new LlmChatResult(gleaned, 10));

        // when
        EntityExtractionResult result = service.extract(contextWithGleaning(1), List.of(textChunk("body")));

        // then：仅首轮+1 轮补漏，两实体都在
        assertEquals(2, result.nodes().size());
        verify(llmClient, times(2)).chat(any(LlmChatRequest.class));
    }

    @Test
    void should_stopEarly_when_extract_given_completionInFirstRound() {
        // given：首轮即含完成信号，补漏上限 3 轮
        String response = entityRow("Alice", "Person", "Alice desc.") + "\n" + COMPLETION_DELIMITER;
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(response, 10));

        // when
        service.extract(contextWithGleaning(3), List.of(textChunk("body")));

        // then：不再发起补漏调用
        verify(llmClient, times(1)).chat(any(LlmChatRequest.class));
    }

    /**
     * 场景：续抽轮解析零新增即停（M-4 条件②）。
     * 变体一：续抽轮仅返回与首轮同名实体（合并为更新、零新增）且无完成信号 → 该轮后终止；
     * 变体二：续抽轮仅返回完成信号（无任何记录、零新增）→ 同样在该轮后终止。
     * 预期：两种变体的单块 LLM 总调用次数均为 2（首轮+1 轮续抽），而非跑满 maxGleaning=2。
     */
    @Test
    void should_stopGleaning_when_extractSingleChunk_given_zeroNewRecordsRound() {
        // given：变体一——首轮有实体无完成信号，第 1 轮续抽仅返回同名实体（描述更长）
        String first = entityRow("Alice", "Person", "Alice desc.") + "\n";
        String sameNameGleaned = entityRow("Alice", "Person", "A longer Alice description.") + "\n";
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(first, 10))
                .thenReturn(new LlmChatResult(sameNameGleaned, 10));

        // when
        EntityExtractionResult result = service.extract(contextWithGleaning(2), List.of(textChunk("body")));

        // then：零新增在续抽第 1 轮后终止（条件②，若无此条件将发起第 3 次调用）；合并先于终止生效
        verify(llmClient, times(2)).chat(any(LlmChatRequest.class));
        assertEquals(1, result.nodes().size());
        assertEquals("A longer Alice description.", nodeByName(result, "Alice").properties().description());

        // given：变体二——续抽轮仅返回完成信号（解析零新增）
        clearInvocations(llmClient);
        String firstB = entityRow("Bob", "Person", "Bob desc.") + "\n";
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(firstB, 10))
                .thenReturn(new LlmChatResult(COMPLETION_DELIMITER, 1));

        // when
        service.extract(contextWithGleaning(2), List.of(textChunk("body")));

        // then：该轮后终止，总调用 2 次
        verify(llmClient, times(2)).chat(any(LlmChatRequest.class));
    }

    /**
     * 场景：每轮续抽均有新增且均无完成信号。
     * 预期：N=2 时续抽跑满 2 轮后因上限终止，LLM 总调用次数为 3（首轮+2 轮续抽）。
     */
    @Test
    void should_continueGleaning_when_extractSingleChunk_given_newRecordsEachRound_untilLimit() {
        // given：三轮响应各含一个新实体名且均无完成信号，补漏上限 2 轮
        String first = entityRow("Alice", "Person", "Alice desc.") + "\n";
        String gleanOne = entityRow("Bob", "Person", "Bob desc.") + "\n";
        String gleanTwo = entityRow("Carol", "Person", "Carol desc.") + "\n";
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(first, 10))
                .thenReturn(new LlmChatResult(gleanOne, 10))
                .thenReturn(new LlmChatResult(gleanTwo, 10));

        // when
        EntityExtractionResult result = service.extract(contextWithGleaning(2), List.of(textChunk("body")));

        // then：零新增条件未触发，因达上限终止，三实体齐全
        verify(llmClient, times(3)).chat(any(LlmChatRequest.class));
        assertEquals(3, result.nodes().size());
    }

    @Test
    void should_dropEntityAndRelatedEdges_when_extract_given_typeNotInAllowedList() {
        // given：自定义类型清单仅含 Drug，未知类型实体及其关系应被连带丢弃
        EntityExtractionContext ctx = new EntityExtractionContext(KB_ID, DOC_ID, PROFILE_ID, "en",
                List.of(new EntityType("Drug")), false, 0, 4, FILE_PATH, null, null);
        String response = entityRow("Alice", "Person", "Alice is a person.") + "\n"
                + entityRow("Aspirin", "drug", "Aspirin is a medicine.") + "\n"
                + entityRow("Mystery", "UnknownType", "Mystery has a disallowed type.") + "\n"
                + relationRow("Alice", "Aspirin", "recommends", "Alice recommends Aspirin.") + "\n"
                + relationRow("Alice", "Mystery", "knows", "Alice knows Mystery.") + "\n"
                + COMPLETION_DELIMITER;
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(response, 10));

        // when
        EntityExtractionResult result = service.extract(ctx, List.of(textChunk("body")));

        // then：未知类型实体与其引用边被丢弃，自定义类型大小写不敏感放行
        assertEquals(2, result.nodes().size());
        assertEquals(1, result.edges().size());
        assertEquals("Aspirin", result.edges().get(0).targetName());
    }

    @Test
    void should_skipMalformedLines_when_extract_given_noisyResponse() {
        // given：含垃圾行、字段数不足行与有效行
        String response = "this line is garbage\n"
                + "entity" + TUPLE_DELIMITER + "OnlyTwoParts\n"
                + entityRow("Alice", "Person", "Alice is fine.") + "\n"
                + entityRow("Bob", "Person", "Bob is fine.") + "\n"
                + "relation" + TUPLE_DELIMITER + "Alice" + TUPLE_DELIMITER + "Bob\n"
                + relationRow("Alice", "Bob", "friendship", "Alice and Bob are friends.") + "\n"
                + COMPLETION_DELIMITER;
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(response, 10));

        // when
        EntityExtractionResult result = service.extract(defaultContext(), List.of(textChunk("body")));

        // then：坏行全部被跳过，有效数据完整保留
        assertEquals(2, result.nodes().size());
        assertEquals(1, result.edges().size());
        assertEquals("Alice and Bob are friends.", result.edges().get(0).properties().description());
    }

    /**
     * 场景：{@code relationship} 前缀容错（M-2）。
     * 预期：合法 relationship 行与 relation 行完全等价入汇聚器；relationship 前缀但字段数
     * 不足/自环的非法行仍被拒绝，校验行为与 relation 一致。
     */
    @Test
    void should_parseRelation_when_parseTextResponse_given_relationshipPrefix() {
        // given：合法 relationship 行 + 字段数不足行 + 自环行，外加实体行与完成信号
        String response = entityRow("Alice", "Person", "Alice desc.") + "\n"
                + entityRow("ACME Corp", "Organization", "ACME desc.") + "\n"
                + relationshipRow("Alice", "ACME Corp", "employment", "Alice works at ACME Corp.") + "\n"
                + "relationship" + TUPLE_DELIMITER + "Alice" + TUPLE_DELIMITER + "ACME Corp" + "\n"
                + relationshipRow("Alice", "Alice", "self", "Self loop must be rejected.") + "\n"
                + COMPLETION_DELIMITER;
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(response, 10));

        // when
        EntityExtractionResult result = service.extract(defaultContext(), List.of(textChunk("body")));

        // then：仅合法 relationship 行入库，非法行被同分支校验拒绝
        assertEquals(2, result.nodes().size());
        assertEquals(1, result.edges().size());
        RelationEdge edge = result.edges().get(0);
        // 端点方向恒归一为字典序（"ACME Corp" < "Alice"），与模型输出的源/目标先后无关
        assertEquals("ACME Corp", edge.sourceName());
        assertEquals("Alice", edge.targetName());
        assertEquals(List.of("employment"), edge.properties().keywords());
        assertEquals("Alice works at ACME Corp.", edge.properties().description());
        assertEquals(1.0, edge.properties().weight(), 0.001);
    }

    /**
     * 场景：无法识别的前缀行跳过。
     * 预期：{@code item<|#|>...} 行被跳过且不产生任何记录，同响应内合法 entity/relation 行不受影响。
     */
    @Test
    void should_skipUnknownPrefix_when_parseTextResponse_given_illegalPrefix() {
        // given：item 前缀行夹在合法 entity/relation 行之间
        String response = "item" + TUPLE_DELIMITER + "A" + TUPLE_DELIMITER + "B" + "\n"
                + entityRow("Alice", "Person", "Alice desc.") + "\n"
                + relationRow("Alice", "ACME Corp", "works_at", "Alice works at ACME Corp.") + "\n"
                + entityRow("ACME Corp", "Organization", "ACME desc.") + "\n"
                + COMPLETION_DELIMITER;
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(response, 10));

        // when
        EntityExtractionResult result = service.extract(defaultContext(), List.of(textChunk("body")));

        // then：非法前缀行不影响其余行解析，且自身不产出实体「A」；边方向恒字典序归一
        assertEquals(2, result.nodes().size());
        assertEquals(1, result.edges().size());
        assertEquals("ACME Corp", result.edges().get(0).sourceName());
        assertEquals("Alice", result.edges().get(0).targetName());
        assertEquals("Alice works at ACME Corp.", result.edges().get(0).properties().description());
    }

    @Test
    void should_returnEmptyAndSkipGleaning_when_extract_given_blankResponse() {
        // given：LLM 返回空白响应，补漏上限 2 轮
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult("   ", 0));

        // when
        EntityExtractionResult result = service.extract(contextWithGleaning(2), List.of(textChunk("body")));

        // then：空结果且不进入补漏循环
        assertTrue(result.nodes().isEmpty());
        assertTrue(result.edges().isEmpty());
        verify(llmClient, times(1)).chat(any(LlmChatRequest.class));
    }

    @Test
    void should_parseJsonWithFence_when_extract_given_jsonModeResponse() {
        // given：JSON 模式，响应含 markdown 围栏与前后缀噪声
        EntityExtractionContext ctx = new EntityExtractionContext(KB_ID, DOC_ID, PROFILE_ID, "en",
                List.of(), true, 0, 4, FILE_PATH, null, null);
        String response = "```json\n"
                + "{\"entities\": ["
                + "{\"name\": \"Alice\", \"type\": \"Person\", \"description\": \"Alice is a researcher.\"},"
                + " {\"name\": \"ACME Corp\", \"type\": \"Organization\", \"description\": \"A company.\"}], "
                + "\"relationships\": ["
                + "{\"source\": \"Alice\", \"target\": \"ACME Corp\", \"keywords\": \"employment, research\","
                + " \"description\": \"Alice works at ACME Corp.\"}]}\n"
                + "```\n" + COMPLETION_DELIMITER;
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(response, 10));

        // when
        EntityExtractionResult result = service.extract(ctx, List.of(textChunk("body")));

        // then：JSON 数组被正确解析为节点与边
        assertEquals(2, result.nodes().size());
        assertEquals(1, result.edges().size());
        assertEquals(List.of("employment", "research"), result.edges().get(0).properties().keywords());
    }

    /**
     * 场景：多模态图片块上下文未携带主实体名（mediaPrimaryEntityName 空白），块内抽到文本实体。
     * 预期：caption 兜底命名链已删除——WARN 跳过归属边注入，结果只含文本实体节点、零边，
     * 不产出任何主实体节点或 caption 兜底名节点。
     */
    @Test
    void should_skipBelongsToInjection_when_extract_given_imageChunkWithoutPrimaryEntityName() {
        // given：图片块含标题元数据，但上下文未提供主实体名
        ChunkVO chunk = new ChunkVO(1, "text body", 10,
                new ContentBlockVO(ContentBlockVO.TYPE_IMAGE, "raw image text",
                        Map.of("image_caption", "Sunset over the lake")));
        String response = entityRow("Alice", "Person", "Alice desc.") + "\n" + COMPLETION_DELIMITER;
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(response, 10));

        // when
        EntityExtractionResult result = service.extract(defaultContext(), List.of(persisted(chunk)));

        // then：只保留块内文本实体；无 caption 兜底主实体节点、无 belongs_to 边
        assertEquals(1, result.nodes().size());
        assertEquals("Alice", result.nodes().get(0).entityName());
        assertTrue(result.edges().isEmpty());
        assertTrue(result.nodes().stream()
                .noneMatch(node -> node.entityName().startsWith("Sunset")));
    }

    /**
     * 场景：图片块携带 ctx 定形主实体名，块内抽出 2 个文本实体与 1 条普通关系。
     * 预期：每个文本实体→主实体端点对恰产出一条 10.0 全额 belongs_to 记录
     * （单路径注入不重复计权），sourceIds 只含本块真实主键；普通关系边 1.0 不受影响。
     */
    @Test
    void should_injectSingleBelongsToRecordPerPair_when_extract_given_imageChunkWithTwoTextEntities() {
        // given：图片块（ctx 主实体名 "Lake (image)"），块内抽出 2 个文本实体与 1 条普通关系
        ChunkVO chunk = new ChunkVO(2, "text body", 10,
                new ContentBlockVO(ContentBlockVO.TYPE_IMAGE, "raw image text", Map.of()));
        String response = entityRow("Alice", "Person", "Alice desc.") + "\n"
                + entityRow("Bob", "Person", "Bob desc.") + "\n"
                + relationRow("Alice", "Bob", "knows", "Alice knows Bob.") + "\n"
                + COMPLETION_DELIMITER;
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(response, 10));

        // when
        EntityExtractionResult result = service.extract(contextWithPrimaryEntityName("Lake (image)"),
                List.of(persisted(chunk)));

        // then：2 条 belongs_to 边各恰 1 条 10.0 记录、来源为本块真实主键 1002；普通关系边 1.0 不受影响
        List<RelationEdge> aliceBelongs = edgesByPair(result, "Alice", "Lake (image)");
        assertEquals(1, aliceBelongs.size());
        assertEquals(BELONGS_TO_WEIGHT, aliceBelongs.get(0).properties().weight(), 0.001);
        assertEquals(List.of(1002L), aliceBelongs.get(0).properties().sourceIds());
        List<RelationEdge> bobBelongs = edgesByPair(result, "Bob", "Lake (image)");
        assertEquals(1, bobBelongs.size());
        assertEquals(BELONGS_TO_WEIGHT, bobBelongs.get(0).properties().weight(), 0.001);
        assertEquals(List.of(1002L), bobBelongs.get(0).properties().sourceIds());
        List<RelationEdge> knowsEdges = edgesByPair(result, "Alice", "Bob");
        assertEquals(1, knowsEdges.size());
        assertEquals(1.0, knowsEdges.get(0).properties().weight(), 0.001);
        assertEquals(3, result.edges().size());
    }

    /**
     * 场景：图片块携带 ctx 定形主实体名，但 LLM 未抽出任何文本实体。
     * 预期：主实体节点不在本阶段构造（唯一构造点在摄入管线）；无归属来源实体则零边，产物为空。
     */
    @Test
    void should_notConstructPrimaryEntityNode_when_extract_given_multimodalChunkWithoutTextEntities() {
        // given
        ChunkVO chunk = new ChunkVO(3, "text body", 10,
                new ContentBlockVO(ContentBlockVO.TYPE_IMAGE, "image placeholder text", Map.of()));
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(COMPLETION_DELIMITER, 1));

        // when
        EntityExtractionResult result = service.extract(contextWithPrimaryEntityName("Lake (image)"),
                List.of(persisted(chunk)));

        // then
        assertTrue(result.nodes().isEmpty());
        assertTrue(result.edges().isEmpty());
    }

    /**
     * 场景：表格块无主实体名（caption/兜底命名链已删除）且块内无文本实体。
     * 预期：WARN 跳过注入，产物完全为空——既无「类型-文档ID-块序号」兜底名节点，也不产出任何边。
     */
    @Test
    void should_produceNothing_when_extract_given_tableChunkWithoutPrimaryNameAndTextEntities() {
        // given
        ChunkVO chunk = new ChunkVO(2, "text body", 10,
                new ContentBlockVO(ContentBlockVO.TYPE_TABLE, "table text", Map.of()));
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(COMPLETION_DELIMITER, 1));

        // when
        EntityExtractionResult result = service.extract(defaultContext(), List.of(persisted(chunk)));

        // then
        assertTrue(result.nodes().isEmpty());
        assertTrue(result.edges().isEmpty());
    }

    /**
     * 场景：图片块 meta 含题注，同时上下文携带定形主实体名（全链路只消费该值）。
     * 预期：belongs_to 边端点恒为 ctx 定形主实体名，caption 名不进入任何节点/边端点；
     * 主实体节点仍不在本阶段构造。
     */
    @Test
    void should_useOnlyContextPrimaryName_when_extract_given_imageChunkWithCaptionAndPrimaryName() {
        // given
        ChunkVO chunk = new ChunkVO(1, "text body", 10,
                new ContentBlockVO(ContentBlockVO.TYPE_IMAGE, "raw image text",
                        Map.of("image_caption", "Sunset over the lake")));
        String response = entityRow("Alice", "Person", "Alice desc.") + "\n" + COMPLETION_DELIMITER;
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(response, 10));

        // when
        EntityExtractionResult result = service.extract(contextWithPrimaryEntityName("Lake Photo (image)"),
                List.of(persisted(chunk)));

        // then：仅文本实体入节点表；唯一边指向 ctx 定形名，权重与 belongs_to 约定一致
        assertEquals(1, result.nodes().size());
        assertEquals("Alice", result.nodes().get(0).entityName());
        assertTrue(result.nodes().stream()
                .noneMatch(node -> node.entityName().startsWith("Sunset")));
        assertEquals(1, result.edges().size());
        RelationEdge edge = result.edges().get(0);
        assertEquals("Alice", edge.sourceName());
        assertEquals("Lake Photo (image)", edge.targetName());
        assertEquals(BELONGS_TO_WEIGHT, edge.properties().weight(), 0.001);
        assertEquals(List.of("belongs_to", "contained_in", "part_of"), edge.properties().keywords());
    }

    /**
     * 场景：同一无向端点对、同一 chunk 来源先收到权重 1.0 的模型关系行、
     * 后收到权重 10.0 的 sidecar belongs_to 注入记录（同 (端点对, 来源) 重复记录）。
     * 预期：该来源只产出一条贡献记录且取最大权重 10.0（后到更大者生效）；
     * keywords 为两次记录并集、description 取长不变。
     */
    @Test
    void should_takeMaxWeight_when_extract_given_samePairSameSourceDuplicateRecords() {
        // given：图片块（ctx 主实体名 "Lake (image)"），模型输出指向主实体名的普通关系行（默认权重 1.0）
        ChunkVO chunk = new ChunkVO(1, "text body", 10,
                new ContentBlockVO(ContentBlockVO.TYPE_IMAGE, "raw image text", Map.of()));
        String response = entityRow("Alice", "Person", "Alice desc.") + "\n"
                + relationRow("Alice", "Lake (image)", "depicts", "Alice appears in the photo.") + "\n"
                + COMPLETION_DELIMITER;
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(response, 10));

        // when
        EntityExtractionResult result = service.extract(contextWithPrimaryEntityName("Lake (image)"),
                List.of(persisted(chunk)));

        // then：同来源归一为一条记录、权重取两轮较大者 10.0；关键词并集、描述取更长的 belongs 描述
        List<RelationEdge> edges = edgesByPair(result, "Alice", "Lake (image)");
        assertEquals(1, edges.size());
        assertEquals(1, result.edges().size());
        RelationEdge edge = edges.get(0);
        assertEquals(BELONGS_TO_WEIGHT, edge.properties().weight(), 0.001);
        assertEquals(List.of(1001L), edge.properties().sourceIds());
        assertEquals(List.of("belongs_to", "contained_in", "depicts", "part_of"), edge.properties().keywords());
        assertEquals("Entity Alice belongs to Lake (image)", edge.properties().description());
    }

    /**
     * 场景：两个 chunk 分别输出 (Alice, Bob) 与 (Bob, Alice) 反序端点。
     * 预期：产出边的 source/target 恒为字典序 (Alice, Bob)，与模型输出的源/目标先后顺序无关。
     */
    @Test
    void should_sameStoredDirection_when_extract_given_modelEmitsReversedEndpoints() {
        // given：首轮正序、次轮反序输出同一端点对
        String first = entityRow("Alice", "Person", "Alice desc.") + "\n"
                + entityRow("Bob", "Person", "Bob desc.") + "\n"
                + relationRow("Alice", "Bob", "knows", "First direction desc.") + "\n"
                + COMPLETION_DELIMITER;
        String second = relationRow("Bob", "Alice", "friend", "Reversed direction desc.") + "\n"
                + COMPLETION_DELIMITER;
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(first, 10))
                .thenReturn(new LlmChatResult(second, 10));

        // when
        EntityExtractionResult result = service.extract(defaultContext(),
                List.of(textChunk("chunk one"), persisted(new ChunkVO(2, "chunk two", 10, null))));

        // then：两条来源记录各出一边，落库方向恒为字典序 (Alice, Bob)
        List<RelationEdge> edges = edgesByPair(result, "Alice", "Bob");
        assertEquals(2, edges.size());
        for (RelationEdge edge : edges) {
            assertEquals("Alice", edge.sourceName());
            assertEquals("Bob", edge.targetName());
        }
    }

    /**
     * 场景：多模态块 sidecar 抽取（块内仅抽出文本实体、无普通关系）。
     * 预期：抽取结果节点不含主实体节点，边全部为文本实体→主实体的 belongs_to 边
     * （权重 10.0、来源为该块 chunkId）。
     */
    @Test
    void should_onlyProduceBelongsToEdges_when_extract_given_multimodalSidecarChunk() {
        // given：表格块（ctx 定形主实体名），模型仅抽出两个文本实体
        ChunkVO chunk = new ChunkVO(1, "text body", 10,
                new ContentBlockVO(ContentBlockVO.TYPE_TABLE, "raw table text", Map.of()));
        String response = entityRow("Alice", "Person", "Alice desc.") + "\n"
                + entityRow("Bob", "Person", "Bob desc.") + "\n"
                + COMPLETION_DELIMITER;
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(response, 10));

        // when
        EntityExtractionResult result = service.extract(contextWithPrimaryEntityName("Q3 Sales Table (table)"),
                List.of(persisted(chunk)));

        // then：节点仅两个文本实体，主实体名不出现在节点列表；两条 belongs_to 边均为 10.0 全额
        assertEquals(2, result.nodes().size());
        assertTrue(result.nodes().stream()
                .noneMatch(node -> node.entityName().equals("Q3 Sales Table (table)")));
        assertEquals(2, result.edges().size());
        for (RelationEdge edge : result.edges()) {
            assertEquals("Q3 Sales Table (table)", edge.targetName());
            assertEquals(BELONGS_TO_WEIGHT, edge.properties().weight(), 0.001);
            assertEquals(List.of(1001L), edge.properties().sourceIds());
            assertEquals(List.of("belongs_to", "contained_in", "part_of"), edge.properties().keywords());
        }
        assertEquals("Entity Alice belongs to Q3 Sales Table (table)",
                edgeByPair(result, "Alice", "Q3 Sales Table (table)").properties().description());
    }

    @Test
    void should_keepSucceededChunks_when_extract_given_oneChunkFails() {
        // given：两个 chunk，第一块 LLM 抛异常，第二块正常
        String good = entityRow("Bob", "Person", "Bob desc.") + "\n" + COMPLETION_DELIMITER;
        when(llmClient.chat(any(LlmChatRequest.class)))
                .thenThrow(new RuntimeException("LLM 暂时不可用"))
                .thenReturn(new LlmChatResult(good, 10));

        // when
        EntityExtractionResult result = service.extract(defaultContext(),
                List.of(textChunk("chunk one"), persisted(new ChunkVO(2, "chunk two", 10, null))));

        // then：失败块被跳过，成功块数据保留
        assertEquals(1, result.nodes().size());
        assertEquals("Bob", result.nodes().get(0).entityName());
        verify(llmClient, times(2)).chat(any(LlmChatRequest.class));
    }

    // ------------------------------------------------------------------ 扇出中断完整性（change fix-rag-concurrency-integrity / D1、D2）

    /**
     * 场景（spec fanout-interruption-integrity）：任务在等待并发闸门时被中断
     * （典型为执行器停机宽限期耗尽后 shutdownNow 中断等待者）。
     * 预期：中断计入阶段失败——抽取以 {@link IllegalStateException} 上抛、异常 cause 为
     * 真实中断原因（留痕可与单块业务异常区分）；MUST NOT 以不完整快照继续聚合
     * （异常上抛即证明未走到聚合出口）；被中断条目不发起任何 LLM 调用。
     */
    @Test
    void should_throw_when_extract_given_taskInterruptedWhileAwaitingGate() {
        // given：同步执行器使任务体在当前线程内执行；预置本线程中断标记，
        // gate.acquire() 立即抛 InterruptedException（等待路径的中断检查与许可余量无关）
        Thread.currentThread().interrupt();
        IllegalStateException thrown;
        try {
            // when
            thrown = assertThrows(IllegalStateException.class, () -> service.extract(defaultContext(),
                    List.of(textChunk("chunk one"), persisted(new ChunkVO(2, "chunk two", 10, null)))));
        } finally {
            // 任务体恢复中断标记后返回，测试线程须自行清场（防污染后续用例）
            assertTrue(Thread.interrupted(), "抽取被中断收口后中断标记应仍在，测试线程清场");
        }

        // then：阶段失败以真实中断为原因；聚合与 LLM 调用均未发生
        assertInstanceOf(InterruptedException.class, thrown.getCause(),
                "阶段失败 MUST 携带真实中断原因（cause 链可归因）");
        verifyNoInteractions(llmClient);
    }

    /**
     * 场景（回归护栏 / D1 分流）：单块业务异常（模型失败）而非中断。
     * 预期：业务异常仍走既有「告警跳过、允许部分成功」路径、MUST NOT 计入中断收集器，
     * 其余块正常聚合，文档判定成功（守护既有部分成功语义不被改坏）。
     */
    @Test
    void should_stillSucceed_when_extract_given_singleChunkBusinessFailure() {
        // given：第一块 LLM 抛业务异常，第二块正常返回
        String good = entityRow("Bob", "Person", "Bob desc.") + "\n" + COMPLETION_DELIMITER;
        when(llmClient.chat(any(LlmChatRequest.class)))
                .thenThrow(new IllegalStateException("模型响应非法"))
                .thenReturn(new LlmChatResult(good, 10));

        // when：不抛异常（业务失败 ≠ 中断，阶段判定成功）
        EntityExtractionResult result = service.extract(defaultContext(),
                List.of(textChunk("chunk one"), persisted(new ChunkVO(2, "chunk two", 10, null))));

        // then：失败块跳过、成功块正常聚合且溯源回填真实分块主键
        assertEquals(1, result.nodes().size());
        assertEquals("Bob", result.nodes().get(0).entityName());
        assertEquals(List.of(1002L), result.nodes().get(0).properties().sourceIds());
        verify(llmClient, times(2)).chat(any(LlmChatRequest.class));
    }

    /**
     * 场景（D2）：提交循环中途命中取消。
     * 预期：命中取消时先等待已提交任务收口再上抛——取消异常抛出后，已提交任务已完整
     * 执行完毕（MUST NOT 留「无人等待的孤儿扇出」）；未提交的分块不再提交、不发起调用。
     */
    @Test
    void should_awaitSubmittedTasks_when_extract_given_cancelledMidLoop() throws Exception {
        // given：测试侧惰性执行器——扇出任务仅被捕获不执行，使「等待收口」可被外部观测
        // （测试替身不改生产线程口径：生产扇出统一走受管执行器，此处仅模拟其异步性）
        List<Runnable> capturedTasks = Collections.synchronizedList(new ArrayList<>());
        EntityExtractionService lazyService = new EntityExtractionService(llmClient, capturedTasks::add,
                new ObjectMapper(), chunkExtractCacheRepository, llmCacheRepository);
        // 取消检查器：入口第 1 次与首块提交前的第 2 次放行，第二块提交前（第 3 次）命中取消
        AtomicInteger cancellationChecks = new AtomicInteger();
        BooleanSupplier cancelOnThirdCheck = () -> cancellationChecks.getAndIncrement() >= 2;
        String response = entityRow("Alice", "Person", "Alice desc.") + "\n" + COMPLETION_DELIMITER;
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(response, 10));
        AtomicReference<Throwable> thrownByExtractor = new AtomicReference<>();
        // 后台线程发起抽取：仅为可观测「抽取线程尚未返回」，收口前不得提前返回
        Thread extractor = Thread.ofVirtual().start(() -> {
            try {
                lazyService.extract(contextWithCancel(cancelOnThirdCheck),
                        List.of(textChunk("chunk one"), persisted(new ChunkVO(2, "chunk two", 10, null))));
            } catch (Throwable t) {
                thrownByExtractor.set(t);
            }
        });
        awaitCapturedTask(capturedTasks);

        // then①：恰首块任务被提交（第二块提交前命中取消）；任务未收口时抽取线程不得返回
        assertEquals(1, capturedTasks.size(), "命中取消后 MUST NOT 再提交剩余分块");
        TimeUnit.MILLISECONDS.sleep(100L);
        assertTrue(extractor.isAlive(), "取消命中时抽取线程应仍在等待已提交任务收口");

        // when：手动执行被捕获任务，模拟其自然收口
        capturedTasks.get(0).run();
        extractor.join(ASYNC_TIMEOUT_MILLIS);

        // then②：收口后以取消异常上抛；已提交任务确已完整执行（恰一次 LLM 调用）
        assertFalse(extractor.isAlive(), "任务收口后抽取线程应结束（先收口再上抛）");
        assertInstanceOf(IllegalStateException.class, thrownByExtractor.get());
        verify(llmClient, times(1)).chat(any(LlmChatRequest.class));
    }

    @Test
    void should_throwIseWithoutLlmCall_when_extract_given_cancelledAtEntry() {
        // given：入口前已取消
        BooleanSupplier cancelled = () -> true;

        // when & then
        assertThrows(IllegalStateException.class,
                () -> service.extract(contextWithCancel(cancelled), List.of(textChunk("body"))));
        verify(llmClient, never()).chat(any(LlmChatRequest.class));
    }

    @Test
    void should_abortBeforeNextChunk_when_extract_given_cancelledAfterFirstChunk() {
        // given：首块响应返回时置取消标志，第二块提交前应中断
        AtomicBoolean cancelled = new AtomicBoolean(false);
        String response = entityRow("Alice", "Person", "Alice desc.") + "\n" + COMPLETION_DELIMITER;
        when(llmClient.chat(any(LlmChatRequest.class))).thenAnswer(invocation -> {
            cancelled.set(true);
            return new LlmChatResult(response, 10);
        });

        // when & then：同步执行器下首块已完成，提交第二块前抛取消异常
        assertThrows(IllegalStateException.class, () -> service.extract(contextWithCancel(cancelled::get),
                List.of(textChunk("chunk one"), persisted(new ChunkVO(2, "chunk two", 10, null)))));
        verify(llmClient, times(1)).chat(any(LlmChatRequest.class));
    }

    @Test
    void should_applyDefaultsAndRejectInvalidIds_when_construct_given_blankOrNegativeValues() {
        // given & when：空白语言/负补漏轮数/非正并发度回退默认
        EntityExtractionContext ctx = new EntityExtractionContext(KB_ID, DOC_ID, PROFILE_ID, "  ",
                null, false, -3, 0, FILE_PATH, null, null);

        // then
        assertEquals("en", ctx.language());
        assertEquals(0, ctx.maxGleaning());
        assertEquals(EntityExtractionContext.DEFAULT_CONCURRENCY, ctx.concurrency());
        assertTrue(ctx.entityTypes().isEmpty());
        assertFalse(ctx.isCancelled());

        // given & then：库/文档身份缺失快速失败
        assertThrows(IllegalArgumentException.class, () -> new EntityExtractionContext(
                null, DOC_ID, PROFILE_ID, "en", List.of(), false, 0, 4, FILE_PATH, null, null));
        assertThrows(IllegalArgumentException.class, () -> new EntityExtractionContext(
                KB_ID, null, PROFILE_ID, "en", List.of(), false, 0, 4, FILE_PATH, null, null));
        assertThrows(IllegalArgumentException.class, () -> new EntityExtractionContext(
                KB_ID, DOC_ID, " ", "en", List.of(), false, 0, 4, FILE_PATH, null, null));
    }

    /**
     * 场景：抽取调用经缓存客户端执行时的分区归位断言。
     * 预期：{@link EntityExtractionService} 构造的发往 {@link LlmClient} 的请求一律携带
     * {@link CacheType#EXTRACT} 缓存分区（摄入期抽取产物独立分区、口径显式化）。
     */
    @Test
    void should_sendRequestWithExtractCacheType_when_extract_given_validInput() {
        // given：单块文本模式响应，一次抽取触发一次 LLM 调用
        String response = entityRow("Alice", "Person", "Alice desc.") + "\n" + COMPLETION_DELIMITER;
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(response, 10));

        // when
        service.extract(defaultContext(), List.of(textChunk("body")));

        // then：抽取请求挂 EXTRACT 分区，不误入 ENTITY_DESC / ANSWER
        ArgumentCaptor<LlmChatRequest> captor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(llmClient).chat(captor.capture());
        assertEquals(CacheType.EXTRACT, captor.getValue().cacheType(),
                "实体抽取调用必须挂 EXTRACT 缓存分区（首轮与续抽同一单点）");
    }

    // ------------------------------------------------------------------ 缓存归属标识透传（spec extract-cache-attribution / R1）

    /**
     * 场景：单块文本抽取（无续抽）。
     * 预期：请求携带该分块的真实主键作为归因标识（仅用于缓存归属登记，不参与缓存键计算）。
     */
    @Test
    void should_carryChunkAttribution_when_extract_given_textChunk() {
        // given：一次抽取触发一次 LLM 调用（textChunk 的绑定主键为 1001）
        String response = entityRow("Alice", "Person", "Alice desc.") + "\n" + COMPLETION_DELIMITER;
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(response, 10));

        // when
        service.extract(defaultContext(), List.of(textChunk("body")));

        // then：归因标识为绑定的真实分块主键，而非序号占位
        ArgumentCaptor<LlmChatRequest> captor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(llmClient).chat(captor.capture());
        assertEquals(1001L, captor.getValue().attributionChunkId().longValue(),
                "抽取请求必须携带该分块的真实主键作为归因标识");
    }

    /**
     * 场景：首轮抽取 + 一轮补漏续抽（两轮提示词不同 ⇒ 缓存键不同）。
     * 预期：两轮请求均携带同一分块标识，使该分块与各轮缓存行分别建立归属关系。
     */
    @Test
    void should_carrySameChunkAttributionOnEveryRound_when_extract_given_gleaning() {
        // given：首轮与续抽响应不同（续抽轮产出新记录）
        String first = entityRow("Alice", "Person", "Short.") + "\n";
        String gleaned = entityRow("Bob", "Person", "Bob is an engineer.") + "\n";
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(first, 10))
                .thenReturn(new LlmChatResult(gleaned, 10));

        // when
        service.extract(contextWithGleaning(1), List.of(textChunk("body")));

        // then：两轮同一分块标识，提示词不同（各自对应一条归属）
        ArgumentCaptor<LlmChatRequest> captor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(llmClient, times(2)).chat(captor.capture());
        captor.getAllValues().forEach(request ->
                assertEquals(1001L, request.attributionChunkId().longValue(),
                        "首轮与续抽必须携带同一分块标识"));
        assertFalse(captor.getAllValues().get(0).userPrompt().equals(captor.getAllValues().get(1).userPrompt()),
                "两轮提示词不同 ⇒ 缓存键不同 ⇒ 同一分块产生多条归属");
    }

    // ------------------------------------------------------------------ 实体名归一接入（spec R1）

    /**
     * 场景：同一实体的引号与空格变体分别出现在多行抽取输出中。
     * 预期：全部归一为同一身份键、归并单节点，描述按既有同名合并语义取最长。
     */
    @Test
    void should_mergeIntoSingleNode_when_extract_given_quoteAndSpaceVariants() {
        // given：三行分别输出 `"星辰科技"`、裸名、中文间空格变体
        String response = entityRow("\"星辰科技\"", "Organization", "desc one.") + "\n"
                + entityRow("星辰科技", "Organization", "A longer organization desc.") + "\n"
                + entityRow("星辰 科技", "Organization", "short.") + "\n"
                + COMPLETION_DELIMITER;
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(response, 10));

        // when
        EntityExtractionResult result = service.extract(defaultContext(), List.of(textChunk("body")));

        // then：单节点归一，长描述胜出
        assertEquals(1, result.nodes().size());
        assertEquals("星辰科技", result.nodes().get(0).entityName());
        assertEquals("A longer organization desc.", result.nodes().get(0).properties().description());
    }

    /**
     * 场景：短纯数字实体名及其引用关系（spec「短纯数字名丢弃且引用关系连带处理」）。
     * 预期：`3.14` 实体不入库；以其为端点的关系行整体丢弃（与类型过滤连带语义一致）。
     */
    @Test
    void should_dropNumericNameAndReferencingRelation_when_extract_given_pureNumericEntity() {
        // given：纯数字实体行 + 以它为端点的关系行 + 正常实体
        String response = entityRow("3.14", "Other", "pi value.") + "\n"
                + entityRow("星辰科技", "Organization", "company desc.") + "\n"
                + relationRow("3.14", "星辰科技", "约等于", "numeric relation desc.") + "\n"
                + COMPLETION_DELIMITER;
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(response, 10));

        // when
        EntityExtractionResult result = service.extract(defaultContext(), List.of(textChunk("body")));

        // then：数字名实体与其端点无效的关系均不产出
        assertEquals(1, result.nodes().size());
        assertEquals("星辰科技", result.nodes().get(0).entityName());
        assertTrue(result.edges().isEmpty(), "无效端点的关系行必须连带整体丢弃");
    }

    /**
     * 场景：关系两端点为同一实体的引号变体（归一后同名）。
     * 预期：自环判定基于归一后的值，该关系行被丢弃。
     */
    @Test
    void should_dropRelation_when_extract_given_endpointsConvergeAfterNormalize() {
        // given：带中文弯引号的源与裸名目标实为同一实体
        String response = entityRow("星辰科技", "Organization", "company desc.") + "\n"
                + relationRow("“星辰科技”", "星辰科技", "别名", "same entity self loop.") + "\n"
                + COMPLETION_DELIMITER;
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(response, 10));

        // when
        EntityExtractionResult result = service.extract(defaultContext(), List.of(textChunk("body")));

        // then：归一后端点相同，按自环丢弃
        assertEquals(1, result.nodes().size());
        assertTrue(result.edges().isEmpty(), "归一后同名端点必须判定为自环并丢弃");
    }

    // ------------------------------------------------------------------ 文本模式修复层（spec R2）

    /**
     * 场景：恰 5 字段且 entity 前缀的错误前缀关系行（spec「错误前缀关系行恢复为关系」）。
     * 预期：按关系行解析（源/目标/关键词/描述各归其位），不产出「类型=关系对端」的垃圾实体。
     */
    @Test
    void should_recoverAsRelation_when_parseTextResponse_given_misPrefixedRelationRow() {
        // given：spec 样例原文——entity 前缀、5 字段的真实关系行
        String response = "entity" + TUPLE_DELIMITER + "张三" + TUPLE_DELIMITER + "星辰科技"
                + TUPLE_DELIMITER + "雇佣" + TUPLE_DELIMITER + "张三任职于星辰科技" + "\n"
                + COMPLETION_DELIMITER;
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(response, 10));

        // when
        EntityExtractionResult result = service.extract(defaultContext(), List.of(textChunk("body")));

        // then：恢复为关系边（字典序端点），零实体产出
        assertTrue(result.nodes().isEmpty(), "错误前缀行不得产出垃圾实体");
        assertEquals(1, result.edges().size());
        RelationEdge edge = result.edges().get(0);
        assertEquals("张三", edge.sourceName());
        assertEquals("星辰科技", edge.targetName());
        assertEquals(List.of("雇佣"), edge.properties().keywords());
        assertEquals("张三任职于星辰科技", edge.properties().description());
    }

    /**
     * 场景：模型在同一行以元组分隔符串联「实体+实体+关系」三条记录（spec 粘连重切场景）。
     * 预期：解析前重切为三条独立记录并按各自前缀正确解析，字段语义不变。
     */
    @Test
    void should_resplitGluedRecords_when_parseTextResponse_given_threeRecordsInOneLine() {
        // given：单行三条记录（两实体一关系）用元组分隔符串联
        String response = "entity" + TUPLE_DELIMITER + "张三" + TUPLE_DELIMITER + "Person"
                + TUPLE_DELIMITER + "张三desc"
                + TUPLE_DELIMITER + "entity" + TUPLE_DELIMITER + "星辰科技" + TUPLE_DELIMITER + "Organization"
                + TUPLE_DELIMITER + "公司desc"
                + TUPLE_DELIMITER + "relation" + TUPLE_DELIMITER + "张三" + TUPLE_DELIMITER + "星辰科技"
                + TUPLE_DELIMITER + "雇佣" + TUPLE_DELIMITER + "任职关系desc"
                + "\n" + COMPLETION_DELIMITER;
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(response, 10));

        // when
        EntityExtractionResult result = service.extract(defaultContext(), List.of(textChunk("body")));

        // then：两实体一关系各归其位，字段语义与逐行输出完全一致
        assertEquals(2, result.nodes().size());
        assertEquals("张三desc", nodeByName(result, "张三").properties().description());
        assertEquals("公司desc", nodeByName(result, "星辰科技").properties().description());
        assertEquals(1, result.edges().size());
        assertEquals("任职关系desc", result.edges().get(0).properties().description());
    }

    /**
     * 场景：元组分隔符三形态残字 + 自由文本相近序列（spec「分隔符残字修复」）。
     * 预期：双核粘连、转义污染、紧邻缺核均被修复还原；两侧非紧邻（有空格隔开）的
     * {@code <|>} 自由文本不被误改写。
     */
    @Test
    void should_fixDelimiterCorruptionForms_when_parseTextResponse_given_gluedEscapeAndMissingCore() {
        // given：四行分别覆盖双核（<|##|>）、转义（<|\#|>）、紧邻缺核（entity<|>）、自由文本非紧邻
        String response = "entity" + TUPLE_DELIMITER + "张三<|##|>Person" + TUPLE_DELIMITER + "d1" + "\n"
                + "entity<|\\#|>李四" + TUPLE_DELIMITER + "Person" + TUPLE_DELIMITER + "d2" + "\n"
                + "entity<|>孙七" + TUPLE_DELIMITER + "Person" + TUPLE_DELIMITER + "d3" + "\n"
                + "entity" + TUPLE_DELIMITER + "公式" + TUPLE_DELIMITER + "Concept"
                + TUPLE_DELIMITER + "公式 a <|> b 成立" + "\n"
                + COMPLETION_DELIMITER;
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(response, 10));

        // when
        EntityExtractionResult result = service.extract(defaultContext(), List.of(textChunk("body")));

        // then：行①双核残字还原后张三入库（原形态会产出名称含残字的错误切分）
        assertEquals(4, result.nodes().size());
        assertEquals("Person", nodeByName(result, "张三").properties().entityType());
        assertEquals("Person", nodeByName(result, "李四").properties().entityType());
        assertEquals("Person", nodeByName(result, "孙七").properties().entityType());
        // 自由文本中的非紧邻 <|> 未被误改写，描述原样保留
        assertEquals("公式 a <|> b 成立", nodeByName(result, "公式").properties().description());
    }

    /**
     * 场景（converge-rag-hot-path-object-creation / 2.3①）：修复层删除「分隔符核心小写二次执行」
     * 分支的前提——核心与其 lowercase 形态等价（{@code #} 无大小写形态）。
     * 预期：以生产代码同口径（{@code tuple_delimiter[2:-2]} 截取）导出的核心经
     * {@code StringUtils.lowerCase} 后与原形态逐字相等；未来若改动
     * {@link EntityExtractionService#TUPLE_DELIMITER} 引入含大小写字符的核心，
     * 本断言即失败，防止已删除的二次执行分支变成静默缺陷。
     */
    @Test
    void should_keepDelimiterCoreCaseInvariant_when_fixDelimiterCorruption_given_lowercaseEquivalencePremise() {
        // given：与生产代码 DELIMITER_CORE 同口径的核心截取
        String core = StringUtils.substring(TUPLE_DELIMITER, 2, TUPLE_DELIMITER.length() - 2);

        // when：核心取小写形态（上游守卫比较的两侧）
        String coreLower = StringUtils.lowerCase(core, Locale.ROOT);

        // then：两形态逐字相等——删除 lowercase 二次执行分支的前提成立
        assertEquals(core, coreLower,
                "元组分隔符核心必须无大小写形态，否则修复层缺失小写二次执行将静默失效");
    }

    /**
     * 场景：残字与错误前缀叠加形态（紧邻缺核 + 恰 5 字段 entity 前缀的关系行）。
     * 预期：先残字修复还原分隔符、再错前缀改判恢复，两修复层叠加生效产出正确关系。
     */
    @Test
    void should_recoverRelationWithBothRepairs_when_parseTextResponse_given_missingCorePlusWrongPrefix() {
        // given：首分隔符缺核（entity<|>），整行实为恰 5 字段的错误前缀关系行
        String response = "entity<|>甲" + TUPLE_DELIMITER + "乙" + TUPLE_DELIMITER + "关联"
                + TUPLE_DELIMITER + "甲乙关联描述" + "\n"
                + COMPLETION_DELIMITER;
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(response, 10));

        // when
        EntityExtractionResult result = service.extract(defaultContext(), List.of(textChunk("body")));

        // then：修复后按关系解析（字典序端点 甲~乙），零实体产出
        assertTrue(result.nodes().isEmpty());
        assertEquals(1, result.edges().size());
        RelationEdge edge = result.edges().get(0);
        assertEquals("乙", edge.sourceName(), "「乙」(U+4E59) 字典序先于「甲」(U+7532)");
        assertEquals("甲", edge.targetName());
        assertEquals(List.of("关联"), edge.properties().keywords());
        assertEquals("甲乙关联描述", edge.properties().description());
    }

    /**
     * 场景：entity 前缀超过 5 字段的行（描述误含分隔符）。
     * 预期：维持既有 joinTail 宽容按实体解析、描述以分隔符回连（不改判、不丢弃）。
     */
    @Test
    void should_keepJoinTailLeniency_when_parseTextResponse_given_entityRowOverFiveFields() {
        // given：6 字段 entity 行（描述被误切成三段）
        String response = "entity" + TUPLE_DELIMITER + "宽描述" + TUPLE_DELIMITER + "Concept"
                + TUPLE_DELIMITER + "前半" + TUPLE_DELIMITER + "中段" + TUPLE_DELIMITER + "后半" + "\n"
                + COMPLETION_DELIMITER;
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(response, 10));

        // when
        EntityExtractionResult result = service.extract(defaultContext(), List.of(textChunk("body")));

        // then：实体入库且描述回连保真
        assertEquals(1, result.nodes().size());
        assertEquals("前半" + TUPLE_DELIMITER + "中段" + TUPLE_DELIMITER + "后半",
                result.nodes().get(0).properties().description());
        assertTrue(result.edges().isEmpty(), ">5 字段 entity 行不满足恰 5 字段改判条件");
    }

    /**
     * 场景：JSON 输出模式下混入文本模式脏行（spec「JSON 模式解析不受影响」）。
     * 预期：修复层不介入——错前缀形态的文本行不会被改判恢复，仅 JSON 对象被解析。
     */
    @Test
    void should_notApplyRepairLayer_when_extract_given_jsonModeWithTextNoise() {
        // given：JSON 模式，响应前置一行恰 5 字段 entity 脏文本（文本模式下会被改判为关系）
        EntityExtractionContext ctx = new EntityExtractionContext(KB_ID, DOC_ID, PROFILE_ID, "zh",
                List.of(), true, 0, 4, FILE_PATH, null, null);
        String response = "entity" + TUPLE_DELIMITER + "甲" + TUPLE_DELIMITER + "乙"
                + TUPLE_DELIMITER + "丙" + TUPLE_DELIMITER + "丁" + "\n"
                + "{\"entities\": [{\"name\": \"JSON实体\", \"type\": \"Person\", \"description\": \"d\"}],"
                + " \"relationships\": []}" + "\n" + COMPLETION_DELIMITER;
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(response, 10));

        // when
        EntityExtractionResult result = service.extract(ctx, List.of(textChunk("body")));

        // then：仅 JSON 实体入库；文本脏行未被修复层消化（零边、无甲/乙实体）
        assertEquals(1, result.nodes().size());
        assertEquals("JSON实体", result.nodes().get(0).entityName());
        assertTrue(result.edges().isEmpty());
    }

    /**
     * 场景：续抽轮响应全是修复不了的垃圾记录（spec「修复不影响续抽停止判定」）。
     * 预期：零新增即停以修复后记录为准，该轮后即终止（总调用 2 次，而非跑满 3 次）。
     */
    @Test
    void should_stopOnZeroNewRecords_when_extract_given_gleaningRoundFullOfJunk() {
        // given：首轮有效实体且无完成信号；续抽轮为无法修复的垃圾行（上限 2 轮）
        String first = entityRow("Alice", "Person", "Alice desc.") + "\n";
        String junk = "this line is garbage\n"
                + "entity" + TUPLE_DELIMITER + "OnlyTwoParts\n";
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(new LlmChatResult(first, 10))
                .thenReturn(new LlmChatResult(junk, 10));

        // when
        EntityExtractionResult result = service.extract(contextWithGleaning(2), List.of(textChunk("body")));

        // then：修复层未使垃圾轮虚增记录，续抽一轮后零新增终止
        verify(llmClient, times(2)).chat(any(LlmChatRequest.class));
        assertEquals(1, result.nodes().size());
        assertEquals("Alice", result.nodes().get(0).entityName());
    }

    /**
     * 场景：双套真实例句——语言全名 {@code Chinese} 的库执行文本模式抽取。
     * 预期：{@link LlmClient} 收到的系统提示词含中文例句集（张三/星辰科技），
     * 不出现英文人名地名例句。
     */
    @Test
    void should_injectChineseTextExamples_when_extract_given_chineseLanguageTextMode() {
        // given
        when(llmClient.chat(any(LlmChatRequest.class)))
                .thenReturn(new LlmChatResult(COMPLETION_DELIMITER, 1));

        // when
        service.extract(contextWithLanguageAndMode("Chinese", false), List.of(textChunk("body")));

        // then
        String systemPrompt = capturedFirstSystemPrompt();
        assertTrue(systemPrompt.contains("张三"), "Chinese 库应注入中文例句（张三）");
        assertTrue(systemPrompt.contains("星辰科技"), "Chinese 库中文例句应含星辰科技实体样例");
        assertTrue(systemPrompt.contains("雇佣,研究"), "中文例句关键词应为英文逗号分隔的可切分形态");
        assertFalse(systemPrompt.contains("Alice"), "Chinese 库不应注入英文人名例句（防输出语言拽偏）");
    }

    /**
     * 场景：语言全名 {@code Japanese}（非中文）的库执行文本模式抽取。
     * 预期：{@link LlmClient} 收到的系统提示词含英文例句集（Alice/ACME），
     * 与 en 模板套指令语言一致。
     */
    @Test
    void should_injectEnglishTextExamples_when_extract_given_japaneseLanguageTextMode() {
        // given
        when(llmClient.chat(any(LlmChatRequest.class)))
                .thenReturn(new LlmChatResult(COMPLETION_DELIMITER, 1));

        // when
        service.extract(contextWithLanguageAndMode("Japanese", false), List.of(textChunk("body")));

        // then
        String systemPrompt = capturedFirstSystemPrompt();
        assertTrue(systemPrompt.contains("Alice"), "非 Chinese 语言库应注入英文例句（Alice）");
        assertTrue(systemPrompt.contains("ACME Corp"), "英文例句应含 ACME Corp 组织样例");
        assertFalse(systemPrompt.contains("张三"), "Japanese 库不应注入中文例句");
    }

    /**
     * 场景：——语言全名 {@code Chinese} 的库执行文本模式抽取。
     * 预期：{@link LlmClient} 收到的系统提示词中 {language} 实测为全名 {@code Chinese}
     * （不再是历史「中文」标签）。
     */
    @Test
    void should_injectLanguageFullName_when_extract_given_chineseLanguage() {
        // given
        when(llmClient.chat(any(LlmChatRequest.class)))
                .thenReturn(new LlmChatResult(COMPLETION_DELIMITER, 1));

        // when
        service.extract(contextWithLanguageAndMode("Chinese", false), List.of(textChunk("body")));

        // then
        String systemPrompt = capturedFirstSystemPrompt();
        assertTrue(systemPrompt.contains("must be written in `Chinese`"),
                "{language} 必须注入知识库语言全名 Chinese");
        assertFalse(systemPrompt.contains("must be written in `中文`"), "旧「中文」标签映射已删除");
    }

    /**
     * 场景：语言全名 {@code Japanese} 的库执行抽取，上下文携带前后空白（trim 口径验证）。
     * 预期：{@link LlmClient} 收到的系统提示词中 {language} 为 trim 后的 {@code Japanese}
     * （不回退历史「English」标签）。
     */
    @Test
    void should_injectTrimmedLanguageFullName_when_extract_given_paddedJapaneseLanguage() {
        // given
        when(llmClient.chat(any(LlmChatRequest.class)))
                .thenReturn(new LlmChatResult(COMPLETION_DELIMITER, 1));

        // when
        service.extract(contextWithLanguageAndMode("  Japanese  ", false), List.of(textChunk("body")));

        // then：未 trim 将呈现为带空白的原串；错误回退将出现 English 标签
        String systemPrompt = capturedFirstSystemPrompt();
        assertTrue(systemPrompt.contains("must be written in `Japanese`"),
                "{language} 应注入 trim 后的知识库语言全名");
        assertFalse(systemPrompt.contains("`  Japanese  `"), "语言前后空白应被 trim");
        assertFalse(systemPrompt.contains("must be written in `English`"), "不得回退为历史 English 标签");
    }

    /**
     * 场景：语言全名 {@code Chinese} 的库执行 JSON 模式抽取。
     * 预期：{@link LlmClient} 收到的 JSON 系统提示词实测注入中文 JSON 例句（张三/雇佣,研究）。
     */
    @Test
    void should_injectChineseJsonExamples_when_extract_given_chineseLanguageJsonMode() {
        // given
        when(llmClient.chat(any(LlmChatRequest.class)))
                .thenReturn(new LlmChatResult(COMPLETION_DELIMITER, 1));

        // when
        service.extract(contextWithLanguageAndMode("Chinese", true), List.of(textChunk("body")));

        // then
        String systemPrompt = capturedFirstSystemPrompt();
        assertTrue(systemPrompt.contains("\"name\": \"张三\""), "Chinese 库 JSON 例句应含张三实体对象");
        assertTrue(systemPrompt.contains("\"keywords\": \"雇佣,研究\""), "JSON 例句关键词应为逗号分隔字符串");
        assertTrue(systemPrompt.contains(COMPLETION_DELIMITER), "JSON 例句应以完成信号结尾");
    }

    /**
     * 场景：英文语言全名 {@code English} 的库执行 JSON 模式抽取。
     * 预期：{@link LlmClient} 收到的系统提示词实测注入英文 JSON 例句，且渲染产物整体
     * 不含任何占位符字面量（静态门面替换 + fail-fast 残留校验口径）。
     */
    @Test
    void should_keepExamplesFreeOfPlaceholders_when_extract_given_englishLanguageJsonMode() {
        // given
        when(llmClient.chat(any(LlmChatRequest.class)))
                .thenReturn(new LlmChatResult(COMPLETION_DELIMITER, 1));

        // when
        service.extract(contextWithLanguageAndMode("English", true), List.of(textChunk("body")));

        // then
        String systemPrompt = capturedFirstSystemPrompt();
        assertTrue(systemPrompt.contains("Alice"), "English 库应注入英文 JSON 例句");
        for (String placeholder : List.of("{tuple_delimiter}", "{completion_delimiter}",
                "{language}", "{examples}", "{entity_name}")) {
            assertFalse(systemPrompt.contains(placeholder), "渲染产物不应含占位符 " + placeholder);
        }
    }

    // ------------------------------------------------------------------ 缓存重放入口（rebuild-kg-on-document-delete / tasks 3.x）

    /**
     * 场景：分块 1001 存在「首轮 + 续抽」两条可重放缓存行（归属集合刻意以续抽键在前返回，
     * 合并顺序必须只由创建时间升序决定）。
     * 预期：两条响应全部纳入——先创建的首轮响应先入汇聚器（节点序以 Alice 起始），
     * 后创建的续抽响应追加 Bob 并以更长描述覆盖同名 Alice；sourceIds 只含本块主键、
     * filePaths 为空（由重建侧重算）；缓存键合并一次批量读取（无 N+1）、重放全程零 LLM 调用。
     */
    @Test
    @SuppressWarnings("unchecked")
    void should_replayAllResponsesInCreatedAtAscOrder_when_replayExtractedChunks_given_firstAndGleaningEntries() {
        // given：归属键返回序 [续抽, 首轮]，创建时间首轮早于续抽
        Set<String> keys = new LinkedHashSet<>(List.of(CACHE_KEY_GLEAN, CACHE_KEY_FIRST));
        when(chunkExtractCacheRepository.findCacheKeysByChunkIds(eq(KB_ID), eq(CacheType.EXTRACT),
                eq(List.of(1001L)))).thenReturn(Map.of(1001L, keys));
        String firstResponse = entityRow("Alice", "Person", "Short.") + "\n";
        String gleanResponse = entityRow("Bob", "Person", "Bob desc.") + "\n"
                + entityRow("Alice", "Person", "A much longer description.") + "\n";
        when(llmCacheRepository.findByKbIdAndCacheKeys(eq(KB_ID), eq(CacheType.EXTRACT), anyCollection()))
                .thenReturn(List.of(
                        cacheEntry(CACHE_KEY_GLEAN, gleanResponse, BASE_TIME.plusMinutes(1)),
                        cacheEntry(CACHE_KEY_FIRST, firstResponse, BASE_TIME)));

        // when
        Map<Long, EntityExtractionResult> results = service.replayExtractedChunks(
                replayContext(List.of(), false), List.of(replayChunk(1001L, null, null)));

        // then：创建时间升序合并（乱序返回的键不改变节点产出序）；同名条目取后到的更长描述
        EntityExtractionResult chunkResult = results.get(1001L);
        assertEquals(List.of("Alice", "Bob"), entityNames(chunkResult));
        assertEquals("A much longer description.", chunkResult.nodes().get(0).properties().description());
        assertEquals(List.of(1001L), chunkResult.nodes().get(0).properties().sourceIds());
        assertTrue(chunkResult.nodes().get(0).properties().filePaths().isEmpty());
        assertTrue(chunkResult.edges().isEmpty());
        // 一次批量读取：两键合并下发，MUST NOT 逐键 N+1
        verify(chunkExtractCacheRepository, times(1))
                .findCacheKeysByChunkIds(eq(KB_ID), eq(CacheType.EXTRACT), eq(List.of(1001L)));
        ArgumentCaptor<Collection<String>> keysCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(llmCacheRepository, times(1))
                .findByKbIdAndCacheKeys(eq(KB_ID), eq(CacheType.EXTRACT), keysCaptor.capture());
        assertEquals(Set.of(CACHE_KEY_FIRST, CACHE_KEY_GLEAN), Set.copyOf(keysCaptor.getValue()));
        verify(llmClient, never()).chat(any(LlmChatRequest.class));
    }

    /**
     * 场景：同一分块同名条目，先创建的响应携带更长描述、后创建的响应描述更短。
     * 预期：先出现者为基准，后来者仅描述严格更长才覆盖——短描述不生效（取描述更长者口径的
     * 「等长/更短保先」半边，对齐上游 LightRAG）。
     */
    @Test
    void should_keepEarlierLongerDescription_when_replayExtractedChunks_given_laterShorterDesc() {
        // given
        when(chunkExtractCacheRepository.findCacheKeysByChunkIds(eq(KB_ID), eq(CacheType.EXTRACT),
                eq(List.of(1001L)))).thenReturn(Map.of(1001L, Set.of(CACHE_KEY_FIRST, CACHE_KEY_GLEAN)));
        String early = entityRow("Alice", "Person", "A much longer description.") + "\n";
        String late = entityRow("Alice", "Person", "Short.") + "\n";
        when(llmCacheRepository.findByKbIdAndCacheKeys(eq(KB_ID), eq(CacheType.EXTRACT), anyCollection()))
                .thenReturn(List.of(
                        cacheEntry(CACHE_KEY_FIRST, early, BASE_TIME),
                        cacheEntry(CACHE_KEY_GLEAN, late, BASE_TIME.plusMinutes(1))));

        // when
        EntityExtractionResult chunkResult = service.replayExtractedChunks(
                replayContext(List.of(), false), List.of(replayChunk(1001L, null, null))).get(1001L);

        // then：同名归并单节点，长描述（先者）胜出
        assertEquals(1, chunkResult.nodes().size());
        assertEquals("A much longer description.", chunkResult.nodes().get(0).properties().description());
        verify(llmClient, never()).chat(any(LlmChatRequest.class));
    }

    /**
     * 场景：多模态图片块的缓存响应含「不在清单类型」的实体及引用它的关系，分块携带定形主实体名。
     * 预期：实体类型清单过滤（含连带丢弃引用关系）与 sidecar belongs_to 归属边注入在重放路径
     * 同样生效，口径与抽取一致（权重 10.0、关键词字典序、sourceIds 只含本块主键）。
     */
    @Test
    void should_applyTypeFilterAndSidecarInjection_when_replayExtractedChunks_given_multimodalChunkEntry() {
        // given：库类型清单仅 Drug（内置 Person 放行、UnknownType 过滤）
        ContentBlockVO imageBlock = new ContentBlockVO(ContentBlockVO.TYPE_IMAGE, "raw image text", Map.of());
        when(chunkExtractCacheRepository.findCacheKeysByChunkIds(eq(KB_ID), eq(CacheType.EXTRACT),
                eq(List.of(1002L)))).thenReturn(Map.of(1002L, Set.of(CACHE_KEY_FIRST)));
        String response = entityRow("Alice", "Person", "Alice desc.") + "\n"
                + entityRow("Mystery", "UnknownType", "Not allowed.") + "\n"
                + relationRow("Alice", "Mystery", "knows", "Alice knows Mystery.") + "\n"
                + COMPLETION_DELIMITER;
        when(llmCacheRepository.findByKbIdAndCacheKeys(eq(KB_ID), eq(CacheType.EXTRACT), anyCollection()))
                .thenReturn(List.of(cacheEntry(CACHE_KEY_FIRST, response, BASE_TIME)));

        // when
        EntityExtractionResult chunkResult = service.replayExtractedChunks(
                replayContext(List.of(new EntityType("Drug")), false),
                List.of(replayChunk(1002L, imageBlock, "Lake (image)"))).get(1002L);

        // then：Mystery 及引用它的关系被清单过滤；Alice 存活并获得指向主实体的 10.0 归属边
        assertEquals(List.of("Alice"), entityNames(chunkResult));
        assertEquals(1, chunkResult.edges().size());
        RelationEdge belongs = chunkResult.edges().get(0);
        assertEquals("Alice", belongs.sourceName());
        assertEquals("Lake (image)", belongs.targetName());
        assertEquals(BELONGS_TO_WEIGHT, belongs.properties().weight(), 0.001);
        assertEquals(List.of("belongs_to", "contained_in", "part_of"), belongs.properties().keywords());
        assertEquals(List.of(1002L), belongs.properties().sourceIds());
        verify(llmClient, never()).chat(any(LlmChatRequest.class));
    }

    /**
     * 场景：JSON 输出模式的库重放 JSON 形态缓存响应（含 markdown 围栏与完成信号噪声）。
     * 预期：双模式解析在重放路径同样生效，JSON 对象解析为节点与边（与抽取 JSON 分支同收口）。
     */
    @Test
    void should_parseJsonResponse_when_replayExtractedChunks_given_jsonModeContext() {
        // given
        when(chunkExtractCacheRepository.findCacheKeysByChunkIds(eq(KB_ID), eq(CacheType.EXTRACT),
                eq(List.of(1001L)))).thenReturn(Map.of(1001L, Set.of(CACHE_KEY_FIRST)));
        String response = "```json\n"
                + "{\"entities\": [{\"name\": \"Alice\", \"type\": \"Person\", \"description\": \"Alice desc.\"},"
                + " {\"name\": \"ACME Corp\", \"type\": \"Organization\", \"description\": \"A company.\"}], "
                + "\"relationships\": [{\"source\": \"Alice\", \"target\": \"ACME Corp\","
                + " \"keywords\": \"employment\", \"description\": \"Alice works at ACME Corp.\"}]}\n"
                + "```\n" + COMPLETION_DELIMITER;
        when(llmCacheRepository.findByKbIdAndCacheKeys(eq(KB_ID), eq(CacheType.EXTRACT), anyCollection()))
                .thenReturn(List.of(cacheEntry(CACHE_KEY_FIRST, response, BASE_TIME)));

        // when
        EntityExtractionResult chunkResult = service.replayExtractedChunks(
                replayContext(List.of(), true), List.of(replayChunk(1001L, null, null))).get(1001L);

        // then
        assertEquals(2, chunkResult.nodes().size());
        assertEquals(1, chunkResult.edges().size());
        assertEquals(List.of("employment"), chunkResult.edges().get(0).properties().keywords());
        verify(llmClient, never()).chat(any(LlmChatRequest.class));
    }

    /**
     * 场景：两个分块各归属独立缓存键（一次批量读取覆盖两键的行）。
     * 预期：每分块的记录集合只来自其归属键命中的响应行，互不串扰；批量读取仅一次。
     */
    @Test
    void should_keepChunkResultsIsolated_when_replayExtractedChunks_given_twoChunksDistinctKeys() {
        // given
        when(chunkExtractCacheRepository.findCacheKeysByChunkIds(eq(KB_ID), eq(CacheType.EXTRACT),
                eq(List.of(1001L, 1002L)))).thenReturn(Map.of(
                        1001L, Set.of(CACHE_KEY_FIRST), 1002L, Set.of(CACHE_KEY_OTHER)));
        when(llmCacheRepository.findByKbIdAndCacheKeys(eq(KB_ID), eq(CacheType.EXTRACT), anyCollection()))
                .thenReturn(List.of(
                        cacheEntry(CACHE_KEY_FIRST, entityRow("Alice", "Person", "Alice desc."), BASE_TIME),
                        cacheEntry(CACHE_KEY_OTHER, entityRow("Bob", "Person", "Bob desc."),
                                BASE_TIME.plusMinutes(1))));

        // when
        Map<Long, EntityExtractionResult> results = service.replayExtractedChunks(replayContext(List.of(), false),
                List.of(replayChunk(1001L, null, null), replayChunk(1002L, null, null)));

        // then：分块 1001 只见 Alice、分块 1002 只见 Bob
        assertEquals(2, results.size());
        assertEquals(List.of("Alice"), entityNames(results.get(1001L)));
        assertEquals(List.of("Bob"), entityNames(results.get(1002L)));
        verify(llmCacheRepository, times(1))
                .findByKbIdAndCacheKeys(eq(KB_ID), eq(CacheType.EXTRACT), anyCollection());
        verify(llmClient, never()).chat(any(LlmChatRequest.class));
    }

    /**
     * 场景：重放分块在归属表无任何归属记录。
     * 预期：返回该分块对应空记录集合（供上层降级判定）、不下发缓存读取、不抛异常、零 LLM 调用。
     */
    @Test
    void should_returnEmptyResultWithoutCacheQuery_when_replayExtractedChunks_given_noAttributionKeys() {
        // given：归属表空映射
        when(chunkExtractCacheRepository.findCacheKeysByChunkIds(eq(KB_ID), eq(CacheType.EXTRACT),
                eq(List.of(1001L)))).thenReturn(Map.of());

        // when
        Map<Long, EntityExtractionResult> results = service.replayExtractedChunks(
                replayContext(List.of(), false), List.of(replayChunk(1001L, null, null)));

        // then
        assertTrue(results.containsKey(1001L));
        assertTrue(results.get(1001L).nodes().isEmpty());
        assertTrue(results.get(1001L).edges().isEmpty());
        verifyNoInteractions(llmCacheRepository);
        verify(llmClient, never()).chat(any(LlmChatRequest.class));
    }

    /**
     * 场景：归属键存在但键在缓存表查不到行（缓存行已被回收等）。
     * 预期：该分块返回空记录集合，不抛异常、零 LLM 调用。
     */
    @Test
    void should_returnEmptyResult_when_replayExtractedChunks_given_keysWithoutCacheRows() {
        // given
        when(chunkExtractCacheRepository.findCacheKeysByChunkIds(eq(KB_ID), eq(CacheType.EXTRACT),
                eq(List.of(1001L)))).thenReturn(Map.of(1001L, Set.of(CACHE_KEY_FIRST)));
        when(llmCacheRepository.findByKbIdAndCacheKeys(eq(KB_ID), eq(CacheType.EXTRACT), anyCollection()))
                .thenReturn(List.of());

        // when
        EntityExtractionResult chunkResult = service.replayExtractedChunks(
                replayContext(List.of(), false), List.of(replayChunk(1001L, null, null))).get(1001L);

        // then
        assertTrue(chunkResult.nodes().isEmpty());
        assertTrue(chunkResult.edges().isEmpty());
        verify(llmClient, never()).chat(any(LlmChatRequest.class));
    }

    /**
     * 场景：同一分块两条缓存行中一条响应文本为空白。
     * 预期：空白行跳过解析（与抽取期空白响应跳过同口径），另一条正常解析不受影响。
     */
    @Test
    void should_skipBlankResponseEntry_when_replayExtractedChunks_given_oneBlankOneValid() {
        // given
        when(chunkExtractCacheRepository.findCacheKeysByChunkIds(eq(KB_ID), eq(CacheType.EXTRACT),
                eq(List.of(1001L)))).thenReturn(Map.of(1001L, Set.of(CACHE_KEY_FIRST, CACHE_KEY_GLEAN)));
        when(llmCacheRepository.findByKbIdAndCacheKeys(eq(KB_ID), eq(CacheType.EXTRACT), anyCollection()))
                .thenReturn(List.of(
                        cacheEntry(CACHE_KEY_FIRST, "   ", BASE_TIME),
                        cacheEntry(CACHE_KEY_GLEAN, entityRow("Alice", "Person", "Alice desc."),
                                BASE_TIME.plusMinutes(1))));

        // when
        EntityExtractionResult chunkResult = service.replayExtractedChunks(
                replayContext(List.of(), false), List.of(replayChunk(1001L, null, null))).get(1001L);

        // then：仅有效行入记录
        assertEquals(List.of("Alice"), entityNames(chunkResult));
        verify(llmClient, never()).chat(any(LlmChatRequest.class));
    }

    /**
     * 场景：分块列表为空/null；上下文为 null。
     * 预期：空分块输入直接返回空映射（不触碰任何仓储）；null 上下文快速失败抛
     * IllegalArgumentException；两种形态均零 LLM 调用。
     */
    @Test
    void should_returnEmptyMapOrThrow_when_replayExtractedChunks_given_emptyOrNullInput() {
        // when & then：空/null 分块列表 → 空映射，不触碰仓储
        assertTrue(service.replayExtractedChunks(replayContext(List.of(), false), List.of()).isEmpty());
        assertTrue(service.replayExtractedChunks(replayContext(List.of(), false), null).isEmpty());
        // given & then：null 上下文快速失败
        assertThrows(IllegalArgumentException.class,
                () -> service.replayExtractedChunks(null, List.of(replayChunk(1001L, null, null))));
        verifyNoInteractions(chunkExtractCacheRepository, llmCacheRepository);
        verify(llmClient, never()).chat(any(LlmChatRequest.class));
    }

    // ------------------------------------------------------------------ 测试辅助

    /**
     * 自旋等待惰性执行器捕获到扇出任务（带超时上限；配合
     * {@code should_awaitSubmittedTasks_when_extract_given_cancelledMidLoop} 观测收口等待）。
     *
     * @param capturedTasks 惰性执行器捕获的任务列表
     */
    private void awaitCapturedTask(List<Runnable> capturedTasks) {
        long deadline = System.currentTimeMillis() + ASYNC_TIMEOUT_MILLIS;
        while (capturedTasks.isEmpty() && System.currentTimeMillis() < deadline) {
            try {
                TimeUnit.MILLISECONDS.sleep(10L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("等待扇出任务提交被中断");
            }
        }
        assertFalse(capturedTasks.isEmpty(), "在限定时间内未观察到扇出任务提交");
    }

    /**
     * 捕获发往 {@link LlmClient} 的首次调用所携带的系统提示词。
     * <p>语言/例句注入类用例均为「单块、不补漏」的单次调用场景，故按恰一次调用捕获。</p>
     *
     * @return 首个 LLM 请求的 systemPrompt
     */
    private String capturedFirstSystemPrompt() {
        ArgumentCaptor<LlmChatRequest> captor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(llmClient).chat(captor.capture());
        return captor.getAllValues().get(0).systemPrompt();
    }

    /**
     * 指定语言与输出模式的上下文（不补漏、并发 4、无取消检查）。
     *
     * @param language 知识库语言全名
     * @param jsonMode 是否 JSON 输出模式
     * @return 抽取上下文
     */
    private static EntityExtractionContext contextWithLanguageAndMode(String language, boolean jsonMode) {
        return new EntityExtractionContext(KB_ID, DOC_ID, PROFILE_ID, language,
                List.of(), jsonMode, 0, 4, FILE_PATH, null, null);
    }

    /**
     * 拼实体行。
     */
    private static String entityRow(String name, String type, String description) {
        return "entity" + TUPLE_DELIMITER + name + TUPLE_DELIMITER + type + TUPLE_DELIMITER + description;
    }

    /**
     * 拼关系行。
     */
    private static String relationRow(String source, String target, String keywords, String description) {
        return "relation" + TUPLE_DELIMITER + source + TUPLE_DELIMITER + target
                + TUPLE_DELIMITER + keywords + TUPLE_DELIMITER + description;
    }

    /**
     * 拼 relationship 前缀关系行（LightRAG 原版别名前缀，与 relation 行结构相同）。
     */
    private static String relationshipRow(String source, String target, String keywords, String description) {
        return "relationship" + TUPLE_DELIMITER + source + TUPLE_DELIMITER + target
                + TUPLE_DELIMITER + keywords + TUPLE_DELIMITER + description;
    }

    /**
     * 纯文本落库态分块（sequence=1、chunkId=1001、无内容块元数据）。
     */
    private static PersistedChunkVO textChunk(String text) {
        return persisted(new ChunkVO(1, text, 10, null));
    }

    /**
     * 将分块中间态包装为落库态：chunkId = {@code CHUNK_ID_BASE + sequence}，与序号数值可区分，
     * 断言来源键取的是绑定的真实主键而非占位序号。
     */
    private static PersistedChunkVO persisted(ChunkVO chunk) {
        return new PersistedChunkVO(chunk.sequence(), CHUNK_ID_BASE + chunk.sequence(), chunk);
    }

    /**
     * 默认上下文：文本模式、不补漏、并发 4、无取消检查。
     */
    private static EntityExtractionContext defaultContext() {
        return new EntityExtractionContext(KB_ID, DOC_ID, PROFILE_ID, "en",
                List.of(), false, 0, 4, FILE_PATH, null, null);
    }

    /**
     * 指定补漏轮数的上下文。
     */
    private static EntityExtractionContext contextWithGleaning(int maxGleaning) {
        return new EntityExtractionContext(KB_ID, DOC_ID, PROFILE_ID, "en",
                List.of(), false, maxGleaning, 4, FILE_PATH, null, null);
    }

    /**
     * 指定多模态主实体名的上下文（文本模式、不补漏、无取消检查）。
     */
    private static EntityExtractionContext contextWithPrimaryEntityName(String mediaPrimaryEntityName) {
        return new EntityExtractionContext(KB_ID, DOC_ID, PROFILE_ID, "en",
                List.of(), false, 0, 4, FILE_PATH, null, mediaPrimaryEntityName);
    }

    /**
     * 指定取消检查器的上下文。
     */
    private static EntityExtractionContext contextWithCancel(BooleanSupplier cancellationCheck) {
        return new EntityExtractionContext(KB_ID, DOC_ID, PROFILE_ID, "en",
                List.of(), false, 0, 4, FILE_PATH, cancellationCheck, null);
    }

    /**
     * 按名称查找结果节点。
     */
    private static EntityNode nodeByName(EntityExtractionResult result, String name) {
        return result.nodes().stream()
                .filter(node -> node.entityName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("结果中缺少实体：" + name));
    }

    /**
     * 按无向端点对查找结果边。
     */
    private static RelationEdge edgeByPair(EntityExtractionResult result, String first, String second) {
        return edgesByPair(result, first, second).stream()
                .findFirst()
                .orElseThrow(() -> new AssertionError("结果中缺少关系：" + first + " ~ " + second));
    }

    /**
     * 按无向端点对查找全部结果边（「一记录一边」出口下同端点对可能对应多条边，
     * 返回顺序即贡献记录收集顺序）。
     */
    private static List<RelationEdge> edgesByPair(EntityExtractionResult result, String first, String second) {
        return result.edges().stream()
                .filter(edge -> (edge.sourceName().equals(first) && edge.targetName().equals(second))
                        || (edge.sourceName().equals(second) && edge.targetName().equals(first)))
                .toList();
    }

    /**
     * 构造重放输入缓存行（EXTRACT 分区，创建时间可逐条指定以驱动排序断言）。
     *
     * @param cacheKey  缓存键（32 位 hex 常量）
     * @param response  响应文本
     * @param createdAt 创建时间
     * @return 缓存条目
     */
    private static LlmCacheEntry cacheEntry(String cacheKey, String response, OffsetDateTime createdAt) {
        return LlmCacheEntry.restore(1L, KB_ID, cacheKey, CacheType.EXTRACT, "gpt-test",
                "prompt-text", response, 10, createdAt, createdAt);
    }

    /**
     * 重放库级上下文（kbId + 实体类型清单 + 输出模式）。
     *
     * @param entityTypes 实体类型清单
     * @param jsonMode    是否 JSON 输出模式
     * @return 重放上下文
     */
    private static ChunkReplayContext replayContext(List<EntityType> entityTypes, boolean jsonMode) {
        return new ChunkReplayContext(KB_ID, entityTypes, jsonMode);
    }

    /**
     * 重放分块输入（正文固定占位——重放解析不消费正文；块 meta 与主实体名可为 null）。
     *
     * @param chunkId                分块真实主键
     * @param block                  来源内容块（多模态判定用）
     * @param mediaPrimaryEntityName 定形多模态主实体名
     * @return 重放分块输入
     */
    private static ReplayChunkInput replayChunk(Long chunkId, ContentBlockVO block,
                                                String mediaPrimaryEntityName) {
        return new ReplayChunkInput(chunkId, "chunk body", block, mediaPrimaryEntityName);
    }

    /**
     * 按出口顺序提取结果实体名列表（节点产出序即汇聚器插入序，用于合并顺序断言）。
     *
     * @param result 抽取/重放记录集合
     * @return 实体名列表
     */
    private static List<String> entityNames(EntityExtractionResult result) {
        return result.nodes().stream().map(EntityNode::entityName).toList();
    }
}
