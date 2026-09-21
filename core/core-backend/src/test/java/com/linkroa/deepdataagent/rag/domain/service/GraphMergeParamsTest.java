package com.linkroa.deepdataagent.rag.domain.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@link GraphMergeParams} 单元测试。
 *
 * <p>纯值对象离线测试，无外部依赖：覆盖内置默认值口径（source_ids 上限 200、截断策略 KEEP）、
 * 紧凑构造器非正值回退与截断策略归一化（大小写/空白不敏感、非法值回落 KEEP）、
 * {@code fromMap} 逐项「JSONB 显式键 &gt; 基底参数 &gt; 内置默认」优先级，
 * 以及并发闸门 = {@code llmModelMaxAsync × 2} 的推导。</p>
 *
 * @author DeepDataAgent
 */
public class GraphMergeParamsTest {

    /** 基底参数中的 source_ids 上限（用于区分「回落基底」与「回落内置默认」） */
    private static final int BASE_SOURCE_IDS_LIMIT = 50;

    /** 基底参数中的模型最大异步并发数 */
    private static final int BASE_LLM_MODEL_MAX_ASYNC = 3;

    /** 非法截断策略取值（构造器应回落 KEEP） */
    private static final String ILLEGAL_TRUNCATION = "LIFO";

    /**
     * 以指定上限与截断策略构造基底参数，其余取内置默认。
     *
     * @param applySourceIdsLimit source_ids 保留上限
     * @param sourceIdsTruncation 截断策略
     * @return 基底参数实例
     */
    private static GraphMergeParams baseWith(int applySourceIdsLimit, String sourceIdsTruncation) {
        return new GraphMergeParams(BASE_LLM_MODEL_MAX_ASYNC, applySourceIdsLimit,
                GraphMergeParams.DEFAULT_EMBEDDING_TOKEN_LIMIT, GraphMergeParams.DEFAULT_SUMMARY_CONTEXT_SIZE,
                GraphMergeParams.DEFAULT_SUMMARY_MAX_TOKENS, GraphMergeParams.DEFAULT_FORCE_LLM_SUMMARY_ON_MERGE,
                sourceIdsTruncation, GraphMergeParams.DEFAULT_SOURCE_FILE_PATHS_LIMIT,
                GraphMergeParams.DEFAULT_SOURCE_FILE_PATHS_PLACEHOLDER);
    }

    /**
     * 内置默认值口径：source_ids 上限已从 10 放宽至 200，截断策略默认 KEEP。
     */
    @Test
    public void should_useRelaxedDefaults_when_defaults_given_noConfiguration() {
        // given：无配置来源

        // when：取内置默认参数
        GraphMergeParams params = GraphMergeParams.defaults();

        // then：上限 200、策略 KEEP、闸门为并发数两倍
        assertEquals(200, GraphMergeParams.DEFAULT_APPLY_SOURCE_IDS_LIMIT, "默认上限应为 200（原 10 已放宽）");
        assertEquals(200, params.applySourceIdsLimit(), "默认参数应携带放宽后的上限");
        assertEquals(GraphMergeParams.SOURCE_IDS_TRUNCATION_KEEP, params.sourceIdsTruncation(),
                "默认截断策略应为 KEEP");
        assertEquals(GraphMergeParams.DEFAULT_SOURCE_IDS_TRUNCATION, params.sourceIdsTruncation(),
                "默认策略常量应与 KEEP 口径一致");
        assertEquals(GraphMergeParams.DEFAULT_LLM_MODEL_MAX_ASYNC * 2, params.concurrencyGate(),
                "并发闸门应为并发基数两倍");
    }

    /**
     * 紧凑构造器归一：非正值逐项回退内置默认（合法正值原样保留）。
     */
    @Test
    public void should_fallbackToBuiltinDefaults_when_Constructor_given_nonPositiveValues() {
        // given：全 0/负数/空白入参（含来源文件路径上限与占位词）
        GraphMergeParams params = new GraphMergeParams(0, -1, -100, 0, -5, 0,
                GraphMergeParams.SOURCE_IDS_TRUNCATION_FIFO, 0, "   ");

        // when & then：逐项回退默认，截断策略不受数值回退影响
        assertEquals(GraphMergeParams.DEFAULT_LLM_MODEL_MAX_ASYNC, params.llmModelMaxAsync(),
                "非正并发数应回退默认");
        assertEquals(GraphMergeParams.DEFAULT_APPLY_SOURCE_IDS_LIMIT, params.applySourceIdsLimit(),
                "非正上限应回退默认 200");
        assertEquals(GraphMergeParams.DEFAULT_EMBEDDING_TOKEN_LIMIT, params.embeddingTokenLimit(),
                "非正 token 上限应回退默认");
        assertEquals(GraphMergeParams.DEFAULT_SUMMARY_CONTEXT_SIZE, params.summaryContextSize(),
                "非正摘要上下文应回退默认");
        assertEquals(GraphMergeParams.DEFAULT_SUMMARY_MAX_TOKENS, params.summaryMaxTokens(),
                "非正摘要输出上限应回退默认");
        assertEquals(GraphMergeParams.DEFAULT_FORCE_LLM_SUMMARY_ON_MERGE, params.forceLlmSummaryOnMerge(),
                "非正强制摘要阈值应回退默认");
        assertEquals(GraphMergeParams.SOURCE_IDS_TRUNCATION_FIFO, params.sourceIdsTruncation(),
                "合法截断策略应原样保留");
        assertEquals(GraphMergeParams.DEFAULT_SOURCE_FILE_PATHS_LIMIT, params.sourceFilePathsLimit(),
                "非正来源路径上限应回退默认 75");
        assertEquals(GraphMergeParams.DEFAULT_SOURCE_FILE_PATHS_PLACEHOLDER, params.sourceFilePathsPlaceholder(),
                "空白占位词应回退默认");
    }

