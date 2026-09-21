package com.linkroa.deepdataagent.rag.domain.service;

import com.linkroa.deepdataagent.knowledgebase.api.KnowledgeBaseApi;
import com.linkroa.deepdataagent.knowledgebase.domain.model.Chunk;
import com.linkroa.deepdataagent.rag.domain.model.ContextBudget;
import com.linkroa.deepdataagent.rag.domain.model.EntityHit;
import com.linkroa.deepdataagent.rag.domain.model.KgContext;
import com.linkroa.deepdataagent.rag.domain.model.KgSearchResult;
import com.linkroa.deepdataagent.rag.domain.model.RankedChunk;
import com.linkroa.deepdataagent.rag.domain.model.RelationHit;
import com.linkroa.deepdataagent.rag.infrastructure.prompts.catalog.PromptCatalog;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * 上下文构建服务默认实现（Stage 4）。
 *
 * <p>按动态 token 预算将全局有序 chunk 装入 MIX/NAIVE 上下文模板：</p>
 * <ol>
 *     <li>预算公式：
 *     {@code available = maxTotalTokens − 回答模板空算 − 框架空算 − token(query)
 *     − BUFFER_TOKENS(200)}；回答模板空算 = 当前形态的回答系统模板（
 *     {@link AnswerProfile#responseTemplateName()}）以「Stage 5 同款默认变量 +
 *     内容槽空串」渲染后的真实 token 数（即先为最终送入 LLM 的 system prompt 骨架预留份额）；
 *     框架空算 = 选中上下文模板以「实际 entities_str/relations_str + 空 chunks + 空引用」
 *     渲染后的真实 token 数（即先为图谱实体/关系与模板骨架预留份额），下限钳制为 0；</li>
 *     <li>{@code processChunksUnified}：按输入顺序逐 chunk 累加正文 token（复用
 *     {@link TokenCounter} 真实编码），超预算即停、仅保留之前已装入的 chunk；</li>
 *     <li>{@code generateReferenceList}：保留 chunk 的来源文件按首次出现序去重分配
 *     {@code reference_id}，引用列表格式 {@code [n] 来源文件名}，
 *     最多 {@link RetrievalConstants#REFERENCE_MAX_COUNT} 条；</li>
 *     <li>{@code renderChunksContext}：每条保留 chunk 渲染为含 {@code reference_id}
 *     与 {@code content} 的单行 JSON（ObjectMapper 序列化，等价
 *     {@code json.dumps(ensure_ascii=False)}）。</li>
 * </ol>
 *
 * <p>图谱段渲染（P1）：{@code entities_str} / {@code relations_str} 不再是裸名称逐行 JSON，
 * 而是<b>每行一条精简字段集</b>的实体 / 关系记录（实体 {@code entity/type/description/
 * created_at/file_path}，关系 {@code entity1/entity2/description/created_at/file_path}），
 * 字段序由 {@link LinkedHashMap} 固定、时间以 ISO-8601 呈现；文本分量缺失以空串落位
 * （键恒在，形态稳定）。{@code file_path} 的值来自来源文件路径列表（{@code filePaths}），
 * 渲染时以 {@code |} 拼接为<b>单个字符串</b>（spec graph-source-file-paths R4），
 * 上下文记录结构与列表化之前完全一致。</p>
 *
 * <p>图谱段截断（P3，spec「实体列表 token 截断」/「关系列表 token 截断」场景）：
 * 渲染前实体与关系列表分别按 {@link ContextBudget#maxEntityTokens()} /
 * {@link ContextBudget#maxRelationTokens()} 依列表顺序<b>逐条累加截断</b>——单条计重 =
 * 仅含知识字段的 dict 副本（实体 {@code entity/type/description}，关系
 * {@code entity1/entity2/description}；<b>剔除 {@code created_at} 与 {@code file_path}
 * 计重</b>）经 {@link TokenCounter} 真实 encode 的 token 数，累计将超上限即停止纳入、
 * 保留此前全部前缀（对齐参考 {@code truncate_list_by_token_size} 保前缀语义，
 * 单条自身即超上限时该条及其后全部舍弃、不留半条）。实体与关系两侧份额<b>完全独立、
 * 互不侵占</b>；截断只决定记录去留，展示仍保留全字段 dict，且不回缩 chunk 候选、
 * 不影响 chunk 预算计算以外的任何上游结果。</p>
 *
 * <p>模板选择：{@code ContextBudget} 实际定义仅承载
 * {@code maxTotalTokens}/{@code query} 与实体/关系截断份额、无 mode 字段，故依 {@code RecallService} 契约推导——
 * NAIVE 模式与图谱 MISSING 时 {@link KgSearchResult} 各字段均为空列表；实体/关系任一非空即
 * MIX。推导出的布尔 {@code kgMode} 不再在本类自行映射模板名，统一交
 * {@link AnswerProfile#forKgMode(boolean)} 单点供给（上下文骨架与回答模板同形态，
 * 杜绝与 Stage 5 各判各的导致错配）。</p>
 *
 * <p>正文回取：chunk 正文经 {@link KnowledgeBaseApi#findChunksByKbIdAndChunkIds}
 * 批量回取并以 {@code kbId} 等值过滤保证单库隔离。{@code ContextBuilder} 接口签名（已冻结）
 * 与 {@code ContextBudget} 均不承载 {@code kbId}，故本类提供带 {@code kbId} 的公开重载作为
 * 完整实现路径；接口方法 {@link #build(List, KgSearchResult, ContextBudget)} 以
 * {@code kbId = null} 委托，此时跳过正文回取、上下文退化为仅图谱部分，不抛异常。</p>
 *
 * @author DeepDataAgent
 */
@Service
public class DefaultContextBuilder implements ContextBuilder {

    /** 日志器 */
    private static final Logger log = LoggerFactory.getLogger(DefaultContextBuilder.class);

    /** 占位符变量名：实体逐行 JSON（{entities_str}） */
    private static final String VAR_ENTITIES_STR = "entities_str";

    /** 占位符变量名：关系逐行 JSON（{relations_str}） */
    private static final String VAR_RELATIONS_STR = "relations_str";

    /** 占位符变量名：文档切片逐行 JSON（{text_chunks_str}） */
    private static final String VAR_TEXT_CHUNKS_STR = "text_chunks_str";

    /** 占位符变量名：引用列表文本（{reference_list_str}） */
    private static final String VAR_REFERENCE_LIST_STR = "reference_list_str";

    /** chunk 记录字段名：引用编号 */
    private static final String FIELD_REFERENCE_ID = "reference_id";

    /** chunk 记录字段名：正文内容 */
    private static final String FIELD_CONTENT = "content";

    /** 实体记录字段名：实体名（{@code entity}） */
    private static final String FIELD_ENTITY = "entity";

    /** 实体记录字段名：实体类型（{@code type}） */
    private static final String FIELD_TYPE = "type";

    /** 关系记录字段名：源实体（{@code entity1}） */
    private static final String FIELD_ENTITY_ONE = "entity1";

    /** 关系记录字段名：目标实体（{@code entity2}） */
    private static final String FIELD_ENTITY_TWO = "entity2";

    /** 图谱记录字段名：描述（{@code description}，实体/关系同名） */
    private static final String FIELD_DESCRIPTION = "description";

    /** 图谱记录字段名：创建时间（{@code created_at}，ISO-8601 文本，由注入的 ObjectMapper 序列化） */
    private static final String FIELD_CREATED_AT = "created_at";

    /** 图谱记录字段名：来源文件路径（{@code file_path}，实体/关系同名；列表渲染时拼接为单个字符串） */
    private static final String FIELD_FILE_PATH = "file_path";

    /** 来源文件路径列表拼接为单字符串的分隔符（对齐 LightRAG 上游多来源以 {@code |} 连接的展示形态） */
    private static final String FILE_PATH_JOINER = "|";

    /** 引用列表条目格式：[n] 来源文件名 */
    private static final String REFERENCE_ENTRY_FORMAT = "[%d] %s";

    /** 逐行 JSON 记录之间的连接符 */
    private static final String LINE_JOINER = "\n";

    /** 来源文件名缺失时的引用占位名 */
    private static final String UNKNOWN_SOURCE_FILE = "unknown";

    /** Token 计数器（真实 encode，预算累加、框架空算与回答模板空算的唯一口径） */
    private final TokenCounter tokenCounter;

    /** 知识库跨 BC 只读契约（chunk 正文批量回取，kbId 单库隔离） */
    private final KnowledgeBaseApi knowledgeBaseApi;

    /** JSON 序列化器（实体/关系/chunk 记录逐行转义，等价 json.dumps ensure_ascii=False） */
    private final ObjectMapper objectMapper;

    /**
     * 构造上下文构建器。
     *
     * @param tokenCounter   Token 计数器
     * @param knowledgeBaseApi 知识库跨 BC 只读契约
     * @param objectMapper   JSON 序列化器
     */
    public DefaultContextBuilder(TokenCounter tokenCounter,
                                 KnowledgeBaseApi knowledgeBaseApi, ObjectMapper objectMapper) {
        this.tokenCounter = tokenCounter;
        this.knowledgeBaseApi = knowledgeBaseApi;
        this.objectMapper = objectMapper;
    }

    /**
     * {@inheritDoc}
     * <p>接口签名不承载 {@code kbId}，以 {@code kbId = null} 委托
     * {@link #build(Long, List, KgSearchResult, ContextBudget)}：
     * 跳过 chunk 正文批量回取，上下文退化为仅图谱部分（实体/关系），不抛异常。</p>
     */
    @Override
    public KgContext build(List<RankedChunk> chunks, KgSearchResult kg, ContextBudget budget) {
        return build(null, chunks, kg, budget);
    }

    /**
     * 带知识库隔离的完整构建入口（推荐编排方使用）。
     *
     * @param kbId   chunk 正文回取的知识库主键；为空时跳过回取、上下文退化为仅图谱部分
     * @param chunks 全局有序 chunk 列表（预算内截断由本方法执行），可为空
     * @param kg     结构化实体/关系/chunk 结果，可为空（按空图谱处理，走 NAIVE 模板）
     * @param budget 上下文预算入参（maxTotalTokens、查询文本与实体/关系截断份额），不可为空
     * @return 上下文产物（contextData / referenceList / kgResult / retainedChunkIds，
     *         其中 retainedChunkIds 为预算内实际装入上下文的全通道 chunkId、按上下文序）
     * @throws IllegalArgumentException 预算入参为空（无法确定上限，快速失败）
     */
    @Override
    public KgContext build(Long kbId, List<RankedChunk> chunks, KgSearchResult kg, ContextBudget budget) {
        if (ObjectUtils.isEmpty(budget)) {
            throw new IllegalArgumentException("上下文构建预算入参不能为空");
        }
        KgSearchResult safeKg = ObjectUtils.isEmpty(kg)
                ? new KgSearchResult(List.of(), List.of(), List.of()) : kg;
        List<RankedChunk> safeChunks = CollectionUtils.isEmpty(chunks) ? List.of() : chunks;

        boolean kgMode = CollectionUtils.isNotEmpty(safeKg.entities())
                || CollectionUtils.isNotEmpty(safeKg.relations());
        AnswerProfile profile = AnswerProfile.forKgMode(kgMode);
        String templateName = profile.contextTemplateName();
        // P3 截断（渲染前）：实体/关系各按独立 token 份额逐条累加保前缀，计重剔除 created_at/file_path
        List<EntityHit> budgetedEntities = kgMode
                ? truncateEntities(safeKg.entities(), budget.maxEntityTokens()) : List.of();
        List<RelationHit> budgetedRelations = kgMode
                ? truncateRelations(safeKg.relations(), budget.maxRelationTokens()) : List.of();
        String entitiesStr = renderEntities(budgetedEntities);
        String relationsStr = renderRelations(budgetedRelations);

        int availableTokens = resolveAvailableTokens(profile, entitiesStr, relationsStr, budget);
        Map<Long, Chunk> chunkById = fetchChunkById(kbId, safeChunks);
        List<RetainedChunk> retained = processChunksUnified(safeChunks, chunkById, availableTokens);
        Map<String, Integer> referenceIdBySource = assignReferenceIds(retained);
        List<String> referenceList = buildReferenceList(referenceIdBySource);
        String textChunksStr = renderChunksContext(retained, referenceIdBySource);

        Map<String, String> vars = new LinkedHashMap<>();
        vars.put(VAR_ENTITIES_STR, entitiesStr);
        vars.put(VAR_RELATIONS_STR, relationsStr);
        vars.put(VAR_TEXT_CHUNKS_STR, textChunksStr);
        vars.put(VAR_REFERENCE_LIST_STR, String.join(LINE_JOINER, referenceList));
        String contextData = PromptCatalog.render(templateName, RetrievalConstants.DEFAULT_LANGUAGE, vars);
        // 按装入序导出实际装入上下文的 chunkId 集，
        // 供通路 B（作答侧原图直读）作为全通道候选域；退化路径（kbId=null 不回取正文）自然得空表
        List<Long> retainedChunkIds = retained.stream().map(RetainedChunk::chunkId).toList();
        return new KgContext(contextData, referenceList, safeKg, retainedChunkIds);
    }

    /**
     * 计算 chunk 可用预算：
     * {@code available = maxTotalTokens − 回答模板空算 − 框架空算 − token(query)
     * − BUFFER_TOKENS}，下限钳制为 0。
     * <p>回答模板空算必须与 Stage 5 实际送入 LLM 的 system prompt 同源
     * （同模板、同默认变量、内容槽空串），否则该部分份额始终无人扣减，
     * 送入总量必然超 {@code maxTotalTokens}。</p>
     * <p><b>渲染异常口径</b>：与本方法既有的框架空算渲染一致，不额外吞异常——
     * 两个模板同属编译期静态目录，渲染失败即目录资产故障，Stage 5 渲染同必失败，
     * 继续构建只会把确定的失败延后；故异常一律上抛，由编排层 Stage 4 既有
     * {@code try/catch} 统一降级（置 {@code degraded} 并以空上下文继续）。</p>
     *
     * @param profile      当前模板形态（上下文骨架与回答模板同形态供给）
     * @param entitiesStr  实体逐行 JSON（计入框架空算份额）
     * @param relationsStr 关系逐行 JSON（计入框架空算份额）
     * @param budget       预算入参
     * @return chunk 累加可用 token 上限（非负）
     */
    private int resolveAvailableTokens(AnswerProfile profile, String entitiesStr, String relationsStr,
                                       ContextBudget budget) {
        Map<String, String> frameworkVars = new LinkedHashMap<>();
        frameworkVars.put(VAR_ENTITIES_STR, entitiesStr);
        frameworkVars.put(VAR_RELATIONS_STR, relationsStr);
        frameworkVars.put(VAR_TEXT_CHUNKS_STR, StringUtils.EMPTY);
        frameworkVars.put(VAR_REFERENCE_LIST_STR, StringUtils.EMPTY);
        String framework = PromptCatalog.render(profile.contextTemplateName(),
                RetrievalConstants.DEFAULT_LANGUAGE, frameworkVars);
        int frameworkTokens = tokenCounter.count(framework);
        int answerTokens = tokenCounter.count(PromptCatalog.render(profile.responseTemplateName(),
                RetrievalConstants.DEFAULT_LANGUAGE, profile.responseVars(StringUtils.EMPTY)));
        int queryTokens = StringUtils.isEmpty(budget.query()) ? 0 : tokenCounter.count(budget.query());
        return Math.max(0, budget.maxTotalTokens() - answerTokens - frameworkTokens - queryTokens
                - RetrievalConstants.BUFFER_TOKENS);
    }

    /**
     * 批量回取 chunk 正文并按 id 索引（kbId 单库隔离）。
     *
     * @param kbId   知识库主键；为空时不回取（接口签名未承载 kbId 的退化路径）
     * @param chunks 全局有序 chunk 列表
     * @return chunkId → 切片领域模型的索引；不回取或无命中时为空 Map
     */
    private Map<Long, Chunk> fetchChunkById(Long kbId, List<RankedChunk> chunks) {
        if (ObjectUtils.isEmpty(kbId)) {
            if (CollectionUtils.isNotEmpty(chunks)) {
                log.warn("kbId 缺失，跳过 chunk 正文批量回取，上下文退化为仅图谱部分");
            }
            return Map.of();
        }
        List<Long> chunkIds = chunks.stream()
                .filter(ObjectUtils::isNotEmpty)
                .map(RankedChunk::chunkId)
                .filter(ObjectUtils::isNotEmpty)
                .toList();
        if (CollectionUtils.isEmpty(chunkIds)) {
            return Map.of();
        }
        return knowledgeBaseApi.findChunksByKbIdAndChunkIds(kbId, chunkIds).stream()
                .filter(chunk -> ObjectUtils.isNotEmpty(chunk) && ObjectUtils.isNotEmpty(chunk.id()))
                .collect(Collectors.toMap(Chunk::id, chunk -> chunk, (first, second) -> first));
    }

    /**
     * 预算内逐条装入 chunk（processChunksUnified）：按输入顺序累加正文 token，
     * 超预算即停（仅保留之前已装入者）；正文回取缺失的记录 WARN 并跳过。
     *
     * @param chunks         全局有序 chunk 列表
     * @param chunkById      正文回取索引
     * @param availableTokens 可用预算上限
     * @return 预算内保留的 chunk 渲染中间态列表（保持输入序）
     */
    private List<RetainedChunk> processChunksUnified(List<RankedChunk> chunks, Map<Long, Chunk> chunkById,
                                                     int availableTokens) {
        List<RetainedChunk> retained = new ArrayList<>();
        int usedTokens = 0;
        for (RankedChunk ranked : chunks) {
            if (ObjectUtils.isEmpty(ranked)) {
                continue;
            }
            Chunk chunk = ObjectUtils.isEmpty(ranked.chunkId()) ? null : chunkById.get(ranked.chunkId());
            if (ObjectUtils.isEmpty(chunk)) {
                log.warn("chunk 正文回取缺失，已跳过该条: chunkId={}", ranked.chunkId());
                continue;
            }
            int chunkTokens = tokenCounter.count(chunk.chunkContent());
            if (usedTokens + chunkTokens > availableTokens) {
                // 超预算即停：截断语义为「保留此前已装入的全部」，不做跳过后继续
                break;
            }
            usedTokens += chunkTokens;
            String sourceFile = StringUtils.defaultIfBlank(chunk.sourceFileName(), ranked.sourceFile());
            // chunkId 为装入条目的固有身份：与 content/sourceFile 同步携带至出口
            retained.add(new RetainedChunk(chunk.id(), chunk.chunkContent(),
                    StringUtils.defaultIfBlank(sourceFile, UNKNOWN_SOURCE_FILE)));
        }
        return retained;
    }

    /**
     * 为保留 chunk 的来源文件按首次出现序去重分配 reference_id（从 1 起）。
     *
     * @param retained 预算内保留的 chunk 列表
     * @return 来源文件名 → 引用编号的有序映射
     */
    private Map<String, Integer> assignReferenceIds(List<RetainedChunk> retained) {
        Map<String, Integer> idBySource = new LinkedHashMap<>();
        for (RetainedChunk chunk : retained) {
            idBySource.putIfAbsent(chunk.sourceFile(), idBySource.size() + 1);
        }
        return idBySource;
    }

    /**
     * 生成引用列表：格式 {@code [n] 来源文件名}，
     * 最多 {@link RetrievalConstants#REFERENCE_MAX_COUNT} 条。
     *
     * @param idBySource 来源文件名 → 引用编号映射
     * @return 引用列表（有序，可能为空）
     */
    private List<String> buildReferenceList(Map<String, Integer> idBySource) {
        List<String> referenceList = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : idBySource.entrySet()) {
            if (referenceList.size() >= RetrievalConstants.REFERENCE_MAX_COUNT) {
                break;
            }
            referenceList.add(String.format(REFERENCE_ENTRY_FORMAT, entry.getValue(), entry.getKey()));
        }
        return referenceList;
    }

    /**
     * 渲染文档切片上下文（renderChunksContext）：每条保留 chunk 输出
     * {@code {"reference_id": n, "content": "..."}} 单行 JSON，逐行以换行符连接；
     * 序列化失败的行记录 WARN 并跳过。
     *
     * @param retained     预算内保留的 chunk 列表
     * @param idBySource   来源文件名 → 引用编号映射
     * @return 逐行 JSON 拼接的切片文本（无保留项时为空串）
     */
    private String renderChunksContext(List<RetainedChunk> retained, Map<String, Integer> idBySource) {
        List<String> lines = new ArrayList<>();
        for (RetainedChunk chunk : retained) {
            Map<String, String> record = new LinkedHashMap<>();
            record.put(FIELD_REFERENCE_ID, String.valueOf(idBySource.get(chunk.sourceFile())));
            record.put(FIELD_CONTENT, chunk.content());
            String line = tryWriteValueAsString(record);
            if (ObjectUtils.isNotEmpty(line)) {
                lines.add(line);
            }
        }
        return String.join(LINE_JOINER, lines);
    }

    /**
     * 渲染实体上下文（entities_str）：每条命中输出一行精简字段集 JSON
     * （{@code entity / type / description / created_at / file_path}，字段序固定），
     * 逐行以换行符连接。
     * <p>空白名称的记录跳过（无可展示内容）；文本分量缺失以空串落位（键恒在，形态稳定）；
     * {@code created_at} 为 {@link OffsetDateTime}，由注入的 {@link ObjectMapper} 序列化为
     * ISO-8601 文本（工程无自定义全局时间序列化配置，Jackson 3 的 java.time 支持内置且默认
     * 非时间戳输出；渲染格式由单测锁定，见类注释）。</p>
     * <p>入参列表已由调用方在渲染前按 {@code maxEntityTokens} 份额完成逐条累加截断（P3，
     * 见 {@link #truncateEntities(List, int)}）；本方法只负责剩余记录的全字段渲染。</p>
     *
     * @param entities 实体候选集，可为空
     * @return 逐行 JSON 拼接的实体文本（无有效记录时为空串）
     */
    private String renderEntities(List<EntityHit> entities) {
        if (CollectionUtils.isEmpty(entities)) {
            return StringUtils.EMPTY;
        }
        List<String> lines = new ArrayList<>();
        for (EntityHit hit : entities) {
            if (ObjectUtils.isEmpty(hit) || StringUtils.isBlank(hit.name())) {
                continue;
            }
            Map<String, Object> record = new LinkedHashMap<>();
            record.put(FIELD_ENTITY, StringUtils.defaultString(hit.name()));
            record.put(FIELD_TYPE, StringUtils.defaultString(hit.entityType()));
            record.put(FIELD_DESCRIPTION, StringUtils.defaultString(hit.description()));
            record.put(FIELD_CREATED_AT, createdAtOrEmpty(hit.createdAt()));
            record.put(FIELD_FILE_PATH, renderFilePaths(hit.filePaths()));
            appendLine(lines, record);
        }
        return String.join(LINE_JOINER, lines);
    }

    /**
     * 渲染关系上下文（relations_str）：每条关系视图记录输出一行精简字段集 JSON
     * （{@code entity1 / entity2 / description / created_at / file_path}，字段序固定），
     * 逐行以换行符连接；取值与跳过口径同 {@link #renderEntities(List)}。
     * <p>关系视图含「关联边转换记录」，与关系路命中同形渲染，消费方无需区分来源。</p>
     *
     * @param relations 关系视图列表，可为空
     * @return 逐行 JSON 拼接的关系文本（无有效记录时为空串）
     */
    private String renderRelations(List<RelationHit> relations) {
        if (CollectionUtils.isEmpty(relations)) {
            return StringUtils.EMPTY;
        }
        List<String> lines = new ArrayList<>();
        for (RelationHit hit : relations) {
            if (ObjectUtils.isEmpty(hit) || hit.normalizedPair().isEmpty()) {
                continue;
            }
            Map<String, Object> record = new LinkedHashMap<>();
            record.put(FIELD_ENTITY_ONE, StringUtils.defaultString(hit.sourceName()));
            record.put(FIELD_ENTITY_TWO, StringUtils.defaultString(hit.targetName()));
            record.put(FIELD_DESCRIPTION, StringUtils.defaultString(hit.description()));
            record.put(FIELD_CREATED_AT, createdAtOrEmpty(hit.createdAt()));
            record.put(FIELD_FILE_PATH, renderFilePaths(hit.filePaths()));
            appendLine(lines, record);
        }
        return String.join(LINE_JOINER, lines);
    }

    /**
     * 来源文件路径列表 → 上下文单字符串（spec graph-source-file-paths R4）：
     * 列表元素以 {@link #FILE_PATH_JOINER} 拼接为单个值，MUST NOT 输出数组形态；
     * 空列表以空串落位（键恒在，形态稳定）。该字段本就<b>不参与计重</b>
     * （见 {@link #entityKnowledgeRecord(EntityHit)} / {@link #relationKnowledgeRecord(RelationHit)}），
     * 拼接仅影响展示串，上下文记录字段序与计重口径与列表化之前完全一致。
     *
     * @param filePaths 来源文件路径列表（命中值对象紧凑构造器已归一非 null 并剔除空白元素），可为空
     * @return 拼接后的单个路径字符串；空列表返回空串
     */
    private static String renderFilePaths(List<String> filePaths) {
        if (CollectionUtils.isEmpty(filePaths)) {
            return StringUtils.EMPTY;
        }
        return String.join(FILE_PATH_JOINER, filePaths);
    }

    /**
     * 实体列表 token 份额截断（P3，spec「实体列表 token 截断」场景）：
     * 依列表顺序逐条累加知识字段计重，累计将超 {@code maxEntityTokens} 即停止纳入、
     * 保留此前全部前缀；剔除口径与跳过口径见
     * {@link #truncateByKnowledgeTokens(List, int, Predicate, Function)}。
     *
     * @param entities        实体候选集（GRAPH 通道组装产出，已按业务序）
     * @param maxEntityTokens 实体侧 token 份额上限（{@link ContextBudget#maxEntityTokens()}，
     *                        非正值已在值对象构造期归一为默认常量）
     * @return 份额内保留的实体前缀列表（可能为空，恒为入参前缀）
     */
    private List<EntityHit> truncateEntities(List<EntityHit> entities, int maxEntityTokens) {
        return truncateByKnowledgeTokens(entities, maxEntityTokens,
                hit -> StringUtils.isNotBlank(hit.name()), DefaultContextBuilder::entityKnowledgeRecord);
    }

    /**
     * 关系列表 token 份额截断（P3，spec「关系列表 token 截断」场景）：
     * 语义同 {@link #truncateEntities(List, int)}，份额取 {@code maxRelationTokens}，
     * 与实体侧完全独立、互不侵占；端点不全的记录（渲染侧必跳）不计重也不纳入。
     *
     * @param relations         关系视图列表（关系路命中 ∪ 关联边，已按业务序）
     * @param maxRelationTokens 关系侧 token 份额上限（{@link ContextBudget#maxRelationTokens()}，
     *                          非正值已在值对象构造期归一为默认常量）
     * @return 份额内保留的关系前缀列表（可能为空，恒为入参前缀）
     */
    private List<RelationHit> truncateRelations(List<RelationHit> relations, int maxRelationTokens) {
        return truncateByKnowledgeTokens(relations, maxRelationTokens,
                hit -> !hit.normalizedPair().isEmpty(), DefaultContextBuilder::relationKnowledgeRecord);
    }

    /**
     * 逐条累加截断骨架（对齐参考 {@code truncate_list_by_token_size} 保前缀语义）：
     * 按输入序取「知识字段 dict 副本」的真实 encode token 数累加，
     * <b>累计将超上限即停止纳入</b>——保留此前全部前缀，不做跳过后继续，
     * 单条自身即超上限时该条及其后全部舍弃、不留半条；渲染侧必跳的记录（不可用判定不通过）
     * 不计重、不纳入、不终止累加。
     *
     * @param hits            待截断的命中列表（有序）
     * @param maxTokens       该侧 token 份额上限
     * @param usable          记录可用性判定（与渲染侧跳过口径一致：空白实体名 / 端点不全的关系）
     * @param knowledgeRecord 知识字段 dict 提取器（计重字段集，剔除 created_at/file_path）
     * @param <T>             命中记录类型（{@link EntityHit} / {@link RelationHit}）
     * @return 份额内保留的前缀列表（可能为空，恒为入参前缀）
     */
    private <T> List<T> truncateByKnowledgeTokens(List<T> hits, int maxTokens,
                                                  Predicate<T> usable,
                                                  Function<T, Map<String, Object>> knowledgeRecord) {
        if (CollectionUtils.isEmpty(hits)) {
            return List.of();
        }
        List<T> kept = new ArrayList<>(hits.size());
        int usedTokens = 0;
        for (T hit : hits) {
            if (ObjectUtils.isEmpty(hit) || !usable.test(hit)) {
                continue;
            }
            int tokens = knowledgeRecordTokens(knowledgeRecord.apply(hit));
            if (usedTokens + tokens > maxTokens) {
                // 超即停保前缀：其余记录（含该条）整体舍弃，与 chunk 装入的同语义截断口径一致
                break;
            }
            usedTokens += tokens;
            kept.add(hit);
        }
        return kept;
    }

    /**
     * 实体记录的知识字段 dict 副本（计重口径）：仅
     * {@code entity/type/description} 三项，{@code created_at/file_path} 不参与计重；
     * 文本缺失以空串落位，与渲染侧同口径（spec「计重 SHALL 不含 file_path 与 created_at」）。
     *
     * @param hit 实体命中记录（已确认可用）
     * @return 仅含知识字段的有序 dict
     */
    private static Map<String, Object> entityKnowledgeRecord(EntityHit hit) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put(FIELD_ENTITY, StringUtils.defaultString(hit.name()));
        record.put(FIELD_TYPE, StringUtils.defaultString(hit.entityType()));
        record.put(FIELD_DESCRIPTION, StringUtils.defaultString(hit.description()));
        return record;
    }

    /**
     * 关系记录的知识字段 dict 副本（计重口径）：仅
     * {@code entity1/entity2/description} 三项，{@code created_at/file_path} 不参与计重；
     * 文本缺失以空串落位，与渲染侧同口径。
     *
     * @param hit 关系视图记录（已确认可用）
     * @return 仅含知识字段的有序 dict
     */
    private static Map<String, Object> relationKnowledgeRecord(RelationHit hit) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put(FIELD_ENTITY_ONE, StringUtils.defaultString(hit.sourceName()));
        record.put(FIELD_ENTITY_TWO, StringUtils.defaultString(hit.targetName()));
        record.put(FIELD_DESCRIPTION, StringUtils.defaultString(hit.description()));
        return record;
    }

    /**
     * 单条知识字段 dict 的 token 计重：以 {@link ObjectMapper} 序列化为 JSON 文本后交
     * {@link TokenCounter} 真实 encode。序列化失败按 0 计（知识字段全为字符串，
     * 实际不可达；真失败时该记录随后在渲染侧同样跳行，两侧行为一致）。
     *
     * @param record 知识字段 dict
     * @return 该条记录占用的 token 数
     */
    private int knowledgeRecordTokens(Map<String, Object> record) {
        String json = tryWriteValueAsString(record);
        return ObjectUtils.isEmpty(json) ? 0 : tokenCounter.count(json);
    }

    /**
     * 图谱记录的时间分量取值：缺失以空串落位（保持键在场、形态稳定），
     * 非空时原样交给 {@link ObjectMapper} 序列化为 ISO-8601 文本。
     *
     * @param createdAt 图行创建时间，可为 null
     * @return 可序列化的时间值（{@link OffsetDateTime}）或空串
     */
    private static Object createdAtOrEmpty(OffsetDateTime createdAt) {
        return ObjectUtils.isEmpty(createdAt) ? StringUtils.EMPTY : createdAt;
    }

    /**
     * 单条记录序列化并追加为一行：序列化失败记录 WARN 并跳过该行，不中断整体构建。
     *
     * @param lines  行累积器（就地写入）
     * @param record 待序列化的记录（LinkedHashMap 保证字段序）
     */
    private void appendLine(List<String> lines, Map<String, Object> record) {
        String line = tryWriteValueAsString(record);
        if (ObjectUtils.isNotEmpty(line)) {
            lines.add(line);
        }
    }

    /**
     * 单值 JSON 序列化，失败不中断整体构建：记录 WARN 返回 {@code null} 由调用方跳行。
     *
     * @param value 待序列化值
     * @return 单行 JSON 文本；序列化失败返回 {@code null}
     */
    private String tryWriteValueAsString(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException e) {
            log.warn("上下文记录 JSON 序列化失败，该行已跳过: {}", value, e);
            return null;
        }
    }

    /**
     * 预算内保留的 chunk 渲染中间态（chunkId + 正文 + 已兜底非空的来源文件名）。
     *
     * @param chunkId    切片主键（装入序导出 {@code retainedChunkIds} 的身份来源，回取索引保证非空）
     * @param content    切片正文
     * @param sourceFile 来源文件名（缺失时已归一为占位名）
     */
    private record RetainedChunk(Long chunkId, String content, String sourceFile) {
    }
}
