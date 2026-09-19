package com.linkroa.deepdataagent.runtime.infrastructure.convert;

import com.linkroa.deepdataagent.runtime.domain.model.AgentSession;
import com.linkroa.deepdataagent.runtime.domain.model.ChatEvent;
import com.linkroa.deepdataagent.runtime.domain.model.SessionResource;
import com.linkroa.deepdataagent.runtime.domain.model.SessionThread;
import com.linkroa.deepdataagent.runtime.domain.model.enums.AgentSessionStatus;
import com.linkroa.deepdataagent.runtime.domain.model.enums.ChatEventType;
import com.linkroa.deepdataagent.runtime.domain.model.enums.TurnPhase;
import com.linkroa.deepdataagent.runtime.infrastructure.persistence.entity.AgentSessionEntity;
import com.linkroa.deepdataagent.runtime.infrastructure.persistence.entity.ChatEventEntity;
import com.linkroa.deepdataagent.runtime.infrastructure.persistence.entity.SessionThreadEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 领域对象 ⇄ 持久化实体的 MapStruct 转换器（runtime BC）。
 * <p>会话双列（{@code status} / {@code turn_phase}）与事件类型以字符串（小写规范值
 * {@code value()}）落库，与建表注释一致；反查经 default 方法严格 / 兜底解析。</p>
 * <p>会话 {@code metadata} / {@code resources} / {@code vault_ids} / {@code memory_store_ids} /
 * {@code environment_variables} 以 jsonb 文本落库：MapStruct 自动使用本接口声明的 default
 * 转换方法完成值 ⇄ JSON 文本的映射。挂载资源以 <b>snake_case 键</b>手工装配
 * （{@code file_id / mount_path / authorization_token / memory_store_id} 等，与对外契约一致；
 * 持久化全量含令牌，泄露门禁在响应层排除），非法 JSON 反查收敛为空集合（不阻断查询）。</p>
 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface RuntimePersistenceConvert {

    RuntimePersistenceConvert INSTANCE = Mappers.getMapper(RuntimePersistenceConvert.class);

    ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };

    // ===== AgentSession =====

    AgentSessionEntity toEntity(AgentSession session);

    AgentSession toDomain(AgentSessionEntity entity);

    // ===== ChatEvent =====

    // sessionThreadId（线程归属）显式声明双向映射，防止同名字段静默丢失（回归由
    // RuntimePersistenceConvertTest 的 Domain→Entity→Domain 全分量往返钉死）
    @Mapping(source = "sessionThreadId", target = "sessionThreadId")
    ChatEventEntity toEntity(ChatEvent event);

    @Mapping(source = "sessionThreadId", target = "sessionThreadId")
    ChatEvent toDomain(ChatEventEntity entity);

    // ===== SessionThread =====

    SessionThreadEntity toEntity(SessionThread thread);

    SessionThread toDomain(SessionThreadEntity entity);

    // ===== 枚举 ⇄ 字符串（小写规范值） =====

    default String sessionStatusToString(AgentSessionStatus status) {
        return status == null ? null : status.value();
    }

    default AgentSessionStatus stringToSessionStatus(String value) {
        return AgentSessionStatus.fromValue(value);
    }

    default String turnPhaseToString(TurnPhase phase) {
        return phase == null ? null : phase.value();
    }

    default TurnPhase stringToTurnPhase(String value) {
        return TurnPhase.fromValue(value);
    }

    default String chatEventTypeToString(ChatEventType type) {
        return type == null ? null : type.value();
    }

    default ChatEventType stringToChatEventType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return ChatEventType.fromValue(value);
    }

    // ===== 挂载资源 VO ⇄ jsonb 文本（snake_case 键） =====

    /**
     * 挂载资源列表 → jsonb 文本（null / 空收敛为 {@code []}；snake_case 键，空字段省略）。
     */
    default String sessionResourcesToString(List<SessionResource> resources) {
        if (resources == null || resources.isEmpty()) {
            return "[]";
        }
        List<Map<String, Object>> items = new ArrayList<>(resources.size());
        for (SessionResource resource : resources) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", resource.id());
            item.put("type", resource.type());
            putIfPresent(item, "file_id", resource.fileId());
            putIfPresent(item, "mount_path", resource.mountPath());
            putIfPresent(item, "url", resource.url());
            putIfPresent(item, "authorization_token", resource.authorizationToken());
            putIfPresent(item, "password", resource.password());
            putIfPresent(item, "checkout", resource.checkout());
            putIfPresent(item, "memory_store_id", resource.memoryStoreId());
            putIfPresent(item, "access", resource.access());
            putIfPresent(item, "instructions", resource.instructions());
            items.add(item);
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(items);
        } catch (JacksonException ex) {
            throw new IllegalArgumentException("会话挂载资源序列化失败", ex);
        }
    }

    /**
     * jsonb 文本 → 挂载资源列表（null / 空白 / 非法 JSON 收敛为空列表，不阻断查询）。
     */
    default List<SessionResource> stringToSessionResources(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(json);
            if (!root.isArray()) {
                return List.of();
            }
            List<SessionResource> resources = new ArrayList<>();
            for (JsonNode node : root) {
                resources.add(new SessionResource(
                        textOrNull(node, "id"),
                        textOrNull(node, "type"),
                        textOrNull(node, "file_id"),
                        textOrNull(node, "mount_path"),
                        textOrNull(node, "url"),
                        textOrNull(node, "authorization_token"),
                        textOrNull(node, "password"),
                        textOrNull(node, "checkout"),
                        textOrNull(node, "memory_store_id"),
                        textOrNull(node, "access"),
                        textOrNull(node, "instructions")));
            }
            return resources;
        } catch (JacksonException | IllegalArgumentException ex) {
            return List.of();
        }
    }

    // ===== 字符串列表 ⇄ jsonb 数组文本（vault_ids / memory_store_ids） =====

    /**
     * ID 列表 → jsonb 数组文本（null / 空收敛为 {@code []}）。
     */
    default String stringListToJson(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "[]";
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(values);
        } catch (JacksonException ex) {
            throw new IllegalArgumentException("会话挂载ID列表序列化失败", ex);
        }
    }

    /**
     * jsonb 数组文本 → ID 列表（null / 空白 / 非法 JSON 收敛为空列表，不阻断查询）。
     */
    default List<String> jsonToStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> values = OBJECT_MAPPER.readValue(json, STRING_LIST_TYPE);
            return values == null ? List.of() : values;
        } catch (JacksonException ex) {
            return List.of();
        }
    }

    /**
     * 值非 null 时写入 map（snake_case 装配辅助，省略空字段）。
     */
    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    /**
     * 读取节点字符串字段（缺失 / null 节点返回 null，辅助反序列化）。
     */
    private static String textOrNull(JsonNode node, String field) {
        JsonNode child = node.get(field);
        return child == null || child.isNull() ? null : child.asString();
    }
}