    /**
     * 来源文件路径默认口径（graph-source-file-paths R2）：上限默认 75、占位词默认「…等」；
     * 合法正值与占位词 trim 后原样保留。
     */
    @Test
    public void should_useFilePathsDefaults_when_defaults_given_noConfiguration() {
        // given：无配置来源

        // when：取内置默认参数
        GraphMergeParams params = GraphMergeParams.defaults();

        // then：默认 75 与默认占位词
        assertEquals(75, GraphMergeParams.DEFAULT_SOURCE_FILE_PATHS_LIMIT,
                "来源文件路径上限默认应为 75（spec R2）");
        assertEquals(GraphMergeParams.DEFAULT_SOURCE_FILE_PATHS_LIMIT, params.sourceFilePathsLimit());
        assertEquals("…等", GraphMergeParams.DEFAULT_SOURCE_FILE_PATHS_PLACEHOLDER, "默认占位词应为「…等」");
        assertEquals(GraphMergeParams.DEFAULT_SOURCE_FILE_PATHS_PLACEHOLDER, params.sourceFilePathsPlaceholder());

        // given：合法正值与带首尾空白的自定义占位词
        GraphMergeParams custom = new GraphMergeParams(
                GraphMergeParams.DEFAULT_LLM_MODEL_MAX_ASYNC, GraphMergeParams.DEFAULT_APPLY_SOURCE_IDS_LIMIT,
                GraphMergeParams.DEFAULT_EMBEDDING_TOKEN_LIMIT, GraphMergeParams.DEFAULT_SUMMARY_CONTEXT_SIZE,
                GraphMergeParams.DEFAULT_SUMMARY_MAX_TOKENS, GraphMergeParams.DEFAULT_FORCE_LLM_SUMMARY_ON_MERGE,
                GraphMergeParams.DEFAULT_SOURCE_IDS_TRUNCATION, 10, " 其余 ");

        // when & then：上限原样保留、占位词 trim
        assertEquals(10, custom.sourceFilePathsLimit(), "合法上限应原样保留");
        assertEquals("其余", custom.sourceFilePathsPlaceholder(), "占位词应 trim 后保留");
    }

    /**
     * fromMap 来源文件路径键（spec 5.3 库级覆盖）：{@code source_file_paths_limit} 与
     * {@code source_file_paths_placeholder} 显式键覆盖基底，缺失键回落基底/默认。
     */
    @Test
    public void should_overrideFilePathsKeys_when_fromMap_given_jsonbKeys() {
        // given：库级 JSONB 显式给出两项来源路径参数
        Map<String, Object> config = Map.of(
                "source_file_paths_limit", 5,
                "source_file_paths_placeholder", "其余文件");

        // when：以内置默认为基底反序列化
        GraphMergeParams params = GraphMergeParams.fromMap(config);

        // then：显式键逐项覆盖
        assertEquals(5, params.sourceFilePathsLimit(), "JSONB 应可覆盖来源路径上限");
        assertEquals("其余文件", params.sourceFilePathsPlaceholder(), "JSONB 应可覆盖溢出占位词");

        // given：仅给出占位词，未给出上限
        Map<String, Object> partial = Map.of("source_file_paths_placeholder", "其他");

        // when & then：上限回落基底默认
        GraphMergeParams partialParams = GraphMergeParams.fromMap(partial,
                baseWith(BASE_SOURCE_IDS_LIMIT, GraphMergeParams.SOURCE_IDS_TRUNCATION_KEEP));
        assertEquals(GraphMergeParams.DEFAULT_SOURCE_FILE_PATHS_LIMIT, partialParams.sourceFilePathsLimit(),
                "缺失键应回落基底的来源路径上限");
        assertEquals("其他", partialParams.sourceFilePathsPlaceholder(), "显式占位词键应生效");
    }

