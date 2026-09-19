package com.linkroa.deepdataagent.agent.domain.model;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DeploymentSchedule} 调度配置值对象单测：cron / 时区不变量、到期时间推算、
 * 未来运行预告与 JSONB 列文本序列化往返。
 */
class DeploymentScheduleTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 每日 9 点整（Spring 6 段 cron） */
    private static final String CRON_DAILY_9 = "0 0 9 * * *";

    @Test
    void should_defaultTimezoneAndTrimCron_when_construct_given_paddedCronAndBlankTimezone() {
        // given // when
        DeploymentSchedule schedule = new DeploymentSchedule("  " + CRON_DAILY_9 + "  ", " ");

        // then（cron 首尾空白收敛、时区缺省 Asia/Shanghai）
        assertEquals(CRON_DAILY_9, schedule.cron());
        assertEquals(DeploymentSchedule.DEFAULT_TIMEZONE, schedule.timezone());
    }

    @Test
    void should_throwException_when_construct_given_blankOrIllegalCron() {
        // given // when // then（空白、5 段旧式表达式、乱码一律拒绝）
        assertThrows(IllegalArgumentException.class, () -> new DeploymentSchedule(" ", null));
        assertThrows(IllegalArgumentException.class, () -> new DeploymentSchedule("0 9 * * *", null));
        assertThrows(IllegalArgumentException.class, () -> new DeploymentSchedule("bad cron", null));
    }

    @Test
    void should_throwException_when_construct_given_unknownTimezone() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> new DeploymentSchedule(CRON_DAILY_9, "Mars/Olympus_Mons"));
    }

    @Test
    void should_computeNextSlotInScheduleTimezone_when_nextAfter_given_utcBase() {
        // given（上海时间 2026-09-04 00:00 起算，UTC 时刻表达同瞬）
        DeploymentSchedule schedule = new DeploymentSchedule(CRON_DAILY_9, "Asia/Shanghai");
        OffsetDateTime from = OffsetDateTime.parse("2026-09-03T16:00:00+00:00");

        // when
        OffsetDateTime next = schedule.nextAfter(from);

        // then（下一个 9 点槽位 = 上海 2026-09-04 09:00）
        assertEquals(OffsetDateTime.parse("2026-09-04T09:00:00+08:00"), next);
    }

    @Test
    void should_throwException_when_nextAfter_given_nullBase() {
        // given
        DeploymentSchedule schedule = new DeploymentSchedule(CRON_DAILY_9, null);

        // when // then
        assertThrows(IllegalArgumentException.class, () -> schedule.nextAfter(null));
    }

    @Test
    void should_returnAscendingSlots_when_upcomingRuns_given_count() {
        // given
        DeploymentSchedule schedule = new DeploymentSchedule(CRON_DAILY_9, null);
        OffsetDateTime from = OffsetDateTime.parse("2026-09-04T10:00:00+08:00");

        // when
        List<OffsetDateTime> runs = schedule.upcomingRuns(from, 3);

        // then（升序 3 条：9/5、9/6、9/7 的 9 点整）
        assertEquals(3, runs.size());
        assertEquals(OffsetDateTime.parse("2026-09-05T09:00:00+08:00"), runs.get(0));
        assertEquals(OffsetDateTime.parse("2026-09-06T09:00:00+08:00"), runs.get(1));
        assertEquals(OffsetDateTime.parse("2026-09-07T09:00:00+08:00"), runs.get(2));
        assertTrue(runs.get(1).isAfter(runs.get(0)));
    }

    @Test
    void should_throwException_when_upcomingRuns_given_nonPositiveCount() {
        // given
        DeploymentSchedule schedule = new DeploymentSchedule(CRON_DAILY_9, null);

        // when // then
        assertThrows(IllegalArgumentException.class,
                () -> schedule.upcomingRuns(OffsetDateTime.now(ZONE), 0));
    }

    @Test
    void should_roundTripSchedule_when_toJsonAndFromJson_given_validSchedule() {
        // given
        DeploymentSchedule schedule = new DeploymentSchedule(CRON_DAILY_9, "UTC");

        // when
        String json = schedule.toJson();
        DeploymentSchedule restored = DeploymentSchedule.fromJson(json);

        // then
        assertTrue(json.contains(CRON_DAILY_9));
        assertEquals(schedule, restored);
    }

    @Test
    void should_returnNull_when_fromJson_given_blankText() {
        // given // when // then（空白列文本=仅手动/webhook 触发语义）
        assertNull(DeploymentSchedule.fromJson(null));
        assertNull(DeploymentSchedule.fromJson(" "));
    }

    @Test
    void should_throwException_when_fromJson_given_missingCronOrInvalidJson() {
        // given // when // then（缺 cron 字段与非法 JSON 均拒绝）
        assertThrows(IllegalArgumentException.class,
                () -> DeploymentSchedule.fromJson("{\"timezone\":\"UTC\"}"));
        assertThrows(IllegalArgumentException.class,
                () -> DeploymentSchedule.fromJson("not-a-json"));
    }
}
