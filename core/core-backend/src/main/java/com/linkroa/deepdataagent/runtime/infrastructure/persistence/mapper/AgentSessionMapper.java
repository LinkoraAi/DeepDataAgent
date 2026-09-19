package com.linkroa.deepdataagent.runtime.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linkroa.deepdataagent.runtime.domain.model.SessionListFilter;
import com.linkroa.deepdataagent.runtime.domain.model.Transition;
import com.linkroa.deepdataagent.runtime.domain.model.enums.AgentSessionStatus;
import com.linkroa.deepdataagent.runtime.domain.model.enums.TurnPhase;
import com.linkroa.deepdataagent.runtime.infrastructure.persistence.entity.AgentSessionEntity;
import org.apache.commons.lang3.StringUtils;
import org.apache.ibatis.annotations.Mapper;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * Agent 会话 Mapper。
 * <p>此处以 {@code @SuppressWarnings("null")} 压制 JDT 空指针静态分析对 MyBatis-Plus
 * {@code SFunction} 方法引用（{@code Entity::getXxx}）的误报：该写法是 MP lambda 包装器
 * 解析列名的标准形式（改写为普通 lambda 会导致列名解析失败），运行时与 null 语义无关。</p>
 * <p>双列模型：对外 {@code status} 存小写规范值（{@code idle / running / rescheduling / terminated}），
 * 内部相位 {@code turn_phase} 存小写规范值（{@code idle / running / awaiting_confirmation /
 * cancelling}），见领域枚举 {@link AgentSessionStatus} / {@link TurnPhase}。
 * <b>状态迁移的唯一 CAS 落点是 {@link #transition(String, Transition)}</b>——按迁移声明
 * 选择性写入 {@code status} / {@code turn_phase} 单列或双列，前置条件同样由声明生成；
 * 归档移出状态机，唯一落点为 {@link #archive(String)}（仅写 {@code archived_at}）。</p>
 */
@SuppressWarnings("null")
@Mapper
public interface AgentSessionMapper extends BaseMapper<AgentSessionEntity> {

    default AgentSessionEntity findBySessionId(String sessionId) {
        return selectOne(Wrappers.<AgentSessionEntity>lambdaQuery()
                .eq(AgentSessionEntity::getSessionId, sessionId)
                .last("LIMIT 1"));
    }

    /**
     * 游标分页查询会话（公开契约 GET 列表过滤集合，创建时间 keyset 行值比较）。
     * <p>过滤：agent_id / agent_version / deployment_id（落 {@code trigger_id} 列）/
     * memory_store_id（{@code memory_store_ids} JSONB {@code @>} 包含）/ statuses /
     * created_at 四边界；归档排除改由 {@code archived_at IS NULL} 单列承载
     * （{@code includeArchived=false} 时附加，与 statuses 无耦合）。</p>
     * <p>方向：{@code reverse=false} 时按 {@code ascending} 排序；{@code reverse=true}
     * （before_id 方向）取更新侧故排序方向取反，应用层读后翻转回请求方向。</p>
     */
    default List<AgentSessionEntity> selectByCursor(String userId, SessionListFilter filter, int limit) {
        List<String> statuses = filter.statuses().stream().map(AgentSessionStatus::value).toList();
        LambdaQueryWrapper<AgentSessionEntity> wrapper = Wrappers.<AgentSessionEntity>lambdaQuery()
                .eq(AgentSessionEntity::getUserId, userId)
                .eq(StringUtils.isNotBlank(filter.agentId()), AgentSessionEntity::getAgentId, filter.agentId())
                .eq(StringUtils.isNotBlank(filter.agentVersion()),
                        AgentSessionEntity::getAgentVersion, filter.agentVersion())
                .eq(StringUtils.isNotBlank(filter.deploymentId()),
                        AgentSessionEntity::getTriggerId, filter.deploymentId())
                .in(!statuses.isEmpty(), AgentSessionEntity::getStatus, statuses)
                .apply(StringUtils.isNotBlank(filter.memoryStoreId()),
                        "memory_store_ids @> cast({0} as jsonb)", "[\"" + filter.memoryStoreId() + "\"]")
                .apply(StringUtils.isNotBlank(filter.metadataJson()),
                        "metadata @> cast({0} as jsonb)", filter.metadataJson());
        // 归档排除：archived_at 为null 即未归档（归档已从状态值域移出，不再按 status 判定）
        if (!filter.includeArchived()) {
            wrapper.isNull(AgentSessionEntity::getArchivedAt);
        }
        wrapper.gt(filter.createdAtGt() != null, AgentSessionEntity::getCreatedAt, filter.createdAtGt())
                .ge(filter.createdAtGte() != null, AgentSessionEntity::getCreatedAt, filter.createdAtGte())
                .lt(filter.createdAtLt() != null, AgentSessionEntity::getCreatedAt, filter.createdAtLt())
                .le(filter.createdAtLte() != null, AgentSessionEntity::getCreatedAt, filter.createdAtLte());
        if (filter.cursorCreatedAt() != null) {
            // 请求方向：ascending 为请求排序方向，reverse 为 before_id 取数方向（翻转到更新侧读取）
            boolean readAscending = filter.ascending() ^ filter.reverse();
            wrapper.apply(readAscending, "(created_at, id) > ({0}, {1})",
                    filter.cursorCreatedAt(), filter.cursorRowId());
            wrapper.apply(!readAscending, "(created_at, id) < ({0}, {1})",
                    filter.cursorCreatedAt(), filter.cursorRowId());
        }
        boolean readAscending = filter.ascending() ^ filter.reverse();
        if (readAscending) {
            wrapper.orderByAsc(AgentSessionEntity::getCreatedAt).orderByAsc(AgentSessionEntity::getId);
        } else {
            wrapper.orderByDesc(AgentSessionEntity::getCreatedAt).orderByDesc(AgentSessionEntity::getId);
        }
        return selectList(wrapper.last("LIMIT " + limit));
    }

    /**
     * 状态机迁移的唯一 CAS 落点：按 {@link Transition} 声明生成窄列 guarded UPDATE。
     * <p><b>选择性写列</b>：{@code transition.touchesStatus()} 为真才 {@code SET status}，
     * {@code transition.touchesPhase()} 为真才 {@code SET turn_phase}（相位专用迁移只动相位列，
     * rescheduling 预留迁移只动状态列）。{@code updated_at} 恒刷新。</p>
     * <p><b>选择性守卫</b>：{@code guardsStatus()} 为真才拼 {@code status IN (...)}，
     * {@code guardsPhase()} 为真才拼 {@code turn_phase IN (...)}；空集表示该维度不设条件。</p>
     * <p>红线：列引用一律 {@code Entity::getX} 方法引用，MUST NOT 改写为 lambda 表达式。</p>
     *
     * @param sessionId  会话 ID
     * @param transition 迁移声明（来自 {@code SessionStateMachine} 的命名常量）
     * @return 受影响行数（1=迁移成功；0=前置条件不匹配，调用方据此判定 CAS miss）
     */
    default int transition(String sessionId, Transition transition) {
        if (transition == null) {
            throw new IllegalArgumentException("状态迁移声明不能为空");
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
        LambdaUpdateWrapper<AgentSessionEntity> wrapper = Wrappers.<AgentSessionEntity>lambdaUpdate()
                .set(transition.touchesStatus(), AgentSessionEntity::getStatus,
                        transition.to() == null ? null : transition.to().value())
                .set(transition.touchesPhase(), AgentSessionEntity::getTurnPhase,
                        transition.toPhase() == null ? null : transition.toPhase().value())
                .set(AgentSessionEntity::getUpdatedAt, now)
                .eq(AgentSessionEntity::getSessionId, sessionId);
        if (transition.guardsStatus()) {
            wrapper.in(AgentSessionEntity::getStatus,
                    transition.statusFrom().stream().map(AgentSessionStatus::value).toList());
        }
        if (transition.guardsPhase()) {
            wrapper.in(AgentSessionEntity::getTurnPhase,
                    transition.phaseFrom().stream().map(TurnPhase::value).toList());
        }
        return update(null, wrapper);
    }

    /**
     * 归档会话（唯一归档落点）：仅写 {@code archived_at} + {@code updated_at}，
     * <b>不改写 status / turn_phase</b>。守卫 {@code archived_at IS NULL}——并发归档时仅一方命中
     * （另一方影响 0 行，调用方据此判定已归档 / 竞态）。
     *
     * @param sessionId 会话 ID
     * @return 受影响行数（1=归档成功；0=已归档或不存在）
     */
    default int archive(String sessionId) {
        OffsetDateTime now = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
        return update(null, Wrappers.<AgentSessionEntity>lambdaUpdate()
                .set(AgentSessionEntity::getArchivedAt, now)
                .set(AgentSessionEntity::getUpdatedAt, now)
                .eq(AgentSessionEntity::getSessionId, sessionId)
                .isNull(AgentSessionEntity::getArchivedAt));
    }

    /**
     * 会话当前是否处于取消中相位（只读布尔查询：终态判定谓词的持久取消痕迹）。
     *
     * @param sessionId 会话 ID
     * @return true=内部相位当前为 cancelling
     */
    default boolean isCancelling(String sessionId) {
        return selectCount(Wrappers.<AgentSessionEntity>lambdaQuery()
                .eq(AgentSessionEntity::getSessionId, sessionId)
                .eq(AgentSessionEntity::getTurnPhase, TurnPhase.CANCELLING.value())) > 0;
    }

    /**
     * 当前状态窄读（仅取 {@code status} 单列，不做任何写入）：终态决策表判定
     * 「显式指令胜出（已终止）」一行用。
     *
     * @param sessionId 会话 ID
     * @return 状态原始取值（会话不存在 / 已逻辑删除时为 null）
     */
    default String selectStatusValue(String sessionId) {
        return selectList(Wrappers.<AgentSessionEntity>lambdaQuery()
                        .select(AgentSessionEntity::getStatus)
                        .eq(AgentSessionEntity::getSessionId, sessionId)
                        .last("LIMIT 1"))
                .stream()
                .findFirst()
                .map(AgentSessionEntity::getStatus)
                .orElse(null);
    }

    /**
     * 更新会话可变属性（title / metadata / environment_variables）。
     * <p>{@code titlePresent=true} 时无条件写 title 列（可为 null 清空）；metadata /
     * environment_variables 传 null 的列不更新（「缺省不覆盖」由条件 set 承载）。</p>
     */
    default int updateProfile(String sessionId, String title, boolean titlePresent,
                              String metadata, String environmentVariables) {
        return update(null, Wrappers.<AgentSessionEntity>lambdaUpdate()
                .set(titlePresent, AgentSessionEntity::getTitle, title)
                .set(metadata != null, AgentSessionEntity::getMetadata, metadata)
                .set(environmentVariables != null, AgentSessionEntity::getEnvironmentVariables, environmentVariables)
                .set(AgentSessionEntity::getUpdatedAt, OffsetDateTime.now(ZoneId.of("Asia/Shanghai")))
                .eq(AgentSessionEntity::getSessionId, sessionId));
    }

    /**
     * 删除会话（公开契约 delete 面；BaseEntity {@code @TableLogic} 下实为逻辑删除 is_deleted=1）。
     */
    default int deleteBySessionId(String sessionId) {
        return delete(Wrappers.<AgentSessionEntity>lambdaQuery()
                .eq(AgentSessionEntity::getSessionId, sessionId));
    }

    /**
     * 覆盖更新挂载资源 jsonb 文本（创建后追加挂载，仅改 resources 列）。
     *
     * @param sessionId     会话 ID
     * @param resourcesJson 合并后的资源列表 JSON 文本（snake_case 键，含 sesr_ 资源 ID）
     * @return 受影响行数（1=成功）
     */
    default int updateResources(String sessionId, String resourcesJson) {
        return update(null, Wrappers.<AgentSessionEntity>lambdaUpdate()
                .set(AgentSessionEntity::getResources, resourcesJson)
                .set(AgentSessionEntity::getUpdatedAt, OffsetDateTime.now(ZoneId.of("Asia/Shanghai")))
                .eq(AgentSessionEntity::getSessionId, sessionId));
    }

    default int touchLastActive(String sessionId) {
        return update(null, Wrappers.<AgentSessionEntity>lambdaUpdate()
                .set(AgentSessionEntity::getLastActiveAt, OffsetDateTime.now(ZoneId.of("Asia/Shanghai")))
                .set(AgentSessionEntity::getUpdatedAt, OffsetDateTime.now(ZoneId.of("Asia/Shanghai")))
                .eq(AgentSessionEntity::getSessionId, sessionId));
    }

    /**
     * 查询存在活跃执行的会话 ID（启动恢复用）：对外 {@code status ∈ (running, rescheduling)}
     * 且内部 {@code turn_phase ∈ (running, cancelling)}。
     * <p>{@code rescheduling} 本期无生产者，纳入查询以便存量行按孤儿执行一并复位；
     * {@code turn_phase=awaiting_confirmation} <b>不在</b>命中范围——durable HITL 等待跨重启保留。</p>
     */
    default List<String> findActiveExecutionSessionIds() {
        return selectList(Wrappers.<AgentSessionEntity>lambdaQuery()
                        .select(AgentSessionEntity::getSessionId)
                        .in(AgentSessionEntity::getStatus,
                                AgentSessionStatus.RUNNING.value(), AgentSessionStatus.RESCHEDULING.value())
                        .in(AgentSessionEntity::getTurnPhase,
                                TurnPhase.RUNNING.value(), TurnPhase.CANCELLING.value()))
                .stream()
                .map(AgentSessionEntity::getSessionId)
                .toList();
    }

    /**
     * 统计仍引用指定执行环境的未删除会话数（环境删除引用校验）。
     *
     * @param environmentId 环境业务 ID
     * @return 引用数（0 表示无会话挂载，可安全删除）
     */
    default Long countByEnvironmentId(String environmentId) {
        return selectCount(Wrappers.<AgentSessionEntity>lambdaQuery()
                .eq(AgentSessionEntity::getEnvironmentId, environmentId));
    }

    /**
     * 统计仍挂载指定记忆库的未删除会话数（memory_store_ids JSONB 包含查询）。
     *
     * @param storeId 记忆库业务 ID（ms_ 前缀）
     * @return 引用数（0 表示无会话挂载，可安全删除）
     */
    default Long countByMemoryStoreId(String storeId) {
        return selectCount(Wrappers.<AgentSessionEntity>lambdaQuery()
                .apply("memory_store_ids @> cast({0} as jsonb)", "[\"" + storeId + "\"]"));
    }

    /**
     * 统计仍挂载指定保管库的未删除会话数（vault_ids JSONB 包含查询，保管库删除引用校验）。
     *
     * @param vaultId 保管库业务 ID（vault_ 前缀）
     * @return 引用数（0 表示无会话挂载，可安全删除）
     */
    default Long countByVaultId(String vaultId) {
        return selectCount(Wrappers.<AgentSessionEntity>lambdaQuery()
                .apply("vault_ids @> cast({0} as jsonb)", "[\"" + vaultId + "\"]"));
    }
}