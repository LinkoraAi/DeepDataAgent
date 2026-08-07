package com.linkroa.deepdataagent.agent.application.assembler;

import com.linkroa.deepdataagent.agent.application.dto.SessionListItemDTO;
import com.linkroa.deepdataagent.agent.domain.model.AgentSession;

import java.time.format.DateTimeFormatter;

/**
 * SessionListItemDTO 组装器
 * <p>将领域模型 {@link AgentSession} 转换为应用层 DTO {@link SessionListItemDTO}，
 * 由控制器层进一步转换为 {@link com.linkroa.deepdataagent.agent.controller.response.SessionListItem}。</p>
 */
public final class SessionListItemDTOAssembler {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private SessionListItemDTOAssembler() {
    }

    /**
     * 将 AgentSession 领域模型转换为 SessionListItemDTO
     *
     * @param session 会话聚合根
     * @return 会话列表项 DTO（running 默认 false）
     */
    public static SessionListItemDTO toDTO(AgentSession session) {
        return toDTO(session, false);
    }

    /**
     * 将 AgentSession 领域模型转换为 SessionListItemDTO，并指定其运行状态
     *
     * @param session 会话聚合根
     * @param running 会话是否正在分析中
     * @return 会话列表项 DTO
     */
    public static SessionListItemDTO toDTO(AgentSession session, boolean running) {
        return new SessionListItemDTO(
                session.getId(),
                session.getTitle(),
                session.getDatasourceId(),
                session.getModelConfigId(),
                session.getStatus().name(),
                session.getLastMessageTime() != null ? session.getLastMessageTime().format(FORMATTER) : null,
                session.getCreatedTime() != null ? session.getCreatedTime().format(FORMATTER) : null,
                running
        );
    }
}