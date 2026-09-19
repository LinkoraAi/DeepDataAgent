package com.linkroa.deepdataagent.runtime.controller.rest;

import com.linkroa.deepdataagent.runtime.application.command.InboundEventDraft;
import com.linkroa.deepdataagent.runtime.application.contract.SseEventEnvelope;
import com.linkroa.deepdataagent.runtime.application.convert.AgentRuntimeCommandConvert;
import com.linkroa.deepdataagent.runtime.application.service.AgentRuntimeQueryService;
import com.linkroa.deepdataagent.runtime.application.service.event.InboundEventService;
import com.linkroa.deepdataagent.runtime.application.service.subscription.SessionSubscriptionService;
import com.linkroa.deepdataagent.runtime.application.validation.InboundEventValidator;
import com.linkroa.deepdataagent.runtime.controller.request.SendEventRequest;
import com.linkroa.deepdataagent.shared.constant.api.ApiVersionConstants;
import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import com.linkroa.deepdataagent.shared.result.ApiResponse;
import com.linkroa.deepdataagent.shared.result.CursorPage;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 聊天事件 REST 控制器（前缀 {@code /api/v1/cloud/sessions/{sessionId}/events}，对齐 Managed Agents Event 接口）。
 * <p>事件契约下事件身份由顶层 {@code type}（{@code {域}.{动作}}）判别，其余字段为该事件类型自有的顶层字段；
 * 协议层只做参数解析、类型解析与形态归一（零领域 / 基础设施引用）：</p>
 * <ul>
 *   <li>{@code POST .../events}：请求体为 {@code {"events":[...]}}，逐条取顶层 {@code type} 判别事件类型
 *       （未知类型 400 {@code unknown_event_type}，先于任何会话查询），其余字段序列化为类型特化
 *       payload；入站事件经应用服务批量落库（全有或全无）并回显已落库扁平 Event，
 *       随后按语义驱动 turn（user.message → 跑 turn；user.interrupt → 中断；
 *       user.tool_confirmation → HITL 确认 / 拒绝）；</li>
 *   <li>{@code GET .../events}：按 {@code limit / page / after_id / before_id / order / types /
 *       created_at[gt|gte|lt|lte]} 过滤分页列出扁平 Event（响应 {@code {data,first_id,last_id,has_more,next_page}}）；
 *       携带 {@code Accept: text/event-stream} 时同一路径切换为 SSE 行为（与独立流端点等价）；</li>
 *   <li>{@code GET .../events/stream}：权威 SSE 订阅端点（编排全部下沉
 *       {@link SessionSubscriptionService}），下发 {@code : connected} 后按 {@code Last-Event-ID}
 *       （evt_ 事件 ID 游标，兼容数字 seq）续推 + 实时订阅；增量帧经 {@code event_deltas[]}
 *       连接级协商（缺省仅 buffered，非法取值 400）。</li>
 * </ul>
 */
@RestController
@RequestMapping(path = "/cloud/sessions/{sessionId}/events", version = ApiVersionConstants.CURRENT_API_VERSION)
public class AgentChatEventController {

    private static final String LAST_EVENT_ID_HEADER = "Last-Event-ID";

    @Resource
    private InboundEventService inboundEventService;
    @Resource
    private AgentRuntimeQueryService queryService;
    @Resource
    private SessionSubscriptionService subscriptionService;
    @Resource
    private ObjectMapper objectMapper;

    /**
     * 发送事件（对齐 {@code POST /sessions/{session_id}/events}）。
     * <p>控制器只做**类型解析与形态归一**：请求体 {@code events[]} 每项的顶层 {@code type} 经
     * {@link InboundEventValidator#parseKnownType} 解析（非权威事件表成员 → 400
     * {@code unknown_event_type}，先于任何会话查询），其余字段（content / tool_use_id / result 等）
     * 直接位于事件对象顶层、原样序列化为类型特化 JSON（MUST NOT 使用嵌套 payload 包装）；
     * 跨字段结构校验（content / confirmation / tool_result / system.message 批规则）由应用服务
     * 入口首行调用校验器，整批全有或全无（单事务落库）；成功响应为 HTTP 200 与
     * {@code {"data":[…已落库扁平 Event…]}}（顺序与提交一致）。</p>
     */
    @PostMapping
    public ApiResponse<List<SseEventEnvelope>> sendEvent(@PathVariable String sessionId,
                                                         @Valid @RequestBody SendEventRequest request) {
        List<InboundEventDraft> drafts = request.events().stream()
                .map(this::toDraft)
                .toList();
        return ApiResponse.success(inboundEventService.ingestInboundEventEnvelopes(sessionId, drafts));
    }

