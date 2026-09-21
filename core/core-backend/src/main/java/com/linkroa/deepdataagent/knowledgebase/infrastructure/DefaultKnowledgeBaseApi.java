package com.linkroa.deepdataagent.knowledgebase.infrastructure;

import com.linkroa.deepdataagent.knowledgebase.api.KnowledgeBaseApi;
import com.linkroa.deepdataagent.knowledgebase.application.contract.ChunkScoreDTO;
import com.linkroa.deepdataagent.knowledgebase.application.contract.KnowledgeBaseReferenceDTO;
import com.linkroa.deepdataagent.knowledgebase.domain.model.Chunk;
import com.linkroa.deepdataagent.knowledgebase.domain.model.ChunkScoreHit;
import com.linkroa.deepdataagent.knowledgebase.domain.model.EntityType;
import com.linkroa.deepdataagent.knowledgebase.domain.model.FusionStrategyConfig;
import com.linkroa.deepdataagent.knowledgebase.domain.model.KnowledgeBase;
import com.linkroa.deepdataagent.knowledgebase.domain.model.RerankModelConfig;
import com.linkroa.deepdataagent.knowledgebase.domain.model.RetrievalStrategyConfig;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.FusionStrategyType;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.KbLanguage;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.LifecycleStatus;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.RetrievalChannel;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.RetrievalStrategyType;
import com.linkroa.deepdataagent.knowledgebase.domain.repository.ChunkRepository;
import com.linkroa.deepdataagent.knowledgebase.domain.repository.ChunkRepresentationRepository;
import com.linkroa.deepdataagent.knowledgebase.domain.repository.KnowledgeBaseRepository;
import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 知识库服务契约实现（{@link KnowledgeBaseApi}）。
 * <p>读侧进程内直连本 BC 领域仓储，只读不加锁；处于删除流程（DELETING / DELETE_FAILED）的知识库
 * 仍会被解析出来，由消费方依据 {@code lifecycleStatus} 或 {@link #isActive(Long)} 自行判定；
 * 已收口库行已物理删除（DELETED 不再是持久状态），按行缺失不可达。</p>
 * <p><b>写侧全部直连本 BC 领域仓储</b>：删除收口 {@code executeDelete} 为条件物理 DELETE，
 * 失败留痕 {@code markFailed} 为单语句 CAS；本类只做契约适配、不含业务规则。</p>
 * <p><b>依赖收窄（Bean 循环消除）</b>：本类原先为两处写侧方法委托 {@code KnowledgeBaseApplicationService}，
 * 而该应用服务持有 rag 实现的跨 BC 出站端口（{@code KbCleanupTaskSubmitter} / {@code InFlightTaskRegistry}），
 * 使知识库与 rag 的 Bean 装配图形成闭环、应用无法启动。现全部改为直连领域仓储——适配器只依赖叶子能力，
 * 构造器签名即编译期约束；原跨 BC 契约方法 {@code saveRetrievalConfig} 因全项目零消费方同步退役。</p>
 */
@Component
public class DefaultKnowledgeBaseApi implements KnowledgeBaseApi {

    private static final Logger log = LoggerFactory.getLogger(DefaultKnowledgeBaseApi.class);

    /** 检索策略 JSON 字段名：策略类型 */
    private static final String FIELD_STRATEGY_TYPE = "strategyType";

    /** 检索策略 JSON 字段名：是否启用问题改写 */
    private static final String FIELD_REWRITE_QUESTION = "rewriteQuestion";

    /** 检索策略 JSON 字段名：结果返回数量 */
    private static final String FIELD_RESULT_CHUNK_COUNT = "resultChunkCount";

    /** 检索策略 JSON 字段名：相似度阈值 */
    private static final String FIELD_SIMILAR_THRESHOLD = "similarThreshold";

    /** 检索策略 JSON 字段名：重排配置 */
    private static final String FIELD_RERANK_CONFIG = "rerankConfig";

    /** 检索策略 JSON 字段名：融合配置 */
    private static final String FIELD_FUSION_CONFIG = "fusionConfig";

    /** 重排配置字段名：是否启用 */
    private static final String FIELD_ENABLED = "enabled";

    /** 重排配置字段名：模型引用标识 */
    private static final String FIELD_MODEL_PROFILE_ID = "modelProfileId";

    /** 重排配置字段名：重排保留 top K 数量 */
    private static final String FIELD_TOP_K = "topK";

    /** 融合配置字段名：融合类型 */
    private static final String FIELD_FUSION_TYPE = "fusionType";

    /** 融合配置字段名：RRF 常数 k */
    private static final String FIELD_RRF_K = "rrfK";

    /** 融合配置字段名：各通道稠密权重 */
    private static final String FIELD_CHANNEL_DENSE_WEIGHT = "channelDenseWeight";

    /** entity_type_config JSON 字段名：实体类型清单 */
    private static final String FIELD_ENTITY_TYPES = "entityTypes";

    /** 实体类型条目字段名：类型名称 */
    private static final String FIELD_ENTITY_TYPE = "entityType";

    /** 失败留痕（error_message）截断上限，防止超长异常文案溢出留痕列（与知识库应用服务同形口径） */
    private static final int KB_ERROR_MESSAGE_MAX_LENGTH = 500;

    /** 检索策略 JSON 解析器（无状态，进程内共享） */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 知识库汇聚仓储（读侧与写侧收口 / 失败留痕的唯一权威通道） */
    private final KnowledgeBaseRepository knowledgeBaseRepository;

    /** 切片仓储（按库与切片 ID 集合读取切片正文） */
    private final ChunkRepository chunkRepository;

    /** 切片 1:1 派生表示仓储（向量 / 全文检索召回通道） */
    private final ChunkRepresentationRepository chunkRepresentationRepository;

    /**
     * 构造知识库契约实现。
     * <p>仅依赖领域仓储：MUST NOT 引入本 BC 应用服务——应用服务持有 rag 实现的跨 BC 出站端口，
     * 会使两个 BC 的 Bean 装配图形成闭环、应用无法启动。</p>
     *
     * @param knowledgeBaseRepository       知识库仓储
     * @param chunkRepository               切片仓储
     * @param chunkRepresentationRepository 切片派生表示仓储
     */
    public DefaultKnowledgeBaseApi(KnowledgeBaseRepository knowledgeBaseRepository,
                                   ChunkRepository chunkRepository,
                                   ChunkRepresentationRepository chunkRepresentationRepository) {
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.chunkRepository = chunkRepository;
        this.chunkRepresentationRepository = chunkRepresentationRepository;
    }

    @Override
    public Optional<KnowledgeBaseReferenceDTO> resolveById(Long kbId) {
        if (ObjectUtils.isEmpty(kbId)) {
            return Optional.empty();
        }
        return knowledgeBaseRepository.findById(kbId).map(DefaultKnowledgeBaseApi::toReference);
    }

    @Override
    public boolean isActive(Long kbId) {
        if (ObjectUtils.isEmpty(kbId)) {
            return false;
        }
        return knowledgeBaseRepository.findById(kbId)
                .map(KnowledgeBase::isActive)
                .orElse(false);
    }

    @Override
    public RetrievalStrategyConfig findRetrievalStrategyByKbId(Long kbId) {
        if (ObjectUtils.isEmpty(kbId)) {
            return null;
        }
        return knowledgeBaseRepository.findById(kbId)
                .map(KnowledgeBase::retrievalStrategy)
                .filter(StringUtils::isNotBlank)
                .map(this::parseRetrievalStrategy)
                .orElse(null);
    }

    @Override
    public String findLanguageByKbId(Long kbId) {
        if (ObjectUtils.isEmpty(kbId)) {
            return null;
        }
        // 真相源 = knowledge_base.language 列：rag_engine_config JSONB 的 language 键已弃用
        return knowledgeBaseRepository.findById(kbId)
                .map(KnowledgeBase::language)
                .map(DefaultKnowledgeBaseApi::normalizeLanguage)
                .orElse(null);
    }

    @Override
    public List<Long> findIdsByLifecycle(LifecycleStatus lifecycle) {
        if (ObjectUtils.isEmpty(lifecycle)) {
            return List.of();
        }
        return knowledgeBaseRepository.findIdsByLifecycle(lifecycle);
    }

    /**
     * language 列值 → 知识库语言全名（读侧归一，与 {@code DefaultIngestionSourceReader}
     * 归一口径一致）。
     * <p>命中 11 全名值域（trim、大小写不敏感）归一为枚举规范全名（存量值如 {@code ENGLISH}
     * 归入 English）；未命中按原文 trim 透传（历史裸语言码宽容口径，维持现状不变）；
     * 列值空白按未配置处理返回 {@code null}（缺省视同 Chinese 由调用方回落）。</p>
     *
     * @param rawLanguage knowledge_base.language 列原始值，可为 {@code null}
     * @return 归一语言全名 / 原样透传值；未配置返回 {@code null}
     */
    private static String normalizeLanguage(String rawLanguage) {
        if (StringUtils.isBlank(rawLanguage)) {
            return null;
        }
        return KbLanguage.find(rawLanguage).map(KbLanguage::name).orElse(rawLanguage.trim());
    }

    @Override
    public String findMediaModelProfileIdByKbId(Long kbId) {
        if (ObjectUtils.isEmpty(kbId)) {
            return null;
        }
        return knowledgeBaseRepository.findById(kbId)
                .map(KnowledgeBase::multiModelConfig)
                .filter(StringUtils::isNotBlank)
                .map(this::parseMediaModelProfileId)
                .orElse(null);
    }

    /**
     * multi_model_config JSON → 多模态模型 profileId（读侧防御式解析，与
     * {@code DefaultIngestionSourceReader#parseMultiModelConfig} 取值口径一致）。
     * <p>{@code modelProfileId} 键缺失、非文本或空白一律按「未配置视觉模型」返回 {@code null}
     * （由消费方走文本回落，不视为数据损坏）；非法 JSON / 非对象视为数据损坏。</p>
     *
     * @param multiModelConfigJson multi_model_config JSON 文本（调用方已保证非空白）
     * @return 多模态模型 profileId（已 trim）；未配置返回 {@code null}
     * @throws DeepDataAgentException JSON 非法或非对象（数据损坏，正常写入口已校验不应触发）
     */
    private String parseMediaModelProfileId(String multiModelConfigJson) {
        final JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(multiModelConfigJson);
        } catch (JacksonException e) {
            log.error("多模态模型配置不是合法JSON，原始值={}", multiModelConfigJson, e);
            throw new DeepDataAgentException("多模态模型配置不是合法的JSON");
        }
        if (!root.isObject()) {
            log.error("多模态模型配置必须是JSON对象，原始值={}", multiModelConfigJson);
            throw new DeepDataAgentException("多模态模型配置必须是JSON对象");
        }
        return StringUtils.trimToNull(readText(root, FIELD_MODEL_PROFILE_ID));
    }

    @Override
    public String findEmbeddingModelProfileIdByKbId(Long kbId) {
        if (ObjectUtils.isEmpty(kbId)) {
            return null;
        }
        return knowledgeBaseRepository.findById(kbId)
                .map(KnowledgeBase::embeddingConfig)
                .filter(StringUtils::isNotBlank)
                .map(this::parseEmbeddingModelProfileId)
                .orElse(null);
    }

    /**
     * embedding_config JSON → 嵌入模型 profileId（读侧防御式解析，与
     * {@code DefaultIngestionSourceReader#parseEmbeddingConfig} 取值口径一致）。
     * <p>{@code modelProfileId} 键缺失、非文本或空白一律按「未配置嵌入模型」返回 {@code null}
     * （由消费方按向量检索不可用处理，不回退任何全局默认值）；非法 JSON / 非对象视为数据损坏。</p>
     *
     * @param embeddingConfigJson embedding_config JSON 文本（调用方已保证非空白）
     * @return 嵌入模型 profileId（已 trim）；未配置返回 {@code null}
     * @throws DeepDataAgentException JSON 非法或非对象（数据损坏，正常写入口已校验不应触发）
     */
    private String parseEmbeddingModelProfileId(String embeddingConfigJson) {
        final JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(embeddingConfigJson);
        } catch (JacksonException e) {
            log.error("嵌入模型配置不是合法JSON，原始值={}", embeddingConfigJson, e);
            throw new DeepDataAgentException("嵌入模型配置不是合法的JSON");
        }
        if (!root.isObject()) {
            log.error("嵌入模型配置必须是JSON对象，原始值={}", embeddingConfigJson);
            throw new DeepDataAgentException("嵌入模型配置必须是JSON对象");
        }
        return StringUtils.trimToNull(readText(root, FIELD_MODEL_PROFILE_ID));
    }

    @Override
    public List<EntityType> findEntityTypesByKbId(Long kbId) {
        if (ObjectUtils.isEmpty(kbId)) {
            return List.of();
        }
        return knowledgeBaseRepository.findById(kbId)
                .map(KnowledgeBase::entityTypeConfig)
                .map(this::parseEntityTypes)
                .orElseGet(List::of);
    }

    /**
     * 实体类型自定义配置 JSON → 清单（读侧防御式解析，口径与
     * {@code DefaultIngestionSourceReader#parseEntityTypes} 一致：非关键路径配置，
     * 任何解析失败按空清单兜底并告警，MUST NOT 抛出）。
     *
     * @param configJson entity_type_config 列 JSON 文本，可为空白
     * @return 实体类型清单；未配置或解析失败返回空列表
     */
    private List<EntityType> parseEntityTypes(String configJson) {
        if (StringUtils.isBlank(configJson)) {
            return List.of();
        }
        try {
            JsonNode array = OBJECT_MAPPER.readTree(configJson).path(FIELD_ENTITY_TYPES);
            if (!array.isArray()) {
                return List.of();
            }
            List<EntityType> entityTypes = new ArrayList<>(array.size());
            for (int i = 0; i < array.size(); i++) {
                JsonNode item = array.get(i);
                String name = item.isObject() ? readText(item, FIELD_ENTITY_TYPE)
                        : (item.isTextual() ? item.stringValue() : null);
                if (StringUtils.isNotBlank(name)) {
                    entityTypes.add(new EntityType(name));
                }
            }
            return List.copyOf(entityTypes);
        } catch (JacksonException | IllegalArgumentException e) {
            log.warn("实体类型配置解析失败，按空清单兜底, 原始值={}", configJson, e);
            return List.of();
        }
    }

    @Override
    public String findRagEngineConfigJsonByKbId(Long kbId) {
        if (ObjectUtils.isEmpty(kbId)) {
            return null;
        }
        // 无损只读投影：列原文 trim 后透传，子段（图合并段等）语义由消费方（rag BC）自行解析
        return knowledgeBaseRepository.findById(kbId)
                .map(KnowledgeBase::ragEngineConfig)
                .map(StringUtils::trimToNull)
                .orElse(null);
    }

    @Override
    public List<Chunk> findChunksByKbIdAndChunkIds(Long kbId, Collection<Long> chunkIds) {
        if (ObjectUtils.isEmpty(kbId) || ObjectUtils.isEmpty(chunkIds)) {
            return Collections.emptyList();
        }
        return chunkRepository.findByKbIdAndIds(kbId, chunkIds);
    }

    @Override
    public List<ChunkScoreDTO> searchByVector(Long kbId, double threshold, String vecLiteral, int limit) {
        return chunkRepresentationRepository.searchByVector(kbId, threshold, vecLiteral, limit).stream()
                .map(DefaultKnowledgeBaseApi::toScoreDto)
                .toList();
    }

    @Override
    public List<ChunkScoreDTO> searchByKeywords(Long kbId, String query, int limit) {
        return chunkRepresentationRepository.searchByKeywords(kbId, query, limit).stream()
                .map(DefaultKnowledgeBaseApi::toScoreDto)
                .toList();
    }

    /**
     * 检索命中投影 → 发布语言 DTO。
     *
     * @param hit 检索命中投影（领域侧只读投影）
     * @return 命中切片契约
     */
    private static ChunkScoreDTO toScoreDto(ChunkScoreHit hit) {
        return new ChunkScoreDTO(hit.chunkId(), hit.score());
    }

    /**
     * 领域聚合根 → 发布语言 DTO。
     *
     * @param knowledgeBase 知识库聚合根
     * @return 知识库引用契约
     */
    private static KnowledgeBaseReferenceDTO toReference(KnowledgeBase knowledgeBase) {
        return new KnowledgeBaseReferenceDTO(
                knowledgeBase.id(),
                knowledgeBase.name(),
                knowledgeBase.lifecycleStatus().name()
        );
    }

    @Override
    public boolean executeDelete(Long kbId) {
        if (ObjectUtils.isEmpty(kbId)) {
            throw new DeepDataAgentException("知识库ID不能为空");
        }
        // 收口 = Repository 单条条件物理 DELETE（executeDelete 由 Repository 层提供）
        boolean hit = knowledgeBaseRepository.executeDelete(kbId);
        if (!hit) {
            log.info("知识库 {} 未处于可收口态（DELETING/DELETE_FAILED）或行已不存在，收口按幂等空转处理", kbId);
            return false;
        }
        log.info("知识库生命周期收口完成（行物理删除，行缺失即已删除）, kbId={}", kbId);
        return true;
    }

    /**
     * 清退失败留痕：单语句 CAS {@code DELETING → DELETE_FAILED} 并写入 {@code error_message}。
     * <p>直连领域仓储完成条件判定与写入（不做先查后写），非 DELETING 源态零行命中按幂等空转处理，
     * MUST NOT 覆盖并发链已写入的状态；超长文案按 {@value #KB_ERROR_MESSAGE_MAX_LENGTH} 字符截断。</p>
     *
     * @param kbId   知识库主键，必填；为空抛参数错误异常
     * @param reason 失败步骤与原因摘要（写入 error_message），可为空
     * @throws DeepDataAgentException 知识库ID为空（400）
     */
    @Override
    public void markFailed(Long kbId, String reason) {
        if (ObjectUtils.isEmpty(kbId)) {
            throw new DeepDataAgentException("知识库ID不能为空");
        }
        String traceMessage = StringUtils.abbreviate(reason, KB_ERROR_MESSAGE_MAX_LENGTH);
        boolean hit = knowledgeBaseRepository.markFailed(kbId, traceMessage);
        if (!hit) {
            log.info("知识库 {} 未处于 DELETING 源态（或行已不存在），失败留痕按幂等空转处理, reason={}",
                    kbId, traceMessage);
            return;
        }
        log.warn("知识库清退失败已置 DELETE_FAILED（请重新执行删除）, kbId={}, error_message={}", kbId, traceMessage);
    }

    /**
     * 检索策略 JSON → {@link RetrievalStrategyConfig} 领域值对象。
     * <p>JSONB 无类型约束，读取侧做防御性解析：字段缺失或类型不符按 {@code null} 处理，
     * 必填不变量（策略类型 / 融合类型）由领域 record 紧凑构造器兜底校验。</p>
     *
     * @param strategyJson 检索策略 JSON 文本（调用方已保证非空白）
     * @return 检索策略配置领域模型
     * @throws DeepDataAgentException 库中策略 JSON 非法或与领域不变量冲突（数据损坏，正常写入口已校验不应触发）
     */
    private RetrievalStrategyConfig parseRetrievalStrategy(String strategyJson) {
        final JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(strategyJson);
        } catch (JacksonException e) {
            log.error("检索策略配置不是合法JSON，原始值={}", strategyJson, e);
            throw new DeepDataAgentException("检索策略配置不是合法的JSON");
        }
        if (!root.isObject()) {
            log.error("检索策略配置必须是JSON对象，原始值={}", strategyJson);
            throw new DeepDataAgentException("检索策略配置必须是JSON对象");
        }
        try {
            return new RetrievalStrategyConfig(
                    parseStrategyType(root.path(FIELD_STRATEGY_TYPE)),
                    readBoolean(root, FIELD_REWRITE_QUESTION),
                    readInteger(root, FIELD_RESULT_CHUNK_COUNT),
                    readFloat(root, FIELD_SIMILAR_THRESHOLD),
                    parseRerankConfig(root.path(FIELD_RERANK_CONFIG)),
                    parseFusionConfig(root.path(FIELD_FUSION_CONFIG)));
        } catch (IllegalArgumentException e) {
            // 领域 record 紧凑构造器不变量冲突（如策略类型缺失），属库中数据损坏
            log.error("检索策略配置与领域模型不变量冲突，原始值={}", strategyJson, e);
            throw new DeepDataAgentException("检索策略配置数据损坏：" + e.getMessage());
        }
    }

    /**
     * 解析策略类型节点：仅接受文本且可匹配枚举名（写入口已归一化为大写枚举名）。
     *
     * @param node 策略类型节点
     * @return 策略类型；缺失、非文本或取值非法返回 {@code null}（由 record 构造器兜底）
     */
    private static RetrievalStrategyType parseStrategyType(JsonNode node) {
        if (!node.isTextual()) {
            return null;
        }
        String normalized = StringUtils.trimToEmpty(node.stringValue()).toUpperCase(Locale.ROOT);
        for (RetrievalStrategyType candidate : RetrievalStrategyType.values()) {
            if (StringUtils.equals(candidate.name(), normalized)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 解析重排配置节点：缺失、非对象返回 {@code null}（表示未启用重排）。
     *
     * @param node 重排配置节点
     * @return 重排模型配置；未配置返回 {@code null}
     */
    private static RerankModelConfig parseRerankConfig(JsonNode node) {
        if (node.isMissingNode() || node.isNull() || !node.isObject()) {
            return null;
        }
        return new RerankModelConfig(
                readBoolean(node, FIELD_ENABLED),
                readText(node, FIELD_MODEL_PROFILE_ID),
                readInteger(node, FIELD_TOP_K));
    }

    /**
     * 解析融合配置节点：缺失、非对象返回 {@code null}。
     * <p>RRF 时 rrfK 缺省由领域 record 兜底默认值 60；WEIGHTED_SUM 读取各通道稠密权重映射。</p>
     *
     * @param node 融合配置节点
     * @return 融合策略配置；未配置返回 {@code null}
     */
    private static FusionStrategyConfig parseFusionConfig(JsonNode node) {
        if (node.isMissingNode() || node.isNull() || !node.isObject()) {
            return null;
        }
        FusionStrategyType fusionType = parseFusionType(node.path(FIELD_FUSION_TYPE));
        if (fusionType == FusionStrategyType.RRF) {
            return new FusionStrategyConfig(FusionStrategyType.RRF, readInteger(node, FIELD_RRF_K), null);
        }
        return new FusionStrategyConfig(fusionType, null, parseChannelWeights(node.path(FIELD_CHANNEL_DENSE_WEIGHT)));
    }

    /**
     * 解析融合类型节点（大小写不敏感）。
     *
     * @param node 融合类型节点
     * @return 融合类型；缺失、非文本或取值非法返回 {@code null}（由 record 构造器兜底）
     */
    private static FusionStrategyType parseFusionType(JsonNode node) {
        if (!node.isTextual()) {
            return null;
        }
        String normalized = StringUtils.trimToEmpty(node.stringValue()).toUpperCase(Locale.ROOT);
        for (FusionStrategyType candidate : FusionStrategyType.values()) {
            if (StringUtils.equals(candidate.name(), normalized)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 解析各通道稠密权重映射：键为通道枚举名，值为 (0,1) 数值。
     * <p>未识别通道名忽略（写入口白名单外无合法落库来源）；权重非数值视为数据损坏。</p>
     *
     * @param node 通道权重节点
     * @return 通道 → 权重映射；未配置返回 {@code null}
     * @throws DeepDataAgentException 权重值非数值（数据损坏）
     */
    private static Map<RetrievalChannel, Float> parseChannelWeights(JsonNode node) {
        if (node.isMissingNode() || node.isNull() || !node.isObject()) {
            return null;
        }
        Map<RetrievalChannel, Float> weights = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> field : node.properties()) {
            RetrievalChannel channel = parseChannel(field.getKey());
            if (channel == null) {
                continue;
            }
            JsonNode value = field.getValue();
            if (!value.isNumber()) {
                throw new DeepDataAgentException("通道" + channel.name() + "的稠密权重必须是数值");
            }
            weights.put(channel, (float) value.doubleValue());
        }
        return weights;
    }

    /**
     * 按枚举名解析检索通道。
     *
     * @param raw 通道枚举名
     * @return 检索通道；未识别返回 {@code null}
     */
    private static RetrievalChannel parseChannel(String raw) {
        for (RetrievalChannel candidate : RetrievalChannel.values()) {
            if (StringUtils.equals(candidate.name(), raw)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 读取布尔字段：缺失或非布尔返回 {@code null}。
     */
    private static Boolean readBoolean(JsonNode parent, String fieldName) {
        JsonNode node = parent.path(fieldName);
        return node.isBoolean() ? node.booleanValue() : null;
    }

    /**
     * 读取整型字段：缺失或非数值返回 {@code null}。
     */
    private static Integer readInteger(JsonNode parent, String fieldName) {
        JsonNode node = parent.path(fieldName);
        return node.isNumber() ? node.intValue() : null;
    }

    /**
     * 读取浮点字段：缺失或非数值返回 {@code null}。
     */
    private static Float readFloat(JsonNode parent, String fieldName) {
        JsonNode node = parent.path(fieldName);
        return node.isNumber() ? (float) node.doubleValue() : null;
    }

    /**
     * 读取文本字段：缺失或非文本返回 {@code null}。
     */
    private static String readText(JsonNode parent, String fieldName) {
        JsonNode node = parent.path(fieldName);
        return node.isTextual() ? node.stringValue() : null;
    }
}
