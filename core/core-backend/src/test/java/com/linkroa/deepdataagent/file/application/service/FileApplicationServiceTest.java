package com.linkroa.deepdataagent.file.application.service;

import com.linkroa.deepdataagent.file.application.command.CreateFileCommand;
import com.linkroa.deepdataagent.file.application.port.FileContentPort;
import com.linkroa.deepdataagent.file.application.query.ListFilesQuery;
import com.linkroa.deepdataagent.file.application.service.FileApplicationService.FileDownload;
import com.linkroa.deepdataagent.file.application.service.FileApplicationService.FilePage;
import com.linkroa.deepdataagent.file.domain.model.File;
import com.linkroa.deepdataagent.file.domain.model.FileCursor;
import com.linkroa.deepdataagent.file.domain.model.enums.FilePurpose;
import com.linkroa.deepdataagent.file.domain.model.enums.FileStatus;
import com.linkroa.deepdataagent.file.domain.repository.FileRepository;
import com.linkroa.deepdataagent.file.domain.service.FileContentTypeDomainService;
import com.linkroa.deepdataagent.shared.exception.FileContentIntegrityException;
import com.linkroa.deepdataagent.shared.exception.ForbiddenException;
import com.linkroa.deepdataagent.shared.exception.ResourceNotFoundException;
import com.linkroa.deepdataagent.shared.security.AuthContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link FileApplicationService} 单测（上传校验链 / 游标判尾 / 下载门禁与一致性语义）。
 */
@ExtendWith(MockitoExtension.class)
class FileApplicationServiceTest {

    private static final byte[] CONTENT = "hello 文件".getBytes(StandardCharsets.UTF_8);

    @Mock
    private FileRepository fileRepository;
    @Mock
    private FileContentPort fileContentPort;

    private FileApplicationService service;

