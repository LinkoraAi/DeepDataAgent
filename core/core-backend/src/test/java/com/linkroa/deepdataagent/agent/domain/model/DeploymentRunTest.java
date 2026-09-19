package com.linkroa.deepdataagent.agent.domain.model;

import com.linkroa.deepdataagent.agent.domain.model.enums.DeploymentRunStatus;
import com.linkroa.deepdataagent.agent.domain.model.enums.DeploymentTriggerType;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link DeploymentRun} 调度运行记录领域模型单测：start 工厂初始态与业务ID前缀不变量。
 */
class DeploymentRunTest {

    @Test
    void should_createRunningRecord_when_start_given_validIds() {
        // given // when
        DeploymentRun run = DeploymentRun.start("drun_abc", "dep-1", "sess-1", DeploymentTriggerType.CRON);

        // then（触发即 running 初始态，结束时间留空待终态回写）
        assertNull(run.id());
        assertEquals("drun_abc", run.runId());
        assertEquals("dep-1", run.deploymentId());
        assertEquals("sess-1", run.sessionId());
        assertEquals(DeploymentTriggerType.CRON, run.triggerKind());
        assertEquals(DeploymentRunStatus.RUNNING, run.status());
        assertNotNull(run.startedAt());
        assertNull(run.finishedAt());
        assertNotNull(run.createdAt());
    }

    @Test
    void should_allowNullSession_when_start_given_launchWithoutSession() {
        // given // when（启动失败场景允许无会话的运行记录）
        DeploymentRun run = DeploymentRun.start("drun_abc", "dep-1", null, DeploymentTriggerType.MANUAL);

        // then
        assertNull(run.sessionId());
    }

    @Test
    void should_throwException_when_start_given_runIdWithoutPrefix() {
        // given // when // then（drun_ 前缀不变量）
        assertThrows(IllegalArgumentException.class, () -> DeploymentRun.start("run_abc", "dep-1",
                "sess-1", DeploymentTriggerType.CRON));
    }

    @Test
    void should_throwException_when_construct_given_missingRequiredFields() {
        // given（合法前缀的 runId，逐项缺失其余必填）
        OffsetDateTime now = OffsetDateTime.now();

        // when // then（runId / deploymentId / triggerKind / status / startedAt 必填）
        assertThrows(IllegalArgumentException.class,
                () -> new DeploymentRun(1L, " ", "dep-1", null, DeploymentTriggerType.CRON,
                        DeploymentRunStatus.RUNNING, now, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new DeploymentRun(1L, "drun_1", " ", null, DeploymentTriggerType.CRON,
                        DeploymentRunStatus.RUNNING, now, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new DeploymentRun(1L, "drun_1", "dep-1", null, null,
                        DeploymentRunStatus.RUNNING, now, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new DeploymentRun(1L, "drun_1", "dep-1", null, DeploymentTriggerType.CRON,
                        null, now, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new DeploymentRun(1L, "drun_1", "dep-1", null, DeploymentTriggerType.CRON,
                        DeploymentRunStatus.RUNNING, null, null, null, null));
    }
}
