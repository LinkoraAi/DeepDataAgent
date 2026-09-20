package com.linkroa.deepdataagent.runtime.domain.model.enums;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 聊天（会话）事件类型（对应 chat_event.type，SSE 的 {@code event} 字段与扁平 Event 顶层 {@code type}）。
 * <p>取值域对齐公开契约权威事件表（{@code {域}.{动作}} 形式，域小写、点分隔）：</p>
 * <ul>
 *   <li><b>入站</b>（用户投递白名单）：{@code user.message} / {@code user.interrupt} /
 *       {@code user.tool_confirmation} / {@code user.tool_result} / {@code user.custom_tool_result} /
 *       {@code system.message}（批尾注入，紧跟 user 事件）；</li>
 *   <li><b>出站 Agent</b>：{@code agent.message} / {@code agent.thinking} / {@code agent.tool_use} /
 *       {@code agent.tool_result} / {@code agent.custom_tool_use} / {@code agent.mcp_tool_use} /
 *       {@code agent.mcp_tool_result} / {@code agent.artifact_delivered}；</li>
 *   <li><b>会话</b>：{@code session.status_<status>}（四态 idle / running / rescheduled / terminated）/
 *       {@code session.error} / {@code session.updated} / {@code session.deleted}；</li>
 *   <li><b>线程</b>：{@code session.thread_created} / {@code session.thread_status_<status>}
 *       （四态，主线程状态镜像）；</li>
 *   <li><b>计量</b>：{@code span.model_request_start} / {@code span.model_request_end}
 *       （token 计量下沉，不产出 credits）；</li>
 *   <li><b>流式专用（不落库、不回放）</b>：{@code event_start} / {@code event_delta}。</li>
 * </ul>
 * <p>已废止词汇（MUST NOT 产出或接受）：{@code session.requires_action}、{@code session.interrupted}、
 * 六态旧状态事件（{@code status_processing} / {@code status_canceling} /
 * {@code status_waiting_confirmation} / {@code status_archived}）、{@code user.define_outcome}、
 * {@code span.outcome_evaluation_*}；{@code agent.thread_context_compacted} 仅登记不产出。</p>
 * <p>本枚举即对外 {@code type} 的唯一事实来源；未知 {@code type} MUST 返回 400（见 {@link #fromValue}）。</p>
 */
public enum ChatEventType {

    // ==================== 入站（投递即驱动 / 影响 turn） ====================

    /** 入站：用户消息（追加并跑 turn，payload.content MUST 为非空 content block 数组） */
    USER_MESSAGE("user.message"),
    /** 入站：中断进行中的 turn（回 idle，不产生 cancelled 终态；可选 session_thread_id） */
    USER_INTERRUPT("user.interrupt"),
    /** 入站：HITL 确认 / 拒绝（tool_use_id + result=allow|deny，deny_message 仅 deny 时合法） */
    USER_TOOL_CONFIRMATION("user.tool_confirmation"),
    /** 入站：自托管 worker 回传内置工具结果（tool_use_id + 可选 content blocks / is_error；续跑属执行面） */
    USER_TOOL_RESULT("user.tool_result"),
    /** 入站：自定义工具结果回传（custom_tool_use_id + 可选 content blocks / is_error；续跑属执行面） */
    USER_CUSTOM_TOOL_RESULT("user.custom_tool_result"),
    /** 入站：会话级 system 注入（每批至多一条、批尾、紧跟 user / tool_result，content 仅 text 块） */
    SYSTEM_MESSAGE("system.message"),

    // ==================== 出站 Agent ====================

    /** 出站：助手文本（payload.content 为 content block 数组） */
    AGENT_MESSAGE("agent.message"),
    /** 出站：思考阶段（无思考正文，口径为「仅存在性 + 空载荷」） */
    AGENT_THINKING("agent.thinking"),
    /** 出站：工具调用（payload.tool_use_id / name / input） */
    AGENT_TOOL_USE("agent.tool_use"),
    /** 出站：工具结果（payload.tool_use_id / name / state / output / truncated） */
    AGENT_TOOL_RESULT("agent.tool_result"),
    /** 出站：自定义工具调用（与入站 {@code user.custom_tool_result} 成对，外化执行面产出，本期登记值域） */
    AGENT_CUSTOM_TOOL_USE("agent.custom_tool_use"),
    /** 出站：MCP 工具调用（平台侧 JVM 内执行；payload.tool_use_id / name / input / mcp_server_name / evaluated_permission） */
    AGENT_MCP_TOOL_USE("agent.mcp_tool_use"),
    /** 出站：MCP 工具结果（每次 agent.mcp_tool_use 的配对结果；payload.tool_use_id / name / mcp_server_name / state） */
    AGENT_MCP_TOOL_RESULT("agent.mcp_tool_result"),
    /** 出站：产物交付（逐文件一条；payload.file_id / original_filename / size / content_type） */
    AGENT_ARTIFACT_DELIVERED("agent.artifact_delivered"),
    /** 出站：线程上下文压缩（仅登记值域，本期不产出） */
    AGENT_THREAD_CONTEXT_COMPACTED("agent.thread_context_compacted"),

    // ==================== 会话状态 / 终态 ====================

    /** 出站：会话状态 running（turn 启动） */
    SESSION_STATUS_RUNNING("session.status_running"),
    /** 出站：会话状态 idle（一轮正常结束 / 中断 / 出错回落） */
    SESSION_STATUS_IDLE("session.status_idle"),
    /** 出站：会话状态 rescheduled（重新调度中间态，本期无生产者） */
    SESSION_STATUS_RESCHEDULED("session.status_rescheduled"),
    /** 出站：会话状态 terminated（不可恢复终态） */
    SESSION_STATUS_TERMINATED("session.status_terminated"),
    /** 出站：不可恢复失败（payload.error 含 type / message / retry_status / error_code） */
    SESSION_ERROR("session.error"),
    /** 出站：会话更新（payload 选择性携带 title / metadata，永不回显环境变量） */
    SESSION_UPDATED("session.updated"),
    /** 出站：会话删除（级联前实时广播） */
    SESSION_DELETED("session.deleted"),

    // ==================== 线程（主线程状态镜像） ====================

    /** 出站：线程创建（单 Agent 场景不产出） */
    SESSION_THREAD_CREATED("session.thread_created"),
    /** 出站：线程状态 running（轮次开场镜像） */
    SESSION_THREAD_STATUS_RUNNING("session.thread_status_running"),
    /** 出站：线程状态 idle（轮次收场镜像） */
    SESSION_THREAD_STATUS_IDLE("session.thread_status_idle"),
    /** 出站：线程状态 rescheduled（本期无生产者） */
    SESSION_THREAD_STATUS_RESCHEDULED("session.thread_status_rescheduled"),
    /** 出站：线程状态 terminated（会话终止镜像） */
    SESSION_THREAD_STATUS_TERMINATED("session.thread_status_terminated"),

    // ==================== 模型调用计量 ====================

    /** 出站：模型调用开始（span 边界起点，落库审计） */
    SPAN_MODEL_REQUEST_START("span.model_request_start"),
    /** 出站：模型调用结束（payload.is_error / model_request_start_id / model_usage 下沉 token 计量） */
    SPAN_MODEL_REQUEST_END("span.model_request_end"),

    // ==================== 流式专用（不落库、不回放） ====================

    /** 流式专用：事件流开始（payload.event{id,type}，仅供实时流消费） */
    EVENT_START("event_start"),
    /** 流式专用：事件流增量（payload.event_id + delta，仅供实时流消费） */
    EVENT_DELTA("event_delta");

    /** 会话状态事件类型前缀（{@code session.status_}），用于动态判别四态事件。 */
    public static final String SESSION_STATUS_PREFIX = "session.status_";

    /** 线程状态事件类型前缀（{@code session.thread_status_}）。 */
    public static final String THREAD_STATUS_PREFIX = "session.thread_status_";

    /**
     * 工具调用事件类型全集（内置 {@code agent.tool_use} 与 MCP {@code agent.mcp_tool_use}）。
     * <p>两类事件载荷同形（{@code tool_use_id} / {@code name} / {@code input}），故 HITL 待确认现场
     * （durable 续跑明细重建与旧锚点次匹配）的事件表查询 MUST 按本集合匹配，
     * 否则 MCP 工具暂停的批次无法被解析。</p>
     */
    public static final List<String> TOOL_USE_TYPES = List.of(
            AGENT_TOOL_USE.value,
            AGENT_MCP_TOOL_USE.value
    );

    /**
     * 工具结果事件类型全集（内置 {@code agent.tool_result} 与 MCP {@code agent.mcp_tool_result}）。
     * <p>与 {@link #TOOL_USE_TYPES} 配对：未出现在结果集合中的工具调用即「未应答」——
     * durable HITL 等待现场（{@code awaiting_confirmation} 相位）的批次判据。</p>
     */
    public static final List<String> TOOL_RESULT_TYPES = List.of(
            AGENT_TOOL_RESULT.value,
            AGENT_MCP_TOOL_RESULT.value
    );

    /** 入站事件类型白名单（投递事件仅接受下表，其余视为未知类型）。 */
    private static final Set<String> INBOUND_TYPES = Set.of(
            USER_MESSAGE.value,
            USER_INTERRUPT.value,
            USER_TOOL_CONFIRMATION.value,
            USER_TOOL_RESULT.value,
            USER_CUSTOM_TOOL_RESULT.value,
            SYSTEM_MESSAGE.value
    );

    private static final Set<String> ALL_VALUES = Arrays.stream(values())
            .map(ChatEventType::value)
            .collect(Collectors.toUnmodifiableSet());

    private final String value;

    ChatEventType(String value) {
        this.value = value;
    }

    /**
     * 事件类型名（如 {@code "agent.message"} / {@code "session.status_idle"}）。
     *
     * @return 底层 {@code {域}.{动作}} 字符串
     */
    public String value() {
        return value;
    }

    /**
     * 是否入站事件类型（用户投递白名单）。
     *
     * @return true=入站
     */
    public boolean inbound() {
        return INBOUND_TYPES.contains(value);
    }

    /**
     * 是否已知事件类型（权威事件表成员）。
     *
     * @param type 事件类型字符串
     * @return true=已知
     */
    public static boolean isKnown(String type) {
        return type != null && ALL_VALUES.contains(type);
    }

    /**
     * 严格反解事件类型：未知取值（含大小写不一致、无域前缀的旧枚举名）抛
     * {@link IllegalArgumentException}（接口层据此返回 400）。
     *
     * @param raw 事件类型字符串（如 {@code "user.message"}）
     * @return 匹配的枚举
     */
    public static ChatEventType fromValue(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("聊天事件类型不能为空");
        }
        // 大小写不敏感匹配（权威事件表取值域小写，重复类型须经判别避免并存）
        for (ChatEventType type : values()) {
            if (type.value.equalsIgnoreCase(raw.trim().toLowerCase(Locale.ROOT))) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知事件类型: " + raw);
    }
}