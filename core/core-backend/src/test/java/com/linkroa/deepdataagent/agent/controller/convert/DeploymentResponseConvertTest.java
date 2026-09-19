package com.linkroa.deepdataagent.agent.controller.convert;

import com.linkroa.deepdataagent.agent.controller.response.DeploymentResponse;
import com.linkroa.deepdataagent.agent.controller.response.DeploymentRunResponse;
import com.linkroa.deepdataagent.agent.domain.model.Deployment;
import com.linkroa.deepdataagent.agent.domain.model.DeploymentRun;
import com.linkroa.deepdataagent.agent.domain.model.DeploymentSchedule;
import com.linkroa.deepdataagent.agent.domain.model.enums.DeploymentRunStatus;
import com.linkroa.deepdataagent.agent.domain.model.enums.DeploymentStatus;
import com.linkroa.deepdataagent.agent.domain.model.enums.DeploymentTriggerType;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DeploymentResponseConvert} 单测（D14 响应装配基线）：snake_case 协议字段映射、
 * 状态枚举小写值域、{@code upcoming_runs_at} 响应侧实时计算（升序、最多 5 条、无调度空列表）、
 * JSON 透传载荷结构化回显与非法 JSON 快速失败、运行记录响应装配。
 */
class DeploymentResponseConvertTest {

    private static final String CRON_DAILY_9 = "0 0 9 * * *";

    private static final OffsetDateTime T_BASE = OffsetDateTime.parse("2026-09-01T08:00:00+08:00");

    private final DeploymentResponseConvert convert = DeploymentResponseConvert.INSTANCE;

    /** 26 参调度器基底：仅差异化字段入参，其余取中性值 */
    private Deployment buildDeployment(DeploymentSchedule schedule, OffsetDateTime nextRunAt,
                                       String webhookToken, DeploymentStatus status) {
        return new Deployment(1L, "dep-1", "定时调度", "描述", "agent-1", 2, null,
                "{}", "[]", List.of(), "[]", "{}",
                schedule, nextRunAt, webhookToken, status, null, null, null, null,
                1L, null, T_BASE, T_BASE, null, null);
    }

    @Test
    void should_mapSnakeFieldsWithLowerCaseStatusAndFiveUpcomingRuns_when_toResponse_given_cronScheduler() {
        // given（每日 9 点 cron，上海时区）
        DeploymentSchedule schedule = new DeploymentSchedule(CRON_DAILY_9, "Asia/Shanghai");
        Deployment deployment = buildDeployment(schedule, T_BASE, null, DeploymentStatus.ACTIVE);

        // when
        DeploymentResponse response = convert.toResponse(deployment);

        // then（协议字段映射 + 状态小写 + 调度回显）
        assertEquals("dep-1", response.deployment_id());
        assertEquals("agent-1", response.agent_id());
        assertEquals(2, response.agent_version());
        assertEquals("active", response.status());
        assertEquals(T_BASE, response.next_run_at());
        assertEquals(CRON_DAILY_9, response.schedule().cron());
        assertEquals("Asia/Shanghai", response.schedule().timezone());
        // upcoming_runs_at 实时推算：最多 5 条、严格升序、均晚于当前时刻
        List<OffsetDateTime> upcoming = response.upcoming_runs_at();
        assertEquals(5, upcoming.size());
        for (int i = 0; i < upcoming.size(); i++) {
            assertTrue(upcoming.get(i).isAfter(OffsetDateTime.now().minusMinutes(1)),
                    "预告第 " + i + " 条应为未来时刻");
            if (i > 0) {
                assertTrue(upcoming.get(i).isAfter(upcoming.get(i - 1)), "预告序列必须升序");
            }
        }
    }

    @Test
    void should_returnEmptyUpcomingAndNullSchedule_when_toResponse_given_manualScheduler() {
        // given（仅手动/webhook 触发：无调度、无到期时间、有回调 token）
        Deployment deployment = buildDeployment(null, null, "tok-123", DeploymentStatus.PAUSED);

        // when
        DeploymentResponse response = convert.toResponse(deployment);

        // then（schedule 为 null、预告空列表、暂停原因与 token 透传）
        assertNull(response.schedule());
        assertEquals(List.of(), response.upcoming_runs_at());
        assertNull(response.next_run_at());
        assertEquals("tok-123", response.webhook_token());
        assertEquals("paused", response.status());
    }

    @Test
    void should_parsePayloadsIntoStructuredEcho_when_toResponse_given_populatedJsonPayloads() {
        // given（触发透传载荷：环境变量 / 资源 / 首批事件 / 元数据均为 JSON 文本）
        Deployment deployment = new Deployment(1L, "dep-1", "载荷调度", null, "agent-1", 2, "env-1",
                "{\"TZ\":\"UTC\"}", "[{\"type\":\"file\",\"file_id\":\"file_1\"}]", List.of("vault-a"),
                "[{\"type\":\"user_message\",\"text\":\"开工\"}]", "{\"biz\":\"x\"}",
                null, null, null, DeploymentStatus.ACTIVE, "例行维护", null, "sess-9", "succeeded",
                1L, null, T_BASE, T_BASE, null, null);

        // when
        DeploymentResponse response = convert.toResponse(deployment);

        // then（JSON 文本解析为结构化对象原样回显）
        assertEquals(Map.of("TZ", "UTC"), response.environment_variables());
        assertEquals(List.of(Map.of("type", "file", "file_id", "file_1")), response.resources());
        assertEquals(List.of("vault-a"), response.vault_ids());
        assertEquals(List.of(Map.of("type", "user_message", "text", "开工")), response.initial_events());
        assertEquals(Map.of("biz", "x"), response.metadata());
        // 暂停原因与最近运行快照回显
        assertEquals("例行维护", response.paused_reason());
        assertEquals("sess-9", response.last_session_id());
        assertEquals("succeeded", response.last_status());
    }

    @Test
    void should_throwIllegalState_when_toResponse_given_malformedPayloadJson() {
        // given（存储侧 JSON 损坏：解析失败必须快速失败而非静默吞掉）
        Deployment deployment = new Deployment(1L, "dep-1", "损坏调度", null, "agent-1", 2, null,
                "not-a-json", "[]", List.of(), "[]", "{}",
                null, null, null, DeploymentStatus.ACTIVE, null, null, null, null,
                1L, null, T_BASE, T_BASE, null, null);

        // when // then
        assertThrows(IllegalStateException.class, () -> convert.toResponse(deployment));
    }

    @Test
    void should_mapRunResponseWithLowerCaseValues_when_toRunResponse_given_runningRecord() {
        // given（手动触发、进行中、未终态）
        DeploymentRun run = new DeploymentRun(9L, "drun_a", "dep-1", "sess-1",
                DeploymentTriggerType.MANUAL, DeploymentRunStatus.RUNNING, T_BASE, null, T_BASE, T_BASE);

        // when
        DeploymentRunResponse response = convert.toRunResponse(run);

        // then（业务ID → id、类型常量、触发方式与状态小写值域）
        assertEquals("drun_a", response.id());
        assertEquals(DeploymentRunResponse.TYPE, response.type());
        assertEquals("dep-1", response.deployment_id());
        assertEquals("sess-1", response.session_id());
        assertEquals("manual", response.trigger());
        assertEquals("running", response.status());
        assertEquals(T_BASE, response.started_at());
        assertNull(response.finished_at());
    }

    @Test
    void should_returnNull_when_toResponseOrRunResponse_given_null() {
        // given // when // then
        assertNull(convert.toResponse(null));
        assertNull(convert.toRunResponse(null));
        assertNull(convert.toScheduleResponse(null));
    }
}
