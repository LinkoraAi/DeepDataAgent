package com.linkroa.deepdataagent.knowledgebase.infrastructure.repository;

import com.linkroa.deepdataagent.knowledgebase.domain.model.Chunk;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.ChunkContentType;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.ChunkSource;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.DocumentStatus;
import com.linkroa.deepdataagent.knowledgebase.infrastructure.persistence.entity.ChunkEntity;
import com.linkroa.deepdataagent.knowledgebase.infrastructure.persistence.mapper.ChunkMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link JdbcChunkRepository} 仓储实现单测（mock MyBatis Mapper）。
 */
@ExtendWith(MockitoExtension.class)
class JdbcChunkRepositoryTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-01T10:00:00+08:00");

    @Mock
    private ChunkMapper mapper;

    @InjectMocks
    private JdbcChunkRepository repository;

    private static ChunkEntity entityOf(Long id, Long kbId, Long documentId, Integer sequence) {
        ChunkEntity entity = new ChunkEntity();
        entity.setId(id);
        entity.setKbId(kbId);
        entity.setDocumentId(documentId);
        entity.setSequence(sequence);
        entity.setTokens(64);
        entity.setChunkContent("切片内容-" + sequence);
        entity.setChunkContentType("TEXT");
        entity.setSourceFileName("手册.pdf");
        entity.setCreatedAt(NOW);
        entity.setUpdatedAt(NOW);
        return entity;
    }

    private static Chunk aggregateOf(Long id, Integer sequence) {
        return Chunk.restore(id, 1L, 11L, sequence, 64, "切片内容-" + sequence,
                null, ChunkContentType.TEXT, "手册.pdf", null, ChunkSource.PARSED, NOW, NOW);
    }

    /**
     * 构造带来源标识列值的轻量实体（「切片 → 来源」投影回读形态：仅 id + source_type 有效）。
     *
     * @param id         切片主键
     * @param sourceType 来源列值（枚举名字符串；null/空白模拟异常行）
     * @return 切片实体
     */
    private static ChunkEntity sourceEntityOf(Long id, String sourceType) {
        ChunkEntity entity = new ChunkEntity();
        entity.setId(id);
        entity.setSourceType(sourceType);
        return entity;
    }

    @Test
    void should_returnPersistedAggregate_when_save_given_newChunkWithoutId() {
        // given
        Chunk chunk = Chunk.create(1L, 11L, 0, 64, "首块内容", null, ChunkContentType.TEXT, "手册.pdf");
        doAnswer(invocation -> {
            ChunkEntity argument = invocation.getArgument(0);
            assertNull(argument.getId());
            argument.setId(300L);
            return 1;
        }).when(mapper).insert(any(ChunkEntity.class));
        when(mapper.selectById(300L)).thenReturn(entityOf(300L, 1L, 11L, 0));

        // when
        Chunk saved = repository.save(chunk);

        // then
        assertEquals(300L, saved.id());
        // save 后重读数据库，返回的是落库后的聚合而非入参对象
        assertEquals("切片内容-0", saved.chunkContent());
        // 人工新增工厂的 MANUAL 标经映射落 source_type 列（枚举 → 枚举名字符串）
        ArgumentCaptor<ChunkEntity> insertCaptor = ArgumentCaptor.forClass(ChunkEntity.class);
        verify(mapper).insert(insertCaptor.capture());
        assertEquals("MANUAL", insertCaptor.getValue().getSourceType());
        verify(mapper, never()).updateById(any(ChunkEntity.class));
    }

    @Test
    void should_callUpdateById_when_update_given_existingChunk() {
        // given
        Chunk chunk = aggregateOf(51L, 2);
        when(mapper.selectById(51L)).thenReturn(entityOf(51L, 1L, 11L, 2));

        // when
        Chunk updated = repository.update(chunk);

        // then
        ArgumentCaptor<ChunkEntity> captor = ArgumentCaptor.forClass(ChunkEntity.class);
        verify(mapper).updateById(captor.capture());
        assertEquals(51L, captor.getValue().getId());
        assertEquals(2, captor.getValue().getSequence());
        verify(mapper, never()).insert(any(ChunkEntity.class));
        assertEquals(51L, updated.id());
    }

    @Test
    void should_returnEmpty_when_findById_given_noRecord() {
        // given
        when(mapper.selectById(404L)).thenReturn(null);

        // when
        Optional<Chunk> found = repository.findById(404L);

        // then
        assertFalse(found.isPresent());
    }

    @Test
    void should_convertPageToOffset_when_findByDocumentId_given_secondPage() {
        // given
        when(mapper.selectByDocumentId(11L, 30L, 30))
                .thenReturn(List.of(entityOf(61L, 1L, 11L, 30)));

        // when
        List<Chunk> found = repository.findByDocumentId(11L, 2, 30);

        // then
        assertEquals(1, found.size());
        assertEquals(61L, found.get(0).id());
        assertEquals(ChunkContentType.TEXT, found.get(0).chunkContentType());
        verify(mapper).selectByDocumentId(11L, 30L, 30);
    }

    @Test
    void should_delegateCount_when_countByDocumentId_given_documentId() {
        // given
        when(mapper.countByDocumentId(11L)).thenReturn(9L);

        // when
        long total = repository.countByDocumentId(11L);

        // then
        assertEquals(9L, total);
    }

    @Test
    void should_passAllConditions_when_findByKbId_given_documentSequenceAndKeyword() {
        // given
        when(mapper.selectByKbId(1L, 11L, 3, "关键字", 0L, 20))
                .thenReturn(List.of(entityOf(62L, 1L, 11L, 3)));

        // when
        List<Chunk> found = repository.findByKbId(1L, 11L, 3, "关键字", 1, 20);

        // then
        assertEquals(1, found.size());
        assertEquals(3, found.get(0).sequence());
    }

    @Test
    void should_passNullConditions_when_countByKbId_given_optionalFiltersAbsent() {
        // given
        when(mapper.countByKbId(1L, null, null, null)).thenReturn(120L);

        // when
        long total = repository.countByKbId(1L, null, null, null);

        // then
        assertEquals(120L, total);
        verify(mapper).countByKbId(1L, null, null, null);
    }

    @Test
    void should_delegateCountOnly_when_countByKbIdOnly_given_kbId() {
        // given
        when(mapper.countByKbIdOnly(1L)).thenReturn(4L);

        // when
        long total = repository.countByKbIdOnly(1L);

        // then
        assertEquals(4L, total);
    }

    @Test
    void should_delegateDeletes_when_deleteOperations_given_variousScopes() {
        // when
        repository.deleteById(71L);
        repository.deleteByDocumentId(11L);
        repository.deleteByKbId(1L);

        // then
        verify(mapper).deleteById(71L);
        verify(mapper).deleteByDocumentId(11L);
        verify(mapper).deleteByKbId(1L);
    }

    @Test
    void should_fillAuditAndRequery_when_saveBatch_given_newChunks() {
        // given
        List<Chunk> chunks = List.of(
                Chunk.create(1L, 11L, 1, 64, "切片内容-1", null, ChunkContentType.TEXT, "手册.pdf"),
                Chunk.create(1L, 11L, 2, 64, "切片内容-2", null, ChunkContentType.TEXT, "手册.pdf"));
        when(mapper.selectAllByDocumentId(11L)).thenReturn(List.of(
                entityOf(2001L, 1L, 11L, 1),
                entityOf(2002L, 1L, 11L, 2)));

        // when
        List<Chunk> persisted = repository.saveBatch(chunks, "7");

        // then
        ArgumentCaptor<List<ChunkEntity>> captor = ArgumentCaptor.captor();
        verify(mapper).insertBatch(captor.capture());
        List<ChunkEntity> rows = captor.getValue();
        assertEquals(2, rows.size());
        for (ChunkEntity row : rows) {
            // 批量写入前需清空入参主键并显式补齐 audit 字段（彻底物理删体系，无 isDeleted）
            assertNull(row.getId());
            assertEquals("7", row.getCreatedBy());
            assertEquals("7", row.getUpdatedBy());
            assertNotNull(row.getCreatedAt());
            assertNotNull(row.getUpdatedAt());
            // insertBatch 已含 source_type 列：来源标识随实体一并批量写入
            assertEquals("MANUAL", row.getSourceType());
        }
        // 多值 INSERT 不回填主键，结果来自按 documentId 的回查
        assertEquals(2, persisted.size());
        assertEquals(2001L, persisted.get(0).id());
        assertEquals(2002L, persisted.get(1).id());
    }

    @Test
    void should_fallbackSystemOperator_when_saveBatch_given_blankOperator() {
        // given
        List<Chunk> chunks = List.of(
                Chunk.create(1L, 11L, 1, 64, "切片内容-1", null, ChunkContentType.TEXT, "手册.pdf"));
        when(mapper.selectAllByDocumentId(11L)).thenReturn(List.of(entityOf(2001L, 1L, 11L, 1)));

        // when
        repository.saveBatch(chunks, null);

        // then
        ArgumentCaptor<List<ChunkEntity>> captor = ArgumentCaptor.captor();
        verify(mapper).insertBatch(captor.capture());
        assertEquals("system", captor.getValue().get(0).getCreatedBy());
    }

    @Test
    void should_splitIntoBatches_when_saveBatch_given_overBatchSize() {
        // given
        List<Chunk> chunks = IntStream.rangeClosed(1, 1200)
                .mapToObj(index -> Chunk.create(1L, 11L, index, 64, "切片内容-" + index, null,
                        ChunkContentType.TEXT, "手册.pdf"))
                .toList();
        when(mapper.selectAllByDocumentId(11L)).thenReturn(List.of());

        // when
        List<Chunk> persisted = repository.saveBatch(chunks, "7");

        // then：按 500 条/批拆分为 3 次批量写入
        ArgumentCaptor<List<ChunkEntity>> captor = ArgumentCaptor.captor();
        verify(mapper, times(3)).insertBatch(captor.capture());
        assertEquals(500, captor.getAllValues().get(0).size());
        assertEquals(500, captor.getAllValues().get(1).size());
        assertEquals(200, captor.getAllValues().get(2).size());
        assertTrue(persisted.isEmpty());
    }

    @Test
    void should_writeNothing_when_saveBatch_given_emptyChunks() {
        // when
        List<Chunk> persisted = repository.saveBatch(List.of(), "7");

        // then
        assertTrue(persisted.isEmpty());
        verify(mapper, never()).insertBatch(anyList());
        verify(mapper, never()).selectAllByDocumentId(any());
    }

    @Test
    void should_convertEntities_when_findByKbIdAndIds_given_validIds() {
        // given
        when(mapper.selectByKbIdAndIds(1L, List.of(300L)))
                .thenReturn(List.of(entityOf(300L, 1L, 11L, 2)));

        // when
        List<Chunk> found = repository.findByKbIdAndIds(1L, List.of(300L));

        // then：实体完整映射到切片聚合（正文/内容形态/来源文件名随查询带回，供检索消费展示）
        assertEquals(1, found.size());
        Chunk chunk = found.get(0);
        assertEquals(300L, chunk.id());
        assertEquals(1L, chunk.kbId());
        assertEquals(11L, chunk.documentId());
        assertEquals(2, chunk.sequence());
        assertEquals("切片内容-2", chunk.chunkContent());
        assertEquals(ChunkContentType.TEXT, chunk.chunkContentType());
        assertEquals("手册.pdf", chunk.sourceFileName());
        verify(mapper).selectByKbIdAndIds(1L, List.of(300L));
    }

    @Test
    void should_returnEmpty_when_findByKbIdAndIds_given_emptyChunkIds() {
        // given：仓储按接口约定透传 Mapper，空参短路由 Mapper 层内部保证（mock 需显式回空列表）
        when(mapper.selectByKbIdAndIds(1L, List.of())).thenReturn(List.of());

        // when
        List<Chunk> found = repository.findByKbIdAndIds(1L, List.of());

        // then：空入参返回空列表且不抛异常（防 NPE 语义），委托关系仍被正确调用
        assertTrue(found.isEmpty());
        verify(mapper).selectByKbIdAndIds(1L, List.of());
    }

    @Test
    void should_delegateMaxSequenceLookup_when_findMaxSequenceByDocumentId_given_documentWithChunks() {
        // given：Mapper 回读文档内当前最大序号（降序取首行的 sequence 投影）
        when(mapper.selectMaxSequenceByDocumentId(11L)).thenReturn(7);

        // when
        Integer maxSequence = repository.findMaxSequenceByDocumentId(11L);

        // then：等值透传，供人工新增入口在其上加一分配序号
        assertEquals(7, maxSequence.intValue());
        verify(mapper).selectMaxSequenceByDocumentId(11L);
    }

    @Test
    void should_returnNull_when_findMaxSequenceByDocumentId_given_documentWithoutChunks() {
        // given：文档尚无切片（Mapper 无命中返回 null）
        when(mapper.selectMaxSequenceByDocumentId(11L)).thenReturn(null);

        // when
        Integer maxSequence = repository.findMaxSequenceByDocumentId(11L);

        // then：null 照实透传，调用方按首个分块自 0 起分配
        assertNull(maxSequence);
    }

    @Test
    void should_mapSourceEnums_when_findSourcesByChunkIds_given_sourceTypeColumns() {
        // given：投影回读两条存活切片，来源列分别为两种枚举名
        List<Long> chunkIds = List.of(300L, 301L);
        when(mapper.selectIdAndSourceTypeByIds(chunkIds)).thenReturn(List.of(
                sourceEntityOf(300L, "MANUAL"), sourceEntityOf(301L, "PARSED")));

        // when
        Map<Long, ChunkSource> sourceByChunk = repository.findSourcesByChunkIds(chunkIds);

        // then：字符串列值反解为枚举，键为切片主键
        assertEquals(2, sourceByChunk.size());
        assertEquals(ChunkSource.MANUAL, sourceByChunk.get(300L));
        assertEquals(ChunkSource.PARSED, sourceByChunk.get(301L));
    }

    @Test
    void should_fallbackParsed_when_findSourcesByChunkIds_given_blankSourceTypeColumn() {
        // given：来源列缺失的异常行（null / 空白）
        List<Long> chunkIds = List.of(300L, 301L);
        when(mapper.selectIdAndSourceTypeByIds(chunkIds)).thenReturn(List.of(
                sourceEntityOf(300L, null), sourceEntityOf(301L, "  ")));

        // when
        Map<Long, ChunkSource> sourceByChunk = repository.findSourcesByChunkIds(chunkIds);

        // then：一律兜底「解析产生」（拒绝删除优于误删的安全方向）
        assertEquals(ChunkSource.PARSED, sourceByChunk.get(300L));
        assertEquals(ChunkSource.PARSED, sourceByChunk.get(301L));
    }

    @Test
    void should_returnEmptyWithoutMapperCall_when_findSourcesByChunkIds_given_emptyChunkIds() {
        // when：空集合入参按接口约定返回空映射
        Map<Long, ChunkSource> sourceByChunk = repository.findSourcesByChunkIds(List.of());

        // then：零 DB 交互
        assertTrue(sourceByChunk.isEmpty());
        verifyNoInteractions(mapper);
    }

    @Test
    void should_pinProcessedVisibilityFilter_when_readByKbIdAndIds() {
        // 检索可见性契约：按主键回取切片的过滤子查询只放行「已处理」文档的切片。
        // 真值行级过滤由 MyBatis 生成的 SQL 承担（需真实数据库验证），此处锁定子查询取值，
        // 防止过滤被静默移除或改成放行摄入中 / 失败状态。
        String subQuery = ChunkMapper.RETRIEVAL_VISIBLE_DOCUMENT_SUB_QUERY;
        assertTrue(subQuery.contains("FROM document"));
        assertTrue(subQuery.contains("status = '" + DocumentStatus.PROCESSED.name() + "'"));
        assertFalse(subQuery.contains(DocumentStatus.PENDING.name()));
        assertFalse(subQuery.contains(DocumentStatus.PROCESSING.name()));
        assertFalse(subQuery.contains(DocumentStatus.FAILED.name()));
    }
}
