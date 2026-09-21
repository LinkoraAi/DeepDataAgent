package com.linkroa.deepdataagent.knowledgebase.controller.rest;

import com.linkroa.deepdataagent.knowledgebase.application.command.DeleteDocumentCommand;
import com.linkroa.deepdataagent.knowledgebase.application.command.ReparseDocumentCommand;
import com.linkroa.deepdataagent.knowledgebase.application.command.UploadDocumentCommand;
import com.linkroa.deepdataagent.knowledgebase.application.query.ListDocumentQuery;
import com.linkroa.deepdataagent.knowledgebase.application.result.DedupHit;
import com.linkroa.deepdataagent.knowledgebase.application.result.DocumentContentResult;
import com.linkroa.deepdataagent.knowledgebase.application.result.UploadDocumentResult;
import com.linkroa.deepdataagent.knowledgebase.application.service.DocumentApplicationService;
import com.linkroa.deepdataagent.knowledgebase.controller.request.PrecheckUploadRequest;
import com.linkroa.deepdataagent.knowledgebase.controller.request.ReparseDocumentRequest;
import com.linkroa.deepdataagent.knowledgebase.controller.request.UploadDocumentRequest;
import com.linkroa.deepdataagent.knowledgebase.controller.response.DocumentResponse;
import com.linkroa.deepdataagent.knowledgebase.controller.response.DuplicateDocumentResponse;
import com.linkroa.deepdataagent.knowledgebase.controller.response.UploadDocumentResponse;
import com.linkroa.deepdataagent.knowledgebase.domain.model.Document;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.DocumentStatus;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.FileType;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.ImportType;
import com.linkroa.deepdataagent.shared.result.ApiResponse;
import com.linkroa.deepdataagent.shared.result.PaginatedResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DocumentController} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class DocumentControllerTest {

    /** 源文件存储引用 JSON（仅对象键形态，桶概念已退役） */
    private static final String S3_FILE = "{\"objectKey\":\"rag/10/source/a.pdf\"}";

    /** multipart 上传桩文件字节内容（用于断言服务端原样接收字节流） */
    private static final byte[] FILE_BYTES = "%PDF-1.4 fake pdf bytes".getBytes(StandardCharsets.UTF_8);

    @Mock
    private DocumentApplicationService applicationService;

    @InjectMocks
    private DocumentController controller;

    @Test
    void should_returnDocument_when_upload_given_validRequest() throws Exception {
        // given
        when(applicationService.upload(any(UploadDocumentCommand.class)))
                .thenReturn(UploadDocumentResult.registered(document(1L)));

        // when
        ApiResponse<UploadDocumentResponse> response = controller.upload(uploadFile(), uploadMeta());

        // then
        assertTrue(response.success());
        assertFalse(response.data().skipped());
        assertEquals(1L, response.data().document().id());
        assertEquals("a.pdf", response.data().document().fileName());
        assertEquals(FileType.PDF.name(), response.data().document().fileType());
        assertEquals(DocumentStatus.PENDING.name(), response.data().document().status());
    }

    @Test
    void should_markSkipped_when_upload_given_duplicateSkippedByPolicy() throws Exception {
        // given
        when(applicationService.upload(any(UploadDocumentCommand.class)))
                .thenReturn(UploadDocumentResult.skipped(document(1L)));

        // when
        ApiResponse<UploadDocumentResponse> response = controller.upload(uploadFile(), uploadMeta());

        // then
        assertTrue(response.data().skipped());
        assertEquals(1L, response.data().document().id());
    }

    @Test
    void should_buildUploadCommandInOrder_when_upload_given_allFields() throws Exception {
        // given
        when(applicationService.upload(any(UploadDocumentCommand.class)))
                .thenReturn(UploadDocumentResult.registered(document(1L)));

        // when
        controller.upload(uploadFile(), uploadMeta());

        // then：标量字段取自 meta 部分，contentType 与文件字节取自 file 部分
        ArgumentCaptor<UploadDocumentCommand> captor = ArgumentCaptor.forClass(UploadDocumentCommand.class);
        verify(applicationService).upload(captor.capture());
        UploadDocumentCommand command = captor.getValue();
        assertEquals(10L, command.kbId());
        assertEquals("a.pdf", command.fileName());
        assertEquals("PDF", command.fileType());
        assertEquals("application/pdf", command.contentType());
        assertEquals("{\"pages\":3}", command.sourceFileProfile());
        assertEquals("{\"mode\":\"AUTO\"}", command.chunkStrategy());
        assertArrayEquals(FILE_BYTES, command.content());
    }

    @Test
    void should_returnAcceptedDeletingSnapshot_when_delete_given_id() {
        // given：受理段返回状态为 DELETING 的文档快照（清退由异步线程推进，202 语义）
        Document accepted = Document.restore(3L, 10L, "a.pdf", FileType.PDF, DocumentStatus.DELETING, null,
                2048L, 0, "{\"pages\":3}", ImportType.UPLOAD, S3_FILE, "{\"hash\":\"abc\"}", null, null, null);
        when(applicationService.delete(new DeleteDocumentCommand(3L))).thenReturn(accepted);

        // when
        ApiResponse<DocumentResponse> response = controller.delete(3L);

        // then：响应码为受理语义「202」、文案「删除中」，回显快照状态 DELETING
        assertTrue(response.success());
        assertEquals("202", response.code());
        assertEquals("删除中，清退任务已受理", response.message());
        assertNotNull(response.data());
        assertEquals(3L, response.data().id());
        assertEquals(DocumentStatus.DELETING.name(), response.data().status());
        verify(applicationService).delete(new DeleteDocumentCommand(3L));
    }

    @Test
    void should_useDefaultPaging_when_list_given_noPageParams() {
        // given
        when(applicationService.list(new ListDocumentQuery(10L, "a", "PENDING", 1, 10))).thenReturn(List.of());
        when(applicationService.count(10L, "a", "PENDING")).thenReturn(0L);

        // when
        ApiResponse<PaginatedResponse<DocumentResponse>> response = controller.list(10L, "a", "PENDING", null, null);

        // then
        assertEquals(1, response.data().page());
        assertEquals(10, response.data().size());
    }

    @Test
    void should_capPageSize_when_list_given_oversizedSize() {
        // given
        when(applicationService.list(new ListDocumentQuery(10L, null, null, 1, 100))).thenReturn(List.of());
        when(applicationService.count(10L, null, null)).thenReturn(0L);

        // when
        ApiResponse<PaginatedResponse<DocumentResponse>> response = controller.list(10L, null, null, null, 999);

        // then
        assertEquals(100, response.data().size());
    }

    @Test
    void should_convertEnumFields_when_list_given_documents() {
        // given
        when(applicationService.list(new ListDocumentQuery(10L, null, null, 1, 10)))
                .thenReturn(List.of(document(1L), document(2L)));
        when(applicationService.count(10L, null, null)).thenReturn(2L);

        // when
        ApiResponse<PaginatedResponse<DocumentResponse>> response = controller.list(10L, null, null, 1, 10);

        // then
        assertEquals(2, response.data().list().size());
        assertEquals(2L, response.data().total());
        assertEquals(ImportType.UPLOAD.name(), response.data().list().get(0).importType());
    }

    @Test
    void should_returnDetail_when_detail_given_existingId() {
        // given
        when(applicationService.get(2L)).thenReturn(document(2L));

        // when
        ApiResponse<DocumentResponse> response = controller.detail(2L);

        // then
        assertEquals(2L, response.data().id());
        assertEquals(10L, response.data().kbId());
    }

    @Test
    void should_reparseDocument_when_reparse_given_id() {
        // given
        when(applicationService.reparse(any(ReparseDocumentCommand.class))).thenReturn(document(4L));

        // when
        ApiResponse<DocumentResponse> response = controller.reparse(4L, null);

        // then
        verify(applicationService).reparse(new ReparseDocumentCommand(4L, null));
        assertEquals(4L, response.data().id());
    }

    @Test
    void should_passStrategyOverride_when_reparse_given_requestBody() {
        // given
        when(applicationService.reparse(any(ReparseDocumentCommand.class))).thenReturn(document(5L));

        // when
        controller.reparse(5L, new ReparseDocumentRequest("{\"chunkMode\":\"QA\"}"));

        // then
        verify(applicationService).reparse(new ReparseDocumentCommand(5L, "{\"chunkMode\":\"QA\"}"));
    }

    @Test
    void should_propagateException_when_reparse_given_documentNotReparseable() {
        // given
        when(applicationService.reparse(any(ReparseDocumentCommand.class)))
                .thenThrow(new IllegalStateException("仅失败文档可重新解析"));

        // when & then
        assertThrows(IllegalStateException.class, () -> controller.reparse(9L, null));
    }

    @Test
    void should_returnDuplicateList_when_precheck_given_hits() {
        // given
        when(applicationService.precheck(10L, "a.pdf", null))
                .thenReturn(List.of(new DedupHit(document(1L), List.of("fileName"))));

        // when
        ApiResponse<List<DuplicateDocumentResponse>> response =
                controller.precheck(new PrecheckUploadRequest(10L, "a.pdf", null));

        // then
        assertEquals(1, response.data().size());
        assertEquals(1L, response.data().get(0).id());
        assertEquals("a.pdf", response.data().get(0).fileName());
        assertEquals(List.of("fileName"), response.data().get(0).matchAxes());
    }

    @Test
    void should_returnEmptyList_when_precheck_given_noHits() {
        // given
        when(applicationService.precheck(10L, null, "sha256:abc")).thenReturn(List.of());

        // when
        ApiResponse<List<DuplicateDocumentResponse>> response =
                controller.precheck(new PrecheckUploadRequest(10L, null, "sha256:abc"));

        // then
        assertTrue(response.data().isEmpty());
    }

    @Test
    void should_returnInlineDisposition_when_previewContent_given_contentResult() {
        // given
        when(applicationService.openContent(1L)).thenReturn(new DocumentContentResult(
                "a.pdf", "application/pdf", 3L, new ByteArrayInputStream(new byte[] {1, 2, 3})));

        // when
        ResponseEntity<InputStreamResource> response = controller.previewContent(1L);

        // then
        String disposition = response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertTrue(disposition.contains("inline"));
        assertTrue(disposition.contains("a.pdf"));
        assertEquals("application/pdf", response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE));
        assertEquals("3", response.getHeaders().getFirst(HttpHeaders.CONTENT_LENGTH));
        assertNotNull(response.getBody());
        verify(applicationService).openContent(1L);
    }

    @Test
    void should_returnAttachmentDisposition_when_downloadContent_given_contentResult() {
        // given
        when(applicationService.openContent(2L)).thenReturn(new DocumentContentResult(
                "手册.pdf", "application/octet-stream", 5L, new ByteArrayInputStream(new byte[] {1})));

        // when
        ResponseEntity<InputStreamResource> response = controller.downloadContent(2L);

        // then
        String disposition = response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertTrue(disposition.contains("attachment"));
        // UTF-8 中文文件名按 RFC 5987 编码回传
        assertTrue(disposition.contains("UTF-8"));
        assertEquals("application/octet-stream", response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE));
        assertEquals("5", response.getHeaders().getFirst(HttpHeaders.CONTENT_LENGTH));
        verify(applicationService).openContent(2L);
    }

    /** 模拟容器解析后的 multipart 文件部分 */
    private MultipartFile uploadFile() {
        return new MockMultipartFile("file", "a.pdf", "application/pdf", FILE_BYTES);
    }

    /** 模拟容器解析后的 multipart 元数据部分 */
    private UploadDocumentRequest uploadMeta() {
        return new UploadDocumentRequest(10L, "a.pdf", "PDF", "{\"pages\":3}", "{\"mode\":\"AUTO\"}");
    }

    private Document document(Long id) {
        return Document.restore(id, 10L, "a.pdf", FileType.PDF, DocumentStatus.PENDING, null, 2048L, 0,
                "{\"pages\":3}", ImportType.UPLOAD, S3_FILE, "{\"hash\":\"abc\"}", null, null, null);
    }
}
