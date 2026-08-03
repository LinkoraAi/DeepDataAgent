package com.linkroa.deepdataagent.agent.application.dto;

/**
 * 会话 DTO
 * <p>应用层返回的会话对象，由控制器层转换为 {@link com.linkroa.deepdataagent.agent.controller.response.SessionResponse}。</p>
 *
 * @param id            会话 ID
 * @param title         会话标题
 * @param datasourceId  数据源 ID
 * @param modelConfigId 模型配置 ID
 * @param status        会话状态
 * @param lastMessageAt 最后消息时间（已格式化）
 * @param createdAt     创建时间（已格式化）
 */
public record SessionDTO(
        String id,
        String title,
        Long datasourceId,
        Long modelConfigId,
        String status,
        String lastMessageAt,
        String createdAt
) {
}