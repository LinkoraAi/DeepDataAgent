package com.linkroa.deepdataagent.agent.application.service;

import com.linkroa.deepdataagent.agent.acl.datasource.DatasourceGateway;
import com.linkroa.deepdataagent.agent.application.assembler.MessageDTOAssembler;
import com.linkroa.deepdataagent.agent.application.assembler.SessionDTOAssembler;
import com.linkroa.deepdataagent.agent.application.assembler.SessionListItemDTOAssembler;
import com.linkroa.deepdataagent.agent.application.context.RunningAnalysisRegistry;
import com.linkroa.deepdataagent.agent.application.dto.MessageDTO;
import com.linkroa.deepdataagent.agent.application.dto.SessionDTO;
import com.linkroa.deepdataagent.agent.application.dto.SessionListItemDTO;
import com.linkroa.deepdataagent.agent.domain.model.AgentSession;
import com.linkroa.deepdataagent.agent.domain.model.Dialogue;
import com.linkroa.deepdataagent.agent.domain.model.DialogueMessage;
import com.linkroa.deepdataagent.agent.domain.repository.AgentSessionRepository;
import com.linkroa.deepdataagent.agent.domain.repository.DialogueRepository;
import com.linkroa.deepdataagent.agent.domain.repository.ModelConfigRepository;
import com.linkroa.deepdataagent.agent.domain.valueobject.MessageRole;
import com.linkroa.deepdataagent.agent.domain.valueobject.SessionStatus;
import com.linkroa.deepdataagent.agent.infrastructure.config.SessionProperties;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 会话应用服务
 * <p>负责会话生命周期的编排：创建、查询、关闭。
 * 返回应用层 DTO，由控制器层转换为响应对象。</p>
 */
@Service
public class SessionApplicationService {

    private static final Logger log = LoggerFactory.getLogger(SessionApplicationService.class);
    private static final String DEFAULT_TITLE = "新对话";
    /** 消息分页默认轮次数 */
    private static final int DEFAULT_ROUND_LIMIT = 5;

    private final AgentSessionRepository sessionRepository;
    private final ModelConfigRepository modelInfoRepository;
    private final DialogueRepository dialogueRepository;
    private final SessionProperties sessionProperties;
    private final DatasourceGateway datasourceGateway;
    private final RunningAnalysisRegistry runningAnalysisRegistry;

    public SessionApplicationService(AgentSessionRepository sessionRepository,
                                     ModelConfigRepository modelInfoRepository,
                                     DialogueRepository dialogueRepository,
                                     SessionProperties sessionProperties,
                                     DatasourceGateway datasourceGateway,
                                     RunningAnalysisRegistry runningAnalysisRegistry) {
        this.sessionRepository = sessionRepository;
        this.modelInfoRepository = modelInfoRepository;
        this.dialogueRepository = dialogueRepository;
        this.sessionProperties = sessionProperties;
        this.datasourceGateway = datasourceGateway;
        this.runningAnalysisRegistry = runningAnalysisRegistry;
    }

    /**
     * 创建新会话
     *
     * @param userId         用户 ID
     * @param datasourceId   数据源 ID
     * @param modelConfigId  模型配置 ID
     * @param text           用户问题（用于生成即时标题）
     * @return 会话 DTO
     */
    public SessionDTO createSession(Long userId, Long datasourceId, Long modelConfigId, String text) {
        // 校验数据源是否存在
        datasourceGateway.findDatasource(datasourceId)
                .orElseThrow(() -> new IllegalArgumentException("数据源不存在: " + datasourceId));

        // 校验模型配置是否存在且可用
        modelInfoRepository.findById(modelConfigId)
                .orElseThrow(() -> new IllegalArgumentException("模型配置不存在: " + modelConfigId));

        // 检查活跃会话数是否达到上限
        int activeCount = sessionRepository.countActiveSessions();
        if (activeCount >= sessionProperties.getMaxActiveSessions()) {
            throw new IllegalStateException("活跃会话数已达上限: " + sessionProperties.getMaxActiveSessions());
        }

        String sessionId = "session-" + UUID.randomUUID().toString();
        String title = generateInstantTitle(text);

        AgentSession session = new AgentSession(sessionId, title, userId, datasourceId,
                modelConfigId, SessionStatus.ACTIVE);

        sessionRepository.save(session);
        log.info("SessionApplicationService: created session={}, datasourceId={}, modelConfigId={}",
                sessionId, datasourceId, modelConfigId);

        return SessionDTOAssembler.toDTO(session);
    }

    /**
     * 获取单个会话详情
     *
     * @param sessionId 会话 ID
     * @return 会话 DTO
     */
    public SessionDTO getSession(String sessionId) {
        AgentSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在: " + sessionId));
        return SessionDTOAssembler.toDTO(session);
    }

