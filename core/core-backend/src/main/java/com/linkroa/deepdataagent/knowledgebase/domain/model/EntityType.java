package com.linkroa.deepdataagent.knowledgebase.domain.model;

/**
 * 实体类型值对象。
 *
 * @param entityType 实体类型名称
 */
public record EntityType(
        String entityType
) {

    public EntityType {
        if (entityType == null || entityType.isBlank()) {
            throw new IllegalArgumentException("实体类型名称不能为空");
        }
    }
}
