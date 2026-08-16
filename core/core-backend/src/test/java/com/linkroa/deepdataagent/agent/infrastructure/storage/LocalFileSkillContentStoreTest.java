package com.linkroa.deepdataagent.agent.infrastructure.storage;

import com.linkroa.deepdataagent.agent.infrastructure.config.SkillStorageProperties;
import com.linkroa.deepdataagent.shared.exception.SkillContentMissingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本地文件技能内容存储单测（确定性路径 + 存储缺失 500）
 */
class LocalFileSkillContentStoreTest {

    @TempDir
    Path tempDir;

    private LocalFileSkillContentStore store;

    @BeforeEach
    void setUp() {
        SkillStorageProperties properties = new SkillStorageProperties();
        properties.setRoot(tempDir.toString());
        properties.setStorageType("LOCAL_FILE");
        store = new LocalFileSkillContentStore(properties);
    }

    @Test
    void should_storeAtDeterministicPath_when_put_given_skillIdAndVersion() {
        // given
        byte[] content = "技能包内容".getBytes(StandardCharsets.UTF_8);

        // when
        String storageKey = store.put("skill-1", 1, content);

        // then
        assertEquals("skill-1/1/skill-1-1.zip", storageKey);
        assertTrue(Files.exists(tempDir.resolve("skill-1/1/skill-1-1.zip")));
    }

    @Test
    void should_readBackSameContent_when_get_given_storedKey() {
        // given
        byte[] content = "技能包内容".getBytes(StandardCharsets.UTF_8);
        String storageKey = store.put("skill-1", 1, content);

        // when
        byte[] readBack = store.get(storageKey);

        // then
        assertArrayEquals(content, readBack);
    }

    @Test
    void should_throwSkillContentMissing_when_get_given_missingStorage() {
        // given
        String missingKey = "skill-unknown/1/skill-unknown-1.zip";

        // when
        // then
        assertThrows(SkillContentMissingException.class, () -> store.get(missingKey));
    }

    @Test
    void should_throwSkillContentMissing_when_get_given_blankKey() {
        // given
        // when
        // then
        assertThrows(SkillContentMissingException.class, () -> store.get("  "));
    }

    @Test
    void should_deleteFile_when_delete_given_storedKey() throws Exception {
        // given
        String storageKey = store.put("skill-1", 1, "技能包内容".getBytes(StandardCharsets.UTF_8));

        // when
        store.delete(storageKey);

        // then
        assertTrue(!Files.exists(tempDir.resolve(storageKey)));
    }
}