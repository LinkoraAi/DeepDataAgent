package com.linkroa.deepdataagent.knowledgebase.application.service;

import com.linkroa.deepdataagent.knowledgebase.application.port.KbAssetStoragePort;
import com.linkroa.deepdataagent.knowledgebase.domain.model.Document;
import com.linkroa.deepdataagent.knowledgebase.domain.model.S3File;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.DocumentStatus;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.FileType;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.ImportType;
import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link MediaImageCleanupService} 单元测试（桶概念退役后口径）。
 * <p>覆盖：文档级前缀单次清退与 ID 异常跳过、源文件对象定点回收与引用缺失宽松跳过、
 * 宽松引用解析（仅对象键形态）、切片图片对象回收的幂等与失败转业务异常、
 * 整库单次前缀清退与存储异常上抛。</p>
 */
@ExtendWith(MockitoExtension.class)
class MediaImageCleanupServiceTest {

    private static final Long TEST_KB_ID = 7L;

    private static final Long TEST_DOCUMENT_ID = 42L;

    private static final String DOCUMENT_PREFIX = "rag/7/42/images/";

    private static final String KB_PREFIX = "rag/7/";

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-01T10:00:00+08:00");

    @Mock
    private KbAssetStoragePort kbAssetStoragePort;

    @InjectMocks
    private MediaImageCleanupService mediaImageCleanupService;

    /**
     * 构造携带源文件存储引用的文档聚合根。
     *
     * @param kbId       知识库ID
     * @param documentId 文档ID
     * @param s3File     源文件存储引用 JSON，可为 null
     * @return 文档聚合根
     */
    private static Document documentOf(Long kbId, Long documentId, String s3File) {
        return Document.restore(documentId, kbId, "手册.pdf", FileType.PDF, DocumentStatus.PROCESSED, null,
                1024L, 4, "{\"pageCount\":2}", ImportType.UPLOAD, s3File, null, null, NOW, NOW);
    }

    // ==================== cleanupDocumentImages ====================

    @Test
    void should_cleanupDocumentPrefix_when_cleanupDocumentImages_given_validDocument() {
        // given 文档 ID 齐备（前缀仅由 ID 派生，无需任何存储引用）
        Document document = documentOf(TEST_KB_ID, TEST_DOCUMENT_ID, null);

        // when
        mediaImageCleanupService.cleanupDocumentImages(document);

        // then 按 rag/{kbId}/{documentId}/images/ 前缀单次清退
        verify(kbAssetStoragePort).cleanupPrefix(DOCUMENT_PREFIX);
    }

    @Test
    void should_skipWithoutStorageCall_when_cleanupDocumentImages_given_nullDocument() {
        // when / then 文档为空仅 WARN 跳过，零存储交互
        mediaImageCleanupService.cleanupDocumentImages(null);
        verifyNoInteractions(kbAssetStoragePort);
    }

    @Test
    void should_skipCleanup_when_cleanupDocumentImages_given_nullDocumentId() {
        // given 文档 ID 异常（kbId 正常），前缀无法定位
        Document document = documentOf(TEST_KB_ID, null, null);

        // when / then 不抛异常、不清理（MUST NOT 因 ID 异常阻断删除主流程）
        mediaImageCleanupService.cleanupDocumentImages(document);
        verifyNoInteractions(kbAssetStoragePort);
    }

    @Test
    void should_propagateException_when_cleanupDocumentImages_given_storageFailure() {
        // given 前缀清退抛存储异常
        Document document = documentOf(TEST_KB_ID, TEST_DOCUMENT_ID, null);
        doThrow(new IllegalStateException("存储不可达")).when(kbAssetStoragePort).cleanupPrefix(DOCUMENT_PREFIX);

        // when / then 异常原样上抛，由删除链留痕重删续跑
        assertThrows(IllegalStateException.class,
                () -> mediaImageCleanupService.cleanupDocumentImages(document));
    }

    // ==================== cleanupSourceFiles ====================

    @Test
    void should_deleteRegisteredObjectOnce_when_cleanupSourceFiles_given_validReference() {
        // given 文档登记了仅对象键的源文件引用
        String objectKey = "rag/7/source/3f2a.pdf";
        Document document = documentOf(TEST_KB_ID, TEST_DOCUMENT_ID,
                "{\"objectKey\":\"" + objectKey + "\"}");

        // when
        mediaImageCleanupService.cleanupSourceFiles(document);

        // then 按登记引用定点删除一次（端口删除幂等：对象不存在视为成功）
        verify(kbAssetStoragePort).delete(objectKey);
    }

    @Test
    void should_skipWithoutStorageCall_when_cleanupSourceFiles_given_missingOrIncompleteReference() {
        // given 引用缺失 / 非法 JSON / 缺对象键 / 对象键空白，均无法精确定位源文件对象
        Document nullReference = documentOf(TEST_KB_ID, TEST_DOCUMENT_ID, null);
        Document illegalJson = documentOf(TEST_KB_ID, TEST_DOCUMENT_ID, "not-a-json{{{");
        Document missingKey = documentOf(TEST_KB_ID, TEST_DOCUMENT_ID, "{\"other\":\"x\"}");
        Document blankObjectKey = documentOf(TEST_KB_ID, TEST_DOCUMENT_ID, "{\"objectKey\":\" \"}");

        // when / then WARN 留痕跳过（幂等），零存储交互
        mediaImageCleanupService.cleanupSourceFiles(nullReference);
        mediaImageCleanupService.cleanupSourceFiles(illegalJson);
        mediaImageCleanupService.cleanupSourceFiles(missingKey);
        mediaImageCleanupService.cleanupSourceFiles(blankObjectKey);
        verifyNoInteractions(kbAssetStoragePort);
    }

