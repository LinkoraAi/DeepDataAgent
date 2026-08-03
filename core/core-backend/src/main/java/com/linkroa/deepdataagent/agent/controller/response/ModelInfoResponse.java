package com.linkroa.deepdataagent.agent.controller.response;

/**
 * 模型信息响应 DTO
 *
 * @param id          模型 ID
 * @param modelKey    模型标识（如 "qwen-plus"）
 * @param displayName 模型显示名称
 */
public record ModelInfoResponse(
    Long id,
    String modelKey,
    String displayName
) {
}