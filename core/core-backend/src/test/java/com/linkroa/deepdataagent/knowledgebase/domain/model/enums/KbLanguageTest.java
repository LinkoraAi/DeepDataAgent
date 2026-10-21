package com.linkroa.deepdataagent.knowledgebase.domain.model.enums;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link KbLanguage} 单元测试：Chinese/English 两值值域的查找归一与中文判定口径。
 */
class KbLanguageTest {

    @Test
    void should_findEnum_when_given_exactFullName() {
        // when & then：仅两个合法全名命中自身枚举
        assertEquals(Optional.of(KbLanguage.English), KbLanguage.find("English"));
        assertEquals(Optional.of(KbLanguage.Chinese), KbLanguage.find("Chinese"));
    }

    @Test
    void should_findEnum_when_given_caseVariantOrPaddedFullName() {
        // when & then：大小写变体与前后空白（trim）均命中
        assertEquals(Optional.of(KbLanguage.English), KbLanguage.find("english"));
        assertEquals(Optional.of(KbLanguage.English), KbLanguage.find("ENGLISH"));
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
    void should_returnEmpty_when_given_formerDomainLanguage() {
        // when & then：历史 11 值域中的其它语言不再命中
        assertFalse(KbLanguage.find("Japanese").isPresent());
        assertFalse(KbLanguage.find("Spanish").isPresent());
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
        // when & then：仅 Chinese 判定为中文，English 为 false
        assertTrue(KbLanguage.Chinese.isChinese());
        for (KbLanguage language : KbLanguage.values()) {
            assertEquals(language == KbLanguage.Chinese, language.isChinese(),
                    "isChinese 判定与枚举项不一致: " + language.name());
        }
    }
}