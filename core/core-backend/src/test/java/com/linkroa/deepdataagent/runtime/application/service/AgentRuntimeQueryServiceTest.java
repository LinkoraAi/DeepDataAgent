package com.linkroa.deepdataagent.runtime.application.service;

import com.linkroa.deepdataagent.agent.api.AgentSnapshotApi;
import com.linkroa.deepdataagent.agent.api.dto.AgentSnapshotDTO;
import com.linkroa.deepdataagent.runtime.application.contract.SseEventEnvelope;
import com.linkroa.deepdataagent.runtime.application.query.ListEventsQuery;
import com.linkroa.deepdataagent.runtime.application.query.ListSessionsQuery;
import com.linkroa.deepdataagent.runtime.application.query.ReplayQuery;
import com.linkroa.deepdataagent.runtime.domain.model.runstate.TurnRunState;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSession;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSessionContext;
import com.linkroa.deepdataagent.runtime.domain.model.ChatEvent;
import com.linkroa.deepdataagent.runtime.domain.model.ChatEventQuery;
import com.linkroa.deepdataagent.runtime.domain.model.SessionListFilter;
import com.linkroa.deepdataagent.runtime.domain.model.SessionResource;
import com.linkroa.deepdataagent.runtime.domain.model.enums.AgentSessionStatus;
import com.linkroa.deepdataagent.runtime.domain.model.enums.ChatEventType;
import com.linkroa.deepdataagent.runtime.domain.model.enums.TurnPhase;
import com.linkroa.deepdataagent.runtime.domain.repository.AgentSessionRepository;
import com.linkroa.deepdataagent.runtime.domain.repository.ChatEventRepository;
import com.linkroa.deepdataagent.runtime.application.port.SessionRuntimeRegistry;
import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import com.linkroa.deepdataagent.shared.exception.ResourceNotFoundException;
import com.linkroa.deepdataagent.shared.result.CursorPage;
import com.linkroa.deepdataagent.shared.result.CursorPageParams;
import com.linkroa.deepdataagent.shared.security.AuthContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * {@link AgentRuntimeQueryService} 只读用例单测：会话 / 事件回放（seq 游标 + types 过滤）查询、
 * Last-Event-ID 断点游标解析（三段重连语义定位）。
 * <p>事件溯源模型下轮次与轨迹查询已删除（轨迹由事件流承载），仅验证查询编排与
 * 「会话存在性」前置校验。</p>
 */
@ExtendWith(MockitoExtension.class)
class AgentRuntimeQueryServiceTest {

    @Mock private AgentSessionRepository sessionRepository;
    @Mock private ChatEventRepository chatEventRepository;
    @Mock private SessionRuntimeRegistry sessionRegistry;
    @Mock private AgentSnapshotApi agentSnapshotApi;

    private AgentRuntimeQueryService service;

    @BeforeEach
    void setUp() {
        AuthContext.setUserId(1L);
        service = newQueryService();
    }

    @AfterEach
    void clearAuth() {
        AuthContext.clear();
    }

    private AgentRuntimeQueryService newQueryService() {
        AgentRuntimeQueryService svc = new AgentRuntimeQueryService();
        ReflectionTestUtils.setField(svc, "sessionRepository", sessionRepository);
        ReflectionTestUtils.setField(svc, "chatEventRepository", chatEventRepository);
        ReflectionTestUtils.setField(svc, "sessionRegistry", sessionRegistry);
        ReflectionTestUtils.setField(svc, "agentSnapshotApi", agentSnapshotApi);
        return svc;
    }

    // ==================== 会话查询 ====================

