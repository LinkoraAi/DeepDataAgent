package com.linkroa.deepdataagent.knowledgebase.domain.model.enums;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link KbLanguage} 单元测试：11 语言全名值域的查找归一与中文判定口径。
 */
class KbLanguageTest {

    @Test
    void should_findEnum_when_given_exactFullName() {
        // when & then：11 个全名逐项命中自身枚举
        assertEquals(Optional.of(KbLanguage.English), KbLanguage.find("English"));
        assertEquals(Optional.of(KbLanguage.Chinese), KbLanguage.find("Chinese"));
        assertEquals(Optional.of(KbLanguage.Spanish), KbLanguage.find("Spanish"));
        assertEquals(Optional.of(KbLanguage.French), KbLanguage.find("French"));
        assertEquals(Optional.of(KbLanguage.German), KbLanguage.find("German"));
        assertEquals(Optional.of(KbLanguage.Japanese), KbLanguage.find("Japanese"));
        assertEquals(Optional.of(KbLanguage.Korean), KbLanguage.find("Korean"));
        assertEquals(Optional.of(KbLanguage.Vietnamese), KbLanguage.find("Vietnamese"));
        assertEquals(Optional.of(KbLanguage.Arabic), KbLanguage.find("Arabic"));
        assertEquals(Optional.of(KbLanguage.Turkish), KbLanguage.find("Turkish"));
        assertEquals(Optional.of(KbLanguage.Dutch), KbLanguage.find("Dutch"));
    }

    @Test
    void should_findEnum_when_given_caseVariantOrPaddedFullName() {
        // when & then：大小写变体与前后空白（trim）均命中
        assertEquals(Optional.of(KbLanguage.Japanese), KbLanguage.find("japanese"));
        assertEquals(Optional.of(KbLanguage.Japanese), KbLanguage.find("JAPANESE"));
        assertEquals(Optional.of(KbLanguage.Chinese), KbLanguage.find("  chinese\t"));
    }

    @Test
    void should_returnEmpty_when_given_bareLanguageCode() {
        // when & then：历史裸语言码不在全名值域
        assertFalse(KbLanguage.find("ja").isPresent());
        assertFalse(KbLanguage.find("zh").isPresent());
        assertFalse(KbLanguage.find("en").isPresent());
    }

    @Test
    void should_returnEmpty_when_given_unlistedLanguage() {
        // when & then：未收录语言（如 Portuguese）不命中
        assertFalse(KbLanguage.find("Portuguese").isPresent());
    }

    @Test
    void should_returnEmpty_when_given_nullOrBlank() {
        // when & then：null / 空白安全返回空，不抛异常
        assertFalse(KbLanguage.find(null).isPresent());
        assertFalse(KbLanguage.find("   ").isPresent());
    }

    @Test
    void should_onlyChineseReportIsChinese_when_called() {
        // when & then：仅 Chinese 判定为中文，其余 10 项均为 false
        assertTrue(KbLanguage.Chinese.isChinese());
        for (KbLanguage language : KbLanguage.values()) {
            assertEquals(language == KbLanguage.Chinese, language.isChinese(),
                    "isChinese 判定与枚举项不一致: " + language.name());
        }
    }
}
