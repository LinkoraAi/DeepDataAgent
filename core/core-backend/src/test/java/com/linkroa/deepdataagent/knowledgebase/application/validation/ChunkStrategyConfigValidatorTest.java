package com.linkroa.deepdataagent.knowledgebase.application.validation;

import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ChunkStrategyConfigValidator} 单元测试：分块策略保存时点的拒绝式校验。
 * <p>覆盖七枚举模式白名单、仅 GENERAL 可携 params、chunk_token_num (0,2048] 整数、
 * overlapped_percent [0,30] 整数（含历史兼容键 overlap）、delimiter 非空、
 * params 未识别扩展键与历史废弃键忽略、字符串包裹形态兼容，以及「未配置」与「配了非法值」的区分口径。</p>
 */
class ChunkStrategyConfigValidatorTest {

    /** JSON 文本再包一层字符串用的解析器（无状态） */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 合法通用策略：三项参数均落在值域内 */
    private static final String LEGAL_GENERAL_STRATEGY =
            "{\"chunkMode\":\"GENERAL\",\"modeConfig\":{\"params\":"
                    + "{\"chunk_token_num\":1024,\"overlapped_percent\":15,\"delimiter\":\"\\\\n\"}}}";

    /**
     * 把策略 JSON 再包一层字符串（存量形态：chunkStrategy 的值是 JSON 文本字符串）。
     *
     * @param strategyJson 策略 JSON 文本
     * @return 作为 JSON 字符串值再编码一次的文本
     */
    private static String wrappedAsJsonString(String strategyJson) {
        return OBJECT_MAPPER.writeValueAsString(strategyJson);
    }

    /**
     * 构造仅指定 GENERAL 下单项参数的策略 JSON。
     *
     * @param paramsJson 参数对象 JSON 文本
     * @return 通用模式策略 JSON
     */
    private static String generalStrategy(String paramsJson) {
        return "{\"chunkMode\":\"GENERAL\",\"modeConfig\":{\"params\":" + paramsJson + "}}";
    }

    @Test
    void should_pass_when_validate_given_legalGeneralParams() {
        // given & then：合法配置放行（保存原样落库）
        assertDoesNotThrow(() -> ChunkStrategyConfigValidator.validate(LEGAL_GENERAL_STRATEGY));
    }

    @Test
    void should_pass_when_validate_given_boundaryParamValues() {
        // given & then：边界值合法——token 上限 2048、重叠下界 0 与上界 30、空格分隔符
        assertDoesNotThrow(() -> ChunkStrategyConfigValidator.validate(
                generalStrategy("{\"chunk_token_num\":2048,\"overlapped_percent\":0}")));
        assertDoesNotThrow(() -> ChunkStrategyConfigValidator.validate(
                generalStrategy("{\"overlapped_percent\":30,\"delimiter\":\" \"}")));
    }

    @Test
    void should_pass_when_validate_given_blankStrategy() {
        // given & then：整段空白 / null 按「未配置」放行
        assertDoesNotThrow(() -> ChunkStrategyConfigValidator.validate((String) null));
        assertDoesNotThrow(() -> ChunkStrategyConfigValidator.validate("  "));
    }

    @Test
    void should_pass_when_validate_given_missingChunkMode() {
        // given & then：模式名缺失 / 空白按未配置放行（实际模式由继承链决定），但已写出的参数仍校验值域
        assertDoesNotThrow(() -> ChunkStrategyConfigValidator.validate(
                "{\"modeConfig\":{\"params\":{\"chunk_token_num\":512}}}"));
        assertDoesNotThrow(() -> ChunkStrategyConfigValidator.validate("{\"chunkMode\":\"  \"}"));
    }

    @Test
    void should_pass_when_validate_given_nonGeneralModeWithoutParams() {
        // given & then：非 GENERAL 模式无 params 合法（模式名 trim、大小写不敏感）
        assertDoesNotThrow(() -> ChunkStrategyConfigValidator.validate("{\"chunkMode\":\"BOOK\"}"));
        assertDoesNotThrow(() -> ChunkStrategyConfigValidator.validate(
                "{\"chunkMode\":\" one \",\"modeConfig\":{\"chunkSize\":512}}"));
    }

    @Test
    void should_pass_when_validate_given_strategyWrappedAsJsonString() {
        // given：策略 JSON 被再包一层字符串（存量两种形态之一）
        String wrapped = wrappedAsJsonString(LEGAL_GENERAL_STRATEGY);

        // when & then：解包后按同一套规则校验，合法即放行
        assertDoesNotThrow(() -> ChunkStrategyConfigValidator.validate(wrapped));
    }

    @Test
    void should_pass_when_validate_given_legacyDeprecatedAndUnknownKeys() {
        // given：存量配置的 params 含历史废弃键与未来扩展键
        String strategy = generalStrategy("{\"chunk_token_num\":1024,\"enable_children\":true,"
                + "\"children_delimiter\":\"\\\\n\",\"table_context_size\":3,\"image_context_size\":2,"
                + "\"future_extension_key\":\"whatever\"}");

        // when & then：编辑保存成功——未识别键忽略不报错
        assertDoesNotThrow(() -> ChunkStrategyConfigValidator.validate(strategy));
    }

