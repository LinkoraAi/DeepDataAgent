package com.linkroa.deepdataagent.agent.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linkroa.deepdataagent.agent.domain.model.DeploymentListFilter;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.DeploymentEntity;
import org.apache.commons.lang3.StringUtils;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 调度器 Mapper（MyBatis-Plus 数据库访问器，非 Convert 约定）。
 *
 * <p>归档语义：列表查询一律排除已归档（{@code archived_at IS NULL}）记录；
 * 调度领取语义（D14）：{@link #selectDue} 粗筛到期候选，{@link #advanceNextRunCas}
 * 以 {@code next_run_at} 条件更新（CAS）完成领取，多实例并发下恰好一个成功。</p>
 * <p>列引用一律使用<b>方法引用</b>（{@code DeploymentEntity::getX}）：MyBatis-Plus 的
 * {@code SFunction} 依赖序列化 lambda 的 {@code implMethodName} 解析属性名，
 * {@code e -> e.getX()} 形式编译为合成方法（{@code lambda$N}）会在运行期抛
 * {@code ReflectionException}，禁止使用。</p>
 */
@Mapper
public interface DeploymentMapper extends BaseMapper<DeploymentEntity> {

    /**
     * 按业务ID查询（含已归档，详情可见）。
     */
    default DeploymentEntity selectByDeploymentId(String deploymentId) {
        return selectOne(Wrappers.<DeploymentEntity>lambdaQuery()
                .eq(DeploymentEntity::getDeploymentId, deploymentId)
                .last("LIMIT 1"));
    }

    /**
     * 按 webhook token 查询<b>可触发</b>的调度器（仅 active 且未归档）。
     * <p>暂停（status=paused）/ 已归档（archived_at 非空）/ 未知 token 一律查不到，
     * 与「调度器不存在」不可区分（webhook 免 JWT 语义，统一 404）。</p>
     */
    default DeploymentEntity selectActiveByWebhookToken(String webhookToken) {
        return selectOne(Wrappers.<DeploymentEntity>lambdaQuery()
                .eq(DeploymentEntity::getWebhookToken, webhookToken)
                .isNull(DeploymentEntity::getArchivedAt)
                .eq(DeploymentEntity::getStatus, "active")
                .last("LIMIT 1"));
    }

    /**
     * 游标分页查询调度器（6.5 管理面 Cursor 约定，创建时间降序 keyset 行值比较）。
     * <p>after 方向 {@code (created_at, id) < (?,?)} 降序读取更旧页；before 方向反向比较后
     * 升序读取更新页（应用层翻转回降序）；状态 / Agent / 创建时间区间过滤可缺省；
     * 归档默认排除（{@code include_archived=true} 时一并返回）。</p>
     */
    @SuppressWarnings("null")
    default List<DeploymentEntity> selectByCursor(Long ownerId, DeploymentListFilter filter, int limit) {
        LambdaQueryWrapper<DeploymentEntity> wrapper = Wrappers.<DeploymentEntity>lambdaQuery()
                .eq(DeploymentEntity::getOwnerId, ownerId)
                .eq(filter.status() != null, DeploymentEntity::getStatus,
                        filter.status() == null ? null : filter.status().getValue())
                .eq(StringUtils.isNotBlank(filter.agentId()), DeploymentEntity::getAgentId, filter.agentId())
                .ge(filter.createdAfter() != null, DeploymentEntity::getCreatedAt, filter.createdAfter())
                .le(filter.createdBefore() != null, DeploymentEntity::getCreatedAt, filter.createdBefore());
        if (!filter.includeArchived()) {
            wrapper.isNull(DeploymentEntity::getArchivedAt);
        }
        if (filter.cursorCreatedAt() != null) {
            wrapper.apply(filter.reverse(), "(created_at, id) > ({0}, {1})",
                    filter.cursorCreatedAt(), filter.cursorRowId());
            wrapper.apply(!filter.reverse(), "(created_at, id) < ({0}, {1})",
                    filter.cursorCreatedAt(), filter.cursorRowId());
        }
        if (filter.reverse()) {
            wrapper.orderByAsc(DeploymentEntity::getCreatedAt).orderByAsc(DeploymentEntity::getId);
        } else {
            wrapper.orderByDesc(DeploymentEntity::getCreatedAt).orderByDesc(DeploymentEntity::getId);
        }
        return selectList(wrapper.last("LIMIT " + limit));
    }

    /**
     * 粗筛到期待触发的调度器（有 schedule、active、未归档且 {@code next_run_at} 已到期，
     * 按到期时间升序限量返回；领取以 CAS 为准）。
     *
     * @param now   当前时间
     * @param limit 单轮候选上限（调用方控制的整数，非外部输入）
     */
    default List<DeploymentEntity> selectDue(OffsetDateTime now, int limit) {
        return selectList(Wrappers.<DeploymentEntity>lambdaQuery()
                .isNotNull(DeploymentEntity::getSchedule)
                .eq(DeploymentEntity::getStatus, "active")
                .isNull(DeploymentEntity::getArchivedAt)
                .isNotNull(DeploymentEntity::getNextRunAt)
                .le(DeploymentEntity::getNextRunAt, now)
                .orderByAsc(DeploymentEntity::getNextRunAt)
                .last("LIMIT " + limit));
    }

    /**
     * 到期时间 CAS 推进（D14 条件更新领取：仅当 {@code next_run_at} 仍等于期望值时推进）。
     *
     * @param deploymentId      调度器业务ID
     * @param expectedNextRunAt 期望的当前到期时间（CAS 比较值）
     * @param nextRunAt         重算后的下一次到期时间
     * @return 影响行数（1=领取成功；0=已被其他实例领取）
     */
    @Update("UPDATE deployment SET next_run_at = #{nextRunAt}, updated_at = now() "
            + "WHERE deployment_id = #{deploymentId} AND next_run_at = #{expectedNextRunAt} "
            + "AND status = 'active' AND archived_at IS NULL AND is_deleted = 0")
    int advanceNextRunCas(@Param("deploymentId") String deploymentId,
                          @Param("expectedNextRunAt") OffsetDateTime expectedNextRunAt,
                          @Param("nextRunAt") OffsetDateTime nextRunAt);

    /**
     * 窄列回写最近运行信息（审查修复 F08：触发完成后仅更新 {@code last_run_at /
     * last_session_id / last_status}，不回写整行，避免陈旧快照撤销并发 pause / update）。
     * <p>运行快照与调度器当前状态无关（暂停后不回写最近运行属合理语义），
     * 故不加 status 守卫，仅排除逻辑删除行。</p>
     *
     * @param deploymentId  调度器业务ID
     * @param sessionId     本次触发产生的会话 ID（可为 null）
     * @param lastStatus    本次触发结果状态（success / failed 等对外词汇）
     * @return 影响行数
     */
    @Update("UPDATE deployment SET last_run_at = #{runAt}, last_session_id = #{sessionId}, "
            + "last_status = #{lastStatus}, updated_at = now() "
            + "WHERE deployment_id = #{deploymentId} AND is_deleted = 0")
    int updateLastRun(@Param("deploymentId") String deploymentId,
                      @Param("sessionId") String sessionId,
                      @Param("lastStatus") String lastStatus,
                      @Param("runAt") OffsetDateTime runAt);

    /**
     * 窄列刷新 {@code last_status}（终态回写专用）：仅当 {@code last_session_id} 仍等于
     * 本次回写会话时更新——该守卫即「避免迟到回写覆盖新触发」的落点：调度器被再次触发后
     * {@code last_session_id} 已指向新会话，旧 episode 的迟到终态回写命中 0 行、不改写快照。
     * <p>不复用 {@link #updateLastRun}：它三连覆写 {@code last_run_at / last_session_id /
     * last_status}，长任务旧运行的终态回写会把新触发的快照字段一并倒回。</p>
     *
     * @param deploymentId 调度器业务ID
     * @param sessionId    触发本次回写的会话ID（CAS 守卫值）
     * @param lastStatus   终态契约值（succeeded / failed / terminated）
     * @return 影响行数（1=快照仍属本会话，已刷新；0=已被新触发接管）
     */
    @Update("UPDATE deployment SET last_status = #{lastStatus}, updated_at = now() "
            + "WHERE deployment_id = #{deploymentId} AND last_session_id = #{sessionId} AND is_deleted = 0")
    int refreshLastStatusCas(@Param("deploymentId") String deploymentId,
                             @Param("sessionId") String sessionId,
                             @Param("lastStatus") String lastStatus);

    /**
     * 统计引用指定运行环境的<b>未归档</b>调度器数量（审查修复 F10：环境删除前的反查引用，
     * {@code archived_at IS NULL} 即视为仍在调度生命周期内，含 paused）。
     *
     * @param environmentId 运行环境业务ID
     * @return 引用该环境的未归档调度器数量
     */
    default Long selectCountActiveByEnvironmentId(String environmentId) {
        return selectCount(Wrappers.<DeploymentEntity>lambdaQuery()
                .eq(DeploymentEntity::getEnvironmentId, environmentId)
                .isNull(DeploymentEntity::getArchivedAt));
    }
}
