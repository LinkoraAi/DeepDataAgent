package com.linkroa.deepdataagent.knowledgebase.infrastructure;

import com.linkroa.deepdataagent.knowledgebase.application.contract.ChunkIdentity;
import com.linkroa.deepdataagent.knowledgebase.application.contract.IngestionDocumentContext;
import com.linkroa.deepdataagent.knowledgebase.api.IngestionSourceReader;
import com.linkroa.deepdataagent.knowledgebase.domain.model.Document;
import com.linkroa.deepdataagent.knowledgebase.domain.model.DocumentParseConfig;
import com.linkroa.deepdataagent.knowledgebase.domain.model.EmbeddingModelConfig;
import com.linkroa.deepdataagent.knowledgebase.domain.model.EngineConfig;
import com.linkroa.deepdataagent.knowledgebase.domain.model.EntityType;
import com.linkroa.deepdataagent.knowledgebase.domain.model.KnowledgeBase;
import com.linkroa.deepdataagent.knowledgebase.domain.model.MultiModelConfig;
import com.linkroa.deepdataagent.knowledgebase.domain.model.RagEngineConfig;
import com.linkroa.deepdataagent.knowledgebase.domain.model.S3File;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.DocumentStatus;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.KbLanguage;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.ParseEngineProvider;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.RagEngineType;
import com.linkroa.deepdataagent.knowledgebase.domain.repository.ChunkRepository;
import com.linkroa.deepdataagent.knowledgebase.domain.repository.DocumentRepository;
import com.linkroa.deepdataagent.knowledgebase.domain.repository.KnowledgeBaseRepository;
import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import com.linkroa.deepdataagent.shared.exception.ResourceConflictException;
import com.linkroa.deepdataagent.shared.exception.ResourceNotFoundException;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@link IngestionSourceReader} 契约的默认实现（防腐层 / 契约适配器）。
 * <p>供 RAG 上下文的摄入管线读取文档处理所需的知识库侧输入。本类只做「聚合根 + JSONB
 * 配置 → 发布语言」的组装与防御式解析，不含任何业务规则，全程只读不加锁。</p>
 * <p>JSONB 列无类型约束，读取侧防御口径与 {@link DefaultKnowledgeBaseApi} 一致：
 * 非法 JSON / 与领域不变量冲突即数据损坏，抛业务异常；空白按 {@code null} 处理；
 * 实体类型清单解析失败按空清单兜底（非关键路径，不阻断摄入）。</p>
 */
@Component
public class DefaultIngestionSourceReader implements IngestionSourceReader {

    private static final Logger log = LoggerFactory.getLogger(DefaultIngestionSourceReader.class);

    /** rag_engine_config JSON 字段名：引擎类型 */
    private static final String FIELD_ENGINE_TYPE = "engineType";

    /** rag_engine_config JSON 字段名：解析引擎配置 */
    private static final String FIELD_PARSE_ENGINE = "parseEngine";

    /** rag_engine_config JSON 字段名：分块策略配置 */
    private static final String FIELD_CHUNK_STRATEGY = "chunkStrategy";

    /** parseEngine 段字段名：解析提供方 */
    private static final String FIELD_PROVIDER = "provider";

    /** parseEngine 段字段名：引擎配置 */
    private static final String FIELD_ENGINE_CONFIG = "engineConfig";

    /** engineConfig 段字段名：参数映射 */
    private static final String FIELD_PARAMS = "params";

    /** 模型配置 JSON 字段名：模型引用标识 */
    private static final String FIELD_MODEL_PROFILE_ID = "modelProfileId";

    /** s3_file JSON 字段名：对象键（桶概念已退役） */
    private static final String FIELD_OBJECT_KEY = "objectKey";

    /** entity_type_config JSON 字段名：实体类型清单 */
    private static final String FIELD_ENTITY_TYPES = "entityTypes";

    /** 实体类型条目字段名：类型名称 */
    private static final String FIELD_ENTITY_TYPE = "entityType";

    /** 配置 JSON 解析器（无状态，进程内共享） */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final DocumentRepository documentRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final ChunkRepository chunkRepository;

    public DefaultIngestionSourceReader(DocumentRepository documentRepository,
                                        KnowledgeBaseRepository knowledgeBaseRepository,
                                        ChunkRepository chunkRepository) {
        this.documentRepository = documentRepository;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.chunkRepository = chunkRepository;
    }

