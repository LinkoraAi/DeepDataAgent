package com.linkroa.deepdataagent.agent.infrastructure.persistence;

import com.linkroa.deepdataagent.agent.domain.model.Dialogue;
import com.linkroa.deepdataagent.agent.domain.model.DialogueMessage;
import com.linkroa.deepdataagent.agent.domain.repository.AgentSessionRepository;
import com.linkroa.deepdataagent.agent.domain.repository.DialogueRepository;
import com.linkroa.deepdataagent.agent.domain.valueobject.DialogueStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * 消息持久化服务
 * <p>负责对话轮次与消息的持久化。用户消息在流式开始时同步写入，
 * 后续每次 harness agent 返回事件消息后由 {@link #updateMessagesSync} 实时写入 dialogue.messages，
 * 保证"来一条消息就马上入库"。</p>
 */
@Service
public class MessagePersistenceService {

    private static final Logger log = LoggerFactory.getLogger(MessagePersistenceService.class);

    private final AgentSessionRepository sessionRepository;
    private final DialogueRepository dialogueRepository;
    private final TransactionTemplate transactionTemplate;

    /**
     * 构造方法
     *
     * @param sessionRepository   会话仓储
     * @param dialogueRepository  对话轮次仓储
     * @param transactionTemplate 编程式事务模板，用于保证对话、用户消息与会话时间三处写入的原子性
     */
    public MessagePersistenceService(AgentSessionRepository sessionRepository,
                                     DialogueRepository dialogueRepository,
                                     TransactionTemplate transactionTemplate) {
        this.sessionRepository = sessionRepository;
        this.dialogueRepository = dialogueRepository;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 同步持久化用户消息并创建对话轮次
     * <p>创建新的 Dialogue（状态 RUNNING），将用户消息作为 messages 数组的第一个元素写入，
     * 返回 newly created 的 dialogueId 供流式生命周期使用。</p>
     * <p>方法内部使用 {@link TransactionTemplate} 开启编程式事务，保证创建对话、写入用户消息、
     * 触摸会话最后消息时间三处写入要么全部成功、要么全部回滚，事务边界自包含，不依赖调用方开启事务。</p>
     *
     * @param sessionId 会话 ID
     * @param text     用户问题（上限 5000 字符）
     * @return 新创建的对话轮次 ID
     */
    public Long persistUserMessageSync(String sessionId, String text) {
        return transactionTemplate.execute(status -> {
            Dialogue dialogue = new Dialogue(sessionId, text);
            dialogue.start();
            dialogueRepository.save(dialogue);

            DialogueMessage userMsg = DialogueMessage.userMessage(1, text);
            userMsg.complete();
            dialogueRepository.updateMessages(dialogue.getId(), List.of(userMsg), DialogueStatus.RUNNING);

            sessionRepository.touchLastMessage(sessionId);
            return dialogue.getId();
        });
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

    /**
     * 同步写入当前对话轮次的全部消息快照
     * <p>每次 harness agent 返回事件消息后调用，将收集到的消息连同对话状态实时落库。
     * 异常向上抛出：流式过程中写库失败视为分析失败，由调用方终止流程并推送 error 事件。</p>
     *
     * @param dialogueId 对话轮次 ID
     * @param messages   消息列表快照（含进行中消息）
     * @param status     对话状态（RUNNING / COMPLETED / FAILED / CANCELLED）
     */
    public void updateMessagesSync(Long dialogueId, List<DialogueMessage> messages, DialogueStatus status) {
        dialogueRepository.updateMessages(dialogueId, messages, status);
    }
}