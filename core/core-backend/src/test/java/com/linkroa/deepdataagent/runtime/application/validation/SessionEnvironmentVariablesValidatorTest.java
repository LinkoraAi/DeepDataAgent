package com.linkroa.deepdataagent.runtime.application.validation;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SessionEnvironmentVariablesValidator} 会话环境变量形态校验单测
 * （spec {@code runtime/sessions}「Session 环境变量校验」六场景的离线可执行断言，D11 共用判定）。
 * <p>校验器零协作者、入参为会话环境变量 JSON 文本，故本类只钉「判定项本体」：变量名形态、
 * 值类型、保留名 / 保留前缀、单值 / 条数 / 总字节越限，以及各上限的边界取值。
 * 「校验失败早于落库（创建）/ 早于列更新（更新）」的编排时序由会话生命周期服务用例留守钉桩
 * （{@code SessionLifecycleServiceTest}）。</p>
 */
class SessionEnvironmentVariablesValidatorTest {

    /** 单值上限（8 KiB）内的合法值。 */
    private static final String LEGAL_VALUE = "debug";

    @Test
    void should_pass_when_validate_given_legalVariables() {
        // given（合法命名：字母开头、含下划线与数字；值为字符串）
        String variables = "{\"LOG_LEVEL\":\"debug\",\"_TZ_2\":\"Asia/Shanghai\",\"MAX_RETRY\":\"3\"}";

        // when & then（零异常）
        assertDoesNotThrow(() -> SessionEnvironmentVariablesValidator.validate(variables));
    }

    @Test
    void should_pass_when_validate_given_absentOrEmptyVariables() {
        // given（未提供 / 空对象：均视为无环境变量，直接通过）
        // when & then
        assertDoesNotThrow(() -> SessionEnvironmentVariablesValidator.validate(null));
        assertDoesNotThrow(() -> SessionEnvironmentVariablesValidator.validate(""));
        assertDoesNotThrow(() -> SessionEnvironmentVariablesValidator.validate("   "));
        assertDoesNotThrow(() -> SessionEnvironmentVariablesValidator.validate("{}"));
    }

