package com.linkroa.deepdataagent.agent.domain.model.enums;

import org.apache.commons.lang3.StringUtils;

/**
 * 调度运行记录状态枚举（对应 deployment_run.status 列）。
 * <p>触发落 {@link #RUNNING} 初始态；触发会话终局出口经
 * {@code DeploymentRunRepository#casCompleteBySessionId} 回写三个终态之一。</p>
 */
public enum DeploymentRunStatus {

    /** 运行中（触发成功创建会话后的初始态） */
    RUNNING("running"),

    /** 成功结束 */
    SUCCEEDED("succeeded"),

    /** 执行失败 */
    FAILED("failed"),

    /** 被终止 */
    TERMINATED("terminated");

    private final String value;

    DeploymentRunStatus(String value) {
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
    public static DeploymentRunStatus fromValue(String value) {
        String normalized = StringUtils.trimToEmpty(value).toLowerCase();
        for (DeploymentRunStatus status : values()) {
            if (status.value.equals(normalized)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的调度运行状态值: " + value);
    }
}
