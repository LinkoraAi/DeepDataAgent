package com.linkroa.deepdataagent.agent.controller.response;

/**
 * 会话响应 DTO
 */
public record SessionResponse(
    String id,
    String title,
    Long datasourceId,
    Long modelConfigId,
    String status,
    Integer messageCount,
    String lastMessageAt,
    String createdAt,
    String closedAt
) {}
