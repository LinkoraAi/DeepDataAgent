package com.linkroa.deepdataagent.agent.domain.repository;

import com.linkroa.deepdataagent.agent.domain.model.Deployment;
import com.linkroa.deepdataagent.agent.domain.model.DeploymentListFilter;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 调度器仓储接口（Deployment 即调度器资源）。
 */
public interface DeploymentRepository {

    /**
     * 保存调度器（新增）。
     */
    Deployment save(Deployment deployment);

    /**
     * 按业务ID查询调度器（含已归档）。
     */
    Optional<Deployment> findByDeploymentId(String deploymentId);

    /**
     * 按 webhook token 查询<b>可触发</b>的调度器（仅 active 且未归档；暂停 / 已归档 / 未知 token 一律视为不存在）。
     * <p>webhook 免 JWT 语义：不可触发 / 未知 token 与不存在不可区分（均返回空），
     * 由调用方统一收敛为 404，不泄露调度器存在性。</p>
     */
    Optional<Deployment> findByWebhookToken(String webhookToken);

    /**
     * 游标分页查询调度器（6.5 管理面）：创建时间降序 keyset（{@code created_at, id}），
     * 支持状态 / Agent / 创建时间区间过滤与归档包含开关；限量由调用方传入（含探针）。
     */
    List<Deployment> findByCursor(Long ownerId, DeploymentListFilter filter, int limit);

    /**
     * 查询到期待触发的调度器候选（有 schedule、active、未归档且 {@code next_run_at} 已到期；
     * 按到期时间升序，仅粗筛，领取以 {@link #advanceNextRun} CAS 为准）。
     */
    List<Deployment> findDue(OffsetDateTime now, int limit);

    /**
     * 推进到期时间（DB 条件更新 CAS，即 D14「条件更新领取到期 Deployment」的领取动作：
     * 仅当 {@code next_run_at} 仍等于期望值时更新，多实例并发下恰好一个领取成功）。
     *
     * @param deploymentId        调度器业务ID
     * @param expectedNextRunAt   期望的当前到期时间（CAS 比较值）
     * @param nextRunAt           重算后的下一次到期时间
     * @return true=领取成功；false=已被其他实例领取或时间已推进
     */
    boolean advanceNextRun(String deploymentId, OffsetDateTime expectedNextRunAt, OffsetDateTime nextRunAt);

    /**
     * 全量更新调度器（暂停 / 恢复 / 归档 / 编辑）。
     */
    Deployment update(Deployment deployment);

    /**
     * 窄列回写最近运行信息（审查修复 F08）：触发完成后仅更新 {@code last_run_at /
     * last_session_id / last_status} 三列，不以陈旧内存快照整行回写，
     * 避免撤销并发 pause / update 的结果。
     *
     * @param deploymentId 调度器业务ID
     * @param sessionId    本次触发产生的会话 ID（可为 null）
     * @param lastStatus   本次触发结果状态
     * @param runAt        本次触发时间
     * @return true=回写成功；false=调度器不存在
     */
    boolean updateLastRun(String deploymentId, String sessionId, String lastStatus, OffsetDateTime runAt);

    /**
     * 窄列刷新 {@code last_status}（运行终态回写专用，D4「避免迟到回写覆盖新触发」落点）：
     * 仅当调度器最近运行快照 {@code last_session_id} 仍等于本次回写会话时更新——
     * 调度器已被再次触发时命中 0 行、不改写新触发的快照。
     * <p>不复用 {@link #updateLastRun}：其三连覆写会把新触发的快照字段倒回旧运行。</p>
     *
     * @param deploymentId 调度器业务ID
     * @param sessionId    触发本次回写的会话ID（守卫值）
     * @param lastStatus   运行终态契约值（succeeded / failed / terminated）
     * @return true=快照仍属本会话并已刷新；false=已被新触发接管
     */
    boolean refreshLastStatusForTrigger(String deploymentId, String sessionId, String lastStatus);

    /**
     * 统计引用指定运行环境的未归档调度器数量（审查修复 F10：环境删除前反查引用）。
     *
     * @param environmentId 运行环境业务ID
     * @return 引用数（含 paused，仅排除已归档）
     */
    long countActiveByEnvironmentId(String environmentId);
}
