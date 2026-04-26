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
}
