package com.linkroa.deepdataagent.datasource.controller.response;

/**
 * API字段响应
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
