package com.linkroa.deepdataagent.memory.infrastructure.convert;

import com.linkroa.deepdataagent.memory.domain.model.MemoryStore;
import com.linkroa.deepdataagent.memory.domain.model.enums.MemoryStoreStatus;
import com.linkroa.deepdataagent.memory.infrastructure.persistence.entity.MemoryStoreEntity;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;

/**
 * 记忆库 ⇄ 持久化实体转换器（MapStruct）。
 * <p>status 为领域枚举 ⇄ 小写值字符串，显式 default 方法承载映射。</p>
 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface MemoryStorePersistenceConvert {

    MemoryStorePersistenceConvert INSTANCE = Mappers.getMapper(MemoryStorePersistenceConvert.class);

    /**
     * 领域模型 → 持久化实体。
     */
    default MemoryStoreEntity toEntity(MemoryStore store) {
        if (store == null) {
            return null;
        }
        MemoryStoreEntity entity = new MemoryStoreEntity();
        entity.setId(store.id());
        entity.setStoreId(store.storeId());
        entity.setName(store.name());
        entity.setDescription(store.description());
        entity.setStatus(store.status().getValue());
        entity.setEntryCount(store.entryCount());
        entity.setTotalSize(store.totalSize());
        entity.setOwnerId(store.ownerId());
        entity.setArchivedAt(store.archivedAt());
        entity.setCreatedAt(store.createdAt());
        entity.setUpdatedAt(store.updatedAt());
        entity.setCreatedBy(store.createdBy());
        entity.setUpdatedBy(store.updatedBy());
        return entity;
    }

    /**
     * 持久化实体 → 领域模型。
     */
    default MemoryStore toDomain(MemoryStoreEntity entity) {
        if (entity == null) {
            return null;
        }
        return new MemoryStore(
                entity.getId(),
                entity.getStoreId(),
                entity.getName(),
                entity.getDescription(),
                MemoryStoreStatus.fromValue(entity.getStatus()),
                entity.getEntryCount() == null ? 0 : entity.getEntryCount(),
                entity.getTotalSize() == null ? 0L : entity.getTotalSize(),
                entity.getOwnerId(),
                entity.getArchivedAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getCreatedBy(),
                entity.getUpdatedBy()
        );
    }
}
