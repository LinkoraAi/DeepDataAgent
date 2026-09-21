package com.linkroa.deepdataagent.rag.infrastructure.convert;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkroa.deepdataagent.rag.domain.enums.CacheType;
import com.linkroa.deepdataagent.rag.domain.model.EntityInfoVector;
import com.linkroa.deepdataagent.rag.domain.model.EntityNode;
import com.linkroa.deepdataagent.rag.domain.model.EntityProperties;
import com.linkroa.deepdataagent.rag.domain.model.LlmCacheEntry;
import com.linkroa.deepdataagent.rag.domain.model.RelationEdge;
import com.linkroa.deepdataagent.rag.domain.model.RelationInfoVector;
import com.linkroa.deepdataagent.rag.domain.model.RelationProperties;
import com.linkroa.deepdataagent.rag.infrastructure.persistence.entity.EntityInfoVectorEntity;
import com.linkroa.deepdataagent.rag.infrastructure.persistence.entity.EntityNodeGraphEntity;
import com.linkroa.deepdataagent.rag.infrastructure.persistence.entity.LlmCacheEntity;
import com.linkroa.deepdataagent.rag.infrastructure.persistence.entity.RelationEdgeGraphEntity;
import com.linkroa.deepdataagent.rag.infrastructure.persistence.entity.RelationInfoVectorEntity;
import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 图谱/向量/LLM 缓存领域对象 ⇄ 持久化实体转换器（RAG BC）。
 * <p>
 * 领域模型 → 实体方向由 MapStruct 自动生成（字段同名同型），仅对
 * properties 对象 → JSON 文本、float[] → 向量字面量、List&lt;Long&gt; → JSON 数组
 * 显式指定命名转换；实体 → 领域模型方向由 default 方法手写：图节点/关系边经紧凑构造器
 * 恢复（携带 JSONB 反序列化与空值归一），向量与缓存条目经各自 {@code restore} 工厂恢复。
 * JSON 序列化使用 Jackson 2（{@code com.fasterxml.jackson}），与 rag 包既有实现一致。
 * </p>
 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface RagGraphPersistenceConvert {

    RagGraphPersistenceConvert INSTANCE = Mappers.getMapper(RagGraphPersistenceConvert.class);

    /** Jackson 2 序列化器：properties 对象/chunk_ids 数组与 DB JSON(B) 列互转（拒绝 null 直存，统一兜底默认值）。 */
    ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 分块 ID 数组反序列化类型常量（Jackson 2）。{@code TypeReference} 不可变、线程安全，
     * 泛型父类解析在构造期完成，静态复用避免检索逐行映射时反复匿名子类构造与泛型反射解析；
     * 接口字段隐式 {@code public static final}，与 {@link #OBJECT_MAPPER} 同风格。
     */
    TypeReference<List<Long>> LONG_LIST_TYPE = new TypeReference<>() {
    };

    /**
     * 字符串数组反序列化类型常量（Jackson 2）。不可变、线程安全，静态复用（口径同
     * {@link #LONG_LIST_TYPE}）。
     */
    TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };

    /**
     * 宽容解析告警日志器（检索读路径脏账本降级专用，接口字段隐式 {@code public static final}）。
     */
    Logger CONVERT_LOGGER = LoggerFactory.getLogger(RagGraphPersistenceConvert.class);

    // ===== 领域模型 → 持久化实体（MapStruct 自动生成 + 显式命名转换） =====

    /**
     * 实体节点转持久化实体（properties 序列化为 JSON 文本）。
     *
     * @param node 实体节点
     * @return 实体节点实体
     */
    @Mapping(target = "properties", qualifiedByName = "entityPropertiesToJson")
    EntityNodeGraphEntity toEntity(EntityNode node);

    /**
     * 关系边转持久化实体（properties 序列化为 JSON 文本）。
     *
     * @param edge 关系边
     * @return 关系边实体
     */
    @Mapping(target = "properties", qualifiedByName = "relationPropertiesToJson")
    RelationEdgeGraphEntity toEntity(RelationEdge edge);

    /**
     * 实体向量转持久化实体（vector 转向量字面量、chunkIds 转 JSON 数组文本）。
     *
     * @param vector 实体向量
     * @return 实体向量实体
     */
    @Mapping(target = "contentVector", source = "vector", qualifiedByName = "floatArrayToLiteral")
    @Mapping(target = "chunkIds", source = "chunkIds", qualifiedByName = "longListToJson")
    EntityInfoVectorEntity toEntity(EntityInfoVector vector);

    /**
     * 关系向量转持久化实体（vector 转向量字面量、chunkIds 转 JSON 数组文本）。
     *
     * @param vector 关系向量
     * @return 关系向量实体
     */
    @Mapping(target = "contentVector", source = "vector", qualifiedByName = "floatArrayToLiteral")
    @Mapping(target = "chunkIds", source = "chunkIds", qualifiedByName = "longListToJson")
    RelationInfoVectorEntity toEntity(RelationInfoVector vector);

    /**
     * LLM 缓存条目转持久化实体（cacheType 枚举由 MapStruct 自动转为枚举名文本，其余全字段同名直落）。
     *
     * @param entry 缓存条目
     * @return 缓存实体
     */
    LlmCacheEntity toEntity(LlmCacheEntry entry);

    // ===== 持久化实体 → 领域模型（手写，经紧凑构造器 / restore 工厂） =====

    /**
     * 实体节点实体还原为领域模型（properties 反序列化并归一空值）。
     *
     * @param entity 实体节点实体，可为 null
     * @return 实体节点；实体为 null 时返回 null
     */
    default EntityNode toEntityNode(EntityNodeGraphEntity entity) {
        if (ObjectUtils.isEmpty(entity)) {
            return null;
        }
        return new EntityNode(entity.getKbId(), entity.getEntityName(),
                normalize(jsonToEntityProperties(entity.getProperties())));
    }

    /**
     * 关系边实体还原为领域模型（properties 反序列化）。
     *
     * @param entity 关系边实体，可为 null
     * @return 关系边；实体为 null 时返回 null
     */
    default RelationEdge toRelationEdge(RelationEdgeGraphEntity entity) {
        if (ObjectUtils.isEmpty(entity)) {
            return null;
        }
        return new RelationEdge(entity.getKbId(), entity.getSourceName(), entity.getTargetName(),
                jsonToRelationProperties(entity.getProperties()));
    }

    /**
     * 实体向量实体还原为领域模型（向量字面量解析为 float[]、chunk_ids 解析为 ID 列表）。
     *
     * @param entity 实体向量实体，可为 null
     * @return 实体向量；实体为 null 时返回 null
     */
    default EntityInfoVector toEntityInfoVector(EntityInfoVectorEntity entity) {
        if (ObjectUtils.isEmpty(entity)) {
            return null;
        }
        return EntityInfoVector.restore(
                entity.getId(), entity.getKbId(), entity.getEntityName(), entity.getContent(),
                jsonToLongList(entity.getChunkIds()), literalToFloatArray(entity.getContentVector()),
                entity.getCreatedAt(), entity.getUpdatedAt());
    }

    /**
     * 关系向量实体还原为领域模型（向量字面量解析为 float[]、chunk_ids 解析为 ID 列表）。
     *
     * @param entity 关系向量实体，可为 null
     * @return 关系向量；实体为 null 时返回 null
     */
    default RelationInfoVector toRelationInfoVector(RelationInfoVectorEntity entity) {
        if (ObjectUtils.isEmpty(entity)) {
            return null;
        }
        return RelationInfoVector.restore(
                entity.getId(), entity.getKbId(), entity.getSourceName(), entity.getTargetName(),
                entity.getContent(), jsonToLongList(entity.getChunkIds()), literalToFloatArray(entity.getContentVector()),
                entity.getCreatedAt(), entity.getUpdatedAt());
    }

    /**
     * LLM 缓存实体还原为领域模型（cache_type 文本解析为枚举后经 restore 工厂恢复）。
     *
     * @param entity 缓存实体，可为 null
     * @return 缓存条目；实体为 null 时返回 null
     */
    default LlmCacheEntry toLlmCacheEntry(LlmCacheEntity entity) {
        if (ObjectUtils.isEmpty(entity)) {
            return null;
        }
        return LlmCacheEntry.restore(
                entity.getId(), entity.getKbId(), entity.getCacheKey(), cacheTypeFromString(entity.getCacheType()),
                entity.getModel(), entity.getPrompt(), entity.getResponse(), entity.getTotalTokens(),
                entity.getCreatedAt(), entity.getUpdatedAt());
    }

    /**
     * 缓存分类文本解析为枚举（llm_cache.cache_type 列 → {@link CacheType}）。
     *
     * @param cacheType 分类文本（枚举名），可为 null/空白
     * @return 缓存分类枚举；空白入参返回 null（由领域模型紧凑构造器兜底校验）
     */
    default CacheType cacheTypeFromString(String cacheType) {
        if (StringUtils.isBlank(cacheType)) {
            return null;
        }
        try {
            return CacheType.valueOf(cacheType.trim());
        } catch (IllegalArgumentException e) {
            throw new DeepDataAgentException("未知的 LLM 缓存分类: " + cacheType);
        }
    }

    // ===== 命名转换：领域字段 → 持久化列值 =====

    /**
     * EntityProperties 序列化为 JSON 文本（entity_node_graph.properties）。
     *
     * @param props 实体属性，可为 null
     * @return JSON 文本；入参为 null 时返回 null
     */
    @Named("entityPropertiesToJson")
    default String entityPropertiesToJson(EntityProperties props) {
        if (props == null) {
            return null;
        }
        return serialize(props);
    }

    /**
     * RelationProperties 序列化为 JSON 文本（relation_edge_graph.properties）。
     *
     * @param props 关系属性，可为 null
     * @return JSON 文本；入参为 null 时返回 null
     */
    @Named("relationPropertiesToJson")
    default String relationPropertiesToJson(RelationProperties props) {
        if (props == null) {
            return null;
        }
        return serialize(props);
    }

    /**
     * float[] 向量转 PG 向量字面量（形如 {@code "[0.1,0.2]"}，由 SQL 侧 {@code ::vector} 落库）。
     *
     * @param vector 向量数组
     * @return 向量字面量；空入参返回 null
     */
    @Named("floatArrayToLiteral")
    default String floatArrayToLiteral(float[] vector) {
        if (ObjectUtils.isEmpty(vector)) {
            return null;
        }
        StringBuilder builder = new StringBuilder(vector.length * 8);
        builder.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(Float.toString(vector[i]));
        }
        return builder.append(']').toString();
    }

    /**
     * 分块 ID 列表序列化为 JSON 数组文本（chunk_ids 列，空列表落库 {@code "[]"}）。
     *
     * @param ids 分块 ID 列表，可为 null
     * @return JSON 数组文本；空/为 null 时返回 {@code "[]"}
     */
    @Named("longListToJson")
    default String longListToJson(List<Long> ids) {
        if (ObjectUtils.isEmpty(ids)) {
            return "[]";
        }
        return serialize(ids);
    }

    // ===== 持久化列值 → 领域字段 =====

    /**
     * 实体属性 JSON 文本反序列化（缺失字段兜底为空集合）。
     *
     * @param json JSON 文本，可为 null/空白
     * @return 实体属性；空白入参返回空属性
     */
    default EntityProperties jsonToEntityProperties(String json) {
        if (StringUtils.isBlank(json)) {
            return EntityProperties.empty();
        }
        try {
            return OBJECT_MAPPER.readValue(json, EntityProperties.class);
        } catch (JsonProcessingException e) {
            throw new DeepDataAgentException("实体属性 JSON 反序列化失败: " + e.getMessage());
        }
    }

    /**
     * 关系属性 JSON 文本反序列化。
     *
     * @param json JSON 文本，可为 null/空白
     * @return 关系属性；空白入参返回空属性
     */
    default RelationProperties jsonToRelationProperties(String json) {
        if (StringUtils.isBlank(json)) {
            return RelationProperties.empty();
        }
        try {
            return OBJECT_MAPPER.readValue(json, RelationProperties.class);
        } catch (JsonProcessingException e) {
            throw new DeepDataAgentException("关系属性 JSON 反序列化失败: " + e.getMessage());
        }
    }

    /**
     * 分块 ID JSON 数组文本反序列化。
     *
     * @param json JSON 数组文本，可为 null/空白
     * @return 分块 ID 列表；空白入参返回空列表
     */
    default List<Long> jsonToLongList(String json) {
        if (StringUtils.isBlank(json)) {
            return List.of();
        }
        try {
            return new ArrayList<>(OBJECT_MAPPER.readValue(json, LONG_LIST_TYPE));
        } catch (JsonProcessingException e) {
            throw new DeepDataAgentException("分块 ID JSON 反序列化失败: " + e.getMessage());
        }
    }

    /**
     * 字符串数组 JSON 文本反序列化（检索侧只读消化 {@code properties.keywords}，
     * 与 {@link #jsonToLongList} 同风格：空白入参归一空列表，不抛空指针）。
     *
     * @param json JSON 数组文本，可为 null/空白（如 JSONB 键缺失时 SQL 出列为 NULL）
     * @return 字符串列表；空白入参返回空列表
     */
    default List<String> jsonToStringList(String json) {
        if (StringUtils.isBlank(json)) {
            return List.of();
        }
        try {
            return new ArrayList<>(OBJECT_MAPPER.readValue(json, STRING_LIST_TYPE));
        } catch (JsonProcessingException e) {
            throw new DeepDataAgentException("关键词 JSON 反序列化失败: " + e.getMessage());
        }
    }

    /**
     * 分块 ID JSON 数组文本<b>宽容</b>反序列化（检索读路径专用）。
     * <p><b>与严格版 {@link #jsonToLongList} 的差异</b>：严格版在解析失败或文本非 JSON 数组时
     * 抛 {@link DeepDataAgentException}（摄入读路径要求账本结构完整，异常须上抛暴露）；本版
     * 遇同样情形仅记 WARN 并返回<b>空列表</b>，绝不外抛。</p>
     * <p><b>检索侧选用理由</b>：GRAPH 通道单条命中的 chunk 账本来自可能含历史脏数据的 JSONB 列，
     * 严格版异常会穿透 {@code DefaultRecallService#recallGraphSafely} 杀掉整个 GRAPH 通道
     * （连带丢弃其余健康命中）。宽容版把爆炸半径收敛到「该命中贡献零块」——单行脏账本只让该命中
     * 少贡献 chunk，不影响其余命中与通道，符合《realign-rag-retrieval-with-lightrag》检索读路径
     * 的容错口径。空白入参（键缺失，SQL 出列 NULL）视为无账本、静默归空列表，不计 WARN。</p>
     *
     * @param json JSON 数组文本，可为 null/空白/脏数据
     * @return 分块 ID 列表；空白入参或解析失败/非数组返回空列表
     */
    default List<Long> jsonToLongListQuietly(String json) {
        if (StringUtils.isBlank(json)) {
            return List.of();
        }
        try {
            return jsonToLongList(json);
        } catch (RuntimeException e) {
            CONVERT_LOGGER.warn("检索侧分块 ID 账本 JSON 解析失败，按空账本处理该命中: json={} err={}",
                    json, e.getMessage());
            return List.of();
        }
    }

    /**
     * 字符串数组 JSON 文本<b>宽容</b>反序列化（检索读路径专用，如 {@code properties.keywords}）。
     * <p>容错语义与选用理由同 {@link #jsonToLongListQuietly}：解析失败/非数组仅记 WARN 返回空列表，
     * 不让单行脏数据穿透到 GRAPH 通道降级；空白入参静默归空列表，不计 WARN。与严格版
     * {@link #jsonToStringList}（异常上抛）互为检索/摄入两套口径，共享底层严格实现零改动。</p>
     *
     * @param json JSON 数组文本，可为 null/空白/脏数据
     * @return 字符串列表；空白入参或解析失败/非数组返回空列表
     */
    default List<String> jsonToStringListQuietly(String json) {
        if (StringUtils.isBlank(json)) {
            return List.of();
        }
        try {
            return jsonToStringList(json);
        } catch (RuntimeException e) {
            CONVERT_LOGGER.warn("检索侧关键词 JSON 解析失败，按空关键词处理该命中: json={} err={}",
                    json, e.getMessage());
            return List.of();
        }
    }

    /**
     * PG 向量字面量解析为 float[]。
     *
     * @param literal 向量字面量（形如 {@code "[0.1,0.2]"}），可为 null/空白
     * @return 向量数组；空白入参返回 null
     */
    default float[] literalToFloatArray(String literal) {
        if (StringUtils.isBlank(literal)) {
            return null;
        }
        String inner = literal.trim();
        if (!inner.startsWith("[") || !inner.endsWith("]")) {
            throw new DeepDataAgentException("向量字面量格式非法(缺少方括号): " + literal);
        }
        inner = inner.substring(1, inner.length() - 1).trim();
        if (StringUtils.isBlank(inner)) {
            return new float[0];
        }
        String[] parts = inner.split(",");
        float[] result = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                result[i] = Float.parseFloat(parts[i].trim());
            } catch (NumberFormatException e) {
                throw new DeepDataAgentException("向量字面量分量解析失败: " + literal);
            }
        }
        return result;
    }

    /**
     * 实体属性空值归一：历史行可能缺失 votes/descriptions 等新字段（以及 filePaths 由单值
     * {@code filePath} 列表化前的旧键形态——存量按既定策略清库重灌，不做猜测性兼容），
     * 反序列化后统一兜底为空集合，保证下游 safe* 语义与序列化一致性。
     *
     * @param props 反序列化的实体属性，可为 null
     * @return 归一后的实体属性；入参为 null 时返回空属性
     */
    default EntityProperties normalize(EntityProperties props) {
        if (ObjectUtils.isEmpty(props)) {
            return EntityProperties.empty();
        }
        return new EntityProperties(
                props.entityType(),
                props.description(),
                ObjectUtils.isEmpty(props.sourceIds()) ? List.of() : props.sourceIds(),
                ObjectUtils.isEmpty(props.filePaths()) ? List.of() : props.filePaths(),
                ObjectUtils.isEmpty(props.entityTypeVotes()) ? Map.of() : props.entityTypeVotes(),
                ObjectUtils.isEmpty(props.descriptions()) ? List.of() : props.descriptions());
    }

    /**
     * 对象序列化为 JSON 文本（统一异常包装为业务异常）。
     *
     * @param value 待序列化对象
     * @return JSON 文本
     */
    default String serialize(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new DeepDataAgentException("持久化 JSON 序列化失败: " + e.getMessage());
        }
    }
}