package com.linkroa.deepdataagent.datasource.controller.response;

import java.util.List;
import java.util.Map;

/**
 * 解析API响应结果对象
 * <p>包含树形字段结构和扁平化数据行。</p>
 *
 * @author system
 * @since 2026-05-12
 */
public record ParseApiResponseResult(
    List<ParsedFieldResponse> fieldTree,
    List<Map<String, Object>> rows
) {
}
