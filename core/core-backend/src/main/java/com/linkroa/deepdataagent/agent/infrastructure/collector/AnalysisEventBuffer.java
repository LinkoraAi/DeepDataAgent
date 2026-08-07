package com.linkroa.deepdataagent.agent.infrastructure.collector;

import io.agentscope.core.event.AgentEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 分析事件缓冲
 * <p>按会话累积分析过程中已推送的 {@link AgentEvent}，供刷新恢复（resume）时回放。
 * 属于运行中会话的瞬态状态，随运行中注册表同生命周期，会话结束、出错或取消即释放。</p>
 * <p>断线窗口内前端无法接收事件，但事件仍被本缓冲累积；前端恢复订阅后，
 * 通过回放这些事件即可完整重建分析进度，避免断线期间的事件永久丢失。</p>
 * <p>线程安全：使用同步锁保护底层列表的读写（事件由执行线程追加，回放由 resume 请求线程读取）。</p>
 */
public class AnalysisEventBuffer {

    /** 已推送事件的累积列表（按产生顺序） */
    private final List<AgentEvent> events = Collections.synchronizedList(new ArrayList<>());

    /**
     * 追加一个事件到缓冲
     *
     * @param event 分析事件
     */
    public void add(AgentEvent event) {
        if (event != null) {
            events.add(event);
        }
    }

    /**
     * 导出当前缓冲事件的不可变快照
     *
     * @return 事件列表快照（按产生顺序）
     */
    public List<AgentEvent> snapshot() {
        synchronized (events) {
            return new ArrayList<>(events);
        }
    }

    /**
     * 获取缓冲事件数量
     *
     * @return 数量
     */
    public int size() {
        return events.size();
    }
}