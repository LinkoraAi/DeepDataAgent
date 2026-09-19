package com.linkroa.deepdataagent.agent.application.convert;

import com.linkroa.deepdataagent.agent.application.command.CreateAgentCommand;
import com.linkroa.deepdataagent.agent.application.command.PublishAgentVersionCommand;
import com.linkroa.deepdataagent.agent.application.command.UpdateAgentCommand;
import com.linkroa.deepdataagent.agent.application.query.ListAgentQuery;
import com.linkroa.deepdataagent.agent.controller.request.AgentConfigRequest;
import com.linkroa.deepdataagent.agent.controller.request.UpdateAgentRequest;
import com.linkroa.deepdataagent.shared.result.CursorPageParams;
import org.apache.commons.lang3.StringUtils;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * Agent 配置请求转换器（Request → Command，创建与发布复用同一请求体「配置即版本」）。
 * <p>模型引用、工具 / MCP / 技能配方与元数据在协议层以结构化形态提交，此处统一序列化为
 * JSON 字符串随命令下发，落 {@code agent_version.*_json} 各列。</p>
 */
@Mapper
public interface AgentCommandConvert {

    AgentCommandConvert INSTANCE = Mappers.getMapper(AgentCommandConvert.class);

    ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    default CreateAgentCommand toCreateCommand(AgentConfigRequest request) {
        rejectAgentsMd(request.agentsMd());
        return new CreateAgentCommand(
                request.name(),
                request.description(),
                request.system(),
                toJsonValue(request.model(), "模型引用"),
                toJsonArray(request.tools()),
                toJsonArray(request.mcpServers()),
                toJsonArray(request.skills()),
                toJsonValue(request.multiagent(), "multiagent"),
                toJsonValue(request.metadata(), "元数据")
        );
    }

    default PublishAgentVersionCommand toPublishCommand(String agentId, AgentConfigRequest request) {
        rejectAgentsMd(request.agentsMd());
        return new PublishAgentVersionCommand(
                agentId,
                StringUtils.trimToNull(request.name()),
                request.description(),
                request.system(),
                toJsonValue(request.model(), "模型引用"),
                toJsonArray(request.tools()),
                toJsonArray(request.mcpServers()),
                toJsonArray(request.skills()),
                toJsonValue(request.multiagent(), "multiagent"),
                toJsonValue(request.metadata(), "元数据")
        );
    }

    /**
     * 组装 Agent 列表游标查询（HTTP 原始参数归一：status 词汇、metadata JSON 对象、
     * RFC3339 时间界、Cursor limit 越界统一抛 IAE → 400 invalid_request_error）。
     */
    default ListAgentQuery toListQuery(String keyword, String status, String metadata,
                                       String createdAfter, String createdBefore,
                                       String limit, String afterId, String beforeId) {
        return new ListAgentQuery(
                StringUtils.trimToNull(keyword),
                StringUtils.trimToNull(status),
                normalizeMetadataFilter(metadata),
                parseTimestamp(createdAfter, "created_after"),
                parseTimestamp(createdBefore, "created_before"),
                CursorPageParams.parse(limit, afterId, beforeId));
    }

    default UpdateAgentCommand toUpdateCommand(String agentId, UpdateAgentRequest request) {
        return new UpdateAgentCommand(
                agentId,
                StringUtils.trimToNull(request.name()),
                request.description(),
                request.version());
    }

    /** AGENTS.md 字段已废止：任何提交（含空串 / 空白串）一律 400 invalid_request_error；字段缺省（null）放行。 */
    private static void rejectAgentsMd(String agentsMd) {
        if (agentsMd != null) {
            throw new IllegalArgumentException("agents_md 字段已废止，指令统一由 system 承载");
        }
    }

    /** 元数据过滤串校验：必须是 JSON 对象文本（{@code @>} 包含语义）；空白归一为不过滤。 */
    private static String normalizeMetadataFilter(String metadata) {
        if (StringUtils.isBlank(metadata)) {
            return null;
        }
        try {
            Object parsed = OBJECT_MAPPER.readValue(metadata, Object.class);
            if (!(parsed instanceof Map)) {
                throw new IllegalArgumentException("metadata 过滤条件必须是 JSON 键值对象");
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("metadata 过滤条件不是合法 JSON: " + metadata);
        }
        return metadata;
    }

    /** RFC3339 时间参数解析（可空 = 不设界；非法格式抛 IAE → 400）。 */
    private static OffsetDateTime parseTimestamp(String raw, String paramName) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(paramName + " 必须为 RFC3339 时间格式: " + raw);
        }
    }

    /** 结构化数组序列化为 JSON 字符串（null / 空数组视为未配置，返回 {@code null}）。 */
    private static String toJsonArray(java.util.List<Object> value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("配置数组序列化失败", e);
        }
    }

    /**
     * 结构化值序列化为 JSON 字符串（可空 = 未配置；字符串简写形态带引号落 JSON）。
     */
    private static String toJsonValue(Object value, String fieldName) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            if (text.isBlank()) {
                return null;
            }
            try {
                return OBJECT_MAPPER.writeValueAsString(text);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException(fieldName + "序列化失败", e);
            }
        }
        if (value instanceof Map || value instanceof java.util.List) {
            try {
                return OBJECT_MAPPER.writeValueAsString(value);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException(fieldName + "序列化失败", e);
            }
        }
        throw new IllegalArgumentException("不支持的" + fieldName + "提交形态: " + value.getClass().getSimpleName());
    }
}