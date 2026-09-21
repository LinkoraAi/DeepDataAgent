package com.linkroa.deepdataagent.rag.domain.service;

import com.linkroa.deepdataagent.rag.domain.model.GraphSourceFilePaths;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.Locale;
import java.util.Map;

/**
 * 图合并参数值对象（由 knowledge_base.rag_engine_config 的图合并段反序列化，缺失键取默认）。
 * <p>对应合并流程中的全部可调限额：并发闸门（{@code llm_model_max_async × 2}）、
 * source_ids 截断（{@code apply_source_ids_limit}）、来源文件路径列表截断
 * （{@code source_file_paths_limit} / {@code source_file_paths_placeholder}）、
 * 向量内容 token 上限（{@code embedding_token_limit}）与描述摘要四级分级阈值
 * （{@code summary_context_size} / {@code summary_max_tokens} /
 * {@code force_llm_summary_on_merge}）。未给出数值的项沿用 LightRAG 同名默认。</p>
 *
 * @param llmModelMaxAsync       模型最大异步并发数（实体/关系阶段共用闸门基数，闸门 = 本值 × 2）
 * @param applySourceIdsLimit    单实体/关系保留的 source chunk 数上限（超出部分按截断策略丢弃）
 * @param embeddingTokenLimit    向量内容 token 上限（超限精确截断并重编码验证）
 * @param summaryContextSize     摘要第二级直接 join 的总 token 上限
 * @param summaryMaxTokens       摘要第三级单次 LLM 摘要的总 token 上限（同时作为输出长度约束）
 * @param forceLlmSummaryOnMerge 描述条数达到该值时无论 token 大小都强制走 LLM 摘要
 * @param sourceIdsTruncation    source_ids 截断策略（"KEEP" 保留前 N 条 / "FIFO" 保留后 N 条，非法值回落 KEEP）
 * @param sourceFilePathsLimit   来源文件路径列表（filePaths）保留上限（超限保留前 N 条并追加溢出占位元素）
 * @param sourceFilePathsPlaceholder 溢出占位元素的占位词（占位元素 = 占位词 + 括号内策略与数量信息）
 * @author DeepDataAgent
 */
