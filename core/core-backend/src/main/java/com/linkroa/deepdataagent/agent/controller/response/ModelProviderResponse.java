package com.linkroa.deepdataagent.agent.controller.response;

/**
 * 模型服务商响应 DTO
 *
 * @param id          服务商 ID
 * @param name        服务商显示名称
 * @param providerKey 服务商标识
 * @param baseUrl     API 基础地址
 */
public record ModelProviderResponse(
    Long id,
    String name,
    String providerKey,
    String baseUrl
) {
}