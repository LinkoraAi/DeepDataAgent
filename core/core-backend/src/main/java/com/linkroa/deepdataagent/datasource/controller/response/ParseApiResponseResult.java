package com.linkroa.deepdataagent.datasource.controller.response;

import java.util.List;
import java.util.Map;

/**
 * 解析API响应结果对象
 * <p>包含解析后的字段列表和预览数据。</p>
 *
 * @author system
 * @since 2026-05-12
 */
public record ParseApiResponseResult(
    List<ParsedFieldResponse> fields,
    List<Map<String, Object>> previewData
) {
}