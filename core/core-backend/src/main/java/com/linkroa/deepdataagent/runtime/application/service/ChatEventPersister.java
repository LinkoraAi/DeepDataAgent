package com.linkroa.deepdataagent.runtime.application.service;

import com.linkroa.deepdataagent.runtime.domain.model.ChatEvent;

/**
 * 聊天事件异步落库端口：SSE 推流与持久化解耦，事件先入内存队列，
 * 由后台按 {@code sequence_number} 单调顺序批量落库。落库为尽力而为语义：
 * 写库失败重试并记录日志，最终凭 {@code (session_id, sequence_num)} 唯一索引
 * 与断线重连回放做幂等 / 去重兜底；进程崩溃等非优雅停机会丢失尚未落库的事件。
 */
public interface ChatEventPersister {

    /**
     * 入队（非阻塞，不等待落库）。
     */
    void enqueue(ChatEvent event);

    /**
     * 排空待写队列（优雅停机 / 测试确定性用）。
     */
    void flush();
}