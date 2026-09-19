package com.linkroa.deepdataagent.runtime.controller.rest;

import com.linkroa.deepdataagent.runtime.application.command.InboundEventDraft;
import com.linkroa.deepdataagent.runtime.application.contract.SseEventEnvelope;
import com.linkroa.deepdataagent.runtime.application.query.ListEventsQuery;
import com.linkroa.deepdataagent.runtime.application.service.AgentRuntimeQueryService;
import com.linkroa.deepdataagent.runtime.application.service.event.InboundEventService;
import com.linkroa.deepdataagent.runtime.application.service.subscription.SessionSubscriptionService;
import com.linkroa.deepdataagent.runtime.controller.request.SendEventRequest;
import com.linkroa.deepdataagent.runtime.domain.model.enums.ChatEventType;
import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import com.linkroa.deepdataagent.shared.result.ApiResponse;
import com.linkroa.deepdataagent.shared.result.CursorPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link AgentChatEventController} 事件 REST 协议层单测。
 * <p>控制器只做参数解析、类型解析与形态归一（零领域 / 基础设施引用）：扁平 Event 装配、
 * 分页游标换算与入站结构校验分别由应用服务出口（{@code listEventPage} /
 * {@code ingestInboundEventEnvelopes}）与校验器承载，本测试锁定协议层职责——
 * 入站草案装配（顶层 {@code type} 判别 + 其余字段原样序列化、到达顺序、type 不重复入 payload）、
 * 列表过滤参数归一（page 与 after_id/before_id 互斥、order 非法 400、types 未知静默剔除、
 * limit 越界 400）、响应信封 {@code CursorPage} 直通，以及 SSE 订阅一行委托。</p>
 */
@ExtendWith(MockitoExtension.class)
class AgentChatEventControllerTest {

    /** 入站摄取入口服务 mock（decompose-command-facade 4.4 改线：原门面 mock 替换为目标服务 mock）。 */
    @Mock
    private InboundEventService inboundEventService;

    @Mock
    private AgentRuntimeQueryService queryService;

    @Mock
    private SessionSubscriptionService subscriptionService;

    @InjectMocks
    private AgentChatEventController controller;

    @BeforeEach
    void setUpObjectMapper() {
        // 真实序列化器：控制器侧 payload 形态归一需要可用的 ObjectMapper（@Resource 字段不参与 @InjectMocks）
        ReflectionTestUtils.setField(controller, "objectMapper", new ObjectMapper());
    }

    // ==================== SSE 订阅委托 ====================

    @Test
    void should_delegateOpenVerbatim_when_stream_given_deltaParams() {
        // given（订阅编排已全部下沉应用服务）
        SseEmitter emitter = new SseEmitter();
        when(subscriptionService.open("s-1", "evt_9", List.of("agent.message"), 30L)).thenReturn(emitter);

        // when
        SseEmitter result = controller.stream("s-1", "evt_9", List.of("agent.message"), 30L);

        // then（一行委托、参数原样透传、返回同一 emitter）
        assertSame(emitter, result);
        verify(subscriptionService).open("s-1", "evt_9", List.of("agent.message"), 30L);
    }

    @Test
    void should_delegateOpenVerbatim_when_streamEvents_given_acceptSwitchEntry() {
        // given（Accept: text/event-stream 等价入口与独立流端点共用同一 handler）
        SseEmitter emitter = new SseEmitter();
        when(subscriptionService.open("s-2", null, null, null)).thenReturn(emitter);

        // when
        SseEmitter result = controller.streamEvents("s-2", null, null, null);

        // then
        assertSame(emitter, result);
        verify(subscriptionService).open("s-2", null, null, null);
    }

    // ==================== 事件列表：信封直通与参数归一 ====================

    @Test
    void should_returnFlatEventPageVerbatim_when_listEvents_given_fullPage() {
        // given（游标信封由应用服务出口给出，控制器仅直通）
        SseEventEnvelope first = envelope("evt_a", "user.message");
        SseEventEnvelope second = envelope("evt_b", "agent.message");
        CursorPage<SseEventEnvelope> page = new CursorPage<>(List.of(first, second), "evt_a", "evt_b", true, "evt_b");
        when(queryService.listEventPage(any(ListEventsQuery.class))).thenReturn(page);

        // when
        ApiResponse<CursorPage<SseEventEnvelope>> response =
                controller.listEvents("s-1", null, null, null, null, null, null, null, null, null, null);

        // then（data/first_id/last_id/has_more/next_page 逐字透传）
        assertEquals(List.of(first, second), response.data().data());
        assertEquals("evt_a", response.data().firstId());
        assertEquals("evt_b", response.data().lastId());
        assertTrue(response.data().hasMore());
        assertEquals("evt_b", response.data().nextPage());
    }

