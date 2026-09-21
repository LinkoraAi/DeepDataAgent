package com.linkroa.deepdataagent.knowledgebase.domain.model;

import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.DocumentStatus;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.FileType;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.ImportType;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Document} 聚合根单元测试。
 * <p>重点覆盖创建不变量与 errorMessage（处理失败原因）的复制语义。</p>
 */
class DocumentTest {

    /** 固定时间基准，便于断言 updatedAt 刷新 */
    private static final OffsetDateTime PAST = OffsetDateTime.parse("2026-09-01T10:00:00+08:00");

    private static Document failedDocument() {
        return Document.restore(1L, 10L, "手册.pdf", FileType.PDF, DocumentStatus.FAILED,
                "解析失败：向量模型超时", 1024L, 4, "{\"pages\":3}", ImportType.UPLOAD,
                null, null, null, PAST, PAST);
    }

    @Test
    void should_haveNullErrorMessageAndPending_when_create_given_validInput() {
        // when
        Document document = Document.create(10L, "手册.pdf", FileType.PDF, 1024L,
                "{\"pages\":3}", null, null, null);

        // then
        assertEquals(DocumentStatus.PENDING, document.status());
        assertNull(document.errorMessage());
        assertEquals(0, document.chunkCount().intValue());
        assertEquals(ImportType.UPLOAD, document.importType());
    }

    @Test
    void should_throwIllegalArgument_when_create_given_blankFileName() {
        // when // then
        assertThrows(IllegalArgumentException.class, () -> Document.create(10L, "   ",
                FileType.PDF, 1024L, null, null, null, null));
    }

    @Test
    void should_throwIllegalArgument_when_create_given_nullKbId() {
        // when // then
        assertThrows(IllegalArgumentException.class, () -> Document.create(null, "手册.pdf",
                FileType.PDF, 1024L, null, null, null, null));
    }

    @Test
    void should_passThroughErrorMessage_when_restore_given_failedDocument() {
        // given
        Document document = failedDocument();

        // when // then
        assertEquals(DocumentStatus.FAILED, document.status());
        assertEquals("解析失败：向量模型超时", document.errorMessage());
        assertEquals(PAST, document.updatedAt());
    }

    @Test
    void should_keepErrorMessage_when_withStatus_given_failedDocument() {
        // given：状态迁移本身不清除失败原因，清除由显式 withErrorMessage(null) 承担
        Document document = failedDocument();

        // when
        Document updated = document.withStatus(DocumentStatus.PENDING);

        // then
        assertEquals(DocumentStatus.PENDING, updated.status());
        assertEquals("解析失败：向量模型超时", updated.errorMessage());
        assertEquals("手册.pdf", updated.fileName());
    }

    @Test
    void should_overrideErrorMessage_when_withErrorMessage_given_newReason() {
        // given
        Document document = failedDocument();

        // when
        Document updated = document.withErrorMessage("解析失败：文件损坏");

        // then：仅覆盖失败原因，其余业务组件保持不变
        assertEquals("解析失败：文件损坏", updated.errorMessage());
        assertEquals(DocumentStatus.FAILED, updated.status());
        assertEquals(1L, updated.id());
        assertEquals(4, updated.chunkCount().intValue());
    }

    @Test
    void should_clearErrorMessage_when_withErrorMessage_given_null() {
        // given
        Document document = failedDocument();

        // when
        Document cleared = document.withErrorMessage(null);

        // then
        assertNull(cleared.errorMessage());
        assertEquals(DocumentStatus.FAILED, cleared.status());
    }

    @Test
    void should_refreshUpdatedAt_when_withErrorMessage_given_originalPastTime() {
        // given
        Document document = failedDocument();

        // when
        Document updated = document.withErrorMessage(null);

        // then
        assertTrue(updated.updatedAt().isAfter(PAST));
        assertEquals(PAST, updated.createdAt());
    }

    @Test
    void should_overrideChunkCountOnly_when_withChunkCount_given_newCount() {
        // given
        Document document = failedDocument();

        // when
        Document updated = document.withChunkCount(7);

        // then：仅覆盖分块数量，状态与失败原因等其余字段原样复制
        assertEquals(7, updated.chunkCount().intValue());
        assertEquals(DocumentStatus.FAILED, updated.status());
        assertEquals("解析失败：向量模型超时", updated.errorMessage());
        assertEquals(PAST, updated.createdAt());
        assertTrue(updated.updatedAt().isAfter(PAST));
    }

    @Test
    void should_overrideChunkStrategyOnly_when_withChunkStrategy_given_newStrategy() {
        // given
        Document document = failedDocument();

        // when
        Document updated = document.withChunkStrategy("{\"size\":512}");

        // then：仅覆盖分块策略，分块数量等其余字段不受影响
        assertEquals("{\"size\":512}", updated.chunkStrategy());
        assertEquals(4, updated.chunkCount().intValue());
        assertEquals(PAST, updated.createdAt());
        assertTrue(updated.updatedAt().isAfter(PAST));
    }
}
