package com.linkroa.deepdataagent.runtime.application.service;

import com.linkroa.deepdataagent.runtime.domain.model.enums.AgentSessionStatus;
import com.linkroa.deepdataagent.runtime.domain.repository.AgentSessionRepository;
import com.linkroa.deepdataagent.runtime.domain.repository.ExecutionRoundRepository;
import com.linkroa.deepdataagent.runtime.infrastructure.config.AgentRuntimeProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationArguments;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link StartupRecoveryRunner} 启动恢复单测。
 */
@ExtendWith(MockitoExtension.class)
class StartupRecoveryRunnerTest {

    @Mock
    private AgentSessionRepository sessionRepository;
    @Mock
    private ExecutionRoundRepository roundRepository;
    @Mock
    private ApplicationArguments arguments;
    @Mock
    private TransactionTemplate transactionTemplate;

    private StartupRecoveryRunner runner;

    @BeforeEach
    void setUp() {
        runner = new StartupRecoveryRunner();
    }

    /** 通过字段注入装配 Runner 的依赖（@Resource 字段注入，测试中不依赖 Spring 容器）。 */
    private void wireRunner(AgentRuntimeProperties properties) {
        ReflectionTestUtils.setField(runner, "properties", properties);
        ReflectionTestUtils.setField(runner, "sessionRepository", sessionRepository);
        ReflectionTestUtils.setField(runner, "roundRepository", roundRepository);
        ReflectionTestUtils.setField(runner, "transactionTemplate", transactionTemplate);
    }

    /** 让 mock 事务模板同步执行 executeWithoutResult 回调。 */
    private void wireTransactionTemplate() {
        doAnswer(inv -> {
            Consumer<TransactionStatus> consumer = inv.getArgument(0);
            consumer.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    @Test
    void should_resetRunningSessions_when_run_given_recordsExist() {
        // given
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.setStartupRecoveryEnabled(true);
        wireRunner(properties);
        wireTransactionTemplate();
        when(sessionRepository.findRunningSessionIds()).thenReturn(List.of("s-1", "s-2"));
        when(roundRepository.updateRunningToInterrupted(List.of("s-1", "s-2"))).thenReturn(2);

        // when
        runner.run(arguments);

        // then
        verify(sessionRepository).updateStatus("s-1", AgentSessionStatus.IDLE);
        verify(sessionRepository).updateStatus("s-2", AgentSessionStatus.IDLE);
        verify(roundRepository).updateRunningToInterrupted(List.of("s-1", "s-2"));
    }

    @Test
    void should_doNothing_when_run_given_noRunningSessions() {
        // given
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.setStartupRecoveryEnabled(true);
        wireRunner(properties);
        wireTransactionTemplate();
        when(sessionRepository.findRunningSessionIds()).thenReturn(List.of());

        // when
        runner.run(arguments);

        // then
        verify(sessionRepository, never()).updateStatus(any(), any());
        verify(roundRepository, never()).updateRunningToInterrupted(any());
    }

    @Test
    void should_skipWhen_run_given_recoveryDisabled() {
        // given
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.setStartupRecoveryEnabled(false);
        wireRunner(properties);

        // when
        runner.run(arguments);

        // then
        verify(sessionRepository, never()).findRunningSessionIds();
        verify(roundRepository, never()).updateRunningToInterrupted(any());
    }
}