    @Test
    void should_throwBadRequest_when_validate_given_zeroChunkTokenNum() {
        // given：token 预算写为 0（不得回退默认 512 后保存成功）
        String strategy = generalStrategy("{\"chunk_token_num\":0}");

        // when
        DeepDataAgentException exception = assertThrows(DeepDataAgentException.class,
                () -> ChunkStrategyConfigValidator.validate(strategy));

        // then：400 指明字段与合法值域 (0,2048]
        assertTrue(exception.getMessage().contains("chunk_token_num"));
        assertTrue(exception.getMessage().contains("(0,2048]"));
    }

    @Test
    void should_throwBadRequest_when_validate_given_chunkTokenNumBeyondRange() {
        // given & then：负数、超上限、小数、非数值写法一律拒绝
        assertThrows(DeepDataAgentException.class, () -> ChunkStrategyConfigValidator.validate(
                generalStrategy("{\"chunk_token_num\":-1}")));
        assertThrows(DeepDataAgentException.class, () -> ChunkStrategyConfigValidator.validate(
                generalStrategy("{\"chunk_token_num\":2049}")));
        assertThrows(DeepDataAgentException.class, () -> ChunkStrategyConfigValidator.validate(
                generalStrategy("{\"chunk_token_num\":1024.5}")));
        assertThrows(DeepDataAgentException.class, () -> ChunkStrategyConfigValidator.validate(
                generalStrategy("{\"chunk_token_num\":\"1024\"}")));
    }

    @Test
    void should_throwBadRequest_when_validate_given_overlappedPercentOutOfRange() {
        // given & then：45 与 -1 均落在 [0,30] 之外
        DeepDataAgentException exception = assertThrows(DeepDataAgentException.class,
                () -> ChunkStrategyConfigValidator.validate(generalStrategy("{\"overlapped_percent\":45}")));
        assertTrue(exception.getMessage().contains("overlapped_percent"));
        assertTrue(exception.getMessage().contains("[0,30]"));
        assertThrows(DeepDataAgentException.class, () -> ChunkStrategyConfigValidator.validate(
                generalStrategy("{\"overlapped_percent\":-1}")));
    }

    @Test
    void should_throwBadRequest_when_validate_given_nonIntegralOverlappedPercent() {
        // given & then：小数重叠比例拒绝（仅接受整数）
        assertThrows(DeepDataAgentException.class, () -> ChunkStrategyConfigValidator.validate(
                generalStrategy("{\"overlapped_percent\":7.5}")));
    }

    @Test
    void should_throwBadRequest_when_validate_given_legacyOverlapKeyOutOfRange() {
        // given & then：历史兼容键 overlap 与规范键同受值域约束
        assertThrows(DeepDataAgentException.class, () -> ChunkStrategyConfigValidator.validate(
                generalStrategy("{\"overlap\":45}")));
    }

    @Test
    void should_throwBadRequest_when_validate_given_paramsWithNonGeneralMode() {
        // given：非 GENERAL 模式携带 params
        String strategy = "{\"chunkMode\":\"BOOK\",\"modeConfig\":{\"params\":{\"chunk_token_num\":1024}}}";

        // when
        DeepDataAgentException exception = assertThrows(DeepDataAgentException.class,
                () -> ChunkStrategyConfigValidator.validate(strategy));

        // then：提示仅 GENERAL 支持参数配置
        assertTrue(exception.getMessage().contains("仅 GENERAL 模式支持分块参数配置"));
    }

    @Test
    void should_throwBadRequest_when_validate_given_unknownChunkMode() {
        // when：模式名 auto 落在七枚举白名单外
        DeepDataAgentException exception = assertThrows(DeepDataAgentException.class,
                () -> ChunkStrategyConfigValidator.validate("{\"chunkMode\":\"auto\"}"));

        // then：拒绝并列出七个合法值，MUST NOT 静默回退 GENERAL
        assertTrue(exception.getMessage().contains("GENERAL/QA/BOOK/LAWS/TABLE/PRESENTATION/ONE"));
        assertThrows(DeepDataAgentException.class,
                () -> ChunkStrategyConfigValidator.validate("{\"chunkMode\":\"smart\"}"));
    }

    @Test
    void should_throwBadRequest_when_validate_given_emptyDelimiter() {
        // given & then：delimiter 空串与非文本类型均拒绝
        assertThrows(DeepDataAgentException.class, () -> ChunkStrategyConfigValidator.validate(
                generalStrategy("{\"delimiter\":\"\"}")));
        assertThrows(DeepDataAgentException.class, () -> ChunkStrategyConfigValidator.validate(
                generalStrategy("{\"delimiter\":123}")));
    }

    @Test
    void should_throwBadRequest_when_validate_given_illegalJsonOrNonObject() {
        // given & then：数据损坏在写入口前置拒绝（非法 JSON / 数组根 / 内层字符串非法 JSON）
        assertThrows(DeepDataAgentException.class, () -> ChunkStrategyConfigValidator.validate("{oops"));
        assertThrows(DeepDataAgentException.class, () -> ChunkStrategyConfigValidator.validate("[1,2]"));
        assertThrows(DeepDataAgentException.class, () -> ChunkStrategyConfigValidator.validate("\"{oops\""));
    }
}
