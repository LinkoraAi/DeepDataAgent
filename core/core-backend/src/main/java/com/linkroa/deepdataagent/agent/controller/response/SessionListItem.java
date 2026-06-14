package com.linkroa.deepdataagent.agent.controller.response;

/**
 * 会话列表项 DTO
 */
public record SessionListItem(
    String id,
    String title,
    Long datasourceId,
    Long modelConfigId,
    String status,
    Integer messageCount,
    String lastMessageAt,
    String createdAt
) {
    public static SessionListItem from(SessionResponse response) {
        return new SessionListItem(
                response.id(),
                response.title(),
                response.datasourceId(),
                response.modelConfigId(),
                response.status(),
                response.messageCount(),
                response.lastMessageAt(),
                response.createdAt()
        );
    }
}