    @Test
    void should_rejectIllegalName_when_validate_given_nameNotMatchingPattern() {
        // given（数字开头 / 含连字符 / 含空格 三种不匹配 [A-Za-z_][A-Za-z0-9_]* 的变量名）
        String[] illegalNames = {"1LOG_LEVEL", "LOG-LEVEL", "LOG LEVEL"};

        // when & then（逐一拒绝）
        for (String name : illegalNames) {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> SessionEnvironmentVariablesValidator.validate(json(Map.of(name, LEGAL_VALUE))));
            assertTrue(ex.getMessage().contains("变量名非法"), ex.getMessage());
        }
    }

    @Test
    void should_rejectNonStringValue_when_validate_given_scalarOrStructuredValue() {
        // given（值分别取数字 / 布尔 / null / 嵌套对象 / 数组：均非字符串）
        String variables = "{\"NUM\":3,\"FLAG\":true,\"NIL\":null,\"OBJ\":{\"a\":1},\"ARR\":[\"a\"]}";

        // when & then（每个非字符串值均被拒绝，批量全有或全无）
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> SessionEnvironmentVariablesValidator.validate(variables));
        assertTrue(ex.getMessage().contains("值必须为字符串"), ex.getMessage());
    }

    @Test
    void should_rejectReservedName_when_validate_given_platformInjectedNames() {
        // given（平台注入的三个保留名：用户不得以会话环境变量覆盖同名变量）
        String[] reservedNames = {"SERVER_ENDPOINT", "USER_ID", "WORK_DIR"};

        // when & then
        for (String name : reservedNames) {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> SessionEnvironmentVariablesValidator.validate(json(Map.of(name, LEGAL_VALUE))));
            assertTrue(ex.getMessage().contains("保留"), ex.getMessage());
        }
    }

    @Test
    void should_rejectReservedPrefix_when_validate_given_cawPrefixedName() {
        // given（平台保留前缀 CAW_）
        String[] prefixedNames = {"CAW_ENDPOINT"};

        // when & then
        for (String name : prefixedNames) {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> SessionEnvironmentVariablesValidator.validate(json(Map.of(name, LEGAL_VALUE))));
            assertTrue(ex.getMessage().contains("保留前缀"), ex.getMessage());
        }
    }

    @Test
    void should_acceptNameOutsideReservedPrefix_when_validate_given_nonReservedPrefixedName() {
        // given（非保留前缀的名称：前缀保留面已收敛为 CAW_ 单一命名空间）
        String json = json(Map.of("QODER_TOKEN", LEGAL_VALUE));

        // when & then
        assertDoesNotThrow(() -> SessionEnvironmentVariablesValidator.validate(json));
    }

    @Test
    void should_accept8KibValueAndRejectOverflow_when_validate_given_valueBoundary() {
        // given（单值上限边界：8192 字节恰好通过、8193 字节越限）
        String atLimit = json(Map.of("BIG", "a".repeat(8 * 1024)));
        String overLimit = json(Map.of("BIG", "a".repeat(8 * 1024 + 1)));

        // when & then
        assertDoesNotThrow(() -> SessionEnvironmentVariablesValidator.validate(atLimit));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> SessionEnvironmentVariablesValidator.validate(overLimit));
        assertTrue(ex.getMessage().contains("单值不能超过"), ex.getMessage());
    }

    @Test
    void should_accept64EntriesAndReject65_when_validate_given_entryCountBoundary() {
        // given（条数上限边界：64 条恰好通过、65 条越限）
        Map<String, Object> atLimit = new LinkedHashMap<>();
        for (int i = 0; i < 64; i++) {
            atLimit.put("VAR_" + i, LEGAL_VALUE);
        }
        Map<String, Object> overLimit = new LinkedHashMap<>(atLimit);
        overLimit.put("VAR_64", LEGAL_VALUE);

        // when & then
        assertDoesNotThrow(() -> SessionEnvironmentVariablesValidator.validate(json(atLimit)));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> SessionEnvironmentVariablesValidator.validate(json(overLimit)));
        assertTrue(ex.getMessage().contains("条数不能超过"), ex.getMessage());
    }

    @Test
    void should_acceptValuesWithinTotalAndRejectOverflow_when_validate_given_totalBytesBoundary() {
        // given（总字节上限边界：单值均在 8 KiB 内，条数均在 64 内）
        // 通过档：64 条 × 1000 字节 = 64000 字节 + 名字节 < 64 KiB
        Map<String, Object> withinTotal = new LinkedHashMap<>();
        for (int i = 0; i < 64; i++) {
            withinTotal.put("KEY_" + i, "v".repeat(1000));
        }
        // 越限档：9 条 × 8000 字节 = 72000 字节 > 64 KiB
        Map<String, Object> overTotal = new LinkedHashMap<>();
        for (int i = 0; i < 9; i++) {
            overTotal.put("KEY_" + i, "v".repeat(8000));
        }

        // when & then
        assertDoesNotThrow(() -> SessionEnvironmentVariablesValidator.validate(json(withinTotal)));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> SessionEnvironmentVariablesValidator.validate(json(overTotal)));
        assertTrue(ex.getMessage().contains("总字节不能超过"), ex.getMessage());
    }

    @Test
    void should_rejectNonObjectJson_when_validate_given_arrayOrScalarText() {
        // given（非键值对象形态：数组 / 标量 / 非法 JSON）
        String[] nonObjects = {"[\"A\"]", "\"A\"", "{not-json}"};

        // when & then
        for (String text : nonObjects) {
            assertThrows(IllegalArgumentException.class,
                    () -> SessionEnvironmentVariablesValidator.validate(text));
        }
    }

    /** 键值对象 → JSON 文本（测试侧序列化，等价接口层装配结果）。 */
    private static String json(Map<String, Object> variables) {
        StringBuilder builder = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : variables.entrySet()) {
            if (!first) {
                builder.append(',');
            }
            builder.append('"').append(entry.getKey()).append("\":\"").append(entry.getValue()).append('"');
            first = false;
        }
        return builder.append('}').toString();
    }
}