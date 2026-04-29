package com.linkroa.deepdataagent.datasource.controller.response;

import java.util.List;

/**
 * 解析字段响应对象
 * <p>包含API响应解析后提取的单个字段信息，支持树形结构展示。</p>
 *
 * @author system
 * @since 2026-05-12
 */
public record ParsedFieldResponse(
    String originalName,
    String jsonPath,
    String fieldType,
    List<ParsedFieldResponse> children
) {
}
