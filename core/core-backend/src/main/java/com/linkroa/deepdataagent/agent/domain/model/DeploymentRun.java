package com.linkroa.deepdataagent.agent.domain.model;

import com.linkroa.deepdataagent.agent.domain.model.enums.DeploymentRunStatus;
import com.linkroa.deepdataagent.agent.domain.model.enums.DeploymentTriggerType;
import org.apache.commons.lang3.StringUtils;

import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * 调度运行记录领域模型（对应 deployment_run 表，一行定稿）。
 * <p>每次 Deployment 触发（cron 轮询 / 手动 / webhook）产生一条运行记录，
 * 承载触发时间、触发方式、结果状态与关联会话；以 {@code drun_} 前缀业务ID标识。
 * 触发落 {@link DeploymentRunStatus#RUNNING} 初始态，触发会话终局出口按
 * episode 口径（自触发起首个终局出口轮）经 CAS 窄列更新就地终态化。</p>
 *
 * @param id           数据库主键
 * @param runId        运行记录业务ID（drun_ 前缀）
 * @param deploymentId 所属调度器业务ID
 * @param sessionId    本次触发新建的会话ID（可空：启动失败时无会话）
 * @param triggerKind  触发方式（cron / manual / webhook）
 * @param status       运行状态
 * @param startedAt    触发时间
 * @param finishedAt   结束时间（{@code running} 初始态为 null，触发会话终局出口回写后填充）
 * @param createdAt    创建时间
 * @param updatedAt    更新时间
 */
public record DeploymentRun(
        Long id,
        String runId,
        String deploymentId,
        String sessionId,
        DeploymentTriggerType triggerKind,
        DeploymentRunStatus status,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    /** 运行记录业务ID前缀 */
    public static final String RUN_ID_PREFIX = "drun_";

    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");

    /**
     * 紧凑构造器：不变量校验。
     */
    public DeploymentRun {
        if (StringUtils.isBlank(runId)) {
            throw new IllegalArgumentException("运行记录ID不能为空");
        }
        if (!runId.startsWith(RUN_ID_PREFIX)) {
            throw new IllegalArgumentException("运行记录ID必须以 drun_ 为前缀");
        }
        if (StringUtils.isBlank(deploymentId)) {
            throw new IllegalArgumentException("调度器ID不能为空");
        }
        if (triggerKind == null) {
            throw new IllegalArgumentException("触发方式不能为空");
        }
        if (status == null) {
            throw new IllegalArgumentException("运行状态不能为空");
        }
        if (startedAt == null) {
            throw new IllegalArgumentException("触发时间不能为空");
        }
    }

    /**
     * 新建运行记录（触发成功：{@code status=running}、{@code startedAt=now}）。
     *
     * @param runId        运行记录业务ID（drun_ 前缀）
     * @param deploymentId 所属调度器业务ID
     * @param sessionId    本次触发新建的会话ID（可空）
     * @param triggerKind  触发方式
     * @return 运行记录
     */
    public static DeploymentRun start(String runId, String deploymentId, String sessionId,
                                      DeploymentTriggerType triggerKind) {
        OffsetDateTime now = OffsetDateTime.now(DEFAULT_ZONE);
        return new DeploymentRun(null, runId, deploymentId, sessionId, triggerKind,
                DeploymentRunStatus.RUNNING, now, null, now, now);
    }
}
