package com.linkroa.deepdataagent.agent.application.dto;

/**
 * 模型信息 DTO
 * <p>应用层返回的模型信息，由控制器层转换为 {@link com.linkroa.deepdataagent.agent.controller.response.ModelInfoResponse}。</p>
 *
 * @param id          模型 ID
 * @param modelKey    模型标识（如 "qwen-plus"）
 * @param displayName 模型显示名称（如 "阿里百炼 - qwen-plus"）
 */
public record ModelInfoDTO(
        Long id,
        String modelKey,
        String displayName
) {
}