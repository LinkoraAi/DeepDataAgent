package com.linkroa.deepdataagent.agent.infrastructure.persistence.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkroa.deepdataagent.agent.domain.model.Dialogue;
import com.linkroa.deepdataagent.agent.domain.model.DialogueMessage;
import com.linkroa.deepdataagent.agent.domain.repository.DialogueRepository;
import com.linkroa.deepdataagent.agent.domain.valueobject.DialogueStatus;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.DialogueEntity;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.mapper.DialogueMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 对话轮次仓储实现
 * <p>消息列表整体以 JSON 数组形式序列化存储于 dialogue.messages 字段。</p>
 */
@Repository
public class DialogueRepositoryImpl implements DialogueRepository {

    private static final Logger log = LoggerFactory.getLogger(DialogueRepositoryImpl.class);

    private static final TypeReference<List<DialogueMessage>> MESSAGES_TYPE = new TypeReference<>() {
    };

    private final DialogueMapper dialogueMapper;
    private final ObjectMapper objectMapper;

    public DialogueRepositoryImpl(DialogueMapper dialogueMapper,
                                  ObjectMapper objectMapper) {
        this.dialogueMapper = dialogueMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<Dialogue> findById(Long id) {
        DialogueEntity entity = dialogueMapper.selectById(id);
        if (entity == null) {
            return Optional.empty();
        }
        return Optional.of(toDomain(entity));
    }

    @Override
    public List<Dialogue> findBySessionId(String sessionId) {
        return dialogueMapper.selectBySessionId(sessionId).stream()
                .map(this::toDomain).collect(Collectors.toList());
    }

    @Override
    public List<Dialogue> findRoundsBySessionId(String sessionId, Long beforeDialogueId, int limit) {
        return dialogueMapper.selectRoundsBySessionId(sessionId, beforeDialogueId, limit).stream()
                .map(this::toDomain).collect(Collectors.toList());
    }

    @Override
    public List<Dialogue> findRunningDialogues() {
        return dialogueMapper.selectRunning().stream()
                .map(this::toDomain).collect(Collectors.toList());
    }

    @Override
    public Dialogue save(Dialogue dialogue) {
        DialogueEntity entity = toEntity(dialogue);
        if (entity.getId() != null && dialogueMapper.selectById(entity.getId()) != null) {
            dialogueMapper.updateById(entity);
        } else {
            dialogueMapper.insert(entity);
            dialogue.setId(entity.getId());
        }
        return toDomain(entity);
    }

    @Override
    public void updateStatus(Long id, DialogueStatus status) {
        dialogueMapper.updateStatus(id, status.name());
    }

    @Override
    public void updateStatusAndMetadata(Long id, DialogueStatus status, String metadata) {
        dialogueMapper.updateStatusAndMetadata(id, status.name(), metadata);
    }

    @Override
    public List<DialogueMessage> findMessagesByDialogueId(Long dialogueId) {
        return parseMessages(dialogueMapper.selectMessages(dialogueId));
    }

    @Override
    public void updateMessages(Long dialogueId, List<DialogueMessage> messages, DialogueStatus status) {
        String json = serializeMessages(messages);
        dialogueMapper.updateMessagesAndStatus(dialogueId, json, status.name());
    }

    @Override
    public void markAllRunningAsFailed() {
        dialogueMapper.markAllRunningAsFailed();
    }

    // --- Entity ↔ Domain 转换 ---

    private Dialogue toDomain(DialogueEntity e) {
        Dialogue d = new Dialogue();
        d.setId(e.getId());
        d.setSessionId(e.getSessionId());
        d.setUserQuestion(e.getUserQuestion());
        d.setMessages(parseMessages(e.getMessages()));
        d.setStatus(DialogueStatus.valueOf(e.getStatus()));
        d.setMetadata(e.getMetadata());
        d.setStartTime(e.getStartTime());
        d.setEndTime(e.getEndTime());
        d.setDeleted(e.getIsDeleted());
        return d;
    }

    private DialogueEntity toEntity(Dialogue d) {
        DialogueEntity e = new DialogueEntity();
        e.setId(d.getId());
        e.setSessionId(d.getSessionId());
        e.setUserQuestion(d.getUserQuestion());
        e.setMessages(serializeMessages(d.getMessages()));
        e.setStatus(d.getStatus().name());
        e.setMetadata(d.getMetadata());
        e.setStartTime(d.getStartTime());
        e.setEndTime(d.getEndTime());
        e.setIsDeleted(d.getDeleted());
        return e;
    }

    /** 将消息列表序列化为 JSON 字符串（null 或空列表返回 null） */
    private String serializeMessages(List<DialogueMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(messages);
        } catch (Exception e) {
            log.error("Failed to serialize dialogue messages to JSON", e);
            throw new IllegalStateException("Failed to serialize dialogue messages", e);
        }
    }

    /** 将 JSON 字符串反序列化为消息列表（null/空/非法 JSON 均返回空列表） */
    private List<DialogueMessage> parseMessages(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, MESSAGES_TYPE);
        } catch (Exception e) {
            log.warn("Failed to parse dialogue messages JSON, return empty list", e);
            return Collections.emptyList();
        }
    }
}
