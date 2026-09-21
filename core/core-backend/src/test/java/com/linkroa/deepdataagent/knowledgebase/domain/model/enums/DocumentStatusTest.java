package com.linkroa.deepdataagent.knowledgebase.domain.model.enums;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DocumentStatus} 值域单测：锁定六态值域，
 * 「已删除」不再是持久状态（收口为删除文档行、行缺失即已删除），DELETED 常量必须保持移除。
 */
class DocumentStatusTest {

    @Test
    void should_containExactlySixStates_when_values_given_currentDomain() {
        // when & then：值域锁定为 PENDING / PROCESSING / PROCESSED / FAILED / DELETING / DELETE_FAILED 六态
        assertEquals(6, DocumentStatus.values().length);
        assertTrue(Arrays.asList(DocumentStatus.values()).contains(DocumentStatus.PENDING));
        assertTrue(Arrays.asList(DocumentStatus.values()).contains(DocumentStatus.PROCESSING));
        assertTrue(Arrays.asList(DocumentStatus.values()).contains(DocumentStatus.PROCESSED));
        assertTrue(Arrays.asList(DocumentStatus.values()).contains(DocumentStatus.FAILED));
        assertTrue(Arrays.asList(DocumentStatus.values()).contains(DocumentStatus.DELETING));
        assertTrue(Arrays.asList(DocumentStatus.values()).contains(DocumentStatus.DELETE_FAILED));
    }

    @Test
    void should_notContainDeleted_when_valueOf_given_deletedName() {
        // when & then：DELETED 已退出持久状态值域，按名解析必须抛异常
        assertFalse(Arrays.stream(DocumentStatus.values())
                .anyMatch(status -> "DELETED".equals(status.name())));
        assertThrows(IllegalArgumentException.class, () -> DocumentStatus.valueOf("DELETED"));
    }
}
