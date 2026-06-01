package com.linkroa.deepdataagent.memory.file;

import com.linkroa.deepdataagent.memory.config.MemoryProperties;
import com.linkroa.deepdataagent.memory.model.ConversationContext;
import com.linkroa.deepdataagent.memory.model.ExtractedMemory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class MarkdownFileManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void should_createBaselineFilesAndPreloadCache_when_initialize_given_emptyWorkspace() {
        // given
        MarkdownFileManager fileManager = fileManager();

        // when
        String memoryContent = fileManager.read("MEMORY.md");
        String userContent = fileManager.read("USER.md");

        // then
        assertTrue(Files.exists(tempDir.resolve("MEMORY.md")));
        assertTrue(Files.exists(tempDir.resolve("USER.md")));
        assertTrue(memoryContent.contains("# 长期记忆"));
        assertTrue(userContent.contains("# 用户画像"));
    }

    @Test
    void should_persistAndReadContent_when_appendWriteFlushAndReadLines_given_existingMarkdownFile() {
        // given
        MarkdownFileManager fileManager = fileManager();

        // when
        fileManager.write("semantic/facts.md", "line1\nline2\nline3\nline4");
        fileManager.flushDirtyFiles();
        String middleLines = fileManager.readLines("semantic/facts.md", 2, 3);
        String initialContent = fileManager.read("semantic/facts.md");

        fileManager.append("semantic/facts.md", "line5");
        fileManager.flushDirtyFiles();
        String finalContent = fileManager.read("semantic/facts.md");

        // then
        assertEquals("line2\nline3", middleLines);
        assertEquals("line1\nline2\nline3\nline4", initialContent);
        assertTrue(finalContent.endsWith("line5"));
        assertTrue(Files.exists(tempDir.resolve("semantic").resolve("facts.md")));
    }

    @Test
    void should_createLayeredMarkdownFiles_when_writeExtractedMemories_given_memoryBatch() {
        // given
        MarkdownFileManager fileManager = fileManager();
        ConversationContext context = new ConversationContext(
                "session-file",
                Instant.parse("2026-04-21T00:00:00Z"),
                List.of()
        );

        // when
        var changed = fileManager.writeExtractedMemories(context, List.of(
                memory("mem-episodic", "episodic", "event", "会话标题", "会话内容"),
                memory("mem-pref", "semantic", "preference", "偏好标题", "偏好内容"),
                memory("mem-rule", "semantic", "rule", "规则标题", "规则内容"),
                memory("mem-skill", "skills", "skill", "部署技能", "技能内容")
        ));
        fileManager.flushDirtyFiles();
        String preferencesContent = fileManager.read("semantic/preferences.md");
        String rulesContent = fileManager.read("semantic/rules.md");
        String userContent = fileManager.read("USER.md");
        String memoryContent = fileManager.read("MEMORY.md");
        String skillContent = fileManager.read("skills/部署技能.md");

        // then
        assertTrue(changed.stream().anyMatch(path -> path.startsWith("episodic/2026-04-21/session-")));
        assertTrue(changed.contains("semantic/preferences.md"));
        assertTrue(changed.contains("semantic/rules.md"));
        assertTrue(changed.contains("USER.md"));
        assertTrue(changed.contains("MEMORY.md"));
        assertTrue(changed.contains("skills/部署技能.md"));
        assertTrue(preferencesContent.contains("偏好内容"));
        assertTrue(rulesContent.contains("规则内容"));
        assertTrue(userContent.contains("偏好标题"));
        assertTrue(memoryContent.contains("规则标题"));
        assertTrue(skillContent.contains("技能内容"));
    }

    @Test
    void should_excludeIndexDirectoryAndRejectUnsafePaths_when_scanAndRead_given_mixedMarkdownFiles() throws Exception {
        // given
        MarkdownFileManager fileManager = fileManager();
        fileManager.write("semantic/facts.md", "facts");
        fileManager.flushDirtyFiles();
        Files.writeString(tempDir.resolve(".index").resolve("ignored.md"), "index");

        // when
        List<String> files = fileManager.scanMarkdownFiles();
        String absoluteOutsideRoot = tempDir.toAbsolutePath().getRoot().resolve("outside-memory.md").toString();

        // then
        assertTrue(files.contains("MEMORY.md"));
        assertTrue(files.contains("semantic/facts.md"));
        assertFalse(files.contains(".index/ignored.md"));
        assertThrows(IllegalArgumentException.class, () -> fileManager.read("../escape.md"));
        assertThrows(IllegalArgumentException.class, () -> fileManager.write(absoluteOutsideRoot, "bad"));
    }

    private MarkdownFileManager fileManager() {
        MemoryProperties properties = new MemoryProperties();
        properties.setRootPath(tempDir.toString());
        MarkdownFileManager fileManager = new MarkdownFileManager(properties);
        fileManager.initialize();
        return fileManager;
    }

    private static ExtractedMemory memory(String id, String layer, String subCategory, String title, String content) {
        return new ExtractedMemory(
                id,
                layer,
                subCategory,
                title,
                content,
                0.8,
                Instant.parse("2026-04-21T00:00:00Z"),
                "session-file"
        );
    }
}
