package com.linkroa.deepdataagent.agent.application.command;

/**
 * 添加模型配置命令
 * <p>应用层命令对象，由控制器层从 {@link com.linkroa.deepdataagent.agent.controller.request.AddModelConfigRequest} 转换而来，
 * 替代请求对象直接进入应用层，避免应用层反向依赖控制器层。</p>
 *
 * @param providerKey 服务商标识（预设模式填服务商 key，自定义模式填 "custom"）
 * @param modelKey    模型标识
 * @param baseUrl     API 基础地址（自定义模式必填，预设模式可选）
 * @param apiFormat   API 格式（openai / anthropic，默认 openai）
 * @param apiKey      API Key
 * @param setDefault  是否设为默认模型
 */
public record AddModelConfigCommand(
        String providerKey,
        String modelKey,
        String baseUrl,
        String apiFormat,
        String apiKey,
        Boolean setDefault
) {
}