package com.linkroa.deepdataagent.agent.application.assembler;

import com.linkroa.deepdataagent.agent.application.dto.SessionDTO;
import com.linkroa.deepdataagent.agent.domain.model.AgentSession;

import java.time.format.DateTimeFormatter;

/**
 * SessionDTO 组装器
 * <p>将领域模型 {@link AgentSession} 转换为应用层 DTO {@link SessionDTO}，
 * 由控制器层进一步转换为 {@link com.linkroa.deepdataagent.agent.controller.response.SessionResponse}。</p>
 */
public final class SessionDTOAssembler {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private SessionDTOAssembler() {
    }

    /**
     * 将 AgentSession 领域模型转换为 SessionDTO
     *
     * @param session 会话聚合根
     * @return 会话 DTO
     */
    public static SessionDTO toDTO(AgentSession session) {
        return new SessionDTO(
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