    /**
     * 获取会话列表
     *
     * @param limit  每页数量
     * @param offset 偏移量
     * @return 会话列表项 DTO 列表
     */
    public List<SessionListItemDTO> listSessions(Integer limit, Integer offset) {
        List<AgentSession> sessions;
        if (limit != null && offset != null) {
            sessions = sessionRepository.findActiveSessionsPaged(limit, offset);
        } else {
            sessions = sessionRepository.findActiveSessions();
        }
        return sessions.stream()
                .map(session -> SessionListItemDTOAssembler.toDTO(session,
                        runningAnalysisRegistry.isRunning(session.getId())))
                .collect(Collectors.toList());
    }

    /**
     * 关闭（删除）会话
     * <p>软删除会话：状态置为 DELETED 并标记逻辑删除。
     * 已删除会话再次关闭会抛出 {@link IllegalStateException}（由 {@link AgentSession#close()} 内置守卫触发）。</p>
     *
     * @param sessionId 会话 ID
     */
    public void closeSession(String sessionId) {
        AgentSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在: " + sessionId));

        session.close();
        sessionRepository.softDelete(sessionId);
        log.info("SessionApplicationService: deleted session={}", sessionId);
    }

    /**
     * 更新会话标题
     *
     * @param sessionId 会话 ID
     * @param title     新标题
     */
    public void updateSessionTitle(String sessionId, String title) {
        if (StringUtils.isBlank(title)) {
            throw new IllegalArgumentException("标题不能为空");
        }
        AgentSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在: " + sessionId));
        if (session.isClosed()) {
            throw new IllegalStateException("会话已删除，无法更新标题");
        }
        sessionRepository.updateTitle(sessionId, title.trim());
        log.info("SessionApplicationService: updated title of session={}", sessionId);
    }

    /**
     * 获取会话消息列表
     * <p>以对话轮次为分页单元游标加载：不传 beforeDialogueId 时返回最新 limit 轮，
     * 传入时返回 id 更小的更早 limit 轮；每轮消息全量返回，保证轮次完整。
     * 输出按 (dialogueId ASC, messageNumber ASC) 升序排列。</p>
     *
     * @param sessionId        会话 ID
     * @param limit            轮次数（可选，默认 5）
     * @param beforeDialogueId 轮次游标（可选，null 表示取最新）
     * @return 消息 DTO 列表（升序）
     */
    public List<MessageDTO> getMessages(String sessionId, Integer limit, Long beforeDialogueId) {
        // 验证会话是否存在
        sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在: " + sessionId));

        // 按轮次游标分页加载（每轮消息全量，保证轮次完整），limit 为空时默认 5 轮
        int roundLimit = (limit != null && limit > 0) ? limit : DEFAULT_ROUND_LIMIT;
        List<Dialogue> dialogues = dialogueRepository.findRoundsBySessionId(sessionId, beforeDialogueId, roundLimit);

        // 收集所有消息及其所属对话 ID
        List<MessageWithDialogue> allMessages = new ArrayList<>();
        for (Dialogue d : dialogues) {
            Long dialogueId = d.getId();
            List<DialogueMessage> messages = d.getMessages();
            // 兜底：若持久化消息缺少用户消息（历史脏数据），依据对话的 user_question 重建首条用户消息，
            // 确保前端能以"用户消息"为锚点渲染对话详情
            if (messages.stream().noneMatch(m -> MessageRole.USER.equals(m.getRole()))) {
                messages = new ArrayList<>(messages);
                messages.add(0, DialogueMessage.userMessage(1L, d.getUserQuestion()));
            }
            messages.stream()
                    .map(msg -> new MessageWithDialogue(dialogueId, msg))
                    .forEach(allMessages::add);
        }
        // 先按 dialogueId 排序（auto-increment 主键反映创建顺序），再按 messageNumber 排序
        // 确保多轮对话中"用户提问→Agent回复"的配对顺序正确，
        // 而非所有对话的 messageNumber=1 排在一起、messageNumber=2 排在一起
        allMessages.sort((a, b) -> {
            int cmp = a.dialogueId().compareTo(b.dialogueId());
            if (cmp != 0) {
                return cmp;
            }
            Long seqA = a.message().getMessageNumber() != null ? a.message().getMessageNumber() : 0L;
            Long seqB = b.message().getMessageNumber() != null ? b.message().getMessageNumber() : 0L;
            return seqA.compareTo(seqB);
        });

        // 转换
        return allMessages.stream()
                .map(mwd -> MessageDTOAssembler.toDTO(mwd.message(), sessionId, mwd.dialogueId()))
                .toList();
    }

    /**
     * 消息与对话 ID 的关联记录
     *
     * @param dialogueId 对话轮次 ID
     * @param message    对话消息
     */
    private record MessageWithDialogue(Long dialogueId, DialogueMessage message) {
    }

    /**
     * 根据用户问题生成即时降级标题
     *
     * @param text 用户问题
     * @return 标题
     */
    private String generateInstantTitle(String text) {
        if (text == null || text.isBlank()) {
            return DEFAULT_TITLE;
        }
        String trimmed = text.trim();
        if (trimmed.length() <= 15) {
            return trimmed;
        }
        return trimmed.substring(0, 15) + "...";
    }
}
