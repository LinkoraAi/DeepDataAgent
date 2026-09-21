package com.linkroa.deepdataagent.knowledgebase.application.validation;

import com.linkroa.deepdataagent.knowledgebase.application.contract.RetrievalConfigDTO;
import com.linkroa.deepdataagent.knowledgebase.domain.model.FusionStrategyConfig;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.FusionStrategyType;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.RetrievalChannel;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.RetrievalStrategyType;
import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.Locale;

/**
 * 检索配置应用级校验器（写入口共用：REST 更新检索配置；另由保留能力 saveRetrievalConfig 复用）。
 * <p>校验规则（任一失败即抛 400，指明具体字段与允许区间，配置不落库）：</p>
 * <ul>
 *   <li>strategyType 必填，仅支持 NAIVE / MIX（大小写不敏感，回写为枚举名）；</li>
 *   <li>resultChunkCount 取值 1~{@value RetrievalConfigDTO#RESULT_CHUNK_COUNT_MAX}；</li>
 *   <li>similarThreshold 取值 0~{@value RetrievalConfigDTO#SIMILAR_THRESHOLD_MAX}；</li>
 *   <li>rerankConfig 仅做结构校验：enabled=true 时必须提供非空 modelProfileId，
 *       MUST NOT 查询模型注册表（未注册标识可落库）；</li>
 *   <li>fusionConfig：fusionType 仅支持 RRF / WEIGHTED_SUM（回写为枚举名）；
 *       RRF 时 rrfK 必须大于 0，缺省回写默认值 60；
 *       WEIGHTED_SUM 时各通道稠密权重必须落在开区间 (0,1)。</li>
 * </ul>
 * <p>无外部 BC 依赖、无状态静态工具；校验须在事务开启前完成。</p>
 */
public final class RetrievalConfigValidator {

    private static final Logger log = LoggerFactory.getLogger(RetrievalConfigValidator.class);

    /** 检索策略 JSON 字段名：策略类型 */
    public static final String FIELD_STRATEGY_TYPE = "strategyType";

    /** 检索策略 JSON 字段名：融合配置 */
    public static final String FIELD_FUSION_CONFIG = "fusionConfig";

    /** 结果返回数量下限 */
    private static final int RESULT_CHUNK_COUNT_MIN = 1;

    /** 相似度阈值下限 */
    private static final float SIMILAR_THRESHOLD_MIN = 0F;

    /** 融合配置字段名：融合类型 */
    private static final String FIELD_FUSION_TYPE = "fusionType";

    /** 融合配置字段名：RRF 常数 k */
    private static final String FIELD_RRF_K = "rrfK";

    /** 融合配置字段名：各通道稠密权重 */
    private static final String FIELD_CHANNEL_DENSE_WEIGHT = "channelDenseWeight";

    /** 重排配置字段名：是否启用 */
    private static final String FIELD_ENABLED = "enabled";

    /** 重排配置字段名：模型引用标识 */
    private static final String FIELD_MODEL_PROFILE_ID = "modelProfileId";

    /** 检索策略 JSON 字段名：结果返回数量 */
    private static final String FIELD_RESULT_CHUNK_COUNT = "resultChunkCount";

    /** 检索策略 JSON 字段名：相似度阈值 */
    private static final String FIELD_SIMILAR_THRESHOLD = "similarThreshold";

    /** 检索策略 JSON 字段名：重排配置 */
    private static final String FIELD_RERANK_CONFIG = "rerankConfig";

    /** 检索策略 JSON 解析器（无状态，进程内共享） */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private RetrievalConfigValidator() {
    }

