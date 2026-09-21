package com.linkroa.deepdataagent.knowledgebase.application.validation;

import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.DocumentChunkMode;
import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * 分块策略配置应用级校验器（库级与文档级两条写入口共用，仅校验不回写原文）。
 * <p>复用面：①库级——{@code knowledge_base.rag_engine_config} 的 {@code chunkStrategy} 子节点，
 * 由 {@link RagEngineConfigValidator#validate(String)} 在同一入口内委托；②文档级——
 * {@code document.chunk_strategy} 的上传策略与重解析覆盖值，由
 * {@code DocumentApplicationService} 在写库前直调 {@link #validate(String)}。</p>
 * <p>策略 JSON 形态：{@code {"chunkMode":"GENERAL","modeConfig":{"params":{...}}}}；
 * 兼容「策略 JSON 被再包一层字符串」的存量形态（字符串节点自动解包）。</p>
 * <p>拒绝式规则（任一不通过即抛 400，错误信息含字段与合法值域，配置不落库）：</p>
 * <ul>
 *   <li>模式名：仅接受七枚举白名单 {@code GENERAL/QA/BOOK/LAWS/TABLE/PRESENTATION/ONE}
 *       （trim、大小写不敏感）；值域外（如 {@code auto} / {@code smart}）拒绝，
 *       MUST NOT 静默回退 GENERAL 后保存成功；</li>
 *   <li>参数对象：仅 GENERAL 模式可携带 {@code modeConfig.params}，非 GENERAL 携带即拒绝；</li>
 *   <li>{@code chunk_token_num}：必须落在开区间下界、闭区间上界的 (0,2048] 整数区间；</li>
 *   <li>{@code overlapped_percent}：必须落在 [0,30] 整数区间（兼容历史键 {@code overlap}）；</li>
 *   <li>{@code delimiter}：必须是非空字符串（空白字符本身是合法分隔符，仅拒绝空串与非文本类型）。</li>
 * </ul>
 * <p><strong>「未配置」与「配了非法值」的区分口径</strong>：整段策略缺失 / null / 空白、模式名缺失或空白、
 * 参数键缺失均按「未配置」放行（沿用库级引擎类型校验既有宽松 tone，实际取值由继承链与代码默认决定）；
 * 一旦显式写出该字段，其取值必须落在值域内，否则拒绝。</p>
 * <p>params 中未识别的扩展键（含历史废弃键 {@code enable_children}、{@code children_delimiter}、
 * {@code table_context_size}、{@code image_context_size}）一律忽略不报错，保证存量配置编辑保存兼容。</p>
 * <p>校验仅约束保存时点，MUST NOT 追溯性修改存量行。无外部 BC 依赖、无状态静态工具；校验须在事务开启前完成。</p>
 */
public final class ChunkStrategyConfigValidator {

    private static final Logger log = LoggerFactory.getLogger(ChunkStrategyConfigValidator.class);

    /** 分块策略 JSON 字段名：分块模式 */
    private static final String FIELD_CHUNK_MODE = "chunkMode";

    /** 分块策略 JSON 字段名：模式参数容器 */
    private static final String FIELD_MODE_CONFIG = "modeConfig";

    /** 模式参数容器字段名：参数集合 */
    private static final String FIELD_PARAMS = "params";

    /** 参数字段名：分块 token 预算 */
    private static final String FIELD_CHUNK_TOKEN_NUM = "chunk_token_num";

    /** 参数字段名：重叠比例（规范键，与 rag 域 ChunkParams 字段一致） */
    private static final String FIELD_OVERLAPPED_PERCENT = "overlapped_percent";

    /** 参数字段名：重叠比例（历史兼容键，与 rag 域 ChunkParams.fromMap 的兼容口径一致） */
    private static final String FIELD_OVERLAPPED_PERCENT_COMPAT = "overlap";

    /** 参数字段名：分隔符 */
    private static final String FIELD_DELIMITER = "delimiter";

    /** 分块 token 预算合法区间下界（开区间：0 非法） */
    private static final int CHUNK_TOKEN_NUM_MIN_EXCLUSIVE = 0;

    /** 分块 token 预算合法区间上界（闭区间） */
    private static final int CHUNK_TOKEN_NUM_MAX = 2048;

    /** 重叠比例合法区间下界（闭区间） */
    private static final int OVERLAPPED_PERCENT_MIN = 0;

    /** 重叠比例合法区间上界（闭区间） */
    private static final int OVERLAPPED_PERCENT_MAX = 30;

    /** 分块模式合法值域提示串（七枚举白名单，错误信息直接复用） */
    private static final String ALLOWED_CHUNK_MODES = Arrays.stream(DocumentChunkMode.values())
            .map(Enum::name)
            .collect(Collectors.joining("/"));

    /** 分块策略 JSON 解析器（无状态，进程内共享） */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private ChunkStrategyConfigValidator() {
    }

    /**
     * 校验分块策略 JSON 文本（文档级 {@code chunk_strategy} 写入口使用）。
     *
     * @param chunkStrategyJson 分块策略 JSON 文本，可为空白（未配置，直接通过）
     * @throws DeepDataAgentException 非法 JSON 或任一规则不通过（400）
     */
    public static void validate(String chunkStrategyJson) {
        if (StringUtils.isBlank(chunkStrategyJson)) {
            return;
        }
        validate(parseJson(chunkStrategyJson));
    }

    /**
     * 校验分块策略节点（库级 {@code rag_engine_config.chunkStrategy} 子节点复用同一套规则）。
     *
     * @param chunkStrategyNode 分块策略节点，可为缺失 / null / 字符串形态（自动解包）
     * @throws DeepDataAgentException 字符串形态内层非法 JSON、结构非法或任一规则不通过（400）
     */
    public static void validate(JsonNode chunkStrategyNode) {
        JsonNode strategy = unwrap(chunkStrategyNode);
        if (ObjectUtils.isEmpty(strategy)) {
            return;
        }
        if (!strategy.isObject()) {
            throw new DeepDataAgentException("分块策略配置（chunkStrategy）必须是JSON对象");
        }
        DocumentChunkMode chunkMode = validateChunkMode(strategy.path(FIELD_CHUNK_MODE));
        validateParams(chunkMode, strategy.path(FIELD_MODE_CONFIG).path(FIELD_PARAMS));
    }

    /**
     * 校验分块模式名：七枚举白名单（trim、大小写不敏感）。
     * <p>缺失 / null / 空白按「未配置模式」返回 {@code null} 放行（模式由继承链决定）；
     * 显式写出但值域外（如 {@code auto}）即拒绝并在错误信息中列出合法值。</p>
     *
     * @param modeNode {@code chunkMode} 节点
     * @return 命中的分块模式；未配置模式名时返回 {@code null}
     * @throws DeepDataAgentException 模式名非白名单取值（400）
     */
    private static DocumentChunkMode validateChunkMode(JsonNode modeNode) {
        if (modeNode.isMissingNode() || modeNode.isNull()) {
            return null;
        }
        String raw = modeNode.isString() ? modeNode.stringValue() : modeNode.toString();
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        String normalized = StringUtils.upperCase(StringUtils.trim(raw), Locale.ROOT);
        return Arrays.stream(DocumentChunkMode.values())
                .filter(candidate -> normalized.equals(candidate.name()))
                .findFirst()
                .orElseThrow(() -> new DeepDataAgentException("分块模式（chunkStrategy.chunkMode）非法：合法值为 "
                        + ALLOWED_CHUNK_MODES + "，当前值=" + raw));
    }

    /**
     * 校验模式参数集合：先判「非 GENERAL 不得携带 params」，再对已出现的已知键做值域强校验。
     * <p>模式名未配置时无法判定参数归属，跳过携带性判定仅校验值域（值域非法在任意模式下都非法）。</p>
     *
     * @param chunkMode  已校验的分块模式，可为 {@code null}（未配置模式）
     * @param paramsNode {@code modeConfig.params} 节点
     * @throws DeepDataAgentException 非 GENERAL 携带 params、params 结构非法或已知键值域非法（400）
     */
    private static void validateParams(DocumentChunkMode chunkMode, JsonNode paramsNode) {
        if (paramsNode.isMissingNode() || paramsNode.isNull()) {
            return;
        }
        if (ObjectUtils.isNotEmpty(chunkMode) && chunkMode != DocumentChunkMode.GENERAL) {
            throw new DeepDataAgentException("仅 GENERAL 模式支持分块参数配置（chunkStrategy.modeConfig.params），"
                    + "当前模式=" + chunkMode.name());
        }
        if (!paramsNode.isObject()) {
            throw new DeepDataAgentException("分块参数（chunkStrategy.modeConfig.params）必须是JSON对象");
        }
        validateChunkTokenNum(paramsNode.path(FIELD_CHUNK_TOKEN_NUM));
        validateOverlappedPercent(paramsNode);
        validateDelimiter(paramsNode.path(FIELD_DELIMITER));
    }

    /**
     * 校验分块 token 预算：提供时必须为 (0,2048] 的整数（0、负数、小数、超上限一律拒绝，
     * MUST NOT 以默认值 512 替换后放行）。
     *
     * @param node {@code chunk_token_num} 节点
     * @throws DeepDataAgentException 取值非法（400）
     */
    private static void validateChunkTokenNum(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return;
        }
        if (!node.isIntegralNumber() || !node.canConvertToInt() || node.intValue() <= CHUNK_TOKEN_NUM_MIN_EXCLUSIVE
                || node.intValue() > CHUNK_TOKEN_NUM_MAX) {
            throw new DeepDataAgentException("分块token预算（chunkStrategy.modeConfig.params.chunk_token_num）"
                    + "必须是 (" + CHUNK_TOKEN_NUM_MIN_EXCLUSIVE + "," + CHUNK_TOKEN_NUM_MAX + "] 的整数，当前值=" + node);
        }
    }

    /**
     * 校验重叠比例：提供时必须为 [0,30] 的整数（范围外与小数拒绝）；规范键缺失时兼容读取历史键 {@code overlap}。
     *
     * @param paramsNode {@code modeConfig.params} 节点
     * @throws DeepDataAgentException 取值非法（400）
     */
    private static void validateOverlappedPercent(JsonNode paramsNode) {
        JsonNode percentNode = paramsNode.path(FIELD_OVERLAPPED_PERCENT);
        if (percentNode.isMissingNode() || percentNode.isNull()) {
            percentNode = paramsNode.path(FIELD_OVERLAPPED_PERCENT_COMPAT);
        }
        if (percentNode.isMissingNode() || percentNode.isNull()) {
            return;
        }
        if (!percentNode.isIntegralNumber() || !percentNode.canConvertToInt()
                || percentNode.intValue() < OVERLAPPED_PERCENT_MIN || percentNode.intValue() > OVERLAPPED_PERCENT_MAX) {
            throw new DeepDataAgentException("重叠比例（chunkStrategy.modeConfig.params.overlapped_percent）"
                    + "必须是 [" + OVERLAPPED_PERCENT_MIN + "," + OVERLAPPED_PERCENT_MAX + "] 的整数，当前值=" + percentNode);
        }
    }

    /**
     * 校验分隔符：提供时必须为非空字符串。
     * <p>空白字符（空格 / 换行 / 制表）本身是合法分隔符，故只拒绝空串与非文本类型取值。</p>
     *
     * @param node {@code delimiter} 节点
     * @throws DeepDataAgentException 空串或非文本类型（400）
     */
    private static void validateDelimiter(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return;
        }
        String delimiter = node.isString() ? node.stringValue() : null;
        if (StringUtils.isEmpty(delimiter)) {
            throw new DeepDataAgentException("分隔符（chunkStrategy.modeConfig.params.delimiter）不能为空，当前值=" + node);
        }
    }

    /**
     * 归一分块策略节点：字符串形态（策略 JSON 被再包一层引号）解包为内嵌 JSON；空白视为未配置。
     *
     * @param strategyNode 分块策略节点，可为 {@code null}
     * @return 待校验的策略对象节点；未配置时返回 {@code null}
     * @throws DeepDataAgentException 字符串形态内层不是合法 JSON（400）
     */
    private static JsonNode unwrap(JsonNode strategyNode) {
        if (ObjectUtils.isEmpty(strategyNode) || strategyNode.isMissingNode() || strategyNode.isNull()) {
            return null;
        }
        if (!strategyNode.isString()) {
            return strategyNode;
        }
        String raw = strategyNode.stringValue();
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        return parseJson(raw);
    }

    /**
     * 解析 JSON 文本为节点，非法 JSON 在写入口前置拒绝（数据损坏不得进入落库流程）。
     *
     * @param json JSON 文本，非空白
     * @return 解析后的节点
     * @throws DeepDataAgentException 非法 JSON（400）
     */
    private static JsonNode parseJson(String json) {
        try {
            return OBJECT_MAPPER.readTree(json);
        } catch (JacksonException e) {
            log.error("分块策略配置不是合法JSON，原始值={}", json, e);
            throw new DeepDataAgentException("分块策略配置（chunkStrategy）不是合法的JSON");
        }
    }
}
