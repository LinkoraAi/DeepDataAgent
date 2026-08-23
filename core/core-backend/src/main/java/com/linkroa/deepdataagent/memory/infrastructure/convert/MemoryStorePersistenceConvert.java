package com.linkroa.deepdataagent.memory.infrastructure.convert;

import com.linkroa.deepdataagent.memory.domain.model.MemoryStore;
import com.linkroa.deepdataagent.memory.infrastructure.persistence.entity.MemoryStoreEntity;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;

/**
 * 记忆库 ⇄ 持久化实体转换器（MapStruct，type 枚举 ⇄ String 名自动映射）
 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface MemoryStorePersistenceConvert {

    MemoryStorePersistenceConvert INSTANCE = Mappers.getMapper(MemoryStorePersistenceConvert.class);

    MemoryStoreEntity toEntity(MemoryStore store);

    MemoryStore toDomain(MemoryStoreEntity entity);
}