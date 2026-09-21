package com.linkroa.deepdataagent.knowledgebase.application.validation;

import com.linkroa.deepdataagent.knowledgebase.domain.model.Document;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.DocumentStatus;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.FileType;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.ImportType;
import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import com.linkroa.deepdataagent.shared.exception.ResourceConflictException;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DocumentValidator} 单元测试。
 */
class DocumentValidatorTest {

    /** 测试用文件大小上限（字节），对应 100MB；对齐 multipart 配置注入的语义 */
    private static final long MAX_BYTES = 100L * 1024L * 1024L;

    /** 合法的判重内容哈希：64 位小写十六进制 SHA-256 */
    private static final String VALID_HASH = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    /** 合法内容哈希的大写形态（同一算法的表示法差异，应被归一化为小写） */
    private static final String UPPER_VALID_HASH = "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD";

    /** 内容哈希形态非法时错误文案的特征片段 */
    private static final String HASH_SHAPE_MESSAGE = "64 位十六进制 SHA-256";

    private Document buildDocument(DocumentStatus status) {
        OffsetDateTime now = OffsetDateTime.now();
        return Document.restore(1L, 10L, "手册.pdf", FileType.PDF, status, null, 1024L, 3,
                null, ImportType.UPLOAD, null, null, null, now, now);
    }

    @Test
    void should_returnFileType_when_validateFileType_given_upperCaseEnumName() {
        // given
        String fileType = "PDF";

        // when
        FileType result = DocumentValidator.validateFileType(fileType);

        // then
        assertEquals(FileType.PDF, result);
    }

    @Test
    void should_returnFileType_when_validateFileType_given_extensionWithDotAndLowerCase() {
        // given
        String fileType = ".md";

        // when
        FileType result = DocumentValidator.validateFileType(fileType);

        // then
        assertEquals(FileType.MD, result);
    }

    @Test
    void should_throwBadRequest_when_validateFileType_given_blankValue() {
        // given
        String fileType = " ";

        // when // then
        assertThrows(DeepDataAgentException.class, () -> DocumentValidator.validateFileType(fileType));
    }

    @Test
    void should_throwBadRequest_when_validateFileType_given_unsupportedValue() {
        // given
        String fileType = "exe";

        // when // then
        DeepDataAgentException ex = assertThrows(DeepDataAgentException.class,
                () -> DocumentValidator.validateFileType(fileType));
        assertTrue(ex.getMessage().contains("不支持的文件格式"));
    }

    @Test
    void should_pass_when_validateFileSize_given_nullSize() {
        // given
        Long fileSize = null;

        // when // then
        assertDoesNotThrow(() -> DocumentValidator.validateFileSize(fileSize, MAX_BYTES));
    }

    @Test
    void should_pass_when_validateFileSize_given_sizeEqualsMax() {
        // given
        long max = MAX_BYTES;

        // when // then
        assertDoesNotThrow(() -> DocumentValidator.validateFileSize(max, max));
    }

    @Test
    void should_throwBadRequest_when_validateFileSize_given_negativeSize() {
        // given
        Long fileSize = -1L;

        // when // then
        DeepDataAgentException ex = assertThrows(DeepDataAgentException.class,
                () -> DocumentValidator.validateFileSize(fileSize, MAX_BYTES));
        assertTrue(ex.getMessage().contains("文件大小非法"));
    }

    @Test
    void should_throwBadRequest_when_validateFileSize_given_sizeExceedsMax() {
        // given
        Long fileSize = MAX_BYTES + 1;

        // when // then
        DeepDataAgentException ex = assertThrows(DeepDataAgentException.class,
                () -> DocumentValidator.validateFileSize(fileSize, MAX_BYTES));
        assertTrue(ex.getMessage().contains("MB"));
    }

    @Test
    void should_pass_when_validateCanReparse_given_pendingDocument() {
        // given
        Document document = buildDocument(DocumentStatus.PENDING);

        // when // then
        assertDoesNotThrow(() -> DocumentValidator.validateCanReparse(document));
    }

    @Test
    void should_pass_when_validateCanReparse_given_processedDocument() {
        // given
        Document document = buildDocument(DocumentStatus.PROCESSED);

        // when // then
        assertDoesNotThrow(() -> DocumentValidator.validateCanReparse(document));
    }

    @Test
    void should_pass_when_validateCanReparse_given_failedDocument() {
        // given
        Document document = buildDocument(DocumentStatus.FAILED);

        // when // then
        assertDoesNotThrow(() -> DocumentValidator.validateCanReparse(document));
    }

