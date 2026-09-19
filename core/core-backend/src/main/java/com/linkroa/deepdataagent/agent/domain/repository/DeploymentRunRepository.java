package com.linkroa.deepdataagent.agent.domain.repository;

import com.linkroa.deepdataagent.agent.domain.model.DeploymentRun;
import com.linkroa.deepdataagent.agent.domain.model.DeploymentRunsFilter;
import com.linkroa.deepdataagent.agent.domain.model.enums.DeploymentRunStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 调度运行记录仓储接口（deployment_run）。
 * <p>一行定稿：触发成功落 {@code running} 初始行，此后仅在触发会话终局出口
 * 经 {@link #casCompleteBySessionId} 窄列条件更新（CAS）就地终态化，
 * 不以陈旧内存快照整行回写（参照 F08 窄列更新先例）。</p>
 */
public interface DeploymentRunRepository {

    /**
     * 保存运行记录（触发成功即落一行初始 running 记录）。
     */
    DeploymentRun save(DeploymentRun run);

    /**
     * 按业务ID查询运行记录。
     */
    Optional<DeploymentRun> findByRunId(String runId);

    /**
     * 查询触发会话仍在运行中的运行行（触发会话与运行行 1:1，至多一条）：
     * 供契约实现定位 {@code deploymentId}，在 CAS 命中后同事务刷新 {@code deployment.last_status}。
     */
    Optional<DeploymentRun> findRunningBySessionId(String sessionId);

    /**
     * 按触发会话终态化（幂等收口）：仅 {@code running → 终态 + finished_at} 条件更新生效，
     * 重复 / 迟到回写命中 0 行、零副作用。
     *
     * @param sessionId   触发会话ID
     * @param status      目标终态（succeeded / failed / terminated）
     * @param finishedAt  结束时间
     * @return true=CAS 命中并完成终态化；false=非调度运行或已终态
     */
    boolean casCompleteBySessionId(String sessionId, DeploymentRunStatus status, OffsetDateTime finishedAt);

    /**
     * 游标分页查询运行记录（6.5 管理面）：触发时间降序 keyset（{@code created_at, id}）；
     * 过滤条件限单调度器或全局归属作用域（{@link DeploymentRunsFilter}），
     * 限量由调用方传入（含探针）。
     */
    List<DeploymentRun> findByCursor(DeploymentRunsFilter filter, int limit);
}
