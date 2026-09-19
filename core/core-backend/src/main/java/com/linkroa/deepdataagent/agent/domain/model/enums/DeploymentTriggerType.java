package com.linkroa.deepdataagent.agent.domain.model.enums;

import org.apache.commons.lang3.StringUtils;

/**
 * 调度触发方式（运行记录 {@code deployment_run.trigger_kind} 与打标会话 {@code agent_session.trigger_type} 共用值域）。
 *
 * <p>对齐真实 Managed Agents 平台的触发语义。调度器本身是否定时由
 * {@code schedule} 配置决定（null=仅手动/webhook 触发），本枚举描述<b>单次触发</b>的来源方式：</p>
 * <ul>
 *   <li>{@link #CRON}：定时轮询领取触发（D14 调度基线）；</li>
 *   <li>{@link #WEBHOOK}：回调触发，靠路径中的 {@code webhook_token} 免 JWT 调用；</li>
 *   <li>{@link #MANUAL}：手动运行，直接经控制面触发。</li>
 * </ul>
 *
 * @see com.linkroa.deepdataagent.agent.domain.model.DeploymentRun
 */
public enum DeploymentTriggerType {

    /** 定时触发（schedule.cron 到期，经轮询 CAS 领取） */
    CRON("cron"),
    /** 回调触发（靠路径 token 免 JWT） */
    WEBHOOK("webhook"),
    /** 手动触发 */
    MANUAL("manual");

    /** 对外暴露的小写源码取值（对齐规范枚举命名小写值域） */
    private final String value;

    DeploymentTriggerType(String value) {
        this.value = value;
    }

    /**
     * 返回值域字符串（小写，如 {@code cron} / {@code webhook} / {@code manual}）。
     *
     * @return 源码取值
     */
    public String getValue() {
        return value;
    }

    /**
     * 大小写不敏感地解析触发类型字符串。
     *
     * @param value 待解析字符串
     * @return 匹配的触发类型
     * @throws IllegalArgumentException 值域外或空白输入
     */
    public static DeploymentTriggerType fromValue(String value) {
        if (StringUtils.isBlank(value)) {
            throw new IllegalArgumentException("触发类型不能为空");
        }
        for (DeploymentTriggerType type : values()) {
            if (type.value.equalsIgnoreCase(value.trim())) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知触发类型: " + value);
    }
}