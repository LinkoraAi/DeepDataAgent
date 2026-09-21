package com.linkroa.deepdataagent.knowledgebase.infrastructure.repository;

import com.linkroa.deepdataagent.knowledgebase.domain.model.Chunk;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.ChunkSource;
import com.linkroa.deepdataagent.knowledgebase.domain.repository.ChunkRepository;
import com.linkroa.deepdataagent.knowledgebase.infrastructure.convert.KnowledgeBasePersistenceConvert;
import com.linkroa.deepdataagent.knowledgebase.infrastructure.persistence.KbAuditFieldUtils;
import com.linkroa.deepdataagent.knowledgebase.infrastructure.persistence.entity.ChunkEntity;
import com.linkroa.deepdataagent.knowledgebase.infrastructure.persistence.mapper.ChunkMapper;
import com.linkroa.deepdataagent.shared.util.BatchSplitter;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Repository;
import org.springframework.util.CollectionUtils;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 切片仓储的关系型实现（MyBatis-Plus）。
 */
@Repository
public class JdbcChunkRepository implements ChunkRepository {

    /** 批量写入分片大小（符合事务规范 500~1000 条/批） */
    private static final int INSERT_BATCH_SIZE = 500;

    private final ChunkMapper mapper;

    public JdbcChunkRepository(ChunkMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Chunk save(Chunk chunk) {
        ChunkEntity entity = KnowledgeBasePersistenceConvert.INSTANCE.toEntity(chunk);
        entity.setId(null);
        // 彻底物理删体系：audit 字段不再由 MetaObjectHandler 填充，插入前显式补齐（无操作人上下文回落 system）
        KbAuditFieldUtils.fillInsert(entity, null);
        mapper.insert(entity);
        return findById(entity.getId()).orElse(chunk);
    }

    @Override
    public Chunk update(Chunk chunk) {
        ChunkEntity entity = KnowledgeBasePersistenceConvert.INSTANCE.toEntity(chunk);
        // 更新前显式刷新 updatedAt/updatedBy（无操作人上下文回落系统缺省值）
        KbAuditFieldUtils.fillUpdate(entity, null);
        mapper.updateById(entity);
        return findById(chunk.id()).orElse(chunk);
    }

    @Override
    public List<Chunk> saveBatch(List<Chunk> chunks, String operator) {
        if (ObjectUtils.isEmpty(chunks)) {
            return Collections.emptyList();
        }
        final KnowledgeBasePersistenceConvert convert = KnowledgeBasePersistenceConvert.INSTANCE;
        Long documentId = chunks.get(0).documentId();
        List<ChunkEntity> entities = chunks.stream()
                .map(convert::toEntity)
                .peek(entity -> {
                    entity.setId(null);
                    // 多值 INSERT 注解 SQL 不触发 MetaObjectHandler，逐条显式补齐 audit 字段
                    KbAuditFieldUtils.fillInsert(entity, operator);
                })
                .toList();
        for (List<ChunkEntity> batch : BatchSplitter.split(entities, INSERT_BATCH_SIZE)) {
            mapper.insertBatch(batch);
        }
        // 多值 INSERT 不回填主键，按 documentId 回查以获得 sequence→id 映射
        return mapper.selectAllByDocumentId(documentId).stream()
                .map(convert::toDomain)
                .toList();
    }

    @Override
    public Optional<Chunk> findById(Long id) {
        return Optional.ofNullable(KnowledgeBasePersistenceConvert.INSTANCE.toDomain(mapper.selectById(id)));
    }

    @Override
    public List<Chunk> findByKbIdAndIds(Long kbId, Collection<Long> chunkIds) {
        return mapper.selectByKbIdAndIds(kbId, chunkIds)
                .stream()
                .map(KnowledgeBasePersistenceConvert.INSTANCE::toDomain)
                .toList();
    }

    @Override
    public List<Chunk> findByDocumentId(Long documentId, int page, int size) {
        return mapper.selectByDocumentId(documentId, offset(page, size), size)
                .stream()
                .map(KnowledgeBasePersistenceConvert.INSTANCE::toDomain)
                .toList();
    }

    @Override
    public Map<Integer, Long> findIdAndSequenceByDocumentId(Long documentId) {
        Map<Integer, Long> idBySequence = new LinkedHashMap<>();
        for (ChunkEntity entity : mapper.selectIdAndSequenceByDocumentId(documentId)) {
            if (ObjectUtils.isEmpty(entity.getSequence()) || ObjectUtils.isEmpty(entity.getId())) {
                continue;
            }
            idBySequence.put(entity.getSequence(), entity.getId());
        }
        return idBySequence;
    }

    @Override
    public long countByDocumentId(Long documentId) {
        return mapper.countByDocumentId(documentId);
    }

    @Override
    public List<Chunk> findByKbId(Long kbId, Long documentId, Integer sequence, String keyword, int page, int size) {
        return mapper.selectByKbId(kbId, documentId, sequence, keyword, offset(page, size), size)
                .stream()
                .map(KnowledgeBasePersistenceConvert.INSTANCE::toDomain)
                .toList();
    }

    @Override
    public long countByKbId(Long kbId, Long documentId, Integer sequence, String keyword) {
        return mapper.countByKbId(kbId, documentId, sequence, keyword);
    }

    @Override
    public void deleteById(Long id) {
        // 彻底物理删体系：无 @TableLogic，deleteById 直接生成 DELETE FROM（行消失即已删除）
        mapper.deleteById(id);
    }

    @Override
    public void deleteByDocumentId(Long documentId) {
        mapper.deleteByDocumentId(documentId);
    }

    @Override
    public void deleteByKbId(Long kbId) {
        mapper.deleteByKbId(kbId);
    }

    @Override
    public long countByKbIdOnly(Long kbId) {
        return mapper.countByKbIdOnly(kbId);
    }

    @Override
    public List<Long> findIdsByKbId(Long kbId, int limit) {
        return mapper.selectIdsByKbId(kbId, limit);
    }

    @Override
    public List<Long> findIdsByDocumentId(Long documentId, int limit) {
        return mapper.selectIdsByDocumentId(documentId, limit);
    }

    @Override
    public int deleteByIds(List<Long> ids) {
        return mapper.deleteByIds(ids);
    }

    @Override
    public Map<Long, Long> findDocumentIdsByChunkIds(Collection<Long> chunkIds) {
        if (CollectionUtils.isEmpty(chunkIds)) {
            return Collections.emptyMap();
        }
        Map<Long, Long> documentIdByChunk = new LinkedHashMap<>();
        for (ChunkEntity entity : mapper.selectIdAndDocumentIdByIds(chunkIds)) {
            if (ObjectUtils.isEmpty(entity.getId()) || ObjectUtils.isEmpty(entity.getDocumentId())) {
                continue;
            }
            documentIdByChunk.put(entity.getId(), entity.getDocumentId());
        }
        return documentIdByChunk;
    }

    @Override
    public Map<Long, Long> findKbIdsByChunkIds(Collection<Long> chunkIds) {
        if (CollectionUtils.isEmpty(chunkIds)) {
            return Collections.emptyMap();
        }
        Map<Long, Long> kbIdByChunk = new LinkedHashMap<>();
        for (ChunkEntity entity : mapper.selectIdAndKbIdByIds(chunkIds)) {
            if (ObjectUtils.isEmpty(entity.getId()) || ObjectUtils.isEmpty(entity.getKbId())) {
                continue;
            }
            kbIdByChunk.put(entity.getId(), entity.getKbId());
        }
        return kbIdByChunk;
    }

    @Override
    public Map<Long, String> findMediaReferencesByDocumentId(Long documentId) {
        if (ObjectUtils.isEmpty(documentId)) {
            return Collections.emptyMap();
        }
        Map<Long, String> referenceByChunk = new LinkedHashMap<>();
        for (ChunkEntity entity : mapper.selectMediaReferencesByDocumentId(documentId)) {
            if (ObjectUtils.isEmpty(entity.getId()) || StringUtils.isBlank(entity.getS3File())) {
                continue;
            }
            referenceByChunk.put(entity.getId(), entity.getS3File());
        }
        return referenceByChunk;
    }

    @Override
    public Integer findMaxSequenceByDocumentId(Long documentId) {
        return mapper.selectMaxSequenceByDocumentId(documentId);
    }

    @Override
    public Map<Long, ChunkSource> findSourcesByChunkIds(Collection<Long> chunkIds) {
        if (CollectionUtils.isEmpty(chunkIds)) {
            return Collections.emptyMap();
        }
        Map<Long, ChunkSource> sourceByChunk = new LinkedHashMap<>();
        for (ChunkEntity entity : mapper.selectIdAndSourceTypeByIds(chunkIds)) {
            if (ObjectUtils.isEmpty(entity.getId())) {
                continue;
            }
            // 来源列缺失的异常行兜底「解析产生」：拒绝删除优于误删的安全方向（与聚合根紧凑构造器同口径）
            ChunkSource source = StringUtils.isBlank(entity.getSourceType())
                    ? ChunkSource.PARSED : ChunkSource.valueOf(entity.getSourceType());
            sourceByChunk.put(entity.getId(), source);
        }
        return sourceByChunk;
    }

    private long offset(int page, int size) {
        return (long) Math.max(0, page - 1) * size;
    }
}