    /**
     * 校验并规范化检索策略配置 JSON（REST 入口使用）。
     *
     * @param strategyJson 检索策略配置 JSON 文本，必填
     * @return 规范化后的配置 JSON 文本（策略类型/融合类型回写为枚举名，RRF 缺省 rrfK 回写 60）
     * @throws DeepDataAgentException 配置为空、非法 JSON 或任一规则不通过（400）
     */
    public static String validateAndNormalize(String strategyJson) {
        if (StringUtils.isBlank(strategyJson)) {
            throw new DeepDataAgentException("检索策略配置不能为空");
        }
        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(strategyJson);
        } catch (JacksonException e) {
            log.error("检索策略配置不是合法JSON，原始值={}", strategyJson, e);
            throw new DeepDataAgentException("检索策略配置不是合法的JSON");
        }
        if (!root.isObject()) {
            throw new DeepDataAgentException("检索策略配置必须是JSON对象");
        }
        return validateAndNormalize((ObjectNode) root).toString();
    }

    /**
     * 校验并规范化检索策略配置节点（契约入口构建完成后复用同一套规则）。
     *
     * @param strategy 检索策略配置节点，原地回写规范化字段
     * @return 同一节点（已完成校验与回写）
     * @throws DeepDataAgentException 任一规则不通过（400）
     */
    public static ObjectNode validateAndNormalize(ObjectNode strategy) {
        normalizeStrategyType(strategy);
        validateResultChunkCount(strategy);
        validateSimilarThreshold(strategy);
        validateRerankConfig(strategy);
        normalizeAndValidateFusionConfig(strategy);
        return strategy;
    }

    /**
     * 校验并回写策略类型：仅支持 NAIVE / MIX（大小写不敏感）。
     */
    private static void normalizeStrategyType(ObjectNode strategy) {
        JsonNode typeNode = strategy.path(FIELD_STRATEGY_TYPE);
        String raw = typeNode.isTextual() ? typeNode.stringValue() : null;
        String normalized = StringUtils.trimToEmpty(raw).toUpperCase(Locale.ROOT);
        for (RetrievalStrategyType candidate : RetrievalStrategyType.values()) {
            if (StringUtils.equals(candidate.name(), normalized)) {
                strategy.put(FIELD_STRATEGY_TYPE, candidate.name());
                return;
            }
        }
        throw new DeepDataAgentException("检索策略类型非法：仅支持naive与mix，当前值=" + raw);
    }

    /**
     * 校验结果返回数量：提供时必须为整数且落在 [1, 100]。
     */
    private static void validateResultChunkCount(ObjectNode strategy) {
        JsonNode countNode = strategy.path(FIELD_RESULT_CHUNK_COUNT);
        if (countNode.isMissingNode() || countNode.isNull()) {
            return;
        }
        if (!countNode.isNumber() || countNode.intValue() < RESULT_CHUNK_COUNT_MIN
                || countNode.intValue() > RetrievalConfigDTO.RESULT_CHUNK_COUNT_MAX) {
            throw new DeepDataAgentException("结果返回数量（resultChunkCount）必须在"
                    + RESULT_CHUNK_COUNT_MIN + "到" + RetrievalConfigDTO.RESULT_CHUNK_COUNT_MAX + "之间");
        }
    }

    /**
     * 校验相似度阈值：提供时必须为数值且落在 [0, 1]。
     */
    private static void validateSimilarThreshold(ObjectNode strategy) {
        JsonNode thresholdNode = strategy.path(FIELD_SIMILAR_THRESHOLD);
        if (thresholdNode.isMissingNode() || thresholdNode.isNull()) {
            return;
        }
        if (!thresholdNode.isNumber() || thresholdNode.doubleValue() < SIMILAR_THRESHOLD_MIN
                || thresholdNode.doubleValue() > RetrievalConfigDTO.SIMILAR_THRESHOLD_MAX) {
            throw new DeepDataAgentException("相似度阈值（similarThreshold）必须在"
                    + SIMILAR_THRESHOLD_MIN + "到" + RetrievalConfigDTO.SIMILAR_THRESHOLD_MAX + "之间");
        }
    }

    /**
     * 重排配置结构校验：仅检查 rerank.enabled=true 时必须携带非空模型引用标识，不做注册表查询。
     */
    private static void validateRerankConfig(ObjectNode strategy) {
        JsonNode rerankNode = strategy.path(FIELD_RERANK_CONFIG);
        if (rerankNode.isMissingNode() || rerankNode.isNull()) {
            return;
        }
        if (!rerankNode.isObject()) {
            throw new DeepDataAgentException("重排配置（rerankConfig）必须是JSON对象");
        }
        boolean enabled = rerankNode.path(FIELD_ENABLED).isBoolean() && rerankNode.path(FIELD_ENABLED).booleanValue();
        if (enabled && StringUtils.isBlank(rerankNode.path(FIELD_MODEL_PROFILE_ID).stringValue(null))) {
            throw new DeepDataAgentException("重排开启时必须提供重排模型引用标识（rerankConfig.modelProfileId）");
        }
    }

    /**
     * 融合配置校验与回写：fusionType 归一化；RRF 校验 rrfK&gt;0 并缺省回写 60；
     * WEIGHTED_SUM 校验各通道稠密权重落在开区间 (0,1)。
     */
    private static void normalizeAndValidateFusionConfig(ObjectNode strategy) {
        JsonNode fusionNode = strategy.path(FIELD_FUSION_CONFIG);
        if (fusionNode.isMissingNode() || fusionNode.isNull()) {
            return;
        }
        if (!fusionNode.isObject()) {
            throw new DeepDataAgentException("融合配置（fusionConfig）必须是JSON对象");
        }
        ObjectNode fusion = (ObjectNode) fusionNode;
        FusionStrategyType fusionType = normalizeFusionType(fusion);
        if (fusionType == FusionStrategyType.RRF) {
            validateRrfK(fusion);
        } else {
            validateChannelDenseWeights(fusion);
        }
    }

    /**
     * 校验并回写融合类型：仅支持 RRF / WEIGHTED_SUM（大小写不敏感）。
     */
    private static FusionStrategyType normalizeFusionType(ObjectNode fusion) {
        JsonNode typeNode = fusion.path(FIELD_FUSION_TYPE);
        String raw = typeNode.isTextual() ? typeNode.stringValue() : null;
        String normalized = StringUtils.trimToEmpty(raw).toUpperCase(Locale.ROOT);
        for (FusionStrategyType candidate : FusionStrategyType.values()) {
            if (StringUtils.equals(candidate.name(), normalized)) {
                fusion.put(FIELD_FUSION_TYPE, candidate.name());
                return candidate;
            }
        }
        throw new DeepDataAgentException("融合策略类型非法：仅支持RRF与WEIGHTED_SUM，当前值=" + raw);
    }

    /**
     * RRF 常数 k 校验：缺省回写默认值 60；提供时必须为大于 0 的数值。
     */
    private static void validateRrfK(ObjectNode fusion) {
        JsonNode kNode = fusion.path(FIELD_RRF_K);
        if (kNode.isMissingNode() || kNode.isNull()) {
            fusion.put(FIELD_RRF_K, FusionStrategyConfig.DEFAULT_RRF_K);
            return;
        }
        if (!kNode.isNumber() || kNode.intValue() <= 0) {
            throw new DeepDataAgentException("RRF融合的rrfK必须大于0，当前值=" + kNode);
        }
    }

    /**
     * 加权求和稠密权重校验：各已提供通道的权重必须为数值且落在开区间 (0,1)。
     */
    private static void validateChannelDenseWeights(ObjectNode fusion) {
        JsonNode weightsNode = fusion.path(FIELD_CHANNEL_DENSE_WEIGHT);
        if (weightsNode.isMissingNode() || weightsNode.isNull()) {
            return;
        }
        if (!weightsNode.isObject()) {
            throw new DeepDataAgentException("通道稠密权重（channelDenseWeight）必须是JSON对象");
        }
        for (RetrievalChannel channel : RetrievalChannel.values()) {
            JsonNode weightNode = weightsNode.path(channel.name());
            if (weightNode.isMissingNode() || weightNode.isNull()) {
                continue;
            }
            if (!weightNode.isNumber() || weightNode.doubleValue() <= 0D || weightNode.doubleValue() >= 1D) {
                throw new DeepDataAgentException("通道" + channel.name()
                        + "的稠密权重必须落在开区间(0,1)，当前值=" + weightNode);
            }
        }
    }
}
