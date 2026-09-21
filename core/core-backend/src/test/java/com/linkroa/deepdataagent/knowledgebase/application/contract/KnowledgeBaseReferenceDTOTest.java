package com.linkroa.deepdataagent.knowledgebase.application.contract;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link KnowledgeBaseReferenceDTO} 契约边界校验单测。
 */
class KnowledgeBaseReferenceDTOTest {

    /** 测试用知识库ID */
    private static final Long KB_ID = 10L;

    @Test
    void should_createDto_when_constructed_given_validFields() {
        // given // when
        KnowledgeBaseReferenceDTO dto = new KnowledgeBaseReferenceDTO(KB_ID, "产品手册", "ACTIVE");

        // then
        assertEquals(KB_ID, dto.kbId());
        assertEquals("产品手册", dto.name());
        assertEquals("ACTIVE", dto.lifecycleStatus());
    }

    @Test
    void should_throwException_when_constructed_given_nullKbId() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> new KnowledgeBaseReferenceDTO(null, "产品手册", "ACTIVE"));
    }

    @Test
    void should_throwException_when_constructed_given_blankName() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> new KnowledgeBaseReferenceDTO(KB_ID, " ", "ACTIVE"));
    }

    @Test
    void should_throwException_when_constructed_given_blankLifecycleStatus() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> new KnowledgeBaseReferenceDTO(KB_ID, "产品手册", " "));
    }
}
