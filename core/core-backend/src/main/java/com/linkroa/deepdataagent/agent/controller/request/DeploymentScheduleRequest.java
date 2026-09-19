package com.linkroa.deepdataagent.agent.controller.request;

/**
 * 调度配置请求项（对应创建请求的 {@code schedule} 字段，格式 {@code {cron, timezone}}）。
 * <p>cron 采用 Spring 6 段表达式（秒级颗粒），合法性由领域值对象
 * {@link com.linkroa.deepdataagent.agent.domain.model.DeploymentSchedule} 构造时校验。</p>
 *
 * @param cron     cron 表达式（6 段，必填）
 * @param timezone 时区 ID（可空，缺省 Asia/Shanghai）
 */
public record DeploymentScheduleRequest(
        String cron,
        String timezone
) {
}
