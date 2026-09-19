package com.linkroa.deepdataagent.memory.infrastructure.convert;

import com.linkroa.deepdataagent.memory.domain.model.MemoryVersion;
import com.linkroa.deepdataagent.memory.domain.model.enums.MemoryVersionAction;
import com.linkroa.deepdataagent.memory.infrastructure.persistence.entity.MemoryVersionEntity;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;

/**
 * 记忆版本 ⇄ 持久化实体转换器（MapStruct）。
 * <p>action 为领域枚举 ⇄ 小写值字符串，显式 default 方法承载映射。</p>
 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface MemoryVersionPersistenceConvert {

    MemoryVersionPersistenceConvert INSTANCE = Mappers.getMapper(MemoryVersionPersistenceConvert.class);

    /**
     * 领域模型 → 持久化实体。
     */
    default MemoryVersionEntity toEntity(MemoryVersion version) {
        if (version == null) {
            return null;
        }
        MemoryVersionEntity entity = new MemoryVersionEntity();
        entity.setId(version.id());
        entity.setVersionId(version.versionId());
        entity.setStoreId(version.storeId());
        entity.setEntryId(version.entryId());
        entity.setEntryPath(version.entryPath());
        entity.setVersion(version.version());
        entity.setAction(version.action().getValue());
        entity.setContent(version.content());
        entity.setSize(version.size());
        entity.setContentSha256(version.contentSha256());
        entity.setRedacted(version.redacted());
        entity.setRedactedAt(version.redactedAt());
        entity.setCreatedAt(version.createdAt());
        return entity;
    }

    /**
     * 持久化实体 → 领域模型。
     */
    default MemoryVersion toDomain(MemoryVersionEntity entity) {
        if (entity == null) {
            return null;
        }
        return new MemoryVersion(
                entity.getId(),
                entity.getVersionId(),
                entity.getStoreId(),
                entity.getEntryId(),
                entity.getEntryPath(),
                entity.getVersion(),
                MemoryVersionAction.fromValue(entity.getAction()),
                entity.getContent(),
                entity.getSize(),
                entity.getContentSha256(),
                Boolean.TRUE.equals(entity.getRedacted()),
                entity.getRedactedAt(),
                entity.getCreatedAt()
        );
    }
}
