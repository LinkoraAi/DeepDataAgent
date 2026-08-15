package com.linkroa.deepdataagent.runtime.application.service;

import com.linkroa.deepdataagent.runtime.domain.model.enums.AgentSessionStatus;
import com.linkroa.deepdataagent.runtime.domain.repository.AgentSessionRepository;
import com.linkroa.deepdataagent.runtime.domain.repository.ExecutionRoundRepository;
import com.linkroa.deepdataagent.runtime.infrastructure.config.AgentRuntimeProperties;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * 启动恢复：进程重启后清理残留 {@code RUNNING} 会话。
 * <p>幂等执行：将残留 RUNNING 会话批量置 IDLE，对应执行中轮次置
 * {@code INTERRUPTED}，防止进程崩溃后会话永久不可用（spec Requirement 会话生命周期）。</p>
 */
@Component
public class StartupRecoveryRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupRecoveryRunner.class);

    @Resource
    private AgentRuntimeProperties properties;
    @Resource
    private AgentSessionRepository sessionRepository;
    @Resource
    private ExecutionRoundRepository roundRepository;
    @Resource
    private TransactionTemplate transactionTemplate;

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isStartupRecoveryEnabled()) {
            log.info("启动恢复已禁用，跳过残留会话清理");
            return;
        }
        transactionTemplate.executeWithoutResult(status -> {
            List<String> runningSessionIds = sessionRepository.findRunningSessionIds();
            if (runningSessionIds.isEmpty()) {
                return;
            }
            runningSessionIds.forEach(sessionId ->
                    sessionRepository.updateStatus(sessionId, AgentSessionStatus.IDLE));
            int interrupted = roundRepository.updateRunningToInterrupted(runningSessionIds);
            log.info("启动恢复完成: 复位会话数={}, 中断轮次数={}", runningSessionIds.size(), interrupted);
        });
    }
}