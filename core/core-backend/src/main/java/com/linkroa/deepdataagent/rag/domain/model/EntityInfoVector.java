package com.linkroa.deepdataagent.rag.domain.model;

import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 实体向量聚合根（对应 {@code entity_info_vector} 表，与 entity_node_graph 1:1）。
 * <p>{@link #merge(EntityInfoVector)} 累积 chunkIds（JSONB 数组随 merge 去重累积）并做
 * Map-Reduce 摘要定位（content 取篇幅更长者，保证摘要以信息量最大的描述为准）。
 * content 为向量化内容 {@code name\n description}，超 embedding_token_limit 由下游精确截断。
 * 注意：vector 为 float[] 引用相等，equals/hashCode 不依赖向量值（合并判重用业务字段比较）。</p>
 *
 * @param id         主键
 * @param kbId       所属知识库ID
 * @param entityName 实体名称（库内唯一）
 * @param content    向量化内容（实体名 + 描述）
 * @param chunkIds   关联分块ID列表（弱引用 chunk.id，merge 时累积）
 * @param vector     实体向量（pgvector）
 * @param createdAt  创建时间
 * @param updatedAt  更新时间
 */
public record EntityInfoVector(
        Long id,
        Long kbId,
        String entityName,
        String content,
        List<Long> chunkIds,
        float[] vector,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    /**
     * 紧凑构造器：不变量校验与兜底。
     */
    public EntityInfoVector {
        if (ObjectUtils.isEmpty(kbId)) {
            throw new IllegalArgumentException("实体向量必须关联知识库");
        }
        if (StringUtils.isBlank(entityName)) {
            throw new IllegalArgumentException("实体名称不能为空");
        }
        chunkIds = ObjectUtils.isEmpty(chunkIds) ? List.of() : chunkIds;
    }

    /**
     * 新建实体向量（摄入写入入口）。
     *
     * @param kbId       所属知识库ID
     * @param entityName 实体名称
     * @param content    向量化内容
     * @param chunkIds   关联分块ID列表（可为空）
     * @param vector     实体向量（可为 null，由 embedding 服务填充）
     * @return 初始实体向量
     */
    public static EntityInfoVector create(Long kbId, String entityName, String content,
                                          List<Long> chunkIds, float[] vector) {
        OffsetDateTime now = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
        return new EntityInfoVector(null, kbId, entityName, content, chunkIds, vector, now, now);
    }

    /**
     * 从数据库恢复实体向量。
     */
    public static EntityInfoVector restore(Long id, Long kbId, String entityName, String content,
                                           List<Long> chunkIds, float[] vector,
                                           OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        return new EntityInfoVector(id, kbId, entityName, content, chunkIds, vector, createdAt, updatedAt);
    }

    /**
     * 合并：chunkIds 去重并集 + content 择优（Map-Reduce 摘要定位：保留篇幅更长者）。
     * 仅同库同名允许合并。
     *
     * @param other 待合并的向量（null 视为无）
     * @return 合并后的新实体向量
     * @throws IllegalArgumentException 非同库或不同名时抛出
     */
    public EntityInfoVector merge(EntityInfoVector other) {
        if (ObjectUtils.isEmpty(other)) {
            return this;
        }
        if (!Objects.equals(kbId, other.kbId) || !StringUtils.equals(entityName, other.entityName)) {
            throw new IllegalArgumentException("仅同知识库同名实体向量可合并");
        }
        Set<Long> merged = new LinkedHashSet<>(chunkIds);
        merged.addAll(other.chunkIds());
        int selfLen = content == null ? 0 : content.length();
        int otherLen = other.content() == null ? 0 : other.content().length();
        String chosen = selfLen >= otherLen ? content : other.content();
        float[] chosenVector = ObjectUtils.isEmpty(vector) ? other.vector() : vector;
        return new EntityInfoVector(id, kbId, entityName, chosen, List.copyOf(merged),
                chosenVector, createdAt, updatedAt);
    }

    /**
     * 构造实体向量化内容：{@code entityName\n description}。
     *
     * @param entityName  实体名称
     * @param description 实体描述（可为空串）
     * @return 向量化内容
     */
    public static String buildContent(String entityName, String description) {
        return entityName + "\n" + StringUtils.defaultString(description);
    }
}