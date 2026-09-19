package com.linkroa.deepdataagent.vault.application.validation;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link VaultMetadataValidator} 契约边界单测。
 * <p>覆盖 metadata 的 {@code object<string,string>} 契约：key 长度 1–64 字符、value 最长 512 字符
 * 且必须为字符串；显式 {@code null} 值为 merge 补丁的键级删除标记，须放行。
 * 同时覆盖 JSON 文本入口（{@code validateJson} / {@code parseObject}）的对象形状与键值约束复用。</p>
 *
 * <p>注：application/validation 层按 AGENTS.md 不计入单元测试覆盖率，本测试为行为回归保障。</p>
 */
class VaultMetadataValidatorTest {

    @Test
    void should_pass_when_validate_given_keyExactlyMaxLength() {
        // given（key 恰好 64 字符：边界内，不得拒绝）
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("k".repeat(64), "v");

        // when & then
        assertDoesNotThrow(() -> VaultMetadataValidator.validate(metadata));
    }

    @Test
    void should_throw_when_validate_given_keyExceedingMaxLength() {
        // given（key 越界一格：65 字符）
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("k".repeat(65), "v");

        // when & then
        assertThrows(IllegalArgumentException.class, () -> VaultMetadataValidator.validate(metadata));
    }

    @Test
    void should_pass_when_validate_given_valueExactlyMaxLength() {
        // given（value 恰好 512 字符：边界内，不得拒绝）
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("team", "v".repeat(512));

        // when & then
        assertDoesNotThrow(() -> VaultMetadataValidator.validate(metadata));
    }

    @Test
    void should_throw_when_validate_given_valueExceedingMaxLength() {
        // given（value 越界一格：513 字符）
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("team", "v".repeat(513));

        // when & then
        assertThrows(IllegalArgumentException.class, () -> VaultMetadataValidator.validate(metadata));
    }

    @Test
    void should_throw_when_validate_given_blankKey() {
        // given（空串与全空白 key 同口径拒绝）
        Map<String, Object> emptyKey = new HashMap<>();
        emptyKey.put("", "v");
        Map<String, Object> blankKey = new HashMap<>();
        blankKey.put("   ", "v");

        // when & then
        assertThrows(IllegalArgumentException.class, () -> VaultMetadataValidator.validate(emptyKey));
        assertThrows(IllegalArgumentException.class, () -> VaultMetadataValidator.validate(blankKey));
    }

    @Test
    void should_pass_when_validate_given_nullValueDeletionMarker() {
        // given（显式 null 值 = merge 补丁的键级删除标记，值本身不参与存储）
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("obsolete", null);

        // when & then
        assertDoesNotThrow(() -> VaultMetadataValidator.validate(metadata));
    }

    @Test
    void should_throw_when_validate_given_nonStringValue() {
        // given（数字 / 布尔 / 嵌套对象 / 数组一律非字符串，必须拒绝）
        Map<String, Object> numberValue = new HashMap<>();
        numberValue.put("count", 1);
        Map<String, Object> booleanValue = new HashMap<>();
        booleanValue.put("enabled", true);
        Map<String, Object> objectValue = new HashMap<>();
        objectValue.put("nested", Map.of("a", "b"));
        Map<String, Object> arrayValue = new HashMap<>();
        arrayValue.put("items", List.of("a", "b"));

        // when & then
        assertThrows(IllegalArgumentException.class, () -> VaultMetadataValidator.validate(numberValue));
        assertThrows(IllegalArgumentException.class, () -> VaultMetadataValidator.validate(booleanValue));
        assertThrows(IllegalArgumentException.class, () -> VaultMetadataValidator.validate(objectValue));
        assertThrows(IllegalArgumentException.class, () -> VaultMetadataValidator.validate(arrayValue));
    }

    @Test
    void should_pass_when_validate_given_nullMetadata() {
        // given（null = 无 metadata，直接放行）
        // when & then
        assertDoesNotThrow(() -> VaultMetadataValidator.validate(null));
    }

    @Test
    void should_pass_when_validateJson_given_validObjectText() {
        // given（合法 JSON 对象文本，key / value 均落在边界上）
        String metadataJson = "{\"" + "k".repeat(64) + "\":\"" + "v".repeat(512) + "\"}";

        // when & then
        assertDoesNotThrow(() -> VaultMetadataValidator.validateJson(metadataJson));
    }

    @Test
    void should_pass_when_validateJson_given_blankText() {
        // given（空白文本视为未提供，含 null / 空串 / 全空白三种形态）
        // when & then
        assertDoesNotThrow(() -> VaultMetadataValidator.validateJson(null));
        assertDoesNotThrow(() -> VaultMetadataValidator.validateJson(""));
        assertDoesNotThrow(() -> VaultMetadataValidator.validateJson("   "));
    }

    @Test
    void should_throw_when_validateJson_given_overlongKey() {
        // given（JSON 对象形状合法，但 key 越界一格：65 字符）
        String metadataJson = "{\"" + "k".repeat(65) + "\":\"v\"}";

        // when & then
        assertThrows(IllegalArgumentException.class, () -> VaultMetadataValidator.validateJson(metadataJson));
    }

    @Test
    void should_throw_when_validateJson_given_nonObjectText() {
        // given（JSON 数组与标量均非 JSON 对象）
        String arrayText = "[1,2]";
        String scalarText = "\"text\"";

        // when & then
        assertThrows(IllegalArgumentException.class, () -> VaultMetadataValidator.validateJson(arrayText));
        assertThrows(IllegalArgumentException.class, () -> VaultMetadataValidator.validateJson(scalarText));
    }

    @Test
    void should_returnKeyValueMap_when_parseObject_given_objectText() {
        // given
        String metadataJson = "{\"team\":\"data\",\"env\":\"prod\"}";

        // when
        Map<String, Object> parsed = VaultMetadataValidator.parseObject(metadataJson);

        // then（键值逐一落地）
        assertEquals(Map.of("team", "data", "env", "prod"), parsed);
    }

    @Test
    void should_throw_when_parseObject_given_nonObjectText() {
        // given（非 JSON 对象文本：数组 / 非法 JSON 文本）
        // when & then
        assertThrows(IllegalArgumentException.class, () -> VaultMetadataValidator.parseObject("[1,2]"));
        assertThrows(IllegalArgumentException.class, () -> VaultMetadataValidator.parseObject("not-json"));
    }
}