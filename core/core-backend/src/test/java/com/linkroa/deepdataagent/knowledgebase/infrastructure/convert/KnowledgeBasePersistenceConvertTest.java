package com.linkroa.deepdataagent.knowledgebase.infrastructure.convert;

import com.linkroa.deepdataagent.knowledgebase.domain.model.Chunk;
import com.linkroa.deepdataagent.knowledgebase.domain.model.Document;
import com.linkroa.deepdataagent.knowledgebase.domain.model.KnowledgeBase;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.ChunkContentType;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.ChunkSource;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.DocumentStatus;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.FileType;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.ImportType;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.LifecycleStatus;
import com.linkroa.deepdataagent.knowledgebase.infrastructure.persistence.entity.ChunkEntity;
import com.linkroa.deepdataagent.knowledgebase.infrastructure.persistence.entity.DocumentEntity;
import com.linkroa.deepdataagent.knowledgebase.infrastructure.persistence.entity.KnowledgeBaseEntity;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link KnowledgeBasePersistenceConvert} 领域 ⇄ 实体映射单测。
 */
class KnowledgeBasePersistenceConvertTest {

    private static final OffsetDateTime CREATED_AT = OffsetDateTime.parse("2026-09-01T10:00:00+08:00");
    private static final OffsetDateTime UPDATED_AT = OffsetDateTime.parse("2026-09-02T11:00:00+08:00");

    /**
     * 判重内容哈希样例：SHA-256("abc") 的 64 位小写十六进制。
     * <p>用于验证 {@code file_content_hash} 作为独立一等列（非 JSONB）在实体与聚合根之间无损往返。</p>
     */
    private static final String CONTENT_HASH =
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    private final KnowledgeBasePersistenceConvert mapper = KnowledgeBasePersistenceConvert.INSTANCE;

    // ===== KnowledgeBase =====

    @Test
    void should_mapToEntityAndBack_when_toEntityAndToDomain_given_knowledgeBase() {
        // given
        KnowledgeBase kb = KnowledgeBase.restore(1L, "运营知识库", "描述", "English", LifecycleStatus.DELETING,
                "[KB-CLEANUP] step=graph_cleanup",
                "{\"engineType\":\"DOCUMENT_ENGINE\"}", "{\"matchRule\":\"BY_NAME\"}",
                "{\"strategyType\":\"MIX\"}", "{\"modelProfileId\":8}", "{\"modelProfileId\":9}",
                "{\"entityTypes\":[]}", CREATED_AT, UPDATED_AT);

        // when
        KnowledgeBaseEntity entity = mapper.toEntity(kb);
        KnowledgeBase restored = mapper.toDomain(entity);

        // then
        assertEquals(1L, entity.getId());
        assertEquals("DELETING", entity.getLifecycleStatus());
        assertEquals(kb.id(), restored.id());
        assertEquals(kb.name(), restored.name());
        assertEquals(kb.description(), restored.description());
        // language 为唯一真相源列，需在实体与聚合根之间无损往返
        assertEquals("English", entity.getLanguage());
        assertEquals(kb.language(), restored.language());
        assertEquals(LifecycleStatus.DELETING, restored.lifecycleStatus());
        // errorMessage 为删除失败留痕列，需在实体与聚合根之间无损往返
        assertEquals("[KB-CLEANUP] step=graph_cleanup", entity.getErrorMessage());
        assertEquals(kb.errorMessage(), restored.errorMessage());
        assertEquals(kb.ragEngineConfig(), restored.ragEngineConfig());
        assertEquals(kb.dedupPolicy(), restored.dedupPolicy());
        assertEquals(kb.retrievalStrategy(), restored.retrievalStrategy());
        assertEquals(kb.embeddingConfig(), restored.embeddingConfig());
        assertEquals(kb.multiModelConfig(), restored.multiModelConfig());
        assertEquals(kb.entityTypeConfig(), restored.entityTypeConfig());
        assertEquals(CREATED_AT, restored.createdAt());
        assertEquals(UPDATED_AT, restored.updatedAt());
    }

    @Test
    void should_returnNull_when_toDomain_given_nullEntity() {
        // when & then
        assertNull(mapper.toDomain((KnowledgeBaseEntity) null));
        assertNull(mapper.toDomain((DocumentEntity) null));
        assertNull(mapper.toDomain((ChunkEntity) null));
    }

    @Test
    void should_defaultActive_when_toDomain_given_blankLifecycleStatus() {
        // given
        KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
        entity.setId(2L);
        entity.setName("空状态库");
        entity.setLifecycleStatus("  ");

        // when
        KnowledgeBase restored = mapper.toDomain(entity);

        // then
        assertEquals(LifecycleStatus.ACTIVE, restored.lifecycleStatus());
    }

    // ===== Document =====

