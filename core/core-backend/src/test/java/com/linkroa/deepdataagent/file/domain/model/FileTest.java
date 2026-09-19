package com.linkroa.deepdataagent.file.domain.model;

import com.linkroa.deepdataagent.file.domain.model.enums.FilePurpose;
import com.linkroa.deepdataagent.file.domain.model.enums.FileStatus;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link File} 聚合不变量与派生行为单测（创建推导、摘要校验、恢复形态）。
 */
class FileTest {

    private static final byte[] CONTENT = "你好，世界".getBytes(StandardCharsets.UTF_8);

    @Test
    void should_deriveSizeShaStatusAndDownloadable_when_create_given_toolOutputContent() {
        // given // when
        File file = File.create("file_1", 1L, "out.md", "text/markdown",
                FilePurpose.TOOL_OUTPUT, null, null, CONTENT);

        // then（size 取字节长度、sha 为 64 位摘要、status=ready、downloadable 由 purpose 派生）
        assertEquals("file_1", file.fileId());
        assertEquals(CONTENT.length, file.sizeBytes());
        assertEquals(64, file.contentSha256().length());
        assertEquals(FileStatus.READY, file.status());
        assertTrue(file.downloadable());
        assertEquals("file", File.FILE_TYPE);
        assertNull(file.scope());
    }

    @Test
    void should_computeUtf8SizeBytes_when_create_given_multibyteContent() {
        // given（多字节 UTF-8 内容） // when
        File file = File.create("file_1", 1L, "hello.txt", "text/plain",
                FilePurpose.USER_UPLOAD, null, null, CONTENT);

        // then
        assertEquals("你好，世界".getBytes(StandardCharsets.UTF_8).length, file.sizeBytes());
    }

    @Test
    void should_normalizeBlankMetadataToEmptyObject_when_create_given_blankMetadata() {
        // given // when
        File file = File.create("file_1", 1L, "a.txt", "text/plain",
                FilePurpose.USER_UPLOAD, null, "  ", CONTENT);

        // then
        assertEquals("{}", file.metadata());
    }

    @Test
    void should_keepGivenScope_when_create_given_sessionScope() {
        // given
        FileScope scope = FileScope.ofSession("sess_1");

        // when
        File file = File.create("file_1", 1L, "a.txt", "text/plain",
                FilePurpose.SESSION_RESOURCE, scope, null, CONTENT);

        // then
        assertEquals(scope, file.scope());
        assertFalse(file.downloadable());
    }

    @Test
    void should_matchContent_when_contentMatches_given_sameBytes() {
        // given
        File file = File.create("file_1", 1L, "a.txt", "text/plain",
                FilePurpose.USER_UPLOAD, null, null, CONTENT);

        // when & then
        assertTrue(file.contentMatches(CONTENT));
        assertFalse(file.contentMatches("被篡改的内容".getBytes(StandardCharsets.UTF_8)));
        assertFalse(file.contentMatches(null));
    }

    @Test
    void should_matchByHex_when_contentSha256Matches_given_equalDigestIgnoreCase() {
        // given（登记摘要为小写 hex，物化面传入大写归一比对）
        File file = File.create("file_1", 1L, "a.txt", "text/plain",
                FilePurpose.USER_UPLOAD, null, null, CONTENT);
        String lower = file.contentSha256();

        // when & then（同摘要、大小写不同均判一致）
        assertTrue(file.contentSha256Matches(lower));
        assertTrue(file.contentSha256Matches(lower.toUpperCase(java.util.Locale.ROOT)));
    }

    @Test
    void should_notMatch_when_contentSha256Matches_given_differentOrBlankDigest() {
        // given
        File file = File.create("file_1", 1L, "a.txt", "text/plain",
                FilePurpose.USER_UPLOAD, null, null, CONTENT);

        // when & then（不符摘要与 null 均判不一致）
        assertFalse(file.contentSha256Matches("f".repeat(64)));
        assertFalse(file.contentSha256Matches(null));
    }