public record GraphMergeParams(
        int llmModelMaxAsync,
        int applySourceIdsLimit,
        int embeddingTokenLimit,
        int summaryContextSize,
        int summaryMaxTokens,
        int forceLlmSummaryOnMerge,
        String sourceIdsTruncation,
        int sourceFilePathsLimit,
        String sourceFilePathsPlaceholder
) {

    /** 模型最大异步并发数默认值（LightRAG DEFAULT_MAX_ASYNC 同名口径）。 */
    public static final int DEFAULT_LLM_MODEL_MAX_ASYNC = 4;

    /** source_ids 保留上限默认值（LightRAG source_ids_limit 同名口径）。 */
    public static final int DEFAULT_APPLY_SOURCE_IDS_LIMIT = 200;

    /** 截断策略：保留前 N 条（旧来源优先）。 */
    public static final String SOURCE_IDS_TRUNCATION_KEEP = "KEEP";

    /** 截断策略：保留后 N 条（新来源优先，先进先出淘汰旧来源）。 */
    public static final String SOURCE_IDS_TRUNCATION_FIFO = "FIFO";

    /** source_ids 截断策略默认值（保留最早的 N 条，LightRAG DEFAULT_SOURCE_IDS_TRUNCATION 同名口径）。 */
    public static final String DEFAULT_SOURCE_IDS_TRUNCATION = SOURCE_IDS_TRUNCATION_KEEP;

    /** 向量内容 token 上限默认值（兼容主流 8k 上下文 embedding 模型）。 */
    public static final int DEFAULT_EMBEDDING_TOKEN_LIMIT = 8192;

    /** 摘要直接 join 阈值默认 token 数（LightRAG SUMMARY_CONTEXT_SIZE 同名口径）。 */
    public static final int DEFAULT_SUMMARY_CONTEXT_SIZE = 12000;

    /** 单次 LLM 摘要输出上限默认 token 数（与 LightRAG DEFAULT_SUMMARY_MAX_TOKENS=1200 取值一致）。 */
    public static final int DEFAULT_SUMMARY_MAX_TOKENS = 1200;

    /** 强制 LLM 摘要的描述条数阈值默认值（与 LightRAG DEFAULT_FORCE_LLM_SUMMARY_ON_MERGE=8 取值一致）。 */
    public static final int DEFAULT_FORCE_LLM_SUMMARY_ON_MERGE = 8;

    /** 来源文件路径列表保留上限默认值（graph-source-file-paths R2 默认 75，截断口径见 {@link GraphSourceFilePaths}）。 */
    public static final int DEFAULT_SOURCE_FILE_PATHS_LIMIT = GraphSourceFilePaths.DEFAULT_LIMIT;

    /** 来源文件路径溢出占位词默认值（占位元素 = 占位词 + 括号内策略与数量信息）。 */
    public static final String DEFAULT_SOURCE_FILE_PATHS_PLACEHOLDER = GraphSourceFilePaths.DEFAULT_PLACEHOLDER_WORD;

    /**
     * 全参紧凑构造器：非正值回退默认，截断策略归一化（大小写不敏感，非法值回落 KEEP），
     * 来源文件路径上限非正值回退默认、占位词空白回退默认，
     * 防止配置错误导致闸门为 0、限额非法或截断语义不明。
     */
    public GraphMergeParams {
        llmModelMaxAsync = llmModelMaxAsync <= 0 ? DEFAULT_LLM_MODEL_MAX_ASYNC : llmModelMaxAsync;
        applySourceIdsLimit = applySourceIdsLimit <= 0 ? DEFAULT_APPLY_SOURCE_IDS_LIMIT : applySourceIdsLimit;
        embeddingTokenLimit = embeddingTokenLimit <= 0 ? DEFAULT_EMBEDDING_TOKEN_LIMIT : embeddingTokenLimit;
        summaryContextSize = summaryContextSize <= 0 ? DEFAULT_SUMMARY_CONTEXT_SIZE : summaryContextSize;
        summaryMaxTokens = summaryMaxTokens <= 0 ? DEFAULT_SUMMARY_MAX_TOKENS : summaryMaxTokens;
        forceLlmSummaryOnMerge = forceLlmSummaryOnMerge <= 0
                ? DEFAULT_FORCE_LLM_SUMMARY_ON_MERGE : forceLlmSummaryOnMerge;
        sourceIdsTruncation = normalizeTruncation(sourceIdsTruncation);
        sourceFilePathsLimit = sourceFilePathsLimit <= 0 ? DEFAULT_SOURCE_FILE_PATHS_LIMIT : sourceFilePathsLimit;
        sourceFilePathsPlaceholder = StringUtils.isBlank(sourceFilePathsPlaceholder)
                ? DEFAULT_SOURCE_FILE_PATHS_PLACEHOLDER : sourceFilePathsPlaceholder.trim();
    }

    /**
     * 默认图合并参数。
     *
     * @return 默认参数实例
     */
    public static GraphMergeParams defaults() {
        return new GraphMergeParams(
                DEFAULT_LLM_MODEL_MAX_ASYNC,
                DEFAULT_APPLY_SOURCE_IDS_LIMIT,
                DEFAULT_EMBEDDING_TOKEN_LIMIT,
                DEFAULT_SUMMARY_CONTEXT_SIZE,
                DEFAULT_SUMMARY_MAX_TOKENS,
                DEFAULT_FORCE_LLM_SUMMARY_ON_MERGE,
                DEFAULT_SOURCE_IDS_TRUNCATION,
                DEFAULT_SOURCE_FILE_PATHS_LIMIT,
                DEFAULT_SOURCE_FILE_PATHS_PLACEHOLDER
        );
    }

    /**
     * 从 JSONB 参数映射构建图合并参数（键名对齐 snake_case 字段清单，未知键忽略），
     * 缺失键回落内置默认。
     *
     * @param params 参数映射，可为空
     * @return 图合并参数实例
     */
    public static GraphMergeParams fromMap(Map<String, Object> params) {
        return fromMap(params, defaults());
    }

    /**
     * 以给定基底参数构建图合并参数（优先级：JSONB 显式键 &gt; base 基底值 &gt; 内置默认）。
     * <p>调用方通常以应用级配置（application.yaml 的 app.rag.graph.*）作为 base 传入，
     * 使知识库级 JSONB 配置可以逐项覆盖应用级配置。</p>
     *
     * @param params 参数映射，可为空（为空时直接返回 base）
     * @param base   基底参数，可为 null（为 null 时以 {@link #defaults()} 为基底）
     * @return 图合并参数实例
     */
    public static GraphMergeParams fromMap(Map<String, Object> params, GraphMergeParams base) {
        GraphMergeParams effectiveBase = ObjectUtils.isEmpty(base) ? defaults() : base;
        if (ObjectUtils.isEmpty(params)) {
            return effectiveBase;
        }
        return new GraphMergeParams(
                toInt(params.get("llm_model_max_async"), effectiveBase.llmModelMaxAsync()),
                toInt(params.get("apply_source_ids_limit"), effectiveBase.applySourceIdsLimit()),
                toInt(params.get("embedding_token_limit"), effectiveBase.embeddingTokenLimit()),
                toInt(params.get("summary_context_size"), effectiveBase.summaryContextSize()),
                toInt(params.get("summary_max_tokens"), effectiveBase.summaryMaxTokens()),
                toInt(params.get("force_llm_summary_on_merge"), effectiveBase.forceLlmSummaryOnMerge()),
                toStr(params.get("source_ids_truncation"), effectiveBase.sourceIdsTruncation()),
                toInt(params.get("source_file_paths_limit"), effectiveBase.sourceFilePathsLimit()),
                toStr(params.get("source_file_paths_placeholder"), effectiveBase.sourceFilePathsPlaceholder())
        );
    }

    /**
     * 并发闸门大小：{@code llm_model_max_async × 2}（关系阶段内部还要发 LLM 摘要故取 2 倍）。
     *
     * @return 信号量许可数
     */
    public int concurrencyGate() {
        return llmModelMaxAsync * 2;
    }

    /**
     * 数值参数解析：数字/字符串均兼容，非法值回退默认。
     */
    private static int toInt(Object value, int defaultValue) {
        if (ObjectUtils.isEmpty(value)) {
            return defaultValue;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 字符串参数解析：空白或非字符串值回退默认。
     */
    private static String toStr(Object value, String defaultValue) {
        if (ObjectUtils.isEmpty(value)) {
            return defaultValue;
        }
        String text = String.valueOf(value);
        return StringUtils.isBlank(text) ? defaultValue : text;
    }

    /**
     * 截断策略归一化：trim + 大写后仅接受 FIFO，其余（含空白 / 非法值）一律回落 KEEP。
     */
    private static String normalizeTruncation(String value) {
        if (StringUtils.isBlank(value)) {
            return SOURCE_IDS_TRUNCATION_KEEP;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return SOURCE_IDS_TRUNCATION_FIFO.equals(normalized) ? SOURCE_IDS_TRUNCATION_FIFO : SOURCE_IDS_TRUNCATION_KEEP;
    }
}
