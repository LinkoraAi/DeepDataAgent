package com.linkroa.deepdataagent.runtime.domain.model.enums;

import java.util.Locale;

/**
 * 聊天事件类型（对应 chat_event.event_type，SSE 的 {@code event} 字段）。
 * <p>事件模型：{@code payload} 内部采用 content-blocks 结构（结构定义与装配见
 * {@code application ChatEventPayloadAssembler}），本枚举承接流内增量事件、
 * 应用层合成事件与终态事件三类：</p>
 * <ul>
 *   <li><b>流内增量</b>：THINKING / MESSAGE / TOOL_CALL / TOOL_CALL_OUTPUT——SDK 事件映射，
 *       {@code is_last} 标记文本/推理块结束；</li>
 *   <li><b>应用层合成</b>：RUN_START（流开始）、SESSION_STATUS（终态唯一广播出口，
 *       事务提交后合成，携带 session status 与 stop_reason）；</li>
 *   <li><b>终态</b>：RUN_END 应用层合成、随轮次终态落库不发布（终态广播统一由
 *       SESSION_STATUS 出口），RUN_ERROR / ERROR 供异常路径；SUMMARY / AGENT_PROGRESS /
 *       EXCEED_MAX_ITERS 为保留枚举（当前版本不产出事件，迭代上限经 SESSION_STATUS
 *       携带 {@code stop_reason=max_iterations} 表达）。</li>
 * </ul>
 * <p>历史事件（已落库的旧枚举名）永不删除，保证回放兼容；新事件统一使用 content-blocks payload。</p>
 */
public enum ChatEventType {
    /** 本轮执行开始（应用层合成，含 round_id / run_id） */
    RUN_START,
    /** 推理增量（is_last 标记思维块结束） */
    THINKING,
    /** 助手文本增量或最终块（delta 语义，is_last 标记文本块结束） */
    MESSAGE,
    /** 工具调用（含入参 arguments，SDK TOOL_CALL_* 聚合后发出） */
    TOOL_CALL,
    /** 工具调用结果（head+tail 截断的最终输出） */
    TOOL_CALL_OUTPUT,
    /** 上下文压缩摘要（保留枚举，当前版本不产出） */
    SUMMARY,
    /** 本轮执行结束（应用层合成，随轮次终态落库不发布，终态出口为 SESSION_STATUS） */
    RUN_END,
    /** 本轮执行异常（应用层合成，随轮次终态落库不发布） */
    RUN_ERROR,
    /** 会话状态变更（终态唯一广播出口：status + stop_reason） */
    SESSION_STATUS,
    /** 通用错误（应用层合成，随轮次终态落库不发布） */
    ERROR,
    /** 执行进度占位（保留枚举，当前版本不产出事件） */
    AGENT_PROGRESS,
    /** 迭代上限触发（保留枚举，不产出事件；经 SESSION_STATUS 携带 stop_reason=max_iterations） */
    EXCEED_MAX_ITERS;

    /**
     * 将事件名归一化（小写）后反解为枚举。
     *
     * @param value SSE 事件名（小写带下划线，如 run_start）
     * @return 匹配的枚举
     */
    public static ChatEventType fromValue(String value) {
        if (value == null) {
            throw new IllegalArgumentException("聊天事件类型不能为空");
        }
        return ChatEventType.valueOf(value.toUpperCase(Locale.ROOT));
    }
}