package com.linkroa.deepdataagent.file.infrastructure.convert;

import com.linkroa.deepdataagent.file.domain.model.File;
import com.linkroa.deepdataagent.file.domain.model.FileScope;
import com.linkroa.deepdataagent.file.domain.model.enums.FilePurpose;
import com.linkroa.deepdataagent.file.domain.model.enums.FileStatus;
import com.linkroa.deepdataagent.file.infrastructure.persistence.entity.FileEntity;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FilePersistenceConvert} 单测：全字段落库形态（purpose/status 契约小写词）、
 * scope JSONB 文本往返与脏数据归一 null、可空列兜底（sizeBytes→0、downloadable→false）。
 */
class FilePersistenceConvertTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-03T10:00:00+08:00");

    private File buildFile(FileScope scope) {
        return File.create("file_a", 1L, "out.csv", "text/csv",
                FilePurpose.TOOL_OUTPUT, scope, "{\"k\":\"v\"}",
                "hello".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void should_writeContractCodesAndScopeJson_when_toEntity_given_scopedFile() {
        // given
        File file = File.restore(11L, "file_a", 1L, "out.csv", "text/csv", 5L,
                FilePurpose.TOOL_OUTPUT, FileStatus.READY, true,
                new FileScope("sess_1", FileScope.TYPE_SESSION), "{}", "0".repeat(64), NOW, NOW);

        // when
        FileEntity entity = FilePersistenceConvert.INSTANCE.toEntity(file);

        // then（purpose/status 落契约小写词，scope 落 {id,type} JSON 文本）
        assertEquals("file_a", entity.getFileId());
        assertEquals("tool_output", entity.getPurpose());
        assertEquals("ready", entity.getStatus());
        assertEquals(Boolean.TRUE, entity.getDownloadable());
        assertEquals("{\"id\":\"sess_1\",\"type\":\"session\"}", entity.getScope());
        assertEquals(11L, entity.getId());
    }

    @Test
    void should_keepScopeNull_when_toEntity_given_unscopedFile() {
        // given（用户上传文件无作用域关联）
        File file = buildFile(null);

        // when
        FileEntity entity = FilePersistenceConvert.INSTANCE.toEntity(file);

        // then
        assertNull(entity.getScope());
        assertEquals("{\"k\":\"v\"}", entity.getMetadata());
    }

    @Test
    void should_roundTripScope_when_toDomain_given_entityFromToEntity() {
        // given
        File original = buildFile(new FileScope("sess_9", FileScope.TYPE_SESSION));

        // when
        File restored = FilePersistenceConvert.INSTANCE.toDomain(
                FilePersistenceConvert.INSTANCE.toEntity(original));

        // then（往返一致）
        assertEquals(original.fileId(), restored.fileId());
        assertEquals(new FileScope("sess_9", FileScope.TYPE_SESSION), restored.scope());
        assertEquals(FilePurpose.TOOL_OUTPUT, restored.purpose());
        assertTrue(restored.downloadable());
    }

    @Test
    void should_normalizeNullSizeAndDownloadable_when_toDomain_given_dirtyEntity() {
        // given（可空列兜底：sizeBytes null→0、downloadable null→false）
        FileEntity entity = new FileEntity();
        entity.setFileId("file_b");
        entity.setOwnerId(1L);
        entity.setFilename("note.txt");
        entity.setMimeType("text/plain");
        entity.setSizeBytes(null);
        entity.setPurpose("user_upload");
        entity.setStatus("ready");
        entity.setDownloadable(null);
        entity.setScope(null);
        entity.setContentSha256("0".repeat(64));
        entity.setCreatedAt(NOW);
        entity.setUpdatedAt(NOW);

        // when
        File file = FilePersistenceConvert.INSTANCE.toDomain(entity);

        // then
        assertEquals(0L, file.sizeBytes());
        assertFalse(file.downloadable());
        assertNull(file.scope());
    }

    @Test
    void should_returnNullScope_when_toDomain_given_invalidOrPartialScopeJson() {
        // given（非法 JSON 与缺 id/type 键的半成品对象一律归一「未关联作用域」）
        FileEntity entity = new FileEntity();
        entity.setFileId("file_c");
        entity.setOwnerId(1L);
        entity.setFilename("a.txt");
        entity.setMimeType("text/plain");
        entity.setSizeBytes(1L);
        entity.setPurpose("session_resource");
        entity.setStatus("ready");
        entity.setDownloadable(false);
        entity.setMetadata("{}");
        entity.setContentSha256("0".repeat(64));
        entity.setCreatedAt(NOW);
        entity.setUpdatedAt(NOW);

        // when & then（非法 JSON）
        entity.setScope("not-a-json");
        assertNull(FilePersistenceConvert.INSTANCE.toDomain(entity).scope());

        // when & then（缺 type 键）
        entity.setScope("{\"id\":\"sess_1\"}");
        assertNull(FilePersistenceConvert.INSTANCE.toDomain(entity).scope());
    }

    @Test
    void should_throwIllegalArgument_when_toDomain_given_unknownPurposeCode() {
        // given（值域外 purpose 契约词属数据污染，快速失败）
        FileEntity entity = new FileEntity();
        entity.setFileId("file_d");
        entity.setOwnerId(1L);
        entity.setFilename("a.txt");
        entity.setMimeType("text/plain");
        entity.setSizeBytes(1L);
        entity.setPurpose("mystery");
        entity.setStatus("ready");
        entity.setContentSha256("0".repeat(64));

        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> FilePersistenceConvert.INSTANCE.toDomain(entity));
    }
}
