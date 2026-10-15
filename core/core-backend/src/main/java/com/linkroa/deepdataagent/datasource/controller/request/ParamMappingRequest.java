package com.linkroa.deepdataagent.datasource.controller.request;

/**
 * 前置操作参数映射请求体。
 *
 * @param paramName     参数名
 * @param paramLocation 注入位置（仅 {@code header} / {@code query} / {@code body}）
 * @param jsonPath      从响应抽取值的 JsonPath
 * @author DeepDataAgent
 */
public record ParamMappingRequest(
        String paramName,
        String paramLocation,
        String jsonPath
) {
}