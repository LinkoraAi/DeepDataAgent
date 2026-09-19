package com.linkroa.deepdataagent.runtime.controller.convert;

import com.linkroa.deepdataagent.runtime.controller.response.SessionResponse;
import com.linkroa.deepdataagent.runtime.controller.response.SessionResourceResponse;
import com.linkroa.deepdataagent.runtime.controller.response.ThreadResponse;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSession;
import com.linkroa.deepdataagent.runtime.domain.model.SessionResource;
import com.linkroa.deepdataagent.runtime.domain.model.SessionThread;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 领域对象 → 响应 DTO 转换器（会话部分手工装配，承载 snake_case 契约字段映射）。
 * <p>会话 {@code metadata} 以 JSON 文本持久化，此处解析为对象后返回；{@code agent}
 * 嵌入快照经双参重载接收应用层裁剪装配的完整快照（3.9），单参便捷入口降级为
 * {@code id / type / version} 摘要；{@code resources} 按类型回显（file：file_id / mount_path；github_repository：url /
 * checkout；git_repository：url / checkout；memory_store：memory_store_id / access / instructions），空值省略，
 * <b>MUST NOT 返回 authorization_token / password</b>（仓库凭证只写不读）。</p>
 */
@Mapper
public interface AgentRuntimeResponseConvert {

    AgentRuntimeResponseConvert INSTANCE = Mappers.getMapper(AgentRuntimeResponseConvert.class);

    ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    /**
     * 会话领域模型 → Session 响应（契约字段全集）。
     * <p>{@code deployment_id} 取触发来源调度器业务 ID（triggerId 的对外别名）；
     * {@code archived_at} 直接透传领域模型（归档时间戳已落库）；环境变量仅入参接受，
     * MUST NOT 回显；{@code usage} / {@code stats} / {@code outcome_evaluations} 不在本对象内嵌。</p>
     */
    default SessionResponse toSessionResponse(AgentSession session) {
        return toSessionResponse(session, agentSummary(session));
    }

    /**
     * 会话领域模型 → Session 响应（嵌入完整 Agent 快照版本，3.9）。
     *
     * @param session 会话领域模型
     * @param agent   嵌入 Agent 快照（应用层经 Agent 快照契约裁剪装配，snake_case 键对象）
     */
    default SessionResponse toSessionResponse(AgentSession session, Map<String, Object> agent) {
        return new SessionResponse(
                session.sessionId(),
                "session",
                agent,
                session.environmentId(),
                session.status().value(),
                session.title(),
                parseMetadata(session.metadata()),
                toResources(session.resources()),
                session.vaultIds(),
                session.triggerId(),
                session.archivedAt(),
                session.createdAt(),
                session.updatedAt()
        );
    }

    /**
     * Agent 摘要快照（单参便捷入口的降级形态；完整嵌入快照见双参重载 + 应用层装配）。
     */
    private Map<String, Object> agentSummary(AgentSession session) {
        Map<String, Object> agent = new LinkedHashMap<>();
        agent.put("id", session.agentId());
        agent.put("type", "agent");
        agent.put("version", parseVersionNumber(session.agentVersion()));
        return agent;
    }

    /**
     * 挂载资源值对象列表 → 响应对象数组（snake_case，按类型回显，空值省略）。
     * <p>GitHub 令牌（authorization_token）只写不读，MUST NOT 出现在回显中。</p>
     */
    private List<Map<String, Object>> toResources(List<SessionResource> resources) {
        if (resources == null || resources.isEmpty()) {
            return List.of();
        }
        return resources.stream().map(resource -> {
            Map<String, Object> item = new LinkedHashMap<>();
            // memory_store 资源 MUST NOT 携带资源 id 与时间戳（挂载顺序承载于数组下标）
            if (!"memory_store".equals(resource.type())) {
                item.put("id", resource.id());
            }
            item.put("type", resource.type());
            putIfPresent(item, "file_id", resource.fileId());
            putIfPresent(item, "mount_path", resource.mountPath());
            putIfPresent(item, "url", resource.url());
            putIfPresent(item, "checkout", resource.checkout());
            putIfPresent(item, "memory_store_id", resource.memoryStoreId());
            putIfPresent(item, "access", resource.access());
            putIfPresent(item, "instructions", resource.instructions());
            return item;
        }).toList();
    }

    /**
     * 追加挂载资源值对象列表 → 资源项响应（追加挂载成功返回形状）。
     * <p>资源 VO 自身不持有时间戳，挂载时刻取会话追加后的 {@code updated_at}
     * （{@code created_at} 与 {@code updated_at} 同值）；GitHub 令牌 MUST NOT 回显，
     * 本响应结构不承载令牌字段。</p>
     *
     * @param resources   已追加的挂载资源列表
     * @param mountedAt   挂载时刻（会话追加后的 updated_at）
     * @return 资源项响应列表（保序）
     */
    default List<SessionResourceResponse> toResourceResponses(List<SessionResource> resources,
                                                              OffsetDateTime mountedAt) {
        if (resources == null || resources.isEmpty()) {
            return List.of();
        }
        return resources.stream()
                .map(resource -> new SessionResourceResponse(
                        resource.id(),
                        resource.type(),
                        resource.fileId(),
                        resource.mountPath(),
                        mountedAt,
                        mountedAt))
                .toList();
    }