    /**
     * 列出会话事件历史（对齐 {@code GET /sessions/{session_id}/events}，JSON 形态）。
     * <p>{@code limit} 默认 20、范围 1–100（越界 400）；{@code page} 为响应 {@code next_page}
     * 回传的不透明游标（与 {@code after_id} / {@code before_id} 互斥，同传 400）；
     * {@code after_id} / {@code before_id} 为 {@code evt_} 事件 ID 定位游标；{@code order}
     * 取 {@code asc}（缺省）/ {@code desc}（非法 400）；{@code types} 为类型过滤
     * （<b>无法识别的类型静默忽略</b>，不报错、不命中）；{@code created_at[gt|gte|lt|lte]}
     * 为 RFC 3339 时间边界（非法 400）。响应形状 {@code {data, first_id, last_id, has_more, next_page}}，
     * 事件 {@code evt_} ID 作为所有分页游标。</p>
     * <p>请求头 {@code Accept: text/event-stream} 时由同时匹配的流式映射接管（见
     * {@link #streamEvents}），两条接入路径共用同一 handler / 序列化器 / 心跳 / 增量协商。</p>
     */
    @GetMapping
    public ApiResponse<CursorPage<SseEventEnvelope>> listEvents(
            @PathVariable String sessionId,
            @RequestParam(name = "limit", required = false) String limit,
            @RequestParam(name = "page", required = false) String page,
            @RequestParam(name = "after_id", required = false) String afterId,
            @RequestParam(name = "before_id", required = false) String beforeId,
            @RequestParam(name = "order", required = false) String order,
            @RequestParam(name = "types", required = false) List<String> types,
            @RequestParam(name = "created_at[gt]", required = false) String createdAtGt,
            @RequestParam(name = "created_at[gte]", required = false) String createdAtGte,
            @RequestParam(name = "created_at[lt]", required = false) String createdAtLt,
            @RequestParam(name = "created_at[lte]", required = false) String createdAtLte) {
        return ApiResponse.success(queryService.listEventPage(AgentRuntimeCommandConvert.INSTANCE
                .toListEventsQuery(sessionId, null, types, createdAtGt, createdAtGte,
                        createdAtLt, createdAtLte, order, limit, page, afterId, beforeId)));
    }

    /**
     * 事件订阅端点（{@code GET /events} 携带 {@code Accept: text/event-stream} 的等价入口）。
     * <p>与独立流端点 {@link #stream} 共用同一 handler：先绑定连接（注册实时订阅，消除事件丢失窗口），
     * 随后下发 {@code : connected} 注释行并回放 {@code Last-Event-ID} 之后的历史事件，回放完成后
     * 保持实时订阅。帧形状、心跳（{@code : heartbeat}）、增量协商（非法
     * {@code event_deltas[]} 取值 → 400）与重连语义与独立流端点逐字一致。</p>
     */
    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamEvents(
            @PathVariable String sessionId,
            @RequestHeader(name = LAST_EVENT_ID_HEADER, required = false) String lastEventId,
            @RequestParam(name = "event_deltas[]", required = false) List<String> eventDeltas,
            @RequestParam(name = "delta_flush_interval_ms", required = false) Long deltaFlushIntervalMs) {
        return subscriptionService.open(sessionId, lastEventId, eventDeltas, deltaFlushIntervalMs);
    }

    /**
     * 权威事件订阅端点（对齐公开契约 {@code GET /sessions/{id}/events/stream}）。
     * <p>对齐 Managed Agents SSE 设计：{@code event} 恒为扁平 Event 顶层 {@code type}（源码事件类型），
     * 帧含 {@code id}（{@code evt_} 事件 ID，同时为 data.id）/ {@code event} / {@code data} 三字段；
     * 客户端断线重连时经 SSE 标准 {@code Last-Event-ID} 请求头回传，服务端据此换算回放起点续推
     * （指向非公开 / 归档事件 400、引用不存在事件 404）。连接断开 MUST NOT 触发 turn 取消
     * （取消只来自显式 {@code POST /cancel} 或 {@code user.interrupt}）。</p>
     * <p><b>增量流式帧为连接级协商</b>：默认仅推 buffered 完整事件；仅当连接携带查询参数
     * {@code event_deltas[]}（可重复，取值仅 {@code agent.message} / {@code agent.thinking}）时，
     * 该连接才接收 {@code event_start} / {@code event_delta} 增量帧，不影响同会话其他连接；
     * 不支持 / 未知取值 MUST 返回 400；{@code delta_flush_interval_ms} 控制该连接增量刷写频率
     * （缺省 50ms）。线程事件流不支持增量协商（见线程嵌套端点）。</p>
     */
    @GetMapping("/stream")
    public SseEmitter stream(
            @PathVariable String sessionId,
            @RequestHeader(name = LAST_EVENT_ID_HEADER, required = false) String lastEventId,
            @RequestParam(name = "event_deltas[]", required = false) List<String> eventDeltas,
            @RequestParam(name = "delta_flush_interval_ms", required = false) Long deltaFlushIntervalMs) {
        return subscriptionService.open(sessionId, lastEventId, eventDeltas, deltaFlushIntervalMs);
    }

    /**
     * 单个扁平事件对象 → 入站事件草案：顶层 {@code type} 判别类型（未知类型 400），
     * 其余字段（含 {@code content} / {@code tool_use_id} / {@code result} 等）原样作为
     * 类型特化 payload 序列化（{@code type} 不再重复出现在 payload 中）。
     */
    private InboundEventDraft toDraft(Map<String, Object> event) {
        if (event == null || event.isEmpty()) {
            throw new DeepDataAgentException("unknown_event_type: 事件对象不能为空");
        }
        Object typeValue = event.get("type");
        Map<String, Object> payload = new LinkedHashMap<>(event);
        payload.remove("type");
        return new InboundEventDraft(
                InboundEventValidator.parseKnownType(typeValue == null ? null : String.valueOf(typeValue)),
                toPayloadJson(payload));
    }

    /** payload 对象 → 类型特化 JSON 文本（形态归一：null / 空对象收敛为 {@code "{}"}；序列化失败 400）。 */
    private String toPayloadJson(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JacksonException ex) {
            throw new IllegalArgumentException("事件 payload 序列化失败", ex);
        }
    }
}