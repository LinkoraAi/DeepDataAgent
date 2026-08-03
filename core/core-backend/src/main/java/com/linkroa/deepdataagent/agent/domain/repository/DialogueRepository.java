package com.linkroa.deepdataagent.agent.domain.repository;

import com.linkroa.deepdataagent.agent.domain.model.Dialogue;
import com.linkroa.deepdataagent.agent.domain.model.DialogueMessage;
import com.linkroa.deepdataagent.agent.domain.valueobject.DialogueStatus;

import java.util.List;
import java.util.Optional;

/**
 * 对话轮次仓储接口
 * <p>定义在 domain 层，实现在 infrastructure 层。</p>
 */
public interface DialogueRepository {

    Optional<Dialogue> findById(Long id);

    List<Dialogue> findBySessionId(String sessionId);

    /**
     * 按轮次游标分页查询会话的对话轮次
     * <p>不传 beforeDialogueId 时返回最新的 limit 轮；传入时返回 id 小于该值的最近 limit 轮。
     * 每轮包含全量消息（messages JSON 已解析），保证轮次完整性。</p>
     *
     * @param sessionId        会话 ID
     * @param beforeDialogueId 游标（可选，null 表示取最新）
     * @param limit            轮次数
     * @return 对话轮次列表（id 倒序，最新在前）
     */
    List<Dialogue> findRoundsBySessionId(String sessionId, Long beforeDialogueId, int limit);

    /** 查询所有 RUNNING 状态的对话（用于启动时崩溃恢复扫描） */
    List<Dialogue> findRunningDialogues();

    Dialogue save(Dialogue dialogue);

    void updateStatus(Long id, DialogueStatus status);

    void updateStatusAndMetadata(Long id, DialogueStatus status, String metadata);

    List<DialogueMessage> findMessagesByDialogueId(Long dialogueId);

    /** 更新对话的消息列表并同步状态（原子操作，messages 序列化为 JSON 存储） */
    void updateMessages(Long dialogueId, List<DialogueMessage> messages, DialogueStatus status);

    /** 将所有 RUNNING 状态的对话批量标记为 FAILED（启动崩溃恢复的兜底操作） */
    void markAllRunningAsFailed();
}
