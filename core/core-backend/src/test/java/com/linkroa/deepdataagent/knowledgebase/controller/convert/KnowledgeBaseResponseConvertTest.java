package com.linkroa.deepdataagent.knowledgebase.controller.convert;

import com.linkroa.deepdataagent.knowledgebase.controller.response.ChunkResponse;
import com.linkroa.deepdataagent.knowledgebase.controller.response.DocumentResponse;
import com.linkroa.deepdataagent.knowledgebase.controller.response.KnowledgeBaseResponse;
import com.linkroa.deepdataagent.knowledgebase.controller.response.KnowledgeBaseStatsResponse;
import com.linkroa.deepdataagent.knowledgebase.domain.model.Chunk;
import com.linkroa.deepdataagent.knowledgebase.domain.model.Document;
import com.linkroa.deepdataagent.knowledgebase.domain.model.KnowledgeBase;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.ChunkContentType;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.ChunkSource;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.DocumentStatus;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.FileType;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.ImportType;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.LifecycleStatus;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link KnowledgeBaseResponseConvert} 单元测试。
 * <p>重点覆盖枚举 → 字符串转换、空值兜底与统计映射。</p>
 */
class KnowledgeBaseResponseConvertTest {

    private final KnowledgeBaseResponseConvert convert = KnowledgeBaseResponseConvert.INSTANCE;

    @Test
    void should_convertLifecycleStatusToName_when_toResponse_given_knowledgeBase() {
        // given
        OffsetDateTime now = OffsetDateTime.now();
        KnowledgeBase kb = KnowledgeBase.restore(1L, "kb", "desc", "English", LifecycleStatus.DELETING,
                null, "{}", "{}", "{}", "{}", "{}", "{}", now, now);

        // when
        KnowledgeBaseResponse response = convert.toResponse(kb);

        // then
        assertEquals(1L, response.id());
        assertEquals(LifecycleStatus.DELETING.name(), response.lifecycleStatus());
        // language 列值原样回显（读侧投影不做归一）
        assertEquals("English", response.language());
        assertEquals("{}", response.ragEngineConfig());
        assertEquals(now, response.createdAt());
    }

    @Test
    void should_returnNull_when_toResponse_given_nullKnowledgeBase() {
        // when & then
        assertNull(convert.toResponse((KnowledgeBase) null));
    }

    @Test
    void should_convertAllEnums_when_toResponse_given_document() {
        // given
        Document document = Document.restore(2L, 1L, "a.docx", FileType.DOCX, DocumentStatus.PROCESSED, null,
                1024L, 5, "{}", ImportType.UPLOAD, "{}", "{}", null, null, null);

        // when
        DocumentResponse response = convert.toResponse(document);

        // then
        assertEquals(FileType.DOCX.name(), response.fileType());
        assertEquals(DocumentStatus.PROCESSED.name(), response.status());
        assertEquals(ImportType.UPLOAD.name(), response.importType());
        assertEquals(5, response.chunkCount());
        assertNull(response.errorMessage());
    }

    @Test
    void should_mapDefaultedEnums_when_toResponse_given_documentWithNullOptionalEnums() {
        // given
        Document document = new Document(3L, 1L, "b.txt", FileType.TXT, DocumentStatus.FAILED,
                "解析失败：文件损坏", 10L, 0, null, null, null, null, null, null, null);

        // when
        DocumentResponse response = convert.toResponse(document);

        // then
        assertEquals(DocumentStatus.FAILED.name(), response.status());
        assertEquals(ImportType.UPLOAD.name(), response.importType());
        assertEquals("解析失败：文件损坏", response.errorMessage());
        assertNull(response.sourceFileProfile());
    }

    @Test
    void should_convertContentType_when_toResponse_given_chunk() {
        // given
        Chunk chunk = Chunk.restore(4L, 1L, 2L, 1, 66, "内容", "{}", ChunkContentType.TABLE, "a.docx",
                null, ChunkSource.MANUAL, null, null);

        // when
        ChunkResponse response = convert.toResponse(chunk);

        // then
        assertEquals(4L, response.id());
        assertEquals(ChunkContentType.TABLE.name(), response.chunkContentType());
        assertEquals("内容", response.chunkContent());
        // 来源标识以枚举名透出（PARSED=解析产生 / MANUAL=人工新增）
        assertEquals(ChunkSource.MANUAL.name(), response.sourceType());
    }

    @Test
    void should_convertParsedSourceType_when_toResponse_given_parsedChunk() {
        // given：解析产生的切片（整篇重建落库来源）
        Chunk chunk = Chunk.restore(5L, 1L, 2L, 1, 66, "内容", "{}", ChunkContentType.TEXT, "a.docx",
                null, ChunkSource.PARSED, null, null);

        // when
        ChunkResponse response = convert.toResponse(chunk);

        // then
        assertEquals(ChunkSource.PARSED.name(), response.sourceType());
    }

    @Test
    void should_returnZeroCounts_when_toStatsResponse_given_nullStatistics() {
        // when
        KnowledgeBaseStatsResponse response = convert.toStatsResponse(null);

        // then
        assertEquals(0L, response.totalKnowledgeBases());
        assertEquals(0L, response.totalDocuments());
        assertEquals(0L, response.totalChunks());
    }

    @Test
    void should_ignoreMissingKeys_when_toStatsResponse_given_partialStatistics() {
        // when
        KnowledgeBaseStatsResponse response = convert.toStatsResponse(Map.of("totalDocuments", 8L));

        // then
        assertEquals(0L, response.totalKnowledgeBases());
        assertEquals(8L, response.totalDocuments());
        assertEquals(0L, response.totalChunks());
    }

    @Test
    void should_returnEmptyList_when_toResponseList_given_nullList() {
        // when & then
        assertTrue(convert.toKnowledgeBaseResponseList(null).isEmpty());
        assertTrue(convert.toDocumentResponseList(null).isEmpty());
        assertTrue(convert.toChunkResponseList(null).isEmpty());
    }

    @Test
    void should_mapEachElement_when_toResponseList_given_nonEmptyLists() {
        // given
        List<KnowledgeBase> kbs = List.of(
                KnowledgeBase.restore(1L, "kb-1", null, "Chinese", LifecycleStatus.ACTIVE,
                        null, null, null, null, null, null, null, null, null),
                KnowledgeBase.restore(2L, "kb-2", null, "Chinese", LifecycleStatus.DELETE_FAILED,
                        "[KB-CLEANUP] step=chunk_cleanup", null, null, null, null, null, null, null, null));

        // when
        List<KnowledgeBaseResponse> responses = convert.toKnowledgeBaseResponseList(kbs);

        // then
        assertEquals(2, responses.size());
        assertEquals(LifecycleStatus.DELETE_FAILED.name(), responses.get(1).lifecycleStatus());
    }
}
