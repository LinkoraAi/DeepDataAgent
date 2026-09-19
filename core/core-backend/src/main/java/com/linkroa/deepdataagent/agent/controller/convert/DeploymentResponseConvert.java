package com.linkroa.deepdataagent.agent.controller.convert;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkroa.deepdataagent.agent.controller.response.DeploymentResponse;
import com.linkroa.deepdataagent.agent.controller.response.DeploymentRunResponse;
import com.linkroa.deepdataagent.agent.controller.response.DeploymentScheduleResponse;
import com.linkroa.deepdataagent.agent.domain.model.Deployment;
import com.linkroa.deepdataagent.agent.domain.model.DeploymentRun;
import com.linkroa.deepdataagent.agent.domain.model.DeploymentSchedule;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * 调度器 → 响应 DTO 转换器（MapStruct 静态单例）。
 *
 * <p>领域侧 JSON 文本透传载荷（环境变量 / 资源 / 首批事件 / 元数据）解析为结构化对象回显；
 * 调度配置回显 {@code {cron, timezone}}；{@code upcoming_runs_at} 响应侧按当前时刻实时计算
 * （最多 5 条，不落库）；状态枚举序列化为源码小写值域字符串。</p>
 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface DeploymentResponseConvert {

    DeploymentResponseConvert INSTANCE = Mappers.getMapper(DeploymentResponseConvert.class);

    /** JSON 解析工具（容忍未知字段，透传载荷原样回显） */
    ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 计算时刻默认时区（与调度值对象一致） */
    ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");

    /** 未来到期时间预告条数 */
    int UPCOMING_RUNS_LIMIT = 5;

    TypeReference<Map<String, Object>> JSON_OBJECT_TYPE = new TypeReference<>() {
    };

    TypeReference<List<Map<String, Object>>> JSON_OBJECT_LIST_TYPE = new TypeReference<>() {
    };

    /**
     * 调度器 → 调度器响应 DTO（手工装配：camelCase 领域字段 → snake_case 协议字段，
     * JSON 文本解析回显 + 到期预告实时计算）。
     */
    default DeploymentResponse toResponse(Deployment deployment) {
        if (deployment == null) {
            return null;
        }
        return new DeploymentResponse(
                deployment.deploymentId(),
                deployment.name(),
                deployment.description(),
                deployment.agentId(),
                deployment.agentVersion(),
                deployment.environmentId(),
                parseJsonObject(deployment.environmentVariables()),
                parseJsonObjectList(deployment.resources()),
                deployment.vaultIds(),
                parseJsonObjectList(deployment.initialEvents()),
                parseJsonObject(deployment.metadata()),
                toScheduleResponse(deployment.schedule()),
                deployment.nextRunAt(),
                deployment.webhookToken(),
                deployment.status().getValue(),
                deployment.pausedReason(),
                deployment.lastRunAt(),
                deployment.lastSessionId(),
                deployment.lastStatus(),
                deployment.archivedAt(),
                deployment.createdAt(),
                deployment.updatedAt(),
                toUpcomingRunsAt(deployment.schedule())
        );
    }

    /**
     * 运行记录 → 响应 DTO（6.5 管理面：业务ID → {@code id}、触发方式 / 状态取枚举小写值域）。
     */
    default DeploymentRunResponse toRunResponse(DeploymentRun run) {
        if (run == null) {
            return null;
        }
        return new DeploymentRunResponse(
                run.runId(),
                DeploymentRunResponse.TYPE,
                run.deploymentId(),
                run.sessionId(),
                run.triggerKind().getValue(),
                run.status().getValue(),
                run.startedAt(),
                run.finishedAt()
        );
    }

    /**
     * 调度配置值对象 → 回显（null=仅手动/webhook 触发）。
     */
    default DeploymentScheduleResponse toScheduleResponse(DeploymentSchedule schedule) {
        return schedule == null ? null : new DeploymentScheduleResponse(schedule.cron(), schedule.timezone());
    }

    /**
     * 未来到期时间预告（有调度时按当前时刻升序推算最多 5 条；无调度为空列表）。
     */
    default List<OffsetDateTime> toUpcomingRunsAt(DeploymentSchedule schedule) {
        if (schedule == null) {
            return List.of();
        }
        return schedule.upcomingRuns(OffsetDateTime.now(DEFAULT_ZONE), UPCOMING_RUNS_LIMIT);
    }

    /**
     * JSON 对象文本 → 结构化 Map（空白归一空对象）。
     */
    default Map<String, Object> parseJsonObject(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return OBJECT_MAPPER.readValue(json, JSON_OBJECT_TYPE);
        } catch (Exception e) {
            throw new IllegalStateException("调度器 JSON 载荷解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * JSON 数组文本 → 结构化对象列表（空白归一空列表）。
     */
    default List<Map<String, Object>> parseJsonObjectList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return OBJECT_MAPPER.readValue(json, JSON_OBJECT_LIST_TYPE);
        } catch (Exception e) {
            throw new IllegalStateException("调度器 JSON 载荷解析失败: " + e.getMessage(), e);
        }
    }
}
