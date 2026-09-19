package com.linkroa.deepdataagent.file.domain.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link FileContentTypeDomainService} 单测（扩展名映射 / 二进制黑名单 / 严格 UTF-8 准入）。
 */
class FileContentTypeDomainServiceTest {

    private final FileContentTypeDomainService service = new FileContentTypeDomainService();

    private static byte[] text(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void should_mapKnownTextExtensions_when_resolveTextMimeType_given_typicalFilenames() {
        // given // when & then
        assertEquals("text/plain", service.resolveTextMimeType("a.txt", text("hi")));
        assertEquals("text/markdown", service.resolveTextMimeType("notes.md", text("# 标题")));
        assertEquals("text/markdown", service.resolveTextMimeType("notes.markdown", text("# t")));
        assertEquals("application/json", service.resolveTextMimeType("data.json", text("{}")));
        assertEquals("text/csv", service.resolveTextMimeType("rows.csv", text("a,b")));
        assertEquals("application/sql", service.resolveTextMimeType("q.sql", text("select 1")));
    }

    @Test
    void should_beCaseInsensitive_when_resolveTextMimeType_given_uppercaseExtension() {
        // given // when & then
        assertEquals("text/markdown", service.resolveTextMimeType("NOTES.MD", text("# t")));
    }

    @Test
    void should_fallbackToTextPlain_when_resolveTextMimeType_given_unknownOrMissingExtension() {
        // given // when & then
        assertEquals("text/plain", service.resolveTextMimeType("weird.qqq", text("x")));
        assertEquals("text/plain", service.resolveTextMimeType("Makefile", text("all:")));
        assertEquals("text/plain", service.resolveTextMimeType("trailing-dot.", text("x")));
    }

    @Test
    void should_throwException_when_resolveTextMimeType_given_rejectedBinaryExtension() {
        // given // when // then（图片 / 压缩包 / 办公文档等显式拒绝）
        assertThrows(IllegalArgumentException.class,
                () -> service.resolveTextMimeType("photo.png", text("pretend")));
        assertThrows(IllegalArgumentException.class,
                () -> service.resolveTextMimeType("doc.pdf", text("pretend")));
        assertThrows(IllegalArgumentException.class,
                () -> service.resolveTextMimeType("pack.zip", text("pretend")));
    }

    @Test
    void should_throwException_when_resolveTextMimeType_given_contentWithNulByte() {
        // given（扩展名合法但内容含 NUL：疑似二进制）
        byte[] withNul = new byte[]{'o', 'k', 0, '!' };

        // when // then
        assertThrows(IllegalArgumentException.class,
                () -> service.resolveTextMimeType("a.txt", withNul));
    }

    @Test
    void should_throwException_when_resolveTextMimeType_given_malformedUtf8Content() {
        // given（0xC3 后接非法续字节：严格 UTF-8 解码失败）
        byte[] malformed = new byte[]{(byte) 0xC3, 0x28, (byte) 0xFE};

        // when // then
        assertThrows(IllegalArgumentException.class,
                () -> service.resolveTextMimeType("a.txt", malformed));
    }

    @Test
    void should_acceptEmptyContent_when_resolveTextMimeType_given_zeroBytes() {
        // given // when & then（空文件放行）
        assertEquals("text/plain", service.resolveTextMimeType("empty.txt", new byte[0]));
    }

    @Test
    void should_throwException_when_resolveTextMimeType_given_nullContent() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> service.resolveTextMimeType("a.txt", null));
    }

    @Test
    void should_mapKnownBinaryExtensions_when_resolveArtifactMimeType_given_binaryFilenames() {
        // given // when & then（产出登记平面识别已知二进制扩展名，不拒绝内容）
        assertEquals("image/png", service.resolveArtifactMimeType("chart.png", text("pretend")));
        assertEquals("application/pdf", service.resolveArtifactMimeType("report.pdf", text("x")));
        assertEquals("application/zip", service.resolveArtifactMimeType("pack.zip", text("x")));
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                service.resolveArtifactMimeType("book.xlsx", text("x")));
        assertEquals("image/vnd.microsoft.icon", service.resolveArtifactMimeType("fav.ico", text("x")));
    }

    @Test
    void should_mapTextExtensions_when_resolveArtifactMimeType_given_textFilenames() {
        // given // when & then（文本扩展名在产出面同样映射具体 MIME）
        assertEquals("text/markdown", service.resolveArtifactMimeType("notes.md", text("# t")));
        assertEquals("application/json", service.resolveArtifactMimeType("data.json", text("{}")));
        assertEquals("text/plain", service.resolveArtifactMimeType("a.txt", text("hi")));
    }

    @Test
    void should_fallbackToOctetStream_when_resolveArtifactMimeType_given_unknownOrMissingExtension() {
        // given // when & then（未知扩展名 / 无扩展名兜底 application/octet-stream）
        assertEquals("application/octet-stream",
                service.resolveArtifactMimeType("mystery.qqq", text("x")));
        assertEquals(FileContentTypeDomainService.DEFAULT_ARTIFACT_MIME,
                service.resolveArtifactMimeType("Makefile", text("all:")));
    }

    @Test
    void should_passThroughRawBytes_when_resolveArtifactMimeType_given_binaryLikeContent() {
        // given（含 NUL 与非法 UTF-8 字节：产出面不做文本性检测，仅 null 被拒）
        byte[] withNul = new byte[]{'a', 0, 'b'};
        byte[] malformed = new byte[]{(byte) 0xC3, 0x28, (byte) 0xFE};

        // when & then
        assertEquals("image/png", service.resolveArtifactMimeType("x.png", withNul));
        assertEquals("application/octet-stream", service.resolveArtifactMimeType("y.dat", malformed));
        assertEquals("text/plain", service.resolveArtifactMimeType("z.txt", new byte[0]));
    }

    @Test
    void should_throwException_when_resolveArtifactMimeType_given_nullContent() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> service.resolveArtifactMimeType("a.png", null));
    }
}
