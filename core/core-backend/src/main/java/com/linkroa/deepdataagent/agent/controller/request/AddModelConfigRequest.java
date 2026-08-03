package com.linkroa.deepdataagent.agent.controller.request;

import jakarta.validation.constraints.NotBlank;

/**
 * 添加模型配置请求
 * <p>支持两种模式：</p>
 * <ul>
 *   <li>预设模式：providerKey 对应已启用的服务商，modelKey 对应预设模型或自定义模型，
 *       baseUrl 可省略（自动取服务商默认地址）</li>
 *   <li>自定义模式：providerKey 为 "custom"，必须提供 baseUrl 和 modelKey</li>
 * </ul>
 */
public record AddModelConfigRequest(
    /** 服务商标识（预设模式填服务商 key，自定义模式填 "custom"） */
    @NotBlank(message = "服务商标识不能为空")
    String providerKey,

    /** 模型标识 */
    @NotBlank(message = "模型标识不能为空")
    String modelKey,

    /** API 基础地址（自定义模式必填，预设模式可选） */
    String baseUrl,

    /** API 格式（openai / anthropic，默认 openai） */
    String apiFormat,

    /** API Key（必填） */
    @NotBlank(message = "API Key 不能为空")
    String apiKey,

    /** 是否设为默认模型 */
    Boolean setDefault
) {}
