package com.linkroa.deepdataagent.knowledgebase.infrastructure.assembly;

import com.linkroa.deepdataagent.knowledgebase.application.port.DocumentTextExtractPort;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TikaDocumentTextExtractor} 单元测试。
 * <p>覆盖纯文本提取、空内容与超长截断三条主路径；Office 二进制容器的解析能力由
 * Tika 标准解析器集自身保证，此处不引入二进制测试资源。</p>
 */
class TikaDocumentTextExtractorTest {

    /** 被测提取器（AutoDetectParser 构造一次复用，与生产 Bean 口径一致）。 */
    private final TikaDocumentTextExtractor extractor = new TikaDocumentTextExtractor();

    @Test
    void should_extractPlainText_when_extract_given_txtStream() {
        // given
        String source = "DeepDataAgent preview text line 1\nline 2\n";
        ByteArrayInputStream in = new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8));

        // when
        DocumentTextExtractPort.ExtractedText result = extractor.extract(in);

        // then
        assertFalse(result.truncated());
        assertTrue(result.content().contains("DeepDataAgent preview text line 1"));
        assertTrue(result.content().contains("line 2"));
    }

    @Test
    void should_returnEmptyContent_when_extract_given_emptyStream() {
        // given
        ByteArrayInputStream in = new ByteArrayInputStream(new byte[0]);

        // when
        DocumentTextExtractPort.ExtractedText result = extractor.extract(in);

        // then
        assertFalse(result.truncated());
        assertEquals("", result.content());
    }

    @Test
    void should_markTruncated_when_extract_given_contentExceedsPreviewLimit() {
        // given：21 万字符超过 20 万字符预览上限（Tika 写上限触发截断信号）
        String source = "a".repeat(210_000);
        ByteArrayInputStream in = new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8));

        // when
        DocumentTextExtractPort.ExtractedText result = extractor.extract(in);

        // then
        assertTrue(result.truncated());
        assertEquals(200_000, result.content().length());
    }
}
