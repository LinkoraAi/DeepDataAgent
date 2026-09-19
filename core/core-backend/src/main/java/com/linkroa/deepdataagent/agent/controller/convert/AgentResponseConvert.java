package com.linkroa.deepdataagent.agent.controller.convert;

import com.linkroa.deepdataagent.agent.controller.response.AgentResponse;
import com.linkroa.deepdataagent.agent.domain.model.AgentDefinition;
import com.linkroa.deepdataagent.agent.domain.model.AgentVersion;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Agent 定义 / 版本快照 → Agent 对象响应转换器（对外契约中两者同形，共用 {@link AgentResponse}）。
 * <p>版本快照的 JSONB 配置列（{@code model} / {@code tools} / {@code mcp_servers} / {@code skills} /
 * {@code metadata} / {@code multiagent}）以协议层结构化 JSON 值回显，不把落库的 {@code *Json}
 * 文本透传给调用方；领域值对象（如 {@code AgentTool}）不进协议层，故此处按通用 JSON 结构解析
 * 而非领域 VO 映射。</p>
 * <p>归档仅以 {@code archived_at} 表达（无 archived 布尔）；{@code multiagent} 本期恒 {@code null}；
 * {@code model_profile_id} 等内部字段不外泄。</p>
 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface AgentResponseConvert {

    AgentResponseConvert INSTANCE = Mappers.getMapper(AgentResponseConvert.class);

    ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** Agent 业务类型词（标准对象头 {@code type}，Agent 对象与版本快照恒为此值）。 */
    String TYPE_AGENT = "agent";

    /**
     * Agent 定义 + 当前生效版本快照 → 完整 Agent 对象。
     * <p>{@code name} / {@code description} / {@code archived_at} / 时间戳取<b>定义</b>（Agent 对象
     * 自身属性为权威值）；{@code model} / {@code system} / 结构化配置与 {@code version} 取
     * <b>当前生效版本快照</b>。快照缺失（版本台账为空）时配置位取空形状、{@code version} 回落
     * {@code latest_version}。</p>
     *
     * @param definition     Agent 定义
     * @param currentVersion 当前生效版本快照（{@code active_version} 对应行，可空）
     * @return 完整 Agent 对象；{@code definition} 为 {@code null} 时返回 {@code null}
     */
    default AgentResponse toResponse(AgentDefinition definition, AgentVersion currentVersion) {
        if (definition == null) {
            return null;
        }
        return new AgentResponse(
                definition.agentId(),
                TYPE_AGENT,
                definition.name(),
                definition.description(),
                fromModelJson(currentVersion == null ? null : currentVersion.modelJson()),
                currentVersion == null ? null : currentVersion.systemPrompt(),
                fromJsonArray(currentVersion == null ? null : currentVersion.toolsJson()),
                fromJsonArray(currentVersion == null ? null : currentVersion.mcpServersJson()),
                fromJsonArray(currentVersion == null ? null : currentVersion.skillsJson()),
                fromJsonObject(currentVersion == null ? null : currentVersion.metadataJson()),
                fromNullableJsonObject(currentVersion == null ? null : currentVersion.multiagent()),
                currentVersion == null ? definition.latestVersion() : currentVersion.versionNumber(),
                definition.archivedAt(),
                definition.createdAt(),
                definition.updatedAt());
    }

    /**
     * 版本快照 → 版本快照对象（与 Agent 对象同形，{@code id} 为所属 Agent 业务 ID）。
     * <p>{@code name} / {@code description} / 时间戳取<b>快照自身</b>（发布时刻的定义属性复制值）；
     * {@code archived_at} 取所属定义当前归档时间（版本快照自身不带归档位）。</p>
     *
     * @param version    版本快照
     * @param archivedAt 所属 Agent 定义的归档时间（未归档传 {@code null}）
     * @return 版本快照对象；{@code version} 为 {@code null} 时返回 {@code null}
     */
    default AgentResponse toVersionResponse(AgentVersion version, OffsetDateTime archivedAt) {
        if (version == null) {
            return null;
        }
        return new AgentResponse(
                version.agentId(),
                TYPE_AGENT,
                version.name(),
                version.description(),
                fromModelJson(version.modelJson()),
                version.systemPrompt(),
                fromJsonArray(version.toolsJson()),
                fromJsonArray(version.mcpServersJson()),
                fromJsonArray(version.skillsJson()),
                fromJsonObject(version.metadataJson()),
                fromNullableJsonObject(version.multiagent()),
                version.versionNumber(),
                archivedAt,
                version.createdAt(),
                version.updatedAt());
    }

    /**
     * 模型引用 JSON → 协议层结构化回显（按提交形态还原：字符串简写 → 字符串，
     * 对象形态 → 键值对象；内部供应商映射引用不外泄到协议层）。
     *
     * @param modelJson 落库模型引用 JSON 文本（可空）
     * @return 字符串或键值对象；未配置返回 {@code null}
     */
    default Object fromModelJson(String modelJson) {
        if (modelJson == null || modelJson.isBlank()) {
            return null;
        }
        return OBJECT_MAPPER.readValue(modelJson, Object.class);
    }

    /**
     * JSONB 数组文本 → 协议层结构化数组（按提交形态原样回显；未配置 → 空数组，
     * 使调用方无需二次解析、也无需空值分支）。
     *
     * @param json 落库 JSON 文本（可空）
     * @return 结构化数组（未配置为空数组）
     * @throws IllegalStateException 落库文本非 JSON 数组
     */
    @SuppressWarnings("unchecked")
    default List<Object> fromJsonArray(String json) {
        Object parsed = parseJson(json);
        if (parsed == null) {
            return List.of();
        }
        if (!(parsed instanceof List<?>)) {
            throw new IllegalStateException("版本快照结构化数组解析失败：期望 JSON 数组");
        }
        return (List<Object>) parsed;
    }

    /**
     * JSONB 对象文本 → 协议层键值对象（未配置 → 空对象）。
     *
     * @param json 落库 JSON 文本（可空）
     * @return 结构化键值对象（未配置为空对象）
     * @throws IllegalStateException 落库文本非 JSON 对象
     */
    @SuppressWarnings("unchecked")
    default Map<String, Object> fromJsonObject(String json) {
        Object parsed = parseJson(json);
        if (parsed == null) {
            return Map.of();
        }
        if (!(parsed instanceof Map<?, ?>)) {
            throw new IllegalStateException("版本快照结构化对象解析失败：期望 JSON 对象");
        }
        return (Map<String, Object>) parsed;
    }

    /**
     * JSONB 对象文本 → 协议层键值对象（未配置 → {@code null}，保持契约「可空」语义；
     * 其余口径与 {@link #fromJsonObject(String)} 一致）。
     *
     * @param json 落库 JSON 文本（可空）
     * @return 结构化键值对象；未配置返回 {@code null}
     * @throws IllegalStateException 落库文本非 JSON 对象
     */
    default Map<String, Object> fromNullableJsonObject(String json) {
        Map<String, Object> parsed = fromJsonObject(json);
        return parsed.isEmpty() && (json == null || json.isBlank()) ? null : parsed;
    }

    /** 空白文本 → null（未配置）；其余按 JSON 解析。 */
    private static Object parseJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        return OBJECT_MAPPER.readValue(json, Object.class);
    }
}