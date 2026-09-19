package com.linkroa.deepdataagent.vault.infrastructure.convert;

import com.linkroa.deepdataagent.vault.domain.model.Vault;
import com.linkroa.deepdataagent.vault.infrastructure.persistence.entity.VaultEntity;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;

/**
 * 保管库 ⇄ 持久化实体转换器（MapStruct 静态单例）。
 * <p>领域与实体均以 {@code metadata} 承载元数据 JSON，字段同名自动映射；
 * 落库列名 {@code metadata_json} 的差异在实体侧 {@code @TableField} 收敛。</p>
 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface VaultPersistenceConvert {

    VaultPersistenceConvert INSTANCE = Mappers.getMapper(VaultPersistenceConvert.class);

    VaultEntity toEntity(Vault vault);

    Vault toDomain(VaultEntity entity);
}