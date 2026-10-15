package com.linkroa.deepdataagent.datasource.controller.response;

/**
 * 前置操作试跑响应体。
 *
 * @param success     是否成功
 * @param rawResponse 原始响应文本（供配置参数映射前校验）
 * @author DeepDataAgent
 */
public record PreOperationTestResponse(
        boolean success,
        String rawResponse
) {
}