package com.linkroa.deepdataagent.datasource.controller.request;

/**
 * API 字段映射请求体。
 *
 * @param originalName 原始字段名
 * @param displayName  展示名
 * @param jsonPath     字段 JsonPath
 * @param fieldType    字段类型（{@code string} / {@code number} / {@code boolean} / {@code object} / {@code array}）
 * @param description  字段描述
 * @author DeepDataAgent
 */
public record ApiFieldRequest(
        String originalName,
        String displayName,
        String jsonPath,
        String fieldType,
        String description
) {
}