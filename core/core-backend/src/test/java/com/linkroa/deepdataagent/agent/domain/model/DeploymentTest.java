package com.linkroa.deepdataagent.agent.domain.model;

import com.linkroa.deepdataagent.agent.domain.model.enums.DeploymentStatus;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Deployment} 调度器领域模型单测：创建工厂不变量、JSON 透传载荷归一、
 * 暂停 / 恢复 / 归档状态流转与到期时间语义、运行快照记录与到期推进。
 */
class DeploymentTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 每日 9 点整（Spring 6 段 cron） */
    private static final String CRON_DAILY_9 = "0 0 9 * * *";

    private DeploymentSchedule schedule() {
        return new DeploymentSchedule(CRON_DAILY_9, null);
    }

    private Deployment buildCronDeployment() {
        return Deployment.create("dep-1", "每日汇总", null, "agent-1", 1, "env-1",
                null, null, null, null, null, schedule(), null, 1L);
    }

    /** 全组件恢复构造（覆盖创建工厂以外的状态字段） */
    private Deployment restoreDeployment(DeploymentStatus status, String pausedReason,
                                         DeploymentSchedule schedule, OffsetDateTime nextRunAt,
                                         OffsetDateTime archivedAt) {
        return new Deployment(1L, "dep-1", "调度器", "desc", "agent-1", 2, null,
                "{}", "[]", List.of(), "[]", "{}", schedule, nextRunAt, null,
                status, pausedReason, null, null, null, 1L, archivedAt,
                OffsetDateTime.now(ZONE), OffsetDateTime.now(ZONE), null, null);
    }

    @Test
    void should_createActiveSchedulerWithNextRunAt_when_create_given_schedule() {
        // given // when
        Deployment deployment = buildCronDeployment();

        // then（初始 active、未运行、未归档；有调度即物化首次到期时间）
        assertEquals("dep-1", deployment.deploymentId());
        assertEquals("每日汇总", deployment.name());
        assertEquals(1, deployment.agentVersion());
        assertEquals(DeploymentStatus.ACTIVE, deployment.status());
        assertNotNull(deployment.schedule());
        assertNotNull(deployment.nextRunAt());
        assertTrue(deployment.nextRunAt().isAfter(OffsetDateTime.now(ZONE)));
        assertTrue(deployment.active());
        assertTrue(deployment.schedulable());
        assertFalse(deployment.archived());
        assertNull(deployment.webhookToken());
        assertNull(deployment.lastRunAt());
    }

    @Test
    void should_keepNextRunAtNull_when_create_given_noSchedule() {
        // given // when
        Deployment deployment = Deployment.create("dep-2", "仅手动", "", "agent-1", 3, null,
                null, null, null, null, null, null, "tok-123", 1L);

        // then（无调度=仅手动/webhook；描述空白归一空串；token 透传）
        assertNull(deployment.schedule());
        assertNull(deployment.nextRunAt());
        assertEquals("", deployment.description());
        assertEquals("tok-123", deployment.webhookToken());
        assertFalse(deployment.schedulable());
        assertTrue(deployment.active());
    }

    @Test
    void should_normalizeJsonPayloadsAndVaultIds_when_create_given_blankPayloads() {
        // given // when
        Deployment deployment = Deployment.create("dep-3", "调度器", "desc", "agent-1", 1, null,
                " ", null, null, null, null, null, null, 1L);

        // then（JSON 透传载荷空白归一默认对象 / 数组文本，vaultIds 归一空列表）
        assertEquals("{}", deployment.environmentVariables());
        assertEquals("[]", deployment.resources());
        assertEquals("[]", deployment.initialEvents());
        assertEquals("{}", deployment.metadata());
        assertEquals(List.of(), deployment.vaultIds());
    }

    @Test
    void should_copyVaultIdsDefensively_when_create_given_vaultIds() {
        // given
        List<String> vaultIds = new java.util.ArrayList<>(List.of("vault-a", "vault-b"));

        // when
        Deployment deployment = Deployment.create("dep-4", "调度器", null, "agent-1", 1, null,
                null, null, vaultIds, null, null, null, null, 1L);
        vaultIds.add("vault-c");

        // then（防御性复制：外部修改不影响领域模型）
        assertEquals(List.of("vault-a", "vault-b"), deployment.vaultIds());
    }

    @Test
    void should_throwException_when_create_given_blankRequiredFields() {
        // given // when // then（deploymentId / name / agentId / ownerId 必填）
        assertThrows(IllegalArgumentException.class, () -> Deployment.create(" ", "调度器", null,
                "agent-1", 1, null, null, null, null, null, null, null, null, 1L));
        assertThrows(IllegalArgumentException.class, () -> Deployment.create("dep-1", " ", null,
                "agent-1", 1, null, null, null, null, null, null, null, null, 1L));
        assertThrows(IllegalArgumentException.class, () -> Deployment.create("dep-1", "调度器", null,
                " ", 1, null, null, null, null, null, null, null, null, 1L));
        assertThrows(IllegalArgumentException.class, () -> Deployment.create("dep-1", "调度器", null,
                "agent-1", 1, null, null, null, null, null, null, null, null, null));
    }

    @Test
    void should_throwException_when_create_given_nonPositivePinnedVersion() {
        // given // when // then（agentVersion 创建时固定，必须 ≥1）
        assertThrows(IllegalArgumentException.class, () -> Deployment.create("dep-1", "调度器", null,
                "agent-1", 0, null, null, null, null, null, null, null, null, 1L));
    }

    @Test
    void should_throwException_when_construct_given_oversizedDescription() {
        // given
        String longDescription = "描".repeat(501);

        // when // then
        assertThrows(IllegalArgumentException.class, () -> Deployment.create("dep-1", "调度器",
                longDescription, "agent-1", 1, null, null, null, null, null, null, null, null, 1L));
    }

    @Test
    void should_clearNextRunAt_when_construct_given_nullScheduleWithStaleNextRunAt() {
        // given（仓储回读脏数据防御：schedule 为 null 时到期时间一律清空）
        // when
        Deployment deployment = restoreDeployment(DeploymentStatus.ACTIVE, null, null,
                OffsetDateTime.now(ZONE), null);

        // then
        assertNull(deployment.nextRunAt());
        assertFalse(deployment.schedulable());
    }

    @Test
    void should_pauseWithReason_when_pause_given_activeScheduler() {
        // given
        Deployment deployment = buildCronDeployment();

        // when
        Deployment paused = deployment.pause(" 节假日停跑 ");

        // then（暂停不改归档、不清调度配置；原因 trim 落位；active 语义翻转为不可触发）
        assertEquals(DeploymentStatus.PAUSED, paused.status());
        assertEquals("节假日停跑", paused.pausedReason());
        assertFalse(paused.active());
        assertFalse(paused.schedulable());
        assertNotNull(paused.schedule());
        assertFalse(paused.archived());
        // 原对象不可变
        assertEquals(DeploymentStatus.ACTIVE, deployment.status());
    }

    @Test
    void should_clearReasonAndRecomputeNextRun_when_unpause_given_pausedCronScheduler() {
        // given（暂停期间到期时间停留在过去：恢复后按当前时刻重算，不补欠账窗口）
        OffsetDateTime staleNextRunAt = OffsetDateTime.now(ZONE).minusDays(3);
        Deployment paused = restoreDeployment(DeploymentStatus.PAUSED, "维护", schedule(),
                staleNextRunAt, null);

        // when
        Deployment resumed = paused.unpause();

        // then
        assertEquals(DeploymentStatus.ACTIVE, resumed.status());
        assertNull(resumed.pausedReason());
        assertTrue(resumed.nextRunAt().isAfter(staleNextRunAt));
        assertTrue(resumed.nextRunAt().isAfter(OffsetDateTime.now(ZONE)));
    }

    @Test
    void should_throwIllegalState_when_pauseOrUnpause_given_archivedScheduler() {
        // given
        Deployment archived = buildCronDeployment().archive();

        // when // then（归档终态不可暂停、不可恢复）
        assertThrows(IllegalStateException.class, () -> archived.pause("reason"));
        assertThrows(IllegalStateException.class, archived::unpause);
    }

    @Test
    void should_archiveWithPausedStatus_when_archive_given_pausedScheduler() {
        // given
        Deployment paused = buildCronDeployment().pause("维护");

        // when（归档=archived_at + paused 双写）
        Deployment archived = paused.archive();

        // then
        assertTrue(archived.archived());
        assertEquals(DeploymentStatus.PAUSED, archived.status());
        assertFalse(archived.active());
        assertNotNull(archived.archivedAt());
        // 归档不清暂停原因（保留审计信息）
        assertEquals("维护", archived.pausedReason());
    }

    @Test
    void should_throwIllegalArgument_when_create_given_nameExceeding64Chars() {
        // given（审查修复 F12：name 列宽 VARCHAR(64)，域构造器须前置拒绝超长名）
        String longName = "n".repeat(65);

        // when // then
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> buildCronDeployment().withTunable(longName, null, null, null, null,
                        List.of(), null, null, null, null));
        assertTrue(ex.getMessage().contains("64"));
    }

    @Test
    void should_advanceNextRunFromFiredAt_when_advanceNextRun_given_cronScheduler() {
        // given
        Deployment deployment = buildCronDeployment();
        OffsetDateTime firedAt = OffsetDateTime.now(ZONE);

        // when
        Deployment advanced = deployment.advanceNextRun(firedAt);

        // then（推进到 firedAt 之后的下一个 cron 槽位）
        assertEquals(deployment.schedule().nextAfter(firedAt), advanced.nextRunAt());
        assertTrue(advanced.nextRunAt().isAfter(firedAt));
    }

    @Test
    void should_returnSameInstance_when_advanceNextRun_given_schedulerWithoutSchedule() {
        // given
        Deployment manual = Deployment.create("dep-9", "仅手动", null, "agent-1", 1, null,
                null, null, null, null, null, null, null, 1L);

        // when
        Deployment advanced = manual.advanceNextRun(OffsetDateTime.now(ZONE));

        // then
        assertSame(manual, advanced);
    }

    @Test
    void should_replaceTunableAndKeepBindings_when_withTunable_given_resolvedValues() {
        // given（cron 调度器 + webhook token：merge-patch 终值替换不应触碰绑定与快照）
        Deployment deployment = Deployment.create("dep-3", "原名", "原描述", "agent-1", 2, "env-1",
                "{\"A\":\"1\"}", "[]", List.of("vault-a"), "[]", "{\"k\":\"v\"}", schedule(), "tok-9", 1L);
        OffsetDateTime keptNextRunAt = OffsetDateTime.now(ZONE).plusDays(3);

        // when
        Deployment updated = deployment.withTunable("新名", "新描述", null,
                "{\"B\":\"2\"}", "[{\"type\":\"file\"}]", List.of(), "[{\"type\":\"user_message\"}]",
                "{}", new DeploymentSchedule("0 0 12 * * *", null), keptNextRunAt);

        // then（可调字段替换 + 绑定 / token / 状态保留 + 到期时间以传入终值为准）
        assertEquals("新名", updated.name());
        assertEquals("新描述", updated.description());
        assertNull(updated.environmentId());
        assertEquals("{\"B\":\"2\"}", updated.environmentVariables());
        assertEquals("[{\"type\":\"file\"}]", updated.resources());
        assertEquals(List.of(), updated.vaultIds());
        assertEquals("{}", updated.metadata());
        assertEquals("0 0 12 * * *", updated.schedule().cron());
        assertEquals(keptNextRunAt, updated.nextRunAt());
        assertEquals("agent-1", updated.agentId());
        assertEquals(2, updated.agentVersion());
        assertEquals("tok-9", updated.webhookToken());
        assertEquals(DeploymentStatus.ACTIVE, updated.status());
        assertEquals(deployment.createdAt(), updated.createdAt());
    }

    @Test
    void should_forceNextRunAtNull_when_withTunable_given_clearedSchedule() {
        // given（构造器不变量：schedule 清空时 nextRunAt 必须随之为空，即便传入残值）
        Deployment deployment = buildCronDeployment();

        // when
        Deployment cleared = deployment.withTunable(deployment.name(), deployment.description(),
                deployment.environmentId(), deployment.environmentVariables(), deployment.resources(),
                deployment.vaultIds(), deployment.initialEvents(), deployment.metadata(), null, deployment.nextRunAt());

        // then
        assertNull(cleared.schedule());
        assertNull(cleared.nextRunAt());
        assertFalse(cleared.schedulable());
    }
}
