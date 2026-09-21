package com.linkroa.deepdataagent.rag.domain.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ChunkParams} 参数契约单元测试。
 * <p>锁定四项语义：①合法值（token ∈ (0,2048]、overlap ∈ [0,30]、delimiter 非空白）原样生效、
 * 分块层不改写合法输入；②非法值（0 值 / 越界 / 小数 / 空白分隔符）走防御兜底并输出 WARN；
 * ③{@code fromMap} 只认三键，存量废弃键（{@code enable_children} 等）忽略不消费、不报错；
 * ④{@code defaults()} 取兜底分隔符保证 GENERAL 默认能按换行与句界合并。</p>
 * <p>纯值对象直测，无外部依赖；WARN 断言用 Logback ListAppender 临时挂载捕获日志事件。</p>
 */
class ChunkParamsTest {

    /** JSON 解析器（模拟策略 JSON → 参数映射的真实装配路径）。 */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 合法 token 预算样例。 */
    private static final int VALID_TOKEN_NUM = 1024;

    /** 合法重叠比例样例。 */
    private static final int VALID_OVERLAP = 15;

    /**
     * 场景：合法参数构造（token=1024、overlap=15、delimiter 非空白）。
     * 预期：三字段原样生效、零改写、零 WARN。
     */
    @Test
    void should_keepValuesUnchanged_when_construct_given_validParams() {
        // given / when
        ChunkParams params = new ChunkParams(VALID_TOKEN_NUM, "\n", VALID_OVERLAP);

        // then
        assertEquals(VALID_TOKEN_NUM, params.chunkTokenNum());
        assertEquals("\n", params.delimiter());
        assertEquals(VALID_OVERLAP, params.overlappedPercent());
    }

    /**
     * 场景：token 预算为 0（保存侧应拒绝，此处构造脏数据）。
     * 预期：回默认 512 并输出一行 WARN。
     */
    @Test
    void should_fallbackToDefaultToken_when_construct_given_zeroTokenNum() {
        List<ILoggingEvent> warnings = captureWarnings(
                () -> assertEquals(ChunkParams.DEFAULT_CHUNK_TOKEN_NUM,
                        new ChunkParams(0, "\n", 0).chunkTokenNum()));

        assertFalse(warnings.isEmpty(), "非法 token 预算兜底必须 WARN");
    }

    /**
     * 场景：token 预算越上界（2049 与负数）。
     * 预期：均回默认 512，各输出一行 WARN。
     */
    @Test
    void should_fallbackToDefaultToken_when_construct_given_tokenOutOfRange() {
        // given / when / then
        assertEquals(ChunkParams.DEFAULT_CHUNK_TOKEN_NUM,
                new ChunkParams(ChunkParams.CHUNK_TOKEN_NUM_MAX + 1, "\n", 0).chunkTokenNum());
        assertEquals(ChunkParams.DEFAULT_CHUNK_TOKEN_NUM,
                new ChunkParams(-8, "\n", 0).chunkTokenNum());
    }

    /**
     * 场景：重叠比例越界（45 与 -1）。
     * 预期：分别 clamp 到上界 30 与下界 0（防御兜底），不改写合法值。
     */
    @Test
    void should_clampOverlapToBoundary_when_construct_given_overlapOutOfRange() {
        // given / when
        ChunkParams tooHigh = new ChunkParams(512, "\n", 45);
        ChunkParams tooLow = new ChunkParams(512, "\n", -1);

        // then
        assertEquals(ChunkParams.OVERLAP_MAX, tooHigh.overlappedPercent());
        assertEquals(ChunkParams.OVERLAP_MIN, tooLow.overlappedPercent());
        assertEquals(512, tooHigh.chunkTokenNum(), "clamp 只作用于越界字段");
    }

    /**
     * 场景：分隔符字段为 null 与空串（契约「非空」的两种缺失形态）。
     * 预期：均落代码兜底分隔符 {@link ChunkParams#FALLBACK_DELIMITER}。
     */
    @Test
    void should_useFallbackDelimiter_when_construct_given_nullOrEmptyDelimiter() {
        // given / when
        ChunkParams fromNull = new ChunkParams(512, null, 0);
        ChunkParams fromEmpty = new ChunkParams(512, "", 0);

        // then
        assertEquals(ChunkParams.FALLBACK_DELIMITER, fromNull.delimiter());
        assertEquals(ChunkParams.FALLBACK_DELIMITER, fromEmpty.delimiter());
    }

    /**
     * 场景：分隔符为空白字符（换行 / 空格）——均为合法分隔符字符，下发层默认即 {@code "\n"}。
     * 预期：原样生效不改写（spec「delimiter 非空」仅拒空串，空白不误杀）。
     */
    @Test
    void should_keepWhitespaceDelimiter_when_construct_given_newlineOrSpaces() {
        // given / when
        ChunkParams fromNewline = new ChunkParams(512, "\n", 0);
        ChunkParams fromSpaces = new ChunkParams(512, "   ", 0);

        // then
        assertEquals("\n", fromNewline.delimiter());
        assertEquals("   ", fromSpaces.delimiter());
    }

