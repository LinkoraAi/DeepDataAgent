package com.linkroa.deepdataagent.file.infrastructure.convert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linkroa.deepdataagent.file.domain.model.File;
import com.linkroa.deepdataagent.file.domain.model.FileScope;
import com.linkroa.deepdataagent.file.domain.model.enums.FilePurpose;
import com.linkroa.deepdataagent.file.domain.model.enums.FileStatus;
import com.linkroa.deepdataagent.file.infrastructure.persistence.entity.FileEntity;
import org.apache.commons.lang3.StringUtils;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;

/**
 * 文件领域 ⇄ 持久化实体转换器（MapStruct 静态单例）。
 * <p>purpose / status 以契约词汇（小写 code）落库，scope / metadata 以 JSON 文本
 * 落 JSONB 列；转换逻辑由 default 方法显式承载（枚举 code 与 record 名不同名，
 * 不走 MapStruct 自动生成）。</p>
 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface FilePersistenceConvert {

    FilePersistenceConvert INSTANCE = Mappers.getMapper(FilePersistenceConvert.class);

    /** JSON 树工具（scope 值对象 ⇄ JSONB 文本序列化） */
    ObjectMapper JSON = new ObjectMapper();

    /**
     * 领域 → 实体（元数据落库形态）。
     */
    default FileEntity toEntity(File file) {
        if (file == null) {
            return null;
        }
        FileEntity entity = new FileEntity();
        entity.setId(file.id());
        entity.setFileId(file.fileId());
        entity.setOwnerId(file.ownerId());
        entity.setFilename(file.filename());
        entity.setMimeType(file.mimeType());
        entity.setSizeBytes(file.sizeBytes());
        entity.setPurpose(file.purpose().code());
        entity.setStatus(file.status().code());
        entity.setDownloadable(file.downloadable());
        entity.setScope(scopeToJson(file.scope()));
        entity.setMetadata(file.metadata());
        entity.setContentSha256(file.contentSha256());
        entity.setCreatedAt(file.createdAt());
        entity.setUpdatedAt(file.updatedAt());
        return entity;
    }

    /**
     * 实体 → 领域（restore 全量恢复形态）。
     */
    default File toDomain(FileEntity entity) {
        if (entity == null) {
            return null;
        }
        return File.restore(
                entity.getId(),
                entity.getFileId(),
                entity.getOwnerId(),
                entity.getFilename(),
                entity.getMimeType(),
                entity.getSizeBytes() == null ? 0L : entity.getSizeBytes(),
                FilePurpose.fromCode(entity.getPurpose()),
                FileStatus.fromCode(entity.getStatus()),
                Boolean.TRUE.equals(entity.getDownloadable()),
                jsonToScope(entity.getScope()),
                entity.getMetadata(),
                entity.getContentSha256(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    /** scope 值对象 → JSON 文本（null 透传 null）。 */
    private static String scopeToJson(FileScope scope) {
        if (scope == null) {
            return null;
        }
        ObjectNode node = JSON.createObjectNode();
        node.put("id", scope.id());
        node.put("type", scope.type());
        return node.toString();
    }

    /** JSON 文本 → scope 值对象（空 / 非法一律归一 null，视为未关联作用域）。 */
    private static FileScope jsonToScope(String json) {
        if (StringUtils.isBlank(json)) {
            return null;
        }
        try {
            JsonNode node = JSON.readTree(json);
            JsonNode id = node.get("id");
            JsonNode type = node.get("type");
            if (id == null || type == null) {
                return null;
            }
            return new FileScope(id.asText(), type.asText());
        } catch (Exception ex) {
            return null;
        }
    }
}
