package com.linkroa.deepdataagent.knowledgebase.application.validation;

import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.RagEngineType;
import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Locale;

/**
 * RAG 引擎配置应用级校验器（知识库创建 / 更新入口共用，仅校验不回写原文）。
 * <p>承载引擎类型白名单校验——{@code rag_engine_config} 的
 * {@code engineType} 仅允许文档引擎，其余引擎类型（含占位 {@code MEDIA_ENGINE}）本期不可被选用：</p>
 * <ul>
 *   <li>{@code engineType} 缺失 / null / 空白：按默认文档引擎放行；</li>
 *   <li>{@code engineType} 命中 {@link RagEngineType#DOCUMENT_ENGINE}（trim、大小写不敏感）：通过；</li>
 *   <li>{@code engineType} 为其他任何取值（含白名单外的非法串）：拒绝并提示「本期不提供该引擎类型」。</li>
 * </ul>
 * <p>承载库级分块策略校验——{@code rag_engine_config} 的 {@code chunkStrategy} 子节点
 * （内嵌对象或被再包一层字符串两种形态均兼容）委托
 * {@link ChunkStrategyConfigValidator#validate(JsonNode)} 执行同一套拒绝式规则
 * （模式七枚举白名单、仅 GENERAL 可携 params、token (0,2048] 整数、overlap [0,30] 整数、
 * delimiter 非空、未识别扩展键含历史废弃键忽略）。</p>
 * <p>整段非法 JSON / 非 JSON 对象：拒绝（数据损坏在写入口先行拦截）。</p>
 * <p><strong>语言键已退役</strong>：语言真相源收敛回
 * {@code knowledge_base.language} 列，本 JSONB 的 {@code language} 键不再校验、不再解析，
 * 存量数据不清理；语言入参值域校验迁移至 {@link KnowledgeBaseValidator#validateLanguage}。</p>
 * <p>与文档解析引擎的 {@code parseEngine.engineConfig.params.language / lang_list}
 * （OCR 多语言列表，如 {@code ch_en}）完全解耦：两者互不校验、互不联动。</p>
 * <p>模型选型不在本校验器职责内：知识库级 LLM 统一引用（对话 / 多模态 / 实体抽取 / 关键词提取）
 * 落 {@code multi_model_config}、嵌入模型落 {@code embedding_config}、重排模型落
 * {@code retrieval_strategy.rerankConfig}，各自独立成列，不混入引擎配置。</p>
 * <p>无外部 BC 依赖、无状态静态工具；校验须在事务开启前完成。</p>
 */
public final class RagEngineConfigValidator {

    private static final Logger log = LoggerFactory.getLogger(RagEngineConfigValidator.class);

    /** rag_engine_config JSON 字段名：引擎类型 */
    private static final String FIELD_ENGINE_TYPE = "engineType";

    /** rag_engine_config JSON 字段名：分块策略 */
    private static final String FIELD_CHUNK_STRATEGY = "chunkStrategy";

    /** rag_engine_config JSON 解析器（无状态，进程内共享） */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private RagEngineConfigValidator() {
    }

    /**
     * 校验 RAG 引擎配置 JSON 的引擎类型与库级分块策略（合法时原样落库，本校验器不做任何回写）。
     *
     * @param ragEngineConfigJson RAG 引擎配置 JSON 文本，可为空白（未配置，直接通过）
     * @throws DeepDataAgentException 非法 JSON、引擎类型非白名单或分块策略非法（400）
     */
    public static void validate(String ragEngineConfigJson) {
        if (StringUtils.isBlank(ragEngineConfigJson)) {
            return;
        }
        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(ragEngineConfigJson);
        } catch (JacksonException e) {
            log.error("RAG引擎配置不是合法JSON，原始值={}", ragEngineConfigJson, e);
            throw new DeepDataAgentException("RAG引擎配置不是合法的JSON");
        }
        if (!root.isObject()) {
            throw new DeepDataAgentException("RAG引擎配置必须是JSON对象");
        }
        validateEngineType(root.path(FIELD_ENGINE_TYPE));
        ChunkStrategyConfigValidator.validate(root.path(FIELD_CHUNK_STRATEGY));
    }

    /**
     * 校验引擎类型白名单：缺失 / null / 空白按默认文档引擎放行；仅接受 {@code DOCUMENT_ENGINE}
     * （trim、大小写不敏感）；其余取值（含占位 {@code MEDIA_ENGINE} 与非法串）一律拒绝。
     */
    private static void validateEngineType(JsonNode engineTypeNode) {
        if (engineTypeNode.isMissingNode() || engineTypeNode.isNull()) {
            return;
        }
        String raw = engineTypeNode.isTextual() ? engineTypeNode.stringValue() : engineTypeNode.toString();
        if (StringUtils.isBlank(raw)) {
            return;
        }
        if (!StringUtils.equals(RagEngineType.DOCUMENT_ENGINE.name(),
                StringUtils.trim(raw).toUpperCase(Locale.ROOT))) {
            throw new DeepDataAgentException("本期不提供该引擎类型：当前值=" + raw
                    + "，本期仅支持 " + RagEngineType.DOCUMENT_ENGINE.name());
        }
    }
}
