package com.linkroa.deepdataagent.shared.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ObjectKeys} 技术校验单测：合法 key 放行，穿越 / 反斜杠 / 控制字符 /
 * 超界 / 绝对形态拒绝，精确 key 与前缀的结尾斜杠口径区分。
 */
class ObjectKeysTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "files/file_abc-123",
            "skills/skill_x/1/SKILL.md",
            "skills/skill_x/2/references/需求文档.md",
            "a/b/c/notes v1.0.txt"
    })
    void should_accept_when_validateKey_given_safeKey(String key) {
        // given // when // then（UTF-8 文件名、层级、空格 / 点 / 中文均允许）
        assertThatCode(() -> ObjectKeys.validateKey(key)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "   ", "/leading-slash", "a//b", "trailing/",
            "../escape", "a/../../etc", "a/./b", "a\\b", "a/b\0c", "a/b\nc",
            "skills//SKILL.md"
    })
    void should_throw_when_validateKey_given_illegalKey(String key) {
        // given // when // then
        assertThrows(IllegalArgumentException.class, () -> ObjectKeys.validateKey(key));
    }

    @Test
    void should_throw_when_validateKey_given_keyTooLong() {
        // given
        String tooLong = "a".repeat(ObjectKeys.MAX_KEY_LENGTH + 1);

        // when // then
        assertThrows(IllegalArgumentException.class, () -> ObjectKeys.validateKey(tooLong));
    }

    @Test
    void should_acceptTrailingSlash_when_validatePrefix_given_directoryPrefix() {
        // given // when // then（前缀允许结尾斜杠，精确 key 不允许）
        assertThatCode(() -> ObjectKeys.validatePrefix("skills/skill_x/1/")).doesNotThrowAnyException();
        assertThrows(IllegalArgumentException.class, () -> ObjectKeys.validateKey("skills/skill_x/1/"));
    }

    @Test
    void should_rejectTraversalAndBackslash_when_validatePrefix_given_illegalPrefix() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> ObjectKeys.validatePrefix("../skills/"));
        assertThrows(IllegalArgumentException.class,
                () -> ObjectKeys.validatePrefix("skills\\x/"));
    }

    @Test
    void should_exposeBoundedMaxLength() {
        // given // when // then
        assertTrue(ObjectKeys.MAX_KEY_LENGTH > 0);
    }
}
