package com.linkroa.deepdataagent.runtime.domain.model;

import com.linkroa.deepdataagent.runtime.domain.model.enums.AgentSessionStatus;
import com.linkroa.deepdataagent.runtime.domain.model.enums.TurnPhase;
import com.linkroa.deepdataagent.runtime.domain.service.SessionMountPolicy;
import org.apache.commons.lang3.StringUtils;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Agent 会话领域模型（对应 agent_session 表，双列模型）。
 * <p>对外会话级 {@code status} 四态（{@code idle / running / rescheduling / terminated}，
 * 见 {@link AgentSessionStatus}）；内部轮次相位 {@code turnPhase} 四态
 * （{@code idle / running / awaiting_confirmation / cancelling}，见 {@link TurnPhase}）。
 * 相位 MAY NOT 出现在任何对外响应或 {@code session.status_*} 事件中——装配层只读 status 列。
 * 同一会话同一时刻仅允许一个执行，由仓储 {@code transition(sessionId, Transition.BEGIN_TURN)}
 * 原子 CAS 保证。新会话初始为 {@code idle / idle}。</p>
 * <p><b>归档是正交维度</b>：{@code archivedAt} 时间戳独立于 {@code status}，
 * 可与任意状态组合（如终止后归档）。归档不经状态机迁移，只写时间戳列。</p>
 * <p>挂载模型：{@code environmentId}（执行环境）、{@code vaultIds}（保管库）、
 * {@code memoryStoreIds}（记忆库）、{@code environmentVariables}（会话级环境变量 JSON 文本，
 * 仅入参接受、永不在响应中回显）、{@code resources}（挂载资源引用，见 {@link SessionResource}）。</p>
 * <p>触发打标：{@code triggerType} / {@code triggerId} 承载「由调度器触发新建」的溯源标记
 * （{@code null}=普通用户会话），触发即新建全新会话、绝不复用。</p>
 */
