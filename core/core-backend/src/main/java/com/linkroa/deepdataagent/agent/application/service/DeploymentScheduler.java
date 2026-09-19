package com.linkroa.deepdataagent.agent.application.service;

import com.linkroa.deepdataagent.agent.domain.repository.DeploymentRepository;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * 调度器轮询组件（D14 调度基线）：固定间隔轮询到期的定时调度器并触发。
 *
 * <p>多实例安全：领取动作收敛在
 * {@link DeploymentRepository#advanceNextRun(String, OffsetDateTime, OffsetDateTime)}
 * 的 DB 条件更新 CAS 上，多实例同轮并发下同一到期窗口恰好一个实例领取成功，无需分布式锁；
 * 单轮异常仅记日志，下一轮自然重试（轮询幂等）。</p>
 *
 * <p>时区：轮询时钟统一 {@code Asia/Shanghai}（与 {@code DeploymentSchedule} 缺省时区、
 * 全链路时区策略一致）。</p>
 */
@Slf4j
@Component
public class DeploymentScheduler {

    /** 默认时区（全链路统一 Asia/Shanghai）。 */
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");

    @Resource
    private DeploymentApplicationService deploymentApplicationService;

    /** 单轮最多领取触发的调度器条数。 */
    @Value("${app.deployment-scheduler.batch-size:20}")
    private int batchSize;

    /**
     * 轮询 tick：领取并触发到期调度器（间隔与首轮延迟可配，默认 15s / 10s）。
     */
    @Scheduled(fixedDelayString = "${app.deployment-scheduler.poll-interval-ms:15000}",
            initialDelayString = "${app.deployment-scheduler.initial-delay-ms:10000}")
    public void tick() {
        try {
            int fired = deploymentApplicationService.triggerDueDeployments(OffsetDateTime.now(DEFAULT_ZONE), batchSize);
            if (fired > 0) {
                log.info("调度器轮询完成: 本轮触发 {} 个到期调度器", fired);
            }
        } catch (Exception e) {
            log.error("调度器轮询异常（下一轮自动重试）", e);
        }
    }
}
