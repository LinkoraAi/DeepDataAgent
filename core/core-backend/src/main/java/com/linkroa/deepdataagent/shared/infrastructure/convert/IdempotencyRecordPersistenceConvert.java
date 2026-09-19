package com.linkroa.deepdataagent.shared.infrastructure.convert;

import com.linkroa.deepdataagent.shared.idempotency.IdempotencyRecord;
import com.linkroa.deepdataagent.shared.infrastructure.persistence.entity.IdempotencyRecordEntity;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;

/**
 * 幂等记录 ⇄ 持久化实体转换器（MapStruct 静态单例）。
 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface IdempotencyRecordPersistenceConvert {

    IdempotencyRecordPersistenceConvert INSTANCE = Mappers.getMapper(IdempotencyRecordPersistenceConvert.class);

    IdempotencyRecordEntity toEntity(IdempotencyRecord record);

    IdempotencyRecord toDomain(IdempotencyRecordEntity entity);
}