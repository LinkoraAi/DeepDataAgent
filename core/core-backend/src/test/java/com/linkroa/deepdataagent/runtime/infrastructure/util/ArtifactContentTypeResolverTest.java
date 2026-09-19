package com.linkroa.deepdataagent.runtime.infrastructure.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link ArtifactContentTypeResolver} 产出内容类型解析器单测
 * （交付事件 {@code content_type} 字段来源，5.4）。
 * <p>覆盖：已知文本扩展名 / 已知二进制扩展名 / 大小写与无扩展名 / 未知扩展名兜底。</p>
 */
class ArtifactContentTypeResolverTest {

    @Test
    void should_resolveMarkdownMime_when_resolve_given_textExtension() {
        // given（文本类产出：报告 markdown）

        // when
        String mime = ArtifactContentTypeResolver.resolve("report.md");

        // then
        assertEquals("text/markdown", mime);
    }

    @Test
    void should_resolveBinaryMime_when_resolve_given_binaryExtension() {
        // given（二进制产出：图表 png / 报表 xlsx）

        // when & then（产出面不拒绝二进制，按扩展名映射）
        assertEquals("image/png", ArtifactContentTypeResolver.resolve("chart.png"));
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                ArtifactContentTypeResolver.resolve("book.xlsx"));
    }

    @Test
    void should_resolveCaseInsensitively_when_resolve_given_uppercaseExtension() {
        // given（扩展名大小写不敏感）

        // when & then
        assertEquals("text/csv", ArtifactContentTypeResolver.resolve("DATA.CSV"));
    }

    @Test
    void should_fallbackToOctetStream_when_resolve_given_unknownOrMissingExtension() {
        // given（未知扩展名 / 无扩展名 / 空文件名）

        // when & then（一律兜底 application/octet-stream，不抛异常）
        assertEquals(ArtifactContentTypeResolver.DEFAULT_ARTIFACT_MIME,
                ArtifactContentTypeResolver.resolve("archive.unknownext"));
        assertEquals(ArtifactContentTypeResolver.DEFAULT_ARTIFACT_MIME,
                ArtifactContentTypeResolver.resolve("no-extension"));
        assertEquals(ArtifactContentTypeResolver.DEFAULT_ARTIFACT_MIME,
                ArtifactContentTypeResolver.resolve(null));
    }
}