    /**
     * 挂载资源 → 资源管理端点响应（list / get / update 共用形态：按类型全字段回显 +
     * 挂载时间戳）。
     * <p>资源项不持有独立时间戳，挂载时刻取会话 {@code updated_at}（created_at 与
     * updated_at 同值）；GitHub 令牌（authorization_token）与通用 Git 密码（password）
     * 均只写不读，MUST NOT 出现。memory_store 资源 MUST NOT 携带资源 {@code id} 与时间戳
     * （挂载顺序承载于数组下标，对齐 sessions 规格）。</p>
     *
     * @param resource  挂载资源值对象
     * @param mountedAt 挂载时刻（所属会话的 updated_at）
     * @return 资源摘要响应
     */
    default Map<String, Object> toResourceDetail(SessionResource resource, OffsetDateTime mountedAt) {
        Map<String, Object> item = new LinkedHashMap<>();
        boolean memoryStore = "memory_store".equals(resource.type());
        // memory_store 资源不携带资源 id 与时间戳（对齐 sessions 规格）
        if (!memoryStore) {
            item.put("id", resource.id());
        }
        item.put("type", resource.type());
        putIfPresent(item, "file_id", resource.fileId());
        putIfPresent(item, "mount_path", resource.mountPath());
        putIfPresent(item, "url", resource.url());
        putIfPresent(item, "checkout", resource.checkout());
        putIfPresent(item, "memory_store_id", resource.memoryStoreId());
        putIfPresent(item, "access", resource.access());
        putIfPresent(item, "instructions", resource.instructions());
        if (!memoryStore) {
            item.put("created_at", mountedAt);
            item.put("updated_at", mountedAt);
        }
        return item;
    }

    /**
     * Session 线程领域模型 → 线程响应（公开契约 Thread 对象字段全集，严格不多不少）。
     * <p>{@code agent} 由线程持有的快照 JSON 文本解析为对象（非法 / 空白收敛为空对象）；
     * 主线程 {@code parent_thread_id} 与 {@code archived_at} 恒为 null；
     * MUST NOT 出现 name / role / stop_reason / usage 等旧字段。</p>
     *
     * @param thread 线程领域模型
     * @return 线程响应
     */
    default ThreadResponse toThreadResponse(SessionThread thread) {
        return new ThreadResponse(
                thread.threadId(),
                "session_thread",
                thread.sessionId(),
                thread.parentThreadId(),
                parseSnapshot(thread.agent()),
                thread.status().value(),
                thread.archivedAt(),
                thread.createdAt(),
                thread.updatedAt());
    }

    /**
     * Session 线程领域模型列表 → 线程响应列表（保序：主线程由查询面排首位）。
     *
     * @param threads 线程领域模型列表（null / 空收敛为空列表）
     * @return 线程响应列表
     */
    default List<ThreadResponse> toThreadResponses(List<SessionThread> threads) {
        if (threads == null || threads.isEmpty()) {
            return List.of();
        }
        return threads.stream().map(this::toThreadResponse).toList();
    }

    /** 线程 Agent 快照 JSON 文本 → 对象（非法 / 空白收敛为空 Map）。 */
    private Map<String, Object> parseSnapshot(String snapshot) {
        if (snapshot == null || snapshot.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = OBJECT_MAPPER.readValue(snapshot, MAP_TYPE);
            return parsed == null ? Map.of() : parsed;
        } catch (Exception ex) {
            return Map.of();
        }
    }

    /** 值非空时写入 map（snake_case 契约空值省略）。 */
    private static void putIfPresent(Map<String, Object> item, String key, String value) {
        if (value != null && !value.isBlank()) {
            item.put(key, value);
        }
    }

    /** 发布号字符串 → int（非法收敛为 0）。 */
    private int parseVersionNumber(String agentVersion) {
        if (agentVersion == null || agentVersion.isBlank()) {
            return 0;
        }
        try {
            return Math.max(Integer.parseInt(agentVersion), 0);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    /** metadata JSON 文本 → 对象（非法/空白收敛为空 Map）。 */
    private Map<String, Object> parseMetadata(String metadata) {
        if (metadata == null || metadata.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = OBJECT_MAPPER.readValue(metadata, MAP_TYPE);
            return parsed == null ? Map.of() : parsed;
        } catch (Exception ex) {
            return Map.of();
        }
    }
}