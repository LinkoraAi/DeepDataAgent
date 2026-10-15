package com.linkroa.deepdataagent.datasource.controller.response;

import java.util.List;

/**
 * API 数据表详情响应体（表配置 + 字段映射组合）。
 *
 * @param schema 表配置（已脱敏）
 * @param fields 字段映射列表
 * @author DeepDataAgent
 */
public record ApiSchemaDetailResponse(
        ApiSchemaResponse schema,
        List<ApiFieldResponse> fields
) {
}