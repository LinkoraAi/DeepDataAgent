package com.linkroa.deepdataagent.agent.controller.response;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 调度器响应 DTO（调度器对象字段全集）。
 * <p>{@code environment_variables / resources / initial_events / metadata} 为触发会话
 * 透传载荷回显（GitHub 令牌等只写材料在装配侧消费，回显按存储 JSON 原样解析）；
 * {@code schedule} 为 null 表示仅手动/webhook 触发；{@code next_run_at} 为轮询领取依据；
 * {@code upcoming_runs_at} 为未来到期时间预告（响应侧实时计算，不落库）；
 * {@code webhook_token} 用于拼装免 JWT 回调路径（仅开放 webhook 时有值）。</p>
 *
 * @param deployment_id         调度器业务ID
 * @param name                  调度器名称
 * @param description           描述
 * @param agent_id              指向的 Agent 业务ID
 * @param agent_version         创建时固定的 Agent 版本号（触发不漂移）
 * @param environment_id        指向的 Environment（null=默认环境）
 * @param environment_variables 触发会话环境变量回显
 * @param resources             触发会话挂载资源回显
 * @param vault_ids             触发会话保管库 ID 列表
 * @param initial_events        首批用户消息事件回显
 * @param metadata              会话元数据回显
 * @param schedule              调度配置（null=仅手动/webhook 触发）
 * @param next_run_at           下次到期触发时间（轮询领取依据）
 * @param webhook_token         webhook 回调密钥（null=未开放）
 * @param status                状态（active / paused）
 * @param paused_reason         暂停原因（可空）
 * @param last_run_at           最近一次触发时间
 * @param last_session_id       最近一次触发新建的会话ID
 * @param last_status           最近一次触发执行结果状态
 * @param archived_at           归档时间（null=未归档）
 * @param created_at            创建时间
 * @param updated_at            更新时间
 * @param upcoming_runs_at      未来到期时间预告（最多 5 条，实时计算；无调度为空列表）
 */
public record DeploymentResponse(
        String deployment_id,
        String name,
        String description,
        String agent_id,
        int agent_version,
        String environment_id,
        Map<String, Object> environment_variables,
        List<Map<String, Object>> resources,
        List<String> vault_ids,
        List<Map<String, Object>> initial_events,
        Map<String, Object> metadata,
        DeploymentScheduleResponse schedule,
        OffsetDateTime next_run_at,
        String webhook_token,
        String status,
        String paused_reason,
        OffsetDateTime last_run_at,
        String last_session_id,
        String last_status,
        OffsetDateTime archived_at,
        OffsetDateTime created_at,
        OffsetDateTime updated_at,
        List<OffsetDateTime> upcoming_runs_at
) {
}
