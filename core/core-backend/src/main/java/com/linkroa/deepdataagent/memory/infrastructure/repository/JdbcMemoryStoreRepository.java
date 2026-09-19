package com.linkroa.deepdataagent.memory.infrastructure.repository;

import com.linkroa.deepdataagent.memory.domain.model.MemoryStore;
import com.linkroa.deepdataagent.memory.domain.repository.MemoryStoreRepository;
import com.linkroa.deepdataagent.memory.infrastructure.convert.MemoryStorePersistenceConvert;
import com.linkroa.deepdataagent.memory.infrastructure.persistence.entity.MemoryStoreEntity;
import com.linkroa.deepdataagent.memory.infrastructure.persistence.mapper.MemoryStoreMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 记忆库仓储实现（MyBatis-Plus）
 */
@Repository
public class JdbcMemoryStoreRepository implements MemoryStoreRepository {

    private final MemoryStoreMapper mapper;

    /**
     * 构造器装配唯一的表访问器依赖。
     *
     * @param mapper 记忆库表 Mapper
     */
    public JdbcMemoryStoreRepository(MemoryStoreMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public MemoryStore save(MemoryStore store) {
        MemoryStoreEntity entity = MemoryStorePersistenceConvert.INSTANCE.toEntity(store);
        entity.setId(null);
        mapper.insert(entity);
        return findByStoreId(store.storeId()).orElse(store);
    }

    @Override
    public Optional<MemoryStore> findByStoreId(String storeId) {
        return Optional.ofNullable(MemoryStorePersistenceConvert.INSTANCE.toDomain(mapper.selectByStoreId(storeId)));
    }

    @Override
    public Optional<MemoryStore> findByStoreIdForUpdate(String storeId) {
        return Optional.ofNullable(MemoryStorePersistenceConvert.INSTANCE.toDomain(mapper.selectByStoreIdForUpdate(storeId)));
    }

    @Override
    public List<MemoryStore> findByIds(Long ownerId, List<String> storeIds) {
        return mapper.selectByIds(ownerId, storeIds).stream()
                .map(MemoryStorePersistenceConvert.INSTANCE::toDomain)
                .toList();
    }

    @Override
    public List<MemoryStore> findByPage(Long ownerId, int page, int size) {
        return mapper.selectPage(ownerId, (long) Math.max(0, page - 1) * size, size).stream()
                .map(MemoryStorePersistenceConvert.INSTANCE::toDomain)
                .toList();
    }

    @Override
    public long countByOwnerId(Long ownerId) {
        return mapper.countByOwnerId(ownerId);
    }

    @Override
    public int archive(String storeId, OffsetDateTime archivedAt) {
        return mapper.archive(storeId, archivedAt);
    }

    @Override
    public int adjustStats(String storeId, int deltaCount, long deltaSize) {
        return mapper.adjustStats(storeId, deltaCount, deltaSize);
    }

    @Override
    public void deleteByStoreId(String storeId) {
        mapper.deleteByStoreId(storeId);
    }
}