public record AgentSession(
        Long id,
        String sessionId,
        String userId,
        String agentId,
        String agentVersion,
        AgentSessionStatus status,
        TurnPhase turnPhase,
        String metadata,
        String title,
        String environmentId,
        List<String> vaultIds,
        List<String> memoryStoreIds,
        String environmentVariables,
        List<SessionResource> resources,
        String triggerType,
        String triggerId,
        OffsetDateTime lastActiveAt,
        OffsetDateTime archivedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        String createdBy,
        String updatedBy
) {

    /** 会话业务 ID 前缀（shared/api-conventions：资源 ID 语义前缀，create 时装配）。 */
    public static final String SESSION_ID_PREFIX = "sess_";

    public AgentSession {
        if (StringUtils.isBlank(sessionId)) {
            throw new IllegalArgumentException("会话ID不能为空");
        }
        if (StringUtils.isBlank(userId)) {
            throw new IllegalArgumentException("用户ID不能为空");
        }
        if (StringUtils.isBlank(agentId)) {
            throw new IllegalArgumentException("AgentID不能为空");
        }
        if (StringUtils.isBlank(agentVersion)) {
            throw new IllegalArgumentException("Agent版本不能为空");
        }
        if (status == null) {
            throw new IllegalArgumentException("会话状态不能为空");
        }
        if (turnPhase == null) {
            throw new IllegalArgumentException("轮次相位不能为空");
        }
        if (title != null && title.length() > 255) {
            throw new IllegalArgumentException("会话标题长度不能超过255");
        }
        if (metadata == null) {
            throw new IllegalArgumentException("会话元数据不能为空");
        }
        // 触发标记：来源类型与来源调度器ID成对出现（仅调度器触发会话可带）
        if (StringUtils.isNotBlank(triggerId) && StringUtils.isBlank(triggerType)) {
            throw new IllegalArgumentException("触发来源ID存在时必须携带触发类型");
        }
        // 挂载列表归一：null 收敛为空列表、防御性复制
        vaultIds = vaultIds == null ? List.of() : List.copyOf(vaultIds);
        memoryStoreIds = memoryStoreIds == null ? List.of() : List.copyOf(memoryStoreIds);
        // 会话级环境变量：null 收敛为空 JSON 对象文本
        environmentVariables = StringUtils.isBlank(environmentVariables) ? "{}" : environmentVariables;
        // 挂载资源归一：null 收敛为空列表、防御性复制（元素自身经 SessionResource 紧凑构造器校验）
        resources = resources == null ? List.of() : List.copyOf(resources);
    }

    /**
     * 创建新会话（普通用户创建，无触发标记、无挂载资源；初始 {@code idle / idle}）。
     */
    public static AgentSession create(
            String userId,
            String agentId,
            String agentVersion,
            String metadata,
            String title
    ) {
        return createWithTrigger(userId, agentId, agentVersion, metadata, title, null, null, List.of());
    }

    /**
     * 创建新会话并打标触发来源（调度器触发专用：新建即打标，可追溯触发调度器与来源类型）。
     *
     * @param triggerType 触发来源类型（manual / webhook / cron，源码小写）
     * @param triggerId   触发来源调度器业务ID（deployment_id）
     */
    public static AgentSession createWithTrigger(
            String userId,
            String agentId,
            String agentVersion,
            String metadata,
            String title,
            String triggerType,
            String triggerId
    ) {
        return createWithTrigger(userId, agentId, agentVersion, metadata, title, triggerType, triggerId, List.of());
    }

    /**
     * 创建新会话并打标触发来源（携带挂载资源，普通用户创建时触发标记为 null；未挂接环境 / 保管库）。
     *
     * @param triggerType 触发来源类型（manual / webhook / cron，源码小写；可空=普通用户会话）
     * @param triggerId   触发来源调度器业务ID（deployment_id；triggerType 存在时必填）
     * @param resources   会话挂载资源引用（可空，经紧凑构造器归一为空列表）
     */
    public static AgentSession createWithTrigger(
            String userId,
            String agentId,
            String agentVersion,
            String metadata,
            String title,
            String triggerType,
            String triggerId,
            List<SessionResource> resources
    ) {
        return createWithMounts(userId, agentId, agentVersion, metadata, title,
                triggerType, triggerId, resources, null, List.of(), null);
    }

    /**
     * 创建新会话并挂接全量挂载字段（Session 创建标准形状）。
     * <p>{@code memoryStoreIds} 由挂载资源派生：收集 {@code type=memory_store} 项的
     * {@code memoryStoreId}（保序去重），不作为独立入参——记忆库关联只经 resources 表达。</p>
     *
     * @param triggerType          触发来源类型（manual / webhook / cron，源码小写；可空=普通用户会话）
     * @param triggerId            触发来源调度器业务ID（deployment_id；triggerType 存在时必填）
     * @param resources            会话挂载资源引用（可空，经紧凑构造器归一为空列表）
     * @param environmentId        运行环境业务ID（可空）
     * @param vaultIds             保管库业务ID列表（可空，归一为空列表）
     * @param environmentVariables 会话级环境变量 JSON 文本（可空，空白归一为 {@code "{}"}）
     */
    public static AgentSession createWithMounts(
            String userId,
            String agentId,
            String agentVersion,
            String metadata,
            String title,
            String triggerType,
            String triggerId,
            List<SessionResource> resources,
            String environmentId,
            List<String> vaultIds,
            String environmentVariables
    ) {
        OffsetDateTime now = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
        List<SessionResource> normalizedResources = resources == null ? List.of() : resources;
        // 关联记忆库派生：挂载资源中 memory_store 项的 ID 集合（保序去重）
        List<String> derivedMemoryStoreIds = normalizedResources.stream()
                .filter(resource -> SessionResource.MEMORY_STORE_TYPE.equals(resource.type()))
                .map(SessionResource::memoryStoreId)
                .distinct()
                .toList();
        return new AgentSession(
                null,
                SESSION_ID_PREFIX + UUID.randomUUID().toString().replace("-", ""),
                userId,
                agentId,
                agentVersion,
                AgentSessionStatus.IDLE,
                TurnPhase.IDLE,
                metadata == null || metadata.isBlank() ? "{}" : metadata,
                title,
                environmentId,
                vaultIds,
                derivedMemoryStoreIds,
                environmentVariables,
                normalizedResources,
                triggerType,
                triggerId,
                now,
                null,
                now,
                now,
                null,
                null
        );
    }

    /**
     * 从数据库恢复（查询场景）。
     */
    public static AgentSession restore(
            Long id,
            String sessionId,
            String userId,
            String agentId,
            String agentVersion,
            AgentSessionStatus status,
            TurnPhase turnPhase,
            String metadata,
            String title,
            String environmentId,
            List<String> vaultIds,
            List<String> memoryStoreIds,
            String environmentVariables,
            List<SessionResource> resources,
            String triggerType,
            String triggerId,
            OffsetDateTime lastActiveAt,
            OffsetDateTime archivedAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            String createdBy,
            String updatedBy
    ) {
        return new AgentSession(
                id, sessionId, userId, agentId, agentVersion, status,
                turnPhase == null ? TurnPhase.IDLE : turnPhase,
                metadata == null ? "{}" : metadata,
                title, environmentId, vaultIds, memoryStoreIds, environmentVariables, resources,
                triggerType, triggerId, lastActiveAt, archivedAt,
                createdAt, updatedAt, createdBy, updatedBy
        );
    }

    /**
     * 派生指定对外状态的会话（CAS 后用于回填内存视图：{@code last_active_at} 同步刷新；
     * 内部相位保持原值，挂载字段原样透传）。
     * <p>相位与对外状态是两列独立事实——调用方若同时迁移了相位，应显式传相位或用
     * {@link #withPhase(TurnPhase)}。</p>
     */
    public AgentSession withStatus(AgentSessionStatus nextStatus) {
        OffsetDateTime now = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
        return new AgentSession(
                id, sessionId, userId, agentId, agentVersion, nextStatus, turnPhase,
                metadata, title, environmentId, vaultIds, memoryStoreIds, environmentVariables,
                resources, triggerType, triggerId, now, archivedAt, createdAt,
                now, createdBy, updatedBy
        );
    }

    /**
     * 派生指定内部相位的会话（相位专用迁移后回填内存视图，对外状态保持原值）。
     *
     * @param nextPhase 目标内部相位（非空）
     * @return 相位迁移后的会话
     */
    public AgentSession withPhase(TurnPhase nextPhase) {
        OffsetDateTime now = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
        return new AgentSession(
                id, sessionId, userId, agentId, agentVersion, status, nextPhase,
                metadata, title, environmentId, vaultIds, memoryStoreIds, environmentVariables,
                resources, triggerType, triggerId, lastActiveAt, archivedAt, createdAt,
                now, createdBy, updatedBy
        );
    }

    /**
     * 归档会话：仅置 {@code archived_at} 时间戳，{@code status} 与内部相位保持原值。
     * <p>归档是独立正交维度（可与 terminated 等任意状态组合），且 MUST NOT 产生归档状态事件。
     * 运行中的会话应先按取消语义中止收敛后再归档（应用层守卫）。</p>
     *
     * @return 归档后的会话（不可变派生）
     */
    public AgentSession withArchived() {
        OffsetDateTime now = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
        return new AgentSession(
                id, sessionId, userId, agentId, agentVersion, status, turnPhase,
                metadata, title, environmentId, vaultIds, memoryStoreIds, environmentVariables,
                resources, triggerType, triggerId, now, now, createdAt, now,
                createdBy, updatedBy
        );
    }

    /**
     * 派生追加挂载资源后的会话（创建后追加挂载专用，不可变派生）。
     * <p>追加项按序拼接至现有 {@code resources} 尾部；{@code memoryStoreIds} 保持原值——
     * 追加路径仅允许 file 类型资源（应用层守卫），记忆库关联不变。
     * {@code updated_at} 刷新为当前时刻（即挂载时刻）。</p>
     *
     * @param additional 追加的挂载资源项（可空=不追加）
     * @return 追加后的会话
     */
    public AgentSession withAppendedResources(List<SessionResource> additional) {
        OffsetDateTime now = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
        List<SessionResource> merged = new ArrayList<>(resources);
        if (additional != null) {
            merged.addAll(additional);
        }
        return new AgentSession(
                id, sessionId, userId, agentId, agentVersion, status, turnPhase,
                metadata, title, environmentId, vaultIds, memoryStoreIds, environmentVariables,
                List.copyOf(merged), triggerType, triggerId, lastActiveAt, archivedAt,
                createdAt, now, createdBy, updatedBy
        );
    }

    /**
     * 派生整体替换挂载资源后的会话（资源轮换 / 移除专用，不可变派生）。
     * <p>替换集合整体覆盖 {@code resources}，并复用创建路径的派生规则重算
     * {@code memoryStoreIds}（收集 {@code type=memory_store} 项的 memoryStoreId，
     * 保序去重）——记忆库挂载被移除时关联随之消失。
     * {@code updated_at} 刷新为当前时刻。</p>
     *
     * @param replacement 替换后的全量挂载资源列表（可空=清空挂载）
     * @return 替换后的会话
     */
    public AgentSession withResourcesReplaced(List<SessionResource> replacement) {
        OffsetDateTime now = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
        List<SessionResource> normalized = replacement == null ? List.of() : List.copyOf(replacement);
        // 与 createWithMounts 同规则：记忆库关联只经 resources 派生表达
        List<String> derivedMemoryStoreIds = normalized.stream()
                .filter(resource -> SessionResource.MEMORY_STORE_TYPE.equals(resource.type()))
                .map(SessionResource::memoryStoreId)
                .distinct()
                .toList();
        return new AgentSession(
                id, sessionId, userId, agentId, agentVersion, status, turnPhase,
                metadata, title, environmentId, vaultIds, derivedMemoryStoreIds, environmentVariables,
                normalized, triggerType, triggerId, lastActiveAt, archivedAt,
                createdAt, now, createdBy, updatedBy
        );
    }

    /**
     * 是否处于归档态（{@code archived_at} 时间戳已填充；归档与 {@code status} 正交）。
     *
     * @return true=已归档
     */
    public boolean archived() {
        return archivedAt != null;
    }

    // ==================== 挂载行为（规则权威在 SessionMountPolicy，聚合保持纯内存） ====================

    /**
     * 追加文件挂载（契约端点 {@code POST /sessions/{id}/resources} 的领域动作）。
     * <p>聚合不触达仓储与跨 BC 端口：文件字节数由应用层经文件服务契约材料化后作入参传入，
     * 聚合内只做 {@code fileId} 判重、挂载路径占用、就绪结论与 500MB 阈值比较
     * （规则见 {@link SessionMountPolicy#validateAppend}）。</p>
     *
     * @param additions          待追加的挂载资源（本期仅 {@code file} 类型）
     * @param sizeBytesByFileId  已材料化的字节清单（既有 + 新增一次取齐；缺键 = 无就绪元数据）
     * @return 追加后的会话（不可变派生）
     * @throws MountViolationException 类型非法 / 判重 / 路径占用 / 文件不可挂载 / 总量超限
     */
    public AgentSession appendFiles(List<SessionResource> additions, Map<String, Long> sizeBytesByFileId) {
        SessionMountPolicy.validateAppend(resources, additions, sizeBytesByFileId);
        return withAppendedResources(additions);
    }

    /**
     * 移除挂载资源（资源管理契约 {@code delete} 的领域动作）：仅 {@code file} 类型可移除，
     * {@code github_repository / memory_store} 创建后不可摘除。
     *
     * @param resourceId 待移除的挂载资源业务 ID（{@code sesr_}）
     * @return 移除后的会话（不可变派生，记忆库关联按替换规则重算）
     * @throws MountViolationException 资源未命中 / 非 file 类型
     */
    public AgentSession removeResource(String resourceId) {
        SessionResource target = mountedOrThrow(resourceId);
        if (!SessionResource.FILE_TYPE.equals(target.type())) {
            throw MountViolationException.removeTypeUnsupported(target.type());
        }
        return withResourcesReplaced(resources.stream()
                .filter(resource -> !resource.id().equals(resourceId))
                .toList());
    }

    /**
     * 轮换 GitHub 仓库挂载令牌（资源管理契约 {@code update} 的领域动作）。
     * <p>判定顺序即状态码优先级：空令牌（400）先于资源寻址（404）先于类型门禁（400）。
     * 新旧令牌均不经由日志 / 响应泄露（响应装配层剔除令牌字段）。</p>
     *
     * @param resourceId 目标挂载资源业务 ID（{@code sesr_}）
     * @param newToken   新访问令牌（非空）
     * @return 轮换后的会话（不可变派生，资源 ID 与其余字段保持不变）
     * @throws MountViolationException 令牌为空 / 资源未命中 / 非 github_repository 类型
     */
    public AgentSession rotateResourceToken(String resourceId, String newToken) {
        if (StringUtils.isBlank(newToken)) {
            throw MountViolationException.blankToken();
        }
        SessionResource target = mountedOrThrow(resourceId);
        if (!SessionResource.GITHUB_REPO_TYPE.equals(target.type())) {
            throw MountViolationException.rotateTypeUnsupported(target.type());
        }
        SessionResource rotated = target.withAuthorizationToken(newToken);
        return withResourcesReplaced(resources.stream()
                .map(resource -> resource.id().equals(resourceId) ? rotated : resource)
                .toList());
    }

    /**
     * 按资源业务 ID 定位挂载项（协议面寻址 / 回显用；未命中 {@code empty}，
     * 状态码由调用方按语义映射——写操作面为 404，与会话详情同口径）。
     *
     * @param resourceId 挂载资源业务 ID（{@code sesr_}）
     * @return 挂载资源值对象
     */
    public Optional<SessionResource> findResource(String resourceId) {
        return resources.stream()
                .filter(resource -> resource.id().equals(resourceId))
                .findFirst();
    }

    /** 定位挂载项，未命中抛资源未命中冲突（聚合内变更方法的统一寻址门禁）。 */
    private SessionResource mountedOrThrow(String resourceId) {
        return findResource(resourceId).orElseThrow(() -> MountViolationException.mountedResourceNotFound(resourceId));
    }

    /**
     * 是否处于可接收新消息的可运行态（{@code status=idle}，CAS 抢占的候选状态）。
     * <p>running（含内部等待/取消相位）与 rescheduling 期间提交 user.message 一律拒绝
     * （409 invalid_request_error，不排队）。</p>
     *
     * @return true=可接收新消息并执行 turn
     */
    public boolean runnable() {
        return status == AgentSessionStatus.IDLE;
    }

    /**
     * 是否存在活跃执行（会话被一轮执行占用）：判定口径为内部相位
     * {@code turnPhase ∈ running / awaiting_confirmation / cancelling}（见
     * {@link TurnPhase#active()}）。活跃执行期间新用户消息提交须拒绝
     * （409 invalid_request_error，单会话同时仅允许一个活跃执行）。
     *
     * @return true=存在活跃执行
     */
    public boolean hasActiveExecution() {
        return turnPhase.active();
    }

    /**
     * 是否归属指定用户（owner 隔离单点谓词：命令 / 查询两侧的越权校验统一收敛至此）。
     * <p>会话 {@code userId} 落库存的是数字 user_id 的字符串形态（审计同口径），
     * 故按 {@code String.valueOf(ownerId)} 等值比对；越权与不存在对调用方不可区分
     * （统一 404 语义由应用层映射）。</p>
     *
     * @param ownerId 当前认证用户数字 ID
     * @return true=归属该用户
     */
    public boolean ownedBy(long ownerId) {
        return String.valueOf(ownerId).equals(userId);
    }

    /**
     * 是否仍可变更挂载资源（已归档与已终止会话挂载冻结；其余状态可追加 / 移除 / 轮换）。
     *
     * @return true=可变更挂载
     */
    public boolean mutable() {
        return !archived() && status != AgentSessionStatus.TERMINATED;
    }
}
