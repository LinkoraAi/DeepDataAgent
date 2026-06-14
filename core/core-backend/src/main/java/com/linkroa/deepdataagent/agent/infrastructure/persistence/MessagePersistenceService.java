package com.linkroa.deepdataagent.agent.infrastructure.persistence;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.linkroa.deepdataagent.agent.domain.repository.SessionRepository;
import com.linkroa.deepdataagent.agent.infrastructure.client.LLMClient;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.AgentSessionEntity;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.ConversationMsgEntity;
import com.linkroa.deepdataagent.datasource.infrastructure.util.LogMasker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * 消息异步持久化服务
 * <p>提供异步的消息持久化能力，包括用户消息、助手消息、工具调用消息的保存，
 * 以及会话元数据更新和标题生成。</p>
 */
@Service
public class MessagePersistenceService {

    private static final Logger log = LoggerFactory.getLogger(MessagePersistenceService.class);
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String FALLBACK_TITLE_PREFIX = "对话 ";

    private final SessionRepository sessionRepository;
    private final LLMClient llmClient;
    private final ObjectMapper objectMapper;

    public MessagePersistenceService(SessionRepository sessionRepository,
                                     LLMClient llmClient,
                                     ObjectMapper objectMapper) {
        this.sessionRepository = sessionRepository;
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 异步持久化用户消息
     *
     * @param sessionId 会话 ID
     * @param content   消息内容
     */
    public void persistUserMessage(String sessionId, String content) {
        Mono.fromRunnable(() -> {
            try {
                ConversationMsgEntity entity = ConversationMsgEntity.builder()
                        .sessionId(sessionId)
                        .role("user")
                        .content(content)
                        .createdAt(LocalDateTime.now().format(DATE_TIME_FORMATTER))
                        .build();
                sessionRepository.saveMessage(entity);
                log.debug("MessagePersistenceService: persisted user message for session={}", sessionId);
            } catch (Exception e) {
                log.error("MessagePersistenceService: failed to persist user message for session={}: {}",
                        sessionId, LogMasker.mask(e.getMessage()), e);
            }
        }).subscribeOn(Schedulers.boundedElastic()).subscribe();
    }

    /**
     * 异步持久化助手消息
     *
     * @param sessionId 会话 ID
     * @param content   消息内容
     */
    public void persistAssistantMessage(String sessionId, String content) {
        Mono.fromRunnable(() -> {
            try {
                ConversationMsgEntity entity = ConversationMsgEntity.builder()
                        .sessionId(sessionId)
                        .role("assistant")
                        .content(content)
                        .createdAt(LocalDateTime.now().format(DATE_TIME_FORMATTER))
                        .build();
                sessionRepository.saveMessage(entity);
                log.debug("MessagePersistenceService: persisted assistant message for session={}", sessionId);
            } catch (Exception e) {
                log.error("MessagePersistenceService: failed to persist assistant message for session={}: {}",
                        sessionId, LogMasker.mask(e.getMessage()), e);
            }
        }).subscribeOn(Schedulers.boundedElastic()).subscribe();
    }

    /**
     * 异步持久化工具调用消息
     *
     * @param sessionId  会话 ID
     * @param toolName   工具名称
     * @param toolInput  工具输入（将被序列化为 JSON）
     * @param toolResult 工具输出（将被序列化为 JSON）
     */
    public void persistToolCall(String sessionId, String toolName, Object toolInput, Object toolResult) {
        Mono.fromRunnable(() -> {
            try {
                String toolCallsJson = serializeToJson(toolName, toolInput);
                String toolResultJson = serializeToJson(toolResult);

                ConversationMsgEntity entity = ConversationMsgEntity.builder()
                        .sessionId(sessionId)
                        .role("tool")
                        .toolCalls(toolCallsJson)
                        .toolResult(toolResultJson)
                        .createdAt(LocalDateTime.now().format(DATE_TIME_FORMATTER))
                        .build();
                sessionRepository.saveMessage(entity);
                log.debug("MessagePersistenceService: persisted tool call for session={}, tool={}", sessionId, toolName);
            } catch (Exception e) {
                log.error("MessagePersistenceService: failed to persist tool call for session={}: {}",
                        sessionId, LogMasker.mask(e.getMessage()), e);
            }
        }).subscribeOn(Schedulers.boundedElastic()).subscribe();
    }

    /**
     * 异步更新会话元数据（消息计数和最后消息时间）
     *
     * @param sessionId 会话 ID
     */
    public void updateSessionMetadata(String sessionId) {
        Mono.fromRunnable(() -> {
            try {
                sessionRepository.incrementMessageCount(sessionId);
                log.debug("MessagePersistenceService: updated session metadata for session={}", sessionId);
            } catch (Exception e) {
                log.error("MessagePersistenceService: failed to update session metadata for session={}: {}",
                        sessionId, LogMasker.mask(e.getMessage()), e);
            }
        }).subscribeOn(Schedulers.boundedElastic()).subscribe();
    }

    /**
     * 异步生成并设置会话标题
     * <p>仅在首次分析时调用 LLM 生成标题，如果 LLM 调用失败则使用降级标题。</p>
     *
     * @param sessionId      会话 ID
     * @param modelConfigId  模型配置 ID
     * @param userQuestion   用户问题
     * @param isFirstAnalysis 是否为首次分析
     */
    public void generateAndSetTitle(String sessionId, Long modelConfigId, String userQuestion, boolean isFirstAnalysis) {
        if (!isFirstAnalysis) {
            return;
        }

        Mono.fromRunnable(() -> {
            try {
                String title = llmClient.generateTitle(modelConfigId, userQuestion);
                if (title == null || title.isBlank()) {
                    // LLM 失败时，使用降级标题
                    int messageCount = sessionRepository.countMessagesBySessionId(sessionId);
                    title = FALLBACK_TITLE_PREFIX + messageCount;
                }
                sessionRepository.updateTitle(sessionId, title);
                log.info("MessagePersistenceService: set title='{}' for session={}", title, sessionId);
            } catch (Exception e) {
                log.error("MessagePersistenceService: failed to generate and set title for session={}: {}",
                        sessionId, LogMasker.mask(e.getMessage()), e);
            }
        }).subscribeOn(Schedulers.boundedElastic()).subscribe();
    }

    /**
     * 将工具名称和输入序列化为 JSON 字符串
     */
    private String serializeToJson(String toolName, Object toolInput) {
        try {
            return objectMapper.writeValueAsString(new ToolCallRecord(toolName, toolInput));
        } catch (JacksonException e) {
            log.warn("MessagePersistenceService: failed to serialize tool call: {}", LogMasker.mask(e.getMessage()));
            return "{\"tool\":\"" + toolName + "\",\"input\":\"unknown\"}";
        }
    }

    /**
     * 将对象序列化为 JSON 字符串
     */
    private String serializeToJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JacksonException e) {
            log.warn("MessagePersistenceService: failed to serialize object: {}", LogMasker.mask(e.getMessage()));
            return obj != null ? obj.toString() : "null";
        }
    }

    /**
     * 工具调用记录（用于 JSON 序列化）
     */
    private record ToolCallRecord(String tool, Object input) {
    }
}
