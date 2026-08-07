package com.linkroa.deepdataagent.agent.controller.response;

import com.linkroa.deepdataagent.agent.domain.model.AgentSession;

import java.time.format.DateTimeFormatter;

/**
 * SessionListItem 组装器
 * <p>将领域模型 {@link AgentSession} 转换为列表项 DTO，字段名与前端对齐。</p>
 */
public final class SessionListItemAssembler {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private SessionListItemAssembler() {
    }

    /**
     * 将 AgentSession 领域模型转换为 SessionListItem
     *
     * @param session 会话聚合根
     * @return 会话列表项 DTO
     */
    public static SessionListItem toListItem(AgentSession session) {
        return new SessionListItem(
                session.getId(),
                session.getTitle(),
                session.getDatasourceId(),
                session.getModelConfigId(),
                session.getStatus().name(),
                session.getLastMessageTime() != null ? session.getLastMessageTime().format(FORMATTER) : null,
                session.getCreatedTime() != null ? session.getCreatedTime().format(FORMATTER) : null,
                false
        );
    }
}