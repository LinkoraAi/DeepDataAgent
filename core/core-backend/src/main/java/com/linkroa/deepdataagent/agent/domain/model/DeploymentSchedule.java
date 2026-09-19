package com.linkroa.deepdataagent.agent.domain.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.lang3.StringUtils;
import org.springframework.scheduling.support.CronExpression;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 调度配置值对象（对应 deployment.schedule JSONB 列，格式 {@code {cron, timezone}}）。
 * <p>cron 采用 Spring 6 段表达式（秒级颗粒）在构造时校验合法性；timezone 缺省
 * {@code Asia/Shanghai}（与全链路时区策略一致）。到期时间 {@code next_run_at}
 * 由 {@link #nextAfter(OffsetDateTime)} 派生，{@link #upcomingRuns} 供响应侧
 * 返回未来运行时间预告（upcoming_runs_at 不落库、实时计算）。</p>
 *
 * @param cron     cron 表达式（6 段，必填）
 * @param timezone 时区 ID（空白归一为 Asia/Shanghai）
 */
public record DeploymentSchedule(String cron, String timezone) {

    /** 缺省时区（全链路统一 Asia/Shanghai） */
    public static final String DEFAULT_TIMEZONE = "Asia/Shanghai";

    /** 序列化 / 反序列化共享工具（接口字段隐式 public static final） */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 紧凑构造器：不变量校验（cron 合法性 + timezone 可解析 + 缺省归一）。
     */
    public DeploymentSchedule {
        if (StringUtils.isBlank(cron)) {
            throw new IllegalArgumentException("cron 表达式不能为空");
        }
        if (!CronExpression.isValidExpression(cron.trim())) {
            throw new IllegalArgumentException("cron 表达式非法: " + cron);
        }
        cron = cron.trim();
        if (StringUtils.isBlank(timezone)) {
            timezone = DEFAULT_TIMEZONE;
        }
        try {
            ZoneId.of(timezone);
        } catch (Exception e) {
            throw new IllegalArgumentException("时区非法: " + timezone);
        }
    }

    /**
     * 计算给定时间之后的下一个到期触发时间（按本时区语义）。
     *
     * @param from 起算时间
     * @return 下一次到期时间；表达式在可预见范围内无下次触发时返回 null
     */
    public OffsetDateTime nextAfter(OffsetDateTime from) {
        if (from == null) {
            throw new IllegalArgumentException("起算时间不能为null");
        }
        ZonedDateTime base = from.atZoneSameInstant(ZoneId.of(timezone));
        ZonedDateTime next = CronExpression.parse(cron).next(base);
        return next == null ? null : next.toOffsetDateTime();
    }

    /**
     * 未来运行时间预告（响应展示用，不落库）。
     *
     * @param from  起算时间
     * @param count 预告条数
     * @return 升序的到期时间列表（不足 count 条时以可计算结果截断）
     */
    public List<OffsetDateTime> upcomingRuns(OffsetDateTime from, int count) {
        if (count < 1) {
            throw new IllegalArgumentException("预告条数必须大于0");
        }
        List<OffsetDateTime> runs = new ArrayList<>(count);
        OffsetDateTime cursor = from;
        for (int i = 0; i < count; i++) {
            OffsetDateTime next = nextAfter(cursor);
            if (next == null) {
                break;
            }
            runs.add(next);
            cursor = next;
        }
        return List.copyOf(runs);
    }

    /**
     * 序列化为 JSONB 列文本（{@code {"cron":"...","timezone":"..."}}）。
     */
    public String toJson() {
        ObjectNode node = OBJECT_MAPPER.createObjectNode();
        node.put("cron", cron);
        node.put("timezone", timezone);
        return node.toString();
    }

    /**
     * 从 JSONB 列文本反序列化。
     *
     * @param json 列文本（空白返回 null，表示仅手动触发）
     * @return 调度配置；json 为空时 null
     * @throws IllegalArgumentException JSON 非法或缺 cron 字段
     */
    public static DeploymentSchedule fromJson(String json) {
        if (StringUtils.isBlank(json)) {
            return null;
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(json);
            JsonNode cronNode = node.get("cron");
            if (cronNode == null || cronNode.isNull()) {
                throw new IllegalArgumentException("schedule JSON 缺少 cron 字段");
            }
            JsonNode tzNode = node.get("timezone");
            return new DeploymentSchedule(cronNode.asText(), tzNode == null || tzNode.isNull() ? null : tzNode.asText());
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("schedule JSON 解析失败: " + e.getMessage(), e);
        }
    }
}
