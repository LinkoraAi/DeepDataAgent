package com.linkroa.deepdataagent.agent.application.dto;

/**
 * 模型服务商 DTO
 * <p>应用层返回的服务商信息，由控制器层转换为 {@link com.linkroa.deepdataagent.agent.controller.response.ModelProviderResponse}。</p>
 *
 * @param id                   服务商 ID
 * @param providerDisplayName  服务商显示名称
 * @param providerName         服务商标识（provider_key）
 * @param apiUrl               默认 API 地址
 */
public record ModelProviderDTO(
        Long id,
        String providerDisplayName,
        String providerName,
        String apiUrl
) {
}