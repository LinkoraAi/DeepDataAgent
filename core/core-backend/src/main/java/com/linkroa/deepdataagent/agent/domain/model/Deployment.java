package com.linkroa.deepdataagent.agent.domain.model;

import com.linkroa.deepdataagent.agent.domain.model.enums.DeploymentStatus;
import org.apache.commons.lang3.StringUtils;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 调度器领域模型（对应 deployment 表）。
 *
 * <p>Deployment 为「调度器 / 触发器」资源：绑定 Agent（{@code agentVersion} 创建时由服务端
 * 解析并<b>固定</b>——省略版本时取激活版本，触发不随后续发布漂移）与 Environment（可空回退默认），
 * 携带调度配置（{@code schedule}：cron + timezone，null=仅手动/webhook 触发）、
 * 触发会话挂载材料（environmentVariables / resources / vaultIds / initialEvents / metadata，
 * 均为透传载荷，装配解释在 runtime 侧）与最近运行快照（last_*）。</p>
 *
 * <p>状态：{@code status}（active/paused）+ 归档（{@code archivedAt}，归档=archived_at+paused 双写）；
 * 版本激活 / 回滚职责归属 {@link AgentDefinition#activeVersion()}，本模型不承担。</p>
 *
 * @param id                   数据库主键
 * @param deploymentId         调度器业务唯一ID
 * @param name                 调度器名称
 * @param description          描述（空白归一为空串，≤500）
 * @param agentId              指向的 Agent 业务ID
 * @param agentVersion         创建时固定的 Agent 版本号（≥1，触发不漂移）
 * @param environmentId        指向的 Environment 业务ID（可空=默认环境）
 * @param environmentVariables 环境变量 JSON 文本（空白归一 {@code "{}"}，触发时透传 runtime）
 * @param resources            挂载资源 JSON 数组文本（空白归一 {@code "[]"}，格式对齐 session resources）
 * @param vaultIds             保管库业务ID列表（null 归一为空列表）
 * @param initialEvents        首批用户消息事件 JSON 数组文本（空白归一 {@code "[]"}）
 * @param metadata             元数据 JSON 对象文本（空白归一 {@code "{}"}）
 * @param schedule             调度配置（可空=仅手动/webhook 触发）
 * @param nextRunAt            下次到期触发时间（schedule 非空时物化，轮询领取依据）
 * @param webhookToken         webhook 触发密钥（可空=未开放 webhook）
 * @param status               状态（active/paused）
 * @param pausedReason         暂停原因（可空）
 * @param lastRunAt            最近一次触发时间
 * @param lastSessionId        最近一次触发新建的会话ID
 * @param lastStatus           最近一次触发执行结果状态
 * @param ownerId              归属用户 ID
 * @param archivedAt           归档时间（归档=archived_at+paused 双写）
 * @param createdAt            创建时间
 * @param updatedAt            更新时间
 * @param createdBy            创建人
 * @param updatedBy            更新人
 */
public record Deployment(
        Long id,
        String deploymentId,
        String name,
        String description,
        String agentId,
        int agentVersion,
        String environmentId,
        String environmentVariables,
        String resources,
        List<String> vaultIds,
        String initialEvents,
        String metadata,
        DeploymentSchedule schedule,
        OffsetDateTime nextRunAt,
        String webhookToken,
        DeploymentStatus status,
        String pausedReason,
        OffsetDateTime lastRunAt,
        String lastSessionId,
        String lastStatus,
        Long ownerId,
        OffsetDateTime archivedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        String createdBy,
        String updatedBy
) {

    /** 默认时区（全链路统一 Asia/Shanghai）。 */
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");

    /**
     * 紧凑构造器：不变量校验与半结构化字段归一。
     * <ul>
     *   <li>{@code deploymentId} / {@code name} / {@code agentId} / {@code agentVersion≥1}
     *       / {@code status} / {@code ownerId} 必填；</li>
     *   <li>JSON 透传字段空白归一为默认对象 / 数组文本；{@code vaultIds} null 归一空列表；</li>
     *   <li>{@code schedule} 为空时强制清空 {@code nextRunAt}（轮询领取依据仅对调度型有意义）。</li>
     * </ul>
     */
    public Deployment {
        if (StringUtils.isBlank(deploymentId)) {
            throw new IllegalArgumentException("调度器ID不能为空");
        }
        if (StringUtils.isBlank(name)) {
            throw new IllegalArgumentException("调度器名称不能为空");
        }
        // 名称长度与 deployment.name 列宽对齐（审查修复 F12：VARCHAR(64)，超长此前在 DB 层才报错）
        if (name.length() > 64) {
            throw new IllegalArgumentException("调度器名称不能超过64个字符");
        }
        description = StringUtils.trimToEmpty(description);
        if (description.length() > 500) {
            throw new IllegalArgumentException("调度器描述不能超过500个字符");
        }
        if (StringUtils.isBlank(agentId)) {
            throw new IllegalArgumentException("Agent ID不能为空");
        }
        if (agentVersion < 1) {
            throw new IllegalArgumentException("Agent 版本号必须大于0");
        }
        environmentVariables = StringUtils.defaultIfBlank(environmentVariables, "{}");
        resources = StringUtils.defaultIfBlank(resources, "[]");
        vaultIds = vaultIds == null ? List.of() : List.copyOf(vaultIds);
        initialEvents = StringUtils.defaultIfBlank(initialEvents, "[]");
        metadata = StringUtils.defaultIfBlank(metadata, "{}");
        if (schedule == null) {
            nextRunAt = null;
        }
        if (status == null) {
            throw new IllegalArgumentException("调度器状态不能为空");
        }
        if (ownerId == null) {
            throw new IllegalArgumentException("调度器归属用户不能为空");
        }
    }

    /** 定时部署业务 ID 前缀（shared/api-conventions：资源 ID 语义前缀，应用层创建时装配）。 */
    public static final String DEPLOYMENT_ID_PREFIX = "dep_";

    /**
     * 创建调度器（初始 {@code status=active}、未运行、未归档；
     * 有 schedule 时按创建时间物化下一次到期时间）。
     *
     * @param deploymentId         调度器业务ID
     * @param name                 调度器名称
     * @param description          描述（可空）
     * @param agentId              指向的 Agent 业务ID
     * @param agentVersion         创建时固定的 Agent 版本号（应用层完成省略版本 → 激活版本的解析）
     * @param environmentId        指向的 Environment（可空=默认环境）
     * @param environmentVariables 环境变量 JSON 文本（可空）
     * @param resources            挂载资源 JSON 文本（可空）
     * @param vaultIds             保管库业务ID列表（可空）
     * @param initialEvents        首批用户消息事件 JSON 文本（可空）
     * @param metadata             元数据 JSON 文本（可空）
     * @param schedule             调度配置（可空=仅手动/webhook 触发）
     * @param webhookToken         webhook 触发密钥（可空=未开放）
     * @param ownerId              归属用户
     * @return 新调度器
     */
    public static Deployment create(
            String deploymentId,
            String name,
            String description,
            String agentId,
            int agentVersion,
            String environmentId,
            String environmentVariables,
            String resources,
            List<String> vaultIds,
            String initialEvents,
            String metadata,
            DeploymentSchedule schedule,
            String webhookToken,
            Long ownerId
    ) {
        OffsetDateTime now = OffsetDateTime.now(DEFAULT_ZONE);
        return new Deployment(
                null, deploymentId, name, description, agentId, agentVersion, environmentId,
                environmentVariables, resources, vaultIds, initialEvents, metadata,
                schedule, schedule == null ? null : schedule.nextAfter(now),
                webhookToken, DeploymentStatus.ACTIVE, null,
                null, null, null, ownerId, null, now, now, null, null
        );
    }

    /**
     * 暂停调度器（{@code status=paused} + 记录原因；暂停期间不被轮询领取、不可手动触发）。
     *
     * @param reason 暂停原因（可空）
     * @return 暂停后的调度器（不可变派生）
     * @throws IllegalStateException 已归档调度器不可暂停
     */
    public Deployment pause(String reason) {
        if (archivedAt != null) {
            throw new IllegalStateException("已归档调度器不可暂停");
        }
        return copyWith(b -> b.status(DeploymentStatus.PAUSED).pausedReason(StringUtils.trimToNull(reason)));
    }

    /**
     * 恢复调度器（{@code status=active}，清空暂停原因；
     * 有 schedule 时到期时间按恢复时刻重算，不补触发暂停期间的欠账窗口）。
     *
     * @return 恢复后的调度器（不可变派生）
     * @throws IllegalStateException 已归档调度器不可恢复
     */
    public Deployment unpause() {
        if (archivedAt != null) {
            throw new IllegalStateException("已归档调度器不可恢复");
        }
        OffsetDateTime now = OffsetDateTime.now(DEFAULT_ZONE);
        return copyWith(b -> b.status(DeploymentStatus.ACTIVE).pausedReason(null)
                .nextRunAt(schedule == null ? null : schedule.nextAfter(now)));
    }

    /**
     * 归档调度器（{@code archived_at} + {@code status=paused} 双写，不再触发、列表默认不可见）。
     *
     * @return 归档后的调度器（不可变派生）
     */
    public Deployment archive() {
        OffsetDateTime now = OffsetDateTime.now(DEFAULT_ZONE);
        return copyWith(b -> b.archivedAt(now).status(DeploymentStatus.PAUSED));
    }

    /**
     * 推进到期时间（cron 触发完成后按本次触发时刻重算下一次 {@code nextRunAt}）。
     *
     * @param firedAt 本次触发时刻
     * @return 推进后的调度器；无 schedule 时原样返回
     */
    public Deployment advanceNextRun(OffsetDateTime firedAt) {
        if (schedule == null) {
            return this;
        }
        return copyWith(b -> b.nextRunAt(schedule.nextAfter(firedAt)));
    }

    /**
     * 是否已归档（{@code archived_at} 时间戳已置位）。
     */
    public boolean archived() {
        return archivedAt != null;
    }

    /**
     * 是否处于可触发状态（未归档且 active；手动 / webhook / 轮询共同前置）。
     */
    public boolean active() {
        return archivedAt == null && status == DeploymentStatus.ACTIVE;
    }

    /**
     * 是否可被调度轮询领取（可触发且携带调度配置与到期时间）。
     */
    public boolean schedulable() {
        return active() && schedule != null;
    }

    /**
     * 可调字段整体替换（merge-patch 更新，6.5 管理面）：应用层完成「缺省回填原值 /
     * 显式置 null 清空」的三态裁决后，以<b>终值</b>调用本方法做不可变派生。
     * <p>绑定关系不可调：{@code agentId / agentVersion}（创建时固定、触发不漂移）、
     * {@code webhookToken}（开通状态）、运行快照与归档时间均原样保留；
     * {@code nextRunAt} 由应用层按 schedule 是否变更显式传入（变更时重算、未变更时回填原值），
     * schedule 清空时构造器强制 {@code nextRunAt=null}。</p>
     *
     * @param name                 名称终值
     * @param description          描述终值（可空归一空串）
     * @param environmentId        Environment 终值（可空=默认环境）
     * @param environmentVariables 环境变量 JSON 文本终值
     * @param resources            挂载资源 JSON 文本终值
     * @param vaultIds             保管库 ID 列表终值
     * @param initialEvents        首批事件 JSON 文本终值
     * @param metadata             元数据 JSON 文本终值
     * @param schedule             调度配置终值（可空=仅手动/webhook 触发）
     * @param nextRunAt            到期时间终值（schedule 变更时按新表达式重算）
     * @return 替换可调字段后的调度器（不可变派生）
     */
    public Deployment withTunable(
            String name,
            String description,
            String environmentId,
            String environmentVariables,
            String resources,
            List<String> vaultIds,
            String initialEvents,
            String metadata,
            DeploymentSchedule schedule,
            OffsetDateTime nextRunAt
    ) {
        OffsetDateTime now = OffsetDateTime.now(DEFAULT_ZONE);
        return new Deployment(
                id, deploymentId, name, description, agentId, agentVersion, environmentId,
                environmentVariables, resources, vaultIds, initialEvents, metadata,
                schedule, nextRunAt, webhookToken, status, pausedReason,
                lastRunAt, lastSessionId, lastStatus, ownerId, archivedAt, createdAt, now,
                createdBy, updatedBy
        );
    }

    /**
     * 不可变派生小工具：以本实例为基复制并按需覆盖字段（record 全量重建样板收敛）。
     */
    private Deployment copyWith(java.util.function.Consumer<Builder> mutator) {
        Builder builder = new Builder();
        mutator.accept(builder);
        return new Deployment(
                id, deploymentId, name, description, agentId, agentVersion, environmentId,
                environmentVariables, resources, vaultIds, initialEvents, metadata, schedule,
                builder.nextRunAtChanged ? builder.nextRunAt : nextRunAt,
                webhookToken,
                builder.status == null ? status : builder.status,
                builder.pausedReasonChanged ? builder.pausedReason : pausedReason,
                builder.lastRunAtChanged ? builder.lastRunAt : lastRunAt,
                builder.lastSessionIdChanged ? builder.lastSessionId : lastSessionId,
                builder.lastStatusChanged ? builder.lastStatus : lastStatus,
                ownerId,
                builder.archivedAtChanged ? builder.archivedAt : archivedAt,
                builder.createdAtChanged ? builder.createdAt : createdAt,
                builder.updatedAtChanged ? builder.updatedAt : updatedAt,
                createdBy, updatedBy
        );
    }

    /**
     * 派生字段覆盖构建器（仅服务于 {@link #copyWith}，非公开装配入口）。
     */
    private static final class Builder {
        private DeploymentStatus status;
        private String pausedReason;
        private boolean pausedReasonChanged;
        private OffsetDateTime nextRunAt;
        private boolean nextRunAtChanged;
        private OffsetDateTime lastRunAt;
        private boolean lastRunAtChanged;
        private String lastSessionId;
        private boolean lastSessionIdChanged;
        private String lastStatus;
        private boolean lastStatusChanged;
        private OffsetDateTime archivedAt;
        private boolean archivedAtChanged;
        private OffsetDateTime createdAt;
        private boolean createdAtChanged;
        private OffsetDateTime updatedAt;
        private boolean updatedAtChanged;

        Builder status(DeploymentStatus value) {
            this.status = value;
            return this;
        }

        Builder pausedReason(String value) {
            this.pausedReason = value;
            this.pausedReasonChanged = true;
            return this;
        }

        Builder nextRunAt(OffsetDateTime value) {
            this.nextRunAt = value;
            this.nextRunAtChanged = true;
            return this;
        }

        Builder lastRunAt(OffsetDateTime value) {
            this.lastRunAt = value;
            this.lastRunAtChanged = true;
            return this;
        }

        Builder lastSessionId(String value) {
            this.lastSessionId = value;
            this.lastSessionIdChanged = true;
            return this;
        }

        Builder lastStatus(String value) {
            this.lastStatus = value;
            this.lastStatusChanged = true;
            return this;
        }

        Builder archivedAt(OffsetDateTime value) {
            this.archivedAt = value;
            this.archivedAtChanged = true;
            return this;
        }

        Builder createdAt(OffsetDateTime value) {
            this.createdAt = value;
            this.createdAtChanged = true;
            return this;
        }

        Builder updatedAt(OffsetDateTime value) {
            this.updatedAt = value;
            this.updatedAtChanged = true;
            return this;
        }
    }
}
