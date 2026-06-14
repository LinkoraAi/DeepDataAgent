package com.linkroa.deepdataagent.agent.controller.response;

/**
 * 会话消息响应 DTO
 */
public record MessageResponse(
    Long id,
    String sessionId,
    String role,
    String content,
    String toolCalls,
    String toolResult,
    String metadata,
    String createdAt
) {}
