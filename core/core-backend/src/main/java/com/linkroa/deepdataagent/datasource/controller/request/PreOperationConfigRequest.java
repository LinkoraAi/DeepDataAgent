package com.linkroa.deepdataagent.datasource.controller.request;

import jakarta.validation.Valid;

import java.util.List;
import java.util.Map;

/**
 * 前置操作配置请求体（在主请求之前执行以换取 token 等）。
 *
 * @param enabled       是否启用（启用时 url 与 method 必填）
 * @param url           请求地址
 * @param method        请求方法（{@code GET} / {@code POST}）
 * @param headers       请求头
 * @param params        查询参数
 * @param body          请求体内容
 * @param bodyType      请求体类型
 * @param paramMappings 参数映射列表（从响应抽取参数注入主请求上下文）
 * @author DeepDataAgent
 */
public record PreOperationConfigRequest(
        boolean enabled,
        String url,
        String method,
        Map<String, String> headers,
        Map<String, String> params,
        String body,
        String bodyType,

        List<@Valid ParamMappingRequest> paramMappings
) {
}