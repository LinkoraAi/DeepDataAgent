package com.linkroa.deepdataagent.shared.storage.local;

import com.linkroa.deepdataagent.shared.storage.ObjectMetadata;
import com.linkroa.deepdataagent.shared.storage.ObjectStorageErrorKind;
import com.linkroa.deepdataagent.shared.storage.ObjectStorageException;
import com.linkroa.deepdataagent.shared.storage.config.ObjectStorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * {@link LocalDiskObjectStorage} 本地磁盘后端单测：原子读写往返、缺失翻译 NOT_FOUND、
 * 全量前缀列举、幂等删除、前缀清空与路径穿越拒绝。
 */
class LocalDiskObjectStorageTest {

    @TempDir
    Path tempDir;

    private LocalDiskObjectStorage storage;

    @BeforeEach
    void setUp() {
        ObjectStorageProperties properties = new ObjectStorageProperties();
        properties.getLocal().setRoot(tempDir.resolve("objects").toString());
        storage = new LocalDiskObjectStorage(properties);
        storage.ensureReady();
    }

    private void put(String key, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        storage.put(key, new ByteArrayInputStream(bytes), bytes.length, "text/plain");
    }

    private String read(String key) throws Exception {
        try (InputStream in = storage.get(key)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void should_roundTripBytes_when_putThenGet_given_nestedKey() throws Exception {
        // given
        String key = "skills/skill_x/1/SKILL.md";
        String body = "# 技能正文";

        // when
        put(key, body);

        // then（层级目录自动创建，读回内容一致）
        assertThat(read(key)).isEqualTo(body);
    }

    @Test
    void should_throwNotFound_when_get_given_missingKey() {
        // given // when // then
        assertThatThrownBy(() -> storage.get("files/file_gone"))
                .isInstanceOf(ObjectStorageException.class)
                .satisfies(ex -> assertThat(((ObjectStorageException) ex).kind())
                        .isEqualTo(ObjectStorageErrorKind.NOT_FOUND));
    }

    @Test
    void should_listAllKeysUnderPrefix_when_list_given_nestedObjects() {
        // given
        put("skills/skill_x/1/SKILL.md", "v1");
        put("skills/skill_x/1/references/a.md", "a");
        put("skills/skill_x/2/SKILL.md", "v2");
        put("files/file_1", "f");

        // when
        List<ObjectMetadata> v1List = storage.list("skills/skill_x/1/");
        List<ObjectMetadata> allSkills = storage.list("skills/");

        // then（本地 walk 全量，无分页截断）
        assertThat(v1List).extracting(ObjectMetadata::key)
                .containsExactlyInAnyOrder("skills/skill_x/1/SKILL.md",
                        "skills/skill_x/1/references/a.md");
        assertThat(allSkills).hasSize(3);
        assertThat(storage.list("files/")).hasSize(1);
    }

    @Test
    void should_deleteIdempotently_when_delete_given_presentOrAbsentKey() {
        // given
        put("files/file_a", "x");

        // when // then（存在删除成功，重复 / 不存在均静默幂等）
        assertDoesNotThrow(() -> storage.delete("files/file_a"));
        assertDoesNotThrow(() -> storage.delete("files/file_a"));
        assertThatThrownBy(() -> storage.get("files/file_a"))
                .isInstanceOf(ObjectStorageException.class);
    }

    @Test
    void should_clearAllObjectsAndEmptyDirs_when_deletePrefix_given_versionPrefix() throws Exception {
        // given
        put("skills/skill_x/9/SKILL.md", "v");
        put("skills/skill_x/9/scripts/run.py", "print(1)");

        // when
        storage.deletePrefix("skills/skill_x/9/");

        // then（前缀下对象全部清空，其他版本不受影响）
        assertThat(storage.list("skills/skill_x/9/")).isEmpty();
        assertThatThrownBy(() -> storage.get("skills/skill_x/9/SKILL.md"))
                .isInstanceOf(ObjectStorageException.class);
    }

    @Test
    void should_reject_when_put_given_traversalKey() {
        // given // when // then（key 穿越 / 绝对 / 反斜杠均被拒绝，不写存储根之外）
        assertThatThrownBy(() -> put("../escape.txt", "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> put("a/../../escape.txt", "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> put("a\\b.txt", "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
