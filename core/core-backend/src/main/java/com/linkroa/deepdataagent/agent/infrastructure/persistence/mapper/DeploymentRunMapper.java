package com.linkroa.deepdataagent.agent.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linkroa.deepdataagent.agent.domain.model.DeploymentRunsFilter;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.DeploymentRunEntity;
import org.apache.commons.lang3.StringUtils;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 调度运行记录 Mapper（MyBatis-Plus 数据库访问器，非 Convert 约定）。
 */
@Mapper
public interface DeploymentRunMapper extends BaseMapper<DeploymentRunEntity> {

    /**
     * 按业务ID查询运行记录。
     */
    default DeploymentRunEntity selectByRunId(String runId) {
        return selectOne(Wrappers.<DeploymentRunEntity>lambdaQuery()
                .eq(DeploymentRunEntity::getRunId, runId)
                .last("LIMIT 1"));
    }

    /**
     * 查询触发会话仍在运行中的运行行（{@code status=running}，触发会话与运行行 1:1，至多一条）。
     * <p>经 wrapper 查询，MyBatis-Plus 逻辑删除条件自动注入。</p>
     */
    default DeploymentRunEntity selectRunningBySessionId(String sessionId) {
        return selectOne(Wrappers.<DeploymentRunEntity>lambdaQuery()
                .eq(DeploymentRunEntity::getSessionId, sessionId)
                .eq(DeploymentRunEntity::getStatus, "running")
                .last("LIMIT 1"));
    }

    /**
     * 按触发会话 CAS 终态化：仅 {@code running → 终态 + finished_at} 条件更新生效，
     * 重复 / 迟到回写命中 0 行（幂等收口）。
     * <p>原生 {@code @Update} 绕过逻辑删除注入，须显式 {@code is_deleted = 0}
     * （参照 {@code DeploymentMapper.updateLastRun} 先例）。</p>
     *
     * @param sessionId   触发会话ID
     * @param status      目标终态契约值（succeeded / failed / terminated）
     * @param finishedAt  结束时间
     * @return 受影响行数（1=CAS 命中；0=非调度运行或已终态）
     */
    @Update("UPDATE deployment_run SET status = #{status}, finished_at = #{finishedAt}, updated_at = now() "
            + "WHERE session_id = #{sessionId} AND status = 'running' AND is_deleted = 0")
    int casCompleteBySessionId(@Param("sessionId") String sessionId,
                               @Param("status") String status,
                               @Param("finishedAt") OffsetDateTime finishedAt);

    /**
     * 游标分页查询运行记录（6.5 管理面 Cursor 约定，触发时间降序 keyset 行值比较）。
     * <p>{@code deploymentId} 非空为单调度器作用域；{@code ownerId} 非空为全局作用域
     * （经 deployment 表子查询限定归属——deployment_run 无 owner 列，ownerId 为服务端
     * 认证上下文 Long 值，字符串拼接无注入面）；after/before 方向语义同列表统一约定；
     * 触发时间区间过滤可缺省。</p>
     */
    @SuppressWarnings("null")
    default List<DeploymentRunEntity> selectByCursor(DeploymentRunsFilter filter, int limit) {
        LambdaQueryWrapper<DeploymentRunEntity> wrapper = Wrappers.<DeploymentRunEntity>lambdaQuery();
        if (StringUtils.isNotBlank(filter.deploymentId())) {
            wrapper.eq(DeploymentRunEntity::getDeploymentId, filter.deploymentId());
        }
        if (filter.ownerId() != null) {
            wrapper.inSql(DeploymentRunEntity::getDeploymentId,
                    "SELECT deployment_id FROM deployment WHERE owner_id = " + filter.ownerId()
                            + " AND is_deleted = 0");
        }
        wrapper.ge(filter.createdAfter() != null, DeploymentRunEntity::getCreatedAt, filter.createdAfter())
                .le(filter.createdBefore() != null, DeploymentRunEntity::getCreatedAt, filter.createdBefore());
        if (filter.cursorCreatedAt() != null) {
            wrapper.apply(filter.reverse(), "(created_at, id) > ({0}, {1})",
                    filter.cursorCreatedAt(), filter.cursorRowId());
            wrapper.apply(!filter.reverse(), "(created_at, id) < ({0}, {1})",
                    filter.cursorCreatedAt(), filter.cursorRowId());
        }
        if (filter.reverse()) {
            wrapper.orderByAsc(DeploymentRunEntity::getCreatedAt).orderByAsc(DeploymentRunEntity::getId);
        } else {
            wrapper.orderByDesc(DeploymentRunEntity::getCreatedAt).orderByDesc(DeploymentRunEntity::getId);
        }
        return selectList(wrapper.last("LIMIT " + limit));
    }
}
