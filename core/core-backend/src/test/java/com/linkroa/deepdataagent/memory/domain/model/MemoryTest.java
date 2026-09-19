package com.linkroa.deepdataagent.memory.domain.model;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Memory} 记忆条目领域模型不变量单测。
 */
class MemoryTest {

    @Test
    void should_deriveSizeAndSha_when_create_given_validContent() {
        // given // when
        Memory memory = Memory.create("mem_1", "ms_1", "notes/a", "内容", null);

        // then
        assertEquals("mem_1", memory.memoryId());
        assertEquals("ms_1", memory.storeId());
        assertEquals("notes/a", memory.path());
        assertEquals(1, memory.version());
        assertEquals("内容".getBytes(StandardCharsets.UTF_8).length, memory.size());
        assertEquals(64, memory.contentSha256().length());
        assertNotNull(memory.metadata());
        assertTrue(memory.metadata().entries().isEmpty());
        assertNotNull(memory.createdAt());
        assertNotNull(memory.updatedAt());
    }

    @Test
    void should_acceptEmptyContent_when_create_given_emptyString() {
        // given // when
        Memory memory = Memory.create("mem_1", "ms_1", "notes/a", "", MemoryMetadata.of(Map.of("k", "v")));

        // then
        assertEquals(0, memory.size());
        assertEquals(1, memory.metadata().entries().size());
    }

    @Test
    void should_throwIllegalArgumentException_when_create_given_nullMemoryId() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> Memory.create(null, "ms_1", "notes/a", "内容", null));
    }

    @Test
    void should_throwIllegalArgumentException_when_create_given_memoryIdWithoutPrefix() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> Memory.create("memory_1", "ms_1", "notes/a", "内容", null));
    }

    @Test
    void should_throwIllegalArgumentException_when_create_given_absolutePath() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> Memory.create("mem_1", "ms_1", "/notes/a", "内容", null));
    }

    @Test
    void should_throwIllegalArgumentException_when_create_given_pathExceeds512Chars() {
        // given
        String longPath = "a".repeat(513);

        // when // then
        assertThrows(IllegalArgumentException.class,
                () -> Memory.create("mem_1", "ms_1", longPath, "内容", null));
    }

    @Test
    void should_throwIllegalArgumentException_when_create_given_contentExceeds100KB() {
        // given
        String bigContent = "a".repeat(Memory.MAX_CONTENT_BYTES + 1);

        // when // then
        assertThrows(IllegalArgumentException.class,
                () -> Memory.create("mem_1", "ms_1", "notes/a", bigContent, null));
    }

    @Test
    void should_throwIllegalArgumentException_when_create_given_blankPath() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> Memory.create("mem_1", "ms_1", "   ", "内容", null));
    }

    @Test
    void should_throwIllegalArgumentException_when_create_given_blankStoreId() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> Memory.create("mem_1", " ", "notes/a", "内容", null));
    }

    @Test
    void should_accept_when_create_given_pathExactly512Chars() {
        // given（512 为合法上界，仅 >512 拒绝）
        String path = "a".repeat(512);

        // when
        Memory memory = Memory.create("mem_1", "ms_1", path, "内容", null);

        // then
        assertEquals(512, memory.path().length());
    }

    @Test
    void should_incrementVersionAndKeepImmutables_when_nextVersion_given_nullMetadata() {
        // given
        Memory memory = Memory.create("mem_1", "ms_1", "notes/a", "旧内容", MemoryMetadata.of(Map.of("k", "v")));
        OffsetDateTime createdAt = memory.createdAt();

        // when
        Memory updated = memory.nextVersion("新内容", null);

        // then
        assertEquals(2, updated.version());
        assertEquals("notes/a", updated.path());
        assertEquals(createdAt, updated.createdAt());
        // 元数据 null=沿用现有
        assertEquals("v", updated.metadata().entries().get("k"));
        assertEquals("新内容".getBytes(StandardCharsets.UTF_8).length, updated.size());
    }

    @Test
    void should_replaceMetadata_when_nextVersion_given_newMetadata() {
        // given
        Memory memory = Memory.create("mem_1", "ms_1", "notes/a", "旧内容", MemoryMetadata.of(Map.of("k", "v")));

        // when
        Memory updated = memory.nextVersion("新内容", MemoryMetadata.of(Map.of("k2", "v2")));

        // then
        assertEquals("v2", updated.metadata().entries().get("k2"));
        assertTrue(!updated.metadata().entries().containsKey("k"));
    }

    @Test
    void should_throwIllegalArgumentException_when_construct_given_zeroVersion() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> new Memory(1L, "mem_1", "ms_1", "notes/a", 0, 2L, "a".repeat(64),
                        null, OffsetDateTime.now(), OffsetDateTime.now()));
    }

    @Test
    void should_throwIllegalArgumentException_when_construct_given_blankSha() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> new Memory(1L, "mem_1", "ms_1", "notes/a", 1, 2L, " ",
                        null, OffsetDateTime.now(), OffsetDateTime.now()));
    }

    @Test
    void should_normalizeEmptyMetadata_when_construct_given_nullMetadata() {
        // given // when
        Memory memory = new Memory(1L, "mem_1", "ms_1", "notes/a", 1, 0L, "a".repeat(64),
                null, OffsetDateTime.now(), OffsetDateTime.now());

        // then
        assertSame(MemoryMetadata.empty().getClass(), memory.metadata().getClass());
        assertTrue(memory.metadata().entries().isEmpty());
    }
}
