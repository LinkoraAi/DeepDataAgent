package com.linkroa.deepdataagent.runtime.application.convert;

import com.linkroa.deepdataagent.runtime.application.command.AgentReference;
import com.linkroa.deepdataagent.runtime.application.command.CreateSessionCommand;
import com.linkroa.deepdataagent.runtime.application.command.ResolveHumanConfirmationCommand;
import com.linkroa.deepdataagent.runtime.application.command.SendMessageCommand;
import com.linkroa.deepdataagent.runtime.application.command.SessionResourceItem;
import com.linkroa.deepdataagent.runtime.application.command.UpdateSessionCommand;
import com.linkroa.deepdataagent.runtime.application.query.ListEventsQuery;
import com.linkroa.deepdataagent.runtime.application.query.ListSessionsQuery;
import com.linkroa.deepdataagent.runtime.application.query.ReplayQuery;
import com.linkroa.deepdataagent.runtime.application.validation.EventTypeValidator;
import com.linkroa.deepdataagent.runtime.domain.model.SessionResource;
import com.linkroa.deepdataagent.runtime.domain.model.enums.AgentSessionStatus;
import com.linkroa.deepdataagent.runtime.domain.model.enums.ChatEventType;
import com.linkroa.deepdataagent.shared.result.CursorPageParams;
import org.apache.commons.lang3.StringUtils;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * 运行时入参 → Command / Query / 领域值对象装配器（**只做映射与装配**）。
 * <p>入参一律为已归一的类型化对象（{@link SessionResourceItem} 等命令侧载体）或基础类型，
 * MUST NOT 接收 {@code controller.request} 类型——协议对象到命令的装配由控制器直接完成，
 * 本装配器不反向依赖接口层。跨字段规则校验（不可更新属性探测、入站 payload 结构、
 * 追加批次非空）一律下沉 {@link com.linkroa.deepdataagent.runtime.application.validation.InboundEventValidator}，
 * 由应用服务入口调用。</p>
 * <p>创建会话的 {@code agent} 引用解析（字符串 ID 或对象形态 → {@link AgentReference}）与
 * 遗留字段拒绝由 {@link #toAgentReference(Object)} / {@link #rejectLegacyCreateFields} 承担；
 * {@code metadata} 序列化在接口层完成后以基础类型传入。入站事件草案
 * （type + payload JSON）亦由接口层装配，未知事件类型名由其经校验器提前判别。</p>
 */
@Mapper
public interface AgentRuntimeCommandConvert {

    AgentRuntimeCommandConvert INSTANCE = Mappers.getMapper(AgentRuntimeCommandConvert.class);

    ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 创建请求遗留字段拒绝（对齐 sessions spec「创建请求遗留字段被拒」）：
     * 内联 {@code environment}、{@code vaults}、顶层 {@code memory_store_ids} 与
     * {@code delta_flush_interval_ms} 任一<b>非空提交</b>即抛参错（400 invalid_request_error），
     * 会话不落库——不得静默忽略，否则客户端会「创建成功但挂载丢失」。
     * <p>Memory Store 只能经 {@code resources[].memory_store} 挂载、Vault 只能经 {@code vault_ids[]}。</p>
     *
     * @param environment          内联环境遗留字段（非 null 即拒）
     * @param vaults               旧保管库列表遗留字段（非 null 即拒）
     * @param memoryStoreIds       顶层记忆库列表遗留字段（非 null 即拒）
     * @param deltaFlushIntervalMs 增量帧冲刷间隔遗留字段（非 null 即拒）
     * @throws IllegalArgumentException 任一遗留字段非空提交
     */
    default void rejectLegacyCreateFields(Object environment, List<Object> vaults,
                                          List<Object> memoryStoreIds, Object deltaFlushIntervalMs) {
        if (environment != null) {
            throw new IllegalArgumentException("environment 字段已废止，运行环境须经 environment_id 绑定");
        }
        if (vaults != null) {
            throw new IllegalArgumentException("vaults 字段已废止，保管库须经 vault_ids[] 绑定");
        }
        if (memoryStoreIds != null) {
            throw new IllegalArgumentException("memory_store_ids 字段已废止，记忆库须经 resources[].memory_store 挂载");
        }
        if (deltaFlushIntervalMs != null) {
            throw new IllegalArgumentException("delta_flush_interval_ms 字段已废止");
        }
    }

    /**
     * 会话创建 Agent 引用解析：字符串 ID 或 {@code {id, type:"agent", version?}} 对象 → 已归一引用。
     * <p>对象缺 {@code type:"agent"}、{@code id} 非非空字符串、{@code version} 非「正整数或
     * 0（省略 / 0 = 激活版本）」一律抛参错（400）；字符串形态空白同样拒绝。</p>
     *
     * @param agent 请求 {@code agent} 原始 JSON 值（字符串或对象）
     * @return 已归一 Agent 引用（显式版本为十进制文本，省略为 null）
     * @throws IllegalArgumentException 形态非法、{@code id} 非非空字符串或 {@code version} 非「正整数或 0」
     */
    default AgentReference toAgentReference(Object agent) {
        if (agent == null) {
            throw new IllegalArgumentException("智能体ID不能为空");
        }
        if (agent instanceof String text) {
            return new AgentReference(StringUtils.trimToNull(text), null);
        }
        if (agent instanceof Map<?, ?> map) {
            if (!"agent".equals(map.get("type"))) {
                throw new IllegalArgumentException("agent 引用对象必须携带 type:\"agent\"");
            }
            Object id = map.get("id");
            if (!(id instanceof String idText)) {
                throw new IllegalArgumentException("agent 引用对象必须携带字符串 id");
            }
            return new AgentReference(StringUtils.trimToNull(idText), parseAgentVersion(map.get("version")));
        }
        throw new IllegalArgumentException("agent 引用必须为字符串 ID 或 {id, type:\"agent\", version?} 对象");
    }

    /**
     * 显式版本号归一：省略 / {@code 0} → {@code null}（激活版本）；正整数（数字或数字字符串）
     * → 十进制文本；其余（负数 / 小数 / 非法文本 / 对象）抛参错。
     *
     * @param raw 请求 {@code agent.version} 原始值（可空 = 省略）
     * @return 十进制版本号文本；省略 / {@code 0} 时为 {@code null}
     * @throws IllegalArgumentException 取值非「正整数或 0」
     */
    private static String parseAgentVersion(Object raw) {
        if (raw == null) {
            return null;
        }
        Integer version = null;
        if (raw instanceof Number number) {
            double value = number.doubleValue();
            if (value == Math.rint(value)) {
                version = (int) value;
            }
        } else if (raw instanceof String text && StringUtils.isNotBlank(text)) {
            try {
                version = Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                version = null;
            }
        }
        if (version == null || version < 0) {
            throw new IllegalArgumentException("agent.version 必须为正整数或 0");
        }
        return version == 0 ? null : Integer.toString(version);
    }

    /**
     * 创建会话入参 → 命令（接口层按字段映射后传入：{@code agentVersion} 已由
     * {@link #toAgentReference(Object)} 归一——显式版本原样下发、省略为 null 由服务侧解析激活版本；
     * {@code metadataJson} 由接口层序列化，触发标记恒为空 = 普通用户会话）。
     * <p>挂载资源以已归一的 {@link SessionResourceItem} 列表传入（协议字段原样承载），
     * 由 {@link #toSessionResources} 按类型分派领域工厂；环境变量键值对在此序列化为 JSON 文本；
     * 环境 / 保管库 / 挂载的存在性与归属校验由应用服务承担。</p>
     */
    default CreateSessionCommand toCreateCommand(String userId, String agentId, String agentVersion, String title,
                                                 String metadataJson, List<SessionResourceItem> resources,
                                                 List<String> vaultIds, String environmentId,
                                                 Map<String, Object> environmentVariables) {
        return new CreateSessionCommand(userId, agentId, agentVersion, title, metadataJson,
                null, null, toSessionResources(resources), environmentId, vaultIds,
                toEnvironmentVariablesJson(environmentVariables));
    }

    /**
     * 已归一挂载资源项 → 领域值对象列表（null / 空收敛为空列表）。
     * <p>按 {@code type} 分派四工厂（file / github_repository / git_repository / memory_store）；
     * 未知类型直接拒绝（400 校验错误），不得静默降级——否则领域构造器的类型不变量将被架空
     * （挂载语义漂移成文件展开）。字段不变量由各工厂紧凑构造器兜底。</p>
     *
     * @param resources 已判别的挂载资源项（type + 按类型取用字段）
     */
    default List<SessionResource> toSessionResources(List<SessionResourceItem> resources) {
        if (resources == null || resources.isEmpty()) {
            return List.of();
        }
        return resources.stream()
                .map(AgentRuntimeCommandConvert::toSessionResource)
                .toList();
    }

    /** 单资源项按类型分派领域工厂（未知类型拒绝：判别式映射的必要守卫）。 */
    private static SessionResource toSessionResource(SessionResourceItem resource) {
        String type = resource.type();
        if (SessionResource.FILE_TYPE.equals(type)) {
            return SessionResource.file(resource.file_id(), resource.mount_path());
        }
        if (SessionResource.GITHUB_REPO_TYPE.equals(type)) {
            return SessionResource.githubRepository(resource.url(),
                    resource.authorization_token(), resource.checkout());
        }
        if (SessionResource.GIT_REPO_TYPE.equals(type)) {
            return SessionResource.gitRepository(resource.url(),
                    resource.password(), resource.checkout());
        }
        if (SessionResource.MEMORY_STORE_TYPE.equals(type)) {
            return SessionResource.memoryStore(resource.memory_store_id(), resource.access(),
                    resource.instructions());
        }
        throw new IllegalArgumentException("不支持的挂载资源类型: " + type);
    }

    /**
     * 会话级环境变量键值对 → JSON 文本（null / 空收敛为 null，由领域模型空白归一为 {@code "{}"}）。
     * <p>装配器只做序列化、不做形态校验：值的原始类型（字符串 / 数字 / 布尔 / 对象）须完整保留到
     * JSON 文本，形态判定（含「所有值 MUST 为字符串」）统一下沉应用级校验器
     * {@code SessionEnvironmentVariablesValidator}（创建与更新两路径共用同一判定，D11）。</p>
     */
    default String toEnvironmentVariablesJson(Map<String, Object> environmentVariables) {
        if (environmentVariables == null || environmentVariables.isEmpty()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(environmentVariables);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("环境变量序列化失败", e);
        }
    }

    /**
     * 发送事件 → 发送消息命令（message 已由接口层从入站事件 payload.text 提取为纯文本）。
     */
    default SendMessageCommand toSendCommand(String sessionId, String message) {
        return new SendMessageCommand(sessionId, message);
    }

    /**
     * 更新会话入参 → 命令（契约端点 {@code POST /sessions/{session_id}}：title 省略不改 /
     * 传 null 清空、metadata 为 patch（值为 null 的键删除）、环境变量整体替换的语义由应用服务承担）。
     * <p>{@code agent / environment_id / status} 为不可更新属性——请求体显式携带非空值即
     * 抛 {@link IllegalArgumentException}（400），不得静默忽略（对齐 spec「MUST NOT 修改」场景）；
     * 守卫在控制器实参求值期执行，先于应用服务的会话归属校验（保持既有 400 优先序）。</p>
     *
     * @param titlePresent             请求是否显式提交 {@code title}（false=省略=不改；true 时 null=清空）
     * @param agent                    请求体 {@code agent} 探测位（非空即视为显式提交不可更新属性）
     * @param metadataJson             metadata patch JSON（请求缺省时 MUST 传 null=不改）
     * @param environmentVariablesJson 环境变量序列化 JSON（请求缺省时 MUST 传 null=不改）
     */
    default UpdateSessionCommand toUpdateCommand(String sessionId, boolean titlePresent, String title,
                                                 Map<String, Object> agent, String environmentId, String status,
                                                 String metadataJson, String environmentVariablesJson) {
        if (agent != null || environmentId != null || status != null) {
            throw new IllegalArgumentException("agent / environment_id / status 不可更新");
        }
        return new UpdateSessionCommand(sessionId, titlePresent,
                titlePresent ? StringUtils.trimToNull(title) : null,
                metadataJson, environmentVariablesJson);
    }

    /**
     * 人工确认入参 → 命令（确认 / 拒绝，可携 {@code tool_use_id} 定位待确认工具调用）。
     * <p>REST 直连端点不携带拒绝说明（{@code deny_message} 仅入站
     * {@code user.tool_confirmation} 事件 payload 支持），恒传 null。</p>
     */
    default ResolveHumanConfirmationCommand toResolveHumanConfirmationCommand(String sessionId,
                                                                             Boolean confirmed,
                                                                             String toolUseId) {
        return new ResolveHumanConfirmationCommand(sessionId, Boolean.TRUE.equals(confirmed), toolUseId, null);
    }

    /**
     * 会话列表查询（契约端点 {@code GET /sessions}）：过滤集合严格对齐公开契约
     * {@code agent_id / agent_version / deployment_id / memory_store_id / statuses[] /
     * include_archived / created_at[gt|gte|lt|lte] / order}（<b>无</b> environment_id 与 metadata），
     * 叠加 shared/api-conventions Cursor 约定（limit 越界、page 与 after_id/before_id 互斥违规、
     * 非法状态名、非法时间 / order 统一抛 IAE → 400 {@code invalid_request_error}）。
     *
     * @param statuses 状态过滤字符串集合（规范小写如 {@code idle} / {@code running}，非法状态抛参错）
     * @param order    排序方向（{@code asc} / {@code desc}，缺省 desc；非法值抛参错）
     * @param page     不透明下页游标（响应 {@code next_page} 原样回传，与 after_id/before_id 互斥）
     */
    default ListSessionsQuery toListQuery(String userId, String agentId, String agentVersion,
                                          String deploymentId, String memoryStoreId, List<String> statuses,
                                          Boolean includeArchived,
                                          String createdAtGt, String createdAtGte,
                                          String createdAtLt, String createdAtLte,
                                          String order, String limit, String page,
                                          String afterId, String beforeId) {
        return new ListSessionsQuery(
                userId,
                agentId,
                agentVersion,
                deploymentId,
                memoryStoreId,
                parseStatuses(statuses),
                Boolean.TRUE.equals(includeArchived),
                parseInstant("created_at[gt]", createdAtGt),
                parseInstant("created_at[gte]", createdAtGte),
                parseInstant("created_at[lt]", createdAtLt),
                parseInstant("created_at[lte]", createdAtLte),
                parseAscending(order, false),
                CursorPageParams.parse(limit, page, afterId, beforeId));
    }

    /**
     * 事件列表查询（契约端点 {@code GET /sessions/{id}/events} 及其线程作用域嵌套端点）：
     * 补齐 {@code limit / page / after_id / before_id / order / types / created_at[gt|gte|lt|lte]} 全集。
     * <p>{@code types} 沿列表路径口径<b>静默剔除</b>未知类型（不因未知类型报错、不命中），
     * 与入站路径「未知类型 400」严格区分；{@code page} 为不透明下页游标（与
     * after_id / before_id 互斥）。</p>
     *
     * @param sessionThreadId 线程归属过滤（可空 = 全会话；线程嵌套端点传入）
     * @param order           方向（{@code asc} 缺省 / {@code desc}；非法抛参错）
     */
    default ListEventsQuery toListEventsQuery(String sessionId, String sessionThreadId, List<String> types,
                                              String createdAtGt, String createdAtGte,
                                              String createdAtLt, String createdAtLte,
                                              String order, String limit, String page,
                                              String afterId, String beforeId) {
        return new ListEventsQuery(
                sessionId,
                sessionThreadId,
                parseKnownTypes(types),
                parseInstant("created_at[gt]", createdAtGt),
                parseInstant("created_at[gte]", createdAtGte),
                parseInstant("created_at[lt]", createdAtLt),
                parseInstant("created_at[lte]", createdAtLte),
                parseAscending(order, true),
                CursorPageParams.parse(limit, page, afterId, beforeId));
    }

    /**
     * 已知事件类型过滤集合：未知 / 空白取值静默剔除（列表路径不对未知类型报错）。
     * <p>判定实现单点在 {@link EventTypeValidator#filterKnown}（列表与入站两条路径口径严格分离）。</p>
     */
    static List<ChatEventType> parseKnownTypes(List<String> types) {
        return EventTypeValidator.filterKnown(types);
    }

    /** 排序方向归一：{@code asc} → true、{@code desc} / 空白 → 缺省值、其余非法抛参错。 */
    private static boolean parseAscending(String order, boolean defaultAscending) {
        String normalized = StringUtils.trimToNull(order);
        if (normalized == null) {
            return defaultAscending;
        }
        if ("asc".equalsIgnoreCase(normalized)) {
            return true;
        }
        if ("desc".equalsIgnoreCase(normalized)) {
            return false;
        }
        throw new IllegalArgumentException("order 必须为 asc 或 desc: " + order);
    }

    /** RFC 3339 时间参数解析（空白归一为 null = 不过滤；非法取值抛参错 → 400）。 */
    private static OffsetDateTime parseInstant(String field, String raw) {
        String normalized = StringUtils.trimToNull(raw);
        if (normalized == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(normalized);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException(field + " 必须为 RFC 3339 时间: " + raw);
        }
    }

    /**
     * 解析状态过滤集合；经 {@link AgentSessionStatus#fromValue} 支持大小写不敏感
     * （规范小写 + 历史大写兼容），非法状态名抛参错（对齐领域状态枚举边界）。
     */
    static List<AgentSessionStatus> parseStatuses(List<String> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return List.of();
        }
        return statuses.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(s -> {
                    try {
                        AgentSessionStatus status = AgentSessionStatus.fromValue(s);
                        if (status == null) {
                            throw new IllegalArgumentException("非法会话状态: " + s);
                        }
                        return status;
                    } catch (IllegalArgumentException ex) {
                        throw new IllegalArgumentException("非法会话状态: " + s);
                    }
                })
                .toList();
    }

    /**
     * 回放查询（事件流 / 事件历史端点）：按会话级 {@code seq} 游标 + 事件类型过滤，不限量。
     *
     * @param types 事件类型过滤（null / 空表示不过滤；可能含非法值由接口层提前校验）
     */
    default ReplayQuery toReplayQuery(String sessionId, long afterSequenceNum, List<String> types) {
        return toReplayQuery(sessionId, afterSequenceNum, types, null);
    }

    /**
     * 回放查询（可限量，游标分页）：{@code limit} 为单页上限（null 表示不限量）。
     */
    default ReplayQuery toReplayQuery(String sessionId, long afterSequenceNum, List<String> types, Integer limit) {
        return new ReplayQuery(sessionId, Math.max(afterSequenceNum, 0), types, limit);
    }
}