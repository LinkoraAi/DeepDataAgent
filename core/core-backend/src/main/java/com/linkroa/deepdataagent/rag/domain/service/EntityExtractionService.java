package com.linkroa.deepdataagent.rag.domain.service;

import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.BuiltinEntityType;
import com.linkroa.deepdataagent.rag.domain.enums.CacheType;
import com.linkroa.deepdataagent.rag.domain.model.ContentBlockVO;
import com.linkroa.deepdataagent.rag.domain.model.EntityNode;
import com.linkroa.deepdataagent.rag.domain.model.EntityProperties;
import com.linkroa.deepdataagent.rag.domain.model.LlmCacheEntry;
import com.linkroa.deepdataagent.rag.domain.model.PersistedChunkVO;
import com.linkroa.deepdataagent.rag.domain.model.RelationContribution;
import com.linkroa.deepdataagent.rag.domain.model.RelationEdge;
import com.linkroa.deepdataagent.rag.domain.model.RelationProperties;
import com.linkroa.deepdataagent.rag.domain.port.LlmChatRequest;
import com.linkroa.deepdataagent.rag.domain.port.LlmChatResult;
import com.linkroa.deepdataagent.rag.domain.port.LlmClient;
import com.linkroa.deepdataagent.rag.domain.repository.ChunkExtractCacheRepository;
import com.linkroa.deepdataagent.rag.domain.repository.LlmCacheRepository;
import com.linkroa.deepdataagent.rag.infrastructure.prompts.catalog.PromptCatalog;
import com.linkroa.deepdataagent.rag.infrastructure.prompts.catalog.PromptTemplates;
import com.linkroa.deepdataagent.knowledgebase.domain.model.EntityType;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 实体抽取领域服务（文档级实体/关系抽取主流程，tasks.md 5.1）。
 *
 * <p><b>流程</b>：对单个文档的一批 chunk 逐块并发调用 LLM 抽取实体与关系
 * （Semaphore 闸门限流，风格与 {@link GraphMergeService} 阶段并发一致）→ 每块「首轮抽取 +
 * 补漏（gleaning）追问」（单块抽取 = 首轮 + 最多 N 轮续抽，N={@code ctx.maxGleaning()}；
 * 续抽循环三停止条件——最近响应含完成信号 {@value #COMPLETION_DELIMITER}、本轮解析零新增
 * 记录、达 N 轮上限——满足任一即终止，详见 {@code extractSingleChunk}）→
 * 文本分隔符 / JSON 双模式容错解析 → 按知识库实体类型清单过滤
 * （类型不在清单的实体丢弃、引用被丢弃实体的关系连带丢弃）→ 多模态 sidecar 归属边注入
 * （块内文本实体与主实体建立 belongs_to 边，权重 {@value #BELONGS_TO_WEIGHT}；
 * 主实体节点<b>不在本阶段构造</b>——唯一构造点在摄入管线，本处只消费上下文携带的
 * 已定形主实体名）→ 跨 chunk 文档级聚合（同名实体、同无向端点对关系归一：
 * 描述取长、类型补空、关键词并集、端点方向恒归一为字典序；关系权重不再压成文档级标量，
 * 而是按无向端点对收集逐来源的 {@link RelationContribution} 贡献记录，
 * 供合并端逐来源全额累加）。</p>
 *
 * <p><b>输出</b>：{@link EntityExtractionResult}（{@code List<EntityNode>} +
 * {@code List<RelationEdge>} 片段），可直接喂给 {@link GraphMergeService#mergeNodesAndEdges}。
 * 实体 {@code properties.sourceIds} 为「该实体出现过的真实分块主键升序列表」；
 * 关系边按贡献记录「一记录一边」产出——每条边的 {@code sourceIds} 只含该记录的真实分块主键、
 * {@code weight} 为该记录全额（普通 1.0 / belongs_to {@value #BELONGS_TO_WEIGHT}）。
 * 来源键均为落库后的真实分块 ID（由 {@link PersistedChunkVO} 承载，构造即真值），
 * 不存在占位与重写环节；文档级溯源另依赖 {@code documentId} 锚点与 {@code filePath}。</p>
 *
 * <p><b>失败与取消</b>（两条失败口径、严格分流）：单块<b>业务异常</b>（模型响应非法、解析失败等）
 * 仅记录告警并跳过该块（部分成功语义，不炸整批，既有有意设计）；但条目在等待并发闸门时
 * <b>被中断</b>一律计为阶段失败——全部任务收口后以首个异常上抛（其余 addSuppressed），
 * MUST NOT 以不完整快照集合继续聚合（与 {@link GraphMergeService} 阶段 fail-closed 口径同族），
 * 由管线既有失败回写路径将文档置 {@code FAILED}，杜绝「缺块却写成 PROCESSED」的静默部分成功。
 * 取消检查在入口、每块提交前、全部完成后共三处生效（与图合并阶段取消语义一致），
 * 提交循环内命中时先等待已提交任务收口再上抛 {@link IllegalStateException}，
 * 不留无人等待的孤儿扇出任务。LLM 缓存由
 * {@code CachingLlmClient} 装饰器内嵌处理，本服务不自行管理。</p>
 *
 * <p><b>缓存重放入口</b>：{@link #replayExtractedChunks} 面向文档删除期的图谱重建（design D5），
 * 按存活分块重放抽取缓存响应并复用与真实抽取<b>完全相同</b>的解析链（双模式解析、类型清单过滤、
 * sidecar 归属边注入、名称归一），纯读缓存与解析、零 LLM 调用，产出「分块 → 与抽取出口同形的
 * 记录集合」。</p>
 *
 * <p><b>补漏上下文适配</b>：续抽模板（{@value #CONTINUE_TEXT_TEMPLATE} /
 * {@value #CONTINUE_JSON_TEMPLATE}）按多轮对话设计、不含 {@code {input_text}} 占位符，
 * 而 {@link LlmClient} 端口为单轮请求-响应式；故将「续抽指令 + 分块原文 + 历轮原始响应」
 * 合成单轮用户提示词发送，同时天然保证 llm_cache 键按块、按轮次唯一。</p>
 *
 * @author DeepDataAgent
 */
@Service
public class EntityExtractionService {

    /** 日志器 */
    private static final Logger log = LoggerFactory.getLogger(EntityExtractionService.class);

    // ------------------------------------------------------------------ 抽取协议分隔符
    // 单一定义处为 PromptTemplates，此处仅保留可读性别名

    /** 元组分隔符：行内字段分隔 */
    static final String TUPLE_DELIMITER = PromptTemplates.TUPLE_DELIMITER;

    /** 完成信号：模型输出该字面量表示补漏结束 */
    static final String COMPLETION_DELIMITER = PromptTemplates.COMPLETION_DELIMITER;

    // ------------------------------------------------------------------ Prompt 模板名（集中定义见 PromptTemplates）

    /** 章节上下文模板名 */
    private static final String SECTION_CONTEXT_TEMPLATE = PromptTemplates.ENTITY_EXTRACTION_SECTION_CONTEXT;

    /** 文本模式系统提示词模板名 */
    private static final String TEXT_SYSTEM_TEMPLATE = PromptTemplates.ENTITY_EXTRACTION_SYSTEM_PROMPT;

    /** 文本模式用户提示词模板名 */
    private static final String TEXT_USER_TEMPLATE = PromptTemplates.ENTITY_EXTRACTION_USER_PROMPT;

    /** 文本模式续抽（gleaning）用户提示词模板名 */
    private static final String CONTINUE_TEXT_TEMPLATE = PromptTemplates.ENTITY_CONTINUE_EXTRACTION_USER_PROMPT;

    /** JSON 模式系统提示词模板名 */
    private static final String JSON_SYSTEM_TEMPLATE = PromptTemplates.ENTITY_EXTRACTION_JSON_SYSTEM_PROMPT;

    /** JSON 模式用户提示词模板名 */
    private static final String JSON_USER_TEMPLATE = PromptTemplates.ENTITY_EXTRACTION_JSON_USER_PROMPT;

    /** JSON 模式续抽（gleaning）用户提示词模板名 */
    private static final String CONTINUE_JSON_TEMPLATE = PromptTemplates.ENTITY_CONTINUE_EXTRACTION_JSON_USER_PROMPT;

    // ------------------------------------------------------------------ 模板占位符键

    /** 占位符：实体类型引导文本 */
    private static final String VAR_ENTITY_TYPES_GUIDANCE = "entity_types_guidance";

    /** 占位符：单次响应总记录数上限 */
    private static final String VAR_MAX_TOTAL_RECORDS = "max_total_records";

    /** 占位符：单次响应实体记录数上限 */
    private static final String VAR_MAX_ENTITY_RECORDS = "max_entity_records";

    /** 占位符：输出语言（注入知识库语言全名） */
    private static final String VAR_LANGUAGE = "language";

    /** 占位符：输出格式示例 */
    private static final String VAR_EXAMPLES = "examples";

    /** 占位符：章节上下文块 */
    private static final String VAR_HEADING_CONTEXT_BLOCK = "heading_context_block";

    /** 占位符：输入文本 */
    private static final String VAR_INPUT_TEXT = "input_text";

    /** 占位符：章节路径 */
    private static final String VAR_HEADING_PATH = "heading_path";

    // ------------------------------------------------------------------ 记录限额（参考 LightRAG 默认口径）

    /** 单次响应实体+关系总行数上限 */
    static final int MAX_TOTAL_RECORDS = 100;

    /** 单次响应实体行数上限 */
    static final int MAX_ENTITY_RECORDS = 40;

    // ------------------------------------------------------------------ 文本模式解析协议

    /** 实体行前缀关键字 */
    private static final String ENTITY_ROW_PREFIX = "entity";

    /** 关系行前缀关键字 */
    private static final String RELATION_ROW_PREFIX = "relation";

    /**
     * 关系行前缀别名关键字：兼容受 LightRAG 原版示例语料污染的模型输出——原版提示词示例
     * 使用 {@code relationship} 前缀，本关键字与 {@link #RELATION_ROW_PREFIX} 完全等价，
     * 命中同一解析分支（复用全部字段数/端点/自环校验），模板措辞仍保持 {@code relation}。
     */
    private static final String RELATION_ROW_PREFIX_ALIAS = "relationship";

    /** 实体行最少字段数（前缀+名称+类型+描述） */
    private static final int ENTITY_ROW_PARTS = 4;

    /** 关系行最少字段数（前缀+源+目标+关键词+描述） */
    private static final int RELATION_ROW_PARTS = 5;

    /** 实体行描述起始下标 */
    private static final int ENTITY_DESCRIPTION_FROM = 3;

    /** 关系行描述起始下标 */
    private static final int RELATION_DESCRIPTION_FROM = 4;

    /** 普通抽取关系默认权重（文本/JSON 模式模板均不输出权重字段） */
    private static final double DEFAULT_RELATION_WEIGHT = 1.0;

    /** sidecar belongs_to 边权重（设计方案约定值） */
    static final double BELONGS_TO_WEIGHT = 10.0;

    /** sidecar belongs_to 边关键词（设计方案逐字约定） */
    private static final List<String> BELONGS_TO_KEYWORDS = List.of("belongs_to", "part_of", "contained_in");

    /** 关系关键词字段内部分隔符 */
    private static final String KEYWORD_SEPARATOR = ",";

    /** 行分隔符（LLM 响应按行切分） */
    private static final String LINE_SEPARATOR = "\n";

    // ------------------------------------------------------------------ 文本模式解析修复层（上游粘连重切/残字修复/错前缀恢复）

    /** 粘连重切标记：元组分隔符包裹的 entity 记录前缀（模型误用元组分隔符作记录分隔符时出现于行内） */
    private static final String ENTITY_RECORD_MARKER = TUPLE_DELIMITER + ENTITY_ROW_PREFIX + TUPLE_DELIMITER;

    /** 粘连重切标记：元组分隔符包裹的 relation 记录前缀 */
    private static final String RELATION_RECORD_MARKER = TUPLE_DELIMITER + RELATION_ROW_PREFIX + TUPLE_DELIMITER;

    /** 粘连重切标记：元组分隔符包裹的 relationship 别名前缀（与前缀别名关键字同义） */
    private static final String RELATIONSHIP_RECORD_MARKER =
            TUPLE_DELIMITER + RELATION_ROW_PREFIX_ALIAS + TUPLE_DELIMITER;

    /** 元组分隔符核心字符（{@code <|#|>} 中的 {@code #}，上游 tuple_delimiter[2:-2] 同口径） */
    private static final String DELIMITER_CORE =
            StringUtils.substring(TUPLE_DELIMITER, 2, TUPLE_DELIMITER.length() - 2);

    /** 残字修复形态一：双核粘连（上游 {@code <|#|*?#|>}，命中 {@code <|##|>}、{@code <|#||#|>} 等） */
    private static final Pattern DELIMITER_DOUBLE_CORE = Pattern.compile(
            "<\\|" + Pattern.quote(DELIMITER_CORE) + "\\|*?" + Pattern.quote(DELIMITER_CORE) + "\\|>");

    /** 残字修复形态二：转义污染（上游 {@code <|\#|>}，模型误加反斜杠） */
    private static final Pattern DELIMITER_ESCAPE = Pattern.compile(
            "<\\|\\\\" + Pattern.quote(DELIMITER_CORE) + "\\|>");

    /**
     * 残字修复形态三：缺核形态（{@code <|>}、{@code <||>} 等），MUST 仅在两侧紧邻非空白时修复
     * （上游 glued-only 口径：自由文本中的 {@code a <|> b} 不被误改写）。
     */
    private static final Pattern DELIMITER_MISSING_CORE = Pattern.compile("(?<=\\S)<\\|+>(?=\\S)");

    // ------------------------------------------------------------------ 类型过滤与兜底

    /** 兜底实体类型 */
    private static final String OTHER_TYPE = "Other";

    /** 语言全名：中文（知识库语言配置值，few-shot 双套例句判定用） */
    private static final String LANGUAGE_FULL_NAME_CHINESE = "Chinese";

    /** 取消中断异常文案（与图合并阶段口径一致） */
    private static final String CANCELLED_MESSAGE = "摄入已取消，实体抽取中止";

    /** 中断阶段失败异常文案前缀（闸门等待被中断以阶段失败收口，真实原因经 cause 链携带） */
    private static final String INTERRUPTED_FAILURE_PREFIX = "实体抽取阶段失败（任务被中断）：";

    // ------------------------------------------------------------------ JSON 模式解析协议

    /** JSON 字段：实体数组 */
    private static final String FIELD_ENTITIES = "entities";

    /** JSON 字段：关系数组 */
    private static final String FIELD_RELATIONSHIPS = "relationships";

    /** JSON 字段：名称 */
    private static final String FIELD_NAME = "name";

    /** JSON 字段：类型 */
    private static final String FIELD_TYPE = "type";

    /** JSON 字段：描述 */
    private static final String FIELD_DESCRIPTION = "description";

    /** JSON 字段：源实体 */
    private static final String FIELD_SOURCE = "source";

    /** JSON 字段：目标实体 */
    private static final String FIELD_TARGET = "target";

    /** JSON 字段：关键词 */
    private static final String FIELD_KEYWORDS = "keywords";

    /** JSON 对象起始符 */
    private static final String JSON_OBJECT_START = "{";

    /** JSON 对象结束符 */
    private static final String JSON_OBJECT_END = "}";

    // ------------------------------------------------------------------ sidecar 主实体约定

    /** 分块元数据键：章节路径 */
    private static final String META_HEADING_PATH = "heading_path";

    /** belongs_to 边描述格式：文本实体归属主实体 */
    private static final String BELONGS_TO_DESCRIPTION_FORMAT = "Entity %s belongs to %s";

    /** 关系聚合键端点分隔符（NUL 字符，实体名不可能含该字符） */
    private static final String PAIR_KEY_SEPARATOR = "\u0000";

    // ------------------------------------------------------------------ 续抽单轮提示词组装段

    /** 续抽模板尾部标记（组装时剥离后重新置于末尾） */
    private static final String OUTPUT_SECTION_HEADER = "---Output---";

    /** 续抽组装段：输入文本标题 */
    private static final String INPUT_TEXT_SECTION_HEADER = "---Input Text---";

    /** 续抽组装段：历轮已抽取内容标题 */
    private static final String PREVIOUS_SECTION_HEADER = "---Previously Extracted Content---";

    /** 代码围栏标记 */
    private static final String CODE_FENCE = "```";

    /** 历轮原始响应连接分隔符 */
    private static final String PREVIOUS_RESPONSE_SEPARATOR = "\n\n";

    // ------------------------------------------------------------------ few-shot 示例（按库语言双套预渲染真实分隔符字面量）

    /** 中文例句集：文本模式输出格式示例（语言全名 {@code Chinese} 的库注入） */
    private static final String EXAMPLES_TEXT_ZH = buildTextExamples(true);

    /** 中文例句集：JSON 模式输出格式示例（语言全名 {@code Chinese} 的库注入） */
    private static final String EXAMPLES_JSON_ZH = buildJsonExamples(true);

    /** 英文例句集：文本模式输出格式示例（除 {@code Chinese} 外全部语言库注入） */
    private static final String EXAMPLES_TEXT_EN = buildTextExamples(false);

    /** 英文例句集：JSON 模式输出格式示例（除 {@code Chinese} 外全部语言库注入） */
    private static final String EXAMPLES_JSON_EN = buildJsonExamples(false);

    /** LLM 对话端口（缓存由装饰器内嵌） */
    private final LlmClient llmClient;

    /** 摄入专用执行器（chunk 级并发载体） */
    private final Executor ingestionExecutor;

    /** JSON 解析器（JSON 模式响应解析） */
    private final ObjectMapper objectMapper;

    /** 抽取缓存归属仓储端口（重放路径经其追溯「分块 → 缓存键集合」，见 {@link #replayExtractedChunks}） */
    private final ChunkExtractCacheRepository chunkExtractCacheRepository;

    /** LLM 调用缓存仓储端口（重放路径经其按键批量读取缓存响应行，不触发任何 LLM 调用） */
    private final LlmCacheRepository llmCacheRepository;

    /**
     * 构造实体抽取领域服务。
     *
     * @param llmClient                 LLM 对话端口
     * @param ingestionExecutor         虚拟线程扇出执行器（ragFanoutExecutor，chunk 级并发仍由 Semaphore 闸门约束）
     * @param objectMapper              Jackson 对象映射器
     * @param chunkExtractCacheRepository 抽取缓存归属仓储端口（重放入口追溯分块缓存键用）
     * @param llmCacheRepository        LLM 调用缓存仓储端口（重放入口批量读取缓存响应用）
     */
    public EntityExtractionService(LlmClient llmClient,
                                   @Qualifier("ragFanoutExecutor") Executor ingestionExecutor,
                                   ObjectMapper objectMapper,
                                   ChunkExtractCacheRepository chunkExtractCacheRepository,
                                   LlmCacheRepository llmCacheRepository) {
        this.llmClient = llmClient;
        this.ingestionExecutor = ingestionExecutor;
        this.objectMapper = objectMapper;
        this.chunkExtractCacheRepository = chunkExtractCacheRepository;
        this.llmCacheRepository = llmCacheRepository;
    }

    /**
     * 文档级实体抽取主入口：对一批 chunk 并发抽取并按文档聚合。
     *
     * <p><b>两条失败口径（严格分流）</b>：① 单块<b>业务异常</b>（模型响应非法、解析失败等）
     * 仅告警跳过该块，文档允许带部分结果成功——既有有意设计，保持不变；② 任一条目在等待
     * 并发闸门时<b>被中断</b>（典型为执行器停机 shutdownNow）一律计为阶段失败：收集器非空时
     * 在全部任务收口后以首个异常上抛（其余 addSuppressed），MUST NOT 调用 {@code aggregate}
     * 以缺块快照继续聚合——静默写 {@code PROCESSED} 会永久缺失实体/关系且不可自愈，
     * 上抛使管线走既有失败回写路径置 {@code FAILED}（用户可重新解析）。两条路径在留痕上可区分：
     * 后者异常链必携带 {@link InterruptedException} 原因。</p>
     *
     * <p>取消检查在入口、每块提交前、全部完成后三处生效；提交循环内命中取消时
     * <b>先等待已提交任务收口</b>（任务体自带闸门与异常兜底、必然结束）再上抛，
     * 不留无人等待的孤儿扇出任务。chunk 列表为空时直接返回空结果（不发起任何 LLM 调用）。</p>
     *
     * @param ctx    抽取执行上下文
     * @param chunks 文档的分块落库态列表（携带真实分块主键，按 sequence 升序传入以获得稳定的聚合遍历序）
     * @return 文档级聚合后的抽取结果，可直接喂给 {@link GraphMergeService#mergeNodesAndEdges}
     * @throws IllegalArgumentException 上下文为 null 时抛出
     * @throws IllegalStateException    任务等待闸门被中断（cause 为真实中断原因）或检测到摄入已取消时抛出
     */
    public EntityExtractionResult extract(EntityExtractionContext ctx, List<PersistedChunkVO> chunks) {
        if (ObjectUtils.isEmpty(ctx)) {
            throw new IllegalArgumentException("实体抽取上下文不能为空");
        }
        if (ctx.isCancelled()) {
            throw new IllegalStateException(CANCELLED_MESSAGE);
        }
        if (ObjectUtils.isEmpty(chunks)) {
            return EntityExtractionResult.empty();
        }
        String systemPrompt = buildSystemPrompt(ctx);
        Set<String> customTypes = resolveCustomTypes(ctx.entityTypes());
        int concurrency = Math.min(Math.max(ctx.concurrency(), 1), chunks.size());
        Semaphore gate = new Semaphore(concurrency);
        // 中断失败收集器：只收「闸门等待被中断」条目，与单块业务异常的容忍跳过严格分流
        // （fail-closed 形态对齐 GraphMergeService#runPhase）
        ConcurrentLinkedQueue<Throwable> interruptFailures = new ConcurrentLinkedQueue<>();
        ChunkSnapshot[] perChunk = new ChunkSnapshot[chunks.size()];
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int index = 0; index < chunks.size(); index++) {
            if (ctx.isCancelled()) {
                // 先收口已提交任务再上抛取消：被放弃的在飞任务无人等待会继续消耗 LLM 配额，
                // 且其结果已不可消费，等待其自然结束消除孤儿扇出
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                throw new IllegalStateException(CANCELLED_MESSAGE);
            }
            final int taskIndex = index;
            final PersistedChunkVO chunk = chunks.get(index);
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    gate.acquire();
                } catch (InterruptedException interrupted) {
                    // 中断 ≠ 业务失败：该块内容缺失不可自愈，恢复中断标记并计入收集器，
                    // 阶段在收口后判定失败，MUST NOT 静默丢弃按成功处理
                    Thread.currentThread().interrupt();
                    interruptFailures.add(interrupted);
                    log.warn("实体抽取任务等待闸门时被中断：documentId=[{}]，sequence=[{}]",
                            ctx.documentId(), chunk.sequence());
                    return;
                }
                try {
                    perChunk[taskIndex] = extractSingleChunk(ctx, systemPrompt, customTypes, chunk);
                } catch (Exception failed) {
                    // 单块业务异常维持既有「告警跳过、允许部分成功」语义，MUST NOT 计入中断收集器
                    log.warn("chunk 实体抽取失败，跳过该块：documentId=[{}]，sequence=[{}]",
                            ctx.documentId(), chunk.sequence(), failed);
                } finally {
                    gate.release();
                }
            }, ingestionExecutor));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        if (ObjectUtils.isNotEmpty(interruptFailures)) {
            // 存在中断缺口：以阶段失败收口，MUST NOT 进入聚合（首异常上抛、其余 addSuppressed）
            List<Throwable> errors = new ArrayList<>(interruptFailures);
            Throwable first = errors.get(0);
            for (int i = 1; i < errors.size(); i++) {
                first.addSuppressed(errors.get(i));
            }
            throw new IllegalStateException(INTERRUPTED_FAILURE_PREFIX + first.getMessage(), first);
        }
        if (ctx.isCancelled()) {
            throw new IllegalStateException(CANCELLED_MESSAGE);
        }
        List<ChunkSnapshot> snapshots = Arrays.stream(perChunk)
                .filter(Objects::nonNull)
                .toList();
        return aggregate(ctx, snapshots);
    }

    // ------------------------------------------------------------------ 抽取缓存重放（design D5）

    /**
     * 抽取缓存重放入口（OpenSpec 变更 rebuild-kg-on-document-delete / tasks 3.1~3.3，design D5）。
     *
     * <p><b>纯重放、零 LLM 调用</b>：入参为知识库与存活分块集合，经归属表追溯各分块摄入期使用过的
     * 缓存键，再按 {@code (kb_id, EXTRACT, keys)} <b>一次批量</b>读取 {@code llm_cache} 响应行
     * （禁止 N+1 逐键查），按响应创建时间<b>升序</b>逐块解析，输出「分块 → 该分块抽取记录集合
     * （实体节点 + 关系边）」。全程不调用 {@link LlmClient}——不查缓存未命中、不回写、不算键，
     * 缓存行的不可变性（重建依赖"响应可复现"）因此不受影响。</p>
     *
     * <p><b>重放与抽取同形</b>：解析链与 {@link #extract} 共用同一套私有实现——
     * 分隔符/JSON 双模式容错解析（含文本模式修复层）、实体名称归一（{@link EntityNameNormalizer}）、
     * 知识库实体类型清单过滤、多模态 sidecar 归属边注入，以及「同一分块内同名条目的多条记录取
     * 描述更长者」的汇聚合并律（{@link ExtractionHolder}，对齐上游 LightRAG：描述更长者更接近模型
     * 完整表述；先出现的响应为基准，后来者仅在描述<b>严格更长</b>时覆盖，等长保先）。
     * 首轮与续抽的全部响应均纳入：同一分块的多条缓存行即各轮请求的完整留痕，
     * 按创建时间升序喂入同一汇聚器，即还原该分块当初抽取的产出（design D5 的成立前提）。</p>
     *
     * <p><b>入参删除期可构造约束</b>：删除期只有 kbId、存活分块的库内行（主键/正文/块 meta）
     * 与库级配置（实体类型清单、输出模式）可得，故入口不依赖任何摄入期一次性状态
     * （documentId、模型 profileId、提示词目录、取消检查器等均不参与——语言与提示词只参与
     * 请求构建、不参与响应解析方向的语义），库级配置由 {@link ChunkReplayContext} 显式携带、
     * 分块级定形主实体名由 {@link ReplayChunkInput#mediaPrimaryEntityName()} 逐块携带。</p>
     *
     * <p><b>缓存缺失语义（供上层降级判定）</b>：归属表无键、键查不到缓存行、或响应解析不出记录时，
     * 对应分块返回<b>空记录集合</b>；MUST NOT 抛异常、MUST NOT 回退真实 LLM 抽取。
     * 上层（重建服务）据此进入「保留语义字段、仅修账本与来源路径」的降级分支。</p>
     *
     * <p><b>分块间不串扰</b>：归属表只存 cache_key，同键可能被多分块共用；
     * 组装严格按「分块 → 其归属键 → 缓存行」进行——某分块的记录集合只来自其归属键命中的响应行。
     * 共用键的两分块各得一份同形记录，与当初各自独立抽取的产出一致。</p>
     *
     * @param ctx    重放执行上下文（kbId + 实体类型清单 + 输出模式，删除期均可得）
     * @param chunks 存活分块输入列表（分块主键、正文、块 meta、定形多模态主实体名）
     * @return 分块主键 → 该分块抽取记录集合（与抽取出口同形的 {@link EntityExtractionResult}）；
     *         入参分块列表为空返回空映射；每个入参分块都有对应条目（无缓存可用者为空记录集合）
     * @throws IllegalArgumentException 上下文为 null 时抛出
     */
    public Map<Long, EntityExtractionResult> replayExtractedChunks(ChunkReplayContext ctx,
                                                                   List<ReplayChunkInput> chunks) {
        if (ObjectUtils.isEmpty(ctx)) {
            throw new IllegalArgumentException("分块重放上下文不能为空");
        }
        if (CollectionUtils.isEmpty(chunks)) {
            return Map.of();
        }
        List<Long> chunkIds = chunks.stream()
                .filter(ObjectUtils::isNotEmpty)
                .map(ReplayChunkInput::chunkId)
                .distinct()
                .toList();
        Map<Long, Set<String>> keysByChunk = chunkExtractCacheRepository
                .findCacheKeysByChunkIds(ctx.kbId(), CacheType.EXTRACT, chunkIds);
        Map<String, LlmCacheEntry> entryByKey = loadEntriesByChunkKeys(ctx.kbId(), keysByChunk);
        Set<String> customTypes = resolveCustomTypes(ctx.entityTypes());
        Map<Long, EntityExtractionResult> results = new LinkedHashMap<>();
        for (ReplayChunkInput chunk : chunks) {
            if (ObjectUtils.isEmpty(chunk)) {
                continue;
            }
            results.put(chunk.chunkId(), replaySingleChunk(ctx, chunk, keysByChunk, entryByKey, customTypes));
        }
        long replayedChunks = results.values().stream()
                .filter(result -> CollectionUtils.isNotEmpty(result.nodes())
                        || CollectionUtils.isNotEmpty(result.edges()))
                .count();
        log.info("抽取缓存重放完成：kbId=[{}]，分块数=[{}]，有记录分块数=[{}]",
                ctx.kbId(), results.size(), replayedChunks);
        return results;
    }

    /**
     * 按「分块 → 键集合」一次批量读取缓存行并按键索引（tasks 3.2：批量读取防 N+1）。
     * <p>所有分块的归属键去重合并为单一键集合后一次下发仓储；键 → 缓存行为一对一
     * （{@code (kb_id, cache_type, cache_key)} 复合唯一键保证），库内异常出现同键多行时保留首行。</p>
     *
     * @param kbId        所属知识库ID
     * @param keysByChunk 归属表追溯的「分块 → 缓存键集合」映射
     * @return 缓存键 → 缓存行索引（无键或全部未命中时为空映射）
     */
    private Map<String, LlmCacheEntry> loadEntriesByChunkKeys(Long kbId, Map<Long, Set<String>> keysByChunk) {
        Set<String> allKeys = keysByChunk.values().stream()
                .filter(CollectionUtils::isNotEmpty)
                .flatMap(Collection::stream)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (CollectionUtils.isEmpty(allKeys)) {
            return Map.of();
        }
        List<LlmCacheEntry> entries = llmCacheRepository.findByKbIdAndCacheKeys(kbId, CacheType.EXTRACT, allKeys);
        Map<String, LlmCacheEntry> entryByKey = new LinkedHashMap<>();
        for (LlmCacheEntry entry : entries) {
            if (ObjectUtils.isNotEmpty(entry) && StringUtils.isNotBlank(entry.cacheKey())) {
                entryByKey.putIfAbsent(entry.cacheKey(), entry);
            }
        }
        return entryByKey;
    }

    /**
     * 单分块重放：其归属键命中的缓存行按创建时间升序全部解析进同一汇聚器，
     * 再经与抽取相同的类型过滤与 sidecar 注入收口，产出与抽取出口同形的记录集合。
     *
     * <p><b>合并顺序与取长口径（tasks 3.3）</b>：多条响应按 {@code createdAt} 升序依序喂入
     * {@link ExtractionHolder}（首轮必早于续抽；同键时间异常并列时以 cacheKey 字典序兜底保证确定性），
     * 同名/同端点对条目由汇聚器执行「先出现者为基准、后来者仅描述严格更长才覆盖」的合并律
     * ——与上游 LightRAG 重建的取描述更长者口径对齐。</p>
     *
     * @param ctx         重放执行上下文
     * @param chunk       目标分块输入
     * @param keysByChunk 归属表追溯的「分块 → 缓存键集合」映射
     * @param entryByKey  缓存键 → 缓存行索引
     * @param customTypes 知识库自定义实体类型允许集（小写 trim）
     * @return 该分块的抽取记录集合；无可用缓存时为空记录集合（不抛异常）
     */
    private EntityExtractionResult replaySingleChunk(ChunkReplayContext ctx, ReplayChunkInput chunk,
                                                     Map<Long, Set<String>> keysByChunk,
                                                     Map<String, LlmCacheEntry> entryByKey,
                                                     Set<String> customTypes) {
        Set<String> cacheKeys = keysByChunk.get(chunk.chunkId());
        List<LlmCacheEntry> responses = ObjectUtils.isEmpty(cacheKeys)
                ? List.of()
                : cacheKeys.stream()
                        .map(entryByKey::get)
                        .filter(ObjectUtils::isNotEmpty)
                        .sorted(Comparator.comparing(LlmCacheEntry::createdAt,
                                        Comparator.nullsFirst(Comparator.naturalOrder()))
                                .thenComparing(LlmCacheEntry::cacheKey,
                                        Comparator.nullsFirst(Comparator.naturalOrder())))
                        .toList();
        ExtractionHolder holder = new ExtractionHolder();
        for (LlmCacheEntry entry : responses) {
            String text = StringUtils.trimToEmpty(entry.response());
            if (StringUtils.isBlank(text)) {
                log.warn("重放的缓存响应为空白，跳过该条解析：chunkId=[{}]，cacheKey=[{}]",
                        chunk.chunkId(), entry.cacheKey());
                continue;
            }
            holder.beginResponse();
            parseResponse(ctx.jsonMode(), text, holder, chunk.chunkId());
        }
        applyTypeFilter(holder, customTypes);
        injectSidecarEntities(chunk.mediaPrimaryEntityName(), chunk.block(), chunk.chunkId(), holder);
        return toChunkResult(ctx.kbId(), holder.snapshot(chunk.chunkId()));
    }

    /**
     * 单分块快照转与抽取出口同形的记录集合（tasks 3.1 出参）。
     * <p>实体 → 节点（sourceIds 为该分块主键单元素列表，与 {@link #aggregate} 单来源块的出口形态一致）；
     * 关系 → 「一记录一边」展开。构造经 {@link #toEntityNode} / {@link #toRelationEdges}
     * 与文档级聚合共用同一出口收口；来源文件路径不承载（重建侧按存活账本重算）。</p>
     *
     * @param kbId     所属知识库ID
     * @param snapshot 单分块抽取快照（过滤与 sidecar 注入后的最终中间态）
     * @return 该分块的抽取记录集合
     */
    private EntityExtractionResult toChunkResult(Long kbId, ChunkSnapshot snapshot) {
        List<Long> sourceIds = ObjectUtils.isEmpty(snapshot.chunkId())
                ? List.of() : List.of(snapshot.chunkId());
        List<EntityNode> nodes = snapshot.entities().stream()
                .map(entity -> toEntityNode(kbId, null, entity, sourceIds))
                .toList();
        List<RelationEdge> edges = snapshot.relations().stream()
                .flatMap(relation -> toRelationEdges(kbId, null, relation).stream())
                .toList();
        return new EntityExtractionResult(nodes, edges);
    }

    // ------------------------------------------------------------------ 提示词构建

    /**
     * 构建抽取系统提示词（按输出模式选模板，注入实体类型引导、记录限额与预渲染示例）。
     *
     * @param ctx 抽取执行上下文
     * @return 渲染完成的系统提示词
     */
    private String buildSystemPrompt(EntityExtractionContext ctx) {
        String guidance = PromptCatalog.buildEntityTypesGuidance(ctx.entityTypes(), ctx.language());
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put(VAR_ENTITY_TYPES_GUIDANCE, guidance);
        vars.put(VAR_MAX_TOTAL_RECORDS, MAX_TOTAL_RECORDS);
        vars.put(VAR_MAX_ENTITY_RECORDS, MAX_ENTITY_RECORDS);
        vars.put(VAR_LANGUAGE, StringUtils.trim(ctx.language()));
        vars.put(VAR_EXAMPLES, resolveExamples(ctx.language(), ctx.jsonMode()));
        String template = ctx.jsonMode() ? JSON_SYSTEM_TEMPLATE : TEXT_SYSTEM_TEMPLATE;
        return PromptCatalog.render(template, ctx.language(), vars);
    }

    /**
     * 构建首轮抽取用户提示词（含可选的章节上下文块与分块原文）。
     *
     * @param ctx   抽取执行上下文
     * @param chunk 目标分块（落库态）
     * @return 渲染完成的用户提示词
     */
    private String buildUserPrompt(EntityExtractionContext ctx, PersistedChunkVO chunk) {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put(VAR_MAX_TOTAL_RECORDS, MAX_TOTAL_RECORDS);
        vars.put(VAR_MAX_ENTITY_RECORDS, MAX_ENTITY_RECORDS);
        vars.put(VAR_LANGUAGE, StringUtils.trim(ctx.language()));
        vars.put(VAR_HEADING_CONTEXT_BLOCK, buildHeadingContextBlock(ctx, chunk));
        vars.put(VAR_INPUT_TEXT, chunk.text());
        String template = ctx.jsonMode() ? JSON_USER_TEMPLATE : TEXT_USER_TEMPLATE;
        return PromptCatalog.render(template, ctx.language(), vars);
    }

    /**
     * 构建章节上下文块：分块元数据含 {@code heading_path} 时渲染上下文模板并补换行，
     * 否则返回空串（模板正文以 `{heading_context_block}---Input Text---` 直接拼接）。
     *
     * @param ctx   抽取执行上下文
     * @param chunk 目标分块（落库态）
     * @return 上下文块文本或空串
     */
    private String buildHeadingContextBlock(EntityExtractionContext ctx, PersistedChunkVO chunk) {
        ContentBlockVO block = chunk.block();
        if (ObjectUtils.isEmpty(block) || ObjectUtils.isEmpty(block.meta())) {
            return StringUtils.EMPTY;
        }
        Object rawPath = block.meta().get(META_HEADING_PATH);
        String headingPath = ObjectUtils.isEmpty(rawPath)
                ? null
                : StringUtils.trimToNull(String.valueOf(rawPath));
        if (StringUtils.isBlank(headingPath)) {
            return StringUtils.EMPTY;
        }
        return PromptCatalog.render(SECTION_CONTEXT_TEMPLATE, ctx.language(),
                Map.of(VAR_HEADING_PATH, headingPath)) + LINE_SEPARATOR;
    }

    /**
     * 构建补漏（gleaning）续抽用户提示词。
     *
     * <p>续抽模板按多轮对话设计（依赖会话历史中的首轮输入与响应）且不含 {@code {input_text}}
     * 占位符，而 {@link LlmClient} 端口为单轮请求-响应式：故剥离模板尾部的
     * {@code ---Output---} 标记，在其位置依次注入「分块原文（代码围栏包裹）+ 历轮原始响应」，
     * 最后重新置上 {@code ---Output---} 收尾。该合成方式同时保证 llm_cache 键按块、按轮次唯一。</p>
     *
     * @param ctx              抽取执行上下文
     * @param chunk            目标分块（落库态）
     * @param previousResponses 历轮原始响应（按时间升序，至少含首轮）
     * @return 合成完成的单轮续抽用户提示词
     */
    private String buildContinuePrompt(EntityExtractionContext ctx, PersistedChunkVO chunk,
                                       List<String> previousResponses) {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put(VAR_MAX_TOTAL_RECORDS, MAX_TOTAL_RECORDS);
        vars.put(VAR_MAX_ENTITY_RECORDS, MAX_ENTITY_RECORDS);
        vars.put(VAR_LANGUAGE, StringUtils.trim(ctx.language()));
        String template = ctx.jsonMode() ? CONTINUE_JSON_TEMPLATE : CONTINUE_TEXT_TEMPLATE;
        String rendered = PromptCatalog.render(template, ctx.language(), vars);
        String head = StringUtils.removeEnd(StringUtils.strip(rendered), OUTPUT_SECTION_HEADER);
        return head + LINE_SEPARATOR + LINE_SEPARATOR
                + INPUT_TEXT_SECTION_HEADER + LINE_SEPARATOR + CODE_FENCE + LINE_SEPARATOR
                + chunk.text() + LINE_SEPARATOR + CODE_FENCE + LINE_SEPARATOR + LINE_SEPARATOR
                + PREVIOUS_SECTION_HEADER + LINE_SEPARATOR
                + String.join(PREVIOUS_RESPONSE_SEPARATOR, previousResponses) + LINE_SEPARATOR + LINE_SEPARATOR
                + OUTPUT_SECTION_HEADER + LINE_SEPARATOR;
    }

    // ------------------------------------------------------------------ 单块抽取与 LLM 调用

    /**
     * 单 chunk 抽取全流程：首轮抽取 → gleaning 补漏循环 → 类型过滤 → sidecar 注入 → 快照。
     *
     * <p>单块抽取 = 首轮 + 最多 N 轮续抽（N={@code ctx.maxGleaning()}）。续抽循环满足以下
     * <b>任一</b>条件即终止：① 最近一次响应包含完成信号
     * {@code <|COMPLETE|>}；② 本轮响应经解析后向抽取汇聚器新增的记录数（实体+关系）为 0
     * （「零新增即停」，计数口径见 {@link ExtractionHolder} 类级 Javadoc，仅作用于续抽轮，
     * 首轮产出多少不决定是否续抽）；③ 已达 N 轮上限。</p>
     *
     * <p>首轮响应为空白时告警并跳过解析与全部补漏（LightRAG 同口径），但类型过滤与 sidecar
     * 注入仍无条件执行，保证多模态主实体在任何情况下都会产出。</p>
     *
     * @param ctx          抽取执行上下文
     * @param systemPrompt 已构建的系统提示词（同文档内复用）
     * @param customTypes  知识库自定义实体类型允许集（小写 trim）
     * @param chunk        目标分块（落库态）
     * @return 该块的抽取快照（已过滤、已注入 sidecar）
     */
    private ChunkSnapshot extractSingleChunk(EntityExtractionContext ctx, String systemPrompt,
                                             Set<String> customTypes, PersistedChunkVO chunk) {
        ExtractionHolder holder = new ExtractionHolder();
        final Long chunkSourceId = chunk.chunkId();
        String response = requestOnce(ctx, systemPrompt, buildUserPrompt(ctx, chunk), chunkSourceId);
        if (StringUtils.isBlank(response)) {
            log.warn("实体抽取返回空响应，跳过该块解析：documentId=[{}]，sequence=[{}]",
                    ctx.documentId(), chunk.sequence());
        } else {
            holder.rememberRaw(response);
            holder.beginResponse();
            parseResponse(ctx.jsonMode(), response, holder, chunkSourceId);
            boolean completed = StringUtils.contains(response, COMPLETION_DELIMITER);
            for (int round = 0; round < ctx.maxGleaning() && !completed; round++) {
                if (ctx.isCancelled()) {
                    throw new IllegalStateException(CANCELLED_MESSAGE);
                }
                String gleaned = requestOnce(ctx, systemPrompt,
                        buildContinuePrompt(ctx, chunk, holder.rawResponses()), chunkSourceId);
                if (StringUtils.isBlank(gleaned)) {
                    break;
                }
                holder.rememberRaw(gleaned);
                holder.beginResponse();
                parseResponse(ctx.jsonMode(), gleaned, holder, chunkSourceId);
                completed = StringUtils.contains(gleaned, COMPLETION_DELIMITER);
                if (holder.newRecordsSinceResponseStart() == 0) {
                    log.debug("续抽第 {} 轮零新增记录，提前终止补漏：documentId=[{}]，sequence=[{}]",
                            round + 1, ctx.documentId(), chunk.sequence());
                    break;
                }
            }
        }
        applyTypeFilter(holder, customTypes);
        injectSidecarEntities(ctx.mediaPrimaryEntityName(), chunk.block(), chunk.chunkId(), holder);
        return holder.snapshot(chunk.chunkId());
    }

    /**
     * 发起一次抽取 LLM 调用并返回裁剪后的响应文本（null 响应兜底为空串）。
     *
     * <p><b>归因透传</b>：本次调用所属分块的真实主键（{@code chunk.chunkId()}）经
     * {@link LlmChatRequest#attributionChunkId()} 透传到缓存客户端，由后者在命中回放与未命中
     * 回写两条路径登记「分块 → 抽取缓存行」归属；该字段仅用于归属登记，不参与缓存键计算，
     * 同一分块的首轮与各轮续抽（提示词不同、键不同）因此各自形成一条归属。</p>
     *
     * @param ctx          抽取执行上下文
     * @param systemPrompt 系统提示词
     * @param userPrompt   用户提示词
     * @param chunkId      本次调用所属分块的真实主键（归属登记用，可为 null 表示不登记）
     * @return 响应正文（trim 后），响应缺失时为空串
     */
    private String requestOnce(EntityExtractionContext ctx, String systemPrompt, String userPrompt, Long chunkId) {
        LlmChatRequest request = new LlmChatRequest(ctx.kbId(), ctx.extractionModelProfileId(),
                systemPrompt, userPrompt, null, CacheType.EXTRACT, List.of(), chunkId);
        LlmChatResult result = llmClient.chat(request);
        if (ObjectUtils.isEmpty(result)) {
            log.warn("抽取 LLM 返回 null 结果：documentId=[{}]", ctx.documentId());
            return StringUtils.EMPTY;
        }
        if (result.cacheHit() && ObjectUtils.isNotEmpty(ctx.llmCacheHits())) {
            ctx.llmCacheHits().incrementAndGet();
        }
        return StringUtils.trimToEmpty(result.text());
    }

    // ------------------------------------------------------------------ 响应解析

    /**
     * 按输出模式分发响应解析（抽取与缓存重放共用的解析链入口，design D5 同形口径的收口点）。
     *
     * @param jsonMode 是否 JSON 输出模式（文本分隔符 / JSON 双模式分发开关）
     * @param response 原始响应文本
     * @param holder   抽取汇聚器
     * @param sourceId 当前分块的真实主键（贡献记录来源标识）
     */
    private void parseResponse(boolean jsonMode, String response, ExtractionHolder holder, Long sourceId) {
        if (jsonMode) {
            parseJsonResponse(response, holder, sourceId);
        } else {
            parseTextResponse(response, holder, sourceId);
        }
    }

    /**
     * 文本模式容错解析：剥离完成信号后先按上游顺序执行修复层，再逐记录解析。
     * <p><b>修复层次序</b>（对齐上游 LightRAG，spec extraction-parse-integrity / R2）：
     * ① 按 {@code \n} 切行后，含 {@code <|#|>entity<|#|>} / {@code <|#|>relation(ship)<|#|>}
     * 行内标记的粘连行按前缀重切为独立记录、缺前缀片段补齐完整前缀（{@link #rechunkGluedRecords}）；
     * ② 逐记录做分隔符残字字符级修复（{@link #fixDelimiterCorruption}）；
     * ③ 恰 5 字段且 entity 前缀的行改判为关系行（{@link #recoverMisPrefixedRelation}，WARN 留痕）。
     * 修复后的记录进入既有解析分支：前缀识别 entity/relation 行（relation 同时兼容别名前缀
     * {@code relationship}，见 {@link #RELATION_ROW_PREFIX_ALIAS}，两种前缀命中同一解析分支、
     * 校验行为完全一致），名称与端点先经 {@link EntityNameNormalizer} 归一再做空判定/自环判定；
     * 字段数不足、名称/端点无效的行跳过并记调试日志；无法识别的前缀行同样跳过记调试日志；
     * 描述字段取剩余全部字段并以分隔符回连（保留本项目对描述含分隔符的宽容，不退回上游严格式）。
     * 修复发生在「零新增即停」计数上游，续抽停止判定天然以修复后记录为准；
     * JSON 模式不进入本修复层（见 {@link #parseJsonResponse}）。</p>
     *
     * @param response 原始响应文本
     * @param holder   抽取汇聚器
     * @param sourceId 当前分块的真实主键（贡献记录来源标识）
     */
    private void parseTextResponse(String response, ExtractionHolder holder, Long sourceId) {
        String stripped = StringUtils.remove(response, COMPLETION_DELIMITER);
        List<String> records = rechunkGluedRecords(StringUtils.split(stripped, LINE_SEPARATOR));
        for (String record : records) {
            String fixedRecord = fixDelimiterCorruption(record);
            String[] parts = recoverMisPrefixedRelation(
                    StringUtils.splitByWholeSeparatorPreserveAllTokens(fixedRecord, TUPLE_DELIMITER));
            String kind = StringUtils.lowerCase(StringUtils.trim(parts[0]), Locale.ROOT);
            if (StringUtils.equals(kind, ENTITY_ROW_PREFIX)) {
                parseEntityLine(parts, holder);
            } else if (StringUtils.equals(kind, RELATION_ROW_PREFIX)
                    || StringUtils.equals(kind, RELATION_ROW_PREFIX_ALIAS)) {
                parseRelationLine(parts, holder, sourceId);
            } else {
                log.debug("跳过无法识别的抽取记录行：{}", fixedRecord);
            }
        }
    }

    /**
     * 粘连行重切（修复层①）：模型误用元组分隔符充当记录分隔符时，单行内会同时出现
     * {@code <|#|>entity<|#|>} / {@code <|#|>relation(ship)<|#|>} 多记录形态。按上游次序先以
     * entity 标记切分、再以 relation/relationship 标记切分，缺前缀片段分别补
     * {@code entity<|#|>} / {@code relation<|#|>} 完整前缀（本项目解析按前缀精确匹配，
     * 补全完整分隔形态；上游对 entity 片段仅补 {@code entity<|} 残核，由其后「包含式」
     * 前缀判定消化，语义等价）。重切导致记录数变化时输出 WARN 留痕；
     * 不含行内标记的普通行原样透传（垃圾行仍按未知前缀跳过，不因此产出实体）。
     *
     * @param lines 按 {@code \n} 切分后的原始行数组
     * @return 重切后的记录列表（已 trim、已剔除空白行）
     */
    private List<String> rechunkGluedRecords(String[] lines) {
        List<String> fixed = new ArrayList<>(lines.length);
        int sourceCount = 0;
        for (String rawLine : lines) {
            String line = StringUtils.trim(rawLine);
            if (StringUtils.isBlank(line)) {
                continue;
            }
            sourceCount++;
            if (!StringUtils.containsAny(line, ENTITY_RECORD_MARKER,
                    RELATION_RECORD_MARKER, RELATIONSHIP_RECORD_MARKER)) {
                fixed.add(line);
                continue;
            }
            for (String entityFragment : splitByMarkers(line, ENTITY_RECORD_MARKER)) {
                String entityRecord = startWithKnownPrefix(entityFragment)
                        ? entityFragment : ENTITY_ROW_PREFIX + TUPLE_DELIMITER + entityFragment;
                for (String relationFragment : splitByMarkers(entityRecord,
                        RELATIONSHIP_RECORD_MARKER, RELATION_RECORD_MARKER)) {
                    fixed.add(startWithKnownPrefix(relationFragment)
                            ? relationFragment : RELATION_ROW_PREFIX + TUPLE_DELIMITER + relationFragment);
                }
            }
        }
        if (fixed.size() != sourceCount) {
            log.warn("检测到模型以元组分隔符充当记录分隔符的粘连输出，已按前缀重切：原行数=[{}]，重切后记录数=[{}]",
                    sourceCount, fixed.size());
        }
        return fixed;
    }

    /**
     * 按整串标记切分（多标记取最先命中者，标记本身不进入结果片段）。
     *
     * @param text    待切分文本
     * @param markers 标记串（按字面量匹配）
     * @return 片段列表（至少含一个元素：无命中时为原文本）
     */
    private static List<String> splitByMarkers(String text, String... markers) {
        List<String> parts = new ArrayList<>();
        int cursor = 0;
        while (true) {
            int hitIndex = -1;
            String hitMarker = null;
            for (String marker : markers) {
                int found = text.indexOf(marker, cursor);
                if (found >= 0 && (hitIndex < 0 || found < hitIndex)) {
                    hitIndex = found;
                    hitMarker = marker;
                }
            }
            if (hitIndex < 0) {
                parts.add(text.substring(cursor));
                return parts;
            }
            parts.add(text.substring(cursor, hitIndex));
            cursor = hitIndex + hitMarker.length();
        }
    }

    /**
     * 判断片段是否已带记录前缀（entity 或 relation——relationship 别名以 relation 开头，天然覆盖）。
     *
     * @param fragment 重切片段
     * @return 带已知前缀返回 true
     */
    private static boolean startWithKnownPrefix(String fragment) {
        return StringUtils.startsWithAny(fragment, ENTITY_ROW_PREFIX, RELATION_ROW_PREFIX);
    }

    /**
     * 分隔符残字修复（修复层②）：移植上游三条字符级修复——双核粘连（{@code <|##|>} 等）、
     * 转义污染（{@code <|\#|>}）、两侧非紧邻不修的缺核形态（{@code <|>} / {@code <||>}），
     * 命中一律还原为完整元组分隔符。三条正则均为同文件预编译 {@code static final} 常量
     * （以同一 {@link #DELIMITER_CORE} 拼装），三条形态共用一处口径、逐记录复用不重编译。
     * <p>溯源说明：上游对「分隔符核心及其小写」各执行一遍（覆盖核心含大小写形态的输出污染
     * 场景）；本项目分隔符 {@code <|#|>} 的核心 {@code #} 无大小写形态，两遍等价于一遍，
     * 故不保留小写二次执行分支。缺核形态仅在紧邻非空白时修复，自由文本中相近序列不被误改。</p>
     *
     * @param record 重切后的单条记录
     * @return 残字修复后的记录
     */
    private static String fixDelimiterCorruption(String record) {
        String fixed = DELIMITER_DOUBLE_CORE.matcher(record).replaceAll(TUPLE_DELIMITER);
        fixed = DELIMITER_ESCAPE.matcher(fixed).replaceAll(TUPLE_DELIMITER);
        return DELIMITER_MISSING_CORE.matcher(fixed).replaceAll(TUPLE_DELIMITER);
    }

    /**
     * 错误前缀恢复（修复层③）：模型高频坏输出形态——关系行误用 entity 前缀。
     * 恰 5 字段且前缀为 entity（按本项目精确前缀匹配，天然不含 relation）的行改判为关系行、
     * 复用既有关系解析与全套校验（端点归一后判空/判自环、空描述丢弃），改判时输出 WARN；
     * {@code >5} 字段的 entity 行维持既有 joinTail 宽容按实体解析（分界与上游错误恢复等长，
     * 不牺牲描述含分隔符的存活率）。改判失败会落入关系校验丢弃，不产出「类型=关系对端」垃圾实体。
     *
     * @param parts 残字修复后切分的字段数组
     * @return 改判后的字段数组（不满足改判条件时原样返回）
     */
    private String[] recoverMisPrefixedRelation(String[] parts) {
        if (parts.length != RELATION_ROW_PARTS
                || !StringUtils.equalsIgnoreCase(StringUtils.trim(parts[0]), ENTITY_ROW_PREFIX)) {
            return parts;
        }
        log.warn("恢复错误前缀的关系行（entity 前缀恰 {} 字段）：源=[{}]，目标=[{}]",
                RELATION_ROW_PARTS, StringUtils.trim(parts[1]), StringUtils.trim(parts[2]));
        parts[0] = RELATION_ROW_PREFIX;
        return parts;
    }

    /**
     * 解析实体行：{@code entity<|#|>名称<|#|>类型<|#|>描述}。
     * <p>名称先经 {@link EntityNameNormalizer} 归一再判空——归一后为空的无效名
     * （如短纯数字碎片）按协议丢弃并记调试日志，保证进入汇聚器的身份键已定形。</p>
     *
     * @param parts  行切分后的字段数组
     * @param holder 抽取汇聚器
     */
    private void parseEntityLine(String[] parts, ExtractionHolder holder) {
        if (parts.length < ENTITY_ROW_PARTS) {
            log.debug("实体行字段数不足，跳过：{}", String.join(TUPLE_DELIMITER, parts));
            return;
        }
        String name = EntityNameNormalizer.normalize(parts[1]);
        if (StringUtils.isBlank(name)) {
            log.debug("实体行名称归一后为空，跳过：{}", String.join(TUPLE_DELIMITER, parts));
            return;
        }
        String type = StringUtils.trimToNull(parts[2]);
        String description = joinTail(parts, ENTITY_DESCRIPTION_FROM);
        holder.mergeEntity(name, type, description);
    }

    /**
     * 解析关系行：{@code relation<|#|>源<|#|>目标<|#|>关键词<|#|>描述}。
     * <p>两端点先经 {@link EntityNameNormalizer} 归一再判空/判自环（自环判定基于归一后的值，
     * 归一后同名即自环）；端点无效时连带整行丢弃，与类型过滤的连带丢弃语义一致。</p>
     *
     * @param parts    行切分后的字段数组
     * @param holder   抽取汇聚器
     * @param sourceId 当前分块的真实主键（贡献记录来源标识）
     */
    private void parseRelationLine(String[] parts, ExtractionHolder holder, Long sourceId) {
        if (parts.length < RELATION_ROW_PARTS) {
            log.debug("关系行字段数不足，跳过：{}", String.join(TUPLE_DELIMITER, parts));
            return;
        }
        String source = EntityNameNormalizer.normalize(parts[1]);
        String target = EntityNameNormalizer.normalize(parts[2]);
        if (StringUtils.isBlank(source) || StringUtils.isBlank(target) || StringUtils.equals(source, target)) {
            log.debug("关系行端点归一后为空或自环，跳过：{}", String.join(TUPLE_DELIMITER, parts));
            return;
        }
        List<String> keywords = splitKeywords(parts[3]);
        String description = joinTail(parts, RELATION_DESCRIPTION_FROM);
        holder.mergeRelation(source, target, sourceId, DEFAULT_RELATION_WEIGHT, keywords, description);
    }

    /**
     * JSON 模式容错解析：截取首个 '{' 至末个 '}' 之间的对象体（容忍 markdown 围栏与前后缀文字），
     * 解析 {@code entities} / {@code relationships} 数组；解析失败仅告警忽略该响应。
     * <p>{@code name}/{@code source}/{@code target} 与文本模式同走
     * {@link EntityNameNormalizer} 归一，判空/自环口径一致；修复层（粘连重切/残字修复/
     * 错前缀改判）MUST NOT 介入 JSON 模式（spec extraction-parse-integrity / R2 场景）。</p>
     *
     * @param response 原始响应文本
     * @param holder   抽取汇聚器
     * @param sourceId 当前分块的真实主键（贡献记录来源标识）
     */
    private void parseJsonResponse(String response, ExtractionHolder holder, Long sourceId) {
        int start = StringUtils.indexOf(response, JSON_OBJECT_START);
        int end = StringUtils.lastIndexOf(response, JSON_OBJECT_END);
        if (start < 0 || end <= start) {
            log.warn("JSON 模式响应中未定位到有效 JSON 对象，忽略该响应：{}", StringUtils.abbreviate(response, 200));
            return;
        }
        String json = StringUtils.substring(response, start, end + JSON_OBJECT_END.length());
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (JacksonException failed) {
            log.warn("JSON 模式响应解析失败，忽略该响应：{}", StringUtils.abbreviate(json, 200), failed);
            return;
        }
        for (JsonNode entity : root.path(FIELD_ENTITIES)) {
            String name = EntityNameNormalizer.normalize(jsonText(entity, FIELD_NAME));
            if (StringUtils.isBlank(name)) {
                continue;
            }
            holder.mergeEntity(name, StringUtils.trimToNull(jsonText(entity, FIELD_TYPE)),
                    jsonText(entity, FIELD_DESCRIPTION));
        }
        for (JsonNode relationship : root.path(FIELD_RELATIONSHIPS)) {
            String source = EntityNameNormalizer.normalize(jsonText(relationship, FIELD_SOURCE));
            String target = EntityNameNormalizer.normalize(jsonText(relationship, FIELD_TARGET));
            if (StringUtils.isBlank(source) || StringUtils.isBlank(target)
                    || StringUtils.equals(source, target)) {
                continue;
            }
            List<String> keywords = splitKeywords(jsonText(relationship, FIELD_KEYWORDS));
            holder.mergeRelation(source, target, sourceId, DEFAULT_RELATION_WEIGHT, keywords,
                    jsonText(relationship, FIELD_DESCRIPTION));
        }
    }

    /**
     * 读取 JSON 节点的文本字段值（缺失或非文本时返回空串）。
     *
     * @param node  JSON 节点
     * @param field 字段名
     * @return 字段文本值
     */
    private String jsonText(JsonNode node, String field) {
        return node.path(field).asText(StringUtils.EMPTY);
    }

    /**
     * 拼接行内第 from 个及之后的全部字段为描述（以元组分隔符回连，容忍描述内误含分隔符）。
     *
     * @param parts 行切分后的字段数组
     * @param from  描述起始下标
     * @return trim 后的描述文本
     */
    private String joinTail(String[] parts, int from) {
        String[] tail = Arrays.copyOfRange(parts, from, parts.length);
        return StringUtils.trim(StringUtils.join(tail, TUPLE_DELIMITER));
    }

    /**
     * 拆分关键词字段：逗号分隔、逐项 trim、丢弃空白项。
     *
     * @param rawKeywords 关键词原始文本（可为 null）
     * @return 关键词列表（可能为空）
     */
    private List<String> splitKeywords(String rawKeywords) {
        if (StringUtils.isBlank(rawKeywords)) {
            return List.of();
        }
        return Arrays.stream(StringUtils.split(rawKeywords, KEYWORD_SEPARATOR))
                .map(StringUtils::trimToNull)
                .filter(Objects::nonNull)
                .toList();
    }

    // ------------------------------------------------------------------ 类型过滤

    /**
     * 按知识库实体类型清单过滤抽取结果：类型不在允许集的实体丢弃，
     * 引用被丢弃实体的关系连带丢弃（设计方案「受 entity_type_config 过滤」）。
     * 端点从未被抽取到的「悬空关系」不在本过滤范围：关系表零外键、端点为弱引用，
     * 悬空端点属正常态——图合并侧不补建占位节点，检索侧按「名称 + 来源关系相似度」
     * 裸名降级消费。
     *
     * @param holder      抽取汇聚器
     * @param customTypes 知识库自定义实体类型允许集（小写 trim）
     */
    private void applyTypeFilter(ExtractionHolder holder, Set<String> customTypes) {
        Set<String> droppedNames = holder.entities().values().stream()
                .filter(entity -> !typeAllowed(entity.type(), customTypes))
                .map(RawEntity::name)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        holder.entities().keySet().removeAll(droppedNames);
        holder.relations().values().removeIf(relation ->
                droppedNames.contains(relation.source()) || droppedNames.contains(relation.target()));
    }

    /**
     * 判断实体类型是否通过过滤：空白类型或 Other 放行（下游兜底为 Other）、
     * 命中内置 12 类放行（英文代码大小写不敏感、中文名精确）、命中自定义类型放行（大小写不敏感）。
     *
     * @param type        实体类型（可为 null）
     * @param customTypes 自定义类型允许集（小写 trim）
     * @return 允许保留返回 true
     */
    private boolean typeAllowed(String type, Set<String> customTypes) {
        if (StringUtils.isBlank(type) || StringUtils.equalsIgnoreCase(type, OTHER_TYPE)) {
            return true;
        }
        if (BuiltinEntityType.find(type).isPresent()) {
            return true;
        }
        String normalized = StringUtils.lowerCase(StringUtils.trim(type), Locale.ROOT);
        return ObjectUtils.isNotEmpty(normalized) && customTypes.contains(normalized);
    }

    /**
     * 归一知识库自定义实体类型清单为小写 trim 允许集（抽取与缓存重放共用同一过滤口径）。
     *
     * @param entityTypes 知识库可配置实体类型清单（可为 null，视为空清单）
     * @return 自定义类型允许集（可为空集）
     */
    private Set<String> resolveCustomTypes(List<EntityType> entityTypes) {
        List<EntityType> types = ObjectUtils.isEmpty(entityTypes) ? List.of() : entityTypes;
        return types.stream()
                .map(EntityType::entityType)
                .map(type -> StringUtils.lowerCase(StringUtils.trim(type), Locale.ROOT))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    // ------------------------------------------------------------------ 多模态 sidecar 注入

    /**
     * 多模态 sidecar 归属边注入：为含图片/表格/公式块的 chunk 建立块内全部文本实体指向
     * 主实体的 belongs_to 边（source=文本实体、target=主实体、权重 {@value #BELONGS_TO_WEIGHT}、
     * 关键词 belongs_to/part_of/contained_in）；每条 belongs_to 边以本块真实分块主键为来源
     * 只贡献一条记录（单路径注入，不重复计权）。
     * <p>本阶段<b>不构造主实体节点</b>：主实体唯一构造点在摄入管线
     * （{@code IngestionWorker.extractArtifacts}），其名称即上下文携带的
     * {@code ctx.mediaPrimaryEntityName()}——VLM 响应解析处一次定形的
     * 「模型识别名 + 内容类型后缀」，全链路只消费该值，命名口径天然一致。</p>
     *
     * <p><b>端点等值比较口径</b>：下方 {@code StringUtils.equals(entity.name(), modalName)}
     * 与归属边端点构造均为逐字比较、不再二次归一——块内文本实体名已在解析入口经
     * {@link EntityNameNormalizer} 归一，主实体名在 {@code MediaDescriptorService}
     * 定形处（裸名拼接类型后缀之前）已走同一归一管道，两侧可比性由构造保证（预期零逻辑改动）。</p>
     *
     * @param mediaPrimaryEntityName 定形多模态主实体名（抽取期取 {@code ctx.mediaPrimaryEntityName()}，
     *                               重放期取分块输入的 {@link ReplayChunkInput#mediaPrimaryEntityName()}）
     * @param block                  目标分块来源内容块（多模态判定与跳过注入的守卫依据，可为 null）
     * @param chunkId                目标分块真实主键（归属边贡献记录来源标识与留痕定位）
     * @param holder                 抽取汇聚器
     */
    private void injectSidecarEntities(String mediaPrimaryEntityName, ContentBlockVO block,
                                       Long chunkId, ExtractionHolder holder) {
        if (ObjectUtils.isEmpty(block) || !block.isMultimodal()) {
            return;
        }
        String modalName = StringUtils.trimToNull(mediaPrimaryEntityName);
        if (ObjectUtils.isEmpty(modalName)) {
            log.warn("多模态块缺少主实体名，跳过归属边注入：chunkId=[{}]", chunkId);
            return;
        }
        for (RawEntity entity : List.copyOf(holder.entities().values())) {
            if (StringUtils.equals(entity.name(), modalName)) {
                continue;
            }
            holder.mergeRelation(entity.name(), modalName, chunkId, BELONGS_TO_WEIGHT,
                    BELONGS_TO_KEYWORDS, String.format(BELONGS_TO_DESCRIPTION_FORMAT, entity.name(), modalName));
        }
    }

    // ------------------------------------------------------------------ 文档级聚合

    /**
     * 跨 chunk 文档级聚合：同名实体归一（描述取长、类型补空）并并行构建「实体名 → 出现过的
     * chunkId 集合」侧表，出口回填 {@code properties.sourceIds}（真实分块主键升序，
     * 由 {@link PersistedChunkVO} 构造即真值）；同无向端点对关系归一
     * （描述取长、关键词并集、端点方向恒归一为字典序），贡献记录逐来源拼接、不做标量求和，
     * 出口按「一记录一边」展开为多条 {@link RelationEdge}，产出可直接喂给图合并阶段的片段节点与边。
     *
     * @param ctx       抽取执行上下文
     * @param snapshots 各成功块的抽取快照（按块序）
     * @return 文档级聚合结果
     */
    private EntityExtractionResult aggregate(EntityExtractionContext ctx, List<ChunkSnapshot> snapshots) {
        ExtractionHolder global = new ExtractionHolder();
        Map<String, Set<Long>> entitySources = new LinkedHashMap<>();
        for (ChunkSnapshot snapshot : snapshots) {
            for (RawEntity entity : snapshot.entities()) {
                global.mergeEntity(entity.name(), entity.type(), entity.description());
                entitySources.computeIfAbsent(entity.name(), key -> new LinkedHashSet<>())
                        .add(snapshot.chunkId());
            }
            for (RawRelation relation : snapshot.relations()) {
                for (RelationContribution contribution : relation.contributions()) {
                    global.mergeRelation(relation.source(), relation.target(), contribution.sourceId(),
                            contribution.weight(), relation.keywords(), relation.description());
                }
            }
        }
        List<EntityNode> nodes = global.entities().entrySet().stream()
                .map(entry -> toEntityNode(ctx.kbId(), ctx.filePath(), entry.getValue(),
                        toSortedSourceIds(entitySources.get(entry.getKey()))))
                .toList();
        List<RelationEdge> edges = global.relations().values().stream()
                .flatMap(relation -> toRelationEdges(ctx.kbId(), ctx.filePath(), relation).stream())
                .toList();
        return new EntityExtractionResult(nodes, edges);
    }

    /**
     * 由文档级聚合产物构建实体节点（全参属性构造，回填溯源 sourceIds）。
     * <p>类型votes/描述descriptions 语义与 {@link EntityProperties#of} 对齐：
     * votes 固定为该类型出现 1 次（文档级已归一），descriptions 非空描述累积 1 条。</p>
     *
     * @param kbId      所属知识库ID（抽取取上下文 kbId，重放取重放上下文 kbId）
     * @param filePath  来源文件路径（抽取取上下文 filePath；重放传 null——路径列表由重建侧
     *                  按存活账本以 {@code GraphSourceFilePaths} 统一口径重算，不经重放承载）
     * @param entity    文档级归一后的实体
     * @param sourceIds 出现过的真实分块主键升序列表
     * @return 实体节点
     */
    private EntityNode toEntityNode(Long kbId, String filePath, RawEntity entity, List<Long> sourceIds) {
        String entityType = StringUtils.defaultIfBlank(entity.type(), OTHER_TYPE);
        String description = entity.description();
        EntityProperties properties = new EntityProperties(entityType, description, sourceIds,
                filePath, Map.of(entityType, 1),
                StringUtils.isNotBlank(description) ? List.of(description) : List.of());
        return new EntityNode(kbId, entity.name(), properties);
    }

    /**
     * 由文档级聚合产物按「一记录一边」展开构建关系边列表。
     * <p>每个贡献记录独立产出一条边（weight 为该记录全额、sourceIds 只含该记录的真实分块主键），
     * 供合并端逐来源全额累加；无任何贡献记录的关系跳过（不出边）。</p>
     *
     * @param kbId     所属知识库ID（抽取取上下文 kbId，重放取重放上下文 kbId）
     * @param filePath 来源文件路径（抽取取上下文 filePath，重放传 null，口径同 {@link #toEntityNode}）
     * @param relation 文档级归一后的关系（携带逐来源贡献记录列表）
     * @return 关系边列表；无贡献记录时为空列表
     */
    private List<RelationEdge> toRelationEdges(Long kbId, String filePath, RawRelation relation) {
        if (CollectionUtils.isEmpty(relation.contributions())) {
            return List.of();
        }
        return relation.contributions().stream()
                .map(contribution -> toRelationEdge(kbId, filePath, relation, contribution))
                .toList();
    }

    /**
     * 由单条贡献记录构建关系边（全参属性构造，keywords/description/filePath 取 pairKey 级归一值）。
     * <p>抽取聚合出口在此把端点定序为字典序（较小为源、较大为目标），落库方向恒为归一方向、
     * 与模型输出的源/目标先后顺序无关；同一对端点在任意批次产生同一图行身份
     * （汇聚器建记录时已归一，此处为出口端的最终定序点）。</p>
     *
     * @param kbId         所属知识库ID（抽取取上下文 kbId，重放取重放上下文 kbId）
     * @param filePath     来源文件路径（抽取取上下文 filePath，重放传 null，口径同 {@link #toEntityNode}）
     * @param relation     文档级归一后的关系
     * @param contribution 本条贡献记录（来源真实分块主键 + 全额权重）
     * @return 关系边；记录 sourceId 为 null 时 sourceIds 为空列表
     */
    private RelationEdge toRelationEdge(Long kbId, String filePath, RawRelation relation,
                                        RelationContribution contribution) {
        Long sourceId = contribution.sourceId();
        List<Long> sourceIds = ObjectUtils.isEmpty(sourceId) ? List.of() : List.of(sourceId);
        RelationProperties properties = new RelationProperties(contribution.weight(), relation.description(),
                List.copyOf(new TreeSet<>(relation.keywords())), sourceIds, filePath);
        boolean sourceFirst = relation.source().compareTo(relation.target()) <= 0;
        String orderedSource = sourceFirst ? relation.source() : relation.target();
        String orderedTarget = sourceFirst ? relation.target() : relation.source();
        return new RelationEdge(kbId, orderedSource, orderedTarget, properties);
    }

    /**
     * chunkId 集合转溯源 sourceIds（升序）。
     *
     * @param sourceIds 出现过的真实分块主键集合（可为 null）
     * @return 升序的分块主键列表；集合为空返回空列表
     */
    private static List<Long> toSortedSourceIds(Set<Long> sourceIds) {
        if (ObjectUtils.isEmpty(sourceIds)) {
            return List.of();
        }
        return sourceIds.stream().sorted().toList();
    }

    // ------------------------------------------------------------------ few-shot 示例构建

    /**
     * 预渲染文本模式 few-shot 示例（必须含真实的分隔符字面量，模板残留占位符校验据此通过）。
     *
     * @param chinese true 输出中文例句集（张三/星辰科技同构样例），false 输出英文例句集（Alice/ACME 现文）
     * @return 文本模式示例文本
     */
    private static String buildTextExamples(boolean chinese) {
        if (chinese) {
            return String.join(LINE_SEPARATOR,
                    ENTITY_ROW_PREFIX + TUPLE_DELIMITER + "张三" + TUPLE_DELIMITER + "Person"
                            + TUPLE_DELIMITER + "张三是星辰科技的研究员，长期从事知识图谱方向的研究工作。",
                    ENTITY_ROW_PREFIX + TUPLE_DELIMITER + "星辰科技" + TUPLE_DELIMITER + "Organization"
                            + TUPLE_DELIMITER + "星辰科技是一家专注于人工智能技术研发的公司，张三在此任职。",
                    RELATION_ROW_PREFIX + TUPLE_DELIMITER + "张三" + TUPLE_DELIMITER + "星辰科技"
                            + TUPLE_DELIMITER + "雇佣,研究"
                            + TUPLE_DELIMITER + "张三任职于星辰科技，并在该公司开展知识图谱研究。",
                    COMPLETION_DELIMITER);
        }
        return String.join(LINE_SEPARATOR,
                ENTITY_ROW_PREFIX + TUPLE_DELIMITER + "Alice" + TUPLE_DELIMITER + "Person"
                        + TUPLE_DELIMITER + "Alice is a researcher at ACME Corp focused on knowledge graphs.",
                ENTITY_ROW_PREFIX + TUPLE_DELIMITER + "ACME Corp" + TUPLE_DELIMITER + "Organization"
                        + TUPLE_DELIMITER + "ACME Corp is the company where Alice works.",
                RELATION_ROW_PREFIX + TUPLE_DELIMITER + "Alice" + TUPLE_DELIMITER + "ACME Corp"
                        + TUPLE_DELIMITER + "employment, research"
                        + TUPLE_DELIMITER + "Alice works at ACME Corp on knowledge graph research.",
                COMPLETION_DELIMITER);
    }

    /**
     * 预渲染 JSON 模式 few-shot 示例（合法 JSON 对象 + 完成信号行）。
     *
     * @param chinese true 输出中文例句集，false 输出英文例句集
     * @return JSON 模式示例文本
     */
    private static String buildJsonExamples(boolean chinese) {
        if (chinese) {
            return String.join(LINE_SEPARATOR,
                    "{\"entities\": ["
                            + "{\"name\": \"张三\", \"type\": \"Person\","
                            + " \"description\": \"张三是星辰科技的研究员，长期从事知识图谱方向的研究工作。\"},"
                            + " {\"name\": \"星辰科技\", \"type\": \"Organization\","
                            + " \"description\": \"星辰科技是一家专注于人工智能技术研发的公司，张三在此任职。\"}], "
                            + "\"relationships\": ["
                            + "{\"source\": \"张三\", \"target\": \"星辰科技\","
                            + " \"keywords\": \"雇佣,研究\","
                            + " \"description\": \"张三任职于星辰科技，并在该公司开展知识图谱研究。\"}]}",
                    COMPLETION_DELIMITER);
        }
        return String.join(LINE_SEPARATOR,
                "{\"entities\": ["
                        + "{\"name\": \"Alice\", \"type\": \"Person\","
                        + " \"description\": \"Alice is a researcher at ACME Corp focused on knowledge graphs.\"},"
                        + " {\"name\": \"ACME Corp\", \"type\": \"Organization\","
                        + " \"description\": \"ACME Corp is the company where Alice works.\"}], "
                        + "\"relationships\": ["
                        + "{\"source\": \"Alice\", \"target\": \"ACME Corp\","
                        + " \"keywords\": \"employment, research\","
                        + " \"description\": \"Alice works at ACME Corp on knowledge graph research.\"}]}",
                COMPLETION_DELIMITER);
    }

    /**
     * 按库语言选择 few-shot 例句集（双套真实例句）。
     * <p>判定口径：语言全名 {@code Chinese}（trim、大小写不敏感）取中文例句集，
     * 其余 10 语言全名与兼容期历史裸码一律取英文例句集。</p>
     *
     * @param language 知识库语言（全名，兼容历史裸码）
     * @param jsonMode 是否 JSON 输出模式
     * @return 对应模式与语言的例句文本
     */
    private static String resolveExamples(String language, boolean jsonMode) {
        boolean chinese = StringUtils.equalsIgnoreCase(StringUtils.trim(language), LANGUAGE_FULL_NAME_CHINESE);
        if (jsonMode) {
            return chinese ? EXAMPLES_JSON_ZH : EXAMPLES_JSON_EN;
        }
        return chinese ? EXAMPLES_TEXT_ZH : EXAMPLES_TEXT_EN;
    }

    // ------------------------------------------------------------------ 内部数据结构

    /**
     * 原始抽取实体（聚合前中间态）。
     *
     * @param name        实体名称（精确匹配键）
     * @param type        实体类型（可为 null）
     * @param description 实体描述（可为空串）
     */
    private record RawEntity(String name, String type, String description) {
    }

    /**
     * 原始抽取关系（聚合前中间态）。方向不再「沿用首次出现」：汇聚器创建记录时即把端点
     * 归一为字典序（较小为源、较大为目标），恒为归一方向；身份为无向端点对。
     *
     * @param source        源实体名（字典序归一后恒 ≤ target）
     * @param target        目标实体名（字典序归一后恒 ≥ source）
     * @param contributions 逐来源贡献记录列表（每条记录含来源真实分块主键与该来源全额权重；
     *                      同 (端点对, 来源) 重复抽取不追加记录、取权重较大者，
     *                      不同来源逐条拼接、不做标量求和）
     * @param keywords      关键词集合（保持插入序，pairKey 级并集）
     * @param description   关系描述（非空，空白描述在汇聚点丢弃）
     */
    private record RawRelation(String source, String target, List<RelationContribution> contributions,
                               Set<String> keywords, String description) {
    }

    /**
     * 单 chunk 抽取快照（过滤与 sidecar 注入后的最终中间态）。
     *
     * @param entities 实体列表
     * @param relations 关系列表
     * @param chunkId  来源分块的真实主键（文档级聚合时直接回填溯源 sourceIds）
     */
    private record ChunkSnapshot(List<RawEntity> entities, List<RawRelation> relations, Long chunkId) {
    }

    /**
     * 抽取汇聚器：按名称/无向端点对在内存内归一合并原始抽取记录。
     * <p>合并语义：同名实体描述取更长者、类型以非空补空；同无向端点对关系描述取更长者、
     * 关键词并集、端点方向创建记录时即归一为字典序（恒为归一方向，不沿用首次出现的模型输出序）；
     * 关系权重不再做标量累加，而是按来源（chunk 真实主键）逐条收集 {@link RelationContribution}
     * 贡献记录（列表拼接）——同一 (端点对, 来源) 重复抽取不追加记录、<b>取权重较大者</b>
     * （后到的更大权重生效并以 DEBUG 哨兵日志留痕，堵住「身份证据是归属边、权重却是普通值」
     * 的静默降级通道），不同来源逐条累积。同 (端点对, 来源) 记录已在本汇聚器归一为一条
     * （取最大权重），上游 LightRAG「同来源重复计权」的批内不去重形态在本管线不可达
     * （统一口径见 {@link GraphMergeService#aggregateEdges} 与 {@link RelationEdge} 类注释，
     * 三处互相引用）；合并段「逐来源全额累加」公式本身不变。空白描述的关系在汇聚点直接丢弃
     * （关系不做兜底，避免下游 {@link RelationEdge#mergeSkeleton} 抛错）。</p>
     *
     * <p><b>响应粒度新增计数</b>（口径，M-4）：{@link #beginResponse()} 归零、
     * {@link #newRecordsSinceResponseStart()} 读取，供续抽循环判定本轮响应是否带来新信息。
     * 计数口径与去重合并语义一致：实体按名称未命中 {@code entities} 计新增、关系按无向端点对
     * 未命中 {@code relations} 计新增；同名/同端点对命中后的合并（描述取长、关键词并集、
     * 贡献记录追加）不计新增，空白描述被丢弃的记录不计新增。计数仅被单块续抽循环读取；
     * 文档级聚合（{@code aggregate}）复用的全局实例上该计数无人读取，仅有自增副作用、无行为影响。
     * 本类与现状一致为非线程安全设计（单块抽取在单线程内顺序使用）。</p>
     */
    private static final class ExtractionHolder {

        /** 实体表：键为实体名（精确匹配） */
        private final Map<String, RawEntity> entities = new LinkedHashMap<>();

        /** 关系表：键为无向端点对聚合键 */
        private final Map<String, RawRelation> relations = new LinkedHashMap<>();

        /** 历轮原始响应（供续抽提示词组装与缓存键唯一性） */
        private final List<String> rawResponses = new ArrayList<>();

        /**
         * 当前响应新增记录数（响应粒度，口径见类级 Javadoc「响应粒度新增计数」；
         * 仅续抽循环经 {@link #newRecordsSinceResponseStart()} 读取，文档级聚合全局实例无人读取）
         */
        private int currentResponseNewRecords;

        /**
         * 记录一条原始响应（供续抽上下文组装）。
         *
         * @param response 原始响应文本
         */
        private void rememberRaw(String response) {
            rawResponses.add(response);
        }

        /**
         * 开启一次新响应的解析：归零响应粒度新增计数（每轮 {@code parseResponse} 前调用）。
         */
        private void beginResponse() {
            currentResponseNewRecords = 0;
        }

        /**
         * 自 {@link #beginResponse()} 以来向本汇聚器新增的记录数（实体+关系，「零新增即停」判据）。
         *
         * @return 新增记录数；0 表示本次响应未带来任何新记录
         */
        private int newRecordsSinceResponseStart() {
            return currentResponseNewRecords;
        }

        /**
         * 历轮原始响应列表（外部只读用途）。
         *
         * @return 原始响应列表
         */
        private List<String> rawResponses() {
            return List.copyOf(rawResponses);
        }

        /** 实体表视图（支持 removeIf 过滤与 putIfAbsent 注入）。 */
        private Map<String, RawEntity> entities() {
            return entities;
        }

        /** 关系表视图（支持 removeIf 过滤）。 */
        private Map<String, RawRelation> relations() {
            return relations;
        }

        /**
         * 合并实体：描述取更长者、类型以非空补空。
         * <p>名称未命中实体表时计一条响应粒度新增（同名合并为更新，不计新增）。</p>
         *
         * @param name        实体名
         * @param type        类型（可为 null）
         * @param description 描述（可为空）
         */
        private void mergeEntity(String name, String type, String description) {
            RawEntity existing = entities.get(name);
            if (ObjectUtils.isEmpty(existing)) {
                entities.put(name, new RawEntity(name, type, StringUtils.defaultString(description)));
                currentResponseNewRecords++;
                return;
            }
            String mergedType = StringUtils.defaultIfBlank(type, existing.type());
            String mergedDescription = StringUtils.length(description) > StringUtils.length(existing.description())
                    ? description : existing.description();
            entities.put(name, new RawEntity(name, mergedType, mergedDescription));
        }

        /**
         * 合并关系：无向端点对归一，描述取更长者、关键词并集、贡献记录按来源逐条累积；
         * 记录方向在创建时即归一为端点名字典序（较小为源、较大为目标），恒为归一方向。
         * <p>同一 (端点对, 来源) 重复抽取不追加记录、<b>取权重较大者</b>——后到的更大权重
         * 生效并输出 DEBUG 哨兵日志（端点对、原权重、新权重）；不同来源的贡献记录列表拼接、
         * 不做标量求和，全额累加由图合并端按「逐来源全额」公式完成。</p>
         * <p>无向端点对未命中关系表时计一条响应粒度新增（同端点对命中后的关键词并集/
         * 描述取长/贡献记录取最大或追加均为更新，不计新增；空白描述被丢弃的记录不计新增）。</p>
         *
         * @param source      源实体名
         * @param target      目标实体名
         * @param sourceId    贡献来源标识（当前分块的真实分块主键，可为 null）
         * @param weight      本来源的全额贡献权重（普通 1.0 / belongs_to {@value #BELONGS_TO_WEIGHT}）
         * @param keywords    关键词
         * @param description 描述（空白则整条丢弃）
         */
        private void mergeRelation(String source, String target, Long sourceId, double weight,
                                   Collection<String> keywords, String description) {
            if (StringUtils.isBlank(description)) {
                log.debug("关系描述为空，丢弃该关系记录：{} ~ {}", source, target);
                return;
            }
            String key = pairKey(source, target);
            RawRelation existing = relations.get(key);
            if (ObjectUtils.isEmpty(existing)) {
                Set<String> initialKeywords = new LinkedHashSet<>();
                if (ObjectUtils.isNotEmpty(keywords)) {
                    initialKeywords.addAll(keywords);
                }
                boolean sourceFirst = source.compareTo(target) <= 0;
                relations.put(key, new RawRelation(sourceFirst ? source : target,
                        sourceFirst ? target : source,
                        List.of(new RelationContribution(sourceId, weight)), initialKeywords,
                        StringUtils.trim(description)));
                currentResponseNewRecords++;
                return;
            }
            List<RelationContribution> mergedContributions;
            RelationContribution covered = findContributionBySource(existing.contributions(), sourceId);
            if (ObjectUtils.isNotEmpty(covered)) {
                // 同 (端点对, 来源) 重复抽取：不追加记录，取权重较大者；关键词/描述仍按既有策略归并
                mergedContributions = replaceContributionWeight(existing.source(), existing.target(),
                        existing.contributions(), sourceId, covered, weight);
            } else {
                mergedContributions = new ArrayList<>(existing.contributions());
                mergedContributions.add(new RelationContribution(sourceId, weight));
            }
            Set<String> mergedKeywords = new LinkedHashSet<>(existing.keywords());
            if (ObjectUtils.isNotEmpty(keywords)) {
                mergedKeywords.addAll(keywords);
            }
            String mergedDescription = StringUtils.length(description) > StringUtils.length(existing.description())
                    ? description : existing.description();
            relations.put(key, new RawRelation(existing.source(), existing.target(),
                    List.copyOf(mergedContributions), mergedKeywords, mergedDescription));
        }

        /**
         * 查找贡献记录列表中指定来源的既有记录（sourceId 为 null 时按 null 相等匹配）。
         *
         * @param contributions 既有贡献记录列表
         * @param sourceId      待查来源标识（可为 null）
         * @return 命中的贡献记录；无该来源记录返回 {@code null}
         */
        private static RelationContribution findContributionBySource(List<RelationContribution> contributions,
                                                                      Long sourceId) {
            if (CollectionUtils.isEmpty(contributions)) {
                return null;
            }
            return contributions.stream()
                    .filter(contribution -> ObjectUtils.equals(contribution.sourceId(), sourceId))
                    .findFirst().orElse(null);
        }

        /**
         * 同来源重复记录的权重归并：新权重更大时替换该来源记录为全额新权重（DEBUG 哨兵留痕
         * 端点对、原权重、新权重），否则原样保留既有列表。
         *
         * @param pairSource    归一端点对之源（哨兵日志定位）
         * @param pairTarget    归一端点对之目标（哨兵日志定位）
         * @param contributions 既有贡献记录列表
         * @param sourceId      重复出现的来源标识（可为 null）
         * @param covered       该来源的既有记录（非空）
         * @param weight        本次记录的全额权重
         * @return 权重归并后的贡献记录列表
         */
        private static List<RelationContribution> replaceContributionWeight(
                String pairSource, String pairTarget, List<RelationContribution> contributions,
                Long sourceId, RelationContribution covered, double weight) {
            if (weight <= covered.weight()) {
                return contributions;
            }
            log.debug("同来源重复关系记录权重提升：端点对=[{}~{}]，来源=[{}]，原权重=[{}]，新权重=[{}]",
                    pairSource, pairTarget, sourceId, covered.weight(), weight);
            return contributions.stream()
                    .map(contribution -> ObjectUtils.equals(contribution.sourceId(), sourceId)
                            ? new RelationContribution(sourceId, weight) : contribution)
                    .toList();
        }

        /**
         * 生成无向端点对聚合键（字典序拼接，(a,b) 与 (b,a) 同键）。
         *
         * @param first  端点一
         * @param second 端点二
         * @return 聚合键
         */
        private static String pairKey(String first, String second) {
            if (first.compareTo(second) <= 0) {
                return first + PAIR_KEY_SEPARATOR + second;
            }
            return second + PAIR_KEY_SEPARATOR + first;
        }

        /**
         * 产出当前状态的不可变快照。
         *
         * @param chunkId 来源分块的真实主键（供文档级聚合直接回填溯源 sourceIds）
         * @return 抽取快照
         */
        private ChunkSnapshot snapshot(Long chunkId) {
            return new ChunkSnapshot(List.copyOf(entities.values()), List.copyOf(relations.values()), chunkId);
        }
    }
}
