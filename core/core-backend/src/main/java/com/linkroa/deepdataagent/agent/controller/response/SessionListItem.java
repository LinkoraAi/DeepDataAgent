package com.linkroa.deepdataagent.agent.controller.response;

/**
 * 会话列表项 DTO
 * <p>与前端 {@code SessionListItem} 类型对齐。</p>
 *
 * @param id             会话 ID
 * @param title          会话标题
 * @param datasourceId   数据源 ID
 * @param modelConfigId  模型配置 ID
 * @param status         会话状态
 * @param lastMessageAt  最后消息时间
 * @param createdAt      创建时间
 * @param running        会话是否正在分析中
 */
public record SessionListItem(
    String id,
    String title,
    Long datasourceId,
    Long modelConfigId,
    String status,
    String lastMessageAt,
    String createdAt,
    Boolean running
) {}