    @Test
    void should_beMountable_when_mountableBy_given_ownedReadyFile() {
        // given
        File file = File.create("file_1", 1L, "a.txt", "text/plain",
                FilePurpose.USER_UPLOAD, null, null, CONTENT);

        // when & then（归属且 ready 才放行）
        assertTrue(file.mountableBy(1L));
    }

    @Test
    void should_rejectMount_when_mountableBy_given_foreignOwnerOrNullRequester() {
        // given
        File file = File.create("file_1", 1L, "a.txt", "text/plain",
                FilePurpose.USER_UPLOAD, null, null, CONTENT);

        // when & then（越权与未认证（null）一律不可挂载）
        assertFalse(file.mountableBy(2L));
        assertFalse(file.mountableBy(null));
    }

    @Test
    void should_throwException_when_create_given_nullOwner() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> File.create("file_1", null, "a.txt", "text/plain",
                        FilePurpose.USER_UPLOAD, null, null, CONTENT));
    }

    @Test
    void should_throwException_when_create_given_blankFilename() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> File.create("file_1", 1L, " ", "text/plain",
                        FilePurpose.USER_UPLOAD, null, null, CONTENT));
    }

    @Test
    void should_throwException_when_create_given_filenameTraversal() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> File.create("file_1", 1L, "../etc/passwd", "text/plain",
                        FilePurpose.USER_UPLOAD, null, null, CONTENT));
    }

    @Test
    void should_stripDirectorySegments_when_sanitizeFilename_given_pathLikeName() {
        // given // when & then（契约：目录段被剥离后落库，而非整请求被拒）
        assertEquals("sales.csv", File.sanitizeFilename("data/2026/sales.csv"));
        assertEquals("passwd", File.sanitizeFilename("../../etc/passwd"));
        assertEquals("report.md", File.sanitizeFilename("C:\\reports\\report.md"));
        assertEquals("a.txt", File.sanitizeFilename("  a.txt  "));
    }

    @Test
    void should_normalizeToBlank_when_sanitizeFilename_given_traversalOnlyOrNull() {
        // given // when & then（纯穿越成分 / 裸分隔符归一为空串，null 原样返回）
        assertEquals("", File.sanitizeFilename(".."));
        assertEquals("", File.sanitizeFilename("/"));
        assertNull(File.sanitizeFilename(null));
    }

    @Test
    void should_accept255ByteFilename_when_create_given_lengthAtLimit() {
        // given（1–255 字节边界：恰 255 字节合法）
        String filename = "a".repeat(File.MAX_FILENAME_BYTES);

        // when
        File file = File.create("file_1", 1L, filename, "text/plain",
                FilePurpose.USER_UPLOAD, null, null, CONTENT);

        // then
        assertEquals(File.MAX_FILENAME_BYTES, file.filename().length());
    }

    @Test
    void should_throwException_when_create_given_filenameBeyondByteLimit() {
        // given（多字节 UTF-8：86 个汉字 = 258 字节，超 255 上限）
        String filename = "文".repeat(86);

        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> File.create("file_1", 1L, filename, "text/plain",
                        FilePurpose.USER_UPLOAD, null, null, CONTENT));
    }

    @Test
    void should_throwException_when_create_given_missingFileIdPrefix() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> File.create("bad-id", 1L, "a.txt", "text/plain",
                        FilePurpose.USER_UPLOAD, null, null, CONTENT));
    }

    @Test
    void should_throwException_when_create_given_nullPurpose() {
        // given // when // then（purpose 五态必填）
        assertThrows(IllegalArgumentException.class,
                () -> File.create("file_1", 1L, "a.txt", "text/plain",
                        null, null, null, CONTENT));
    }

    @Test
    void should_throwException_when_create_given_blankMimeType() {
        // given // when // then（mime 服务端探测，不可为空）
        assertThrows(IllegalArgumentException.class,
                () -> File.create("file_1", 1L, "a.txt", " ",
                        FilePurpose.USER_UPLOAD, null, null, CONTENT));
    }

    @Test
    void should_throwException_when_constructor_given_downloadableNotDerivedFromPurpose() {
        // given（tool_output 派生 downloadable=true，构造时申报 false 视为越权篡改）
        File base = File.create("file_1", 1L, "a.txt", "text/plain",
                FilePurpose.TOOL_OUTPUT, null, null, CONTENT);

        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> new File(base.id(), base.fileId(), base.ownerId(), base.filename(),
                        base.mimeType(), base.sizeBytes(), base.purpose(), base.status(),
                        false, base.scope(), base.metadata(), base.contentSha256(),
                        base.createdAt(), base.updatedAt()));
    }

    @Test
    void should_throwException_when_constructor_given_oversizedMetadata() {
        // given（metadata 超过 8KB 上限，按 UTF-8 字节计量）
        File base = File.create("file_1", 1L, "a.txt", "text/plain",
                FilePurpose.USER_UPLOAD, null, null, CONTENT);
        String oversized = "x".repeat(File.MAX_METADATA_BYTES + 1);

        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> new File(base.id(), base.fileId(), base.ownerId(), base.filename(),
                        base.mimeType(), base.sizeBytes(), base.purpose(), base.status(),
                        base.downloadable(), base.scope(), oversized, base.contentSha256(),
                        base.createdAt(), base.updatedAt()));
    }

    @Test
    void should_throwException_when_constructor_given_invalidSha() {
        // given // when // then（sha 必须为 64 位摘要）
        assertThrows(IllegalArgumentException.class,
                () -> File.restore(null, "file_1", 1L, "a.txt", "text/plain", 3L,
                        FilePurpose.USER_UPLOAD, FileStatus.READY, false, null, "{}",
                        "not-a-sha", null, null));
    }

    @Test
    void should_acceptMaxSize_when_constructor_given_sizeExactly50Mb() {
        // given（限额边界：50MB 整合法）
        File base = File.create("file_1", 1L, "a.txt", "text/plain",
                FilePurpose.USER_UPLOAD, null, null, CONTENT);

        // when
        File edge = new File(base.id(), base.fileId(), base.ownerId(), base.filename(),
                base.mimeType(), File.MAX_SIZE_BYTES, base.purpose(), base.status(),
                base.downloadable(), base.scope(), base.metadata(), base.contentSha256(),
                base.createdAt(), base.updatedAt());

        // then
        assertEquals(File.MAX_SIZE_BYTES, edge.sizeBytes());
    }

    @Test
    void should_throwException_when_constructor_given_sizeOver50Mb() {
        // given（超 50MB 上限 1 字节即拒——spec「单文件超 50MB 被拒」领域防线）
        File base = File.create("file_1", 1L, "a.txt", "text/plain",
                FilePurpose.USER_UPLOAD, null, null, CONTENT);

        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> new File(base.id(), base.fileId(), base.ownerId(), base.filename(),
                        base.mimeType(), File.MAX_SIZE_BYTES + 1, base.purpose(), base.status(),
                        base.downloadable(), base.scope(), base.metadata(), base.contentSha256(),
                        base.createdAt(), base.updatedAt()));
    }

    @Test
    void should_throwException_when_restore_given_negativeSize() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> File.restore(null, "file_1", 1L, "a.txt", "text/plain", -1L,
                        FilePurpose.USER_UPLOAD, FileStatus.READY, false, null, "{}",
                        "0".repeat(64), null, null));
    }

    @Test
    void should_restoreFullForm_when_restore_given_persistedColumns() {
        // given（持久层恢复：含主键、scope、时间戳的完整形态）
        File created = File.create("file_1", 1L, "a.txt", "text/plain",
                FilePurpose.TOOL_OUTPUT, FileScope.ofSession("sess_9"), "{\"k\":1}", CONTENT);

        // when
        File restored = File.restore(7L, created.fileId(), created.ownerId(), created.filename(),
                created.mimeType(), created.sizeBytes(), created.purpose(), created.status(),
                created.downloadable(), created.scope(), created.metadata(),
                created.contentSha256(), created.createdAt(), created.updatedAt());

        // then
        assertEquals(7L, restored.id());
        assertEquals(FileScope.TYPE_SESSION, restored.scope().type());
        assertEquals("sess_9", restored.scope().id());
        assertEquals("{\"k\":1}", restored.metadata());
        assertTrue(restored.contentMatches(CONTENT));
    }
}