    @Override
    public IngestionDocumentContext readContext(Long documentId) {
        if (ObjectUtils.isEmpty(documentId)) {
            throw new DeepDataAgentException("文档ID不能为空");
        }
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("文档不存在"));
        KnowledgeBase knowledgeBase = knowledgeBaseRepository.findById(document.kbId())
                .orElseThrow(() -> new ResourceNotFoundException("知识库不存在"));
        // 删除流程早闸门：处于 DELETING / DELETE_FAILED
        // 的库立即拒绝摄入，排队文档快速出清，不再产生解析 / 分块增强 / 向量化等后续远程调用；
        // worker 走既有 Stage0 失败路径置 FAILED
        if (!knowledgeBase.isActive()) {
            throw new ResourceConflictException("知识库清退中，拒绝摄入");
        }
        // rag_engine_config 整段只解析一次，派生值对象与解析引擎配置两个投影
        JsonNode engineRoot = readJsonObjectOrNull(knowledgeBase.ragEngineConfig(), "RAG引擎配置");
        RagEngineConfig ragEngineConfig = buildRagEngineConfig(engineRoot);
        return new IngestionDocumentContext(
                document.id(),
                document.kbId(),
                document.fileName(),
                document.status(),
                parseS3File(document.s3File()),
                StringUtils.trimToNull(document.chunkStrategy()),
                ragEngineConfig,
                buildParseConfig(engineRoot),
                parseEmbeddingConfig(knowledgeBase.embeddingConfig()),
                parseMultiModelConfig(knowledgeBase.multiModelConfig()),
                parseEntityTypes(knowledgeBase.entityTypeConfig()),
                StringUtils.trimToNull(knowledgeBase.ragEngineConfig()),
                // 语言真相源 = knowledge_base.language 列，读取侧归一口径与
                // DefaultKnowledgeBaseApi.findLanguageByKbId 一致；JSONB language 键已弃用
                normalizeLanguage(knowledgeBase.language())
        );
    }

    @Override
    public DocumentStatus statusOf(Long documentId) {
        if (ObjectUtils.isEmpty(documentId)) {
            return null;
        }
        return documentRepository.findById(documentId)
                .map(Document::status)
                .orElse(null);
    }

    @Override
    public List<ChunkIdentity> listChunkIdentitiesByDocument(Long documentId) {
        if (ObjectUtils.isEmpty(documentId)) {
            return List.of();
        }
        return chunkRepository.findIdAndSequenceByDocumentId(documentId).entrySet().stream()
                .map(entry -> new ChunkIdentity(entry.getKey(), entry.getValue()))
                .toList();
    }

    /**
     * 解析文档源文件引用：空白返回 {@code null}（无源文件，由消费方判定 FAILED），
     * 非法 JSON 或与值对象不变量冲突视为数据损坏抛业务异常。
     *
     * @param s3FileJson document.s3_file 列 JSON 文本
     * @return S3 引用值对象；未配置返回 {@code null}
     * @throws DeepDataAgentException JSON 非法或字段缺失（数据损坏）
     */
    private S3File parseS3File(String s3FileJson) {
        if (StringUtils.isBlank(s3FileJson)) {
            return null;
        }
        JsonNode root = readJsonObject(s3FileJson, "文档源文件引用");
        try {
            return new S3File(readText(root, FIELD_OBJECT_KEY));
        } catch (IllegalArgumentException e) {
            log.error("文档源文件引用与领域模型不变量冲突，原始值={}", s3FileJson, e);
            throw new DeepDataAgentException("文档源文件引用数据损坏：" + e.getMessage());
        }
    }

    /**
     * 由已解析的 rag_engine_config 根节点组装引擎配置值对象（便利投影）。
     * <p>模型选型不属本列职责：知识库级 LLM 统一引用由 {@code multi_model_config} 承载并单独投影为
     * {@code ctx.multiModelConfig()}，嵌入模型由 {@code embedding_config} 承载。</p>
     *
     * @param engineRoot 引擎配置根节点，可为 {@code null}（库未配置）
     * @return 引擎配置值对象；未配置返回 {@code null}
     * @throws DeepDataAgentException engineType 缺失或与领域不变量冲突（数据损坏）
     */
    private RagEngineConfig buildRagEngineConfig(JsonNode engineRoot) {
        if (ObjectUtils.isEmpty(engineRoot)) {
            return null;
        }
        try {
            return new RagEngineConfig(
                    parseEnum(engineRoot.path(FIELD_ENGINE_TYPE), RagEngineType.values()),
                    readJsonText(engineRoot.path(FIELD_PARSE_ENGINE)),
                    readJsonText(engineRoot.path(FIELD_CHUNK_STRATEGY)));
        } catch (IllegalArgumentException e) {
            log.error("RAG引擎配置与领域模型不变量冲突", e);
            throw new DeepDataAgentException("RAG引擎配置数据损坏：" + e.getMessage());
        }
    }

    /**
     * 归一知识库语言（真相源 = {@code knowledge_base.language} 列）：
     * 命中 11 全名值域（trim、大小写不敏感）时返回枚举规范全名（存量值如 ENGLISH 归入 English）；
     * 未命中按原文 trim 透传（历史裸语言码宽容口径，维持不变）；
     * 空白返回 {@code null}（未配置，消费方回落全局默认语言）。
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

    /**
     * 由已解析的 rag_engine_config 根节点提取文档解析引擎配置（parseEngine 段）。
     * <p>provider 取值非法按 {@code null} 处理（由消费方回落默认解析器）；
     * engineConfig.params 递归转换为普通键值映射。</p>
     *
     * @param engineRoot 引擎配置根节点，可为 {@code null}（库未配置）
     * @return 解析引擎配置；parseEngine 段缺失或非对象返回 {@code null}
     */
    private DocumentParseConfig buildParseConfig(JsonNode engineRoot) {
        if (ObjectUtils.isEmpty(engineRoot)) {
            return null;
        }
        JsonNode parseNode = engineRoot.path(FIELD_PARSE_ENGINE);
        if (!parseNode.isObject()) {
            return null;
        }
        ParseEngineProvider provider = parseEnum(parseNode.path(FIELD_PROVIDER), ParseEngineProvider.values());
        JsonNode paramsNode = parseNode.path(FIELD_ENGINE_CONFIG).path(FIELD_PARAMS);
        return new DocumentParseConfig(provider, new EngineConfig(toPlainMap(paramsNode)));
    }

    /**
     * 解析嵌入模型配置（embedding_config 列）：结构 {@code {modelProfileId}}。
     *
     * @param configJson 配置 JSON 文本
     * @return 嵌入模型配置；未配置返回 {@code null}
     * @throws DeepDataAgentException JSON 非法或模型ID缺失（数据损坏）
     */
    private EmbeddingModelConfig parseEmbeddingConfig(String configJson) {
        if (StringUtils.isBlank(configJson)) {
            return null;
        }
        JsonNode root = readJsonObject(configJson, "嵌入模型配置");
        try {
            return new EmbeddingModelConfig(readText(root, FIELD_MODEL_PROFILE_ID));
        } catch (IllegalArgumentException e) {
            log.error("嵌入模型配置与领域模型不变量冲突，原始值={}", configJson, e);
            throw new DeepDataAgentException("嵌入模型配置数据损坏：" + e.getMessage());
        }
    }

    /**
     * 解析多模态模型配置（multi_model_config 列）：结构 {@code {modelProfileId}}。
     *
     * @param configJson 配置 JSON 文本
     * @return 多模态模型配置；未配置返回 {@code null}
     * @throws DeepDataAgentException JSON 非法或模型ID缺失（数据损坏）
     */
    private MultiModelConfig parseMultiModelConfig(String configJson) {
        if (StringUtils.isBlank(configJson)) {
            return null;
        }
        JsonNode root = readJsonObject(configJson, "多模态模型配置");
        try {
            return new MultiModelConfig(readText(root, FIELD_MODEL_PROFILE_ID));
        } catch (IllegalArgumentException e) {
            log.error("多模态模型配置与领域模型不变量冲突，原始值={}", configJson, e);
            throw new DeepDataAgentException("多模态模型配置数据损坏：" + e.getMessage());
        }
    }

    /**
     * 解析实体类型自定义配置（entity_type_config 列）：结构
     * {@code {"entityTypes":[{"entityType":"名称"}]}}，兼容纯字符串数组形态。
     * <p>非关键路径：任何解析失败（含单条名称非法触发的值对象不变量冲突）
     * 均按空清单兜底并告警，不阻断摄入。</p>
     *
     * @param configJson 配置 JSON 文本
     * @return 实体类型清单；未配置或解析失败返回空清单
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
            log.warn("实体类型配置解析失败，按空清单兜底，原始值={}", configJson, e);
            return List.of();
        }
    }

    /**
     * 解析 JSON 文本为对象根节点：空白返回 {@code null}，非法 / 非对象抛业务异常。
     *
     * @param json  JSON 文本（可空白）
     * @param label 配置名称（用于日志与异常消息）
     * @return 对象根节点；空白返回 {@code null}
     * @throws DeepDataAgentException JSON 非法或非对象（数据损坏）
     */
    private JsonNode readJsonObjectOrNull(String json, String label) {
        if (StringUtils.isBlank(json)) {
            return null;
        }
        return readJsonObject(json, label);
    }

    /**
     * 解析 JSON 文本为对象根节点（要求非空白）。
     *
     * @param json  JSON 文本（非空白）
     * @param label 配置名称（用于日志与异常消息）
     * @return 对象根节点
     * @throws DeepDataAgentException JSON 非法或非对象（数据损坏）
     */
    private JsonNode readJsonObject(String json, String label) {
        final JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(json);
        } catch (JacksonException e) {
            log.error("{}不是合法JSON，原始值={}", label, json, e);
            throw new DeepDataAgentException(label + "不是合法的JSON");
        }
        if (!root.isObject()) {
            log.error("{}必须是JSON对象，原始值={}", label, json);
            throw new DeepDataAgentException(label + "必须是JSON对象");
        }
        return root;
    }

    /**
     * 按枚举名解析枚举节点（大小写不敏感，写入口已归一化为大写枚举名）。
     *
     * @param node     待解析节点
     * @param candidates 枚举候选数组
     * @return 命中的枚举值；缺失、非文本或取值非法返回 {@code null}
     */
    private static <E extends Enum<E>> E parseEnum(JsonNode node, E[] candidates) {
        if (!node.isTextual()) {
            return null;
        }
        String normalized = StringUtils.trimToEmpty(node.stringValue()).toUpperCase(Locale.ROOT);
        for (E candidate : candidates) {
            if (StringUtils.equals(candidate.name(), normalized)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 读取节点为 JSON 文本：缺失 / null 返回 {@code null}，文本节点取原值，其余取序列化文本。
     *
     * @param node 待读取节点
     * @return JSON / 文本内容；缺失返回 {@code null}
     */
    private static String readJsonText(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        return node.isTextual() ? node.stringValue() : node.toString();
    }

    /**
     * JSON 节点递归转换为普通 Java 键值映射（供领域侧 Map 消费）。
     *
     * @param node 待转换节点；缺失 / 非对象转为空映射
     * @return 不可变语义的普通映射（LinkedHashMap，保持字段序）
     */
    private static Map<String, Object> toPlainMap(JsonNode node) {
        if (!node.isObject()) {
            return Map.of();
        }
        Map<String, Object> params = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> field : node.properties()) {
            params.put(field.getKey(), toPlainValue(field.getValue()));
        }
        return params;
    }

    /**
     * JSON 节点递归转换为普通 Java 值（对象→映射、数组→列表、标量→对应包装类型）。
     *
     * @param node 待转换节点
     * @return 普通 Java 值；null 节点返回 {@code null}
     */
    private static Object toPlainValue(JsonNode node) {
        if (node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isObject()) {
            return toPlainMap(node);
        }
        if (node.isArray()) {
            List<Object> values = new ArrayList<>(node.size());
            for (int i = 0; i < node.size(); i++) {
                values.add(toPlainValue(node.get(i)));
            }
            return values;
        }
        if (node.isTextual()) {
            return node.stringValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isNumber()) {
            // 以数字文本经 BigDecimal 归一——整数语义统一为 Long，其余为 Double，保证参数映射类型口径稳定。
            // 注意：必须用 if/else 分支返回，若写成三元表达式 long:double 会触发二进制数值提升，
            // Long 分支会被加宽装箱为 Double（JLS 15.25）
            BigDecimal decimal = new BigDecimal(node.asText());
            if (decimal.stripTrailingZeros().scale() <= 0) {
                return decimal.longValue();
            }
            return decimal.doubleValue();
        }
        return node.toString();
    }

    /**
     * 读取文本字段：缺失或非文本返回 {@code null}。
     */
    private static String readText(JsonNode parent, String fieldName) {
        JsonNode node = parent.path(fieldName);
        return node.isTextual() ? node.stringValue() : null;
    }
}
