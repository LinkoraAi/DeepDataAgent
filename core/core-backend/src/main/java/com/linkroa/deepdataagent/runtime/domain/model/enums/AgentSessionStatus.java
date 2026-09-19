package com.linkroa.deepdataagent.runtime.domain.model.enums;

import java.util.Locale;

/**
 * 会话生命周期状态（对外四态词汇，对应 {@code agent_session.status}）。
 * <p>取值仅 {@code idle / running / rescheduling / terminated}：</p>
 * <ul>
 *   <li>{@code IDLE}：空闲，可接收新消息（初始态 / 正常完成 / 中断 / 执行失败后回落）；</li>
 *   <li>{@code RUNNING}：存在活跃执行（含内部 HITL 等待与取消收敛期间的对外派生值）；</li>
 *   <li>{@code RESCHEDULING}：可恢复的重新调度中间态（本期无生产者，词汇与迁移预留）；</li>
 *   <li>{@code TERMINATED}：不可复活终态（显式终止 / 纯迭代上限）。</li>
 * </ul>
 * <p><b>归档不是状态取值</b>：{@code archived_at} 为独立正交维度，可与任意 status 组合
 * （如 archived_at 非空且 status=terminated）。</p>
 * <p>轮次级瞬态见独立内部相位 {@link TurnPhase}——相位永远不会出现在对外响应或
 * {@code session.status_*} 事件中。</p>
 */
public enum AgentSessionStatus {

    /** 空闲，可接收新消息（新会话初始态） */
    IDLE,

    /** 存在活跃执行（含内部等待确认 / 取消收敛相位的对外派生值） */
    RUNNING,

    /** 可恢复的重新调度中间态（本期无生产者，词汇预留） */
    RESCHEDULING,

    /** 已终止，不可复活 */
    TERMINATED;

    /**
     * 状态的规范持久化 / 对外取值（小写，如 {@code "idle"}、{@code "rescheduling"}）。
     * <p>事件类型 {@code session.status_<status>} 即由本值派生（见 {@link #statusEventType()}）。</p>
     *
     * @return 小写状态值
     */
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * 状态对应的会话状态事件类型（如 {@code "session.status_running"}）。
     * <p>会话状态 / 终态一律以 {@code session.status_<status>} 事件对外承载。</p>
     *
     * @return 事件类型名
     */
    public String statusEventType() {
        return "session.status_" + value();
    }

    /**
     * 从字符串解析状态（大小写不敏感，兼容历史大写存量与规范小写取值）。
     *
     * @param value 状态字符串（如 {@code "idle"} / {@code "IDLE"}），null / 空白返回 null
     * @return 状态枚举；未知取值抛 {@link IllegalArgumentException}
     */
    public static AgentSessionStatus fromValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}