package com.linkroa.deepdataagent.runtime.domain.model.enums;

import java.util.Locale;

/**
 * 内部轮次执行相位（对应 {@code agent_session.turn_phase}，与 {@link AgentSessionStatus} 双列独立维护）。
 * <p>取值 {@code idle / running / awaiting_confirmation / cancelling}，用于 HITL 挂起、
 * 取消两源谓词与单活跃执行守卫。<b>该相位 MUST NOT 出现在任何 Session 响应中，
 * 也 MUST NOT 派生任何 {@code session.status_*} 事件</b>——对外仅暴露 {@link AgentSessionStatus}。</p>
 * <p>对外派生口径（装配层读 status 列即可，无需条件翻译）：</p>
 * <ul>
 *   <li>相位 {@code RUNNING / AWAITING_CONFIRMATION / CANCELLING} ⇒ 对外 status 恒为 {@code running}；</li>
 *   <li>相位 {@code IDLE} ⇒ 对外 status 为会话自身 status 列原值。</li>
 * </ul>
 * <p>相位集合 {@code RUNNING / AWAITING_CONFIRMATION / CANCELLING} 同时是「存在活跃执行」的判定口径
 * （见 {@link #active()}）。</p>
 */
public enum TurnPhase {

    /** 无进行中的轮次 */
    IDLE,

    /** 轮次执行中 */
    RUNNING,

    /** HITL 挂起，等待人工确认 / 拒绝（本轮未结束，对外仍为 running） */
    AWAITING_CONFIRMATION,

    /** 轮次取消中（等待执行侧收敛回 idle） */
    CANCELLING;

    /**
     * 相位的规范持久化取值（小写，如 {@code "awaiting_confirmation"}）。
     *
     * @return 小写相位值
     */
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * 该相位是否表示「存在活跃执行」——running / awaiting_confirmation / cancelling 三态为活跃相位。
     *
     * @return true=活跃执行占用中
     */
    public boolean active() {
        return this != IDLE;
    }

    /**
     * 从字符串解析相位（大小写不敏感）。
     *
     * @param value 相位字符串（如 {@code "running"} / {@code "RUNNING"}），null / 空白返回 null
     * @return 相位枚举；未知取值抛 {@link IllegalArgumentException}
     */
    public static TurnPhase fromValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}