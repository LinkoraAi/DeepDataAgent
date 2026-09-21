package com.linkroa.deepdataagent.rag.application.task;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.linkroa.deepdataagent.knowledgebase.api.ChunkBatchWriter;
import com.linkroa.deepdataagent.knowledgebase.application.contract.ChunkIdentity;
import com.linkroa.deepdataagent.knowledgebase.api.DocumentGraphConvergenceApi;
import com.linkroa.deepdataagent.knowledgebase.api.DocumentGraphConvergencePlan;
import com.linkroa.deepdataagent.knowledgebase.api.DocumentGraphConvergenceResult;
import com.linkroa.deepdataagent.knowledgebase.application.contract.IngestionDocumentContext;
import com.linkroa.deepdataagent.knowledgebase.api.InFlightTaskRegistry;
import com.linkroa.deepdataagent.knowledgebase.api.InFlightTaskType;
import com.linkroa.deepdataagent.knowledgebase.api.IngestionSourceReader;
import com.linkroa.deepdataagent.knowledgebase.domain.model.EmbeddingModelConfig;
import com.linkroa.deepdataagent.knowledgebase.domain.model.MultiModelConfig;
import com.linkroa.deepdataagent.knowledgebase.domain.model.RagEngineConfig;
import com.linkroa.deepdataagent.knowledgebase.domain.model.S3File;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.DocumentChunkMode;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.DocumentStatus;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.RagEngineType;
import com.linkroa.deepdataagent.rag.application.service.ChunkPersistenceService;
import com.linkroa.deepdataagent.rag.application.service.MediaImagePersistenceService;
import com.linkroa.deepdataagent.rag.domain.enums.CacheType;
import com.linkroa.deepdataagent.rag.domain.model.ChunkVO;
import com.linkroa.deepdataagent.rag.domain.model.ContentBlockVO;
import com.linkroa.deepdataagent.rag.domain.model.EntityNode;
import com.linkroa.deepdataagent.rag.domain.model.EntityProperties;
import com.linkroa.deepdataagent.rag.domain.model.MediaDescriptionVO;
import com.linkroa.deepdataagent.rag.domain.model.MediaFileRef;
import com.linkroa.deepdataagent.rag.domain.model.ParsedDocument;
import com.linkroa.deepdataagent.rag.domain.model.PersistedChunkVO;
import com.linkroa.deepdataagent.rag.domain.model.RelationEdge;
import com.linkroa.deepdataagent.rag.domain.port.DocumentParser;
import com.linkroa.deepdataagent.rag.domain.port.DocumentParserRegistry;
import com.linkroa.deepdataagent.rag.domain.repository.ChunkExtractCacheRepository;
import com.linkroa.deepdataagent.rag.domain.service.ChunkingOutcome;
import com.linkroa.deepdataagent.rag.domain.service.ChunkingService;
import com.linkroa.deepdataagent.rag.domain.service.EntityExtractionContext;
import com.linkroa.deepdataagent.rag.domain.service.EntityExtractionResult;
import com.linkroa.deepdataagent.rag.domain.service.EntityExtractionService;
import com.linkroa.deepdataagent.rag.domain.service.GraphMergeParams;
import com.linkroa.deepdataagent.rag.domain.service.GraphMergeContext;
import com.linkroa.deepdataagent.rag.domain.service.GraphMergeReport;
import com.linkroa.deepdataagent.rag.domain.service.GraphMergeService;
import com.linkroa.deepdataagent.rag.domain.service.MediaChunkTemplateService;
import com.linkroa.deepdataagent.rag.domain.service.MediaDescriptorService;
import com.linkroa.deepdataagent.rag.domain.service.MultimodalMetaKeys;
import com.linkroa.deepdataagent.rag.domain.service.TokenCounter;
import org.apache.commons.lang3.ObjectUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link IngestionWorker} 单元测试：验证纯文本三阶段与多模态七阶段管线顺序、
 * 抽取入参身份绑定（{@link PersistedChunkVO} 承载落库回传的真实分块主键，占位重写层已从根源移除）、
 * 失败置 FAILED 与错误回传、取消静默收敛、收尾统一置成功终态、嵌入模型引用缺失判定失败与清空重建重跑。
 * <p>另覆盖的失败归因口径：各失败出口回写的
 * {@code errorMessage} 必须携带 {@link IngestionStage} 分类前缀，
 * 未能归位的异常回落 {@link IngestionStage#UNKNOWN_STAGE} 保证前缀永不缺位；
 * 以及 LLM 缓存命中计数器在描述 / 抽取 / 合并三处上下文间的贯穿。</p>
 * <p>另覆盖的直通留痕口径：Stage 1b 分块后必须输出
 * 单行「分块 dispatch」INFO（docId / resolvedMode / chunkCount / elapsedMs 四字段），
 * 与收尾六字段摄入摘要独立并存；纯直通下不再有 explicitMode / dispatchBasis 字段。</p>
 * <p>另覆盖 / 的语言同源口径：媒体描述、
 * 抽取上下文与合并上下文的语言取知识库配置全名优先，库未配置 / 空白回落代码内默认 Chinese。</p>
 * <p>另覆盖知识库级 LLM 引用的唯一真相源口径：媒体描述（VLM）、实体抽取与图合并摘要<strong>共用同一个
 * profileId</strong>，取值只来自 {@code ctx.multiModelConfig().modelProfileId()}（即知识库
 * {@code multi_model_config.modelProfileId}），该配置缺失时归位 Stage 0 / PARSE 前置校验失败并回写
 * FAILED，不再回落任何应用级全局配置。两者同源于同一 {@code multiModelConfig}，故「该库未配置 LLM」
 * 必然同时意味着「该库无 VLM」——Stage 2 的「有块无模型降级跳过」分支在管线上已不可达。</p>
 * <p>全部外部依赖（跨 BC 契约、LLM / VLM / 向量化服务）均为 Mock，测试离线可重复。</p>
 */
@ExtendWith(MockitoExtension.class)
class IngestionWorkerTest {

    /** 测试文档主键 */
    private static final Long DOCUMENT_ID = 42L;

    /** 测试知识库主键 */
    private static final Long KB_ID = 7L;

    /** 测试知识库级通用 LLM profileId（媒体描述 / 实体抽取 / 摘要共用，上下文默认携带） */
    private static final String LLM_PROFILE = "kb-llm-profile-1";

    /** 测试库级显式配置的 LLM profileId（验证取值仅来自 multi_model_config.modelProfileId） */
    private static final String KB_LLM_PROFILE = "kb-llm-profile-9";

    /** 测试向量模型 profileId */
    private static final String EMBED_PROFILE = "embed-profile-1";

    /** 测试文件名 */
    private static final String FILE_NAME = "doc.pdf";

    /** 测试旧代际切片主键（重解析换代场景下替换前预查捕获到的 ID） */
    private static final Long OLD_CHUNK_ID = 8001L;

    /** 并发/异步用例的等待上限（毫秒） */
    private static final long ASYNC_TIMEOUT_MILLIS = 3000L;

    @Mock
    private IngestionSourceReader ingestionSourceReader;

    @Mock
    private ChunkBatchWriter chunkBatchWriter;

    @Mock
    private DocumentParserRegistry parserRegistry;

    @Mock
    private DocumentParser documentParser;

    @Mock
    private ChunkingService chunkingService;

    @Mock
    private ChunkPersistenceService chunkPersistenceService;

    @Mock
    private MediaDescriptorService mediaDescriptorService;

    @Mock
    private MediaChunkTemplateService mediaChunkTemplateService;

    @Mock
    private MediaImagePersistenceService mediaImagePersistenceService;

    @Mock
    private TokenCounter tokenCounter;

    @Mock
    private EntityExtractionService entityExtractionService;

    @Mock
    private GraphMergeService graphMergeService;

    @Mock
    private DocumentGraphConvergenceApi documentGraphConvergenceApi;

    /** 在飞任务注册表 Mock（收尾统一移除本实例文档摄入成员） */
    @Mock
    private InFlightTaskRegistry inFlightTaskRegistry;

    /** 抽取缓存归属仓储 Mock（媒体描述归属落库后补登记断言用） */
    @Mock
    private ChunkExtractCacheRepository chunkExtractCacheRepository;

    @Mock
    private com.linkroa.deepdataagent.rag.domain.service.MultimodalContextInjector multimodalContextInjector;

    @InjectMocks
    private IngestionWorker ingestionWorker;

    /**
     * 真实扇出虚拟线程执行器（3.1：多模态描述扇出经此执行）。MUST NOT 用 Mock——
     * Mock 的 {@link Executor#execute(Runnable)} 不执行任务体，将致 {@code allOf().join()} 死等。
     */
    private ExecutorService fanoutExecutor;

    @BeforeEach
    void setUp() {
        fanoutExecutor = Executors.newVirtualThreadPerTaskExecutor();
        ReflectionTestUtils.setField(ingestionWorker, "ingestionExecutor", fanoutExecutor);
        ReflectionTestUtils.setField(ingestionWorker, "extractionJsonMode", false);
        ReflectionTestUtils.setField(ingestionWorker, "extractionMaxGleaning", 1);
        ReflectionTestUtils.setField(ingestionWorker, "describeConcurrency", 4);
        // 注入器为纯函数依赖，Worker 测试聚焦管线编排，统一打桩为原样透传（不改变解析产物），
        // 注入逻辑本身由 MultimodalContextInjectorTest 单独覆盖；lenient 兼容未走到 Stage 1a 的用例
        lenient().when(multimodalContextInjector.inject(any())).thenAnswer(invocation -> invocation.getArgument(0));
        // Stage 3 落库契约默认桩：落库事务内回传「序号→主键」映射；lenient 兼容未走到 Stage 3 的用例
        stubIdentityMapAnswer();
        // 收敛契约默认桩（组 6 两段式）：prepare 返回句柄、apply 返回全零结果；
        // lenient 兼容首次摄入（旧代为空、三步零触达）的用例
        lenient().when(documentGraphConvergenceApi.prepare(any(), any(), any(), any()))
                .thenReturn(mock(DocumentGraphConvergencePlan.class));
        lenient().when(documentGraphConvergenceApi.apply(any()))
                .thenReturn(DocumentGraphConvergenceResult.empty());
    }

    /**
     * 关闭真实扇出执行器，避免虚拟线程泄漏（3.1 要求 {@code @AfterEach} 关闭）。
     */
    @AfterEach
    void tearDown() {
        if (ObjectUtils.isNotEmpty(fanoutExecutor)) {
            fanoutExecutor.shutdownNow();
        }
    }

    /**
     * 场景（converge-rag-hot-path-object-creation / 5.6）：{@code @Value} 字段注入完成后
     * 多次取应用级图合并参数基底。
     * 预期：基底各分量与注入配置等值；多次调用返回同一实例（底层不重复构造）；
     * 缓存成形后再改字段仍返回同一缓存实例——钉死「应用级配置启动后不变、不支持热更新」前提。
     */
    @Test
    void should_reuseSingleAppLevelParamsInstance_when_appLevelGraphMergeParams_given_repeatedCallsAfterInjection() {
        // given：模拟 @Value 注入完成态（graph 四项应用级配置）
        ReflectionTestUtils.setField(ingestionWorker, "graphSourceIdsLimit", 120);
        ReflectionTestUtils.setField(ingestionWorker, "graphSourceIdsTruncation", "KEEP");
        ReflectionTestUtils.setField(ingestionWorker, "graphSourceFilePathsLimit", 60);
        ReflectionTestUtils.setField(ingestionWorker, "graphSourceFilePathsPlaceholder", "…等");

        // when：重复取应用级基底
        GraphMergeParams first = ReflectionTestUtils.invokeMethod(ingestionWorker, "appLevelGraphMergeParams");
        GraphMergeParams second = ReflectionTestUtils.invokeMethod(ingestionWorker, "appLevelGraphMergeParams");

        // then：与注入配置等值，且复用同一实例（不重复构造）
        assertNotNull(first);
        assertEquals(120, first.applySourceIdsLimit());
        assertEquals("KEEP", first.sourceIdsTruncation());
        assertEquals(60, first.sourceFilePathsLimit());
        assertEquals("…等", first.sourceFilePathsPlaceholder());
        assertSame(first, second, "应用级基底应注入后构造一次并缓存复用");

        // when：缓存成形后修改 @Value 字段（热更新场景本实现不支持）
        ReflectionTestUtils.setField(ingestionWorker, "graphSourceIdsLimit", 1);
        GraphMergeParams third = ReflectionTestUtils.invokeMethod(ingestionWorker, "appLevelGraphMergeParams");

        // then：仍返回缓存实例，不随字段修改重建
        assertSame(first, third, "应用级配置以启动后不变为前提，缓存不随字段漂移重建");
    }

    /**
     * Stage 3 落库契约默认桩：落库事务内回传「序号→主键」映射（id = 9000 + sequence，
     * 与既有身份桩 9001/9002 同值）。
     * <p>Stage 4 抽取前的身份绑定（{@code bindPersistedChunks}）强依赖该映射，
     * 用例内 reset 落库 Mock 后必须重新打桩。</p>
     */
    private void stubIdentityMapAnswer() {
        lenient().when(chunkPersistenceService.replaceForDocument(any(), anyList(), any(), any()))
                .thenAnswer(invocation -> {
                    List<ChunkVO> persisted = invocation.getArgument(1);
                    Map<Integer, Long> identityMap = new LinkedHashMap<>();
                    for (ChunkVO chunk : persisted) {
                        identityMap.put(chunk.sequence(), 9000L + chunk.sequence());
                    }
                    return identityMap;
                });
    }

    @Test
    void should_bindRealChunkIdsIntoExtractInput_when_execute_given_documentEngineContext() {
        // given
        stubContext(documentContext(null, embeddingConfig(), null));
        stubParseAndChunk(List.of(textChunk("Alice met Bob")));
        // 抽取服务直接吃落库态入参，产物 sourceIds 自出生即真实分块主键（9001）
        stubTextExtraction(singleNodeResult("Alice", 9001L));
        when(ingestionSourceReader.listChunkIdentitiesByDocument(DOCUMENT_ID))
                .thenReturn(List.of(new ChunkIdentity(1, 9001L)));
        when(graphMergeService.mergeNodesAndEdges(any(), anyList(), anyList()))
                .thenReturn(new GraphMergeReport(1, 0, 0, 0, 0));

        // when
        ingestionWorker.execute(DOCUMENT_ID);

        // then：阶段顺序——解析 → 分块 → 旧代际预查 → 落库（事务内回传主键映射）→ 绑定抽取 → 合并
        InOrder order = inOrder(documentParser, chunkingService, ingestionSourceReader,
                chunkPersistenceService, entityExtractionService, graphMergeService);
        order.verify(documentParser).parse(any(), any(), any());
        order.verify(chunkingService).chunkWithDispatch(any(), any(), any());
        // 身份回查仅剩旧代际预查一处（Stage 3 前）
        order.verify(ingestionSourceReader).listChunkIdentitiesByDocument(DOCUMENT_ID);
        order.verify(chunkPersistenceService)
                .replaceForDocument(eq(DOCUMENT_ID), anyList(), eq(EMBED_PROFILE), isNull());
        order.verify(entityExtractionService).extract(any(), anyList());
        order.verify(graphMergeService).mergeNodesAndEdges(any(), anyList(), anyList());
        // 抽取入参为绑定落库回传映射的落库态分块：chunkId=9001（与 sequence=1 可区分，真值非占位）
        ArgumentCaptor<List<PersistedChunkVO>> chunksCaptor = ArgumentCaptor.forClass(List.class);
        verify(entityExtractionService).extract(any(), chunksCaptor.capture());
        PersistedChunkVO persisted = chunksCaptor.getValue().get(0);
        assertEquals(Integer.valueOf(1), persisted.sequence());
        assertEquals(Long.valueOf(9001L), persisted.chunkId());
        assertEquals("Alice met Bob", persisted.text());
        // 合并入参即抽取产物本身，来源键不经任何重写动作
        ArgumentCaptor<List<EntityNode>> nodesCaptor = ArgumentCaptor.forClass(List.class);
        verify(graphMergeService).mergeNodesAndEdges(any(), nodesCaptor.capture(), anyList());
        assertEquals(List.of(9001L), nodesCaptor.getValue().get(0).properties().sourceIds());
        // 成功路径无失败回写（收尾结构化日志不影响主流程返回值与状态）
        verify(chunkBatchWriter, never()).markFailed(anyLong(), anyString());
    }

    @Test
    void should_passGeneralMode_when_execute_given_unknownChunkModeJson() {
        // given：文档级策略 JSON 配置了无法识别的分块模式名
        stubContext(chunkStrategyContext("{\"chunkMode\":\"NOT_A_MODE\"}"));
        stubParseAndChunk(List.of(textChunk("Alice met Bob")));
        when(entityExtractionService.extract(any(), anyList())).thenReturn(EntityExtractionResult.empty());

        // when
        ingestionWorker.execute(DOCUMENT_ID);

        // then：未知模式串 WARN 后收敛为 GENERAL（自动分派入口），不再以 null 下发
        ArgumentCaptor<DocumentChunkMode> modeCaptor = ArgumentCaptor.forClass(DocumentChunkMode.class);
        verify(chunkingService).chunkWithDispatch(any(), any(), modeCaptor.capture());
        assertEquals(DocumentChunkMode.GENERAL, modeCaptor.getValue());
    }

    @Test
    void should_passNullMode_when_execute_given_missingChunkModeConfig() {
        // given：策略 JSON 未配置 chunkMode（用户未显式选择模式，走分块服务默认自动分派）
        stubContext(chunkStrategyContext("{\"modeConfig\":{\"params\":{\"chunk_token_num\":512}}}"));
        stubParseAndChunk(List.of(textChunk("Alice met Bob")));
        when(entityExtractionService.extract(any(), anyList())).thenReturn(EntityExtractionResult.empty());

        // when
        ingestionWorker.execute(DOCUMENT_ID);

        // then：未配置路径保持 null 下发（与显式 GENERAL 区分，保留分派留痕语义），行为不回退
        ArgumentCaptor<DocumentChunkMode> modeCaptor = ArgumentCaptor.forClass(DocumentChunkMode.class);
        verify(chunkingService).chunkWithDispatch(any(), any(), modeCaptor.capture());
        assertNull(modeCaptor.getValue());
    }

    @Test
    void should_passFileNameInParsedDocument_when_execute_given_documentContext() {
        // given：解析器产物不携带文件名（解析器侧不感知文件名）
        stubContext(documentContext(null, embeddingConfig(), null));
        stubParseAndChunk(List.of(textChunk("Alice met Bob")));
        when(entityExtractionService.extract(any(), anyList())).thenReturn(EntityExtractionResult.empty());

        // when
        ingestionWorker.execute(DOCUMENT_ID);

        // then：Worker 在 Stage 1a 用上下文文件名包装解析产物后再交给分块服务
        ArgumentCaptor<ParsedDocument> parsedCaptor = ArgumentCaptor.forClass(ParsedDocument.class);
        verify(chunkingService).chunkWithDispatch(parsedCaptor.capture(), any(), any());
        assertEquals(FILE_NAME, parsedCaptor.getValue().fileName());
        assertEquals("hash-1", parsedCaptor.getValue().parsedTextHash());
    }

    @Test
    void should_shareOneCacheHitCounter_when_execute_given_fullTextPipeline() {
        // given：文本管线全成功，抽取与合并上下文应挂同一个 LLM 缓存命中计数器
        stubContext(documentContext(null, embeddingConfig(), null));
        stubParseAndChunk(List.of(textChunk("Alice met Bob")));
        stubTextExtraction(singleNodeResult("Alice", 9001L));
        when(ingestionSourceReader.listChunkIdentitiesByDocument(DOCUMENT_ID))
                .thenReturn(List.of(new ChunkIdentity(1, 9001L)));
        when(graphMergeService.mergeNodesAndEdges(any(), anyList(), anyList()))
                .thenReturn(GraphMergeReport.empty());

        // when
        ingestionWorker.execute(DOCUMENT_ID);

        // then：管线级计数器单实例贯穿抽取与合并上下文，命中次数可被收尾日志一次性汇总
        ArgumentCaptor<EntityExtractionContext> extractCaptor =
                ArgumentCaptor.forClass(EntityExtractionContext.class);
        verify(entityExtractionService).extract(extractCaptor.capture(), anyList());
        ArgumentCaptor<GraphMergeContext> mergeCaptor = ArgumentCaptor.forClass(GraphMergeContext.class);
        verify(graphMergeService).mergeNodesAndEdges(mergeCaptor.capture(), anyList(), anyList());
        assertNotNull(extractCaptor.getValue().llmCacheHits());
        assertNotNull(mergeCaptor.getValue().llmCacheHits());
        assertSame(extractCaptor.getValue().llmCacheHits(), mergeCaptor.getValue().llmCacheHits());
    }

    @Test
    void should_enrichAndSidecarExtract_when_execute_given_documentEngineWithMediaBlocks() {
        // given：文档引擎 + 多模态块 + 库配知识库级 LLM（VLM 与之同源）→ 内容驱动触发增强
        ContentBlockVO imageBlock = new ContentBlockVO(ContentBlockVO.TYPE_IMAGE, "raw caption", Map.of());
        stubContext(documentContext(ragEngineConfig(RagEngineType.DOCUMENT_ENGINE), embeddingConfig(),
                multiModelConfig()));
        when(parserRegistry.resolve(any())).thenReturn(documentParser);
        when(documentParser.parse(any(), any(), any())).thenReturn(parsedDocument(imageBlock));
        when(chunkingService.chunkWithDispatch(any(), any(), any()))
                .thenReturn(new ChunkingOutcome(List.of(new ChunkVO(1, "original image text", 3, imageBlock)),
                        DocumentChunkMode.GENERAL));
        MediaDescriptionVO description =
                new MediaDescriptionVO("Alice Portrait", "image", "one line", "detailed caption", true);
        when(mediaDescriptorService.describe(eq(KB_ID), eq(LLM_PROFILE), eq("Chinese"), eq(imageBlock), any(),
                any())).thenReturn(description);
        ChunkVO rebuilt = new ChunkVO(1, "template enhanced text", 8, imageBlock);
        when(mediaChunkTemplateService.buildChunk(eq(1), eq(imageBlock), eq(description), eq("Chinese"),
                any())).thenReturn(rebuilt);
        when(entityExtractionService.extract(any(), anyList())).thenReturn(EntityExtractionResult.empty());
        when(ingestionSourceReader.listChunkIdentitiesByDocument(DOCUMENT_ID))
                .thenReturn(List.of(new ChunkIdentity(1, 9001L)));
        when(graphMergeService.mergeNodesAndEdges(any(), anyList(), anyList()))
                .thenReturn(new GraphMergeReport(2, 0, 1, 0, 0));

        // when
        ingestionWorker.execute(DOCUMENT_ID);

        // then：VLM 描述与模板化仅一次；描述调用携带管线级 LLM 缓存命中计数器（五参重载），
        // 语言取库未配置回落后的全局默认 Chinese；描述使用的 profileId 即 multi_model_config 的取值
        ArgumentCaptor<AtomicInteger> counterCaptor = ArgumentCaptor.forClass(AtomicInteger.class);
        verify(mediaDescriptorService).describe(eq(KB_ID), eq(LLM_PROFILE), eq("Chinese"), eq(imageBlock),
                any(), counterCaptor.capture());
        verify(mediaChunkTemplateService).buildChunk(eq(1), eq(imageBlock), eq(description), eq("Chinese"),
                any());
        ArgumentCaptor<List<ChunkVO>> persistCaptor = ArgumentCaptor.forClass(List.class);
        verify(chunkPersistenceService)
                .replaceForDocument(eq(DOCUMENT_ID), persistCaptor.capture(), eq(EMBED_PROFILE), isNull());
        assertEquals("template enhanced text", persistCaptor.getValue().get(0).text());
        // sidecar 抽取携带媒体主实体名（与主实体节点逐字一致，文档内同名归并），
        // 入参为落库态分块：真实主键 9001 绑定自落库回传映射（与 sequence=1 可区分）
        ArgumentCaptor<EntityExtractionContext> sidecarCaptor =
                ArgumentCaptor.forClass(EntityExtractionContext.class);
        ArgumentCaptor<List<PersistedChunkVO>> sidecarChunksCaptor = ArgumentCaptor.forClass(List.class);
        verify(entityExtractionService).extract(sidecarCaptor.capture(), sidecarChunksCaptor.capture());
        assertEquals("Alice Portrait", sidecarCaptor.getValue().mediaPrimaryEntityName());
        assertEquals(1, sidecarChunksCaptor.getValue().size());
        assertEquals(Long.valueOf(9001L), sidecarChunksCaptor.getValue().get(0).chunkId());
        assertSame(rebuilt, sidecarChunksCaptor.getValue().get(0).chunk());
        // LLM 与 VLM 同源：sidecar 抽取与 VLM 描述取到同一个 profileId（均出自 multi_model_config）
        assertEquals(LLM_PROFILE, sidecarCaptor.getValue().extractionModelProfileId());
        // 描述与 sidecar 抽取共用同一管线级计数器（命中次数跨阶段累加到收尾日志）
        assertSame(counterCaptor.getValue(), sidecarCaptor.getValue().llmCacheHits());
        // 合并入参含媒体主实体节点（唯一构造点在 Worker 收尾，抽取产物不再含主实体）：
        // 名称取定形实体名，描述取 primaryEntityDescription 口径（一行摘要优先、摘要空回落详描），
        // sourceIds 出生即真实分块主键 9001
        ArgumentCaptor<List<EntityNode>> nodesCaptor = ArgumentCaptor.forClass(List.class);
        verify(graphMergeService).mergeNodesAndEdges(any(), nodesCaptor.capture(), anyList());
        List<EntityNode> mergedNodes = nodesCaptor.getValue().stream()
                .filter(node -> "Alice Portrait".equals(node.entityName()))
                .toList();
        assertEquals(1, mergedNodes.size());
        assertEquals(List.of(9001L), mergedNodes.get(0).properties().sourceIds());
        assertEquals("one line", mergedNodes.get(0).properties().description(),
                "主实体描述应取一行摘要（详描留在块正文，不写入主实体描述）");
    }

    // ==================== 媒体描述缓存归属的延迟补登记 ====================

    /**
     * 场景：文档含 1 个媒体块，描述调用经键出参回报所用缓存键（Stage 2 尚无分块主键）。
     * 预期：Stage 3 落库拿到真实分块主键后、Stage 4 抽取发起前，以
     * 「真实主键 + 抽取分区 + 采集到的键」补登记归属——时序严格位于落库之后、抽取之前。
     */
    @Test
    void should_registerMediaAttributionAfterPersist_when_execute_given_mediaChunkWithCacheKey() {
        // given：单媒体块管线（sequence=1 → 落库主键 9001），描述回报键 media-key-1
        ContentBlockVO imageBlock = new ContentBlockVO(ContentBlockVO.TYPE_IMAGE, "raw caption", Map.of());
        stubSingleMediaChunkPipeline(imageBlock, "media-key-1");

        // when
        ingestionWorker.execute(DOCUMENT_ID);

        // then：补登记发生在整篇替换之后、抽取之前，且参数为真实主键与采集到的键
        InOrder order = inOrder(chunkPersistenceService, chunkExtractCacheRepository, entityExtractionService);
        order.verify(chunkPersistenceService)
                .replaceForDocument(eq(DOCUMENT_ID), anyList(), eq(EMBED_PROFILE), isNull());
        order.verify(chunkExtractCacheRepository).registerAll(KB_ID, 9001L, CacheType.EXTRACT,
                List.of("media-key-1"));
        order.verify(entityExtractionService).extract(any(), anyList());
        verify(chunkBatchWriter, never()).markFailed(anyLong(), anyString());
    }

    /**
     * 场景：文档含 2 个媒体块（sequence=1/2），描述分别回报各自的缓存键。
     * 预期：逐块按「下标 → 序号 → 真实主键」换算后各登记各的键，互不串号。
     */
    @Test
    void should_registerEachMediaChunkKey_when_execute_given_multipleMediaChunks() {
        // given：两个媒体块，落库回传映射为 9001/9002
        ContentBlockVO first = new ContentBlockVO(ContentBlockVO.TYPE_IMAGE, "图1", Map.of());
        ContentBlockVO second = new ContentBlockVO(ContentBlockVO.TYPE_TABLE, "表1", Map.of());
        stubContext(documentContext(ragEngineConfig(RagEngineType.DOCUMENT_ENGINE), embeddingConfig(),
                multiModelConfig()));
        when(parserRegistry.resolve(any())).thenReturn(documentParser);
        when(documentParser.parse(any(), any(), any()))
                .thenReturn(new ParsedDocument("hash-1", List.of(first, second)));
        when(chunkingService.chunkWithDispatch(any(), any(), any()))
                .thenReturn(new ChunkingOutcome(new java.util.ArrayList<>(List.of(
                        new ChunkVO(1, "raw one", 3, first), new ChunkVO(2, "raw two", 3, second))),
                        DocumentChunkMode.GENERAL));
        when(mediaDescriptorService.describe(eq(KB_ID), eq(LLM_PROFILE), eq("Chinese"), eq(first), any(), any()))
                .thenAnswer(invocation -> {
                    invocation.<AtomicReference<String>>getArgument(4).set("media-key-1");
                    return new MediaDescriptionVO("E1", "image", "s1", "d1", true);
                });
        when(mediaDescriptorService.describe(eq(KB_ID), eq(LLM_PROFILE), eq("Chinese"), eq(second), any(), any()))
                .thenAnswer(invocation -> {
                    invocation.<AtomicReference<String>>getArgument(4).set("media-key-2");
                    return new MediaDescriptionVO("E2", "table", "s2", "d2", true);
                });
        when(mediaChunkTemplateService.buildChunk(eq(1), eq(first), any(), eq("Chinese"), any()))
                .thenReturn(new ChunkVO(1, "enhanced one", 8, first));
        when(mediaChunkTemplateService.buildChunk(eq(2), eq(second), any(), eq("Chinese"), any()))
                .thenReturn(new ChunkVO(2, "enhanced two", 8, second));
        when(entityExtractionService.extract(any(), anyList())).thenReturn(EntityExtractionResult.empty());

        // when
        ingestionWorker.execute(DOCUMENT_ID);

        // then：两个媒体块各按自身真实主键登记自身缓存键
        verify(chunkExtractCacheRepository).registerAll(KB_ID, 9001L, CacheType.EXTRACT, List.of("media-key-1"));
        verify(chunkExtractCacheRepository).registerAll(KB_ID, 9002L, CacheType.EXTRACT, List.of("media-key-2"));
    }

    /**
     * 场景：媒体归属补登记仓储抛异常（归属仓储故障）。
     * 预期：摄入不受影响——不抛错、不回写 FAILED，抽取照常执行并最终置 PROCESSED。
     */
    @Test
    void should_continueIngestion_when_execute_given_mediaAttributionRegistrationThrows() {
        // given：单媒体块管线，归属登记抛异常
        ContentBlockVO imageBlock = new ContentBlockVO(ContentBlockVO.TYPE_IMAGE, "raw caption", Map.of());
        stubSingleMediaChunkPipeline(imageBlock, "media-key-1");
        doThrow(new RuntimeException("归属仓储不可用"))
                .when(chunkExtractCacheRepository).registerAll(any(), any(), any(), any());

        // when：execute 永不向外抛出（补登记失败仅 WARN 留痕）
        ingestionWorker.execute(DOCUMENT_ID);

        // then：抽取照常执行、成功终态照常写入、无失败回写
        verify(entityExtractionService).extract(any(), anyList());
        verify(chunkBatchWriter).markProcessed(DOCUMENT_ID);
        verify(chunkBatchWriter, never()).markFailed(anyLong(), anyString());
    }

    /**
     * 场景：纯文本文档（无任何多模态块）。
     * 预期：不产生任何归属补登记——仓储零交互。
     */
    @Test
    void should_notRegisterMediaAttribution_when_execute_given_noMediaChunks() {
        // given：纯文本管线
        stubContext(documentContext(null, embeddingConfig(), null));
        stubParseAndChunk(List.of(textChunk("Alice met Bob")));
        when(entityExtractionService.extract(any(), anyList())).thenReturn(EntityExtractionResult.empty());

        // when
        ingestionWorker.execute(DOCUMENT_ID);

        // then
        verifyNoInteractions(chunkExtractCacheRepository);
    }

    /**
     * 场景：媒体块描述未采集到缓存键（键出参未被写入，如键计算降级）。
     * 预期：空白键的配对直接跳过，不发起任何登记。
     */
    @Test
    void should_skipRegistration_when_execute_given_blankMediaCacheKey() {
        // given：单媒体块管线，描述不回报键（cacheKey 传 null 即不写键出参）
        ContentBlockVO imageBlock = new ContentBlockVO(ContentBlockVO.TYPE_IMAGE, "raw caption", Map.of());
        stubSingleMediaChunkPipeline(imageBlock, null);

        // when
        ingestionWorker.execute(DOCUMENT_ID);

        // then
        verifyNoInteractions(chunkExtractCacheRepository);
    }

    /**
     * 场景：媒体块有采集到的键，但落库回传映射缺失该序号（回传不变量被破坏）。
     * 预期：缺失主键的配对跳过登记（不写悬空归属），抽取阶段随后按既有语义快速失败。
     */
    @Test
    void should_skipRegistration_when_execute_given_missingChunkIdMapping() {
        // given：单媒体块管线 + 落库回传空映射
        ContentBlockVO imageBlock = new ContentBlockVO(ContentBlockVO.TYPE_IMAGE, "raw caption", Map.of());
        stubSingleMediaChunkPipeline(imageBlock, "media-key-1");
        when(chunkPersistenceService.replaceForDocument(eq(DOCUMENT_ID), anyList(), eq(EMBED_PROFILE), isNull()))
                .thenReturn(Map.of());

        // when
        ingestionWorker.execute(DOCUMENT_ID);

        // then：无归属登记；抽取因主键绑定失败而归位 EXTRACT
        verifyNoInteractions(chunkExtractCacheRepository);
        String message = assertFailedStage(IngestionStage.EXTRACT);
        assertTrue(message.contains("切片主键绑定失败"));
    }

    /**
     * 打桩单媒体块（sequence=1）的完整管线：解析 → 分块 → 描述（按需回报键出参）→ 模板重建 → 抽取空结果。
     *
     * @param imageBlock 媒体内容块
     * @param cacheKey   描述服务写入键出参的键值；{@code null} 表示本次描述不采集键
     */
    private void stubSingleMediaChunkPipeline(ContentBlockVO imageBlock, String cacheKey) {
        stubContext(documentContext(ragEngineConfig(RagEngineType.DOCUMENT_ENGINE), embeddingConfig(),
                multiModelConfig()));
        when(parserRegistry.resolve(any())).thenReturn(documentParser);
        when(documentParser.parse(any(), any(), any())).thenReturn(parsedDocument(imageBlock));
        when(chunkingService.chunkWithDispatch(any(), any(), any())).thenReturn(new ChunkingOutcome(
                new java.util.ArrayList<>(List.of(new ChunkVO(1, "original image text", 3, imageBlock))),
                DocumentChunkMode.GENERAL));
        when(mediaDescriptorService.describe(eq(KB_ID), eq(LLM_PROFILE), eq("Chinese"), eq(imageBlock), any(), any()))
                .thenAnswer(invocation -> {
                    if (ObjectUtils.isNotEmpty(cacheKey)) {
                        invocation.<AtomicReference<String>>getArgument(4).set(cacheKey);
                    }
                    return new MediaDescriptionVO("Alice Portrait", "image", "one line", "detailed caption", true);
                });
        when(mediaChunkTemplateService.buildChunk(eq(1), eq(imageBlock), any(), eq("Chinese"), any()))
                .thenReturn(new ChunkVO(1, "template enhanced text", 8, imageBlock));
        // 该打桩仅被走完 Stage 3 的用例消费；主键绑定失败提前终止的用例走不到 Stage 4，故置 lenient
        lenient().when(entityExtractionService.extract(any(), anyList()))
                .thenReturn(EntityExtractionResult.empty());
    }

    @Test
    void should_markFailedWithSourceFileMessage_when_execute_given_missingS3File() {
        // given
        stubContext(contextWithoutSourceFile());

        // when
        ingestionWorker.execute(DOCUMENT_ID);

        // then：Stage 0 前置校验失败归位 PARSE 阶段前缀
        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(chunkBatchWriter).markFailed(eq(DOCUMENT_ID), messageCaptor.capture());
        assertTrue(messageCaptor.getValue().startsWith(IngestionStage.PARSE.prefix()));
        assertTrue(messageCaptor.getValue().contains("源文件"));
        verifyNoInteractions(parserRegistry, chunkPersistenceService, graphMergeService);
    }

    @Test
    void should_markFailedWithLlmModelMessage_when_execute_given_multiModelConfigMissing() {
        // given：该库未配置 multi_model_config（上下文该分量为 null，取值须逐级判空不得 NPE）
        stubContext(contextWithoutLlmModel());

        // when
        ingestionWorker.execute(DOCUMENT_ID);

        // then：缺少 LLM 模型引用同属 Stage 0 校验失败，归位 PARSE 前缀，文案指向知识库配置键
        String message = assertFailedStage(IngestionStage.PARSE);
        assertTrue(message.contains("multi_model_config.modelProfileId"));
        verifyNoInteractions(parserRegistry);
    }

    @Test
    void should_markFailedWithLlmModelMessage_when_execute_given_blankModelProfileIdInMultiModelConfig() {
        // given：配置了 multi_model_config 但 profileId 为空白（值对象构造期禁止空白，
        //       故以 Mock 值对象模拟库中脏数据形态；该场景同样意味着该库无 VLM 可用）
        MultiModelConfig blankProfileModel = mock(MultiModelConfig.class);
        when(blankProfileModel.modelProfileId()).thenReturn("   ");
        stubContext(documentContext(ragEngineConfig(RagEngineType.DOCUMENT_ENGINE), embeddingConfig(),
                blankProfileModel));

        // when
        ingestionWorker.execute(DOCUMENT_ID);

        // then：按未配置模型引用显式失败，不得静默跳过抽取
        String message = assertFailedStage(IngestionStage.PARSE);
        assertTrue(message.contains("multi_model_config.modelProfileId"));
        verifyNoInteractions(parserRegistry);
    }

    @Test
    void should_extractAndSummarizeWithKbLlmProfile_when_execute_given_multiModelProfileConfigured() {
        // given：LLM profileId 唯一来源为知识库 multi_model_config（应用级 yaml 配置已移除）
        stubContext(documentContext(null, embeddingConfig(), new MultiModelConfig(KB_LLM_PROFILE)));
        stubParseAndChunk(List.of(textChunk("Alice met Bob")));
        stubTextExtraction(singleNodeResult("Alice", 9001L));
        when(ingestionSourceReader.listChunkIdentitiesByDocument(DOCUMENT_ID))
                .thenReturn(List.of(new ChunkIdentity(1, 9001L)));
        when(graphMergeService.mergeNodesAndEdges(any(), anyList(), anyList()))
                .thenReturn(GraphMergeReport.empty());

        // when
        ingestionWorker.execute(DOCUMENT_ID);

        // then：抽取与摘要上下文均使用知识库配置的 profileId，且无失败回写
        ArgumentCaptor<EntityExtractionContext> extractCaptor =
                ArgumentCaptor.forClass(EntityExtractionContext.class);
        verify(entityExtractionService).extract(extractCaptor.capture(), anyList());
        assertEquals(KB_LLM_PROFILE, extractCaptor.getValue().extractionModelProfileId());
        ArgumentCaptor<GraphMergeContext> mergeCaptor = ArgumentCaptor.forClass(GraphMergeContext.class);
        verify(graphMergeService).mergeNodesAndEdges(mergeCaptor.capture(), anyList(), anyList());
        assertEquals(KB_LLM_PROFILE, mergeCaptor.getValue().summaryModelProfileId());
        verify(chunkBatchWriter, never()).markFailed(anyLong(), anyString());
    }

    @Test
    void should_describeConcurrentlyPreserveOrder_when_execute_given_multipleMediaBlocks() {
        // given：两个多模态块，describe 按块序回吐可辨识描述；并发上限取默认 4，验证顺序与串行一致
        ContentBlockVO first = new ContentBlockVO(ContentBlockVO.TYPE_IMAGE, "图1", Map.of());
        ContentBlockVO second = new ContentBlockVO(ContentBlockVO.TYPE_TABLE, "表1", Map.of());
        ChunkVO firstChunk = new ChunkVO(1, "raw one", 3, first);
        ChunkVO secondChunk = new ChunkVO(2, "raw two", 3, second);
        stubContext(documentContext(ragEngineConfig(RagEngineType.DOCUMENT_ENGINE), embeddingConfig(),
                multiModelConfig()));
        when(parserRegistry.resolve(any())).thenReturn(documentParser);
        when(documentParser.parse(any(), any(), any()))
                .thenReturn(new ParsedDocument("hash-1", List.of(first, second)));
        when(chunkingService.chunkWithDispatch(any(), any(), any()))
                .thenReturn(new ChunkingOutcome(new java.util.ArrayList<>(List.of(firstChunk, secondChunk)),
                        DocumentChunkMode.GENERAL));
        when(mediaDescriptorService.describe(eq(KB_ID), eq(LLM_PROFILE), eq("Chinese"), eq(first), any(), any()))
                .thenReturn(new MediaDescriptionVO("E1", "image", "s1", "d1", true));
        when(mediaDescriptorService.describe(eq(KB_ID), eq(LLM_PROFILE), eq("Chinese"), eq(second), any(), any()))
                .thenReturn(new MediaDescriptionVO("E2", "table", "s2", "d2", true));
        when(mediaChunkTemplateService.buildChunk(eq(1), eq(first), any(), eq("Chinese"), any()))
                .thenReturn(new ChunkVO(1, "enhanced one", 8, first));
        when(mediaChunkTemplateService.buildChunk(eq(2), eq(second), any(), eq("Chinese"), any()))
                .thenReturn(new ChunkVO(2, "enhanced two", 8, second));
        when(entityExtractionService.extract(any(), anyList())).thenReturn(EntityExtractionResult.empty());
        when(ingestionSourceReader.listChunkIdentitiesByDocument(DOCUMENT_ID))
                .thenReturn(List.of(new ChunkIdentity(1, 9001L), new ChunkIdentity(2, 9002L)));
        when(graphMergeService.mergeNodesAndEdges(any(), anyList(), anyList()))
                .thenReturn(new GraphMergeReport(2, 0, 0, 0, 0));

        // when
        ingestionWorker.execute(DOCUMENT_ID);

        // then：落库切片顺序与串行一致（sequence↔内容对应不乱）
        ArgumentCaptor<List<ChunkVO>> persistCaptor = ArgumentCaptor.forClass(List.class);
        verify(chunkPersistenceService)
                .replaceForDocument(eq(DOCUMENT_ID), persistCaptor.capture(), eq(EMBED_PROFILE), isNull());
        List<ChunkVO> persisted = persistCaptor.getValue();
        assertEquals("enhanced one", persisted.get(0).text());
        assertEquals("enhanced two", persisted.get(1).text());
    }

    @Test
    void should_produceSameEnrichmentResult_when_execute_given_concurrencyOneVersusDefault() {
        // given：并发上限 1 与默认 4 对同一批多模态块产出一致的按序增强结果
        List<String> persistedTexts = new java.util.ArrayList<>();
        for (int concurrency : new int[]{1, 4}) {
            ReflectionTestUtils.setField(ingestionWorker, "describeConcurrency", concurrency);
            ContentBlockVO firstBlock = new ContentBlockVO(ContentBlockVO.TYPE_IMAGE, "图1", Map.of());
            ContentBlockVO secondBlock = new ContentBlockVO(ContentBlockVO.TYPE_IMAGE, "图2", Map.of());
            reset(ingestionSourceReader, parserRegistry, documentParser, chunkingService, chunkPersistenceService,
                    mediaDescriptorService, mediaChunkTemplateService, entityExtractionService, graphMergeService,
                    multimodalContextInjector);
            lenient().when(multimodalContextInjector.inject(any()))
                    .thenAnswer(invocation -> invocation.getArgument(0));
            // reset 抹掉了 setUp 的落库默认桩：Stage 4 身份绑定强依赖「序号→主键」映射，须重桩
            stubIdentityMapAnswer();
            stubContext(documentContext(ragEngineConfig(RagEngineType.DOCUMENT_ENGINE), embeddingConfig(),
                    multiModelConfig()));
            when(parserRegistry.resolve(any())).thenReturn(documentParser);
            when(documentParser.parse(any(), any(), any()))
                    .thenReturn(new ParsedDocument("hash-1", List.of(firstBlock, secondBlock)));
            when(chunkingService.chunkWithDispatch(any(), any(), any()))
                    .thenReturn(new ChunkingOutcome(new java.util.ArrayList<>(
                            List.of(new ChunkVO(1, "raw one", 3, firstBlock),
                                    new ChunkVO(2, "raw two", 3, secondBlock))),
                            DocumentChunkMode.GENERAL));
            when(mediaDescriptorService.describe(eq(KB_ID), eq(LLM_PROFILE), eq("Chinese"), eq(firstBlock), any(),
                    any())).thenReturn(new MediaDescriptionVO("E1", "image", "s1", "d1", true));
            when(mediaDescriptorService.describe(eq(KB_ID), eq(LLM_PROFILE), eq("Chinese"), eq(secondBlock), any(),
                    any())).thenReturn(new MediaDescriptionVO("E2", "image", "s2", "d2", true));
            when(mediaChunkTemplateService.buildChunk(eq(1), eq(firstBlock), any(), eq("Chinese"), any()))
                    .thenReturn(new ChunkVO(1, "enhanced one", 8, firstBlock));
            when(mediaChunkTemplateService.buildChunk(eq(2), eq(secondBlock), any(), eq("Chinese"), any()))
                    .thenReturn(new ChunkVO(2, "enhanced two", 8, secondBlock));
            when(entityExtractionService.extract(any(), anyList())).thenReturn(EntityExtractionResult.empty());

            // when
            ingestionWorker.execute(DOCUMENT_ID);

            ArgumentCaptor<List<ChunkVO>> persistCaptor = ArgumentCaptor.forClass(List.class);
            verify(chunkPersistenceService)
                    .replaceForDocument(eq(DOCUMENT_ID), persistCaptor.capture(), eq(EMBED_PROFILE), isNull());
            persistedTexts.add(persistCaptor.getValue().get(0).text() + "|" + persistCaptor.getValue().get(1).text());
        }
        ReflectionTestUtils.setField(ingestionWorker, "describeConcurrency", 4);

        // then：两种并发上限产出的顺序化增强结果完全一致
        assertEquals(2, persistedTexts.size());
        assertEquals("enhanced one|enhanced two", persistedTexts.get(0));
        assertEquals(persistedTexts.get(0), persistedTexts.get(1));
    }

    /**
     * 场景（3.2）：并发闸门 {@code describe.concurrency}=2，媒体块 5（超过闸门）。
     * 预期：同时在执行的描述数不超过闸门 2（且达上限），全部 5 块均被提交描述，摄入不因限流而失败。
     */
    @Test
    void should_limitConcurrencyToGate_when_describeConcurrently_given_blocksExceedGate() {
        // given
        ReflectionTestUtils.setField(ingestionWorker, "describeConcurrency", 2);
        int blockCount = 5;
        List<ContentBlockVO> blocks = new ArrayList<>();
        List<ChunkVO> chunks = new ArrayList<>();
        for (int i = 0; i < blockCount; i++) {
            ContentBlockVO block = new ContentBlockVO(ContentBlockVO.TYPE_IMAGE, "图" + i, Map.of());
            blocks.add(block);
            chunks.add(new ChunkVO(i + 1, "raw " + i, 3, block));
        }
        stubContext(documentContext(ragEngineConfig(RagEngineType.DOCUMENT_ENGINE), embeddingConfig(),
                multiModelConfig()));
        when(parserRegistry.resolve(any())).thenReturn(documentParser);
        when(documentParser.parse(any(), any(), any())).thenReturn(new ParsedDocument("hash-1", blocks));
        when(chunkingService.chunkWithDispatch(any(), any(), any()))
                .thenReturn(new ChunkingOutcome(chunks, DocumentChunkMode.GENERAL));
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        when(mediaDescriptorService.describe(any(), any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            int current = active.incrementAndGet();
            peak.accumulateAndGet(current, Math::max);
            try {
                TimeUnit.MILLISECONDS.sleep(20L);
            } finally {
                active.decrementAndGet();
            }
            return new MediaDescriptionVO("E", "image", "s", "d", true);
        });
        when(mediaChunkTemplateService.buildChunk(anyInt(), any(), any(), any(), any())).thenAnswer(invocation -> {
            Integer sequence = invocation.getArgument(0);
            ContentBlockVO block = invocation.getArgument(1);
            return new ChunkVO(sequence, "enhanced", 8, block);
        });
        when(entityExtractionService.extract(any(), anyList())).thenReturn(EntityExtractionResult.empty());

        // when
        ingestionWorker.execute(DOCUMENT_ID);

        // then：峰值不超过闸门且触达闸门；全部块均被提交描述；无失败回写
        assertTrue(peak.get() <= 2, "并发峰值应不超过 describe.concurrency，实际：" + peak.get());
        assertEquals(2, peak.get(), "块数超过闸门时应达到并发上限 2");
        verify(mediaDescriptorService, times(blockCount)).describe(any(), any(), any(), any(), any(), any());
        verify(chunkBatchWriter, never()).markFailed(anyLong(), anyString());
    }

    /**
     * 场景（3.3）：闸门=1，两块；首块持闸阻塞，次块等待闸门时被中断。
     * 预期：次块描述从未执行（被中断即放弃任务体），但中断被记为首个失败使阶段判定失败
     * （归位 DESCRIBE、回写 FAILED），杜绝 {@code allOf().join()} 正常返回后带不完整结果继续落库的
     * 「静默部分成功」。
     * <p><b>为何次块任务要挂启动闸门</b>：{@code Semaphore} 非公平且虚拟线程拿到载体的顺序不可控，
     * 两个任务同时开始竞争时，次块完全可能抢在首块之前取到唯一许可并正常完成描述——那样
     * 「等待闸门时被中断」这一被测场景根本不成立，用例只会在竞争落败时假失败。故次块任务先停在
     * {@code secondMayStart} 之外，等测试确认首块已持闸（{@code firstHolding} 已释放）才进入任务体，
     * 使「首块持闸、次块等闸」成为确定时序。停在启动闸门上被中断时恢复中断标记后再进入任务体，
     * 中断语义与原场景一致（由生产侧闸门等待逻辑判定失败），MUST NOT 吞掉中断。</p>
     */
    @Test
    void should_failStage_when_describeConcurrently_given_taskInterrupted() throws Exception {
        // given
        ReflectionTestUtils.setField(ingestionWorker, "describeConcurrency", 1);
        ContentBlockVO first = new ContentBlockVO(ContentBlockVO.TYPE_IMAGE, "图1", Map.of());
        ContentBlockVO second = new ContentBlockVO(ContentBlockVO.TYPE_TABLE, "表2", Map.of());
        stubContext(documentContext(ragEngineConfig(RagEngineType.DOCUMENT_ENGINE), embeddingConfig(),
                multiModelConfig()));
        when(parserRegistry.resolve(any())).thenReturn(documentParser);
        when(documentParser.parse(any(), any(), any())).thenReturn(new ParsedDocument("hash-1", List.of(first, second)));
        when(chunkingService.chunkWithDispatch(any(), any(), any()))
                .thenReturn(new ChunkingOutcome(new ArrayList<>(List.of(
                        new ChunkVO(1, "raw one", 3, first), new ChunkVO(2, "raw two", 3, second))),
                        DocumentChunkMode.GENERAL));
        CountDownLatch firstHolding = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondMayStart = new CountDownLatch(1);
        when(mediaDescriptorService.describe(eq(KB_ID), any(), any(), eq(first), any(), any())).thenAnswer(invocation -> {
            firstHolding.countDown();
            releaseFirst.await(ASYNC_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            return new MediaDescriptionVO("E1", "image", "s1", "d1", true);
        });
        lenient().when(mediaDescriptorService.describe(eq(KB_ID), any(), any(), eq(second), any(), any()))
                .thenReturn(new MediaDescriptionVO("E2", "table", "s2", "d2", true));
        // 真实扇出执行器（非 Mock，任务确被执行），按提交顺序记录线程句柄以精确中断等待闸门的次块
        List<Thread> spawned = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger submissions = new AtomicInteger();
        Executor interruptAwareExecutor = task -> {
            if (submissions.incrementAndGet() == 1) {
                spawned.add(Thread.ofVirtual().start(task));
                return;
            }
            spawned.add(Thread.ofVirtual().start(() -> {
                try {
                    secondMayStart.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                task.run();
            }));
        };
        ReflectionTestUtils.setField(ingestionWorker, "ingestionExecutor", interruptAwareExecutor);

        // when：管线线程外推进；首块持闸后放行并中断等待闸门的次块，随后放行首块
        Thread workerThread = Thread.ofVirtual().start(() -> ingestionWorker.execute(DOCUMENT_ID));
        assertTrue(firstHolding.await(ASYNC_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS), "首块应已获取闸门并持锁");
        awaitSpawnedCount(spawned, 2);
        secondMayStart.countDown();
        spawned.get(1).interrupt();
        releaseFirst.countDown();
        workerThread.join(ASYNC_TIMEOUT_MILLIS);

        // then：次块描述从未被执行；阶段判定失败归位 DESCRIBE（中断记为失败，非静默跳过）
        assertFalse(workerThread.isAlive(), "被中断后阶段应在聚合收口后判定失败并收尾");
        verify(mediaDescriptorService, never()).describe(eq(KB_ID), any(), any(), eq(second), any(), any());
        String message = assertFailedStage(IngestionStage.DESCRIBE);
        assertTrue(message.contains("中断"), "失败文案应体现闸门等待被中断，实际：" + message);
    }

    /**
     * 场景（3.4）：闸门=1，首块描述抛异常、次块正常。
     * 预期：聚合收口后原样回抛首个失败异常（其原文进入 FAILED 文案），两块均被提交描述。
     */
    @Test
    void should_rethrowFirstFailure_when_describeConcurrently_given_firstBlockFails() {
        // given
        ReflectionTestUtils.setField(ingestionWorker, "describeConcurrency", 1);
        ContentBlockVO first = new ContentBlockVO(ContentBlockVO.TYPE_IMAGE, "图1", Map.of());
        ContentBlockVO second = new ContentBlockVO(ContentBlockVO.TYPE_TABLE, "表2", Map.of());
        stubContext(documentContext(ragEngineConfig(RagEngineType.DOCUMENT_ENGINE), embeddingConfig(),
                multiModelConfig()));
        when(parserRegistry.resolve(any())).thenReturn(documentParser);
        when(documentParser.parse(any(), any(), any())).thenReturn(new ParsedDocument("hash-1", List.of(first, second)));
        when(chunkingService.chunkWithDispatch(any(), any(), any()))
                .thenReturn(new ChunkingOutcome(new ArrayList<>(List.of(
                        new ChunkVO(1, "raw one", 3, first), new ChunkVO(2, "raw two", 3, second))),
                        DocumentChunkMode.GENERAL));
        when(mediaDescriptorService.describe(eq(KB_ID), any(), any(), eq(first), any(), any()))
                .thenThrow(new IllegalStateException("首块-VLM-失败"));
        lenient().when(mediaDescriptorService.describe(eq(KB_ID), any(), any(), eq(second), any(), any()))
                .thenReturn(new MediaDescriptionVO("E2", "table", "s2", "d2", true));

        // when
        ingestionWorker.execute(DOCUMENT_ID);

        // then：失败归位 DESCRIBE 且回抛首块异常原文；两块均被提交
        String message = assertFailedStage(IngestionStage.DESCRIBE);
        assertTrue(message.contains("首块-VLM-失败"), "应原样回抛首个失败异常，实际：" + message);
        verify(mediaDescriptorService).describe(eq(KB_ID), any(), any(), eq(first), any(), any());
        verify(mediaDescriptorService).describe(eq(KB_ID), any(), any(), eq(second), any(), any());
    }

    /**
     * 自旋等待扇出执行器按序启动的线程数达到期望值（供中断特定时序下的等待闸门任务）。
     *
     * @param spawned 已启动线程句柄
     * @param expected 期望数量
     * @throws InterruptedException 等待被中断
     */
    private void awaitSpawnedCount(List<Thread> spawned, int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + ASYNC_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (spawned.size() >= expected) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(10L);
        }
        org.junit.jupiter.api.Assertions.fail("扇出任务线程启动数量未在限定时间内达到 " + expected);
    }

    @Test
    void should_markFailedWithChunkPrefix_when_execute_given_parserProducedNoChunks() {
        // given：解析有产物但分块未产出任何内容块（该判定归位 CHUNK 阶段而非 PARSE）
        stubContext(documentContext(null, embeddingConfig(), null));
        stubParseAndChunk(List.of());

        // when
        ingestionWorker.execute(DOCUMENT_ID);

        // then
        String message = assertFailedStage(IngestionStage.CHUNK);
        assertTrue(message.contains("未产出任何内容块"));
        verify(chunkPersistenceService, never()).replaceForDocument(any(), anyList(), any(), any());
    }

    @Test
    void should_markFailedWithDescribePrefix_when_execute_given_mediaDescriptionThrows() {
        // given：文档引擎 + 多模态块 + 已配模型，Stage 2 VLM 描述调用失败（调用层异常仍归位 DESCRIBE）
        ContentBlockVO imageBlock = new ContentBlockVO(ContentBlockVO.TYPE_IMAGE, "raw caption", Map.of());
        stubContext(documentContext(ragEngineConfig(RagEngineType.DOCUMENT_ENGINE), embeddingConfig(),
                multiModelConfig()));
        stubParseAndChunk(imageBlock, List.of(new ChunkVO(1, "original image text", 3, imageBlock)));
        when(mediaDescriptorService.describe(eq(KB_ID), eq(LLM_PROFILE), eq("Chinese"), eq(imageBlock), any(),
                any())).thenThrow(new IllegalStateException("VLM 服务不可用"));

        // when
        ingestionWorker.execute(DOCUMENT_ID);

        // then：描述失败归位 DESCRIBE，切片尚未落库
        assertFailedStage(IngestionStage.DESCRIBE);
        verify(chunkPersistenceService, never()).replaceForDocument(any(), anyList(), any(), any());
    }

    @Test
    void should_markFailedWithEmbedPrefix_when_execute_given_chunkPersistenceThrows() {
        // given：Stage 3 切片整篇替换落库失败（文档仍在处理中，失败回写生效）
        stubContext(documentContext(null, embeddingConfig(), null));
        stubParseAndChunk(List.of(textChunk("alpha text")));
        doThrow(new IllegalStateException("落库失败"))
                .when(chunkPersistenceService)
                .replaceForDocument(any(), anyList(), any(), any());

        // when
        ingestionWorker.execute(DOCUMENT_ID);

        // then：向量化落库失败归位 EMBED，不进入抽取与合并
        assertFailedStage(IngestionStage.EMBED);
        verifyNoInteractions(entityExtractionService, graphMergeService);
    }

    @Test
    void should_markFailedWithExtractPrefix_when_execute_given_extractionThrows() {
        // given：Stage 4 实体抽取失败（切片已落库，产物保留）
        stubContext(documentContext(null, embeddingConfig(), null));
        stubParseAndChunk(List.of(textChunk("Alice met Bob")));
        when(entityExtractionService.extract(any(), anyList()))
                .thenThrow(new IllegalStateException("抽取失败"));

        // when
        ingestionWorker.execute(DOCUMENT_ID);

        // then
        assertFailedStage(IngestionStage.EXTRACT);
        verify(chunkPersistenceService)
                .replaceForDocument(eq(DOCUMENT_ID), anyList(), eq(EMBED_PROFILE), isNull());
        verifyNoInteractions(graphMergeService);
    }

    @Test
    void should_markFailedWithMergePrefix_when_execute_given_graphMergeThrows() {
        // given：Stage 5 图合并写入失败
        stubContext(documentContext(null, embeddingConfig(), null));
        stubParseAndChunk(List.of(textChunk("Alice met Bob")));
        stubTextExtraction(singleNodeResult("Alice", 9001L));
        when(ingestionSourceReader.listChunkIdentitiesByDocument(DOCUMENT_ID))
                .thenReturn(List.of(new ChunkIdentity(1, 9001L)));
        when(graphMergeService.mergeNodesAndEdges(any(), anyList(), anyList()))
                .thenThrow(new IllegalStateException("图谱写入失败"));

        // when
        ingestionWorker.execute(DOCUMENT_ID);

        // then：合并失败归位 MERGE，抽取（含身份绑定）已在合并前完成
        assertFailedStage(IngestionStage.MERGE);
        // 身份列举仅一次：Stage 3 前旧代际预查捕获（抽取身份取落库回传映射，不再回查）
        verify(ingestionSourceReader, times(1)).listChunkIdentitiesByDocument(DOCUMENT_ID);
    }

    /**
     * 场景：落库回传的「序号→主键」映射缺失分块引用的序号（回传不变量被破坏，
     * 如契约实现缺陷或 Mock 显式给出空映射）。
     * 预期：Stage 4 身份绑定快速失败归位 EXTRACT 并回写失败（文案含「切片主键绑定失败」），
     * 抽取根本不发起、不进入合并，SHALL NOT 让任何非真实主键以来源键形态进入图谱账本。
     */
    @Test
    void should_failExtractWithBindingError_when_execute_given_missingChunkIdMapping() {
        // given：Stage 3 返回空映射（本批分块的序号无任何主键可绑定）
        stubContext(documentContext(null, embeddingConfig(), null));
        stubParseAndChunk(List.of(textChunk("Alice met Bob")));
        when(ingestionSourceReader.listChunkIdentitiesByDocument(DOCUMENT_ID)).thenReturn(List.of());
        when(chunkPersistenceService.replaceForDocument(eq(DOCUMENT_ID), anyList(),
                eq(EMBED_PROFILE), isNull())).thenReturn(Map.of());

        // when
        ingestionWorker.execute(DOCUMENT_ID);

        // then：EXTRACT 失败回写且文案定位绑定失败，抽取与合并零触碰
        String message = assertFailedStage(IngestionStage.EXTRACT);
        assertTrue(message.contains("切片主键绑定失败"));
        verifyNoInteractions(entityExtractionService, graphMergeService);
    }

    @Test
    void should_markFailedWithCancelledPrefix_when_execute_given_cancelledAtStageBoundary() {
        // given：Stage 2 后边界取消检查读到 PENDING（用户重新解析），失败回写前复查又回到 PROCESSING
        //       ——竞态窗口下走失败回写，CANCELLED 前缀使运维可与真实故障区分
        stubContext(documentContext(null, embeddingConfig(), null),
                DocumentStatus.PENDING, DocumentStatus.PROCESSING);
        stubParseAndChunk(List.of(textChunk("alpha text")));

        // when
        ingestionWorker.execute(DOCUMENT_ID);

        // then：中断发生在切片落库之前，无任何持久化副作用
        assertFailedStage(IngestionStage.CANCELLED);
        verify(chunkPersistenceService, never()).replaceForDocument(any(), anyList(), any(), any());
        verifyNoInteractions(entityExtractionService, graphMergeService);
    }

    @Test
    void should_markFailedWithUnknownStagePrefix_when_execute_given_exceptionOutsideStageScope() {
        // given：嵌入模型 profileId 解析位于所有 runStage 片段之外，该处异常无法归位到任一管线阶段
        IngestionDocumentContext ctx = mock(IngestionDocumentContext.class);
        when(ctx.s3File()).thenReturn(new S3File("rag/7/source/object.pdf"));
        when(ctx.multiModelConfig()).thenReturn(new MultiModelConfig(LLM_PROFILE));
        when(ctx.embeddingConfig()).thenThrow(new IllegalStateException("嵌入配置读取失败"));
        when(ingestionSourceReader.readContext(DOCUMENT_ID)).thenReturn(ctx);
        when(ingestionSourceReader.statusOf(DOCUMENT_ID)).thenReturn(DocumentStatus.PROCESSING);
        stubParseAndChunk(List.of(textChunk("alpha text")));

        // when
        ingestionWorker.execute(DOCUMENT_ID);

        // then：前缀永不缺位，未归位异常回落 UNKNOWN_STAGE
        assertFailedStage(IngestionStage.UNKNOWN_STAGE);
        verify(chunkPersistenceService, never()).replaceForDocument(any(), anyList(), any(), any());
    }

    @Test
    void should_skipFailureWriteBack_when_execute_given_documentRolledBackToPending() {
        // given：落库阶段异常，且文档已被用户重新解析置 PENDING → 视为已取消，静默返回
        //       （替换前两道取消闸门均读到 PROCESSING，异常从整篇替换处抛出）
        stubContext(documentContext(null, embeddingConfig(), null),
                DocumentStatus.PROCESSING, DocumentStatus.PROCESSING, DocumentStatus.PENDING);
        stubParseAndChunk(List.of(textChunk("alpha text")));
        doThrow(new IllegalStateException("落库失败"))
                .when(chunkPersistenceService)
                .replaceForDocument(any(), anyList(), any(), any());

        // when
        ingestionWorker.execute(DOCUMENT_ID);

        // then：取消场景不覆盖用户操作触发的新状态
        verify(chunkBatchWriter, never()).markFailed(anyLong(), anyString());
        verifyNoInteractions(entityExtractionService, graphMergeService);
    }

    @Test
    void should_skipFailureWriteBack_when_execute_given_documentDeletingAtBoundary() {
        // given：删除链已置 DELETING，阶段边界取消检查须视为已取消
        stubContext(documentContext(null, embeddingConfig(), null), DocumentStatus.DELETING);
        stubParseAndChunk(List.of(textChunk("alpha text")));

        // when
        ingestionWorker.execute(DOCUMENT_ID);

        // then：掐灭在切片落库之前，静默返回不回写 FAILED
        verify(chunkPersistenceService, never()).replaceForDocument(any(), anyList(), any(), any());
        verifyNoInteractions(entityExtractionService, graphMergeService);
        verify(chunkBatchWriter, never()).markFailed(anyLong(), anyString());
    }

    @Test
    void should_skipFailureWriteBack_when_execute_given_documentDeleteFailedAtBoundary() {
        // given：DELETE_FAILED（删除失败待重试）同样视为已取消，禁止在飞 worker 继续回写
        stubContext(documentContext(null, embeddingConfig(), null), DocumentStatus.DELETE_FAILED);
        stubParseAndChunk(List.of(textChunk("alpha text")));

        // when
        ingestionWorker.execute(DOCUMENT_ID);

        // then：掐灭在切片落库之前，静默返回不回写 FAILED
        verify(chunkPersistenceService, never()).replaceForDocument(any(), anyList(), any(), any());
        verifyNoInteractions(entityExtractionService, graphMergeService);
        verify(chunkBatchWriter, never()).markFailed(anyLong(), anyString());
    }

    @Test
    void should_skipFailureWriteBack_when_execute_given_documentRowMissing() {
        // given：行缺失（statusOf 返回 null）＝删除链已收口，「已删除」唯一表达是行物理缺失
        stubContext(documentContext(null, embeddingConfig(), null), (DocumentStatus) null);
        stubParseAndChunk(List.of(textChunk("alpha text")));

        // when
        ingestionWorker.execute(DOCUMENT_ID);

        // then：掐灭在切片落库之前，静默返回不回写 FAILED
        verify(chunkPersistenceService, never()).replaceForDocument(any(), anyList(), any(), any());
        verifyNoInteractions(entityExtractionService, graphMergeService);
        verify(chunkBatchWriter, never()).markFailed(anyLong(), anyString());
    }

    @Test
    void should_cancelAfterChunkPersist_when_execute_given_documentDeletingAfterStage3() {
        // given：整篇替换前的两道闸门均为 PROCESSING，落库回写后删除链置 DELETING → Stage 3→4 边界掐灭
        stubContext(documentContext(null, embeddingConfig(), null),
                DocumentStatus.PROCESSING, DocumentStatus.PROCESSING,
                DocumentStatus.DELETING, DocumentStatus.DELETING);
        stubParseAndChunk(List.of(textChunk("alpha text")));

        // when
        ingestionWorker.execute(DOCUMENT_ID);

        // then：切片已整篇替换（回写闸门之外的窗口），但不启动抽取与合并，静默返回不回写 FAILED
        verify(chunkPersistenceService, times(1))
                .replaceForDocument(eq(DOCUMENT_ID), anyList(), eq(EMBED_PROFILE), isNull());
        verifyNoInteractions(entityExtractionService, graphMergeService);
        verify(chunkBatchWriter, never()).markFailed(anyLong(), anyString());
    }

    /**
     * 场景：重解析换代——文档已有旧代切片，图谱来源收敛已前移：
     * 预查捕获旧代 ID 后、Stage 3 整篇替换之前同步收敛图谱贡献账本（分块引用完整性不变量：
     * 「分块行消失」永远不得先于图谱账本清退）。
     * 预期：预查 → prepare(kbId, docId, 旧代 ID) → apply → 提交后收口 → 整篇替换 → 抽取，
     * 严格顺序且无失败回写。
     */
    @Test
    void should_convergeOldGenerationBeforeStage3Replacement_when_execute_given_reingestionWithPreviousChunks() {
        // given：身份预查仅一次（旧代 8001，Stage 3 替换前捕获）；抽取入参主键取落库默认桩回传（9001）
        stubContext(documentContext(null, embeddingConfig(), null));
        stubParseAndChunk(List.of(textChunk("Alice met Bob")));
        stubTextExtraction(singleNodeResult("Alice", 9001L));
        when(ingestionSourceReader.listChunkIdentitiesByDocument(DOCUMENT_ID))
                .thenReturn(List.of(new ChunkIdentity(1, OLD_CHUNK_ID)));
        when(graphMergeService.mergeNodesAndEdges(any(), anyList(), anyList()))
                .thenReturn(GraphMergeReport.empty());

        // when
        ingestionWorker.execute(DOCUMENT_ID);

        // then：预查 → 收敛（prepare+apply+提交后收口，整段先于替换）→ 落库 → 抽取的严格时序，
        //       收敛入参为旧代 ID 单元素集合、审计锚点为本文档
        InOrder order = inOrder(ingestionSourceReader, documentGraphConvergenceApi,
                chunkPersistenceService, entityExtractionService);
        order.verify(ingestionSourceReader).listChunkIdentitiesByDocument(DOCUMENT_ID);
        order.verify(documentGraphConvergenceApi)
                .prepare(eq(KB_ID), eq(DOCUMENT_ID), eq(List.of(OLD_CHUNK_ID)), isNull());
        order.verify(documentGraphConvergenceApi).apply(any());
        order.verify(documentGraphConvergenceApi).reconcilePendingVectorContent(any(), any());
        order.verify(chunkPersistenceService)
                .replaceForDocument(eq(DOCUMENT_ID), anyList(), eq(EMBED_PROFILE), isNull());
        order.verify(entityExtractionService).extract(any(), anyList());
        verify(chunkBatchWriter, never()).markFailed(anyLong(), anyString());
    }

    /**
     * 场景：首次摄入（文档无旧代切片，预查身份为空列表）。
     * 预期：正常落库置 PROCESSED，但收敛零调用——不产生任何图谱写操作。
     */
    @Test
    void should_skipConvergenceWithZeroGraphWrites_when_execute_given_firstIngestionWithoutPreviousChunks() {
        // given
        stubContext(documentContext(null, embeddingConfig(), null));
        stubParseAndChunk(List.of(textChunk("Alice met Bob")));
        when(ingestionSourceReader.listChunkIdentitiesByDocument(DOCUMENT_ID)).thenReturn(List.of());
        when(entityExtractionService.extract(any(), anyList())).thenReturn(EntityExtractionResult.empty());

        // when
        ingestionWorker.execute(DOCUMENT_ID);

        // then
        verify(chunkPersistenceService)
                .replaceForDocument(eq(DOCUMENT_ID), anyList(), eq(EMBED_PROFILE), isNull());
        verify(documentGraphConvergenceApi, never()).prepare(any(), any(), any(), any());
        verify(documentGraphConvergenceApi, never()).apply(any());
        verify(documentGraphConvergenceApi, never()).reconcilePendingVectorContent(any(), any());
    }

    /**
     * 场景：重解析旧代际收敛调用抛异常（模拟数据库死锁，图谱原语故障）。
     * 预期（fail-closed）：中止本次摄入——异常归位 EMBED 阶段上抛，整篇替换
     * （{@code replaceForDocument}）MUST NOT 执行、抽取与合并零触碰，
     * 回写失败态且 errorMessage 携带 [EMBED] 分类前缀。
     */
    @Test
    void should_abortIngestionWithoutReplace_when_execute_given_oldGenerationConvergenceThrows() {
        // given：旧代预查返回单条（8001）；收敛调用抛运行时异常；状态回查恒为 PROCESSING（非取消，失败回写生效）
        stubContext(documentContext(null, embeddingConfig(), null));
        stubParseAndChunk(List.of(textChunk("Alice met Bob")));
        when(ingestionSourceReader.listChunkIdentitiesByDocument(DOCUMENT_ID))
                .thenReturn(List.of(new ChunkIdentity(1, OLD_CHUNK_ID)));
        when(documentGraphConvergenceApi.prepare(eq(KB_ID), eq(DOCUMENT_ID), anyList(), isNull()))
                .thenThrow(new IllegalStateException("Deadlock found when trying to get lock; try restarting transaction"));

        // when
        ingestionWorker.execute(DOCUMENT_ID);

        // then：收敛失败即中止——未执行整篇替换，抽取与合并零触碰，失败回写归位 EMBED 前缀
        verify(chunkPersistenceService, never()).replaceForDocument(any(), anyList(), any(), any());
        verify(chunkBatchWriter).markFailed(eq(DOCUMENT_ID), contains("[EMBED]"));
        verifyNoInteractions(entityExtractionService, graphMergeService);
    }

    /**
     * 场景：重解析含旧代 ID，旧代际收敛已成功提交，但「收敛 → 替换」之间的取消闸门读到
     * DELETING（删除链掐灭在飞 worker）。
     * 预期：放弃整篇替换——留下「旧分块仍在、图谱贡献已清」的可自愈窗口（下次重解析幂等收敛零行），
     * 不启动抽取、不回写 FAILED，管线静默收敛。
     */
    @Test
    void should_skipReplaceAfterConverged_when_execute_given_documentDeletingAtConvergeGate() {
        // given：首个边界检查 PROCESSING（收敛照常前移执行），收敛后的闸门读到删除链已置 DELETING
        stubContext(documentContext(null, embeddingConfig(), null),
                DocumentStatus.PROCESSING, DocumentStatus.DELETING, DocumentStatus.DELETING);
        stubParseAndChunk(List.of(textChunk("alpha text")));
        when(ingestionSourceReader.listChunkIdentitiesByDocument(DOCUMENT_ID))
                .thenReturn(List.of(new ChunkIdentity(1, OLD_CHUNK_ID)));

        // when
        ingestionWorker.execute(DOCUMENT_ID);

        // then：旧代收敛已先提交（prepare+apply+收口三步全触达），整篇替换被掐灭，抽取与合并零触碰，
        //       且不回写 FAILED
        verify(documentGraphConvergenceApi).prepare(KB_ID, DOCUMENT_ID, List.of(OLD_CHUNK_ID), null);
        verify(documentGraphConvergenceApi).apply(any());
        verify(documentGraphConvergenceApi).reconcilePendingVectorContent(any(), any());
        verify(chunkPersistenceService, never()).replaceForDocument(any(), anyList(), any(), any());
        verifyNoInteractions(entityExtractionService, graphMergeService);
        verify(chunkBatchWriter, never()).markFailed(anyLong(), anyString());
    }

    @Test
    void should_markFailedWithMergePrefix_when_execute_given_missingEmbeddingConfig() {
        // given：库未配置嵌入模型引用 → 切片向量降级落库，Stage 5 因无法构建向量身份判定失败
        stubContext(documentContext(null, null, null));
        stubParseAndChunk(List.of(textChunk("alpha text")));
        stubTextExtraction(singleNodeResult("Alice", 9001L));
        when(ingestionSourceReader.listChunkIdentitiesByDocument(DOCUMENT_ID))
                .thenReturn(List.of(new ChunkIdentity(1, 9001L)));

        // when
        ingestionWorker.execute(DOCUMENT_ID);

        // then：向量降级落库口径不变，合并阶段显式失败并回写 FAILED——
        // 不再产出「有切片、无向量、无图谱」的假成功 PROCESSED 文档
        verify(chunkPersistenceService)
                .replaceForDocument(eq(DOCUMENT_ID), anyList(), isNull(), isNull());
        String message = assertFailedStage(IngestionStage.MERGE);
        assertTrue(message.contains("嵌入模型"));
        verifyNoInteractions(graphMergeService);
        verify(chunkBatchWriter, never()).markProcessed(anyLong());
    }

    @Test
    void should_skipMergeAndIdentityLookup_when_execute_given_emptyExtractionResult() {
        // given：未抽取到任何实体与关系 → 跳过图合并
        stubContext(documentContext(null, embeddingConfig(), null));
        stubParseAndChunk(List.of(textChunk("alpha text")));
        when(entityExtractionService.extract(any(), anyList())).thenReturn(EntityExtractionResult.empty());

        // when
        ingestionWorker.execute(DOCUMENT_ID);

        // then
        verify(chunkPersistenceService)
                .replaceForDocument(eq(DOCUMENT_ID), anyList(), eq(EMBED_PROFILE), isNull());
        // 身份列举仅一次 = Stage 3 前旧代际预查（首摄默认空）
        verify(ingestionSourceReader, times(1)).listChunkIdentitiesByDocument(DOCUMENT_ID);
        verifyNoInteractions(graphMergeService);
        // 早退路径（未抽取到实体与关系）同样走收尾置终态：切片落库不足以为终态，
        // 由收尾统一置 PROCESSED，且无失败回写
        verify(chunkBatchWriter).markProcessed(DOCUMENT_ID);
        verify(chunkBatchWriter, never()).markFailed(anyLong(), anyString());
    }

    @Test
    void should_rerunWholePipeline_when_execute_given_failedDocumentRetried() {
        // given：失败重摄（清空重建重跑）——管线无中间态依赖，第二次执行完整重走并整篇替换
        stubContext(documentContext(null, embeddingConfig(), null));
        stubParseAndChunk(List.of(textChunk("Alice met Bob")));
        stubTextExtraction(singleNodeResult("Alice", 9001L));
        when(ingestionSourceReader.listChunkIdentitiesByDocument(DOCUMENT_ID))
                .thenReturn(List.of(new ChunkIdentity(1, 9001L)));
        when(graphMergeService.mergeNodesAndEdges(any(), anyList(), anyList()))
                .thenReturn(GraphMergeReport.empty());

        // when
        ingestionWorker.execute(DOCUMENT_ID);
        ingestionWorker.execute(DOCUMENT_ID);

        // then：两次均为整篇替换语义（幂等收敛为入参集合），失败标记均未触发
        verify(chunkPersistenceService, times(2))
                .replaceForDocument(eq(DOCUMENT_ID), anyList(), eq(EMBED_PROFILE), isNull());
        verify(chunkBatchWriter, never()).markFailed(anyLong(), anyString());
    }

    @Test
    void should_logDispatchInfo_when_execute_given_successfulChunking() {
        // given：直通留痕日志断言，
        // ListAppender 临时挂载到 Worker logger 捕获 INFO 事件
        Logger workerLogger = (Logger) LoggerFactory.getLogger(IngestionWorker.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        workerLogger.addAppender(appender);
        try {
            stubContext(documentContext(null, embeddingConfig(), null));
            stubParseAndChunk(List.of(textChunk("Alice met Bob")));
            when(entityExtractionService.extract(any(), anyList())).thenReturn(EntityExtractionResult.empty());

            // when
            ingestionWorker.execute(DOCUMENT_ID);
        } finally {
            workerLogger.detachAppender(appender);
        }

        // then：恰一条「分块 dispatch」INFO，四字段齐备（docId / resolvedMode / chunkCount / elapsedMs）
        List<ILoggingEvent> dispatchEvents = appender.list.stream()
                .filter(event -> event.getFormattedMessage().startsWith("分块 dispatch"))
                .toList();
        assertEquals(1, dispatchEvents.size());
        ILoggingEvent dispatchEvent = dispatchEvents.get(0);
        assertEquals(Level.INFO, dispatchEvent.getLevel());
        String message = dispatchEvent.getFormattedMessage();
        assertTrue(message.contains("docId=" + DOCUMENT_ID));
        assertTrue(message.contains("resolvedMode=GENERAL"));
        assertTrue(message.contains("chunkCount=1"));
        assertTrue(message.contains("elapsedMs="));
        assertFalse(message.contains("explicitMode="), "纯直通下不再有 explicitMode 字段");
        assertFalse(message.contains("dispatchBasis="), "决策依据枚举已删除");
    }

    @Test
    void should_logResolvedModeDirectly_when_execute_given_bookModeWithoutHeading() {
        // given：显式 BOOK 模式（无标题文档内部降级），
        // 分块服务返回 resolvedMode=BOOK 的 outcome——纯直通下生效模式恒等于显式模式
        Logger workerLogger = (Logger) LoggerFactory.getLogger(IngestionWorker.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        workerLogger.addAppender(appender);
        try {
            stubContext(chunkStrategyContext("{\"chunkMode\":\"BOOK\"}"));
            stubParseAndChunk(List.of(textChunk("第一章 总则")));
            when(chunkingService.chunkWithDispatch(any(), any(), any()))
                    .thenReturn(new ChunkingOutcome(List.of(textChunk("第一章 总则")),
                            DocumentChunkMode.BOOK));
            when(entityExtractionService.extract(any(), anyList())).thenReturn(EntityExtractionResult.empty());

            // when
            ingestionWorker.execute(DOCUMENT_ID);
        } finally {
            workerLogger.detachAppender(appender);
        }

        // then：日志只留痕生效模式 BOOK，无路由覆盖事件
        String message = singleDispatchMessage(appender);
        assertTrue(message.contains("resolvedMode=BOOK"));
        assertFalse(message.contains("dispatchBasis="));
    }

    @Test
    void should_logExplicitDispatchInfo_when_execute_given_tableModeConfigured() {
        // given：显式配置 TABLE 模式直通，不参与任何选路判断
        Logger workerLogger = (Logger) LoggerFactory.getLogger(IngestionWorker.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        workerLogger.addAppender(appender);
        try {
            stubContext(chunkStrategyContext("{\"chunkMode\":\"TABLE\"}"));
            stubParseAndChunk(List.of(textChunk("col1|col2")));
            when(chunkingService.chunkWithDispatch(any(), any(), any()))
                    .thenReturn(new ChunkingOutcome(List.of(textChunk("col1|col2")),
                            DocumentChunkMode.TABLE));
            when(entityExtractionService.extract(any(), anyList())).thenReturn(EntityExtractionResult.empty());

            // when
            ingestionWorker.execute(DOCUMENT_ID);
        } finally {
            workerLogger.detachAppender(appender);
        }

        // then：显式模式原样留痕
        String message = singleDispatchMessage(appender);
        assertTrue(message.contains("resolvedMode=TABLE"));
    }

    @Test
    void should_useKbLanguageAcrossStages_when_execute_given_kbLanguageJapanese() {
        // given：知识库语言配置为 Japanese（多模态增强路径，覆盖媒体描述 / 抽取上下文 / 合并上下文三处），
        // 代码内回落默认仍为 Chinese，验证库级配置优先生效
        ContentBlockVO imageBlock = new ContentBlockVO(ContentBlockVO.TYPE_IMAGE, "raw caption", Map.of());
        stubContext(documentContextWithLanguage(ragEngineConfig(RagEngineType.DOCUMENT_ENGINE),
                embeddingConfig(), multiModelConfig(), "Japanese"));
        stubParseAndChunk(imageBlock, List.of(new ChunkVO(1, "original image text", 3, imageBlock)));
        MediaDescriptionVO description =
                new MediaDescriptionVO("Yamagoto Portrait", "image", "one line", "detailed caption", true);
        when(mediaDescriptorService.describe(eq(KB_ID), eq(LLM_PROFILE), eq("Japanese"), eq(imageBlock), any(),
                any())).thenReturn(description);
        ChunkVO rebuilt = new ChunkVO(1, "template enhanced text", 8, imageBlock);
        when(mediaChunkTemplateService.buildChunk(eq(1), eq(imageBlock), eq(description), eq("Japanese"), any()))
                .thenReturn(rebuilt);
        when(entityExtractionService.extract(any(), anyList())).thenReturn(EntityExtractionResult.empty());
        when(ingestionSourceReader.listChunkIdentitiesByDocument(DOCUMENT_ID))
                .thenReturn(List.of(new ChunkIdentity(1, 9001L)));
        when(graphMergeService.mergeNodesAndEdges(any(), anyList(), anyList()))
                .thenReturn(new GraphMergeReport(2, 0, 1, 0, 0));

        // when
        ingestionWorker.execute(DOCUMENT_ID);

        // then：媒体描述与模板重建收到 Japanese
        verify(mediaDescriptorService).describe(eq(KB_ID), eq(LLM_PROFILE), eq("Japanese"), eq(imageBlock), any(),
                any());
        verify(mediaChunkTemplateService).buildChunk(eq(1), eq(imageBlock), eq(description), eq("Japanese"), any());
        // then：sidecar 抽取上下文与图合并上下文的语言同为 Japanese
        ArgumentCaptor<EntityExtractionContext> extractCaptor =
                ArgumentCaptor.forClass(EntityExtractionContext.class);
        verify(entityExtractionService).extract(extractCaptor.capture(), anyList());
        assertEquals("Japanese", extractCaptor.getValue().language());
        ArgumentCaptor<GraphMergeContext> mergeCaptor = ArgumentCaptor.forClass(GraphMergeContext.class);
        verify(graphMergeService).mergeNodesAndEdges(mergeCaptor.capture(), anyList(), anyList());
        assertEquals("Japanese", mergeCaptor.getValue().language());
    }

    /**
     * 场景：知识库语言未配置（{@code ctx.language()} 为 null），无全局兜底配置注入。
     * 预期：抽取上下文与合并上下文的生效语言均为代码内默认 {@code Chinese}
     * （该语言全名经 {@code PromptCatalog.normalizeLanguage}
     * 映射 zh 中文模板套）。
     */
    @Test
    void should_fallbackChinese_when_execute_given_contextLanguageBlank() {
        // given：知识库语言未配置（ctx.language() 为 null），回落链为代码内常量 Chinese
        stubContext(documentContext(null, embeddingConfig(), null));
        stubParseAndChunk(List.of(textChunk("Alice met Bob")));
        stubTextExtraction(singleNodeResult("Alice", 9001L));
        when(ingestionSourceReader.listChunkIdentitiesByDocument(DOCUMENT_ID))
                .thenReturn(List.of(new ChunkIdentity(1, 9001L)));
        when(graphMergeService.mergeNodesAndEdges(any(), anyList(), anyList()))
                .thenReturn(GraphMergeReport.empty());

        // when
        ingestionWorker.execute(DOCUMENT_ID);

        // then：抽取与合并上下文均回落代码内默认 Chinese（zh 模板套由门面语言归一保证）
        ArgumentCaptor<EntityExtractionContext> extractCaptor =
                ArgumentCaptor.forClass(EntityExtractionContext.class);
        verify(entityExtractionService).extract(extractCaptor.capture(), anyList());
        assertEquals("Chinese", extractCaptor.getValue().language());
        ArgumentCaptor<GraphMergeContext> mergeCaptor = ArgumentCaptor.forClass(GraphMergeContext.class);
        verify(graphMergeService).mergeNodesAndEdges(mergeCaptor.capture(), anyList(), anyList());
        assertEquals("Chinese", mergeCaptor.getValue().language());
    }

    /**
     * 场景：解析产物携带媒体图片且对象存储写入成功。
     * 预期：Stage 1a 按「库/文档维度」落图后（桶概念已退役），img_path 命中的多模态块 meta
     * 补写 mediaObjectKey 一键，未命中块与文件名/哈希保持不变。
     */
    @Test
    void should_bindMediaRefsIntoBlockMeta_when_execute_given_persistedMediaImages() {
        // given：图片块 img_path 带相对目录（images/a.png），命中持久化返回的引用
        ContentBlockVO imageBlock = new ContentBlockVO(ContentBlockVO.TYPE_IMAGE, "图1",
                Map.of("img_path", "images/a.png"));
        ContentBlockVO textBlock = new ContentBlockVO(ContentBlockVO.TYPE_TEXT, "body", Map.of());
        stubContext(documentContext(null, embeddingConfig(), null));
        when(parserRegistry.resolve(any())).thenReturn(documentParser);
        when(documentParser.parse(any(), any(), any())).thenReturn(new ParsedDocument("hash-1",
                List.of(imageBlock, textBlock), null, Map.of("a.png", new byte[]{1, 2, 3})));
        when(mediaImagePersistenceService.persistImages(eq(KB_ID), eq(DOCUMENT_ID), any()))
                .thenReturn(Map.of("a.png", new MediaFileRef("rag/7/42/images/a.png")));
        when(chunkingService.chunkWithDispatch(any(), any(), any())).thenReturn(new ChunkingOutcome(
                List.of(textChunk("Alice met Bob")), DocumentChunkMode.GENERAL));
        when(entityExtractionService.extract(any(), anyList())).thenReturn(EntityExtractionResult.empty());

        // when
        ingestionWorker.execute(DOCUMENT_ID);

        // then：分块入参即引用注入后的解析产物
        ParsedDocument bound = captureChunkingInput();
        assertEquals(FILE_NAME, bound.fileName(), "文件名包装语义不受引用注入影响");
        assertEquals("hash-1", bound.parsedTextHash());
        Map<String, Object> imageMeta = bound.blocks().get(0).meta();
        assertEquals("rag/7/42/images/a.png", imageMeta.get(MultimodalMetaKeys.META_KEY_MEDIA_OBJECT_KEY));
        assertEquals("images/a.png", imageMeta.get(MultimodalMetaKeys.META_KEY_IMG_PATH), "原 img_path 保留");
        assertFalse(bound.blocks().get(1).meta().containsKey(MultimodalMetaKeys.META_KEY_MEDIA_OBJECT_KEY),
                "文本块不应被写入图片引用");
        assertEquals(1, bound.images().size(), "图片字节仍留在解析产物中间态，不进块 meta");
    }

    /**
     * 场景：同名不同字节冲突导致持久化侧摘除该基名引用。
     * 预期：净化后同名的两个图片块 meta 均不含 mediaObjectKey（桶概念已退役，回落既有文本形态），
     * 未冲突图片块的引用注入不受影响，摄入管线正常继续。
     */
    @Test
    void should_fallbackToTextWithoutRefs_when_execute_given_conflictedMediaImageName() {
        // given：两个块的 img_path 净化后同为 001.png，持久化返回的引用映射中该键已被摘除
        ContentBlockVO firstImage = new ContentBlockVO(ContentBlockVO.TYPE_IMAGE, "图1",
                Map.of("img_path", "images/001.png"));
        ContentBlockVO secondImage = new ContentBlockVO(ContentBlockVO.TYPE_IMAGE, "图2",
                Map.of("img_path", "images/frag/001.png"));
        ContentBlockVO thirdImage = new ContentBlockVO(ContentBlockVO.TYPE_IMAGE, "图3",
                Map.of("img_path", "images/c.png"));
        stubContext(documentContext(null, embeddingConfig(), null));
        when(parserRegistry.resolve(any())).thenReturn(documentParser);
        when(documentParser.parse(any(), any(), any())).thenReturn(new ParsedDocument("hash-1",
                List.of(firstImage, secondImage, thirdImage), null,
                Map.of("images/001.png", new byte[]{1}, "images/frag/001.png", new byte[]{2, 2},
                        "images/c.png", new byte[]{3})));
        when(mediaImagePersistenceService.persistImages(eq(KB_ID), eq(DOCUMENT_ID), any()))
                .thenReturn(Map.of("c.png", new MediaFileRef("rag/7/42/images/c.png")));
        when(chunkingService.chunkWithDispatch(any(), any(), any())).thenReturn(new ChunkingOutcome(
                List.of(textChunk("Alice met Bob")), DocumentChunkMode.GENERAL));
        when(entityExtractionService.extract(any(), anyList())).thenReturn(EntityExtractionResult.empty());

        // when
        ingestionWorker.execute(DOCUMENT_ID);

        // then：冲突基名的两处块引用均未命中，原样回落文本形态；未冲突块引用注入正常
        ParsedDocument bound = captureChunkingInput();
        Map<String, Object> firstMeta = bound.blocks().get(0).meta();
        Map<String, Object> secondMeta = bound.blocks().get(1).meta();
        assertFalse(firstMeta.containsKey(MultimodalMetaKeys.META_KEY_MEDIA_OBJECT_KEY), "冲突基名首块不得携带对象键引用");
        assertFalse(secondMeta.containsKey(MultimodalMetaKeys.META_KEY_MEDIA_OBJECT_KEY), "冲突基名次块不得携带对象键引用");
        assertEquals("images/001.png", firstMeta.get(MultimodalMetaKeys.META_KEY_IMG_PATH), "img_path 原样保留");
        Map<String, Object> thirdMeta = bound.blocks().get(2).meta();
        assertEquals("rag/7/42/images/c.png", thirdMeta.get(MultimodalMetaKeys.META_KEY_MEDIA_OBJECT_KEY));
    }

    /**
     * 场景：媒体图片写对象存储失败（持久化服务返回空引用映射）。
     * 预期：块 meta 无引用键，摄入继续完成分块与切片落库（不阻断）。
     */
    @Test
    void should_continuePipelineWithoutRefs_when_execute_given_mediaImagePutFailed() {
        // given
        ContentBlockVO imageBlock = new ContentBlockVO(ContentBlockVO.TYPE_IMAGE, "图1",
                Map.of("img_path", "images/a.png"));
        stubContext(documentContext(null, embeddingConfig(), null));
        stubParseAndChunk(imageBlock, List.of(new ChunkVO(1, "image chunk", 3, imageBlock)));
        when(mediaImagePersistenceService.persistImages(anyLong(), anyLong(), any()))
                .thenReturn(Map.of());

        // when
        ingestionWorker.execute(DOCUMENT_ID);

        // then：以库/文档维度发起持久化（桶参数已随桶概念退役）；无引用即维持原块形态，管线继续到切片落库
        verify(mediaImagePersistenceService).persistImages(eq(KB_ID), eq(DOCUMENT_ID), any());
        ParsedDocument passed = captureChunkingInput();
        assertFalse(passed.blocks().get(0).meta().containsKey(MultimodalMetaKeys.META_KEY_MEDIA_OBJECT_KEY));
        verify(chunkPersistenceService)
                .replaceForDocument(eq(DOCUMENT_ID), anyList(), eq(EMBED_PROFILE), isNull());
        // 摄入未被单图写入失败阻断：文档引擎管线无多模态块增强，抽取与合并按既有语义零交互
        verifyNoInteractions(entityExtractionService, graphMergeService);
    }

    // ==================== 收尾清理·在飞成员登记与移除 ====================

    /**
     * 场景：完整文本管线成功（Stage 3 只落库、终态由收尾统一置入）。
     * 预期：先写入 PROCESSED 成功终态，再移除本实例在飞注册表成员——正常完成的任务不留残留凭据。
     */
    @Test
    void should_markProcessedThenUnregister_when_execute_given_pipelineSucceeded() {
        // given：解析 → 分块 → 落库 → 抽取（无实体即跳过合并）全流程成功
        stubContext(documentContext(null, embeddingConfig(), null));
        stubParseAndChunk(List.of(textChunk("Alice met Bob")));
        when(entityExtractionService.extract(any(), anyList())).thenReturn(EntityExtractionResult.empty());

        // when
        ingestionWorker.execute(DOCUMENT_ID);

        // then：成功终态由收尾写入（切片落库不置态），置态成功后才移除在飞成员
        InOrder order = inOrder(chunkBatchWriter, inFlightTaskRegistry);
        order.verify(chunkBatchWriter).markProcessed(DOCUMENT_ID);
        order.verify(inFlightTaskRegistry).unregister(InFlightTaskType.DOC_INGESTION, DOCUMENT_ID);
        verify(chunkBatchWriter, never()).markFailed(anyLong(), anyString());
    }

    /**
     * 场景：Stage 0 前置校验失败，FAILED 状态回写成功。
     * 预期：置态成功（终态已写入）后才移除在飞成员。
     */
    @Test
    void should_unregisterMember_when_execute_given_failWriteBackSucceeded() {
        // given：文档缺少源文件引用 → 需回写 FAILED，回写成功
        stubContext(contextWithoutSourceFile());

        // when
        ingestionWorker.execute(DOCUMENT_ID);

        // then：先回写失败态，再移除成员（移除以「状态已收敛」为前提）
        verify(chunkBatchWriter).markFailed(eq(DOCUMENT_ID), anyString());
        verify(inFlightTaskRegistry).unregister(InFlightTaskType.DOC_INGESTION, DOCUMENT_ID);
    }

    /**
     * 场景：任务异常且置失败的状态写入失败（markFailed 抛异常，markFailedQuietly 返回 false）。
     * 预期：任务 ID 保留在集合中，MUST NOT 移除——否则产生「状态停在中间态而注册表无迹可寻」的悬挂。
     */
    @Test
    void should_keepMember_when_execute_given_failWriteBackThrows() {
        // given：文档缺少源文件引用，且 FAILED 回写自身异常（状态未知）
        stubContext(contextWithoutSourceFile());
        doThrow(new IllegalStateException("状态回写失败"))
                .when(chunkBatchWriter).markFailed(eq(DOCUMENT_ID), anyString());

        // when：execute 永不向外抛出（失败回写异常仅 ERROR 留痕）
        ingestionWorker.execute(DOCUMENT_ID);

        // then：置态失败必须保留成员，交由本实例启动恢复重试
        verify(inFlightTaskRegistry, never()).unregister(any(), any());
    }

    /**
     * 场景：管线完整成功，但成功终态写入自身异常（状态是否收敛未知）。
     * 预期：保留在飞成员交由本实例启动恢复重试——避免「状态停在 PROCESSING 而注册表无迹可寻」。
     */
    @Test
    void should_keepMember_when_execute_given_markProcessedThrows() {
        // given：全流程成功，但 PROCESSED 终态回写自身异常
        stubContext(documentContext(null, embeddingConfig(), null));
        stubParseAndChunk(List.of(textChunk("Alice met Bob")));
        when(entityExtractionService.extract(any(), anyList())).thenReturn(EntityExtractionResult.empty());
        doThrow(new IllegalStateException("状态回写失败")).when(chunkBatchWriter).markProcessed(DOCUMENT_ID);

        // when：execute 永不向外抛出（置态异常仅 ERROR 留痕）
        ingestionWorker.execute(DOCUMENT_ID);

        // then：置态失败必须保留成员；且不因成功路径异常而误写 FAILED
        verify(inFlightTaskRegistry, never()).unregister(any(), any());
        verify(chunkBatchWriter, never()).markFailed(anyLong(), anyString());
    }

    /**
     * 场景：管线完整成功，但成功终态条件更新未命中（状态已被删除链 / 用户重新解析接管）。
     * 预期：本就无需置态，视为状态已收敛——仍移除在飞成员，MUST NOT 回写任何状态。
     */
    @Test
    void should_unregisterMember_when_execute_given_markProcessedMissed() {
        // given：全流程成功，收尾置态时状态已被外部推进 → 条件更新未命中
        stubContext(documentContext(null, embeddingConfig(), null));
        stubParseAndChunk(List.of(textChunk("Alice met Bob")));
        when(entityExtractionService.extract(any(), anyList())).thenReturn(EntityExtractionResult.empty());
        when(chunkBatchWriter.markProcessed(DOCUMENT_ID)).thenReturn(false);

        // when
        ingestionWorker.execute(DOCUMENT_ID);

        // then：未命中不视为失败，成员照常移除且无失败回写
        verify(inFlightTaskRegistry).unregister(InFlightTaskType.DOC_INGESTION, DOCUMENT_ID);
        verify(chunkBatchWriter, never()).markFailed(anyLong(), anyString());
    }

    /**
     * 场景：文档已被删除链置 DELETING（取消场景：状态由删除链 / 用户持有，本就无需置态）。
     * 预期：取消路径静默返回，但仍移除在飞成员且不回写 FAILED。
     */
    @Test
    void should_unregisterMember_when_execute_given_cancelledDocument() {
        // given
        stubContext(documentContext(null, embeddingConfig(), null), DocumentStatus.DELETING);
        stubParseAndChunk(List.of(textChunk("alpha text")));

        // when
        ingestionWorker.execute(DOCUMENT_ID);

        // then：取消路径无可置态，直接移除成员
        verify(chunkBatchWriter, never()).markFailed(anyLong(), anyString());
        verify(inFlightTaskRegistry).unregister(InFlightTaskType.DOC_INGESTION, DOCUMENT_ID);
    }

    /**
     * 捕获交给分块服务的解析产物（Stage 1a 出参）。
     *
     * @return 解析产物
     */
    private ParsedDocument captureChunkingInput() {
        ArgumentCaptor<ParsedDocument> parsedCaptor = ArgumentCaptor.forClass(ParsedDocument.class);
        verify(chunkingService).chunkWithDispatch(parsedCaptor.capture(), any(), any());
        return parsedCaptor.getValue();
    }

    /**
     * 从捕获事件中取出唯一一条「分块 dispatch」INFO 日志正文。
     *
     * @param appender 已挂载并收集事件的 ListAppender
     * @return 该条日志的格式化正文
     */
    private String singleDispatchMessage(ListAppender<ILoggingEvent> appender) {
        List<ILoggingEvent> dispatchEvents = appender.list.stream()
                .filter(event -> event.getFormattedMessage().startsWith("分块 dispatch"))
                .toList();
        assertEquals(1, dispatchEvents.size());
        assertEquals(Level.INFO, dispatchEvents.get(0).getLevel());
        return dispatchEvents.get(0).getFormattedMessage();
    }

    /**
     * 断言失败原因仅回写一次且携带指定阶段分类前缀。
     *
     * @param stage 期望归位的管线阶段
     * @return 回写的失败原因原文，供调用方继续断言文案细节
     */
    private String assertFailedStage(IngestionStage stage) {
        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(chunkBatchWriter).markFailed(eq(DOCUMENT_ID), messageCaptor.capture());
        String message = messageCaptor.getValue();
        assertTrue(message.startsWith(stage.prefix()),
                "失败原因应以 " + stage.prefix() + " 开头，实际：" + message);
        return message;
    }

    /**
     * 打桩上下文读取与状态回查（Worker 在落库前与图合并前均检查取消状态，失败回写前亦有取消判定）。
     * <p>缺省按 PROCESSING 打桩（摄入进行中，未被取消）；需要模拟运行中途取消时，
     * 以可变参数给出按调用次序返回的状态序列。</p>
     *
     * @param ctx            摄入文档上下文
     * @param statusSequence 状态序列（为空时固定返回 PROCESSING）
     */
    private void stubContext(IngestionDocumentContext ctx, DocumentStatus... statusSequence) {
        when(ingestionSourceReader.readContext(DOCUMENT_ID)).thenReturn(ctx);
        if (statusSequence.length == 0) {
            when(ingestionSourceReader.statusOf(DOCUMENT_ID)).thenReturn(DocumentStatus.PROCESSING);
        } else {
            DocumentStatus[] following = Arrays.copyOfRange(statusSequence, 1, statusSequence.length);
            when(ingestionSourceReader.statusOf(DOCUMENT_ID)).thenReturn(statusSequence[0], following);
        }
    }

    /**
     * 打桩解析器选型、解析与分块（默认文档引擎路径）。
     *
     * @param chunks 分块产物
     */
    private void stubParseAndChunk(List<ChunkVO> chunks) {
        when(parserRegistry.resolve(any())).thenReturn(documentParser);
        when(documentParser.parse(any(), any(), any()))
                .thenReturn(parsedDocument(new ContentBlockVO(ContentBlockVO.TYPE_TEXT, "body", Map.of())));
        when(chunkingService.chunkWithDispatch(any(), any(), any()))
                .thenReturn(new ChunkingOutcome(chunks, DocumentChunkMode.GENERAL));
    }

    /**
     * 打桩解析器选型、解析与分块（指定解析出的内容块，用于多模态管线用例）。
     *
     * @param block  解析出的内容块
     * @param chunks 分块产物
     */
    private void stubParseAndChunk(ContentBlockVO block, List<ChunkVO> chunks) {
        when(parserRegistry.resolve(any())).thenReturn(documentParser);
        when(documentParser.parse(any(), any(), any())).thenReturn(parsedDocument(block));
        when(chunkingService.chunkWithDispatch(any(), any(), any()))
                .thenReturn(new ChunkingOutcome(chunks, DocumentChunkMode.GENERAL));
    }

    /**
     * 打桩文本批量抽取。
     *
     * @param result 抽取结果
     */
    private void stubTextExtraction(EntityExtractionResult result) {
        when(entityExtractionService.extract(any(), anyList())).thenReturn(result);
    }

    /**
     * 构建单实体抽取结果（sourceIds 直接携带传入的真实分块主键——抽取端已无占位语义）。
     *
     * @param entityName 实体名
     * @param sourceId   来源分块主键
     * @return 抽取结果
     */
    private EntityExtractionResult singleNodeResult(String entityName, Long sourceId) {
        EntityNode node = new EntityNode(KB_ID, entityName, new EntityProperties(
                "person", "desc", List.of(sourceId), FILE_NAME, Map.of("person", 1), List.of("desc")));
        return new EntityExtractionResult(List.of(node), List.<RelationEdge>of());
    }

    /**
     * 构建文本分块。
     *
     * @param text 分块正文
     * @return 分块值对象
     */
    private ChunkVO textChunk(String text) {
        return new ChunkVO(1, text, 3,
                new ContentBlockVO(ContentBlockVO.TYPE_TEXT, text, Map.of()));
    }

    /**
     * 构建解析结果。
     *
     * @param block 内容块
     * @return 解析结果
     */
    private ParsedDocument parsedDocument(ContentBlockVO block) {
        return new ParsedDocument("hash-1", List.of(block));
    }

    /**
     * 构建文档引擎上下文（知识库语言未配置）。
     *
     * @param engineConfig 引擎配置（仅承载解析 / 分块策略投影，不承载任何模型选型；null 时补默认文档引擎配置）
     * @param embedding    嵌入配置（可为 null）
     * @param llmConfig    知识库级通用 LLM 引用（媒体描述 / 抽取 / 摘要共用同一 profileId，
     *                     对应 {@code multi_model_config}；null 时补默认值——Stage 0 对该分量做硬校验，
     *                     未配置进不了后续阶段，需覆盖失败场景请用 {@link #contextWithoutLlmModel()}）
     * @return 摄入文档上下文
     */
    private IngestionDocumentContext documentContext(RagEngineConfig engineConfig,
                                                     EmbeddingModelConfig embedding,
                                                     MultiModelConfig llmConfig) {
        return documentContextWithLanguage(engineConfig, embedding, llmConfig, null);
    }

    /**
     * 构建文档引擎上下文（可指定知识库语言分量）。
     *
     * @param engineConfig 引擎配置（null 时补默认文档引擎配置）
     * @param embedding    嵌入配置（可为 null）
     * @param llmConfig    知识库级通用 LLM 引用（null 时补默认 {@link #LLM_PROFILE}）
     * @param language     知识库语言全名（null = 未配置，回落代码内默认 Chinese）
     * @return 摄入文档上下文
     */
    private IngestionDocumentContext documentContextWithLanguage(RagEngineConfig engineConfig,
                                                                 EmbeddingModelConfig embedding,
                                                                 MultiModelConfig llmConfig,
                                                                 String language) {
        RagEngineConfig effectiveEngineConfig = ObjectUtils.isEmpty(engineConfig)
                ? ragEngineConfig(RagEngineType.DOCUMENT_ENGINE) : engineConfig;
        MultiModelConfig effectiveLlmConfig = ObjectUtils.isEmpty(llmConfig) ? multiModelConfig() : llmConfig;
        return new IngestionDocumentContext(DOCUMENT_ID, KB_ID, FILE_NAME, DocumentStatus.PROCESSING,
                new S3File("rag/7/source/object.pdf"), null, effectiveEngineConfig, null, embedding,
                effectiveLlmConfig, List.of(), null, language);
    }

    /**
     * 构建携带文档级分块策略 JSON 的文档引擎上下文（库级策略仅来自文档级 JSON）。
     *
     * @param documentChunkStrategyJson 文档级分块策略 JSON 原文，可为 null
     * @return 摄入文档上下文
     */
    private IngestionDocumentContext chunkStrategyContext(String documentChunkStrategyJson) {
        return new IngestionDocumentContext(DOCUMENT_ID, KB_ID, FILE_NAME, DocumentStatus.PROCESSING,
                new S3File("rag/7/source/object.pdf"), documentChunkStrategyJson,
                ragEngineConfig(RagEngineType.DOCUMENT_ENGINE), null, embeddingConfig(), multiModelConfig(),
                List.of(), null, null);
    }

    /**
     * 构建缺失源文件引用的文档上下文（s3File 为 null，触发 Stage 0 源文件校验失败）。
     * <p>模型引用分量同时留空，用于验证源文件校验先于模型引用校验。</p>
     *
     * @return 摄入文档上下文
     */
    private IngestionDocumentContext contextWithoutSourceFile() {
        return new IngestionDocumentContext(DOCUMENT_ID, KB_ID, FILE_NAME, DocumentStatus.PROCESSING,
                null, null, null, null, embeddingConfig(), null, List.of(), null, null);
    }

    /**
     * 构建「该库未配置 LLM」的文档上下文（multiModelConfig 为 null，触发 Stage 0 模型引用校验失败）。
     * <p>LLM 与 VLM 同源于该分量，故该上下文同时意味着该库无多模态增强能力。</p>
     *
     * @return 摄入文档上下文
     */
    private IngestionDocumentContext contextWithoutLlmModel() {
        return new IngestionDocumentContext(DOCUMENT_ID, KB_ID, FILE_NAME, DocumentStatus.PROCESSING,
                new S3File("rag/7/source/object.pdf"), null, ragEngineConfig(RagEngineType.DOCUMENT_ENGINE),
                null, embeddingConfig(), null, List.of(), null, null);
    }

    /**
     * 构建 RAG 引擎配置值对象（三分量：引擎类型 + 解析引擎 + 分块策略；模型选型与语言不由其承载）。
     *
     * @param type 引擎类型
     * @return 引擎配置
     */
    private RagEngineConfig ragEngineConfig(RagEngineType type) {
        return new RagEngineConfig(type, null, null);
    }

    /**
     * 构建嵌入模型配置。
     *
     * @return 嵌入配置
     */
    private EmbeddingModelConfig embeddingConfig() {
        return new EmbeddingModelConfig(EMBED_PROFILE);
    }

    /**
     * 构建知识库级通用 LLM 配置（{@code multi_model_config}：媒体描述 / 实体抽取 / 摘要共用）。
     *
     * @return 多模态模型配置
     */
    private MultiModelConfig multiModelConfig() {
        return new MultiModelConfig(LLM_PROFILE);
    }
}
