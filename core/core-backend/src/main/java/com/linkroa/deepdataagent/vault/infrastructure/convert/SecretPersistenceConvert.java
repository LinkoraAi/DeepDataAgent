package com.linkroa.deepdataagent.vault.infrastructure.convert;

import com.linkroa.deepdataagent.vault.domain.model.Secret;
import com.linkroa.deepdataagent.vault.infrastructure.persistence.entity.SecretEntity;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;

/**
 * 凭证密钥 ⇄ 持久化实体转换器（MapStruct）
 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface SecretPersistenceConvert {

    SecretPersistenceConvert INSTANCE = Mappers.getMapper(SecretPersistenceConvert.class);

    SecretEntity toEntity(Secret secret);

    Secret toDomain(SecretEntity entity);
}