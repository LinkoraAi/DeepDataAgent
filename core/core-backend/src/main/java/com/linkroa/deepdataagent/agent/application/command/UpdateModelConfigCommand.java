package com.linkroa.deepdataagent.agent.application.command;

/**
 * 更新模型配置命令
 * <p>应用层命令对象，由控制器层从 {@link com.linkroa.deepdataagent.agent.controller.request.UpdateModelConfigRequest} 转换而来，
 * 替代请求对象直接进入应用层，避免应用层反向依赖控制器层。</p>
 *
 * @param apiKey  API Key（加密存储，留空表示不修改）
 * @param baseUrl API 基础地址（留空表示不修改）
 */
public record UpdateModelConfigCommand(
        String apiKey,
        String baseUrl
) {
}