package com.linkroa.deepdataagent.memory.infrastructure.repository;

import com.linkroa.deepdataagent.memory.domain.model.Memory;
import com.linkroa.deepdataagent.memory.domain.model.MemoryVersion;
import com.linkroa.deepdataagent.memory.domain.repository.MemoryRepository;
import com.linkroa.deepdataagent.memory.infrastructure.convert.MemoryPersistenceConvert;
import com.linkroa.deepdataagent.memory.infrastructure.convert.MemoryVersionPersistenceConvert;
import com.linkroa.deepdataagent.memory.infrastructure.persistence.entity.MemoryEntity;
import com.linkroa.deepdataagent.memory.infrastructure.persistence.mapper.MemoryMapper;
import com.linkroa.deepdataagent.memory.infrastructure.persistence.mapper.MemoryVersionMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 记忆条目与版本仓储实现（MyBatis-Plus）。
 * <p>条目与版本写入均为「条目行 + 版本行」双写，处于应用服务同一事务内；
 * 更新与删除走条件写（OCC / 活跃行），影响行数 0 视为冲突或由上层锁行保证。</p>
 */
@Repository
public class JdbcMemoryRepository implements MemoryRepository {

    private final MemoryMapper memoryMapper;
    private final MemoryVersionMapper versionMapper;

    /**
     * 构造器装配条目与版本两张表的访问器（双写在同一事务内协作）。
     *
     * @param memoryMapper 记忆条目表 Mapper
     * @param versionMapper 记忆版本表 Mapper
     */
    public JdbcMemoryRepository(MemoryMapper memoryMapper, MemoryVersionMapper versionMapper) {
        this.memoryMapper = memoryMapper;
        this.versionMapper = versionMapper;
    }

    @Override
    public Memory createEntry(Memory memory, MemoryVersion createdVersion) {
        memoryMapper.insertEntry(MemoryPersistenceConvert.INSTANCE.toEntity(memory));
        versionMapper.insert(MemoryVersionPersistenceConvert.INSTANCE.toEntity(createdVersion));
        return findByMemoryId(memory.memoryId()).orElse(memory);
    }

    @Override
    public Optional<Memory> findByMemoryId(String memoryId) {
        return Optional.ofNullable(MemoryPersistenceConvert.INSTANCE.toDomain(
                memoryMapper.selectByMemoryId(memoryId)));
    }

    @Override
    public Optional<Memory> findByMemoryIdForUpdate(String memoryId) {
        return Optional.ofNullable(MemoryPersistenceConvert.INSTANCE.toDomain(
                memoryMapper.selectByMemoryIdForUpdate(memoryId)));
    }

    @Override
    public List<Memory> listByStoreId(String storeId) {
        return memoryMapper.selectActiveByStoreId(storeId).stream()
                .map(MemoryPersistenceConvert.INSTANCE::toDomain)
                .toList();
    }

    @Override
    public boolean updateEntry(Memory updated, int expectedVersion, MemoryVersion version) {
        MemoryEntity entity = MemoryPersistenceConvert.INSTANCE.toEntity(updated);
        if (memoryMapper.updateContentCas(entity, expectedVersion) == 0) {
            return false;
        }
        versionMapper.insert(MemoryVersionPersistenceConvert.INSTANCE.toEntity(version));
        return true;
    }

    @Override
    public boolean deleteEntry(String memoryId, MemoryVersion tombstoneVersion) {
        if (memoryMapper.tombstone(memoryId, tombstoneVersion.createdAt()) == 0) {
            return false;
        }
        versionMapper.insert(MemoryVersionPersistenceConvert.INSTANCE.toEntity(tombstoneVersion));
        return true;
    }

    @Override
    public Optional<MemoryVersion> findVersion(String versionId) {
        return Optional.ofNullable(MemoryVersionPersistenceConvert.INSTANCE.toDomain(
                versionMapper.selectByVersionId(versionId)));
    }

    @Override
    public Optional<MemoryVersion> findVersionForUpdate(String versionId) {
        return Optional.ofNullable(MemoryVersionPersistenceConvert.INSTANCE.toDomain(
                versionMapper.selectByVersionIdForUpdate(versionId)));
    }

    @Override
    public List<MemoryVersion> listVersions(String entryId) {
        return versionMapper.selectByEntryIdOrderByVersionDesc(entryId).stream()
                .map(MemoryVersionPersistenceConvert.INSTANCE::toDomain)
                .toList();
    }

    @Override
    public Optional<MemoryVersion> findVersionAt(String entryId, int version) {
        return Optional.ofNullable(MemoryVersionPersistenceConvert.INSTANCE.toDomain(
                versionMapper.selectByEntryIdAndVersion(entryId, version)));
    }

    @Override
    public boolean redactVersion(String versionId, OffsetDateTime redactedAt) {
        return versionMapper.redactByVersionId(versionId, redactedAt) > 0;
    }

    @Override
    public void deleteByStoreId(String storeId) {
        versionMapper.deleteByStoreId(storeId);
        memoryMapper.deleteByStoreId(storeId);
    }
}