    @Test
    void should_throwNotFound_when_getSession_given_missingSession() {
        // given
        when(sessionRepository.findBySessionId("nope")).thenReturn(Optional.empty());

        // when & then
        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> service.getSession("nope"));
        assertTrue(ex.getMessage().contains("DEEP_AGENT_SESSION_NOT_FOUND"));
    }

    @Test
    void should_returnSession_when_getSession_given_existingSession() {
        // given
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));

        // when
        AgentSession found = service.getSession(session.sessionId());

        // then
        assertEquals(session.sessionId(), found.sessionId());
    }

    @Test
    void should_returnCursorPageWithoutHasMore_when_listSessions_given_firstPageNotFull() {
        // given（首页不满：limit+1 探针未命中，has_more=false）
        AgentSession session = sessionWithId(1L, "s-1", OffsetDateTime.parse("2026-08-22T10:00:00+08:00"));
        when(sessionRepository.findByCursor("u-1",
                new SessionListFilter(null, null, null, null, List.of(), null, false,
                        null, null, null, null, false, null, null, false), 21))
                .thenReturn(List.of(session));
        ListSessionsQuery query = new ListSessionsQuery("u-1", null, null, null, null, null, false,
                null, null, null, null, false, new CursorPageParams(20, null, null));

        // when
        CursorPage<AgentSession> page = service.listSessions(query);

        // then
        assertEquals(1, page.data().size());
        assertEquals("s-1", page.firstId());
        assertEquals("s-1", page.lastId());
        assertFalse(page.hasMore());
    }

    @Test
    void should_returnHasMoreWithTrimmedProbe_when_listSessions_given_moreThanOnePage() {
        // given（仓储按降序返回 limit+1 行：探针行被裁掉，has_more=true）
        List<AgentSession> sessions = new ArrayList<>();
        for (int i = 1; i <= 21; i++) {
            sessions.add(sessionWithId((long) i, "s-" + i,
                    OffsetDateTime.parse("2026-08-22T10:00:00+08:00").minusSeconds(i)));
        }
        when(sessionRepository.findByCursor("u-1",
                new SessionListFilter("agent-a", null, null, null, List.of(AgentSessionStatus.IDLE), null,
                        false, null, null, null, null, false, null, null, false), 21))
                .thenReturn(sessions);
        ListSessionsQuery query = new ListSessionsQuery("u-1", "agent-a", null, null, null,
                List.of(AgentSessionStatus.IDLE), false, null, null, null, null, false,
                new CursorPageParams(20, null, null));

        // when
        CursorPage<AgentSession> page = service.listSessions(query);

        // then（当页 20 条 + has_more，last_id 为当页末条业务 ID，next_page 回传不透明游标）
        assertEquals(20, page.data().size());
        assertEquals("s-20", page.lastId());
        assertEquals("s-20", page.nextPage());
        assertTrue(page.hasMore());
    }

    @Test
    void should_resolveCursorRow_when_listSessions_given_afterId() {
        // given（after_id 游标会话 → keyset 行位点（created_at + 主键 id），降序向后）
        OffsetDateTime cursorTime = OffsetDateTime.parse("2026-08-22T10:00:00+08:00");
        AgentSession anchor = sessionWithId(7L, "s-7", cursorTime);
        when(sessionRepository.findBySessionId("s-7")).thenReturn(Optional.of(anchor));
        when(sessionRepository.findByCursor("u-1",
                new SessionListFilter(null, null, null, null, List.of(), null, false,
                        null, null, null, null, false, cursorTime, 7L, false), 21))
                .thenReturn(List.of());
        ListSessionsQuery query = new ListSessionsQuery("u-1", null, null, null, null, null, false,
                null, null, null, null, false, new CursorPageParams(20, "s-7", null));

        // when
        CursorPage<AgentSession> page = service.listSessions(query);

        // then（空页：first/last null，游标位点装配正确性由 stub 匹配本身断言）
        assertEquals(0, page.data().size());
        assertNull(page.firstId());
    }

    @Test
    void should_reverseRows_when_listSessions_given_beforeId() {
        // given（before_id 向前翻页：升序读取后应用层翻转回降序）
        OffsetDateTime cursorTime = OffsetDateTime.parse("2026-08-22T10:00:05+08:00");
        AgentSession anchor = sessionWithId(5L, "s-5", cursorTime);
        when(sessionRepository.findBySessionId("s-5")).thenReturn(Optional.of(anchor));
        when(sessionRepository.findByCursor("u-1",
                new SessionListFilter(null, null, null, null, List.of(), null, false,
                        null, null, null, null, false, cursorTime, 5L, true), 21))
                .thenReturn(List.of(
                        sessionWithId(3L, "s-3", OffsetDateTime.parse("2026-08-22T10:00:03+08:00")),
                        sessionWithId(4L, "s-4", OffsetDateTime.parse("2026-08-22T10:00:04+08:00"))));
        ListSessionsQuery query = new ListSessionsQuery("u-1", null, null, null, null, null, false,
                null, null, null, null, false, new CursorPageParams(20, null, "s-5"));

        // when
        CursorPage<AgentSession> page = service.listSessions(query);

        // then（翻转后降序：s-4 在前）
        assertEquals(List.of("s-4", "s-3"), page.data().stream().map(AgentSession::sessionId).toList());
        assertEquals("s-4", page.firstId());
        assertFalse(page.hasMore());
    }

    @Test
    void should_throwNotFound_when_listSessions_given_missingCursorSession() {
        // given（游标指向不存在的会话 → 404，与详情同语义）
        when(sessionRepository.findBySessionId("s-gone")).thenReturn(Optional.empty());
        ListSessionsQuery query = new ListSessionsQuery("u-1", null, null, null, null, null, false,
                null, null, null, null, false, new CursorPageParams(20, "s-gone", null));

        // when & then
        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> service.listSessions(query));
        assertTrue(ex.getMessage().contains("DEEP_AGENT_SESSION_NOT_FOUND"));
    }

    // ==================== 挂载资源读用例 ====================

    @Test
    void should_returnAllResources_when_listResources_given_noCursor() {
        // given（两条挂载资源，limit 20 首页）
        AgentSession session = mountSession("s-m",
                List.of(SessionResource.file("file_1", null), SessionResource.file("file_2", null)));
        when(sessionRepository.findBySessionId("s-m")).thenReturn(Optional.of(session));

        // when
        CursorPage<SessionResource> page = service.listResources("s-m", new CursorPageParams(20, null, null));

        // then（按挂载顺序返回，无下一页）
        assertEquals(List.of(session.resources().get(0).id(), session.resources().get(1).id()),
                page.data().stream().map(SessionResource::id).toList());
        assertFalse(page.hasMore());
    }

    @Test
    void should_sliceAfterCursor_when_listResources_given_afterIdAndLimitOne() {
        // given（after_id 定位第二条资源：向后仅剩空，limit=1）
        List<SessionResource> mounts = List.of(
                SessionResource.file("file_1", null), SessionResource.file("file_2", null));
        AgentSession session = mountSession("s-m", mounts);
        when(sessionRepository.findBySessionId("s-m")).thenReturn(Optional.of(session));

        // when
        CursorPage<SessionResource> page = service.listResources("s-m",
                new CursorPageParams(1, mounts.get(0).id(), null));

        // then（第一条之后取 1 条 = 第二条，仍宣称有下一页候选：to=total 判 false）
        assertEquals(List.of(mounts.get(1).id()), page.data().stream().map(SessionResource::id).toList());
        assertFalse(page.hasMore());
    }

    @Test
    void should_throwNotFound_when_listResources_given_missingCursorResource() {
        // given（游标资源未命中 → 404）
        AgentSession session = mountSession("s-m", List.of(SessionResource.file("file_1", null)));
        when(sessionRepository.findBySessionId("s-m")).thenReturn(Optional.of(session));

        // when & then
        assertThrows(ResourceNotFoundException.class, () -> service.listResources("s-m",
                new CursorPageParams(20, "sesr_missing", null)));
    }

    @Test
    void should_returnResource_when_getResource_given_mountedId() {
        // given
        SessionResource mount = SessionResource.file("file_1", null);
        AgentSession session = mountSession("s-m", List.of(mount));
        when(sessionRepository.findBySessionId("s-m")).thenReturn(Optional.of(session));

        // when
        SessionResource found = service.getResource("s-m", mount.id());

        // then
        assertEquals(mount.id(), found.id());
        assertEquals("file_1", found.fileId());
    }

    @Test
    void should_throwNotFound_when_getResource_given_unmountedId() {
        // given
        AgentSession session = mountSession("s-m", List.of(SessionResource.file("file_1", null)));
        when(sessionRepository.findBySessionId("s-m")).thenReturn(Optional.of(session));

        // when & then
        assertThrows(ResourceNotFoundException.class, () -> service.getResource("s-m", "sesr_gone"));
    }

    // ==================== after_id 事件游标换算 ====================

    @Test
    void should_returnSeq_when_findEventSeq_given_persistedEventId() {
        // given
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        when(chatEventRepository.findSeqByEventId(session.sessionId(), "evt_done"))
                .thenReturn(OptionalLong.of(7L));

        // when / then
        assertEquals(7L, service.findEventSeq(session.sessionId(), "evt_done"));
    }

    @Test
    void should_throwBadRequest_when_findEventSeq_given_unknownEventId() {
        // given（after_id 指向不存在事件 → 400 非法请求）
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        when(chatEventRepository.findSeqByEventId(session.sessionId(), "evt_gone"))
                .thenReturn(OptionalLong.empty());

        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> service.findEventSeq(session.sessionId(), "evt_gone"));
    }

    // ==================== Agent 嵌入快照（3.9） ====================

    @Test
    void should_returnFullSnapshot_when_agentSnapshot_given_contractResolved() {
        // given（契约命中：完整裁剪快照直接透出）
        AgentSession session = idleSession();
        when(agentSnapshotApi.resolveSnapshot("agent-a", null, 1L))
                .thenReturn(new AgentSnapshotDTO(
                        "agent-a", 1, Map.of("id", "agent-a", "type", "agent",
                                "version", 1, "system", "你是分析助手")));

        // when
        Map<String, Object> snapshot = service.agentSnapshot(session);

        // then
        assertEquals("你是分析助手", snapshot.get("system"));
    }

    @Test
    void should_fallbackToSummary_when_agentSnapshot_given_contractMiss() {
        // given（台账缺行返回 null：降级为 id / type / version 摘要，版本非十进制收敛 0）
        AgentSession session = idleSession();
        when(agentSnapshotApi.resolveSnapshot("agent-a", null, 1L)).thenReturn(null);

        // when
        Map<String, Object> snapshot = service.agentSnapshot(session);

        // then
        assertEquals("agent-a", snapshot.get("id"));
        assertEquals("agent", snapshot.get("type"));
        assertEquals(0, snapshot.get("version"));
    }

    @Test
    void should_fallbackToSummary_when_agentSnapshot_given_contractThrows() {
        // given（契约异常同样降级摘要：会话可读性优先，不向上冒 500）
        AgentSession session = idleSession();
        when(agentSnapshotApi.resolveSnapshot("agent-a", null, 1L))
                .thenThrow(new IllegalStateException("agent bc down"));

        // when
        Map<String, Object> snapshot = service.agentSnapshot(session);

        // then
        assertEquals("agent-a", snapshot.get("id"));
    }

    // ==================== 事件回放（seq 游标 + types 过滤） ====================

    @Test
    void should_throwNotFound_when_replayEvents_given_missingSession() {
        // given
        when(sessionRepository.findBySessionId("nope")).thenReturn(Optional.empty());

        // when & then
        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> service.replayEvents(ReplayQuery.of("nope", 0)));
        assertTrue(ex.getMessage().contains("DEEP_AGENT_SESSION_NOT_FOUND"));
    }

    @Test
    void should_returnReplayEvents_when_replayEvents_given_validSession() {
        // given
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        ChatEvent event = ChatEvent.create(session.sessionId(), ChatEventType.AGENT_MESSAGE, "{\"text\":\"你好\"}", 5L);
        when(chatEventRepository.findBySessionAfter(session.sessionId(), 3L, List.of(), null)).thenReturn(List.of(event));

        // when
        List<ChatEvent> events = service.replayEvents(ReplayQuery.of(session.sessionId(), 3L));

        // then
        assertEquals(1, events.size());
        assertEquals(5L, events.get(0).seq());
        assertEquals(ChatEventType.AGENT_MESSAGE, events.get(0).type());
    }

    @Test
    void should_passTypesFilter_when_replayEvents_given_types() {
        // given
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        List<String> types = List.of("agent.message", "session.status_idle");
        when(chatEventRepository.findBySessionAfter(session.sessionId(), 0L, types, null)).thenReturn(List.of());

        // when
        List<ChatEvent> events = service.replayEvents(new ReplayQuery(session.sessionId(), 0L, types));

        // then
        assertEquals(0, events.size());
    }

    // ==================== 事件历史分页协议出口（扁平 Event + evt_ 游标） ====================

    @Test
    void should_returnEnvelopesWithEventIdCursor_when_listEventPage_given_fullPage() {
        // given（limit=2 满页：两条已落库事件，limit+1 探针命中 → has_more=true）
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        ChatEvent first = ChatEvent.create(session.sessionId(), ChatEventType.USER_MESSAGE,
                "{\"content\":[{\"type\":\"text\",\"text\":\"你好\"}]}", 7L);
        ChatEvent second = ChatEvent.create(session.sessionId(), ChatEventType.AGENT_MESSAGE,
                "{\"text\":\"我在\"}", 8L);
        ChatEvent probe = ChatEvent.create(session.sessionId(), ChatEventType.AGENT_MESSAGE,
                "{\"text\":\"探针\"}", 9L);
        when(chatEventRepository.findPage(new ChatEventQuery(session.sessionId(), null, null, null,
                List.of(), null, null, null, null, true, 3)))
                .thenReturn(List.of(first, second, probe));

        // when
        CursorPage<SseEventEnvelope> page = service.listEventPage(new ListEventsQuery(
                session.sessionId(), null, List.of(), null, null, null, null, true,
                new CursorPageParams(2, null, null)));

        // then（探针行被裁掉；next_page 回传当页末条事件 ID）
        assertEquals(2, page.data().size());
        assertEquals(first.eventId(), page.data().getFirst().id());
        assertEquals("user.message", page.data().getFirst().type());
        assertEquals("你好", ((Map<?, ?>) ((List<?>) page.data().getFirst().attributes().get("content"))
                .getFirst()).get("text"));
        assertEquals("agent.message", page.data().get(1).type());
        assertEquals("我在", page.data().get(1).attributes().get("text"));
        assertEquals(second.eventId(), page.lastId());
        assertEquals(second.eventId(), page.nextPage());
        assertTrue(page.hasMore());
    }

    @Test
    void should_convertAfterIdToSeq_when_listEventPage_given_eventIdCursor() {
        // given（after_id（evt_）换算为事件表 seq 位点后下传）
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        when(chatEventRepository.findSeqByEventId(session.sessionId(), "evt_9"))
                .thenReturn(OptionalLong.of(42L));
        when(chatEventRepository.findPage(new ChatEventQuery(session.sessionId(), null, 42L, null,
                List.of("agent.message"), null, null, null, null, true, 51)))
                .thenReturn(List.of());

        // when
        CursorPage<SseEventEnvelope> page = service.listEventPage(new ListEventsQuery(
                session.sessionId(), null, List.of(ChatEventType.AGENT_MESSAGE),
                null, null, null, null, true, new CursorPageParams(50, "evt_9", null)));

        // then（换算正确性由仓储 stub 精确匹配断言；空页无游标）
        assertTrue(page.data().isEmpty());
        assertNull(page.nextPage());
        assertFalse(page.hasMore());
    }

    @Test
    void should_scopeByThread_when_listEventPage_given_threadScope() {
        // given（线程作用域：线程归属键下传至事件表过滤，不扩张对外扁平 Event 字段）
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        ChatEvent only = ChatEvent.create(session.sessionId(), ChatEventType.AGENT_MESSAGE,
                "{\"text\":\"子线程\"}", 8L, "evt_thread", "sthr_1");
        when(chatEventRepository.findPage(new ChatEventQuery(session.sessionId(), "sthr_1", null, null,
                List.of(), null, null, null, null, true, 101)))
                .thenReturn(List.of(only));

        // when
        CursorPage<SseEventEnvelope> page = service.listEventPage(new ListEventsQuery(
                session.sessionId(), "sthr_1", List.of(), null, null, null, null, true,
                new CursorPageParams(100, null, null)));

        // then（不满页 → 末页）
        assertEquals(1, page.data().size());
        assertEquals("evt_thread", page.firstId());
        assertNull(page.nextPage());
    }

    @Test
    void should_throwBadRequest_when_listEventPage_given_unknownCursorEventId() {
        // given（after_id 指向不存在的已落库事件 → 400 非法请求，区别于资源级 404）
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        when(chatEventRepository.findSeqByEventId(session.sessionId(), "evt_gone"))
                .thenReturn(OptionalLong.empty());

        // when & then
        assertThrows(IllegalArgumentException.class, () -> service.listEventPage(new ListEventsQuery(
                session.sessionId(), null, List.of(), null, null, null, null, true,
                new CursorPageParams(20, "evt_gone", null))));
    }

    // ==================== Last-Event-ID 断点游标解析（三段重连语义） ====================

    @Test
    void should_returnZeroPosition_when_resolveReplayPosition_given_blankCursor() {
        // given
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));

        // when
        AgentRuntimeQueryService.ReplayPosition position =
                service.resolveReplayPosition(session.sessionId(), "  ");

        // then（缺省全量回放）
        assertEquals(0L, position.afterSequence());
        assertFalse(position.midStream());
    }

    @Test
    void should_passThroughNumericCursor_when_resolveReplayPosition_given_legacySeqCursor() {
        // given（历史数字游标兼容：seq 直接透传）
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));

        // when & then
        AgentRuntimeQueryService.ReplayPosition numeric =
                service.resolveReplayPosition(session.sessionId(), "12");
        assertEquals(12L, numeric.afterSequence());
        assertFalse(numeric.midStream());
    }

    @Test
    void should_throwBadRequest_when_resolveReplayPosition_given_malformedCursor() {
        // given（既非数字也非 evt_ 前缀 → 400 非法游标）
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));

        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> service.resolveReplayPosition(session.sessionId(), "abc"));
    }

    @Test
    void should_mapPersistedSeq_when_resolveReplayPosition_given_eventIdHit() {
        // given（一段 / 三段：evt_ 游标命中已落库事件 → 该事件 seq）
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        when(chatEventRepository.findByEventId(session.sessionId(), "evt_done"))
                .thenReturn(Optional.of(ChatEvent.create(session.sessionId(),
                        ChatEventType.AGENT_MESSAGE, "{\"text\":\"完成\"}", 7L, "evt_done")));

        // when
        AgentRuntimeQueryService.ReplayPosition position =
                service.resolveReplayPosition(session.sessionId(), "evt_done");

        // then
        assertEquals(7L, position.afterSequence());
        assertFalse(position.midStream());
    }

    @Test
    void should_throwBadRequest_when_resolveReplayPosition_given_nonPublicStreamingEvent() {
        // given（命中流式专用帧 event_delta：不落库回放，指向即非公开事件 → 400）
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        when(chatEventRepository.findByEventId(session.sessionId(), "evt_frame"))
                .thenReturn(Optional.of(ChatEvent.create(session.sessionId(),
                        ChatEventType.EVENT_DELTA, "{\"text\":\"x\"}", 7L, "evt_frame")));

        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> service.resolveReplayPosition(session.sessionId(), "evt_frame"));
    }

    @Test
    void should_markMidStream_when_resolveReplayPosition_given_inFlightEventId() {
        // given（二段：游标等于进行中流事件 ID（未落库）→ baseSeq + midStream）
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        when(chatEventRepository.findByEventId(session.sessionId(), "evt_live"))
                .thenReturn(Optional.empty());
        AgentSessionContext context = new AgentSessionContext(session);
        TurnRunState runState = context.beginRound(9L);
        runState.markInFlightStream("evt_live", ChatEventType.AGENT_MESSAGE, "blk-1", 9L);
        runState.appendText("blk-1", "已生成");
        when(sessionRegistry.get(session.sessionId())).thenReturn(Optional.of(context));

        // when
        AgentRuntimeQueryService.ReplayPosition position =
                service.resolveReplayPosition(session.sessionId(), "evt_live");

        // then（不重放历史 delta、仅推重连后新增）
        assertEquals(9L, position.afterSequence());
        assertTrue(position.midStream());
        // 进行中流与累积文本快照可读（一段回补入口）
        assertEquals("evt_live", service.currentInFlightStream(session.sessionId()).eventId());
        assertEquals("已生成", service.accumulatedStreamText(session.sessionId(), "blk-1"));
    }

    @Test
    void should_throwNotFound_when_resolveReplayPosition_given_unknownEventIdWithoutInFlight() {
        // given（未命中落库事件且无进行中流：引用不存在事件 → 404）
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        when(chatEventRepository.findByEventId(session.sessionId(), "evt_gone"))
                .thenReturn(Optional.empty());
        when(sessionRegistry.get(session.sessionId())).thenReturn(Optional.empty());

        // when & then
        assertThrows(ResourceNotFoundException.class,
                () -> service.resolveReplayPosition(session.sessionId(), "evt_gone"));
        assertNull(service.currentInFlightStream(session.sessionId()));
        assertEquals("", service.accumulatedStreamText(session.sessionId(), "blk-1"));
    }

    @Test
    void should_throwBadRequest_when_resolveReplayPosition_given_archivedSession() {
        // given（已归档会话携带非空游标：归档事件不承担重连回放语义 → 400）
        AgentSession session = idleSession().withArchived();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));

        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> service.resolveReplayPosition(session.sessionId(), "evt_done"));
    }

    @Test
    void should_returnZeroPosition_when_resolveReplayPosition_given_archivedSessionAndBlankCursor() {
        // given（归档会话历史仍可读：空白游标的全量回放不受归档门禁影响）
        AgentSession session = idleSession().withArchived();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));

        // when
        AgentRuntimeQueryService.ReplayPosition position =
                service.resolveReplayPosition(session.sessionId(), null);

        // then
        assertEquals(0L, position.afterSequence());
        assertFalse(position.midStream());
    }

    // ==================== 工具 ====================

    private AgentSession idleSession() {
        return AgentSession.create("1", "agent-a", "1.0.0", "{}", null);
    }

    /** 带挂载资源的会话（sessionId 固定，userId 对齐 AuthContext=1）。 */
    private AgentSession mountSession(String sessionId, List<SessionResource> resources) {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-22T10:00:00+08:00");
        return AgentSession.restore(1L, sessionId, "1", "agent-a", "1.0.0",
                AgentSessionStatus.IDLE, TurnPhase.IDLE, "{}", null, null,
                List.of(), List.of(), "{}", resources, null, null,
                now, null, now, now, null, null);
    }

    private AgentSession sessionWithId(Long id, String sessionId, OffsetDateTime createdAt) {
        // 双字段状态机 22 参 restore：idle / idle，无挂载字段（sandboxId / workspaceId 组件已删除）
        return AgentSession.restore(id, sessionId, "1", "agent-a", "1.0.0",
                AgentSessionStatus.IDLE, TurnPhase.IDLE, "{}", null, null,
                List.of(), List.of(), "{}", List.of(), null, null,
                createdAt, null, createdAt, createdAt, null, null);
    }
}