    @BeforeEach
    void setUp() {
        AuthContext.setUserId(1L);
        service = new FileApplicationService();
        ReflectionTestUtils.setField(service, "fileRepository", fileRepository);
        ReflectionTestUtils.setField(service, "fileContentPort", fileContentPort);
        // MIME 探测为纯逻辑领域服务，直接注入真实实例
        ReflectionTestUtils.setField(service, "contentTypeDomainService", new FileContentTypeDomainService());
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    /** 构造已持久化形态文件（含主键与时间戳，供游标与恢复语义断言）。 */
    private File buildPersistedFile(long id, FilePurpose purpose, byte[] content) {
        File created = File.create("file_" + id, 1L, "a.txt", "text/plain",
                purpose, null, null, content);
        OffsetDateTime now = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
        return File.restore(id, created.fileId(), created.ownerId(), created.filename(),
                created.mimeType(), created.sizeBytes(), created.purpose(), created.status(),
                created.downloadable(), created.scope(), created.metadata(),
                created.contentSha256(), now, now);
    }

    // ---------- upload ----------

    @Test
    void should_uploadWithDerivedMeta_when_upload_given_validCommand() {
        // given
        when(fileContentPort.register(any(File.class), any(byte[].class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // when
        File saved = service.upload(new CreateFileCommand(
                "notes.md", "user_upload", "{\"k\":1}", CONTENT));

        // then（mime 服务端探测、purpose 解析、owner 取认证上下文、经内容端口登记）
        assertEquals("notes.md", saved.filename());
        assertEquals("text/markdown", saved.mimeType());
        assertEquals(FilePurpose.USER_UPLOAD, saved.purpose());
        assertEquals(1L, saved.ownerId());
        assertEquals(FileStatus.READY, saved.status());
        assertEquals("{\"k\":1}", saved.metadata());
        verify(fileContentPort).register(any(File.class), eq(CONTENT));
    }

    @Test
    void should_stripDirectorySegments_when_upload_given_pathLikeFilename() {
        // given（契约：显式文件名的目录段被剥离后落库，mime 探测亦以清理后文件名）
        when(fileContentPort.register(any(File.class), any(byte[].class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // when
        File saved = service.upload(new CreateFileCommand(
                "reports/2026/notes.md", "user_upload", null, CONTENT));

        // then
        assertEquals("notes.md", saved.filename());
        assertEquals("text/markdown", saved.mimeType());
    }

    @Test
    void should_throwIllegalArgument_when_upload_given_filenameBlankAfterSanitize() {
        // given // when // then（纯穿越成分清理后为空：1–255 字节不变量拒绝 → 400 语义）
        assertThrows(IllegalArgumentException.class, () -> service.upload(
                new CreateFileCommand("..", "user_upload", null, CONTENT)));
    }

    @Test
    void should_throwIllegalArgument_when_upload_given_missingPurpose() {
        // given // when // then（purpose 五态必填：缺省 → 400 语义）
        assertThrows(IllegalArgumentException.class, () -> service.upload(
                new CreateFileCommand("a.txt", null, null, CONTENT)));
    }

    @Test
    void should_throwIllegalArgument_when_upload_given_invalidPurpose() {
        // given // when // then
        assertThrows(IllegalArgumentException.class, () -> service.upload(
                new CreateFileCommand("a.txt", "not_a_purpose", null, CONTENT)));
    }

    @Test
    void should_normalizeNullContentToEmptyBytes_when_upload_given_contentAbsent() {
        // given（content 缺省归一空字节：0 字节文本文件合法，size=0）
        when(fileContentPort.register(any(File.class), any(byte[].class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // when
        File saved = service.upload(new CreateFileCommand("empty.txt", "user_upload", null, null));

        // then
        assertEquals(0L, saved.sizeBytes());
        verify(fileContentPort).register(any(File.class), aryEq(new byte[0]));
    }

    @Test
    void should_throwIllegalArgument_when_upload_given_metadataNotJsonObject() {
        // given // when // then（metadata 必须是 JSON 对象：数组 / 标量按 400 语义拒绝）
        assertThrows(IllegalArgumentException.class, () -> service.upload(
                new CreateFileCommand("a.txt", "user_upload", "[1,2]", CONTENT)));
        assertThrows(IllegalArgumentException.class, () -> service.upload(
                new CreateFileCommand("a.txt", "user_upload", "123", CONTENT)));
    }

    @Test
    void should_throwIllegalArgument_when_upload_given_binaryContentWithNulByte() {
        // given（含 NUL 字节内容：非文本准入拒绝 → 400 语义）
        byte[] binary = new byte[]{104, 101, 0, 108, 108, 111};

        // when // then
        assertThrows(IllegalArgumentException.class, () -> service.upload(
                new CreateFileCommand("a.txt", "user_upload", null, binary)));
    }

    @Test
    void should_throwIllegalArgument_when_upload_given_invalidMetadataJson() {
        // given // when // then（metadata 必须为 JSON 对象）
        assertThrows(IllegalArgumentException.class, () -> service.upload(
                new CreateFileCommand("a.txt", "user_upload", "not-json", CONTENT)));
        assertThrows(IllegalArgumentException.class, () -> service.upload(
                new CreateFileCommand("a.txt", "user_upload", "[1,2]", CONTENT)));
    }

    // ---------- list ----------

    @Test
    void should_trimAndBuildNextCursor_when_list_given_moreRowsThanSize() {
        // given（仓储按 size+1 取回，多一条即判有下一页）
        when(fileRepository.findByFilters(eq(1L), isNull(), isNull(), isNull(), eq(3)))
                .thenReturn(List.of(
                        buildPersistedFile(1L, FilePurpose.USER_UPLOAD, CONTENT),
                        buildPersistedFile(2L, FilePurpose.USER_UPLOAD, CONTENT),
                        buildPersistedFile(3L, FilePurpose.USER_UPLOAD, CONTENT)));

        // when
        FilePage page = service.list(new ListFilesQuery(null, null, null, 2));

        // then（截断至 size，游标定位最后一条）
        assertEquals(2, page.data().size());
        assertNotNull(page.nextCursor());
        FileCursor cursor = FileCursor.parse(page.nextCursor());
        assertNotNull(cursor);
        assertEquals(2L, cursor.id());
    }

    @Test
    void should_returnNullCursor_when_list_given_lastPageReached() {
        // given
        when(fileRepository.findByFilters(eq(1L), isNull(), isNull(), isNull(), eq(3)))
                .thenReturn(List.of(buildPersistedFile(1L, FilePurpose.USER_UPLOAD, CONTENT)));

        // when
        FilePage page = service.list(new ListFilesQuery(null, null, null, 2));

        // then
        assertEquals(1, page.data().size());
        assertNull(page.nextCursor());
    }

    @Test
    void should_applyPurposeFilterAndTolerateBadCursor_when_list_given_filtersAndInvalidCursor() {
        // given（非法游标解析为首页；purpose 契约值翻译为领域枚举下传）
        when(fileRepository.findByFilters(eq(1L), eq(FilePurpose.TOOL_OUTPUT),
                eq("sess_1"), isNull(), eq(21)))
                .thenReturn(List.of());

        // when
        FilePage page = service.list(new ListFilesQuery("tool_output", "sess_1", "!!!bad!!!", 20));

        // then
        assertEquals(0, page.data().size());
        assertNull(page.nextCursor());
    }

    @Test
    void should_throwIllegalArgument_when_list_given_invalidPurposeFilter() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> service.list(new ListFilesQuery("bogus", null, null, 20)));
    }

    // ---------- getMeta ----------

    @Test
    void should_getMeta_when_getMeta_given_ownedFile() {
        // given
        when(fileRepository.findByFileId("file_1"))
                .thenReturn(Optional.of(buildPersistedFile(1L, FilePurpose.USER_UPLOAD, CONTENT)));

        // when
        File meta = service.getMeta("file_1");

        // then
        assertEquals("file_1", meta.fileId());
    }

    @Test
    void should_throwNotFound_when_getMeta_given_otherOwner() {
        // given（越权与不存在统一 404，不泄露存在性）
        File other = File.restore(1L, "file_1", 2L, "a.txt", "text/plain", CONTENT.length,
                FilePurpose.USER_UPLOAD, FileStatus.READY, false, null, "{}",
                buildPersistedFile(1L, FilePurpose.USER_UPLOAD, CONTENT).contentSha256(),
                OffsetDateTime.now(), OffsetDateTime.now());
        when(fileRepository.findByFileId("file_1")).thenReturn(Optional.of(other));

        // when // then
        assertThrows(ResourceNotFoundException.class, () -> service.getMeta("file_1"));
    }

    @Test
    void should_throwNotFound_when_getMeta_given_missingFile() {
        // given
        when(fileRepository.findByFileId("file_gone")).thenReturn(Optional.empty());

        // when // then
        assertThrows(ResourceNotFoundException.class, () -> service.getMeta("file_gone"));
    }

    // ---------- downloadContent ----------

    @Test
    void should_returnContent_when_downloadContent_given_downloadableFileWithIntactContent() {
        // given（tool_output 派生可下载，内容对象与摘要一致）
        File file = buildPersistedFile(1L, FilePurpose.TOOL_OUTPUT, CONTENT);
        when(fileRepository.findByFileId("file_1")).thenReturn(Optional.of(file));
        when(fileContentPort.readContent("file_1")).thenReturn(Optional.of(CONTENT));

        // when
        FileDownload download = service.downloadContent("file_1");

        // then
        assertEquals("a.txt", download.filename());
        assertEquals("text/plain", download.mimeType());
        assertArrayEqualsUtf8(CONTENT, download.content());
    }

    @Test
    void should_throwForbidden_when_downloadContent_given_nonDownloadablePurpose() {
        // given（user_upload 派生不可下载 → 403 语义，不触碰内容对象）
        when(fileRepository.findByFileId("file_1"))
                .thenReturn(Optional.of(buildPersistedFile(1L, FilePurpose.USER_UPLOAD, CONTENT)));

        // when // then
        assertThrows(ForbiddenException.class, () -> service.downloadContent("file_1"));
    }

    @Test
    void should_throwIntegrity_when_downloadContent_given_contentObjectMissing() {
        // given（记录存在但内容对象缺失 → 500 一致性事故语义）
        when(fileRepository.findByFileId("file_1"))
                .thenReturn(Optional.of(buildPersistedFile(1L, FilePurpose.TOOL_OUTPUT, CONTENT)));
        when(fileContentPort.readContent("file_1")).thenReturn(Optional.empty());

        // when // then
        assertThrows(FileContentIntegrityException.class, () -> service.downloadContent("file_1"));
    }

    @Test
    void should_throwIntegrity_when_downloadContent_given_contentShaMismatch() {
        // given（内容对象被篡改，SHA-256 校验不一致 → 500 一致性事故语义）
        when(fileRepository.findByFileId("file_1"))
                .thenReturn(Optional.of(buildPersistedFile(1L, FilePurpose.TOOL_OUTPUT, CONTENT)));
        when(fileContentPort.readContent("file_1"))
                .thenReturn(Optional.of("tampered".getBytes(StandardCharsets.UTF_8)));

        // when // then
        assertThrows(FileContentIntegrityException.class, () -> service.downloadContent("file_1"));
    }

    private static void assertArrayEqualsUtf8(byte[] expected, byte[] actual) {
        assertEquals(new String(expected, StandardCharsets.UTF_8),
                new String(actual, StandardCharsets.UTF_8));
    }
}