    @Test
    void should_normalizeFilterParams_when_listEvents_given_fullFilterSet() {
        // given
        when(queryService.listEventPage(any(ListEventsQuery.class)))
                .thenReturn(new CursorPage<>(List.of(), null, null, false, null));

        // when（page 为不透明游标：等价 after_id；order 缺省升序；types 含未知项）
        controller.listEvents("s-1", "20", "evt_p", null, null, null,
                List.of("agent.message", "agent.not_a_type"), "2026-09-01T00:00:00Z", null, null, null);

        // then
        ListEventsQuery query = captureQuery();
        assertEquals("s-1", query.sessionId());
        assertNull(query.sessionThreadId());
        assertEquals(List.of(ChatEventType.AGENT_MESSAGE), query.types());
        assertEquals(20, query.cursor().limit());
        assertEquals("evt_p", query.cursor().afterId());
        assertNull(query.cursor().beforeId());
        assertTrue(query.ascending());
        assertEquals(OffsetDateTime.parse("2026-09-01T00:00:00Z"), query.createdAtGt());
    }

    @Test
    void should_ignoreUnknownTypesSilently_when_listEvents_given_unknownTypeFilter() {
        // given（列表路径口径：无法识别的类型静默忽略，不报错、不命中）
        when(queryService.listEventPage(any(ListEventsQuery.class)))
                .thenReturn(new CursorPage<>(List.of(), null, null, false, null));

        // when
        controller.listEvents("s-1", null, null, null, null, null,
                List.of("session.nope", "agent.message"), null, null, null, null);

        // then（仅保留权威事件表成员）
        assertEquals(List.of(ChatEventType.AGENT_MESSAGE), captureQuery().types());
    }

