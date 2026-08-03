package com.linkroa.deepdataagent.agent.infrastructure.persistence;

import com.linkroa.deepdataagent.agent.domain.model.Dialogue;
import com.linkroa.deepdataagent.agent.domain.model.DialogueMessage;
import com.linkroa.deepdataagent.agent.domain.repository.AgentSessionRepository;
import com.linkroa.deepdataagent.agent.domain.repository.DialogueRepository;
import com.linkroa.deepdataagent.agent.domain.valueobject.DialogueStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 消息持久化服务
 * <p>负责对话轮次与消息的持久化。用户消息在流式开始时同步写入，
 * 后续全过程消息由 {@link com.linkroa.deepdataagent.agent.application.adapter.BatchFlushManager}
 * 攒批写入 dialogue.messages。</p>
 */
@Service
public class MessagePersistenceService {

    private static final Logger log = LoggerFactory.getLogger(MessagePersistenceService.class);

    private final AgentSessionRepository sessionRepository;
    private final DialogueRepository dialogueRepository;

    public MessagePersistenceService(AgentSessionRepository sessionRepository,
                                     DialogueRepository dialogueRepository) {
        this.sessionRepository = sessionRepository;
        this.dialogueRepository = dialogueRepository;
    }

    /**
     * 同步持久化用户消息并创建对话轮次
     * <p>创建新的 Dialogue（状态 RUNNING），将用户消息作为 messages 数组的第一个元素写入，
     * 返回 newly created 的 dialogueId 供流式生命周期使用。</p>
     *
     * @param sessionId    会话 ID
     * @param userQuestion 用户问题
     * @return 新创建的对话轮次 ID
     */
    public Long persistUserMessageSync(String sessionId, String userQuestion) {
        Dialogue dialogue = new Dialogue(sessionId, userQuestion);
        dialogue.start();
        dialogueRepository.save(dialogue);

        DialogueMessage userMsg = DialogueMessage.userMessage(1, userQuestion);
        userMsg.setDialogueId(dialogue.getId());
        userMsg.complete();
        dialogueRepository.updateMessages(dialogue.getId(), List.of(userMsg), DialogueStatus.RUNNING);

        sessionRepository.touchLastMessage(sessionId);
        log.debug("MessagePersistenceService: persisted user message for dialogue={}", dialogue.getId());
        return dialogue.getId();
    }

    /**
     * 更新会话元数据（兼容方法）
     * <p>触摸会话的最后消息时间，保证会话列表排序正确。</p>
     *
     * @param sessionId 会话 ID
     */
    public void updateSessionMetadataSync(String sessionId) {
        sessionRepository.touchLastMessage(sessionId);
    }
}