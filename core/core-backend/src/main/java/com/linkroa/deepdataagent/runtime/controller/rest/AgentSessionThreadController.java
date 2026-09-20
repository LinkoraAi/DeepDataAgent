package com.linkroa.deepdataagent.runtime.controller.rest;

import com.linkroa.deepdataagent.runtime.application.contract.SseEventEnvelope;
import com.linkroa.deepdataagent.runtime.application.convert.AgentRuntimeCommandConvert;
import com.linkroa.deepdataagent.runtime.application.service.AgentRuntimeQueryService;
import com.linkroa.deepdataagent.runtime.application.service.subscription.SessionSubscriptionService;
import com.linkroa.deepdataagent.runtime.controller.convert.AgentRuntimeResponseConvert;
import com.linkroa.deepdataagent.runtime.controller.response.ThreadResponse;
import com.linkroa.deepdataagent.shared.constant.api.ApiVersionConstants;
import com.linkroa.deepdataagent.shared.result.ApiResponse;
import com.linkroa.deepdataagent.shared.result.CursorPage;
import jakarta.annotation.Resource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * Session Thread 查询面 REST 控制器（前缀 {@code /api/v1/cloud/sessions/{sessionId}/threads}，
 * 对齐公开契约 Thread 只读端点）。
 * <ul>
 *   <li>{@code GET /threads}：列出会话全部线程，协调器主线程排首位
 *       （{@code parent_thread_id} 为 null；单 Agent 场景恒一条）；</li>
 *   <li>{@code GET /threads/{thread_id}/events}：线程作用域扁平 Event 游标分页
 *       （分页 / 过滤参数与 Session 事件列表一致；归属为事件表内部过滤键，
 *       MUST NOT 扩张对外扁平 Event 公开字段）；携带 {@code Accept: text/event-stream} 时
 *       切换为线程事件流；</li>
 *   <li>{@code GET /threads/{thread_id}/events/stream}：线程事件流（仅 buffered，
 *       携带 {@code event_deltas[]} MUST 400）。</li>
 * </ul>
 * <p>本期 MUST NOT 提供线程归档端点（multiagent 子线程能力出界，见 design D12）。</p>
 */
@RestController
@RequestMapping(path = "/cloud/sessions/{sessionId}/threads", version = ApiVersionConstants.CURRENT_API_VERSION)
public class AgentSessionThreadController {

    private static final String LAST_EVENT_ID_HEADER = "Last-Event-ID";

    @Resource
    private AgentRuntimeQueryService queryService;
    @Resource
    private SessionSubscriptionService subscriptionService;

    /**
     * 列出会话线程（公开契约 {@code GET /sessions/{id}/threads}，主线程排首位）。
     * <p>Thread 对象字段严格为 {@code id / type / session_id / parent_thread_id / agent /
     * status / archived_at / created_at / updated_at}（裁剪快照，无 name/role/stop_reason/usage）。</p>
     */
    @GetMapping
    public ApiResponse<List<ThreadResponse>> listThreads(@PathVariable String sessionId) {
        return ApiResponse.success(AgentRuntimeResponseConvert.INSTANCE
                .toThreadResponses(queryService.listThreads(sessionId)));
    }

    /**
     * 线程作用域事件历史（公开契约 {@code GET /sessions/{id}/threads/{thread_id}/events}）。
     * <p>分页与过滤参数与 Session 事件列表逐字一致（{@code limit / page / after_id / before_id /
     * order / types / created_at[gt|gte|lt|lte]}），并叠加线程归属过滤；线程不存在或不属于该会话
     * → 404。响应形状 {@code {data, first_id, last_id, has_more, next_page}}。</p>
     */
    @GetMapping("/{threadId}/events")
    public ApiResponse<CursorPage<SseEventEnvelope>> listThreadEvents(
            @PathVariable String sessionId,
            @PathVariable String threadId,
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
        queryService.requireThread(sessionId, threadId);
        return ApiResponse.success(queryService.listEventPage(AgentRuntimeCommandConvert.INSTANCE
                .toListEventsQuery(sessionId, threadId, types, createdAtGt, createdAtGte,
                        createdAtLt, createdAtLte, order, limit, page, afterId, beforeId)));
    }

    /**
     * 线程事件流（{@code GET /threads/{thread_id}/events} 携带
     * {@code Accept: text/event-stream} 的等价入口，与独立流端点共用同一 handler）。
     * <p>线程流<b>仅提供 buffered</b>：MUST NOT 支持增量协商——携带 {@code event_deltas[]}
     * 一律 400 {@code invalid_request_error}。</p>
     */
    @GetMapping(value = "/{threadId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamThreadEvents(
            @PathVariable String sessionId,
            @PathVariable String threadId,
            @RequestHeader(name = LAST_EVENT_ID_HEADER, required = false) String lastEventId,
            @RequestParam(name = "event_deltas[]", required = false) List<String> eventDeltas) {
        return openThreadStream(sessionId, threadId, lastEventId, eventDeltas);
    }

    /**
     * 权威线程事件流端点（公开契约 {@code GET /sessions/{id}/threads/{thread_id}/events/stream}）。
     * <p>帧形状 / 心跳 / {@code Last-Event-ID} 重连语义与 Session 事件流一致，但仅推 buffered
     * （无 {@code event_start} / {@code event_delta} 增量帧）。</p>
     */
    @GetMapping(value = "/{threadId}/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamThreadEventsExplicit(
            @PathVariable String sessionId,
            @PathVariable String threadId,
            @RequestHeader(name = LAST_EVENT_ID_HEADER, required = false) String lastEventId,
            @RequestParam(name = "event_deltas[]", required = false) List<String> eventDeltas) {
        return openThreadStream(sessionId, threadId, lastEventId, eventDeltas);
    }

    /**
     * 线程事件流统一入口：拒绝增量协商参数（线程流仅 buffered），随后交由订阅服务
     * 按线程归属回放并保持实时订阅。
     */
    private SseEmitter openThreadStream(String sessionId, String threadId, String lastEventId,
                                        List<String> eventDeltas) {
        if (eventDeltas != null && !eventDeltas.isEmpty()) {
            throw new IllegalArgumentException("线程事件流不支持 event_deltas[] 增量协商");
        }
        return subscriptionService.openThread(sessionId, threadId, lastEventId);
    }
}