    /**
     * 截断策略归一：大小写与首尾空白不敏感，仅 FIFO 被识别。
     */
    @Test
    public void should_normalizeToFifo_when_Constructor_given_mixedCaseWithSurroundingSpaces() {
        // given：小写且带空白的 FIFO

        // when：构造参数
        GraphMergeParams params = baseWith(BASE_SOURCE_IDS_LIMIT, "  fifo  ");

        // then：归一为标准 FIFO 字面量
        assertEquals(GraphMergeParams.SOURCE_IDS_TRUNCATION_FIFO, params.sourceIdsTruncation(),
                "截断策略应 trim 并大写归一");
    }

    /**
     * 截断策略非法值与空值一律回落 KEEP（不因错误配置产生未知语义）。
     */
    @Test
    public void should_fallbackToKeep_when_Constructor_given_illegalOrBlankTruncation() {
        // given：三种非法入参
        GraphMergeParams illegal = baseWith(BASE_SOURCE_IDS_LIMIT, ILLEGAL_TRUNCATION);
        GraphMergeParams blank = baseWith(BASE_SOURCE_IDS_LIMIT, "   ");
        GraphMergeParams nullValue = baseWith(BASE_SOURCE_IDS_LIMIT, null);

        // when & then：全部回落 KEEP
        assertEquals(GraphMergeParams.SOURCE_IDS_TRUNCATION_KEEP, illegal.sourceIdsTruncation(),
                "非法策略应回落 KEEP");
        assertEquals(GraphMergeParams.SOURCE_IDS_TRUNCATION_KEEP, blank.sourceIdsTruncation(),
                "空白策略应回落 KEEP");
        assertEquals(GraphMergeParams.SOURCE_IDS_TRUNCATION_KEEP, nullValue.sourceIdsTruncation(),
                "null 策略应回落 KEEP");
    }

    /**
     * 单参 fromMap：读取 source_ids_truncation 键，缺失键回落内置默认。
     */
    @Test
    public void should_readTruncationKey_when_fromMap_given_fifoAndMissingLimit() {
        // given：仅给出截断策略，未给出上限
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("source_ids_truncation", "fifo");

        // when：以内置默认为基底反序列化
        GraphMergeParams params = GraphMergeParams.fromMap(config);

        // then：策略生效（大小写归一），上限仍取内置默认
        assertEquals(GraphMergeParams.SOURCE_IDS_TRUNCATION_FIFO, params.sourceIdsTruncation(),
                "JSONB 的 source_ids_truncation 键应被读取并归一");
        assertEquals(GraphMergeParams.DEFAULT_APPLY_SOURCE_IDS_LIMIT, params.applySourceIdsLimit(),
                "缺失键应回落内置默认上限");
    }

    /**
     * 双参 fromMap 优先级：JSONB 显式键覆盖基底，未出现的键保留基底值。
     */
    @Test
    public void should_keepBaseValue_when_fromMap_given_partialJsonbOverAppBase() {
        // given：基底为应用级配置（上限 50 + FIFO），JSONB 仅覆盖上限
        GraphMergeParams appBase = baseWith(BASE_SOURCE_IDS_LIMIT, GraphMergeParams.SOURCE_IDS_TRUNCATION_FIFO);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("apply_source_ids_limit", 5);

        // when：以应用级参数为基底反序列化库级配置
        GraphMergeParams params = GraphMergeParams.fromMap(config, appBase);

        // then：显式键取库级值，未出现键沿用基底值
        assertEquals(5, params.applySourceIdsLimit(), "JSONB 显式键应覆盖基底上限");
        assertEquals(GraphMergeParams.SOURCE_IDS_TRUNCATION_FIFO, params.sourceIdsTruncation(),
                "JSONB 未给出策略时应沿用基底策略");
        assertEquals(BASE_LLM_MODEL_MAX_ASYNC, params.llmModelMaxAsync(), "JSONB 未给出的并发数应沿用基底");
    }

    /**
     * 基底为 null 时回落内置默认；空映射时直接返回基底实例。
     */
    @Test
    public void should_returnBaseInstance_when_fromMap_given_emptyParamsAndBase() {
        // given：空映射与非默认基底
        GraphMergeParams appBase = baseWith(BASE_SOURCE_IDS_LIMIT, GraphMergeParams.SOURCE_IDS_TRUNCATION_FIFO);

        // when：分别以非空基底与 null 基底反序列化
        GraphMergeParams withBase = GraphMergeParams.fromMap(Map.of(), appBase);
        GraphMergeParams nullBase = GraphMergeParams.fromMap(Map.of(), null);

        // then：空映射直接返回基底实例；null 基底回落内置默认
        assertSame(appBase, withBase, "空映射应原样返回基底实例");
        assertEquals(GraphMergeParams.defaults(), nullBase, "null 基底应回落内置默认参数");
    }

