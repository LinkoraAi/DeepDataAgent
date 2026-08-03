package com.linkroa.deepdataagent.agent.controller.response;

import com.linkroa.deepdataagent.agent.domain.model.AgentSession;

import java.time.format.DateTimeFormatter;

/**
 * SessionResponse 组装器
 * <p>将领域模型 {@link AgentSession} 转换为 Controller 层响应 DTO，字段名与前端对齐。</p>
 */
public final class SessionResponseAssembler {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private SessionResponseAssembler() {
    }

    /**
     * 将 AgentSession 领域模型转换为 SessionResponse
     *
     * @param session 会话聚合根
     * @return 会话响应 DTO
     */
    public static SessionResponse toResponse(AgentSession session) {
        return new SessionResponse(
                session.getId(),
                session.getTitle(),
                session.getDatasourceId(),
                session.getModelConfigId(),
                session.getStatus().name(),
                session.getLastMessageTime() != null ? session.getLastMessageTime().format(FORMATTER) : null,
                session.getCreatedTime() != null ? session.getCreatedTime().format(FORMATTER) : null
        );
    }
}