    @Test
    void should_pass_when_validateCanReparse_given_nullDocument() {
        // given
        Document document = null;

        // when // then
        assertDoesNotThrow(() -> DocumentValidator.validateCanReparse(document));
    }

    @Test
    void should_throwConflict_when_validateCanReparse_given_processingDocument() {
        // given
        Document document = buildDocument(DocumentStatus.PROCESSING);

        // when // then
        ResourceConflictException ex = assertThrows(ResourceConflictException.class,
                () -> DocumentValidator.validateCanReparse(document));
        assertTrue(ex.getMessage().contains("正在解析中"));
    }

    @Test
    void should_throwConflict_when_validateCanReparse_given_deletingDocument() {
        // given：删除链两态之一（DELETING），拒绝与删除链并发写同一文档
        Document document = buildDocument(DocumentStatus.DELETING);

        // when // then
        ResourceConflictException ex = assertThrows(ResourceConflictException.class,
                () -> DocumentValidator.validateCanReparse(document));
        assertTrue(ex.getMessage().contains("已进入删除流程"));
    }

    @Test
    void should_throwConflict_when_validateCanReparse_given_deleteFailedDocument() {
        // given：删除链两态之二（DELETE_FAILED），重删续跑前不允许改道重解析
        Document document = buildDocument(DocumentStatus.DELETE_FAILED);

        // when // then
        ResourceConflictException ex = assertThrows(ResourceConflictException.class,
                () -> DocumentValidator.validateCanReparse(document));
        assertTrue(ex.getMessage().contains("已进入删除流程"));
    }

    @Test
    void should_returnNull_when_parseStatusOrNull_given_blankStatus() {
        // given
        String status = "";

        // when
        DocumentStatus result = DocumentValidator.parseStatusOrNull(status);

        // then
        assertNull(result);
    }

    @Test
    void should_returnStatus_when_parseStatusOrNull_given_lowerCaseStatus() {
        // given
        String status = "processed";

        // when
        DocumentStatus result = DocumentValidator.parseStatusOrNull(status);

        // then
        assertEquals(DocumentStatus.PROCESSED, result);
    }

    @Test
    void should_returnDeleting_when_parseStatusOrNull_given_deletingStatus() {
        // given
        String status = "DELETING";

        // when
        DocumentStatus result = DocumentValidator.parseStatusOrNull(status);

        // then
        assertEquals(DocumentStatus.DELETING, result);
    }

    @Test
    void should_returnDeleteFailed_when_parseStatusOrNull_given_lowerCaseUnderscoreStatus() {
        // given
        String status = "delete_failed";

        // when
        DocumentStatus result = DocumentValidator.parseStatusOrNull(status);

        // then
        assertEquals(DocumentStatus.DELETE_FAILED, result);
    }

    @Test
    void should_throwBadRequest_when_parseStatusOrNull_given_legacyDeletedStatus() {
        // given：DELETED 已退出持久状态值域，旧词按非法值拒绝
        String status = "DELETED";

        // when // then
        assertThrows(DeepDataAgentException.class, () -> DocumentValidator.parseStatusOrNull(status));
    }

    @Test
    void should_throwBadRequest_when_parseStatusOrNull_given_illegalStatus() {
        // given
        String status = "RUNNING";

        // when // then
        assertThrows(DeepDataAgentException.class, () -> DocumentValidator.parseStatusOrNull(status));
    }

    @Test
    void should_throwBadRequest_when_validatePrecheckAxes_given_bothBlank() {
        // given
        String fileName = " ";
        String contentHash = null;

        // when // then
        DeepDataAgentException ex = assertThrows(DeepDataAgentException.class,
                () -> DocumentValidator.validatePrecheckAxes(fileName, contentHash));
        assertTrue(ex.getMessage().contains("至少提供"));
    }

    @Test
    void should_returnNullHash_when_validatePrecheckAxes_given_onlyFileName() {
        // given：仅文件名轴，内容哈希为空白
        String fileName = "手册.pdf";
        String contentHash = "  ";

        // when
        String normalizedHash = DocumentValidator.validatePrecheckAxes(fileName, contentHash);

        // then：归一化结果为 null（仅文件名轴参与判重）
        assertNull(normalizedHash);
    }

    @Test
    void should_returnHash_when_validatePrecheckAxes_given_onlyContentHash() {
        // given：仅内容哈希轴
        String fileName = null;

        // when
        String normalizedHash = DocumentValidator.validatePrecheckAxes(fileName, VALID_HASH);

        // then：合法取值原样返回
        assertEquals(VALID_HASH, normalizedHash);
    }

