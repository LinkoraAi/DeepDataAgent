package com.linkroa.deepdataagent.knowledgebase.application.validation;

import com.linkroa.deepdataagent.knowledgebase.domain.model.KnowledgeBase;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.KnowledgeBaseSortField;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.LifecycleStatus;
import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import com.linkroa.deepdataagent.shared.exception.ResourceConflictException;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link KnowledgeBaseValidator} 单元测试。
 */
class KnowledgeBaseValidatorTest {

    /** 名称长度上限，与被测类内部常量保持一致 */
    private static final int NAME_MAX_LENGTH = 128;

    private KnowledgeBase buildKb(Long id, LifecycleStatus status) {
        return KnowledgeBase.restore(id, "产品手册", "描述", "Chinese", status,
                null, null, null, null, null, null, null, null, null);
    }

    @Test
    void should_pass_when_validateLanguage_given_blankOrMissing() {
        // given & then：空白 = 未配置放行（创建缺省落 Chinese、更新保持原值）
        assertDoesNotThrow(() -> KnowledgeBaseValidator.validateLanguage(null));
        assertDoesNotThrow(() -> KnowledgeBaseValidator.validateLanguage("   "));
    }

    @Test
    void should_pass_when_validateLanguage_given_legalFullName() {
        // given & then：11 全名值域命中放行（含大小写变体与首尾空白，原文落库、读取端归一）
        assertDoesNotThrow(() -> KnowledgeBaseValidator.validateLanguage("Japanese"));
        assertDoesNotThrow(() -> KnowledgeBaseValidator.validateLanguage("japanese"));
        assertDoesNotThrow(() -> KnowledgeBaseValidator.validateLanguage("  CHINESE "));
    }

    @Test
    void should_throwBadRequest_when_validateLanguage_given_unlistedLanguage() {
        // given：值域外语言（未收录全名）
        String language = "Portuguese";

        // when
        DeepDataAgentException ex = assertThrows(DeepDataAgentException.class,
                () -> KnowledgeBaseValidator.validateLanguage(language));

        // then：拒绝并提示合法值域（含 11 全名首尾项），不静默回落
        assertTrue(ex.getMessage().contains("Portuguese"));
        assertTrue(ex.getMessage().contains("English"));
        assertTrue(ex.getMessage().contains("Dutch"));
        assertTrue(ex.getMessage().contains("合法值"));
    }

    @Test
    void should_throwBadRequest_when_validateLanguage_given_bareLanguageCode() {
        // given：历史裸语言码不在全名值域，写入口直接拒绝
        // when
        DeepDataAgentException ex = assertThrows(DeepDataAgentException.class,
                () -> KnowledgeBaseValidator.validateLanguage("ja"));

        // then
        assertTrue(ex.getMessage().contains("ja"));
        assertTrue(ex.getMessage().contains("缺省视同 Chinese"));
    }

    @Test
    void should_pass_when_validateNameUnique_given_nameNotOccupied() {
        // given
        Optional<KnowledgeBase> exists = Optional.empty();

        // when // then
        assertDoesNotThrow(() -> KnowledgeBaseValidator.validateNameUnique("产品手册", exists));
    }

    @Test
    void should_throwBadRequest_when_validateNameUnique_given_blankName() {
        // given
        String name = "  ";

        // when // then
        DeepDataAgentException ex = assertThrows(DeepDataAgentException.class,
                () -> KnowledgeBaseValidator.validateNameUnique(name, Optional.empty()));
        assertTrue(ex.getMessage().contains("不能为空"));
    }

    @Test
    void should_throwBadRequest_when_validateNameUnique_given_tooLongName() {
        // given
        String name = "a".repeat(NAME_MAX_LENGTH + 1);

        // when // then
        DeepDataAgentException ex = assertThrows(DeepDataAgentException.class,
                () -> KnowledgeBaseValidator.validateNameUnique(name, Optional.empty()));
        assertTrue(ex.getMessage().contains(String.valueOf(NAME_MAX_LENGTH)));
    }

