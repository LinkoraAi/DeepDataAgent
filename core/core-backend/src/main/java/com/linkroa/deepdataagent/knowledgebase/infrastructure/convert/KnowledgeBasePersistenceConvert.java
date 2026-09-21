package com.linkroa.deepdataagent.knowledgebase.infrastructure.convert;

import com.linkroa.deepdataagent.knowledgebase.domain.model.Chunk;
import com.linkroa.deepdataagent.knowledgebase.domain.model.Document;
import com.linkroa.deepdataagent.knowledgebase.domain.model.KnowledgeBase;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.ChunkContentType;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.ChunkSource;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.DocumentStatus;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.FileType;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.ImportType;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.LifecycleStatus;
import com.linkroa.deepdataagent.knowledgebase.infrastructure.persistence.entity.ChunkEntity;
import com.linkroa.deepdataagent.knowledgebase.infrastructure.persistence.entity.DocumentEntity;
import com.linkroa.deepdataagent.knowledgebase.infrastructure.persistence.entity.KnowledgeBaseEntity;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;

/**
 * 领域对象 ⇄ 持久化实体转换器（知识库 BC）。
 * <p>
 * 实体 → 领域模型方向由 default 方法手写：三个领域模型均为 record，
 * 需经各自的 {@code restore} 工厂构造（保留不可变语义与默认值兜底），MapStruct 无法直接生成。
 * 领域模型 → 实体方向字段同名同型（枚举以 {@code name()} 落库），交由 MapStruct 自动生成。
 * </p>
 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface KnowledgeBasePersistenceConvert {

    KnowledgeBasePersistenceConvert INSTANCE = Mappers.getMapper(KnowledgeBasePersistenceConvert.class);

    // ===== 领域模型 → 持久化实体（MapStruct 自动生成） =====

    /**
     * 知识库聚合根转持久化实体。
     *
     * @param knowledgeBase 知识库聚合根
     * @return 知识库实体
     */
    KnowledgeBaseEntity toEntity(KnowledgeBase knowledgeBase);

    /**
     * 文档聚合根转持久化实体。
     *
     * @param document 文档聚合根
     * @return 文档实体
     */
    DocumentEntity toEntity(Document document);

    /**
     * 切片聚合根转持久化实体。
     *
     * @param chunk 切片聚合根
     * @return 切片实体
     */
    ChunkEntity toEntity(Chunk chunk);

    // ===== 持久化实体 → 领域模型（手写，经 restore 工厂） =====

    /**
     * 知识库实体还原为聚合根。
     *
     * @param entity 知识库实体，可为 null
     * @return 知识库聚合根；实体为 null 时返回 null
     */
    default KnowledgeBase toDomain(KnowledgeBaseEntity entity) {
        if (ObjectUtils.isEmpty(entity)) {
            return null;
        }
        return KnowledgeBase.restore(
                entity.getId(),
                entity.getName(),
                entity.getDescription(),
                entity.getLanguage(),
                parseEnum(LifecycleStatus.class, entity.getLifecycleStatus()),
                entity.getErrorMessage(),
                entity.getRagEngineConfig(),
                entity.getDedupPolicy(),
                entity.getRetrievalStrategy(),
                entity.getEmbeddingConfig(),
                entity.getMultiModelConfig(),
                entity.getEntityTypeConfig(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    /**
     * 文档实体还原为聚合根。
     *
     * @param entity 文档实体，可为 null
     * @return 文档聚合根；实体为 null 时返回 null
     */
    default Document toDomain(DocumentEntity entity) {
        if (ObjectUtils.isEmpty(entity)) {
            return null;
        }
        return Document.restore(
                entity.getId(),
                entity.getKbId(),
                entity.getFileName(),
                parseEnum(FileType.class, entity.getFileType()),
                parseEnum(DocumentStatus.class, entity.getStatus()),
                entity.getErrorMessage(),
                entity.getFileSize(),
                entity.getChunkCount(),
                entity.getSourceFileProfile(),
                parseEnum(ImportType.class, entity.getImportType()),
                entity.getS3File(),
                entity.getFileContentHash(),
                entity.getChunkStrategy(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    /**
     * 切片实体还原为聚合根。
     *
     * @param entity 切片实体，可为 null
     * @return 切片聚合根；实体为 null 时返回 null
     */
    default Chunk toDomain(ChunkEntity entity) {
        if (ObjectUtils.isEmpty(entity)) {
            return null;
        }
        return Chunk.restore(
                entity.getId(),
                entity.getKbId(),
                entity.getDocumentId(),
                entity.getSequence(),
                entity.getTokens(),
                entity.getChunkContent(),
                entity.getOriginalItem(),
                parseEnum(ChunkContentType.class, entity.getChunkContentType()),
                entity.getSourceFileName(),
                entity.getS3File(),
                parseEnum(ChunkSource.class, entity.getSourceType()),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    /**
     * 枚举反解：空字符串安全返回 null，交由领域模型的紧凑构造器兜底默认值。
     *
     * @param type  枚举类型
     * @param value 落库的枚举名
     * @param <T>   枚举类型参数
     * @return 枚举值；值为空白时返回 null
     */
    private <T extends Enum<T>> T parseEnum(Class<T> type, String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        return Enum.valueOf(type, value);
    }
}