    @Test
    void should_returnLowercaseHash_when_validatePrecheckAxes_given_uppercaseHash() {
        // given：大写十六进制为同一算法（SHA-256）的表示法差异
        String fileName = null;

        // when
        String normalizedHash = DocumentValidator.validatePrecheckAxes(fileName, UPPER_VALID_HASH);

        // then：归一化为小写
        assertEquals(VALID_HASH, normalizedHash);
    }

    @Test
    void should_returnTrimmedHash_when_validatePrecheckAxes_given_hashWithSurroundingSpaces() {
        // given
        String fileName = null;

        // when
        String normalizedHash = DocumentValidator.validatePrecheckAxes(fileName, "  " + UPPER_VALID_HASH + "  ");

        // then：先去空白再归一化大小写
        assertEquals(VALID_HASH, normalizedHash);
    }

    @Test
    void should_throwBadRequest_when_validatePrecheckAxes_given_md5LengthHash() {
        // given：32 位 MD5 摘要属不同算法，一律拒绝
        String fileName = null;
        String contentHash = "900150983cd24fb0d6963f7d28e17f72";

        // when // then
        DeepDataAgentException ex = assertThrows(DeepDataAgentException.class,
                () -> DocumentValidator.validatePrecheckAxes(fileName, contentHash));
        assertTrue(ex.getMessage().contains(HASH_SHAPE_MESSAGE));
    }

    @Test
    void should_throwBadRequest_when_validatePrecheckAxes_given_hashLongerThan64() {
        // given
        String fileName = null;
        String contentHash = VALID_HASH + "a";

        // when // then
        DeepDataAgentException ex = assertThrows(DeepDataAgentException.class,
                () -> DocumentValidator.validatePrecheckAxes(fileName, contentHash));
        assertTrue(ex.getMessage().contains(HASH_SHAPE_MESSAGE));
    }

    @Test
    void should_throwBadRequest_when_validatePrecheckAxes_given_hashWithNonHexChar() {
        // given：长度恰为 64 但含非十六进制字符
        String fileName = null;
        String contentHash = VALID_HASH.substring(0, VALID_HASH.length() - 1) + "z";

        // when // then
        DeepDataAgentException ex = assertThrows(DeepDataAgentException.class,
                () -> DocumentValidator.validatePrecheckAxes(fileName, contentHash));
        assertTrue(ex.getMessage().contains(HASH_SHAPE_MESSAGE));
    }

    @Test
    void should_pass_when_validateChunkOperationAllowed_given_pendingDocument() {
        // given：六态口径中的非删除链态（PENDING）放行
        Document document = buildDocument(DocumentStatus.PENDING);

        // when // then
        assertDoesNotThrow(() -> DocumentValidator.validateChunkOperationAllowed(document));
    }

    @Test
    void should_pass_when_validateChunkOperationAllowed_given_processedDocument() {
        // given
        Document document = buildDocument(DocumentStatus.PROCESSED);

        // when // then
        assertDoesNotThrow(() -> DocumentValidator.validateChunkOperationAllowed(document));
    }

    @Test
    void should_pass_when_validateChunkOperationAllowed_given_failedDocument() {
        // given：FAILED 属摄入失败态，不在删除链，人工切片操作放行
        Document document = buildDocument(DocumentStatus.FAILED);

        // when // then
        assertDoesNotThrow(() -> DocumentValidator.validateChunkOperationAllowed(document));
    }

    @Test
    void should_throwConflict_when_validateChunkOperationAllowed_given_deletingDocument() {
        // given：DELETING 清退进行中，拒绝人工删除与分批清退赛跑
        Document document = buildDocument(DocumentStatus.DELETING);

        // when // then
        ResourceConflictException ex = assertThrows(ResourceConflictException.class,
                () -> DocumentValidator.validateChunkOperationAllowed(document));
        assertTrue(ex.getMessage().contains("已进入删除流程"));
    }

    @Test
    void should_throwConflict_when_validateChunkOperationAllowed_given_deleteFailedDocument() {
        // given：DELETE_FAILED 待用户重删，非 ACTIVE 链资源拒绝一切内容读写
        Document document = buildDocument(DocumentStatus.DELETE_FAILED);

        // when // then
        ResourceConflictException ex = assertThrows(ResourceConflictException.class,
                () -> DocumentValidator.validateChunkOperationAllowed(document));
        assertTrue(ex.getMessage().contains("已进入删除流程"));
    }

    @Test
    void should_pass_when_validateChunkOperationAllowed_given_nullDocument() {
        // given：行缺失由调用方按 404 前置兜底，本闸门不做校验
        Document document = null;

        // when // then
        assertDoesNotThrow(() -> DocumentValidator.validateChunkOperationAllowed(document));
    }
}
