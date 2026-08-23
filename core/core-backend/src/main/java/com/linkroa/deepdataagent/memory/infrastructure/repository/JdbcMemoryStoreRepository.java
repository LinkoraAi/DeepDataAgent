package com.linkroa.deepdataagent.memory.infrastructure.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linkroa.deepdataagent.memory.domain.model.MemoryStore;
import com.linkroa.deepdataagent.memory.domain.repository.MemoryStoreRepository;
import com.linkroa.deepdataagent.memory.infrastructure.convert.MemoryStorePersistenceConvert;
import com.linkroa.deepdataagent.memory.infrastructure.persistence.entity.MemoryStoreEntity;
import com.linkroa.deepdataagent.memory.infrastructure.persistence.mapper.MemoryStoreMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 记忆库仓储实现（MyBatis-Plus）
 */
@Repository
public class JdbcMemoryStoreRepository implements MemoryStoreRepository {

    private final MemoryStoreMapper mapper;

    public JdbcMemoryStoreRepository(MemoryStoreMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public MemoryStore save(MemoryStore store) {
        MemoryStoreEntity entity = MemoryStorePersistenceConvert.INSTANCE.toEntity(store);
        entity.setId(null);
        mapper.insert(entity);
        return findByMemoryId(store.memoryId()).orElse(store);
    }

    @Override
    public Optional<MemoryStore> findByMemoryId(String memoryId) {
        return Optional.ofNullable(MemoryStorePersistenceConvert.INSTANCE.toDomain(mapper.selectByMemoryId(memoryId)));
    }

    @Override
    public Optional<MemoryStore> findByMemoryIdForUpdate(String memoryId) {
        return Optional.ofNullable(MemoryStorePersistenceConvert.INSTANCE.toDomain(mapper.selectByMemoryIdForUpdate(memoryId)));
    }

    @Override
    public List<MemoryStore> findByIds(List<String> memoryIds) {
        return mapper.selectByIds(memoryIds).stream()
                .map(MemoryStorePersistenceConvert.INSTANCE::toDomain)
                .toList();
    }

    @Override
    public List<MemoryStore> findAll() {
        return mapper.selectList(Wrappers.<MemoryStoreEntity>lambdaQuery()).stream()
                .map(MemoryStorePersistenceConvert.INSTANCE::toDomain)
                .toList();
    }

    @Override
    public List<MemoryStore> findByPage(int page, int size) {
        return mapper.selectPage((long) Math.max(0, page - 1) * size, size).stream()
                .map(MemoryStorePersistenceConvert.INSTANCE::toDomain)
                .toList();
    }

    @Override
    public long countAll() {
        return mapper.countAll();
    }

    @Override
    public void deleteByMemoryId(String memoryId) {
        mapper.delete(Wrappers.<MemoryStoreEntity>lambdaUpdate()
                .eq(e -> e.getMemoryId(), memoryId));
    }
}