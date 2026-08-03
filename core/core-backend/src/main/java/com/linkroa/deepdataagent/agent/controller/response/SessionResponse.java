package com.linkroa.deepdataagent.agent.controller.response;

/**
 * 会话响应 DTO
 * <p>与前端 {@code Session} 类型对齐，字段名和类型一一对应。</p>
 *
 * @param id             会话 ID
 * @param title          会话标题
 * @param datasourceId   数据源 ID
 * @param modelConfigId  模型配置 ID
 * @param status         会话状态
 * @param lastMessageAt  最后消息时间
 * @param createdAt      创建时间
 */
public record SessionResponse(
    String id,
    String title,
    Long datasourceId,
    Long modelConfigId,
    String status,
    String lastMessageAt,
    String createdAt
) {}