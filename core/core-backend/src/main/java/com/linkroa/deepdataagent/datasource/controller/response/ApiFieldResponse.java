package com.linkroa.deepdataagent.datasource.controller.response;

/**
 * API 字段映射响应体。
 *
 * @param id           字段 id
 * @param apiSchemaId  所属 API 表 id
 * @param originalName 原始字段名
 * @param displayName  展示名
 * @param jsonPath     字段 JsonPath
 * @param fieldType    字段类型
 * @param description  字段描述
 * @author DeepDataAgent
 */
public record ApiFieldResponse(
        Long id,
        Long apiSchemaId,
        String originalName,
        String displayName,
        String jsonPath,
        String fieldType,
        String description
) {
}