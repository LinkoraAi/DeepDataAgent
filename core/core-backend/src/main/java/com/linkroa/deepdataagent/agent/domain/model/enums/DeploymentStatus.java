package com.linkroa.deepdataagent.agent.domain.model.enums;

import org.apache.commons.lang3.StringUtils;

/**
 * 调度器状态枚举（对应 deployment.status 列，替代旧 enabled 布尔）。
 * <p>ACTIVE=可触发（含 cron 轮询领取）；PAUSED=暂停（含归档双写），暂停期间不触发、不可手动 run。</p>
 */
public enum DeploymentStatus {

    /** 启用中：可被调度轮询领取与手动/webhook 触发 */
    ACTIVE("active"),

    /** 已暂停：不触发（归档态以 archived_at 标记叠加本状态表达） */
    PAUSED("paused");

    private final String value;

    DeploymentStatus(String value) {
        this.value = value;
    }

    /**
     * 数据库 / 协议层小写值。
     */
    public String getValue() {
        return value;
    }

    /**
     * 按小写值解析枚举（大小写不敏感，容忍首尾空白）。
     *
     * @param value 状态值
     * @return 匹配的枚举
     * @throws IllegalArgumentException 值非法
     */
    public static DeploymentStatus fromValue(String value) {
        String normalized = StringUtils.trimToEmpty(value).toLowerCase();
        for (DeploymentStatus status : values()) {
            if (status.value.equals(normalized)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的调度器状态值: " + value);
    }
}
