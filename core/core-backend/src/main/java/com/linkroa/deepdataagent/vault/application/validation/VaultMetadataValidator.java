package com.linkroa.deepdataagent.vault.application.validation;

import org.apache.commons.lang3.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * 保管库 / 凭证 metadata 校验（写入路径与搜索筛选路径共用同一判定）。
 *
 * <p>契约限定 metadata 为 {@code object<string,string>}：key 长度 MUST 为 1–64 字符、
 * value MUST 为最长 512 字符的字符串。写入与搜索共用本判定，避免「只在搜索时校验、
 * 写入时放行」造出搜索永远命中不了的脏值。</p>
 *
 * <p>值为显式 {@code null} 表示 merge 补丁的<b>键级删除标记</b>（更新语义），放行；
 * 其余非字符串值（数字 / 布尔 / 对象 / 数组）一律 400。</p>
 */
public final class VaultMetadataValidator {

    /** metadata key 长度上限（契约）。 */
    private static final int MAX_KEY_LENGTH = 64;
    /** metadata value 长度上限（契约）。 */
    private static final int MAX_VALUE_LENGTH = 512;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private VaultMetadataValidator() {
    }

    /**
     * 校验 metadata 键值对象（可空 = 无 metadata，直接通过）。
     *
     * @param metadata 元数据键值对象（值可为 null，表示键级删除标记）
     * @throws IllegalArgumentException key 长度越界 / 非字符串值 / 值超长
     */
    public static void validate(Map<String, Object> metadata) {
        if (metadata == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            String key = entry.getKey();
            if (StringUtils.isBlank(key) || key.length() > MAX_KEY_LENGTH) {
                throw new IllegalArgumentException("metadata key 长度必须为 1-" + MAX_KEY_LENGTH + " 个字符");
            }
            Object value = entry.getValue();
            if (value == null) {
                // 键级删除标记：merge 补丁语义，值本身不参与存储
                continue;
            }
            if (!(value instanceof String text)) {
                throw new IllegalArgumentException("metadata 的值必须为字符串: " + key);
            }
            if (text.length() > MAX_VALUE_LENGTH) {
                throw new IllegalArgumentException(
                        "metadata 值长度不能超过" + MAX_VALUE_LENGTH + "个字符: " + key);
            }
        }
    }

    /**
     * 校验 metadata JSON 文本（写入路径入口）：必须是 JSON 对象，且满足键值约束；空白视为未提供。
     *
     * @param metadataJson metadata JSON 文本（可空 / 空白 = 未提供）
     * @throws IllegalArgumentException 非 JSON 对象 / 键值约束违规
     */
    public static void validateJson(String metadataJson) {
        if (StringUtils.isBlank(metadataJson)) {
            return;
        }
        validate(parseObject(metadataJson));
    }

    /**
     * metadata JSON 文本 → 键值对象（供写入路径装配紧凑文本与校验复用）。
     *
     * @throws IllegalArgumentException 非 JSON 对象文本
     */
    public static Map<String, Object> parseObject(String metadataJson) {
        try {
            Map<String, Object> parsed = OBJECT_MAPPER.readValue(metadataJson, MAP_TYPE);
            return parsed == null ? Map.of() : parsed;
        } catch (JacksonException e) {
            throw new IllegalArgumentException("metadata 必须为 JSON 对象: " + metadataJson, e);
        }
    }
}