    @Test
    void should_skipWithoutException_when_cleanupSourceFiles_given_nullDocument() {
        // when / then 文档为空仅 WARN 跳过
        mediaImageCleanupService.cleanupSourceFiles(null);
        verifyNoInteractions(kbAssetStoragePort);
    }

    @Test
    void should_throwUpWithoutSwallowing_when_cleanupSourceFiles_given_storageDeleteFailure() {
        // given 定点删除时存储抛出异常（不可达）
        String objectKey = "rag/7/source/fail.pdf";
        Document document = documentOf(TEST_KB_ID, TEST_DOCUMENT_ID, "{\"objectKey\":\"" + objectKey + "\"}");
        doThrow(new IllegalStateException("存储不可达")).when(kbAssetStoragePort).delete(objectKey);

        // when / then 异常原样上抛，由调用方按终删 DELETE_FAILED 续跑 / 换版 WARN 尽力而为处置
        assertThrows(IllegalStateException.class,
                () -> mediaImageCleanupService.cleanupSourceFiles(document));
        verify(kbAssetStoragePort).delete(objectKey);
    }

    // ==================== cleanupKnowledgeBaseImages ====================

    @Test
    void should_cleanupKbPrefixOnce_when_cleanupKnowledgeBaseImages_given_validKbId() {
        // when 整库清退（单次前缀删除覆盖源文件与全部媒体图片）
        mediaImageCleanupService.cleanupKnowledgeBaseImages(TEST_KB_ID);

        // then
        verify(kbAssetStoragePort).cleanupPrefix(KB_PREFIX);
    }

    @Test
    void should_skipWithoutException_when_cleanupKnowledgeBaseImages_given_nullKbId() {
        // when / then kbId 异常仅 WARN 跳过，不抛异常、零存储交互
        mediaImageCleanupService.cleanupKnowledgeBaseImages(null);
        verifyNoInteractions(kbAssetStoragePort);
    }

    @Test
    void should_throw_when_cleanupKnowledgeBaseImages_given_storageFailure() {
        // given 整库前缀清退失败（存储不可达）
        doThrow(new IllegalStateException("存储不可达")).when(kbAssetStoragePort).cleanupPrefix(KB_PREFIX);

        // when / then 异常上抛，由调度器按「本轮暂缓 DB 清退 + 下轮重入」口径处置
        assertThrows(IllegalStateException.class,
                () -> mediaImageCleanupService.cleanupKnowledgeBaseImages(TEST_KB_ID));
    }

    // ==================== parseMediaReference / recycleChunkMediaObject ====================

    @Test
    void should_returnReference_when_parseMediaReference_given_completeJson() {
        // given 仅对象键形态的完整引用
        String json = "{\"objectKey\":\"rag/7/42/images/a.png\"}";

        // when
        S3File reference = mediaImageCleanupService.parseMediaReference(json);

        // then
        assertEquals("rag/7/42/images/a.png", reference.objectKey());
    }

    @Test
    void should_returnNull_when_parseMediaReference_given_blankIllegalOrMissingKey() {
        // given / when / then 空白、非法 JSON、缺对象键一律宽松返回 null（文本切片属预期）
        assertNull(mediaImageCleanupService.parseMediaReference(null));
        assertNull(mediaImageCleanupService.parseMediaReference("  "));
        assertNull(mediaImageCleanupService.parseMediaReference("not-a-json{{"));
        assertNull(mediaImageCleanupService.parseMediaReference("{\"other\":\"x\"}"));
        assertNull(mediaImageCleanupService.parseMediaReference("{\"objectKey\":\" \"}"));
    }

    @Test
    void should_deleteObjectOnce_when_recycleChunkMediaObject_given_validReference() {
        // given
        S3File mediaObject = new S3File("rag/7/42/images/a.png");

        // when 端口删除天然幂等（对象不存在不抛错）
        mediaImageCleanupService.recycleChunkMediaObject(mediaObject);

        // then
        verify(kbAssetStoragePort).delete("rag/7/42/images/a.png");
    }

    @Test
    void should_skipWithoutStorageCall_when_recycleChunkMediaObject_given_nullReference() {
        // given / when 无引用即无可回收对象
        mediaImageCleanupService.recycleChunkMediaObject(null);

        // then
        verifyNoInteractions(kbAssetStoragePort);
    }

    @Test
    void should_throwBusinessErrorWithoutSwallowing_when_recycleChunkMediaObject_given_storageFailure() {
        // given 事务前硬顺序：对象回收失败必须终止本次删除（数据库零写入），不吞异常
        S3File mediaObject = new S3File("rag/7/42/images/fail.png");
        doThrow(new IllegalStateException("存储不可达"))
                .when(kbAssetStoragePort).delete("rag/7/42/images/fail.png");

        // when / then 转换为业务异常上抛，提示文案指引用户重删
        DeepDataAgentException ex = assertThrows(DeepDataAgentException.class,
                () -> mediaImageCleanupService.recycleChunkMediaObject(mediaObject));
        assertTrue(ex.getMessage().contains("请重新执行删除"));
        verify(kbAssetStoragePort).delete("rag/7/42/images/fail.png");
    }
}
