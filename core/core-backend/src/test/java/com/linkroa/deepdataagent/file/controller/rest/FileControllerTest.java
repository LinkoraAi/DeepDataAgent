package com.linkroa.deepdataagent.file.controller.rest;

import com.linkroa.deepdataagent.file.application.command.CreateFileCommand;
import com.linkroa.deepdataagent.file.application.query.ListFilesQuery;
import com.linkroa.deepdataagent.file.application.service.FileApplicationService;
import com.linkroa.deepdataagent.file.application.service.FileApplicationService.FileDownload;
import com.linkroa.deepdataagent.file.application.service.FileApplicationService.FilePage;
import com.linkroa.deepdataagent.file.domain.model.File;
import com.linkroa.deepdataagent.file.domain.model.FileScope;
import com.linkroa.deepdataagent.file.domain.model.enums.FilePurpose;
import com.linkroa.deepdataagent.file.domain.model.enums.FileStatus;
import com.linkroa.deepdataagent.file.controller.response.FileListResponse;
import com.linkroa.deepdataagent.file.controller.response.FileResponse;
import com.linkroa.deepdataagent.shared.exception.ForbiddenException;
import com.linkroa.deepdataagent.shared.result.ApiResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link FileController} 单测：multipart 装配、limit 缺省归一、
 * {@code /content} 直接下载流响应头（Content-Disposition UTF-8 文件名 + 探测 MIME）
 * 与 403 门禁异常透传（7.2 File 行为契约）。
 */
@ExtendWith(MockitoExtension.class)
class FileControllerTest {

    @Mock
    private FileApplicationService applicationService;

    @InjectMocks
    private FileController controller;

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-03T10:00:00+08:00");

    private File buildFile(FilePurpose purpose) {
        return File.restore(1L, "file_1", 1L, "out.csv", "text/csv", 5L,
                purpose, FileStatus.READY, purpose.downloadable(),
                new FileScope("sess_1", FileScope.TYPE_SESSION), "{}", "0".repeat(64), NOW, NOW);
    }

    @Test
    void should_assembleMultipartPartsIntoCommand_when_upload_given_fileAndPurpose() throws IOException {
        // given
        MockMultipartFile part = new MockMultipartFile(
                "file", "notes.md", "multipart/form-data", "# 标题".getBytes(StandardCharsets.UTF_8));
        when(applicationService.upload(any(CreateFileCommand.class))).thenReturn(buildFile(FilePurpose.USER_UPLOAD));

        // when
        ApiResponse<FileResponse> response = controller.upload(part, null, "user_upload", "{\"k\":1}");

        // then（multipart 各部分按位装配为命令，响应为文件对象标准形态）
        ArgumentCaptor<CreateFileCommand> captor = ArgumentCaptor.forClass(CreateFileCommand.class);
        verify(applicationService).upload(captor.capture());
        assertEquals("notes.md", captor.getValue().filename());
        assertEquals("user_upload", captor.getValue().purpose());
        assertEquals("{\"k\":1}", captor.getValue().metadata());
        assertArrayEquals("# 标题".getBytes(StandardCharsets.UTF_8), captor.getValue().content());
        assertEquals("file", response.data().type());
        assertEquals("file_1", response.data().fileId());
        assertEquals("user_upload", response.data().purpose());
        assertThat(response.data().downloadable()).isFalse();
    }

    @Test
    void should_preferExplicitFilename_when_upload_given_filenameParamAndOriginalFilename() throws IOException {
        // given（显式 filename 参数优先于 multipart 原始文件名，5.6 上传端点对齐）
        MockMultipartFile part = new MockMultipartFile(
                "file", "original.md", "multipart/form-data", "正文".getBytes(StandardCharsets.UTF_8));
        when(applicationService.upload(any(CreateFileCommand.class))).thenReturn(buildFile(FilePurpose.USER_UPLOAD));

        // when
        controller.upload(part, "renamed.md", "user_upload", null);

        // then（命令承载显式文件名）
        ArgumentCaptor<CreateFileCommand> captor = ArgumentCaptor.forClass(CreateFileCommand.class);
        verify(applicationService).upload(captor.capture());
        assertEquals("renamed.md", captor.getValue().filename());
    }

    @Test
    void should_normalizeDefaultPageSize_when_list_given_limitAbsent() {
        // given（limit 缺省经查询对象紧凑构造器归一到默认 20）
        when(applicationService.list(any(ListFilesQuery.class)))
                .thenReturn(new FilePage(List.of(buildFile(FilePurpose.TOOL_OUTPUT)), "next-cursor"));

        // when
        ApiResponse<FileListResponse> response = controller.list("tool_output", "sess_1", null, null);

        // then
        ArgumentCaptor<ListFilesQuery> captor = ArgumentCaptor.forClass(ListFilesQuery.class);
        verify(applicationService).list(captor.capture());
        assertEquals(20, captor.getValue().size());
        assertEquals("tool_output", captor.getValue().purposeCode());
        assertEquals("sess_1", captor.getValue().scopeId());
        assertEquals("next-cursor", response.data().nextCursor());
        assertEquals("tool_output", response.data().data().get(0).purpose());
    }

    @Test
    void should_streamContentWithUtf8Attachment_when_download_given_downloadableFile() {
        // given（可下载产出物：直接二进制流 + attachment 文件名 UTF-8 编码）
        byte[] content = "产出内容".getBytes(StandardCharsets.UTF_8);
        when(applicationService.downloadContent("file_1"))
                .thenReturn(new FileDownload("季度报告.csv", "text/csv", content));

        // when
        ResponseEntity<byte[]> response = controller.download("file_1");

        // then
        assertEquals("text/csv", response.getHeaders().getContentType().toString());
        String disposition = response.getHeaders().getFirst("Content-Disposition");
        assertThat(disposition).contains("attachment");
        assertThat(disposition).contains("filename*=UTF-8''");
        assertArrayEquals(content, response.getBody());
    }

    @Test
    void should_propagateForbidden_when_download_given_undownloadablePurpose() {
        // given（user_upload 不可下载：403 语义由全局异常层转信封，控制器不吞异常）
        when(applicationService.downloadContent("file_2"))
                .thenThrow(new ForbiddenException("该文件用途（user_upload）不支持直接下载"));

        // when & then
        assertThrows(ForbiddenException.class, () -> controller.download("file_2"));
    }

    @Test
    void should_returnMetadataWithoutContent_when_detail_given_fileId() {
        // given
        when(applicationService.getMeta("file_1")).thenReturn(buildFile(FilePurpose.TOOL_OUTPUT));

        // when
        ApiResponse<FileResponse> response = controller.detail("file_1");

        // then（详情仅元数据：scope 嵌套 {id,type} 回显、metadata 原样透传）
        assertEquals("sess_1", response.data().scope().id());
        assertEquals("session", response.data().scope().type());
        assertEquals("{}", response.data().metadata());
    }
}
