package com.linkroa.deepdataagent.runtime.domain.repository;

import com.linkroa.deepdataagent.runtime.domain.model.ChatEvent;

import java.util.List;

/**
 * 聊天事件仓储接口。
 */
public interface ChatEventRepository {

    /**
     * 保存事件。
     */
    ChatEvent save(ChatEvent event);

    /**
     * 分配会话内下一序列号（须在事务内调用，当前事件独占事务边界，MAX+1 偶发竞争已由
     * 会话级的「同一会话同时只有一个执行」+ 单实例 + 单事务写入保证，实现时以 RETURNING 消歧）。
     *
     * @param sessionId 会话 ID
     * @return 下一可用 sequence_num（从 1 开始）
     */
    long nextSequenceNum(String sessionId);

    /**
     * 按会话查询序列号大于 afterSequenceNum 的事件（回放用，升序）。
     */
    List<ChatEvent> findBySessionAfter(String sessionId, long afterSequenceNum);

    /**
     * 按轮次查询全部事件（单轮回放用，升序）。
     */
    List<ChatEvent> findByRound(String roundId);
}