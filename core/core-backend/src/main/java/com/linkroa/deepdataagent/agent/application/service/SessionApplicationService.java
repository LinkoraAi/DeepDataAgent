package com.linkroa.deepdataagent.agent.application.service;

import com.linkroa.deepdataagent.agent.acl.datasource.DatasourceGateway;
import com.linkroa.deepdataagent.agent.controller.response.MessageResponse;
import com.linkroa.deepdataagent.agent.controller.response.SessionListItem;
import com.linkroa.deepdataagent.agent.controller.response.SessionResponse;
import com.linkroa.deepdataagent.agent.domain.repository.SessionRepository;
import com.linkroa.deepdataagent.agent.infrastructure.config.SessionProperties;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.AgentSessionEntity;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.ConversationMsgEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * 会话应用服务
 * <p>负责会话生命周期的编排：创建、查询、关闭及消息获取。</p>
 */
@Service
public class SessionApplicationService {

    private static final Logger log = LoggerFactory.getLogger(SessionApplicationService.class);
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String DEFAULT_TITLE = "新对话";

    private final SessionRepository sessionRepository;
    private final SessionProperties sessionProperties;
    private final DatasourceGateway datasourceGateway;
    private final ModelConfigApplicationService modelConfigApplicationService;
    private final AgentSessionManager agentSessionManager;

    public SessionApplicationService(SessionRepository sessionRepository,
                                     SessionProperties sessionProperties,
                                     DatasourceGateway datasourceGateway,
                                     ModelConfigApplicationService modelConfigApplicationService,
                                     AgentSessionManager agentSessionManager) {
        this.sessionRepository = sessionRepository;
        this.sessionProperties = sessionProperties;
        this.datasourceGateway = datasourceGateway;
        this.modelConfigApplicationService = modelConfigApplicationService;
        this.agentSessionManager = agentSessionManager;
    }

    /**
     * 创建新会话
     *
     * @param datasourceId   数据源 ID
     * @param modelConfigId  模型配置 ID
     * @return 会话响应
     */
    public SessionResponse createSession(Long datasourceId, Long modelConfigId) {
        // 校验数据源是否存在
        datasourceGateway.findDatasource(datasourceId)
                .orElseThrow(() -> new IllegalArgumentException("数据源不存在: " + datasourceId));

        // 校验模型配置是否存在
        if (modelConfigApplicationService.getConfigById(modelConfigId) == null) {
            throw new IllegalArgumentException("模型配置不存在: " + modelConfigId);
        }

        // 检查活跃会话数是否达到上限
        int activeCount = sessionRepository.countActiveSessions();
        if (activeCount >= sessionProperties.getMaxActiveSessions()) {
            throw new IllegalStateException("活跃会话数已达上限: " + sessionProperties.getMaxActiveSessions());
        }

        // 生成会话 ID 并创建实体
        String sessionId = "session-" + UUID.randomUUID().toString();
        String now = LocalDateTime.now().format(FORMATTER);

        AgentSessionEntity entity = AgentSessionEntity.builder()
                .id(sessionId)
                .title(DEFAULT_TITLE)
                .datasourceId(datasourceId)
                .modelConfigId(modelConfigId)
                .status("active")
                .messageCount(0)
                .createdAt(now)
                .updatedAt(now)
                .isDeleted(0)
                .build();

        sessionRepository.save(entity);
        log.info("SessionApplicationService: created session={}, datasourceId={}, modelConfigId={}",
                sessionId, datasourceId, modelConfigId);

        return toSessionResponse(entity);
    }

    /**
     * 获取所有活跃会话列表
     *
     * @return 会话列表项
     */
    public List<SessionListItem> listSessions() {
        return sessionRepository.findActiveSessions().stream()
                .map(this::toSessionListItem)
                .toList();
    }

    /**
     * 获取单个会话详情
     *
     * @param sessionId 会话 ID
     * @return 会话响应
     */
    public SessionResponse getSession(String sessionId) {
        AgentSessionEntity entity = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在: " + sessionId));
        return toSessionResponse(entity);
    }

    /**
     * 关闭会话
     *
     * @param sessionId 会话 ID
     */
    public void closeSession(String sessionId) {
        AgentSessionEntity entity = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在: " + sessionId));

        if ("closed".equals(entity.getStatus())) {
            throw new IllegalStateException("会话已关闭");
        }

        sessionRepository.closeSession(sessionId);
        agentSessionManager.evictSession(sessionId);
        log.info("SessionApplicationService: closed session={}", sessionId);
    }

    /**
     * 获取会话消息列表
     *
     * @param sessionId 会话 ID
     * @param limit     每页数量
     * @param offset    偏移量
     * @return 消息响应列表
     */
    public List<MessageResponse> getMessages(String sessionId, int limit, int offset) {
        // 校验会话是否存在
        sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在: " + sessionId));

        List<ConversationMsgEntity> messages = sessionRepository.findMessagesBySessionId(sessionId, limit, offset);
        return messages.stream()
                .map(this::toMessageResponse)
                .toList();
    }

    // ==================== 转换方法 ====================

    private SessionResponse toSessionResponse(AgentSessionEntity entity) {
        return new SessionResponse(
                entity.getId(),
                entity.getTitle(),
                entity.getDatasourceId(),
                entity.getModelConfigId(),
                entity.getStatus(),
                entity.getMessageCount(),
                entity.getLastMessageAt(),
                entity.getCreatedAt(),
                entity.getClosedAt()
        );
    }

    private SessionListItem toSessionListItem(AgentSessionEntity entity) {
        return new SessionListItem(
                entity.getId(),
                entity.getTitle(),
                entity.getDatasourceId(),
                entity.getModelConfigId(),
                entity.getStatus(),
                entity.getMessageCount(),
                entity.getLastMessageAt(),
                entity.getCreatedAt()
        );
    }

    private MessageResponse toMessageResponse(ConversationMsgEntity entity) {
        return new MessageResponse(
                entity.getId(),
                entity.getSessionId(),
                entity.getRole(),
                entity.getContent(),
                entity.getToolCalls(),
                entity.getToolResult(),
                entity.getMetadata(),
                entity.getCreatedAt()
        );
    }
}
