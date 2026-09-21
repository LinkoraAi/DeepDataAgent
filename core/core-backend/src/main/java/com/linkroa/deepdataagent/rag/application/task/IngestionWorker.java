package com.linkroa.deepdataagent.rag.application.task;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkroa.deepdataagent.knowledgebase.api.ChunkBatchWriter;
import com.linkroa.deepdataagent.knowledgebase.application.contract.ChunkIdentity;
import com.linkroa.deepdataagent.knowledgebase.api.DocumentGraphConvergenceApi;
import com.linkroa.deepdataagent.knowledgebase.api.DocumentGraphConvergencePlan;
import com.linkroa.deepdataagent.knowledgebase.api.DocumentGraphConvergenceResult;
import com.linkroa.deepdataagent.knowledgebase.application.contract.IngestionDocumentContext;
import com.linkroa.deepdataagent.knowledgebase.api.InFlightTaskRegistry;
import com.linkroa.deepdataagent.knowledgebase.api.InFlightTaskType;
import com.linkroa.deepdataagent.knowledgebase.api.IngestionSourceReader;
import com.linkroa.deepdataagent.knowledgebase.domain.model.MultiModelConfig;
import com.linkroa.deepdataagent.knowledgebase.domain.model.RagEngineConfig;
import com.linkroa.deepdataagent.knowledgebase.domain.model.S3File;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.DocumentChunkMode;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.DocumentStatus;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.KbLanguage;
import com.linkroa.deepdataagent.rag.application.service.ChunkPersistenceService;
import com.linkroa.deepdataagent.rag.application.service.MediaImagePersistenceService;
import com.linkroa.deepdataagent.rag.domain.enums.CacheType;
import com.linkroa.deepdataagent.rag.domain.model.ChunkVO;
import com.linkroa.deepdataagent.rag.domain.model.ContentBlockVO;
import com.linkroa.deepdataagent.rag.domain.model.EntityNode;
import com.linkroa.deepdataagent.rag.domain.model.MediaDescriptionVO;
import com.linkroa.deepdataagent.rag.domain.model.MediaFileRef;
import com.linkroa.deepdataagent.rag.domain.model.ParsedDocument;
import com.linkroa.deepdataagent.rag.domain.model.PersistedChunkVO;
import com.linkroa.deepdataagent.rag.domain.model.RelationEdge;
import com.linkroa.deepdataagent.rag.domain.port.DocumentParser;
import com.linkroa.deepdataagent.rag.domain.port.DocumentParserRegistry;
import com.linkroa.deepdataagent.rag.domain.repository.ChunkExtractCacheRepository;
import com.linkroa.deepdataagent.rag.domain.service.ChunkParams;
import com.linkroa.deepdataagent.rag.domain.service.ChunkingOutcome;
import com.linkroa.deepdataagent.rag.domain.service.ChunkingService;
import com.linkroa.deepdataagent.rag.domain.service.EntityExtractionContext;
import com.linkroa.deepdataagent.rag.domain.service.EntityExtractionResult;
import com.linkroa.deepdataagent.rag.domain.service.EntityExtractionService;
import com.linkroa.deepdataagent.rag.domain.service.GraphMergeContext;
import com.linkroa.deepdataagent.rag.domain.service.GraphMergeParams;
import com.linkroa.deepdataagent.rag.domain.service.GraphMergeReport;
import com.linkroa.deepdataagent.rag.domain.service.GraphMergeService;
import com.linkroa.deepdataagent.rag.domain.service.MediaChunkTemplateService;
import com.linkroa.deepdataagent.rag.domain.service.MediaDescriptorService;
import com.linkroa.deepdataagent.rag.domain.service.MediaImageObjectKeys;
import com.linkroa.deepdataagent.rag.domain.service.MultimodalContextInjector;
import com.linkroa.deepdataagent.rag.domain.service.MultimodalMetaKeys;
import com.linkroa.deepdataagent.rag.domain.service.TokenCounter;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * 摄入管线执行器。
 * <p>单文档级六阶段主管线，由 {@link IngestionTaskQueue} 出队并经条件更新
 * {@code PENDING → PROCESSING} 领取独占后，在其常驻消费虚拟线程内同步执行
 * （取代原调度器扫表抢占领取 + 独立线程池模型）：</p>
 * <ol>
 *   <li>Stage 0 前置校验：读取摄入上下文（{@link IngestionSourceReader#readContext(Long)}），
 *       校验源文件与知识库级 LLM 模型引用（{@code multi_model_config.modelProfileId}）均存在，
 *       任一缺失即置 FAILED；</li>
 *   <li>Stage 1 解析 + 分块：按库级解析配置选型解析器（无自动回退），解析产物中的媒体图片经
 *       {@link MediaImagePersistenceService} 落对象存储并把引用（{@code mediaObjectKey}，
 *       仅对象键、桶概念已退役）注入多模态块 meta（单图失败仅 WARN，不阻断摄入），再经
 *       {@link MultimodalContextInjector} 为多模态块注入检索上下文（章节路径 / 邻近文本），
 *       最后经 {@link ChunkingService} 分块；文档级分块策略优先、库级 {@code chunkStrategy} 继承兜底；</li>
 *   <li>Stage 2 多模态增强（内容驱动）：分块结果存在多模态块时逐块并发模型描述 + 模板化重建
 *       chunk，模型引用复用 Stage 0 已校验值（该列同时服务视觉与文本，无独立降级路径）；</li>
 *   <li>Stage 3 切片落库：先经读侧身份列举预查捕获旧代际切片 ID（首次摄入为空即零开销跳过），
 *       对旧代 ID 集合<b>在整篇替换之前</b>同步执行图谱贡献收敛（分块引用完整性不变量：
 *       分块行消失不得先于图谱账本清退；收敛失败 fail-closed——上抛中止本次摄入、不执行替换；
 *       崩溃落在收敛与替换之间仅留下「有文本、无图谱」的可自愈窗口，下次重解析幂等收敛）；
 *       取消闸门通过后经 {@link ChunkPersistenceService} 整篇替换（<b>只回写文档分块计数，
 *       不写文档状态</b>——切片写入成功不等于摄入完成；chunk 永远先于图谱抽取持久化）；
 *       落库拿到真实分块主键后、抽取发起前，为 Stage 2 的媒体描述补登记缓存归属
 *       （描述调用点尚无分块主键，归属只能延迟到此处登记）；</li>
 *   <li>Stage 4 实体与关系抽取：文本块批量抽取 + 多模态块逐块带主实体名 sidecar 抽取；
 *       抽取直接以落库回传映射绑定的真实分块主键为来源键（映射为 Stage 3 落库事务内回传的
 *       「序号→主键」，不为翻译而二次回查数据库），占位重写层已从根源移除；</li>
 *   <li>Stage 5 图合并：{@link GraphMergeService} 文档级合并写入图谱与向量；知识库未配置嵌入模型
 *       引用时该阶段显式失败（见失败语义）；</li>
 *   <li>收尾成功置态：{@link #execute(Long)} 在 {@code process()} 正常返回后（含「未抽取到实体
 *       与关系、跳过图合并」的早退分支）对文档执行 {@code PROCESSING → PROCESSED} 条件流转。
 *       切片落库不置终态，成功终态的唯一写入点在此。</li>
 * </ol>
 *
 * <p>失败与取消语义：</p>
 * <ul>
 *   <li>任一步异常先做取消判定：文档状态为 {@code PENDING / FAILED}（用户重新解析或回滚）、
 *       {@code DELETING / DELETE_FAILED}（删除链两态掐灭在飞 worker）或
 *       文档行已不存在（收口＝条件 DELETE 物理删行，「已删除」唯一表达）视为已取消——
 *       取消场景静默返回，不覆盖用户操作触发的新状态，也不回写任何状态与切片
 *       ；</li>
 *   <li>非取消场景经 {@link ChunkBatchWriter#markFailed} 回写 {@code FAILED} 与带阶段分类前缀
 *       （{@link IngestionStage}）且截断后的失败原因；
 *       管线全阶段（前置校验 / 解析分块 / 多模态增强 / 切片落库 / 实体关系抽取 / 图合并）的失败
 *       一律归 {@code FAILED}——切片的落库成功不构成终态，故抽取与合并失败同样翻转文档状态，
 *       已落库切片保留不回滚（不回退、不删除），由用户重新解析触发整篇重建；</li>
 *   <li>知识库未配置嵌入模型引用时 Stage 5 显式失败（无法构建向量身份即摄入无法完成），
 *       文档归 {@code FAILED} 并打印错误日志，不产出「有切片、无向量、无图谱」的假成功文档；</li>
 *   <li>取消检查器注入抽取 / 合并上下文，chunk 级并发与图合并阶段可协作中断。</li>
 * </ul>
 *
 * <p>收尾统一移除本实例在飞成员：三条收尾路径（成功 / 取消 / 失败置态成功）均经
 * {@link InFlightTaskRegistry#unregister(InFlightTaskType, Long)} 移除
 * {@link InFlightTaskType#DOC_INGESTION} 成员，且移除 MUST 以「状态已写入终态」或
 * 「本就无需置态」为前提——成功路径先写入 {@code PROCESSED}，命中或未命中
 * （状态已被删除链 / 启动恢复 / 用户重新解析接管，本就无需置态）均视为已收敛后移除；
 * 失败路径仅当 {@code FAILED} 回写成功时移除；置态写入异常时保留成员，交由本实例启动恢复重试，
 * 避免「状态停在中间态而注册表无迹可寻」的永久不可见悬挂。</p>
 *
 * @author DeepDataAgent
 */
@Component
public class IngestionWorker {

    private static final Logger log = LoggerFactory.getLogger(IngestionWorker.class);

    /** 失败原因回写长度上限（超长异常文案截断，避免污染列表展示）。 */
    private static final int MAX_ERROR_MESSAGE_LENGTH = 500;

    /** 错误文案：文档缺少源文件引用（s3_file 列空白）。 */
    private static final String ERROR_NO_SOURCE_FILE = "文档缺少源文件引用（s3_file 为空），无法解析";

    /** 错误文案：知识库未配置模型引用（multi_model_config.modelProfileId 缺失 / 空白）。 */
    private static final String ERROR_MISSING_LLM_MODEL = "知识库未配置模型引用"
            + "（multi_model_config.modelProfileId），无法执行抽取";

    /** 错误文案：知识库未配置嵌入模型引用（embedding_config.modelProfileId 缺失 / 空白）。 */
    private static final String ERROR_MISSING_EMBEDDING_MODEL = "知识库未配置嵌入模型引用"
            + "（embedding_config.modelProfileId），无法构建向量身份，摄入无法完成";

    /** 错误文案：解析结果未产出任何内容块。 */
    private static final String ERROR_NO_CHUNKS = "解析结果未产出任何内容块";

    /** 分块策略 JSON 字段：分块模式。 */
    private static final String FIELD_CHUNK_MODE = "chunkMode";

    /** 分块策略 JSON 字段：模式参数容器。 */
    private static final String FIELD_MODE_CONFIG = "modeConfig";

    /** 分块策略 JSON 字段：模式参数集合。 */
    private static final String FIELD_PARAMS = "params";

    /** rag_engine_config JSON 字段：图合并段（驼峰写法）。 */
    private static final String FIELD_GRAPH_MERGE_CAMEL = "graphMerge";

    /** rag_engine_config JSON 字段：图合并段（下划线写法）。 */
    private static final String FIELD_GRAPH_MERGE_SNAKE = "graph_merge";

    /** JSON 解析器（策略 JSON → 参数值对象，与 Stage 1 落库服务同栈）。 */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** Map 反序列化类型引用（JsonNode → 参数映射）。 */
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    /**
     * 取消判定状态集合：PENDING / FAILED（用户重新解析或外部回滚）、
     * DELETING / DELETE_FAILED（删除链两态掐灭在飞 worker，不回写任何状态与切片）。
     * <p>DELETED 常量已移除：收口为条件 DELETE，
     * 「已删除」以行缺失表达、命中 {@code statusOf} 的 null 分支即自灭，无需枚举防御。</p>
     */
    private static final Set<DocumentStatus> CANCELLED_STATUSES = Set.of(
            DocumentStatus.PENDING, DocumentStatus.FAILED,
            DocumentStatus.DELETING, DocumentStatus.DELETE_FAILED);

    /** 摄入读侧契约（上下文 / 状态 / 切片身份）。 */
    private final IngestionSourceReader ingestionSourceReader;

    /** 跨 BC 切片批量回写契约（失败状态回写）。 */
    private final ChunkBatchWriter chunkBatchWriter;

    /** 文档解析器注册表（按库级解析配置选型，无自动回退）。 */
    private final DocumentParserRegistry parserRegistry;

    /** 分块编排服务。 */
    private final ChunkingService chunkingService;

    /** Stage 1 切片整篇替换落库服务（向量化 + 全文表示 + 分块计数回写，不写文档状态）。 */
    private final ChunkPersistenceService chunkPersistenceService;

    /** 多模态描述服务（VLM）。 */
    private final MediaDescriptorService mediaDescriptorService;

    /** 多模态 chunk 模板服务。 */
    private final MediaChunkTemplateService mediaChunkTemplateService;

    /** 解析产物媒体图片持久化服务（Stage 1a 图片落对象存储）。 */
    private final MediaImagePersistenceService mediaImagePersistenceService;

    /** 多模态检索上下文注入器（Stage 1a 引用绑定之后、分块之前，为多模态块注入章节路径 / 邻近文本）。 */
    private final MultimodalContextInjector multimodalContextInjector;

    /** Token 计数器（真实 encode 口径）。 */
    private final TokenCounter tokenCounter;

    /** 实体与关系抽取服务。 */
    private final EntityExtractionService entityExtractionService;

    /** 图合并服务。 */
    private final GraphMergeService graphMergeService;

    /** 图谱贡献收敛契约（本 BC 提供、进程内消费；重解析换代后的旧代际账本收敛驱动面）。 */
    private final DocumentGraphConvergenceApi documentGraphConvergenceApi;

    /** 在飞任务注册表（收尾统一移除本实例的文档摄入在飞成员）。 */
    private final InFlightTaskRegistry inFlightTaskRegistry;

    /** 抽取缓存归属仓储（媒体描述归属在 Stage 3 落库拿到真实分块主键后延迟补登记）。 */
    private final ChunkExtractCacheRepository chunkExtractCacheRepository;

    /** 虚拟线程扇出执行器（{@code ragFanoutExecutor}）：Stage 2 多模态描述扇出经此提交，并发仍由 Semaphore 闸门约束。 */
    private final Executor ingestionExecutor;

    /** 是否启用 JSON 输出模式抽取（默认文本分隔符模式）。 */
    @Value("${app.rag.ingestion.extraction.json-mode:false}")
    private boolean extractionJsonMode;

    /** 补漏（gleaning）最大轮数 N（0 表示不补漏，负数归零）；实际续抽至多 N 轮，
     * 且最近响应含完成信号或本轮解析零新增时任一条件即提前停止。 */
    @Value("${app.rag.ingestion.extraction.max-gleaning:1}")
    private int extractionMaxGleaning;

    /** 应用级 source_ids 保留上限（知识库级 JSONB 配置可逐项覆盖本值）。 */
    @Value("${app.rag.graph.source-ids-limit:200}")
    private int graphSourceIdsLimit;

    /** 应用级 source_ids 截断策略（KEEP 保留前 N 条 / FIFO 保留后 N 条，非法值回落 KEEP）。 */
    @Value("${app.rag.graph.source-ids-truncation:KEEP}")
    private String graphSourceIdsTruncation;

    /** 应用级来源文件路径列表（filePaths）保留上限（超限保留前 N 条并追加溢出占位元素）。 */
    @Value("${app.rag.graph.source-file-paths-limit:75}")
    private int graphSourceFilePathsLimit;

    /** 应用级来源文件路径溢出占位词（占位元素 = 占位词 + 括号内策略与数量信息）。 */
    @Value("${app.rag.graph.source-file-paths-placeholder:…等}")
    private String graphSourceFilePathsPlaceholder;

    /** 多模态描述并发上限（虚拟线程 + Semaphore；&lt;=1 即回落逐块串行）。 */
    @Value("${app.rag.ingestion.describe.concurrency:4}")
    private int describeConcurrency;

    /**
     * 应用级图合并参数基底缓存：<b>@Value 注入完成后</b>首次调用时计算一次并复用
     * （严禁在构造器内求值——构造先于字段注入）。
     * <p><b>钉死前提</b>：{@code app.rag.graph.*} 应用级配置启动后不变、不支持热更新——
     * 原实现虽每次重建参数对象，但读取的同样是注入后不再刷新的字段，二者行为等价。</p>
     */
    private GraphMergeParams cachedAppLevelGraphMergeParams;

    /**
     * 构造摄入管线执行器。
     *
     * @param ingestionSourceReader   摄入读侧契约
     * @param chunkBatchWriter        切片批量回写契约
     * @param parserRegistry          文档解析器注册表
     * @param chunkingService         分块编排服务
     * @param chunkPersistenceService Stage 1 落库服务
     * @param mediaDescriptorService  多模态描述服务
     * @param mediaChunkTemplateService 多模态 chunk 模板服务
     * @param mediaImagePersistenceService 解析产物媒体图片持久化服务
     * @param multimodalContextInjector 多模态检索上下文注入器
     * @param tokenCounter            Token 计数器
     * @param entityExtractionService 实体抽取服务
     * @param graphMergeService       图合并服务
     * @param documentGraphConvergenceApi 图谱贡献收敛契约（重解析旧代际收敛）
     * @param inFlightTaskRegistry 在飞任务注册表（收尾统一移除本实例在飞成员）
     * @param chunkExtractCacheRepository 抽取缓存归属仓储（媒体描述归属延迟补登记）
     * @param ingestionExecutor     虚拟线程扇出执行器（{@code ragFanoutExecutor}，Stage 2 描述扇出经此提交，
     *                              并发仍由 Semaphore 闸门约束）
     */
    public IngestionWorker(IngestionSourceReader ingestionSourceReader,
                           ChunkBatchWriter chunkBatchWriter,
                           DocumentParserRegistry parserRegistry,
                           ChunkingService chunkingService,
                           ChunkPersistenceService chunkPersistenceService,
                           MediaDescriptorService mediaDescriptorService,
                           MediaChunkTemplateService mediaChunkTemplateService,
                           MediaImagePersistenceService mediaImagePersistenceService,
                           MultimodalContextInjector multimodalContextInjector,
                           TokenCounter tokenCounter,
                           EntityExtractionService entityExtractionService,
                           GraphMergeService graphMergeService,
                           DocumentGraphConvergenceApi documentGraphConvergenceApi,
                           InFlightTaskRegistry inFlightTaskRegistry,
                           ChunkExtractCacheRepository chunkExtractCacheRepository,
                           @Qualifier("ragFanoutExecutor") Executor ingestionExecutor) {
        this.ingestionSourceReader = ingestionSourceReader;
        this.chunkBatchWriter = chunkBatchWriter;
        this.parserRegistry = parserRegistry;
        this.chunkingService = chunkingService;
        this.chunkPersistenceService = chunkPersistenceService;
        this.mediaDescriptorService = mediaDescriptorService;
        this.mediaChunkTemplateService = mediaChunkTemplateService;
        this.mediaImagePersistenceService = mediaImagePersistenceService;
        this.multimodalContextInjector = multimodalContextInjector;
        this.tokenCounter = tokenCounter;
        this.entityExtractionService = entityExtractionService;
        this.graphMergeService = graphMergeService;
        this.documentGraphConvergenceApi = documentGraphConvergenceApi;
        this.inFlightTaskRegistry = inFlightTaskRegistry;
        this.chunkExtractCacheRepository = chunkExtractCacheRepository;
        this.ingestionExecutor = ingestionExecutor;
    }

    /**
     * 执行单个文档的完整摄入管线（线程池入口，永不向外抛出异常）。
     * <p>成功语义：{@code process()} 正常返回（Stage 5 图合并执行完毕，或未抽取到实体与关系而
     * 提前跳过合并）即视为摄入完整结束，由本方法统一写入 {@code PROCESSED} 成功终态——
     * 切片落库不再置终态，故此处是成功终态的唯一写入点。</p>
     * <p>失败语义：先判取消（取消静默返回），否则回写 {@code FAILED} 与截断后的失败原因；
     * markFailed 自身失败仅记日志（知识库侧状态保护契约，见类 Javadoc）。</p>
     * <p>收尾语义：三条路径均移除本实例在飞注册表成员，且移除以「状态已写入终态」或
     * 「本就无需置态」为前提——成功路径置态命中（已置 {@code PROCESSED}）或未命中
     * （状态已被删除链 / 启动恢复 / 用户重新解析接管）均视为已收敛，直接移除；
     * 取消路径（状态由删除链 / 用户持有，无需置态）直接移除；失败路径仅当 FAILED 回写成功时移除，
     * 回写失败保留成员交由本实例启动恢复重试。</p>
     *
     * @param documentId 目标文档主键
     */
    public void execute(Long documentId) {
        if (ObjectUtils.isEmpty(documentId)) {
            return;
        }
        try {
            process(documentId);
            // 成功收尾：管线完整结束，写入 PROCESSED 成功终态；置态成功或状态已被接管才移除成员
            if (markProcessedQuietly(documentId)) {
                unregisterQuietly(documentId);
            }
        } catch (Exception e) {
            if (isCancelled(documentId)) {
                log.info("文档摄入已取消，跳过失败回写, documentId={}", documentId);
                // 取消场景状态由删除链 / 用户持有，无需置态，直接移除成员
                unregisterQuietly(documentId);
                return;
            }
            log.error("文档摄入失败, documentId={}", documentId, e);
            if (markFailedQuietly(documentId, buildErrorMessage(e))) {
                // 置态成功才移除成员；置态失败必须保留成员，交由本实例启动恢复重试
                unregisterQuietly(documentId);
            }
        }
    }

    /**
     * 主管线：Stage 0 校验 → Stage 1 解析分块 → Stage 2 多模态增强 → Stage 3 落库
     * → Stage 4 抽取 → Stage 5 图合并。
     * <p>各阶段以 {@link #runStage} 分段执行：耗时逐段累积（收尾一行结构化日志），
     * 裸异常统一归位为带 {@link IngestionStage} 标记的 {@link IngestionStageException}
     * （errorMessage 分类前缀）。</p>
     *
     * @param documentId 目标文档主键
     */
    private void process(Long documentId) {
        StageTimings timings = new StageTimings();
        AtomicInteger cacheHits = new AtomicInteger();
        // Stage 0：前置校验（上下文 / 源文件 / CHAT 模型引用；VLM 引用延后到 Stage 2 现取现用）
        IngestionPrelude prelude = runStage(IngestionStage.PARSE, timings, () -> {
            IngestionDocumentContext ctx = ingestionSourceReader.readContext(documentId);
            requireSourceFile(ctx);
            String chatProfile = requireChatProfile(ctx);
            return new IngestionPrelude(ctx, chatProfile);
        });
        IngestionDocumentContext ctx = prelude.ctx();
        // Stage 1a：解析（文档级策略优先，继承库级；配置异常快速失败置 FAILED，无自动回退）
        // 解析产物中的媒体图片先落对象存储，再把成功引用注入多模态块 meta，
        // 最后为多模态块注入检索上下文（章节路径 / 邻近文本）
        ParseOutcome parseOutcome = runStage(IngestionStage.PARSE, timings, () -> {
            String strategyJson = resolveStrategyJson(ctx);
            DocumentParser parser = parserRegistry.resolve(ctx.parseConfig());
            ParsedDocument parsed = withFileName(parser.parse(ctx.s3File(), strategyJson,
                    ctx.multiModelConfig()), ctx.fileName());
            Map<String, MediaFileRef> mediaRefs = mediaImagePersistenceService.persistImages(
                    ctx.kbId(), ctx.documentId(), parsed);
            ParsedDocument enriched = multimodalContextInjector.inject(bindMediaRefs(parsed, mediaRefs));
            return new ParseOutcome(enriched, parseStrategySettings(strategyJson));
        });
        StrategySettings settings = parseOutcome.settings();
        // Stage 1b：分块（纯直通：显式模式恒为生效模式，未产出内容块判定归位 CHUNK 阶段）
        List<ChunkVO> chunks = runStage(IngestionStage.CHUNK, timings, () -> {
            long dispatchStartNanos = System.nanoTime();
            ChunkingOutcome outcome = chunkingService.chunkWithDispatch(
                    parseOutcome.parsed(), settings.params(), settings.mode());
            long elapsedMs = Math.round((System.nanoTime() - dispatchStartNanos) / 1_000_000.0);
            List<ChunkVO> result = new ArrayList<>(outcome.chunks());
            if (CollectionUtils.isEmpty(result)) {
                throw new IllegalStateException(ERROR_NO_CHUNKS);
            }
            // 直通留痕独立一行 INFO：与收尾六字段摄入摘要并存；纯直通下生效模式恒等于显式模式
            log.info("分块 dispatch | docId={} resolvedMode={} chunkCount={} elapsedMs={}", documentId,
                    ObjectUtils.isEmpty(outcome.resolvedMode())
                            ? DocumentChunkMode.GENERAL : outcome.resolvedMode(),
                    result.size(), elapsedMs);
            return result;
        });
        // Stage 2：多模态增强（内容驱动：有多模态块才增强；模型引用复用 Stage 0 已校验值）
        MediaDescribeOutcome mediaOutcome = runStage(IngestionStage.DESCRIBE, timings,
                () -> enrichMediaChunks(ctx, prelude.chatProfile(), chunks, cacheHits));
        List<MediaEnrichment> enrichments = mediaOutcome.enrichments();
        checkCancelledOrThrow(documentId);
        // Stage 3 前置查询（甲案）：捕获该文档旧代际切片 ID——首次摄入为空、收敛零开销跳过
        List<Long> previousGenerationChunkIds = capturePreviousGenerationChunkIds(documentId);
        String embeddingProfile = resolveEmbeddingProfile(ctx);
        // 图谱来源收敛前移（分块引用完整性不变量）：旧代际账本收敛 MUST 在整篇替换之前完成——
        // 收敛为独立事务先提交，替换随后执行；崩溃落在两步之间时仅留下「旧分块仍在、图谱贡献
        // 已清」的可自愈窗口（下次重解析幂等收敛零行），图谱全程不引用不存在的分块。
        // 取消路径不触发本收敛（删除链自会同事务清退）
        convergePreviousGeneration(ctx.kbId(), previousGenerationChunkIds, documentId);
        // 收敛 → 替换之间的取消闸门：取消落在收敛窗口内时放弃替换（状态可自愈），
        // 也避免删除链已置 DELETING 后仍执行整篇替换
        checkCancelledOrThrow(documentId);
        // Stage 3：切片整篇替换落库（只回写分块计数、不写文档状态；chunk 先于抽取持久化）；
        // 落库事务内直接回传「序号→主键」映射，供 Stage 4 身份绑定直取，不再回查数据库
        Map<Integer, Long> sequenceToChunkId = runStage(IngestionStage.EMBED, timings,
                () -> chunkPersistenceService.replaceForDocument(documentId, chunks, embeddingProfile, null));
        // Stage 3→4 边界：落库回写后删除链若已置 DELETING，及时掐灭，不启动昂贵的抽取阶段
        checkCancelledOrThrow(documentId);
        // Stage 3 之后、Stage 4 之前：为媒体描述补登记缓存归属（见 registerMediaCacheAttributions 的时序说明）
        registerMediaCacheAttributions(ctx.kbId(), chunks, sequenceToChunkId,
                mediaOutcome.cacheKeyByChunkIndex());
        // Stage 4：实体与关系抽取（真实分块主键为来源键）+ 媒体主实体节点
        GraphArtifacts artifacts = runStage(IngestionStage.EXTRACT, timings,
                () -> extractArtifacts(ctx, prelude.chatProfile(), chunks, sequenceToChunkId,
                        enrichments, cacheHits));
        if (CollectionUtils.isEmpty(artifacts.nodes()) && CollectionUtils.isEmpty(artifacts.edges())) {
            log.info("文档未抽取到实体与关系，跳过图合并, documentId={}", documentId);
            logCompletion(documentId, timings, chunks.size(), artifacts, null, cacheHits.get());
            return;
        }
        // Stage 5：图合并（嵌入模型引用缺失即判定失败，向量身份无法构建）
        GraphMergeReport report = runStage(IngestionStage.MERGE, timings,
                () -> mergeGraph(ctx, prelude.chatProfile(), embeddingProfile, artifacts, cacheHits));
        logCompletion(documentId, timings, chunks.size(), artifacts, report, cacheHits.get());
    }

    /**
     * 执行一个管线阶段片段：累积耗时（同一阶段多片段累加），裸运行时异常统一归位为
     * 带阶段标记的 {@link IngestionStageException}（已归位异常原样上抛，不重复包装）。
     *
     * @param stage   阶段标记
     * @param timings 阶段耗时累积器
     * @param body    阶段主体
     * @param <T>     阶段产物类型
     * @return 阶段产物
     * @throws IngestionStageException 阶段内异常（携带归类阶段标记）
     */
    private <T> T runStage(IngestionStage stage, StageTimings timings, Supplier<T> body) {
        long startNanos = System.nanoTime();
        try {
            return body.get();
        } catch (IngestionStageException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IngestionStageException(stage, e);
        } finally {
            timings.addElapsed(stage, System.nanoTime() - startNanos);
        }
    }

    /**
     * 摄入收尾一行结构化 INFO：六阶段耗时 + 抽取条数 + 合并计数
     * + LLM 缓存命中计数，固定 INFO 级别供日志检索消费。
     *
     * @param documentId 文档主键
     * @param timings    阶段耗时累积器
     * @param chunkCount 分块条数
     * @param artifacts  图产物累积
     * @param report     图合并报告（未抽取到实体与关系而提前跳过合并时为 null，相关计数按 0 输出）
     * @param llmCacheHits LLM 缓存命中次数
     */
    private void logCompletion(Long documentId, StageTimings timings, int chunkCount,
                               GraphArtifacts artifacts, GraphMergeReport report, int llmCacheHits) {
        log.info("摄入摘要 | documentId={} parseMs={} chunkMs={} describeMs={} embedMs={} extractMs={}"
                        + " mergeMs={} chunks={} nodes={} edges={} entitiesWritten={} entitiesSkipped={}"
                        + " relationsWritten={} relationsSkipped={} selfLoopDropped={}"
                        + " llmCacheHits={}",
                documentId,
                timings.millisOf(IngestionStage.PARSE), timings.millisOf(IngestionStage.CHUNK),
                timings.millisOf(IngestionStage.DESCRIBE), timings.millisOf(IngestionStage.EMBED),
                timings.millisOf(IngestionStage.EXTRACT), timings.millisOf(IngestionStage.MERGE),
                chunkCount, artifacts.nodes().size(), artifacts.edges().size(),
                ObjectUtils.isEmpty(report) ? 0 : report.entitiesWritten(),
                ObjectUtils.isEmpty(report) ? 0 : report.entitiesSkipped(),
                ObjectUtils.isEmpty(report) ? 0 : report.relationsWritten(),
                ObjectUtils.isEmpty(report) ? 0 : report.relationsSkipped(),
                ObjectUtils.isEmpty(report) ? 0 : report.selfLoopDropped(),
                llmCacheHits);
    }

    /**
     * Stage 0 校验：文档必须携带源文件引用（s3_file 列空白表示无源文件可取）。
     *
     * @param ctx 摄入文档上下文
     * @throws IllegalStateException 源文件引用缺失（状态仍 PROCESSING，失败回写生效）
     */
    private void requireSourceFile(IngestionDocumentContext ctx) {
        S3File s3File = ctx.s3File();
        if (ObjectUtils.isEmpty(s3File)) {
            throw new IllegalStateException(ERROR_NO_SOURCE_FILE);
        }
    }

    /**
     * 校验并返回摄入 LLM 模型 profileId（抽取与摘要共用）。
     * <p>唯一真相源为知识库配置：取 {@code ctx.multiModelConfig().modelProfileId()}——该列虽以
     * 「多模态」命名，实为该知识库统一的通用大模型引用（媒体描述 / 实体抽取 / 关系与关键词提取 /
     * 摘要共用），不再回落任何应用级全局配置。配置缺失（值对象可为 null）或其 profileId 空白
     * 一律按「该库未配置模型引用」显式失败。</p>
     * <p>Stage 2 媒体描述与 Stage 4 抽取 / 摘要均复用本方法返回值（单点校验、同源同值），
     * 已无独立的 VLM 引用解析路径——配置了就能用，没配则整篇摄入在 Stage 0 失败。</p>
     *
     * @param ctx 摄入文档上下文
     * @return 非空白 profileId
     * @throws IllegalStateException 知识库未配置模型引用
     */
    private String requireChatProfile(IngestionDocumentContext ctx) {
        MultiModelConfig multiModel = ctx.multiModelConfig();
        String profileId = ObjectUtils.isEmpty(multiModel)
                ? StringUtils.EMPTY : StringUtils.trimToEmpty(multiModel.modelProfileId());
        if (StringUtils.isBlank(profileId)) {
            throw new IllegalStateException(ERROR_MISSING_LLM_MODEL);
        }
        return profileId;
    }

    /**
     * 生效的分块策略 JSON：文档级非空优先，否则继承库级 {@code rag_engine_config.chunkStrategy}。
     *
     * @param ctx 摄入文档上下文
     * @return 策略 JSON 原文；两处均未配置返回 null（分块走默认参数）
     */
    private String resolveStrategyJson(IngestionDocumentContext ctx) {
        if (StringUtils.isNotBlank(ctx.documentChunkStrategyJson())) {
            return ctx.documentChunkStrategyJson();
        }
        RagEngineConfig engineConfig = ctx.ragEngineConfig();
        return ObjectUtils.isEmpty(engineConfig) ? null : engineConfig.chunkStrategy();
    }

    /**
     * 解析分块策略 JSON 为分块模式与参数（形态：{@code {chunkMode, modeConfig.params}}）。
     * <p>容错口径：整段解析失败仅告警并回落默认参数 + 未配置模式（等同 GENERAL 直通）；
     * 模式名非法 WARN 后回退 GENERAL，不中断管线。参数面仅三项
     * （{@code chunk_token_num} / {@code delimiter} / {@code overlapped_percent}），
     * 存量废弃键由 {@link ChunkParams#fromMap(java.util.Map)} 忽略不消费。</p>
     *
     * @param strategyJson 策略 JSON 原文，可为 null
     * @return 策略配置（非空）
     */
    private StrategySettings parseStrategySettings(String strategyJson) {
        if (StringUtils.isBlank(strategyJson)) {
            return StrategySettings.defaultSettings();
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(strategyJson);
            DocumentChunkMode mode = parseChunkMode(root);
            ChunkParams params = ChunkParams.defaults();
            JsonNode paramsNode = root.path(FIELD_MODE_CONFIG).path(FIELD_PARAMS);
            if (!paramsNode.isMissingNode() && !paramsNode.isNull() && paramsNode.isObject()) {
                params = ChunkParams.fromMap(OBJECT_MAPPER.convertValue(paramsNode, MAP_TYPE));
            }
            return new StrategySettings(mode, params);
        } catch (Exception e) {
            log.warn("分块策略 JSON 解析失败，回落默认分块参数, strategyJson={}", strategyJson, e);
            return StrategySettings.defaultSettings();
        }
    }

    /**
     * 解析分块模式枚举名。
     * <p>口径：</p>
     * <ul>
     *   <li>未配置模式名：返回 null，表示用户未显式选择模式，分块服务按 GENERAL 直通执行；</li>
     *   <li>已知模式名：返回对应枚举；</li>
     *   <li>未知模式名：WARN 留痕后回退 {@code GENERAL}，与未配置殊途同归，
     *       下游无需再为「非法模式串」单独兜底。</li>
     * </ul>
     *
     * @param root 策略 JSON 根节点
     * @return 分块模式；未配置模式名时为 null
     */
    private DocumentChunkMode parseChunkMode(JsonNode root) {
        String modeName = root.path(FIELD_CHUNK_MODE).asText(null);
        if (StringUtils.isBlank(modeName)) {
            return null;
        }
        try {
            return DocumentChunkMode.valueOf(StringUtils.upperCase(StringUtils.trim(modeName)));
        } catch (IllegalArgumentException e) {
            log.warn("未知分块模式 {}，回退 GENERAL 直通", modeName);
            return DocumentChunkMode.GENERAL;
        }
    }

    /**
     * 为解析产物补充文件名（前置）：解析器侧不感知文件名，
     * 由摄入 Worker 在 Stage 1a 统一包装，供下游分块留痕日志与溯源取用。
     *
     * @param parsed   解析器原始产物（fileName 为空），可为 null（解析器无产物时透传）
     * @param fileName 摄入上下文中的文档文件名
     * @return 携带文件名的解析结果；入参为 null 时原样返回
     */
    private ParsedDocument withFileName(ParsedDocument parsed, String fileName) {
        if (ObjectUtils.isEmpty(parsed)) {
            return parsed;
        }
        return new ParsedDocument(parsed.parsedTextHash(), parsed.blocks(), fileName, parsed.images());
    }

    /**
     * 将已成功落对象存储的图片引用注入多模态块 meta。
     * <p>按块 meta 的 {@code img_path}（别名 {@code image_path}）取基名命中引用，命中的块补写
     * {@code mediaObjectKey} 单键（仅对象键，桶概念已退役）；块 meta 可能为不可变 Map，故重建块与 meta 副本。
     * 引用映射为空（本地解析器路径、图片全部写入失败）时原样返回，解析产物零变化。</p>
     *
     * @param parsed    解析产物，可为 null
     * @param mediaRefs 图片名（净化后基名）→ 对象存储引用
     * @return 引用注入后的解析产物；无需注入时原样返回入参
     */
    private ParsedDocument bindMediaRefs(ParsedDocument parsed, Map<String, MediaFileRef> mediaRefs) {
        if (ObjectUtils.isEmpty(parsed) || MapUtils.isEmpty(mediaRefs)) {
            return parsed;
        }
        List<ContentBlockVO> blocks = new ArrayList<>(parsed.blocks().size());
        for (ContentBlockVO block : parsed.blocks()) {
            blocks.add(bindBlockMediaRef(block, mediaRefs));
        }
        return new ParsedDocument(parsed.parsedTextHash(), blocks, parsed.fileName(), parsed.images());
    }

    /**
     * 为单个内容块补写媒体图片引用键。
     * <p>引用未命中（含 {@code MediaImagePersistenceService} 同名防线的摘除：不同原始图片名净化后同名
     * 且字节不同时该基名的引用被全部摘除）时原样返回块，即走既有的「无图片引用」文本回落形态，
     * 本方法不新增任何失败面。</p>
     *
     * @param block     内容块，可为 null
     * @param mediaRefs 图片名 → 对象存储引用（非空）
     * @return 引用注入后的新块；非多模态块、无 img_path 或引用未命中时原样返回
     */
    private ContentBlockVO bindBlockMediaRef(ContentBlockVO block, Map<String, MediaFileRef> mediaRefs) {
        if (ObjectUtils.isEmpty(block) || !block.isMultimodal()) {
            return block;
        }
        String imgPath = MultimodalMetaKeys.metaString(block, MultimodalMetaKeys.META_KEY_IMG_PATH,
                MultimodalMetaKeys.META_KEY_IMAGE_PATH);
        String imgName = MediaImageObjectKeys.sanitize(imgPath);
        if (StringUtils.isBlank(imgName)) {
            return block;
        }
        MediaFileRef ref = mediaRefs.get(imgName);
        if (ObjectUtils.isEmpty(ref)) {
            return block;
        }
        Map<String, Object> meta = new HashMap<>(ObjectUtils.isEmpty(block.meta()) ? Map.of() : block.meta());
        meta.put(MultimodalMetaKeys.META_KEY_MEDIA_OBJECT_KEY, ref.objectKey());
        return new ContentBlockVO(block.type(), block.text(), meta);
    }

    /**
     * Stage 2：多模态增强——由文档内容驱动。
     * <ul>
     *   <li>无多模态块 → 静默跳过，零模型调用、零副作用；</li>
     *   <li>有多模态块 → 并发生成描述（{@code describeConcurrency}）+ 模板化重建 chunk，
     *       产物按原分块序号原位回填（序号与顺序不变）。</li>
     * </ul>
     * <p>模型引用直接复用 Stage 0 已校验的 {@code llmProfile}（与抽取 / 摘要同源同值）：
     * 知识库未配置 {@code multi_model_config} 时 Stage 0 已置 FAILED，不存在「有块无模型」中间态，
     * 故本阶段无需判空降级。</p>
     *
     * @param ctx        摄入文档上下文
     * @param llmProfile 知识库级 LLM 模型 profileId（Stage 0 校验通过的非空白值）
     * @param chunks     分块结果（就地替换多模态块，序号不变）
     * @param cacheHits  LLM 缓存命中计数器（描述回放时递增）
     * @return 增强产物 + 媒体块下标到描述缓存键的映射（供落库后补登记归属；
     *         键由 {@link MediaDescriptorService} 只读算出并写入出参引用，未采集到的块不出现在映射中）
     */
    private MediaDescribeOutcome enrichMediaChunks(IngestionDocumentContext ctx, String llmProfile,
                                                   List<ChunkVO> chunks, AtomicInteger cacheHits) {
        List<Integer> mediaIndexes = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            ChunkVO chunk = chunks.get(i);
            if (ObjectUtils.isNotEmpty(chunk.block()) && chunk.block().isMultimodal()) {
                mediaIndexes.add(i);
            }
        }
        if (mediaIndexes.isEmpty()) {
            return new MediaDescribeOutcome(List.of(), Map.of());
        }
        return describeConcurrently(ctx, llmProfile, chunks, mediaIndexes, cacheHits);
    }

    /**
     * 并发生成多模态描述并按索引回填 chunk。
     * <p>描述任务经注入的受管执行器 {@code ragFanoutExecutor} 提交
     * （{@link CompletableFuture#runAsync(Runnable, Executor)}），不再逐块手写创建虚拟线程；
     * 实际并发度仍由 Semaphore 闸门限流至 {@code describeConcurrency}（&lt;=1 即串行），
     * 扇出等待经执行器关闭治理（宽限 → 中断）。闸门等待采用可中断的
     * {@link Semaphore#acquire()}，等待被中断时恢复中断标记并记为首个失败，使该阶段判定失败
     * （杜绝「静默部分成功」）。描述结果先落到按槽位索引的数组，全部任务聚合收口
     * （{@link CompletableFuture#allOf}）后在主线程按原分块序号顺序重建 chunk 与增强产物——
     * 回填顺序与串行执行完全一致。</p>
     * <p><b>缓存键采集</b>：每个媒体块槽位挂一个独立的 {@link AtomicReference} 作为描述服务的键出参
     * （任务内独写、聚合后单线程读，无竞态），聚合后沉淀为「媒体块在 chunks 中的下标 → 缓存键」
     * 映射——媒体块下标是描述期唯一稳定的块身份（此时尚无分块主键），落库后再据此换算真实主键。</p>
     *
     * @param ctx          摄入文档上下文
     * @param vlmProfile   VLM 模型 profileId（非空白）
     * @param chunks       分块结果（就地替换多模态块）
     * @param mediaIndexes 多模态块在 chunks 中的下标（升序）
     * @param cacheHits    LLM 缓存命中计数器
     * @return 增强产物 + 媒体块下标到描述缓存键的映射（按下标升序，未采集到键的块不出现）
     */
    private MediaDescribeOutcome describeConcurrently(IngestionDocumentContext ctx, String vlmProfile,
                                                      List<ChunkVO> chunks, List<Integer> mediaIndexes,
                                                      AtomicInteger cacheHits) {
        String language = effectiveLanguage(ctx);
        int concurrency = Math.max(1, describeConcurrency);
        Semaphore permits = new Semaphore(concurrency);
        int total = mediaIndexes.size();
        MediaDescriptionVO[] descriptions = new MediaDescriptionVO[total];
        List<AtomicReference<String>> cacheKeyRefs = new ArrayList<>(total);
        for (int slot = 0; slot < total; slot++) {
            cacheKeyRefs.add(new AtomicReference<>());
        }
        // 记录首个描述异常，聚合收口后回抛主线程，使并发失败的阶段归位与串行一致（归位 DESCRIBE）
        AtomicReference<RuntimeException> firstFailure = new AtomicReference<>();
        List<CompletableFuture<Void>> futures = new ArrayList<>(total);
        for (int slot = 0; slot < total; slot++) {
            final int index = slot;
            final ContentBlockVO block = chunks.get(mediaIndexes.get(slot)).block();
            final AtomicReference<String> cacheKeyRef = cacheKeyRefs.get(slot);
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    permits.acquire();
                } catch (InterruptedException e) {
                    // 闸门等待被中断（执行器关闭治理）：恢复中断标记并记为首个失败，使阶段判定失败，
                    // 杜绝 allOf 正常返回后带着不完整描述结果继续落库的「静默部分成功」
                    Thread.currentThread().interrupt();
                    firstFailure.compareAndSet(null,
                            new IllegalStateException("多模态描述并发等待闸门被中断", e));
                    return;
                }
                try {
                    descriptions[index] = mediaDescriptorService.describe(
                            ctx.kbId(), vlmProfile, language, block, cacheKeyRef, cacheHits);
                } catch (RuntimeException e) {
                    firstFailure.compareAndSet(null, e);
                } finally {
                    permits.release();
                }
            }, ingestionExecutor));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        RuntimeException failure = firstFailure.get();
        if (ObjectUtils.isNotEmpty(failure)) {
            throw failure;
        }
        Map<Integer, String> cacheKeyByChunkIndex = new LinkedHashMap<>(total);
        List<MediaEnrichment> enrichments = new ArrayList<>(total);
        for (int slot = 0; slot < total; slot++) {
            int chunkIndex = mediaIndexes.get(slot);
            String cacheKey = cacheKeyRefs.get(slot).get();
            if (StringUtils.isNotBlank(cacheKey)) {
                cacheKeyByChunkIndex.put(chunkIndex, cacheKey);
            }
            ChunkVO original = chunks.get(chunkIndex);
            ChunkVO rebuilt = mediaChunkTemplateService.buildChunk(
                    original.sequence(), original.block(), descriptions[slot], language, tokenCounter);
            chunks.set(chunkIndex, rebuilt);
            enrichments.add(new MediaEnrichment(rebuilt, descriptions[slot]));
        }
        return new MediaDescribeOutcome(enrichments, cacheKeyByChunkIndex);
    }

    /**
     * 为媒体描述补登记「分块 → 抽取缓存行」归属（Stage 3 落库之后、Stage 4 抽取之前）。
     * <p><b>为何延迟到落库后登记</b>：媒体描述调用于 Stage 2（切片落库之前）发生，此刻本批切片
     * 还没有数据库主键，无法形成归属关系；描述调用点因而改为经
     * {@link MediaDescriptorService} 的键出参把「该媒体块用了哪个缓存键」带回，
     * 由本方法在 Stage 3 回传的真实分块主键到手后补登记。抽取期（Stage 4）的归属仍由
     * 缓存客户端在调用点就地登记，不在本方法范围内。</p>
     * <p><b>序号换算依据</b>：Stage 3 回传映射的键是 {@link ChunkVO#sequence()}（从 1 开始），
     * 故媒体块下标先经 {@code chunks.get(index).sequence()} 换成序号，再查映射得真实主键
     * （MUST NOT 以「下标 + 1」直接推定序号）。</p>
     * <p><b>登记失败不影响摄入</b>：归属登记属旁路观测职责，单对配对异常只记 WARN 与累计次数，
     * MUST NOT 向上抛出、MUST NOT 影响摄入成败；空白键或映射中缺失主键的配对直接跳过。</p>
     *
     * @param kbId                 所属知识库ID
     * @param chunks               分块结果（下标为媒体块下标换算序号的位置依据）
     * @param sequenceToChunkId    Stage 3 落库回传的「序号 → 主键」映射
     * @param cacheKeyByChunkIndex 媒体块在 chunks 中的下标 → 描述缓存键（可空）
     */
    private void registerMediaCacheAttributions(Long kbId, List<ChunkVO> chunks,
                                                Map<Integer, Long> sequenceToChunkId,
                                                Map<Integer, String> cacheKeyByChunkIndex) {
        if (MapUtils.isEmpty(cacheKeyByChunkIndex)) {
            return;
        }
        int failures = 0;
        for (Map.Entry<Integer, String> entry : cacheKeyByChunkIndex.entrySet()) {
            String cacheKey = entry.getValue();
            Long chunkId = sequenceToChunkId.get(chunks.get(entry.getKey()).sequence());
            if (ObjectUtils.isEmpty(chunkId) || StringUtils.isBlank(cacheKey)) {
                continue;
            }
            try {
                chunkExtractCacheRepository.registerAll(kbId, chunkId, CacheType.EXTRACT, List.of(cacheKey));
            } catch (RuntimeException failed) {
                failures++;
                log.warn("媒体描述缓存归属补登记失败（不影响本次摄入）: kbId={}, chunkId={}, cacheKey={}, "
                        + "累计失败次数={}", kbId, chunkId, cacheKey, failures, failed);
            }
        }
    }

    /**
     * 生效摄入语言：知识库语言（{@code ctx.language()}，全名口径）优先，
     * 未配置 / 空白时回落代码内默认 Chinese。
     *
     * @param ctx 摄入文档上下文
     * @return 生效语言（非空白）
     */
    private String effectiveLanguage(IngestionDocumentContext ctx) {
        return StringUtils.defaultIfBlank(ctx.language(), KbLanguage.Chinese.name());
    }

    /**
     * 解析嵌入模型 profileId。
     *
     * @param ctx 摄入文档上下文
     * @return 嵌入模型 profileId；库未配置返回 null（切片向量降级，Stage 5 图合并判定失败）
     */
    private String resolveEmbeddingProfile(IngestionDocumentContext ctx) {
        return ObjectUtils.isEmpty(ctx.embeddingConfig()) ? null : ctx.embeddingConfig().modelProfileId();
    }

    /**
     * 预查捕获该文档当前代际（将被 Stage 3 整篇替换的旧代）切片 ID 集合。
     * <p>首次摄入（文档尚无切片）返回空集合，调用侧据此跳过收敛、零额外写入。
     * 只读查询，位于替换事务之外，符合「事务内不含远程/耗时操作」的事务规范。</p>
     *
     * @param documentId 文档主键
     * @return 旧代际切片 ID 集合（无旧代时为空集合）
     */
    private List<Long> capturePreviousGenerationChunkIds(Long documentId) {
        List<ChunkIdentity> identities = ingestionSourceReader.listChunkIdentitiesByDocument(documentId);
        if (CollectionUtils.isEmpty(identities)) {
            return List.of();
        }
        List<Long> chunkIds = new ArrayList<>(identities.size());
        for (ChunkIdentity identity : identities) {
            chunkIds.add(identity.chunkId());
        }
        return chunkIds;
    }

    /**
     * 在整篇替换之前同步收敛旧代际的图谱贡献账本（两段式契约：事务外重建计算 → 事务内写回
     * 与收敛 → 提交后向量收口；账本剔除、剔空条目物理删、语义字段整体重建，幂等）。
     * <p><b>时序定位</b>（MUST NOT 后移回替换之后）：本方法是分块整篇替换的前置步骤——
     * 「分块行消失」永远不得先于图谱账本清退，否则替换提交与收敛之间的崩溃会产生永久
     * 死引用（图谱引用不存在的分块）。旧代际切片全部归属本知识库，透传 {@code kbId}
     * 使收敛语句扫描面收窄到本库；重建读取抽取缓存与缓存回收同样先于旧分块行被替换清除
     * （组 6 顺序不变量：分块行删除晚于重建读取，旧代行消失发生在随后的整篇替换事务）。</p>
     * <p><b>窗口形态与自愈路径</b>：收敛（apply 单事务）先提交，替换随后执行；崩溃或取消
     * 落在两步之间时，文档表现为「旧分块仍在、图谱贡献已清、归属与缓存已回收」——下次
     * 重新解析时收敛对旧分块集合幂等命中零行，自动收敛，不产生永久残留。</p>
     * <p><b>失败语义 fail-closed</b>：prepare（事务外重建计算，含摘要/向量化远程）与 apply
     * （自持单事务，纯 DB）失败即整体中止——apply 失败时其事务回滚、旧分块与旧账本保持一致；
     * 本方法将异常归位 {@link IngestionStage#EMBED} 后上抛以中止本次摄入（按摄入管线既有失败
     * 语义置 FAILED，不自动重试，用户重新解析即从 prepare 重推）。提交后的向量收口异常
     * MUST NOT 中止摄入（收敛事实已提交），仅 ERROR 留痕。空集合（首次摄入）直接跳过，
     * 不产生任何图谱写操作。</p>
     *
     * @param kbId                       本知识库主键（透传给收敛以收窄扫描面）
     * @param previousGenerationChunkIds 旧代际切片 ID 集合，可为空
     * @param documentId                 文档主键（收敛审计锚点 + 留痕定位）
     * @throws IngestionStageException 重建计算或收敛写回失败（归位 EMBED 阶段，中止本次摄入、不执行替换）
     */
    private void convergePreviousGeneration(Long kbId, List<Long> previousGenerationChunkIds, Long documentId) {
        if (CollectionUtils.isEmpty(previousGenerationChunkIds)) {
            return;
        }
        DocumentGraphConvergencePlan plan;
        DocumentGraphConvergenceResult result;
        try {
            // 【事务外·远程】重建计算（重放 + 摘要 + 向量化，零写入）；【单事务】写回 + 收缩 + 缓存回收
            plan = documentGraphConvergenceApi.prepare(kbId, documentId, previousGenerationChunkIds, null);
            result = documentGraphConvergenceApi.apply(plan);
            log.info("重解析旧代际图谱收敛完成 | kbId={} documentId={} oldChunks={} removedEntries={}"
                            + " rebuiltEntries={} degradedEntries={} reclaimedCacheRows={}",
                    kbId, documentId, previousGenerationChunkIds.size(), result.prunedEntries(),
                    result.rebuiltEntries(), result.degradedEntries(), result.reclaimedCacheRows());
        } catch (RuntimeException e) {
            // 结构化留痕：失败事实与受影响分块数进入收尾日志，供运维侧发现（收敛不自动重试）
            log.error("重解析旧代际图谱收敛失败（中止本次摄入，不执行整篇替换） | kbId={} documentId={}"
                    + " oldChunkCount={}", kbId, documentId, previousGenerationChunkIds.size(), e);
            throw new IngestionStageException(IngestionStage.EMBED, e);
        }
        try {
            // 【提交后·事务外·远程】向量收口：收敛事实已提交，收口异常仅留痕、MUST NOT 中止摄入
            documentGraphConvergenceApi.reconcilePendingVectorContent(plan, result);
        } catch (RuntimeException e) {
            log.error("重解析旧代际向量收口异常（收敛已提交，残留偏差由条目下次整体重建收敛）"
                    + " | kbId={} documentId={}", kbId, documentId, e);
        }
    }

    /**
     * Stage 4：实体与关系抽取——文本块批量抽取 + 媒体块逐块带主实体名 sidecar 抽取 + 媒体主实体节点。
     * <p>抽取入参先经 {@link #bindPersistedChunks} 与落库回传映射绑定为 {@link PersistedChunkVO}
     * （构造即真值），抽取产物的溯源来源键自出生起即为真实分块主键，无任何占位重写环节。
     * 本方法是多模态主实体节点的<b>唯一构造点</b>（抽取阶段只产出归属关系边、不造节点）：
     * 名称、类型与描述全部取自该块的多模态描述产物——名称为描述服务一次定形的
     * 「裸名 + 内容类型后缀」（与 sidecar 抽取注入的归属边端点逐字一致，保证同名归并为
     * 单节点），描述取一行摘要（完整详描留在块正文，经归属边溯源，不写入主实体描述）；
     * 不存在第二个构造点对其字段做覆盖。主实体仍经图合并服务写入（不直写图与向量表），
     * 保证向量行携带来源账本、可被来源清退识别回收。</p>
     *
     * @param ctx               摄入文档上下文
     * @param chatProfile       抽取 LLM profileId
     * @param chunks            分块结果（含多模态重建块）
     * @param sequenceToChunkId Stage 3 落库事务内回传的「序号→主键」映射
     * @param enrichments       多模态增强产物
     * @param cacheHits         LLM 缓存命中计数器（抽取回放时递增）
     * @return 图产物累积（顺序：文本节点 → 媒体节点 → 文本边 → 媒体边）
     */
    private GraphArtifacts extractArtifacts(IngestionDocumentContext ctx, String chatProfile,
                                            List<ChunkVO> chunks, Map<Integer, Long> sequenceToChunkId,
                                            List<MediaEnrichment> enrichments, AtomicInteger cacheHits) {
        BooleanSupplier cancelCheck = () -> isCancelled(ctx.documentId());
        List<PersistedChunkVO> persistedChunks =
                bindPersistedChunks(chunks, sequenceToChunkId, ctx.documentId());
        Map<Integer, PersistedChunkVO> bySequence = new HashMap<>(persistedChunks.size());
        for (PersistedChunkVO persisted : persistedChunks) {
            bySequence.put(persisted.sequence(), persisted);
        }
        List<EntityNode> nodes = new ArrayList<>();
        List<RelationEdge> edges = new ArrayList<>();
        List<PersistedChunkVO> textChunks = persistedChunks.stream()
                .filter(persisted -> ObjectUtils.isEmpty(persisted.block()) || !persisted.block().isMultimodal())
                .toList();
        if (CollectionUtils.isNotEmpty(textChunks)) {
            EntityExtractionContext extractCtx =
                    buildExtractionContext(ctx, chatProfile, cancelCheck, null, cacheHits);
            appendResult(nodes, edges, entityExtractionService.extract(extractCtx, textChunks));
        }
        for (MediaEnrichment enrichment : enrichments) {
            EntityExtractionContext sidecarCtx = buildExtractionContext(ctx, chatProfile, cancelCheck,
                    enrichment.description().entityName(), cacheHits);
            PersistedChunkVO persistedMedia = bySequence.get(enrichment.chunk().sequence());
            appendResult(nodes, edges, entityExtractionService.extract(sidecarCtx, List.of(persistedMedia)));
            // 主实体节点唯一构造点：名称取定形后的实体名，描述取一行摘要（详描留在块正文）
            nodes.add(EntityNode.create(ctx.kbId(), enrichment.description().entityName(),
                    enrichment.description().entityType(), enrichment.description().primaryEntityDescription(),
                    persistedMedia.chunkId(), ctx.fileName()));
        }
        return new GraphArtifacts(nodes, edges);
    }

    /**
     * 分块中间态与落库回传映射绑定为落库态值对象（抽取链路身份绑定，构造即真值）。
     * <p>映射来源为 Stage 3 落库事务内回传的「序号→主键」（与本批切片一一对应，天然全覆盖）；
     * 任一序号在映射中缺失即视为落库回传不变量被破坏，快速失败，SHALL NOT 让任何非真实主键
     * 以来源键形态进入抽取产物与图谱账本。</p>
     *
     * @param chunks            分块结果（含多模态重建块，按分块序）
     * @param sequenceToChunkId 落库回传的序号→主键映射
     * @param documentId        文档主键（异常定位）
     * @return 落库态分块列表（保持入参顺序，逐块携带真实分块主键）
     * @throws IllegalStateException 任一序号缺映射（落库回传不变量被破坏）
     */
    private List<PersistedChunkVO> bindPersistedChunks(List<ChunkVO> chunks,
                                                       Map<Integer, Long> sequenceToChunkId, Long documentId) {
        List<PersistedChunkVO> persisted = new ArrayList<>(chunks.size());
        for (ChunkVO chunk : chunks) {
            Long chunkId = sequenceToChunkId.get(chunk.sequence());
            if (ObjectUtils.isEmpty(chunkId)) {
                throw new IllegalStateException("切片主键绑定失败（落库回传不变量被破坏）：documentId="
                        + documentId + "，sequence=" + chunk.sequence());
            }
            persisted.add(new PersistedChunkVO(chunk.sequence(), chunkId, chunk));
        }
        return persisted;
    }

    /**
     * 构建实体抽取上下文（文档级参数统一口径）。
     *
     * @param ctx                  摄入文档上下文
     * @param chatProfile          抽取 LLM profileId
     * @param cancelCheck          取消检查器
     * @param mediaPrimaryEntityName 多模态主实体名（文本批量抽取传 null）
     * @param cacheHits            LLM 缓存命中计数器（挂入抽取上下文）
     * @return 抽取上下文
     */
    private EntityExtractionContext buildExtractionContext(IngestionDocumentContext ctx, String chatProfile,
                                                           BooleanSupplier cancelCheck,
                                                           String mediaPrimaryEntityName,
                                                           AtomicInteger cacheHits) {
        return new EntityExtractionContext(ctx.kbId(), ctx.documentId(), chatProfile, effectiveLanguage(ctx),
                ctx.entityTypes(), extractionJsonMode, extractionMaxGleaning,
                EntityExtractionContext.DEFAULT_CONCURRENCY, ctx.fileName(), cancelCheck,
                mediaPrimaryEntityName, cacheHits);
    }

    /**
     * 追加一次抽取的产物到累积列表。
     *
     * @param nodes  节点累积
     * @param edges  边累积
     * @param result 单次抽取结果
     */
    private void appendResult(List<EntityNode> nodes, List<RelationEdge> edges, EntityExtractionResult result) {
        if (ObjectUtils.isEmpty(result)) {
            return;
        }
        nodes.addAll(result.nodes());
        edges.addAll(result.edges());
    }

    /**
     * Stage 5：图合并写入。
     * <p>嵌入模型 profileId 缺失即显式失败：{@link GraphMergeContext} 的向量化身份无法构建，
     * 图谱与向量均无法入库，摄入不构成完成——不产出「有切片、无向量、无图谱」的假成功文档，
     * 由本异常触发文档归 {@code FAILED}（阶段前缀 MERGE），用户配置嵌入模型后重新解析即可。</p>
     * <p>本阶段失败会翻转文档状态：切片落库不再置终态，文档在飞期间恒为 {@code PROCESSING}，
     * 失败回写的条件流转因此得以命中；已落库切片保留不回滚。</p>
     *
     * @param ctx               摄入文档上下文
     * @param chatProfile       摘要 LLM profileId
     * @param embeddingProfile  嵌入模型 profileId，空白即失败
     * @param artifacts         图产物累积
     * @param cacheHits         LLM 缓存命中计数器（摘要回放时递增）
     * @return 图合并报告（恒非 null，未配置嵌入模型时上抛异常）
     * @throws IllegalStateException 知识库未配置嵌入模型引用（由调用侧归位 MERGE 阶段失败）
     */
    private GraphMergeReport mergeGraph(IngestionDocumentContext ctx, String chatProfile,
                                        String embeddingProfile, GraphArtifacts artifacts,
                                        AtomicInteger cacheHits) {
        if (StringUtils.isBlank(embeddingProfile)) {
            log.error("知识库未配置嵌入模型引用（embeddingConfig.modelProfileId 空白），"
                    + "无法构建向量身份，图合并与向量入库无法执行，摄入判定失败, documentId={}",
                    ctx.documentId());
            throw new IllegalStateException(ERROR_MISSING_EMBEDDING_MODEL);
        }
        checkCancelledOrThrow(ctx.documentId());
        GraphMergeContext mergeCtx = new GraphMergeContext(ctx.kbId(), ctx.documentId(), chatProfile,
                embeddingProfile, effectiveLanguage(ctx), resolveGraphMergeParams(ctx), null,
                () -> isCancelled(ctx.documentId()), cacheHits);
        return graphMergeService.mergeNodesAndEdges(mergeCtx, artifacts.nodes(), artifacts.edges());
    }

    /**
     * 解析图合并参数：以应用级配置（{@code app.rag.graph.*}）为基底，
     * rag_engine_config JSON 原文根级探测 {@code graphMerge / graph_merge} 子段逐项覆盖
     * （优先级：知识库级 JSONB &gt; 应用级配置 &gt; 内置默认）；
     * 子段缺失或解析失败回落应用级基底（V10 建库配置当前不承载图合并段）。
     *
     * @param ctx 摄入文档上下文
     * @return 图合并参数（非空）
     */
    private GraphMergeParams resolveGraphMergeParams(IngestionDocumentContext ctx) {
        GraphMergeParams appBase = appLevelGraphMergeParams();
        String configJson = ctx.ragEngineConfigJson();
        if (StringUtils.isBlank(configJson)) {
            return appBase;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(configJson);
            JsonNode mergeNode = root.path(FIELD_GRAPH_MERGE_CAMEL);
            if (mergeNode.isMissingNode() || mergeNode.isNull()) {
                mergeNode = root.path(FIELD_GRAPH_MERGE_SNAKE);
            }
            if (mergeNode.isMissingNode() || mergeNode.isNull() || !mergeNode.isObject()) {
                return appBase;
            }
            return GraphMergeParams.fromMap(OBJECT_MAPPER.convertValue(mergeNode, MAP_TYPE), appBase);
        } catch (Exception e) {
            log.warn("图合并段配置解析失败，回落应用级参数, documentId={}", ctx.documentId(), e);
            return appBase;
        }
    }

    /**
     * 取应用级图合并参数基底（首次调用惰性构造后缓存复用）。
     * <p>基底混入 {@code @Value} 应用级配置，编译期常量不成立，故以实例字段缓存一次
     * （钉死前提见 {@link #cachedAppLevelGraphMergeParams} 字段注释）；
     * 加锁保证并发摄入下至多构造一次，读取热路径仅首次进入同步块。</p>
     *
     * @return 应用级参数基底（非空，同值复用同一实例）
     */
    private synchronized GraphMergeParams appLevelGraphMergeParams() {
        if (ObjectUtils.isEmpty(cachedAppLevelGraphMergeParams)) {
            cachedAppLevelGraphMergeParams = buildAppLevelGraphMergeParams();
        }
        return cachedAppLevelGraphMergeParams;
    }

    /**
     * 构建应用级图合并参数基底：source_ids 上限与截断策略、来源文件路径上限与占位词取
     * {@code app.rag.graph.*} 配置，其余参数取内置默认（应用级暂不承载）；
     * 非法值由紧凑构造器归一化兜底。
     *
     * @return 应用级参数基底
     */
    private GraphMergeParams buildAppLevelGraphMergeParams() {
        return new GraphMergeParams(
                GraphMergeParams.DEFAULT_LLM_MODEL_MAX_ASYNC,
                graphSourceIdsLimit,
                GraphMergeParams.DEFAULT_EMBEDDING_TOKEN_LIMIT,
                GraphMergeParams.DEFAULT_SUMMARY_CONTEXT_SIZE,
                GraphMergeParams.DEFAULT_SUMMARY_MAX_TOKENS,
                GraphMergeParams.DEFAULT_FORCE_LLM_SUMMARY_ON_MERGE,
                graphSourceIdsTruncation,
                graphSourceFilePathsLimit,
                graphSourceFilePathsPlaceholder
        );
    }

    /**
     * 取消判定：文档状态为 {@code null}（行缺失＝删除链已收口，「已删除」唯一表达）、
     * {@code PENDING}（用户重新解析）、{@code FAILED}（外部回滚）或已进入删除链
     * （{@code DELETING / DELETE_FAILED} 两态，见 {@link #CANCELLED_STATUSES}）视为已取消。
     * <p>取消判定为显式白名单（见 {@link #CANCELLED_STATUSES}）：仅集合内取值视为已取消，
     * 其余取值（在飞期间的 {@code PROCESSING}，以及极端并发下收尾自身写入的 {@code PROCESSED}）
     * 一律视为应继续执行——MUST NOT 以「状态非 {@code PROCESSING}」推断取消，
     * 否则会把「无需回写失败」的场景误判为需要失败回写。</p>
     *
     * @param documentId 文档主键
     * @return true 表示应中断管线且无需回写失败
     */
    private boolean isCancelled(Long documentId) {
        DocumentStatus status;
        try {
            status = ingestionSourceReader.statusOf(documentId);
        } catch (Exception e) {
            log.error("取消检查读取文档状态失败, documentId={}", documentId, e);
            return false;
        }
        return ObjectUtils.isEmpty(status) || CANCELLED_STATUSES.contains(status);
    }

    /**
     * 阶段边界取消检查：已取消则抛出携带 {@link IngestionStage#CANCELLED} 标记的中断异常
     * （由 {@link #execute(Long)} 顶层统一静默收敛；竞态窗口下若走失败回写，
     * errorMessage 前缀可与真实故障区分）。
     *
     * @param documentId 文档主键
     * @throws IngestionStageException 已取消（阶段标记 {@link IngestionStage#CANCELLED}）
     */
    private void checkCancelledOrThrow(Long documentId) {
        if (isCancelled(documentId)) {
            throw new IngestionStageException(IngestionStage.CANCELLED, "摄入已取消：documentId=" + documentId);
        }
    }

    /**
     * 成功终态回写（自身异常仅记日志，不再上抛）。
     * <p>三分类语义（设计口径）：命中表示已置 {@code PROCESSED}；未命中表示文档状态已被删除链、
     * 本实例启动恢复或用户重新解析推进，本就无需置态——两种情况状态均已收敛，均返回
     * {@code true} 供调用方移除在飞注册表成员；仅回写异常（状态是否收敛未知）返回 {@code false}，
     * 成员必须保留，交由本实例启动恢复重试。</p>
     *
     * @param documentId 文档主键
     * @return {@code true} 表示状态已收敛（命中置态成功，或未命中而无需置态），可安全移除在飞成员；
     *         {@code false} 表示回写异常、状态未知，成员 MUST 保留
     */
    private boolean markProcessedQuietly(Long documentId) {
        try {
            boolean hit = chunkBatchWriter.markProcessed(documentId);
            if (!hit) {
                log.info("摄入成功终态回写未命中（状态已被删除链 / 启动恢复 / 用户重新解析接管），"
                        + "按状态已收敛处置并移除在飞成员, documentId={}", documentId);
            }
            return true;
        } catch (Exception e) {
            log.error("摄入成功终态回写异常, documentId={}", documentId, e);
            return false;
        }
    }

    /**
     * 失败原因回写（自身异常仅记日志，不再上抛）。
     *
     * @param documentId   文档主键
     * @param errorMessage 截断后的失败原因
     * @return {@code true} 表示 FAILED 已成功写入（或本就无需置态），可安全移除在飞注册表成员；
     *         {@code false} 表示回写异常、状态未知，成员必须保留
     */
    private boolean markFailedQuietly(Long documentId, String errorMessage) {
        try {
            chunkBatchWriter.markFailed(documentId, errorMessage);
            return true;
        } catch (Exception e) {
            log.error("摄入失败状态回写异常, documentId={}", documentId, e);
            return false;
        }
    }

    /**
     * 移除本实例在飞注册表中的文档摄入成员（三条收尾路径的统一动作）。
     * <p>注册表实现本身已吞移除异常，此处为防御性兜底：异常仅 ERROR 留痕、不向上抛出，
     * 不影响管线收尾与消费线程退出。</p>
     *
     * @param documentId 文档主键
     */
    private void unregisterQuietly(Long documentId) {
        try {
            inFlightTaskRegistry.unregister(InFlightTaskType.DOC_INGESTION, documentId);
        } catch (Exception e) {
            log.error("移除在飞注册表成员异常, documentId={}", documentId, e);
        }
    }

    /**
     * 构建失败原因文案：阶段分类前缀 + 异常消息（空白回落类名），
     * 前缀不改变既有长度截断与落库格式，总长仍截断至上限。
     *
     * @param e 管线异常
     * @return 回写文案（形如 {@code [EXTRACT] 原始异常摘要}）
     */
    private String buildErrorMessage(Exception e) {
        String message = StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName());
        return StringUtils.abbreviate(resolveStage(e).prefix() + message, MAX_ERROR_MESSAGE_LENGTH);
    }

    /**
     * 归位失败阶段：沿异常因果链自外向内查找首个 {@link IngestionStageException}，
     * 未命中回落 {@link IngestionStage#UNKNOWN_STAGE}（前缀永不缺位）。
     *
     * @param e 管线异常
     * @return 阶段标记（非空）
     */
    private IngestionStage resolveStage(Throwable e) {
        for (Throwable cursor = e; ObjectUtils.isNotEmpty(cursor); cursor = cursor.getCause()) {
            if (cursor instanceof IngestionStageException stageException) {
                return stageException.stage();
            }
        }
        return IngestionStage.UNKNOWN_STAGE;
    }

    /**
     * Stage 0 前置校验产物（摄入上下文与 CHAT 模型引用；VLM 引用延后 Stage 2 现取）。
     *
     * @param ctx         摄入文档上下文
     * @param chatProfile CHAT 模型 profileId（非空白）
     */
    private record IngestionPrelude(IngestionDocumentContext ctx, String chatProfile) {
    }

    /**
     * Stage 1 解析产物（解析文档与分块策略配置）。
     *
     * @param parsed   解析结果
     * @param settings 分块策略配置（非空）
     */
    private record ParseOutcome(ParsedDocument parsed, StrategySettings settings) {
    }

    /**
     * 阶段耗时累积器（nanoTime 差值折毫秒按阶段累加，同一阶段多片段共享一格）。
     */
    private static final class StageTimings {

        /** 各阶段累计毫秒（下标为 {@link IngestionStage#ordinal()}）。 */
        private final long[] millis = new long[IngestionStage.values().length];

        /**
         * 累加一段耗时到指定阶段。
         *
         * @param stage 阶段标记
         * @param nanos 纳秒耗时
         */
        void addElapsed(IngestionStage stage, long nanos) {
            millis[stage.ordinal()] += Math.round(nanos / 1_000_000.0);
        }

        /**
         * 查询指定阶段的累计毫秒。
         *
         * @param stage 阶段标记
         * @return 累计毫秒
         */
        long millisOf(IngestionStage stage) {
            return millis[stage.ordinal()];
        }
    }

    /**
     * 分块策略解析产物（模式 + 参数）。
     *
     * @param mode   分块模式（null 表示未配置，分块服务按 GENERAL 直通执行；未知模式串已收敛为 GENERAL）
     * @param params 分块参数（非空）
     */
    private record StrategySettings(DocumentChunkMode mode, ChunkParams params) {

        /**
         * 默认策略：未配置模式 + 默认分块参数。
         *
         * @return 默认策略配置
         */
        static StrategySettings defaultSettings() {
            return new StrategySettings(null, ChunkParams.defaults());
        }
    }

    /**
     * 多模态增强产物（模板重建后的 chunk 与媒体主实体描述）。
     *
     * @param chunk       重建后的分块
     * @param description 媒体主实体描述
     */
    private record MediaEnrichment(ChunkVO chunk, MediaDescriptionVO description) {
    }

    /**
     * Stage 2 多模态增强总产物：增强列表 + 媒体块下标到描述缓存键的映射。
     * <p>缓存键映射是「媒体描述归属延迟登记」的载体——Stage 2 尚无可用的分块主键，
     * 只能先按媒体块在 chunks 中的下标沉淀键值，落库后再换算成真实主键补登记。</p>
     *
     * @param enrichments         增强产物列表（重建 chunk + 媒体主实体描述，按原分块序号升序）
     * @param cacheKeyByChunkIndex 媒体块在 chunks 中的下标 → 本次描述所用缓存键
     *                             （未采集到键的媒体块不出现；无媒体块时为空映射）
     */
    private record MediaDescribeOutcome(List<MediaEnrichment> enrichments,
                                        Map<Integer, String> cacheKeyByChunkIndex) {
    }

    /**
     * 图产物累积容器（Stage 4 抽取产物到 Stage 5 合并的中间载体，可变列表支撑逐块追加累积）。
     *
     * @param nodes 实体节点列表
     * @param edges 关系边列表
     */
    private record GraphArtifacts(List<EntityNode> nodes, List<RelationEdge> edges) {
    }
}
