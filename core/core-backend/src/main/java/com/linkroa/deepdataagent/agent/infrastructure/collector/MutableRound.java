package com.linkroa.deepdataagent.agent.infrastructure.collector;

import java.util.ArrayList;
import java.util.List;

/**
 * 可变轮次追踪类（保留自快照重构前的建模，当前已无生产调用方）
 * <p>用于在流式处理过程中增量构建 ReAct 轮次的思考内容与工具调用。
 * 注意：快照持久化链路已删除，{@code toSnapshot()} 方法不复存在。</p>
 */
class MutableRound {
    final String id;
    final long startTime;
    final String thinkingContent;
    final List<ToolCallItem> toolCalls = new ArrayList<>();

    MutableRound(String id, long startTime, String thinkingContent) {
        this.id = id;
        this.startTime = startTime;
        this.thinkingContent = thinkingContent;
    }
}