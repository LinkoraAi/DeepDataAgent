package com.linkroa.deepdataagent.datasource.controller.response;

import java.util.List;

/**
 * API 响应解析的字段树节点响应体（递归结构）。
 *
 * @param originalName 原始字段名
 * @param jsonPath     字段 JsonPath
 * @param fieldType    推断类型（{@code string} / {@code number} / {@code boolean} / {@code object} / {@code array}）
 * @param children     子字段列表（叶子节点为空列表）
 * @author DeepDataAgent
 */
public record ParsedFieldResponse(
        String originalName,
        String jsonPath,
        String fieldType,
        List<ParsedFieldResponse> children
) {
}