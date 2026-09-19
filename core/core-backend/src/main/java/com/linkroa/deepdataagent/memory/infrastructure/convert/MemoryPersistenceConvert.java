package com.linkroa.deepdataagent.memory.infrastructure.convert;

import com.linkroa.deepdataagent.memory.domain.model.Memory;
import com.linkroa.deepdataagent.memory.domain.model.MemoryMetadata;
import com.linkroa.deepdataagent.memory.infrastructure.persistence.entity.MemoryEntity;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;

/**
 * 记忆条目 ⇄ 持久化实体转换器（MapStruct）。
 * <p>metadata 为值对象 ⇄ JSONB 文本（{@link MemoryMetadata#toJson()} /
 * {@link MemoryMetadata#fromJson(String)}），显式 default 方法承载映射。</p>
 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface MemoryPersistenceConvert {

    MemoryPersistenceConvert INSTANCE = Mappers.getMapper(MemoryPersistenceConvert.class);

    /**
     * 领域模型 → 持久化实体。
     */
    default MemoryEntity toEntity(Memory memory) {
        if (memory == null) {
            return null;
        }
        MemoryEntity entity = new MemoryEntity();
        entity.setId(memory.id());
        entity.setMemoryId(memory.memoryId());
        entity.setStoreId(memory.storeId());
        entity.setPath(memory.path());
        entity.setVersion(memory.version());
        entity.setSize(memory.size());
        entity.setContentSha256(memory.contentSha256());
        entity.setMetadata(memory.metadata().toJson());
        entity.setCreatedAt(memory.createdAt());
        entity.setUpdatedAt(memory.updatedAt());
        return entity;
    }

    /**
     * 持久化实体 → 领域模型。
     */
    default Memory toDomain(MemoryEntity entity) {
        if (entity == null) {
            return null;
        }
        return new Memory(
                entity.getId(),
                entity.getMemoryId(),
                entity.getStoreId(),
                entity.getPath(),
                entity.getVersion(),
                entity.getSize() == null ? 0L : entity.getSize(),
                entity.getContentSha256(),
                MemoryMetadata.fromJson(entity.getMetadata()),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