    /**
     * 摘要触发默认值对齐上游（spec description-summary-trigger）：
     * force_llm_summary_on_merge=8、summary_max_tokens=1200、summary_context_size=12000。
     */
    @Test
    public void should_useUpstreamSummaryDefaults_when_defaults_given_noConfiguration() {
        // given：无配置来源

        // when：取内置默认参数
        GraphMergeParams params = GraphMergeParams.defaults();

        // then：三项摘要默认值与上游 LightRAG 取值一致（8 / 1200 / 12000）
        assertEquals(8, GraphMergeParams.DEFAULT_FORCE_LLM_SUMMARY_ON_MERGE,
                "强制摘要条数阈值默认应为 8（原 200 已对齐上游）");
        assertEquals(1200, GraphMergeParams.DEFAULT_SUMMARY_MAX_TOKENS,
                "摘要输出上限默认应为 1200（原 500 已对齐上游）");
        assertEquals(12000, GraphMergeParams.DEFAULT_SUMMARY_CONTEXT_SIZE,
                "直接拼接总 token 上限默认应为 12000");
        assertEquals(8, params.forceLlmSummaryOnMerge());
        assertEquals(1200, params.summaryMaxTokens());
        assertEquals(12000, params.summaryContextSize());
    }

    /**
     * 知识库级 JSONB 逐项覆盖摘要阈值仍生效（覆盖通道不因默认值调整而退化）。
     */
    @Test
    public void should_overrideSummaryThresholds_when_fromMap_given_jsonbKeys() {
        // given：库级 JSONB 显式给出三项摘要阈值
        Map<String, Object> config = Map.of(
                "force_llm_summary_on_merge", 3,
                "summary_max_tokens", 999,
                "summary_context_size", 2000);

        // when：以内置默认为基底反序列化
        GraphMergeParams params = GraphMergeParams.fromMap(config);

        // then：显式键逐项覆盖默认
        assertEquals(3, params.forceLlmSummaryOnMerge(), "JSONB 应可覆盖强制摘要条数阈值");
        assertEquals(999, params.summaryMaxTokens(), "JSONB 应可覆盖摘要输出上限");
        assertEquals(2000, params.summaryContextSize(), "JSONB 应可覆盖拼接上限");

        // given：非法值（非正数）
        Map<String, Object> illegal = Map.of(
                "force_llm_summary_on_merge", 0,
                "summary_max_tokens", -9);

        // when & then：非法值归一化回默认
        GraphMergeParams normalized = GraphMergeParams.fromMap(illegal);
        assertEquals(GraphMergeParams.DEFAULT_FORCE_LLM_SUMMARY_ON_MERGE,
                normalized.forceLlmSummaryOnMerge(), "非正强制摘要阈值应归一回默认 8");
        assertEquals(GraphMergeParams.DEFAULT_SUMMARY_MAX_TOKENS,
                normalized.summaryMaxTokens(), "非正摘要输出上限应归一回默认 1200");
    }

    /**
     * 非法数值兼容：字符串数字可解析，非数字回落基底，非正值回落内置默认。
     */
    @Test
    public void should_parseStringNumberAndFallback_when_fromMap_given_irregularNumericValues() {
        // given：字符串数字 + 非数字串 + 非正值
        GraphMergeParams appBase = baseWith(BASE_SOURCE_IDS_LIMIT, GraphMergeParams.SOURCE_IDS_TRUNCATION_KEEP);
        Map<String, Object> stringNumber = Map.of("apply_source_ids_limit", " 30 ");
        Map<String, Object> garbage = Map.of("apply_source_ids_limit", "abc");
        Map<String, Object> negative = Map.of("apply_source_ids_limit", -7);

        // when：三份映射分别反序列化
        GraphMergeParams parsed = GraphMergeParams.fromMap(stringNumber, appBase);
        GraphMergeParams fallbackToBase = GraphMergeParams.fromMap(garbage, appBase);
        GraphMergeParams fallbackToDefault = GraphMergeParams.fromMap(negative, appBase);

        // then：字符串数字生效，非数字回落基底，非正值经构造器回退内置默认
        assertEquals(30, parsed.applySourceIdsLimit(), "字符串数字应可解析");
        assertEquals(BASE_SOURCE_IDS_LIMIT, fallbackToBase.applySourceIdsLimit(), "非数字值应回落基底");
        assertEquals(GraphMergeParams.DEFAULT_APPLY_SOURCE_IDS_LIMIT, fallbackToDefault.applySourceIdsLimit(),
                "非正值应经构造器回退内置默认");
    }
}
