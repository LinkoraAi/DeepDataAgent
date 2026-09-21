package com.linkroa.deepdataagent.knowledgebase.domain.model;

import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.ChunkContentType;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.ChunkSource;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Chunk} 切片聚合根单测：覆盖不变量校验、字段兜底（内容形态 / 来源标识）、
 * 两个创建工厂（人工新增恒打 MANUAL / 整篇重建恒打 PARSED）的差异与内容更新语义
 * （编辑保留来源标识，写入即定不可改写）。
 */
class ChunkTest {

    private static final OffsetDateTime CREATED_AT = OffsetDateTime.parse("2026-09-01T10:00:00+08:00");

    /** 多模态图片对象存储引用 JSON（s3_file 一等列，仅对象键形态，桶概念已退役） */
    private static final String S3_FILE_JSON = "{\"objectKey\":\"rag/10/1/image.png\"}";

    private static Chunk chunkOf(String s3File) {
        return Chunk.restore(1L, 10L, 100L, 1, 30, "切片内容", "{\"page\":1}",
                ChunkContentType.TEXT, "手册.pdf", s3File, ChunkSource.PARSED, CREATED_AT, CREATED_AT);
    }

    /**
     * 构造已落库的人工新增来源切片（restore 模拟从数据库回读，来源标识为 MANUAL）。
     */
    private static Chunk manualChunkOf(String s3File) {
        return Chunk.restore(1L, 10L, 100L, 1, 30, "切片内容", "{\"page\":1}",
                ChunkContentType.TEXT, "手册.pdf", s3File, ChunkSource.MANUAL, CREATED_AT, CREATED_AT);
    }

    @Test
    void should_leaveIdNullAndFillTimestamps_when_create_given_manualNewChunk() {
        // when
        Chunk created = Chunk.create(10L, 100L, 1, 30, "切片内容", null,
                ChunkContentType.TEXT, "手册.pdf");

        // then：人工新增主键为空、时间戳齐备；人工创建路径不携带图片引用
        assertNull(created.id());
        assertNotNull(created.createdAt());
        assertEquals(created.createdAt(), created.updatedAt());
        assertNull(created.s3File());
    }

    @Test
    void should_tagManualSourceType_when_create_given_validInput() {
        // when
        Chunk created = Chunk.create(10L, 100L, 1, 30, "切片内容", null,
                ChunkContentType.TEXT, "手册.pdf");

        // then：人工新增工厂恒打「人工新增」标（写入即定，是人工删除入口的准入依据）
        assertEquals(ChunkSource.MANUAL, created.sourceType());
    }

    @Test
    void should_tagParsedSourceType_when_createForRebuild_given_validInput() {
        // when
        Chunk created = Chunk.createForRebuild(10L, 100L, 1, 30, "切片内容", null,
                ChunkContentType.TEXT, "手册.pdf", S3_FILE_JSON);

        // then：整篇重建工厂恒打「解析产生」标
        assertEquals(ChunkSource.PARSED, created.sourceType());
    }

    @Test
    void should_defaultParsedSourceType_when_restore_given_nullSourceType() {
        // when：来源列缺失（历史数据/异常行）经紧凑构造器兜底
        Chunk restored = Chunk.restore(1L, 10L, 100L, 1, 30, "切片内容", null,
                ChunkContentType.TEXT, "手册.pdf", null, null, CREATED_AT, CREATED_AT);

        // then：未打标按「解析产生」的安全方向兜底（拒绝删除优于误删）
        assertEquals(ChunkSource.PARSED, restored.sourceType());
    }

    @Test
    void should_carryS3File_when_createForRebuild_given_multimodalChunk() {
        // when
        Chunk created = Chunk.createForRebuild(10L, 100L, 1, 30, "切片内容", null,
                ChunkContentType.TEXT, "手册.pdf", S3_FILE_JSON);

        // then：整篇重建路径图片引用随重建落一等列；创建语义（主键空、时间戳齐备）与人工新增一致
        assertNull(created.id());
        assertNotNull(created.createdAt());
        assertEquals(created.createdAt(), created.updatedAt());
        assertEquals(S3_FILE_JSON, created.s3File());
    }

    @Test
    void should_carryNullS3File_when_createForRebuild_given_textChunk() {
        // when：文本切片无图片引用
        Chunk created = Chunk.createForRebuild(10L, 100L, 1, 30, "切片内容", null,
                ChunkContentType.TEXT, "手册.pdf", null);

        // then
        assertNull(created.s3File());
    }

    @Test
    void should_preserveSourceType_when_withContent_given_manualChunk() {
        // given：已落库的人工新增来源切片
        Chunk existing = manualChunkOf(null);

        // when
        Chunk edited = existing.withContent("新内容", 88);

        // then：编辑保留来源标识（写入即定，MUST NOT 被后续操作改写）
        assertEquals(ChunkSource.MANUAL, edited.sourceType());
    }

    @Test
    void should_refreshContentAndUpdatedAt_when_withContent_given_existingChunk() {
        // given
        Chunk existing = chunkOf(S3_FILE_JSON);

        // when
        Chunk edited = existing.withContent("新内容", 88);

        // then：内容与 token 更新、更新时间刷新；其余字段（含创建时间与图片引用）不受影响
        assertEquals("新内容", edited.chunkContent());
        assertEquals(88, edited.tokens().intValue());
        assertEquals(existing.id(), edited.id());
        assertEquals(existing.kbId(), edited.kbId());
        assertEquals(existing.documentId(), edited.documentId());
        assertEquals(existing.sequence(), edited.sequence());
        assertEquals(existing.originalItem(), edited.originalItem());
        assertEquals(existing.s3File(), edited.s3File());
        assertEquals(CREATED_AT, edited.createdAt());
        assertTrue(edited.updatedAt().isAfter(CREATED_AT));
    }

    @Test
    void should_fallbackContentTypeToText_when_new_given_nullContentType() {
        // when
        Chunk chunk = Chunk.restore(1L, 10L, 100L, 1, 30, "切片内容", null,
                null, "手册.pdf", null, ChunkSource.PARSED, CREATED_AT, CREATED_AT);

        // then
        assertEquals(ChunkContentType.TEXT, chunk.chunkContentType());
    }

    @Test
    void should_throwIllegalArgument_when_new_given_missingKbId() {
        // when & then
        assertThrows(IllegalArgumentException.class, () -> Chunk.create(null, 100L, 1, 30,
                "切片内容", null, ChunkContentType.TEXT, "手册.pdf"));
    }

    @Test
    void should_throwIllegalArgument_when_new_given_missingDocumentId() {
        // when & then
        assertThrows(IllegalArgumentException.class, () -> Chunk.create(10L, null, 1, 30,
                "切片内容", null, ChunkContentType.TEXT, "手册.pdf"));
    }

    @Test
    void should_throwIllegalArgument_when_new_given_blankContent() {
        // when & then：空白内容视为非法
        assertThrows(IllegalArgumentException.class, () -> Chunk.create(10L, 100L, 1, 30,
                "   ", null, ChunkContentType.TEXT, "手册.pdf"));
    }
}