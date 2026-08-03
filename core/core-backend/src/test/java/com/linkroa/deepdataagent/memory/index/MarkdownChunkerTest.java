package com.linkroa.deepdataagent.memory.index;

import com.linkroa.deepdataagent.memory.config.MemoryProperties;
import com.linkroa.deepdataagent.memory.model.MemoryChunk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class MarkdownChunkerTest {

    @Test
    void should_extractMetadataAndCreateChunks_when_chunk_given_structuredMarkdown() {
        // given
        MemoryProperties properties = new MemoryProperties();
        properties.getChunking().setMaxChars(200);
        MarkdownChunker chunker = new MarkdownChunker(properties);
        String content = """
                ---
                layer: semantic
                sub_category: preference
                created_at: 2026-04-21T00:00:00Z
                ---
                
                # 用户偏好
                
                ### Spring Boot 配置 [id: mem-12345678] [importance: 0.9]
                用户偏好 YAML 配置，不使用 XML。
                """;

        // when
        List<MemoryChunk> chunks = chunker.chunk("semantic/preferences.md", content);

        // then
        assertFalse(chunks.isEmpty());
        MemoryChunk first = chunks.getFirst();
        assertEquals("semantic", first.layer());
        assertEquals("preference", first.subCategory());
        assertTrue(chunks.stream().anyMatch(chunk -> "mem-12345678".equals(chunk.memoryId())));
        assertTrue(chunks.stream().anyMatch(chunk -> Math.abs(chunk.importance() - 0.9) < 0.001));
    }

    @Test
    void should_usePathFallbacks_when_chunk_given_markdownWithoutFrontMatter() {
        // given
        MarkdownChunker chunker = new MarkdownChunker(new MemoryProperties());

        // when
        String episodicLayer = chunker.layerOf("episodic/2026-04-21/session.md", "# 会话");
        String skillsLayer = chunker.layerOf("skills/deploy.md", "# 部署");
        String semanticLayer = chunker.layerOf("MEMORY.md", "# 长期记忆");
        String preferenceCategory = chunker.subCategoryOf("USER.md", "# 用户画像");
        String ruleCategory = chunker.subCategoryOf("semantic/rules.md", "# 规则");
        String skillCategory = chunker.subCategoryOf("skills/deploy.md", "# 部署");
        String eventCategory = chunker.subCategoryOf("episodic/2026-04-21/session.md", "# 会话");
        String factCategory = chunker.subCategoryOf("semantic/facts.md", "# 事实");

        // then
        assertEquals("episodic", episodicLayer);
        assertEquals("skills", skillsLayer);
        assertEquals("semantic", semanticLayer);
        assertEquals("preference", preferenceCategory);
        assertEquals("rule", ruleCategory);
        assertEquals("skill", skillCategory);
        assertEquals("event", eventCategory);
        assertEquals("fact", factCategory);
    }

    @Test
    void should_splitLargeContentAndFallbackMetadata_when_chunk_given_oversizedMarkdownWithoutMetadata() {
        // given
        MemoryProperties properties = new MemoryProperties();
        properties.getChunking().setMaxChars(80);
        MarkdownChunker chunker = new MarkdownChunker(properties);
        String content = "# 文档\n\n"
                + "## 第一段\n"
                + "这里有一段很长的内容，用来触发最大字符数兜底切分。".repeat(12)
                + "\n\n## 第二段\n"
                + "这里是第二段内容，没有显式 memory id 和 importance。".repeat(10);

        // when
        List<MemoryChunk> chunks = chunker.chunk("semantic/facts.md", content);

        // then
        assertTrue(chunks.size() > 1);
        assertTrue(chunks.stream().allMatch(chunk -> chunk.memoryId().startsWith("chunk-")));
        assertTrue(chunks.stream().allMatch(chunk -> Math.abs(chunk.importance() - 0.5) < 0.001));
        assertTrue(chunks.stream().allMatch(chunk -> chunk.endLine() >= chunk.startLine()));
    }

    @Test
    void should_returnNoChunksAndNormalizeLayer_when_chunk_given_blankContentAndProceduralAlias() {
        // given
        MarkdownChunker chunker = new MarkdownChunker(new MemoryProperties());

        // when
        List<MemoryChunk> blankChunks = chunker.chunk("semantic/facts.md", "   ");
        String normalizedLayer = chunker.layerOf("semantic/anything.md", """
                ---
                layer: procedural
                ---
                # Skill
                """);

        // then
        assertTrue(blankChunks.isEmpty());
        assertEquals("skills", normalizedLayer);
    }

    @Test
    void should_extractBracketImportance_when_chunk_given_bracketFormat() {
        // given
        MemoryProperties properties = new MemoryProperties();
        properties.getChunking().setMaxChars(200);
        MarkdownChunker chunker = new MarkdownChunker(properties);
        String content = """
                # 记忆
                
                ### 测试 [id: mem-test-123] [importance: 0.8]
                测试内容。
                """;

        // when
        List<MemoryChunk> chunks = chunker.chunk("semantic/facts.md", content);

        // then
        assertFalse(chunks.isEmpty());
        assertTrue(chunks.stream().anyMatch(chunk -> Math.abs(chunk.importance() - 0.8) < 0.001));
    }

    @Test
    void should_useDefaultImportance_when_chunk_given_invalidImportanceValue() {
        // given
        MemoryProperties properties = new MemoryProperties();
        properties.getChunking().setMaxChars(200);
        MarkdownChunker chunker = new MarkdownChunker(properties);
        String content = """
                # 记忆
                
                ### 测试 [id: mem-test-456] [importance: invalid]
                测试内容。
                """;

        // when
        List<MemoryChunk> chunks = chunker.chunk("semantic/facts.md", content);

        // then
        assertFalse(chunks.isEmpty());
        assertTrue(chunks.stream().anyMatch(chunk -> Math.abs(chunk.importance() - 0.5) < 0.001));
    }

    @Test
    void should_useCurrentTime_when_chunk_given_contentWithoutCreatedAt() {
        // given
        MemoryProperties properties = new MemoryProperties();
        properties.getChunking().setMaxChars(200);
        MarkdownChunker chunker = new MarkdownChunker(properties);
        String content = """
                # 记忆
                
                ### 测试 [id: mem-test-789]
                测试内容。
                """;

        // when
        List<MemoryChunk> chunks = chunker.chunk("semantic/facts.md", content);

        // then
        assertFalse(chunks.isEmpty());
        assertTrue(chunks.stream().allMatch(chunk -> chunk.createdAt() != null));
    }

    @Test
    void should_extractDateOnly_when_chunk_given_dateWithoutTime() {
        // given
        MemoryProperties properties = new MemoryProperties();
        properties.getChunking().setMaxChars(200);
        MarkdownChunker chunker = new MarkdownChunker(properties);
        String content = """
                ---
                created_at: 2026-04-21
                ---
                # 记忆
                
                ### 测试 [id: mem-date-test]
                测试内容。
                """;

        // when
        List<MemoryChunk> chunks = chunker.chunk("semantic/facts.md", content);

        // then
        assertFalse(chunks.isEmpty());
        assertTrue(chunks.stream().allMatch(chunk -> chunk.createdAt() != null));
    }

    @Test
    void should_useCurrentTime_when_chunk_given_invalidDate() {
        // given
        MemoryProperties properties = new MemoryProperties();
        properties.getChunking().setMaxChars(200);
        MarkdownChunker chunker = new MarkdownChunker(properties);
        String content = """
                ---
                created_at: not-a-date
                ---
                # 记忆
                
                ### 测试 [id: mem-invalid-date]
                测试内容。
                """;

        // when
        List<MemoryChunk> chunks = chunker.chunk("semantic/facts.md", content);

        // then
        assertFalse(chunks.isEmpty());
        assertTrue(chunks.stream().allMatch(chunk -> chunk.createdAt() != null));
    }

    @Test
    void should_returnNull_when_frontMatterValue_given_nullContent() {
        // given
        MarkdownChunker chunker = new MarkdownChunker(new MemoryProperties());

        // when
        String value = chunker.layerOf("semantic/test.md", null);

        // then
        assertEquals("semantic", value);
    }

    @Test
    void should_returnNull_when_frontMatterValue_given_contentWithoutFrontMatter() {
        // given
        MarkdownChunker chunker = new MarkdownChunker(new MemoryProperties());

        // when
        String layer = chunker.layerOf("semantic/test.md", "# 没有 front matter");
        String subCategory = chunker.subCategoryOf("semantic/test.md", "# 没有 front matter");

        // then
        assertEquals("semantic", layer);
        assertEquals("fact", subCategory);
    }

    @Test
    void should_returnNoChunks_when_chunk_given_nullContent() {
        // given
        MarkdownChunker chunker = new MarkdownChunker(new MemoryProperties());

        // when
        List<MemoryChunk> chunks = chunker.chunk("semantic/test.md", null);

        // then
        assertTrue(chunks.isEmpty());
    }

    @Test
    void should_extractNonBracketImportance_when_chunk_given_plainImportanceFormat() {
        // given
        MemoryProperties properties = new MemoryProperties();
        properties.getChunking().setMaxChars(200);
        MarkdownChunker chunker = new MarkdownChunker(properties);
        String content = """
                # 记忆
                
                ### 测试 [id: mem-plain-imp]
                importance: 0.85
                测试内容。
                """;

        // when
        List<MemoryChunk> chunks = chunker.chunk("semantic/facts.md", content);

        // then
        assertFalse(chunks.isEmpty());
        assertTrue(chunks.stream().anyMatch(chunk -> Math.abs(chunk.importance() - 0.85) < 0.001));
    }

    @Test
    void should_skipBlankChunks_when_chunk_given_contentWithBlankLines() {
        // given
        MemoryProperties properties = new MemoryProperties();
        properties.getChunking().setMaxChars(200);
        MarkdownChunker chunker = new MarkdownChunker(properties);
        String content = """
                # 文档
                
                第一段内容。
                
                
                第二段内容。
                """;

        // when
        List<MemoryChunk> chunks = chunker.chunk("semantic/facts.md", content);

        // then
        assertFalse(chunks.isEmpty());
        assertTrue(chunks.stream().allMatch(chunk -> !chunk.content().isBlank()));
    }

    @Test
    void should_notSplitOnHeading_when_chunk_given_headingButBufferBelowThreshold() {
        // given
        MemoryProperties properties = new MemoryProperties();
        properties.getChunking().setMaxChars(200);
        MarkdownChunker chunker = new MarkdownChunker(properties);
        String content = """
                # 标题
                短内容。
                """;

        // when
        List<MemoryChunk> chunks = chunker.chunk("semantic/facts.md", content);

        // then: buffer 长度小于 maxChars/3，不应该在标题处切分
        assertEquals(1, chunks.size());
    }

    @Test
    void should_useFallbackTime_when_chunk_given_blankCreatedAt() {
        // given
        MemoryProperties properties = new MemoryProperties();
        properties.getChunking().setMaxChars(200);
        MarkdownChunker chunker = new MarkdownChunker(properties);
        String content = """
                ---
                created_at:   
                ---
                # 记忆
                
                ### 测试 [id: mem-blank-date]
                测试内容。
                """;

        // when
        List<MemoryChunk> chunks = chunker.chunk("semantic/facts.md", content);

        // then
        assertFalse(chunks.isEmpty());
        assertTrue(chunks.stream().allMatch(chunk -> chunk.createdAt() != null));
    }

    @Test
    void should_skipFrontMatterField_when_frontMatterValue_given_fieldWithDifferentName() {
        // given
        MarkdownChunker chunker = new MarkdownChunker(new MemoryProperties());

        // when
        String layer = chunker.layerOf("semantic/test.md", """
                ---
                other_field: value
                layer: semantic
                ---
                # 测试
                """);

        // then
        assertEquals("semantic", layer);
    }

    @Test
    void should_returnSingleChunk_when_chunk_given_singleLineContent() {
        // given
        MemoryProperties properties = new MemoryProperties();
        properties.getChunking().setMaxChars(200);
        MarkdownChunker chunker = new MarkdownChunker(properties);
        String content = "只有一行内容。";

        // when
        List<MemoryChunk> chunks = chunker.chunk("semantic/facts.md", content);

        // then
        assertFalse(chunks.isEmpty());
        assertEquals(1, chunks.size());
    }

    @Test
    void should_splitAtHeadingBoundary_when_chunk_given_largeBufferAndHeading() {
        // given: buffer 足够大且遇到 ### 标题
        MemoryProperties properties = new MemoryProperties();
        properties.getChunking().setMaxChars(200);
        MarkdownChunker chunker = new MarkdownChunker(properties);
        String content = "第一段内容。" + "a".repeat(80) + "\n"
                + "### 三级标题\n"
                + "第二段内容。" + "b".repeat(80);

        // when
        List<MemoryChunk> chunks = chunker.chunk("semantic/facts.md", content);

        // then: 应该在 ### 标题处切分
        assertTrue(chunks.size() >= 2);
    }

    @Test
    void should_notSplitAtHeadingBoundary_when_chunk_given_headingLevelFour() {
        // given: #### 不是分块边界
        MemoryProperties properties = new MemoryProperties();
        properties.getChunking().setMaxChars(200);
        MarkdownChunker chunker = new MarkdownChunker(properties);
        String content = "第一段内容。\n"
                + "#### 四级标题\n"
                + "第二段内容。";

        // when
        List<MemoryChunk> chunks = chunker.chunk("semantic/facts.md", content);

        // then: 不分块
        assertEquals(1, chunks.size());
    }

    @Test
    void should_normalizeLineEndings_when_chunk_given_windowsLineEndings() {
        // given: Windows 风格换行符
        MemoryProperties properties = new MemoryProperties();
        properties.getChunking().setMaxChars(200);
        MarkdownChunker chunker = new MarkdownChunker(properties);
        String content = "# 标题\r\n\r\n第一段内容。\r\n\r\n第二段内容。";

        // when
        List<MemoryChunk> chunks = chunker.chunk("semantic/facts.md", content);

        // then: 应该正常分块，不抛异常
        assertFalse(chunks.isEmpty());
    }

    @Test
    void should_fallbackToDateField_when_extractCreatedAt_given_dateInsteadOfCreatedAt() {
        // given: front matter 只有 date 字段，没有 created_at
        MemoryProperties properties = new MemoryProperties();
        properties.getChunking().setMaxChars(200);
        MarkdownChunker chunker = new MarkdownChunker(properties);
        String content = """
                ---
                date: 2026-04-21
                ---
                # 记忆

                ### 测试 [id: mem-date-fallback]
                测试内容。
                """;

        // when
        List<MemoryChunk> chunks = chunker.chunk("semantic/facts.md", content);

        // then
        assertFalse(chunks.isEmpty());
        assertTrue(chunks.stream().allMatch(chunk -> chunk.createdAt() != null));
    }

    @Test
    void should_fallbackToLastUpdatedField_when_extractCreatedAt_given_lastUpdatedInsteadOfDate() {
        // given: front matter 只有 last_updated 字段
        MemoryProperties properties = new MemoryProperties();
        properties.getChunking().setMaxChars(200);
        MarkdownChunker chunker = new MarkdownChunker(properties);
        String content = """
                ---
                last_updated: 2026-04-22
                ---
                # 记忆

                ### 测试 [id: mem-last-updated]
                测试内容。
                """;

        // when
        List<MemoryChunk> chunks = chunker.chunk("semantic/facts.md", content);

        // then
        assertFalse(chunks.isEmpty());
        assertTrue(chunks.stream().allMatch(chunk -> chunk.createdAt() != null));
    }

    @Test
    void should_skipBlankChunkContent_when_chunk_given_whitespaceBufferBeforeHeading() {
        // given: 40 行空白行后跟着标题和内容，空白缓冲区被切分后 strip 为空
        MemoryProperties properties = new MemoryProperties();
        properties.getChunking().setMaxChars(200);
        MarkdownChunker chunker = new MarkdownChunker(properties);
        // 40 行空白行（80 字符 > maxChars/3 = 66），触发标题边界切分
        String content = " \n".repeat(40) + "## Heading\nSome content";

        // when
        List<MemoryChunk> chunks = chunker.chunk("semantic/facts.md", content);

        // then: 空白缓冲区被跳过（addChunk 中 strip 后为空），后面的标题+内容形成一个分块
        assertFalse(chunks.isEmpty());
        assertEquals(1, chunks.size());
        assertTrue(chunks.getFirst().content().contains("## Heading"));
    }

    @Test
    void should_useDefaultImportance_when_chunk_given_invalidDecimalFormat() {
        // given: importance 值为 1.2.3（无效的十进制数格式）
        MemoryProperties properties = new MemoryProperties();
        properties.getChunking().setMaxChars(200);
        MarkdownChunker chunker = new MarkdownChunker(properties);
        String content = """
                # 记忆
                
                ### 测试 [id: mem-invalid-dec] [importance: 1.2.3]
                测试内容。
                """;

        // when
        List<MemoryChunk> chunks = chunker.chunk("semantic/facts.md", content);

        // then: NumberFormatException 被捕获，使用默认值 0.5
        assertFalse(chunks.isEmpty());
        assertTrue(chunks.stream().allMatch(chunk -> Math.abs(chunk.importance() - 0.5) < 0.001));
    }

    @Test
    void should_returnNullFromFrontMatter_when_frontMatterValue_given_fieldNotFoundInFrontMatter() {
        // given: front matter 存在但字段不匹配，且没有关闭分隔符
        MarkdownChunker chunker = new MarkdownChunker(new MemoryProperties());
        String content = "---\nother_field: value\n";

        // when: 查找 front matter 中不存在的字段
        String layer = chunker.layerOf("semantic/test.md", content);

        // then: frontMatterValue 返回 null 后回退到路径解析
        assertEquals("semantic", layer);
    }

    @Test
    void should_returnNullFromFrontMatter_when_frontMatterValue_given_closingDelimiterBeforeField() {
        // given: front matter 关闭后字段不存在
        MarkdownChunker chunker = new MarkdownChunker(new MemoryProperties());
        String content = """
                ---
                existing: value
                ---
                layer: skills
                """;

        // when: 查找 front matter 中不存在的字段
        String layer = chunker.layerOf("semantic/test.md", content);

        // then: 应该回退到路径解析
        assertEquals("semantic", layer);
    }
}
