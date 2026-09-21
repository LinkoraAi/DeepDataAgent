package com.linkroa.deepdataagent.rag.domain.model;

import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.Objects;

/**
 * 知识图谱实体节点聚合根。
 * <p>不变量：实体名称在知识库内全局唯一（同名多次抽取归一为同一节点）；DESCRIPTION 空值兜底为
 * {@code Entity {name}}。实体/向量合并、类型频次取首都以本模型为承载。</p>
 *
 * @param kbId       所属知识库ID
 * @param entityName 实体名称（库内唯一，精确匹配键，不做别名/相似度归一）
 * @param properties 实体属性（持久化 properties JSON 列）
 */
public record EntityNode(Long kbId, String entityName, EntityProperties properties) {

    /**
     * 紧凑构造器：不变量校验与属性兜底。
     */
    public EntityNode {
        if (ObjectUtils.isEmpty(kbId)) {
            throw new IllegalArgumentException("实体必须关联知识库");
        }
        if (StringUtils.isBlank(entityName)) {
            throw new IllegalArgumentException("实体名称不能为空");
        }
        properties = ObjectUtils.isEmpty(properties) ? EntityProperties.empty() : properties;
    }

    /**
     * 新建实体节点（抽取片段入口）。
     *
     * @param kbId        所属知识库ID
     * @param entityName  实体名称
     * @param entityType  实体类型（可为 null，交由频次取首）
     * @param description 实体描述（可为 null，写入时兜底 Entity {name}）
     * @param sourceId    来源分块ID（弱引用 chunk.id）
     * @param filePath    来源文件路径（可为 null）
     * @return 初始实体节点
     */
    public static EntityNode create(Long kbId, String entityName, String entityType,
                                    String description, Long sourceId, String filePath) {
        return new EntityNode(kbId, entityName,
                EntityProperties.of(entityType, description, sourceId, filePath));
    }

    /**
     * 遍历合并同名实体（同名全局归一）：代理到 {@link EntityProperties#merge}。
     * 仅同知识库且同名允许合并。
     *
     * @param other 待合并的同名实体（null 视为无）
     * @return 合并后的新实体节点
     * @throws IllegalArgumentException 非同库或不同名时抛出
     */
    public EntityNode merge(EntityNode other) {
        if (ObjectUtils.isEmpty(other)) {
            return this;
        }
        if (!Objects.equals(kbId, other.kbId) || !StringUtils.equals(entityName, other.entityName)) {
            throw new IllegalArgumentException("仅同知识库同名实体可合并");
        }
        return new EntityNode(kbId, entityName, properties.merge(other.properties));
    }

    /**
     * 有效描述：空描述兜底为 {@code Entity {name}}（写图与向量前的最终落值）。
     *
     * @return 非空描述
     */
    public String effectiveDescription() {
        return StringUtils.defaultIfBlank(properties.description(), "Entity " + entityName);
    }
}