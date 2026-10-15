package com.linkroa.deepdataagent.datasource.controller.response;

import java.util.List;
import java.util.Map;

/**
 * API 响应试解析结果响应体（字段树 + 扁平化样例行）。
 *
 * @param fieldTree 递归字段树
 * @param rows      样例行（最多 10 行）
 * @author DeepDataAgent
 */
public record ParseApiResponseResponse(
        List<ParsedFieldResponse> fieldTree,
        List<Map<String, Object>> rows
) {
}