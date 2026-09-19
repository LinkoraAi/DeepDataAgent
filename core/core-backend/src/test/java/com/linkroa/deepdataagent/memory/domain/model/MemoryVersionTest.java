package com.linkroa.deepdataagent.memory.domain.model;

import com.linkroa.deepdataagent.memory.domain.model.enums.MemoryVersionAction;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MemoryVersion} 记忆版本领域模型不变量单测。
 */
class MemoryVersionTest {

    @Test
    void should_deriveSnapshotFields_when_created_given_validContent() {
        // given // when
        MemoryVersion version = MemoryVersion.created("memver_1", "ms_1", "mem_1", "notes/a", 1, "内容");

        // then
        assertEquals(MemoryVersionAction.CREATED, version.action());
        assertEquals("内容", version.content());
        assertEquals((long) "内容".getBytes(StandardCharsets.UTF_8).length, version.size());
        assertEquals(64, version.contentSha256().length());
        assertFalse(version.redacted());
        assertNull(version.redactedAt());
        assertNotNull(version.createdAt());
    }

    @Test
    void should_markUpdatedAction_when_updated_given_validContent() {
        // given // when
        MemoryVersion version = MemoryVersion.updated("memver_2", "ms_1", "mem_1", "notes/a", 2, "新内容");

        // then
        assertEquals(MemoryVersionAction.UPDATED, version.action());
        assertEquals(2, version.version());
    }

    @Test
    void should_omitContentFields_when_tombstone_given_noContent() {
        // given // when
        MemoryVersion tombstone = MemoryVersion.tombstone("memver_3", "ms_1", "mem_1", "notes/a", 3);

        // then
        assertEquals(MemoryVersionAction.DELETED, tombstone.action());
        assertNull(tombstone.content());
        assertNull(tombstone.size());
        assertNull(tombstone.contentSha256());
        assertFalse(tombstone.redacted());
    }

    @Test
    void should_throwIllegalArgumentException_when_created_given_contentExceeds100KB() {
        // given
        String bigContent = "a".repeat(Memory.MAX_CONTENT_BYTES + 1);

        // when // then
        assertThrows(IllegalArgumentException.class,
                () -> MemoryVersion.created("memver_1", "ms_1", "mem_1", "notes/a", 1, bigContent));
    }

    @Test
    void should_throwIllegalArgumentException_when_construct_given_versionIdWithoutPrefix() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> new MemoryVersion(1L, "ver_1", "ms_1", "mem_1", "notes/a", 1,
                        MemoryVersionAction.CREATED, "内容", 6L, "a".repeat(64),
                        false, null, OffsetDateTime.now()));
    }

    @Test
    void should_throwIllegalArgumentException_when_construct_given_entryIdWithoutPrefix() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> new MemoryVersion(1L, "memver_1", "ms_1", "entry_1", "notes/a", 1,
                        MemoryVersionAction.CREATED, "内容", 6L, "a".repeat(64),
                        false, null, OffsetDateTime.now()));
    }

    @Test
    void should_throwIllegalArgumentException_when_construct_given_nullContentForNonTombstone() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> new MemoryVersion(1L, "memver_1", "ms_1", "mem_1", "notes/a", 1,
                        MemoryVersionAction.CREATED, null, null, null,
                        false, null, OffsetDateTime.now()));
    }

    @Test
    void should_allowNullContent_when_construct_given_redactedVersion() {
        // given // when
        MemoryVersion version = new MemoryVersion(1L, "memver_1", "ms_1", "mem_1", "notes/a", 1,
                MemoryVersionAction.CREATED, null, 6L, null,
                true, OffsetDateTime.now(), OffsetDateTime.now());

        // then
        assertTrue(version.redacted());
        assertNull(version.content());
    }

    @Test
    void should_clearContentAndShaKeepSize_when_redact_given_snapshotVersion() {
        // given
        MemoryVersion version = MemoryVersion.created("memver_1", "ms_1", "mem_1", "notes/a", 1, "内容");
        Long originalSize = version.size();

        // when
        MemoryVersion redacted = version.redact();

        // then
        assertTrue(redacted.redacted());
        assertNotNull(redacted.redactedAt());
        assertNull(redacted.content());
        assertNull(redacted.contentSha256());
        assertEquals(originalSize, redacted.size());
        assertEquals("notes/a", redacted.entryPath());
    }
}