    /**
     * 场景：默认参数口径（配置继承语义③：未填项取代码默认值）。
     * 预期：token 512、overlap 0、delimiter 为下发层默认换行 {@code "\n"}。
     */
    @Test
    void should_useDispatchDefaultDelimiter_when_defaults_given_noConfig() {
        // given / when
        ChunkParams params = ChunkParams.defaults();

        // then
        assertEquals(ChunkParams.DEFAULT_CHUNK_TOKEN_NUM, params.chunkTokenNum());
        assertEquals(ChunkParams.OVERLAP_MIN, params.overlappedPercent());
        assertEquals(ChunkParams.DEFAULT_DISPATCH_DELIMITER, params.delimiter());
    }

    /**
     * 场景：{@code fromMap} 读取三键（含 {@code overlap} 简写兼容）。
     * 预期：三值原样落地。
     */
    @Test
    void should_readThreeKeys_when_fromMap_given_standardKeys() {
        // given
        Map<String, Object> raw = new HashMap<>();
        raw.put("chunk_token_num", VALID_TOKEN_NUM);
        raw.put("delimiter", "`##`");
        raw.put("overlap", VALID_OVERLAP);

        // when
        ChunkParams params = ChunkParams.fromMap(raw);

        // then
        assertEquals(VALID_TOKEN_NUM, params.chunkTokenNum());
        assertEquals("`##`", params.delimiter());
        assertEquals(VALID_OVERLAP, params.overlappedPercent());
    }

    /**
     * 场景：标准键与简写键同时存在。
     * 预期：{@code overlapped_percent} 优先。
     */
    @Test
    void should_preferStandardOverlapKey_when_fromMap_given_bothSpellings() {
        // given
        Map<String, Object> raw = new HashMap<>();
        raw.put("overlapped_percent", 12);
        raw.put("overlap", 25);

        // when / then
        assertEquals(12, ChunkParams.fromMap(raw).overlappedPercent());
    }

    /**
     * 场景：入参映射为 null 与空映射。
     * 预期：均回落默认参数（不抛异常）。
     */
    @Test
    void should_returnDefaults_when_fromMap_given_nullOrEmptyMap() {
        // given / when / then
        assertEquals(ChunkParams.defaults(), ChunkParams.fromMap(null));
        assertEquals(ChunkParams.defaults(), ChunkParams.fromMap(Map.of()));
    }

    /**
     * 场景：存量脏 JSON（携带四个已废弃键）经真实反序列化进入 {@code fromMap}。
     * 预期：不报错、废弃键不消费，仅三键生效。
     */
    @Test
    void should_ignoreDeprecatedKeys_when_fromMap_given_legacyJsonPayload() throws Exception {
        // given
        String legacyJson = "{\"chunk_token_num\":1024,\"delimiter\":\"\\n\",\"overlapped_percent\":15,"
                + "\"enable_children\":true,\"children_delimiter\":\"###\",\"table_context_size\":5,"
                + "\"image_context_size\":6}";
        Map<String, Object> raw = OBJECT_MAPPER.readValue(legacyJson,
                new TypeReference<Map<String, Object>>() {
                });

        // when
        ChunkParams params = ChunkParams.fromMap(raw);

        // then
        assertEquals(1024, params.chunkTokenNum());
        assertEquals("\n", params.delimiter());
        assertEquals(15, params.overlappedPercent());
        assertEquals(3, params.getClass().getRecordComponents().length, "参数值对象只保留三项字段");
    }

    /**
     * 场景：小数 token 值（配置契约要求整数）。
     * 预期：解析阶段视为非法回默认，不做四舍五入改写。
     */
    @Test
    void should_fallbackToDefault_when_fromMap_given_decimalTokenNum() {
        // given
        Map<String, Object> raw = Map.of("chunk_token_num", 512.5D);

        // when
        ChunkParams params = ChunkParams.fromMap(raw);

        // then
        assertEquals(ChunkParams.DEFAULT_CHUNK_TOKEN_NUM, params.chunkTokenNum(), "非整数视为非法，回默认");
    }

    /**
     * 场景：整型语义的小数值（如 {@code 1024.0}）。
     * 预期：JSON 反序列化为 Double 但值为整数，按合法值生效。
     */
    @Test
    void should_acceptIntegralDouble_when_fromMap_given_decimalWithoutFraction() {
        // given / when
        ChunkParams params = ChunkParams.fromMap(Map.of("chunk_token_num", (double) VALID_TOKEN_NUM));

        // then
        assertEquals(VALID_TOKEN_NUM, params.chunkTokenNum());
    }

    /**
     * 场景：非法值兜底必须留痕（重叠比例越界）。
     * 预期：构造期输出一行 WARN，说明「该值应由保存侧入口拒绝」。
     */
    @Test
    void should_logWarn_when_construct_given_illegalOverlap() {
        List<ILoggingEvent> warnings = captureWarnings(
                () -> new ChunkParams(512, "\n", ChunkParams.OVERLAP_MAX + 15));

        assertEquals(1, warnings.size(), "一次越界 clamp 应输出一行 WARN");
        assertTrue(warnings.get(0).getFormattedMessage().contains("clamp"), "WARN 文案需说明兜底动作");
    }

    /**
     * 挂载 ListAppender 捕获 {@link ChunkParams} 的 WARN 日志并执行动作。
     *
     * @param action 触发构造 / 解析的动作
     * @return 捕获到的 WARN 事件列表
     */
    private static List<ILoggingEvent> captureWarnings(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(ChunkParams.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list.stream().filter(event -> Level.WARN.equals(event.getLevel())).toList();
    }
}