    @Test
    void should_throwConflict_when_validateNameUnique_given_nameOccupied() {
        // given
        Optional<KnowledgeBase> exists = Optional.of(buildKb(1L, LifecycleStatus.ACTIVE));

        // when // then
        ResourceConflictException ex = assertThrows(ResourceConflictException.class,
                () -> KnowledgeBaseValidator.validateNameUnique("产品手册", exists));
        assertTrue(ex.getMessage().contains("已存在"));
    }

    @Test
    void should_pass_when_validateNotDeleting_given_activeKnowledgeBase() {
        // given
        KnowledgeBase knowledgeBase = buildKb(1L, LifecycleStatus.ACTIVE);

        // when // then
        assertDoesNotThrow(() -> KnowledgeBaseValidator.validateNotDeleting(knowledgeBase));
    }

    @Test
    void should_throwBadRequest_when_validateNotDeleting_given_deletingKnowledgeBase() {
        // given
        KnowledgeBase knowledgeBase = buildKb(1L, LifecycleStatus.DELETING);

        // when // then
        assertThrows(DeepDataAgentException.class, () -> KnowledgeBaseValidator.validateNotDeleting(knowledgeBase));
    }

    @Test
    void should_throwBadRequest_when_validateNotDeleting_given_nullKnowledgeBase() {
        // given
        KnowledgeBase knowledgeBase = null;

        // when // then
        assertThrows(DeepDataAgentException.class, () -> KnowledgeBaseValidator.validateNotDeleting(knowledgeBase));
    }

    @Test
    void should_fallbackToCreatedAt_when_parseSortField_given_blankValue() {
        // given // when // then（null / 空白均回退默认创建时间字段）
        assertEquals(KnowledgeBaseSortField.CREATED_AT, KnowledgeBaseValidator.parseSortField(null));
        assertEquals(KnowledgeBaseSortField.CREATED_AT, KnowledgeBaseValidator.parseSortField("   "));
    }

    @Test
    void should_returnMatchedField_when_parseSortField_given_whitelistValueIgnoreCase() {
        // given // when // then（大小写不敏感匹配白名单）
        assertEquals(KnowledgeBaseSortField.NAME, KnowledgeBaseValidator.parseSortField("NAME"));
        assertEquals(KnowledgeBaseSortField.CREATED_AT, KnowledgeBaseValidator.parseSortField("createdAt"));
        assertEquals(KnowledgeBaseSortField.UPDATED_AT, KnowledgeBaseValidator.parseSortField(" updatedAt "));
    }

    @Test
    void should_throwBadRequest_when_parseSortField_given_unsupportedField() {
        // given（id 不在排序白名单内）
        String sortBy = "id";

        // when // then（白名单外取值必须拒绝而非静默忽略）
        DeepDataAgentException ex = assertThrows(DeepDataAgentException.class,
                () -> KnowledgeBaseValidator.parseSortField(sortBy));
        assertTrue(ex.getMessage().contains("不支持的排序字段"));
    }

    @Test
    void should_fallbackToDescending_when_parseAscendingSortOrder_given_blankValue() {
        // given // when // then（null / 空白默认倒序）
        assertFalse(KnowledgeBaseValidator.parseAscendingSortOrder(null));
        assertFalse(KnowledgeBaseValidator.parseAscendingSortOrder(" "));
    }

    @Test
    void should_returnDirection_when_parseAscendingSortOrder_given_validOrderIgnoreCase() {
        // given // when // then
        assertTrue(KnowledgeBaseValidator.parseAscendingSortOrder("ASC"));
        assertFalse(KnowledgeBaseValidator.parseAscendingSortOrder("desc"));
    }

    @Test
    void should_throwBadRequest_when_parseAscendingSortOrder_given_unsupportedOrder() {
        // given
        String sortOrder = "descending";

        // when // then
        DeepDataAgentException ex = assertThrows(DeepDataAgentException.class,
                () -> KnowledgeBaseValidator.parseAscendingSortOrder(sortOrder));
        assertTrue(ex.getMessage().contains("不支持的排序方向"));
    }
}
