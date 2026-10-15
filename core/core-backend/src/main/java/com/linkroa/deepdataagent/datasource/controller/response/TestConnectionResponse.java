package com.linkroa.deepdataagent.datasource.controller.response;

/**
 * 连接测试响应体（测试不产生任何持久化副作用）。
 *
 * @param success 是否连接成功
 * @param message 结果说明（失败时为失败原因）
 * @author DeepDataAgent
 */
public record TestConnectionResponse(
        boolean success,
        String message
) {
}