    @Test
    void should_throwBadRequest_when_listEvents_given_pageAndAfterIdTogether() {
        // when & then（page 与 after_id 互斥，同传 400 invalid_request_error）
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> controller.listEvents("s-1", null, "evt_p", "evt_a", null, null, null,
                        null, null, null, null));
        assertTrue(ex.getMessage().contains("互斥"));
        verifyNoInteractions(queryService, subscriptionService);
    }

    @Test
    void should_throwBadRequest_when_listEvents_given_limitOutOfRange() {
        // when & then（limit 越界 400，先于任何应用查询）
        assertThrows(IllegalArgumentException.class,
                () -> controller.listEvents("s-1", "101", null, null, null, null, null,
                        null, null, null, null));
        verifyNoInteractions(queryService, subscriptionService);
    }

    @Test
    void should_throwBadRequest_when_listEvents_given_invalidOrder() {
        // when & then（order 仅接受 asc / desc）
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> controller.listEvents("s-1", null, null, null, null, "xyz", null,
                        null, null, null, null));
        assertTrue(ex.getMessage().contains("order"));
        verifyNoInteractions(queryService, subscriptionService);
    }

    @Test
    void should_passDescOrder_when_listEvents_given_desc() {
        // given
        when(queryService.listEventPage(any(ListEventsQuery.class)))
                .thenReturn(new CursorPage<>(List.of(), null, null, false, null));

        // when
        controller.listEvents("s-1", null, null, null, null, "desc", null, null, null, null, null);

        // then
        assertTrue(!captureQuery().ascending());
    }

    // ==================== 入站事件：扁平回显与草案装配 ====================

    @Test
    void should_echoPersistedFlatEnvelopes_when_sendEvent_given_userMessageInbound() {
        // given（入站 user.message 落库后按扁平 Event 回显，装配在应用服务出口）
        SseEventEnvelope persisted = new SseEventEnvelope("evt_u1", "user.message",
                OffsetDateTime.parse("2026-09-12T10:00:00+08:00"),
                Map.of("content", List.of(Map.of("type", "text", "text", "驱动"))));
        when(inboundEventService.ingestInboundEventEnvelopes(eq("s-1"), anyList())).thenReturn(List.of(persisted));
        SendEventRequest request = new SendEventRequest(List.of(
                Map.of("type", "user.message",
                        "content", List.of(Map.of("type", "text", "text", "驱动")))));

        // when
        ApiResponse<List<SseEventEnvelope>> response = controller.sendEvent("s-1", request);

        // then（扁平 Event 无 object / role，内容块位于类型自有顶层字段）
        assertEquals(persisted, response.data().getFirst());
        assertEquals("user.message", response.data().getFirst().type());
        List<?> content = (List<?>) response.data().getFirst().attributes().get("content");
        assertEquals("驱动", ((Map<?, ?>) content.getFirst()).get("text"));
        verify(inboundEventService).ingestInboundEventEnvelopes(eq("s-1"), anyList());
        verifyNoInteractions(subscriptionService);
    }

    @Test
    void should_buildInboundDraftsInArrivalOrder_when_sendEvent_given_flatEvents() {
        // given（事件身份由顶层 type 判别，其余字段原样序列化为类型特化 JSON）
        SendEventRequest request = new SendEventRequest(List.of(
                Map.of("type", "user.message",
                        "content", List.of(Map.of("type", "text", "text", "你好"))),
                Map.of("type", "user.interrupt")));

        // when
        controller.sendEvent("s-1", request);

        // then（保持到达顺序；type 不重复进入 payload；空载荷收敛为 "{}"）
        List<InboundEventDraft> drafts = captureDrafts();
        assertEquals(2, drafts.size());
        assertEquals(ChatEventType.USER_MESSAGE, drafts.get(0).type());
        assertTrue(drafts.get(0).payloadJson().startsWith("{\"content\":["));
        assertTrue(drafts.get(0).payloadJson().contains("\"text\":\"你好\""));
        assertTrue(!drafts.get(0).payloadJson().contains("\"type\":\"user.message\""));
        assertEquals(ChatEventType.USER_INTERRUPT, drafts.get(1).type());
        assertEquals("{}", drafts.get(1).payloadJson());
    }

    @Test
    void should_passMalformedContentToService_when_sendEvent_given_stringContent() {
        // given（跨字段结构校验已下沉应用服务入口首行：控制器不做形状判断，原样装配下传）
        SendEventRequest request = new SendEventRequest(List.of(
                Map.of("type", "user.message", "content", "你好")));

        // when
        controller.sendEvent("s-1", request);

        // then
        List<InboundEventDraft> drafts = captureDrafts();
        assertEquals(ChatEventType.USER_MESSAGE, drafts.getFirst().type());
        assertEquals("{\"content\":\"你好\"}", drafts.getFirst().payloadJson());
    }

    @Test
    void should_throwUnknownEventType_when_sendEvent_given_unknownType() {
        // given（未知事件类型：非权威事件表成员）
        SendEventRequest request = new SendEventRequest(List.of(Map.of("type", "RUN_START")));

        // when & then（整批 400 unknown_event_type，且先于任何应用服务调用）
        DeepDataAgentException ex = assertThrows(DeepDataAgentException.class,
                () -> controller.sendEvent("s-1", request));
        assertTrue(ex.getMessage().startsWith("unknown_event_type"));
        verifyNoInteractions(inboundEventService);
    }

    @Test
    void should_throwUnknownEventType_when_sendEvent_given_missingType() {
        // given（缺顶层 type 判别字段）
        SendEventRequest request = new SendEventRequest(List.of(Map.of("content", "你好")));

        // when & then
        DeepDataAgentException ex = assertThrows(DeepDataAgentException.class,
                () -> controller.sendEvent("s-1", request));
        assertTrue(ex.getMessage().startsWith("unknown_event_type"));
        verifyNoInteractions(inboundEventService);
    }

    @Test
    void should_rejectDefineOutcomeAtInbound_when_sendEvent_given_outcomeEvent() {
        // given（user.define_outcome 已移出入站白名单：非权威事件表成员）
        SendEventRequest request = new SendEventRequest(List.of(
                Map.of("type", "user.define_outcome", "description", "报告")));

        // when & then（本期一律 400，不接收、不落库）
        DeepDataAgentException ex = assertThrows(DeepDataAgentException.class,
                () -> controller.sendEvent("s-1", request));
        assertTrue(ex.getMessage().startsWith("unknown_event_type"));
        verifyNoInteractions(inboundEventService);
    }

    // ==================== 装配工具 ====================

    /** 捕获下传应用服务的事件列表查询。 */
    private ListEventsQuery captureQuery() {
        ArgumentCaptor<ListEventsQuery> captor = ArgumentCaptor.forClass(ListEventsQuery.class);
        verify(queryService).listEventPage(captor.capture());
        return captor.getValue();
    }

    /** 捕获下传应用服务的入站事件草案列表。 */
    @SuppressWarnings("unchecked")
    private List<InboundEventDraft> captureDrafts() {
        ArgumentCaptor<List<InboundEventDraft>> captor = ArgumentCaptor.forClass(List.class);
        verify(inboundEventService).ingestInboundEventEnvelopes(eq("s-1"), captor.capture());
        return captor.getValue();
    }

    private SseEventEnvelope envelope(String id, String type) {
        OffsetDateTime now = OffsetDateTime.parse("2026-09-12T10:00:00+08:00");
        return new SseEventEnvelope(id, type, now, Map.of());
    }
}