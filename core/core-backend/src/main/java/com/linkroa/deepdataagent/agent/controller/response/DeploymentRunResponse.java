package com.linkroa.deepdataagent.agent.controller.response;

import java.time.OffsetDateTime;

/**
 * 调度运行记录响应 DTO（运行记录形状，6.5 管理面）。
 * <p>{@code id} 为 {@code drun_} 前缀业务ID；{@code trigger} 为单次触发来源
 * （cron / manual / webhook）；{@code status} 为运行状态（本期触发落 running 初始态，
 * 终态回写随运行闭环接通）；{@code finished_at} 终态后填充（可空）。</p>
 *
 * @param id            运行记录业务ID（drun_ 前缀）
 * @param type          资源类型（固定 {@code deployment_run}）
 * @param deployment_id 所属调度器业务ID
 * @param session_id    本次触发新建的会话ID（可空=启动失败无会话）
 * @param trigger       触发方式（cron / manual / webhook）
 * @param status        运行状态（running / succeeded / failed / terminated）
 * @param started_at    触发时间
 * @param finished_at   结束时间（可空=未终态）
 */
public record DeploymentRunResponse(
        String id,
        String type,
        String deployment_id,
        String session_id,
        String trigger,
        String status,
        OffsetDateTime started_at,
        OffsetDateTime finished_at
) {

    /** 资源类型常量（对齐信封 type 约定）。 */
    public static final String TYPE = "deployment_run";
}
