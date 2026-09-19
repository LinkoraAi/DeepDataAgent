package com.linkroa.deepdataagent.agent.application.convert;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkroa.deepdataagent.agent.application.command.CreateDeploymentCommand;
import com.linkroa.deepdataagent.agent.application.command.UpdateDeploymentCommand;
import com.linkroa.deepdataagent.agent.application.query.ListDeploymentRunsQuery;
import com.linkroa.deepdataagent.agent.application.query.ListDeploymentsQuery;
import com.linkroa.deepdataagent.agent.controller.request.CreateDeploymentRequest;
import com.linkroa.deepdataagent.agent.controller.request.DeploymentScheduleRequest;
import com.linkroa.deepdataagent.agent.controller.request.UpdateDeploymentRequest;
import com.linkroa.deepdataagent.agent.domain.model.DeploymentSchedule;
import com.linkroa.deepdataagent.agent.domain.model.enums.DeploymentStatus;
import com.linkroa.deepdataagent.shared.result.CursorPageParams;
import org.apache.commons.lang3.StringUtils;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 调度器请求 → 命令转换器（MapStruct 静态单例，协议层 → 应用层装配）。
 *
 * <p>透传载荷（环境变量 / 资源 / 首批事件 / 元数据）在协议层为结构化对象，
 * 装配命令侧统一序列化为 JSON 文本（领域模型以 JSON 文本组件承载，runtime 侧解释）；
 * 序列化失败抛 {@link IllegalArgumentException}，在进入应用服务前即被拒绝。
 * {@code schedule} 缺省（null 或 cron 空白）装配为 null，即「仅手动/webhook 触发」语义。</p>
 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface DeploymentCommandConvert {

    DeploymentCommandConvert INSTANCE = Mappers.getMapper(DeploymentCommandConvert.class);

    /** JSON 序列化工具（省略 null 字段，透传载荷保持紧凑） */
    ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    /** 元数据浅合并增量序列化工具（保留 null 值键 = 键级删除标记） */
    ObjectMapper MERGE_MAPPER = new ObjectMapper();

    /**
     * 创建调度器请求 → 创建调度器命令（手工装配：snake_case 协议字段 → camelCase 命令字段，
     * 结构化透传载荷序列化为 JSON 文本）。
     */
    default CreateDeploymentCommand toCreateCommand(CreateDeploymentRequest request) {
        if (request == null) {
            return null;
        }
        return new CreateDeploymentCommand(
                request.name(),
                request.description(),
                request.agent_id(),
                request.agent_version(),
                request.environment_id(),
                toJsonText(request.environment_variables()),
                toJsonText(request.resources()),
                request.vault_ids(),
                toJsonText(request.initial_events()),
                toJsonText(request.metadata()),
                toSchedule(request.schedule()),
                Boolean.TRUE.equals(request.webhook())
        );
    }

    /**
     * 调度配置请求项 → 领域值对象（null / cron 空白归一为 null=仅手动触发；
     * 表达式与时区合法性由值对象构造器校验）。
     */
    default DeploymentSchedule toSchedule(DeploymentScheduleRequest request) {
        if (request == null || StringUtils.isBlank(request.cron())) {
            return null;
        }
        return new DeploymentSchedule(request.cron(), request.timezone());
    }

    /**
     * 结构化对象 → JSON 文本（null 原样返回 null，由领域模型空白归一默认值）。
     */
    default String toJsonText(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("透传载荷 JSON 序列化失败: " + e.getMessage(), e);
        }
    }

    /** merge-patch 可调键白名单（未知键拒绝 400，绑定关系与 webhook 开通状态不可调） */
    Set<String> UPDATABLE_FIELDS = Set.of("name", "description", "environment_id",
            "environment_variables", "resources", "vault_ids", "initial_events", "metadata", "schedule");

    /**
     * 游标列表 HTTP 原始参数 → 查询对象（6.5 管理面 Cursor 约定）。
     * <p>{@code status} 取 active/paused（空白=不过滤，非法 → 400）；
     * {@code created_at[gte]/[lte]} 为 ISO-8601 带时区时间文本（空白=不过滤，非法 → 400）；
     * {@code include_archived} 仅接受 {@code true}/{@code false}（缺省 false=排除归档）。</p>
     */
    default ListDeploymentsQuery toListQuery(Long ownerId, String status, String agentId,
                                             String createdAfter, String createdBefore,
                                             String includeArchived,
                                             String limit, String afterId, String beforeId) {
        return new ListDeploymentsQuery(
                ownerId,
                StringUtils.isBlank(status) ? null : DeploymentStatus.fromValue(status),
                StringUtils.trimToNull(agentId),
                parseTime(createdAfter, "created_at[gte]"),
                parseTime(createdBefore, "created_at[lte]"),
                parseBoolean(includeArchived),
                CursorPageParams.parse(limit, afterId, beforeId)
        );
    }

    /**
     * 运行记录游标列表 HTTP 原始参数 → 查询对象（6.5 管理面 Cursor 约定）。
     * <p>{@code deploymentId} 空白归一 null（=全局作用域）；
     * {@code created_at[gte]/[lte]} 为 ISO-8601 带时区时间文本（空白=不过滤，非法 → 400）。</p>
     */
    default ListDeploymentRunsQuery toListRunsQuery(String deploymentId, Long ownerId,
                                                    String createdAfter, String createdBefore,
                                                    String limit, String afterId, String beforeId) {
        return new ListDeploymentRunsQuery(
                StringUtils.trimToNull(deploymentId),
                ownerId,
                parseTime(createdAfter, "created_at[gte]"),
                parseTime(createdBefore, "created_at[lte]"),
                CursorPageParams.parse(limit, afterId, beforeId)
        );
    }

    /**
     * merge-patch 补丁体 → 更新命令（6.5 管理面三态裁决）。
     * <p>以 {@code containsKey} 区分「缺省不改」与「显式提供」：present 且值为 null 装配为
     * 值字段 null + present=true（应用层解释为清空）；{@code name} 显式 null / 空白拒绝（400）；
     * 未知键拒绝（400）；结构化载荷（环境变量 / 资源 / 首批事件）序列化为 JSON 文本，
     * {@code metadata} 增量保留 null 值键（键级 null = 应用层删除该键的删除标记）。</p>
     */
    default UpdateDeploymentCommand toUpdateCommand(String deploymentId, UpdateDeploymentRequest request) {
        Map<String, Object> fields = request == null ? Map.of() : request.fields();
        for (String key : fields.keySet()) {
            if (!UPDATABLE_FIELDS.contains(key)) {
                throw new IllegalArgumentException("不支持更新的字段: " + key);
            }
        }
        String name = null;
        if (fields.containsKey("name")) {
            name = requireNonBlankText(fields.get("name"), "name");
        }
        return new UpdateDeploymentCommand(
                deploymentId,
                name,
                nullableText(fields, "description"),
                fields.containsKey("description"),
                nullableText(fields, "environment_id"),
                fields.containsKey("environment_id"),
                toJsonText(objectValue(fields, "environment_variables")),
                fields.containsKey("environment_variables"),
                toJsonText(arrayValue(fields, "resources")),
                fields.containsKey("resources"),
                nullableStringList(fields, "vault_ids"),
                fields.containsKey("vault_ids"),
                toJsonText(arrayValue(fields, "initial_events")),
                fields.containsKey("initial_events"),
                toMergeJsonText(objectValue(fields, "metadata")),
                fields.containsKey("metadata"),
                toScheduleValue(nullableValue(fields, "schedule")),
                fields.containsKey("schedule")
        );
    }

    /**
     * schedule 补丁值 → 领域值对象（显式 null=清空；对象体要求含 cron，
     * 表达式与时区合法性由值对象构造器校验，非法 → 400）。
     */
    default DeploymentSchedule toScheduleValue(Object value) {
        if (value == null) {
            return null;
        }
        Map<String, Object> node = asObjectMapStatic(value, "schedule");
        Object cron = node.get("cron");
        if (cron == null || StringUtils.isBlank(String.valueOf(cron))) {
            throw new IllegalArgumentException("schedule 缺少 cron 字段（清空调度请显式置 schedule=null）");
        }
        Object timezone = node.get("timezone");
        return new DeploymentSchedule(String.valueOf(cron), timezone == null ? null : String.valueOf(timezone));
    }

    /**
     * 键存在时的可空文本（显式 null → null；非字符串 → 400）。
     */
    private static String nullableText(Map<String, Object> fields, String key) {
        Object value = fields.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(key + " 必须为字符串");
        }
        return text;
    }

    /**
     * 必填非空文本（name 专用：显式 null / 空白 / 非字符串一律 400）。
     */
    private static String requireNonBlankText(Object value, String key) {
        if (!(value instanceof String text) || StringUtils.isBlank(text)) {
            throw new IllegalArgumentException(key + " 不能为空");
        }
        return text.trim();
    }

    /**
     * 键存在时的原始可空值（缺省与显式 null 均返回 null，present 判断由调用方 containsKey 完成）。
     */
    private static Object nullableValue(Map<String, Object> fields, String key) {
        return fields.get(key);
    }

    /**
     * 键存在时的 JSON 对象值（显式 null → null；非对象 → 400）。
     */
    private static Map<String, Object> objectValue(Map<String, Object> fields, String key) {
        Object value = fields.get(key);
        return value == null ? null : asObjectMapStatic(value, key);
    }

    /**
     * 键存在时的 JSON 数组值（显式 null → null；非数组 → 400）。
     */
    private static List<?> arrayValue(Map<String, Object> fields, String key) {
        Object value = fields.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException(key + " 必须为 JSON 数组");
        }
        return list;
    }

    /**
     * 键存在时的字符串数组值（显式 null → null；元素非字符串 → 400）。
     */
    private static List<String> nullableStringList(Map<String, Object> fields, String key) {
        Object value = fields.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException(key + " 必须为字符串数组");
        }
        return list.stream().map(item -> {
            if (!(item instanceof String text)) {
                throw new IllegalArgumentException(key + " 元素必须为字符串");
            }
            return text;
        }).toList();
    }

    /**
     * 元数据浅合并增量 → JSON 文本（保留 null 值键 = 键级删除标记，
     * 与 {@code OBJECT_MAPPER} 的 NON_NULL 省略策略分离；调用方已完成对象形态校验）。
     */
    default String toMergeJsonText(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return MERGE_MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("metadata 增量 JSON 序列化失败: " + e.getMessage(), e);
        }
    }

    /**
     * JSON 对象值校验（非对象 → 400）。
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObjectMapStatic(Object value, String key) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(key + " 必须为 JSON 对象");
        }
        return (Map<String, Object>) map;
    }

    /**
     * ISO-8601 时间参数解析（空白 → null，非法 → 400）。
     */
    private static OffsetDateTime parseTime(String raw, String paramName) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(paramName + " 必须为 ISO-8601 时间: " + raw);
        }
    }

    /**
     * 布尔查询参数解析（仅接受 true/false，缺省 false，其他 → 400）。
     */
    private static boolean parseBoolean(String raw) {
        if (StringUtils.isBlank(raw)) {
            return false;
        }
        String normalized = raw.trim().toLowerCase();
        if (!"true".equals(normalized) && !"false".equals(normalized)) {
            throw new IllegalArgumentException("include_archived 仅接受 true/false: " + raw);
        }
        return "true".equals(normalized);
    }
}
