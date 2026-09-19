package com.linkroa.deepdataagent.runtime.domain.model;

import com.linkroa.deepdataagent.runtime.domain.model.enums.AgentSessionStatus;
import com.linkroa.deepdataagent.runtime.domain.model.enums.TerminalEventKind;
import com.linkroa.deepdataagent.runtime.domain.model.enums.TerminalStopReason;
import org.apache.commons.lang3.StringUtils;

/**
 * 终态事件规格（值对象）：决策表输出侧的「落库终态事件」声明。
 * <p>只承载语义（事件种类 + 目标状态 + 终态原因 + 错误码/错误信息），不承载 payload JSON——
 * 后者由领域事件装配工厂 {@code ChatEventFactory}（domain.event）按对外契约装配，
 * 领域与协议层保持单向依赖。</p>
 * <p>收场事件集按「线程先、会话后」镜像：一轮终态先落
 * {@link TerminalEventKind#THREAD_STATUS}（{@code session.thread_status_<status>}）再落
 * {@link TerminalEventKind#SESSION_STATUS}（{@code session.status_<status>}），二者共享同一
 * {@code stop_reason}；{@code session.interrupted} 已废止，中断不以独立事件表达。</p>
 *
 * @param kind       事件种类（线程状态 / 会话状态 / 错误）
 * @param status     目标状态（线程状态事件与会话状态事件均以此表达，错误事件为 null）
 * @param stopReason 终态原因（仅状态事件携带，可空）
 * @param errorCode  错误码（仅 {@link TerminalEventKind#SESSION_ERROR} 携带）
 * @param errorMessage 已脱敏的错误信息（仅 {@link TerminalEventKind#SESSION_ERROR} 携带）
 */
public record TerminalEventSpec(TerminalEventKind kind, AgentSessionStatus status,
                                TerminalStopReason stopReason, String errorCode, String errorMessage) {

    public TerminalEventSpec {
        if (kind == null) {
            throw new IllegalArgumentException("终态事件种类不能为空");
        }
        switch (kind) {
            case THREAD_STATUS, SESSION_STATUS -> {
                if (status == null) {
                    throw new IllegalArgumentException("状态事件必须携带目标状态");
                }
            }
            case SESSION_ERROR -> {
                if (StringUtils.isBlank(errorCode) || StringUtils.isBlank(errorMessage)) {
                    throw new IllegalArgumentException("会话错误事件必须携带错误码与错误信息");
                }
            }
        }
    }

    /**
     * 会话状态事件（{@code session.status_<status>}）。
     *
     * @param status     迁入的会话状态
     * @param stopReason 终态原因（可空）
     */
    public static TerminalEventSpec statusEvent(AgentSessionStatus status, TerminalStopReason stopReason) {
        return new TerminalEventSpec(TerminalEventKind.SESSION_STATUS, status, stopReason, null, null);
    }

    /**
     * 主线程状态镜像事件（{@code session.thread_status_<status>}）。
     *
     * @param threadStatus 迁入的主线程状态
     * @param stopReason   终态原因（可空）
     */
    public static TerminalEventSpec threadStatusEvent(AgentSessionStatus threadStatus,
                                                      TerminalStopReason stopReason) {
        return new TerminalEventSpec(TerminalEventKind.THREAD_STATUS, threadStatus, stopReason, null, null);
    }

    /**
     * 会话错误事件（{@code session.error}）。
     *
     * @param errorCode    错误码
     * @param errorMessage 已脱敏的错误信息（非空）
     */
    public static TerminalEventSpec errorEvent(String errorCode, String errorMessage) {
        return new TerminalEventSpec(TerminalEventKind.SESSION_ERROR, null, null, errorCode, errorMessage);
    }
}