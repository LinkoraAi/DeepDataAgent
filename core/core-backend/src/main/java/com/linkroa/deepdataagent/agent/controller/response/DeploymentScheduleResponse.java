package com.linkroa.deepdataagent.agent.controller.response;

/**
 * 调度配置回显（对应响应 {@code schedule} 字段）。
 *
 * @param cron     cron 表达式（Spring 6 段）
 * @param timezone 生效时区（缺省 Asia/Shanghai）
 */
public record DeploymentScheduleResponse(
        String cron,
        String timezone
) {
}
