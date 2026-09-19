package com.linkroa.deepdataagent.runtime.application.validation;

import org.apache.commons.lang3.StringUtils;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 会话环境变量校验器（应用级形态校验，契约入站 {@code environment_variables} 的 400 语义权威）。
 * <p>落点遵循 design D11：与挂载校验器同层（应用级），由创建与更新<b>两条写入路径共用同一份判定</b>
 * ——创建见 {@code SessionLifecycleService#createSession} 的挂载前置校验链末位，更新见
 * {@code SessionLifecycleService#updateSession} 方法体首行（载荷类 400 先于会话不存在，与
 * {@link InboundEventValidator#validateAppendResourceBatch} 同优先序）。两条路径均在任何落库 /
 * 物化之前调用，任一违规即整批 400 且零数据变更。MUST NOT 下沉沙箱侧过滤（拒绝时机过晚，
 * 且用户环境变量与「凭据不进沙箱」是两条独立通道），MUST NOT 依赖数据库约束（直撞约束会抛
 * 数据完整性异常映射成 500，而对承诺是 400 {@code invalid_request_error}）。</p>
 * <p>判定项（spec {@code runtime/sessions}「Session 环境变量校验」）：</p>
 * <ul>
 *   <li>变量名 MUST 匹配 {@code [A-Za-z_][A-Za-z0-9_]*}；</li>
 *   <li>所有值 MUST 为字符串（JSON 文本中的数字 / 布尔 / null / 对象 / 数组一律拒绝）——
 *       故入参取<b>已序列化的 JSON 文本</b>而非 {@code Map<String, String>}：后者在协议绑定期
 *       即丢失值的原始类型，无法区分「非字符串」；</li>
 *   <li>MUST 拒绝保留名 {@code SERVER_ENDPOINT} / {@code USER_ID} / {@code WORK_DIR}，以及
 *       任何带 {@code CAW_} 前缀的名称——阻止用户以会话环境变量覆盖平台注入的同名变量；</li>
 *   <li>单个值 MUST NOT 超过 8 KiB；整个 map MUST NOT 超过 64 条，且总字节（各条目「名字节 + 值字节」
 *       的 UTF-8 字节数之和）MUST NOT 超过 64 KiB。</li>
 * </ul>
 * <p>校验器零副作用、不持有协作者（纯形态判定），故以静态工具类承载；空白入参（未提供环境变量）
 * 与空对象 {@code {}} 均直接通过。</p>
 */
public final class SessionEnvironmentVariablesValidator {

    private SessionEnvironmentVariablesValidator() {
    }

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 环境变量 JSON 文本反序列化目标类型（值保留原始 JSON 类型以判别「非字符串」）。 */
    private static final TypeReference<Map<String, Object>> VARIABLES_TYPE = new TypeReference<>() {
    };

    /** 变量名形态：字母 / 下划线开头，后续仅字母 / 数字 / 下划线。 */
    private static final Pattern VARIABLE_NAME_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /** 平台保留变量名（同名变量由平台注入，用户不得覆盖）。 */
    private static final Set<String> RESERVED_NAMES = Set.of("SERVER_ENDPOINT", "USER_ID", "WORK_DIR");

    /** 平台保留前缀（该命名空间归平台所有）。 */
    private static final List<String> RESERVED_PREFIXES = List.of("CAW_");

    /** 单个值的 UTF-8 字节上限（8 KiB）。 */
    private static final int MAX_VALUE_BYTES = 8 * 1024;
    /** 环境变量条目数上限。 */
    private static final int MAX_ENTRY_COUNT = 64;
    /** 环境变量总字节上限（名字节 + 值字节之和，64 KiB）。 */
    private static final int MAX_TOTAL_BYTES = 64 * 1024;

    /**
     * 校验会话环境变量 JSON 文本形态（创建与更新两条写入路径共用）。
     *
     * @param environmentVariablesJson 会话环境变量 JSON 对象文本（null / 空白 = 未提供，直接通过）
     * @throws IllegalArgumentException 非 JSON 对象、变量名非法、值非字符串、命中保留名 / 前缀，
     *                                  或单值 / 条数 / 总字节越限（400 {@code invalid_request_error}）
     */
    public static void validate(String environmentVariablesJson) {
        Map<String, Object> variables = parseObject(environmentVariablesJson);
        if (variables == null || variables.isEmpty()) {
            return;
        }
        if (variables.size() > MAX_ENTRY_COUNT) {
            throw new IllegalArgumentException("会话环境变量条数不能超过 " + MAX_ENTRY_COUNT
                    + " 条: 当前 " + variables.size() + " 条");
        }
        long totalBytes = 0L;
        for (Map.Entry<String, Object> entry : variables.entrySet()) {
            String name = entry.getKey();
            validateName(name);
            Object value = entry.getValue();
            if (!(value instanceof String text)) {
                throw new IllegalArgumentException("会话环境变量值必须为字符串: " + name);
            }
            int valueBytes = utf8Length(text);
            if (valueBytes > MAX_VALUE_BYTES) {
                throw new IllegalArgumentException("会话环境变量单值不能超过 " + MAX_VALUE_BYTES
                        + " 字节: " + name);
            }
            totalBytes += utf8Length(name) + valueBytes;
        }
        if (totalBytes > MAX_TOTAL_BYTES) {
            throw new IllegalArgumentException("会话环境变量总字节不能超过 " + MAX_TOTAL_BYTES
                    + " 字节: 当前 " + totalBytes + " 字节");
        }
    }

    /** 变量名判定：形态正则 → 保留名 → 保留前缀（判定顺序即 400 消息的优先级）。 */
    private static void validateName(String name) {
        if (name == null || !VARIABLE_NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "会话环境变量名非法（须匹配 [A-Za-z_][A-Za-z0-9_]*）: " + name);
        }
        if (RESERVED_NAMES.contains(name)) {
            throw new IllegalArgumentException("会话环境变量名保留，不得覆盖平台注入变量: " + name);
        }
        for (String prefix : RESERVED_PREFIXES) {
            if (name.startsWith(prefix)) {
                throw new IllegalArgumentException("会话环境变量名使用保留前缀 " + prefix + ": " + name);
            }
        }
    }

    /**
     * JSON 文本 → 键值对象（值保留原始类型）；空白归一为 null（未提供），
     * 非 JSON 对象文本（数组 / 标量 / 非法 JSON）一律拒绝。
     */
    private static Map<String, Object> parseObject(String json) {
        if (StringUtils.isBlank(json)) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(json, VARIABLES_TYPE);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("会话环境变量必须是合法的 JSON 键值对象: " + json, ex);
        }
    }

    /** 字符串 UTF-8 字节数（8 KiB 上限按字节而非字符计）。 */
    private static int utf8Length(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }
}