    @Test
    void should_mapToEntityAndBack_when_toEntityAndToDomain_given_document() {
        // given
        Document document = Document.restore(11L, 1L, "手册.pdf", FileType.PDF, DocumentStatus.PROCESSED,
                "解析失败：向量模型超时", 20480L, 12, "{\"pageCount\":30}", ImportType.UPLOAD,
                "{\"objectKey\":\"rag/1/source/k.pdf\"}",
                CONTENT_HASH, null, CREATED_AT, UPDATED_AT);

        // when
        DocumentEntity entity = mapper.toEntity(document);
        Document restored = mapper.toDomain(entity);

        // then
        assertEquals(11L, entity.getId());
        assertEquals("PDF", entity.getFileType());
        assertEquals("PROCESSED", entity.getStatus());
        assertEquals("UPLOAD", entity.getImportType());
        assertEquals("解析失败：向量模型超时", entity.getErrorMessage());
        assertEquals(document.kbId(), restored.kbId());
        assertEquals(document.fileName(), restored.fileName());
        assertEquals(FileType.PDF, restored.fileType());
        assertEquals(DocumentStatus.PROCESSED, restored.status());
        assertEquals("解析失败：向量模型超时", restored.errorMessage());
        assertEquals(20480L, restored.fileSize());
        assertEquals(12, restored.chunkCount());
        assertEquals("{\"pageCount\":30}", restored.sourceFileProfile());
        assertEquals(ImportType.UPLOAD, restored.importType());
        assertEquals("{\"objectKey\":\"rag/1/source/k.pdf\"}", restored.s3File());
        // 判重内容哈希为独立一等列（非 JSONB），需在实体与聚合根之间无损往返
        assertEquals(CONTENT_HASH, entity.getFileContentHash());
        assertEquals(CONTENT_HASH, restored.fileContentHash());
        assertNull(restored.chunkStrategy());
    }

    @Test
    void should_defaultPendingAndUpload_when_toDomain_given_blankStatusColumns() {
        // given
        DocumentEntity entity = new DocumentEntity();
        entity.setId(12L);
        entity.setKbId(1L);
        entity.setFileName("报告.docx");
        entity.setFileType("DOCX");
        entity.setStatus("");
        entity.setImportType(null);

        // when
        Document restored = mapper.toDomain(entity);

        // then
        assertEquals(DocumentStatus.PENDING, restored.status());
        assertEquals(ImportType.UPLOAD, restored.importType());
        assertEquals(0, restored.chunkCount());
    }

    @Test
    void should_throwException_when_toDomain_given_blankFileType() {
        // given
        DocumentEntity entity = new DocumentEntity();
        entity.setId(13L);
        entity.setKbId(1L);
        entity.setFileName("未知文件");
        entity.setFileType(" ");

        // when & then
        assertThrows(IllegalArgumentException.class, () -> mapper.toDomain(entity));
    }

    // ===== Chunk =====

    @Test
    void should_mapToEntityAndBack_when_toEntityAndToDomain_given_chunk() {
        // given
        Chunk chunk = Chunk.restore(21L, 1L, 11L, 3, 128, "切片正文", "{\"position\":1}",
                ChunkContentType.TABLE, "手册.pdf",
                "{\"objectKey\":\"rag/1/11/images/img.png\"}", ChunkSource.MANUAL, CREATED_AT, UPDATED_AT);

        // when
        ChunkEntity entity = mapper.toEntity(chunk);
        Chunk restored = mapper.toDomain(entity);

        // then
        assertEquals(21L, entity.getId());
        assertEquals("TABLE", entity.getChunkContentType());
        assertEquals("切片正文", entity.getChunkContent());
        // 来源标识以枚举名落列并在实体与聚合根之间无损往返
        assertEquals("MANUAL", entity.getSourceType());
        assertEquals(ChunkSource.MANUAL, restored.sourceType());
        assertEquals(chunk.kbId(), restored.kbId());
        assertEquals(chunk.documentId(), restored.documentId());
        assertEquals(3, restored.sequence());
        assertEquals(128, restored.tokens());
        assertEquals("切片正文", restored.chunkContent());
        assertEquals("{\"position\":1}", restored.originalItem());
        assertEquals(ChunkContentType.TABLE, restored.chunkContentType());
        assertEquals("手册.pdf", restored.sourceFileName());
        // s3_file 一等列需在实体与聚合根之间无损往返（仅对象键形态，桶概念已退役）
        assertEquals("{\"objectKey\":\"rag/1/11/images/img.png\"}", entity.getS3File());
        assertEquals("{\"objectKey\":\"rag/1/11/images/img.png\"}", restored.s3File());
    }

    @Test
    void should_defaultTextType_when_toDomain_given_blankChunkContentType() {
        // given
        ChunkEntity entity = new ChunkEntity();
        entity.setId(22L);
        entity.setKbId(1L);
        entity.setDocumentId(11L);
        entity.setChunkContent("纯文本切片");
        entity.setChunkContentType(null);

        // when
        Chunk restored = mapper.toDomain(entity);

        // then
        assertEquals(ChunkContentType.TEXT, restored.chunkContentType());
        assertEquals(22L, restored.id());
        assertNull(restored.createdAt());
    }

    @Test
    void should_defaultParsedSourceType_when_toDomain_given_blankSourceTypeColumn() {
        // given：来源列空白（历史数据/异常行）
        ChunkEntity entity = new ChunkEntity();
        entity.setId(23L);
        entity.setKbId(1L);
        entity.setDocumentId(11L);
        entity.setChunkContent("切片正文");
        entity.setSourceType(null);

        // when
        Chunk restored = mapper.toDomain(entity);

        // then：反解为 null 后由聚合根紧凑构造器兜底「解析产生」（拒绝删除优于误删）
        assertEquals(ChunkSource.PARSED, restored.sourceType());
        assertEquals(23L, restored.id());
    }
}
