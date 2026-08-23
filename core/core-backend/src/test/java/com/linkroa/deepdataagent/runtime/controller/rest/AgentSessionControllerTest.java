package com.linkroa.deepdataagent.runtime.controller.rest;

import com.linkroa.deepdataagent.runtime.application.query.ListSessionsQuery;
import com.linkroa.deepdataagent.runtime.application.service.AgentRuntimeCommandService;
import com.linkroa.deepdataagent.runtime.application.service.AgentRuntimeQueryService;
import com.linkroa.deepdataagent.runtime.controller.response.SessionListResponse;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSession;
import com.linkroa.deepdataagent.runtime.domain.model.enums.AgentSessionStatus;
import com.linkroa.deepdataagent.shared.result.ApiResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AgentSessionController} 会话列表接口单测：锁定「过滤 / 游标参数透传 + 响应信封
 * {@code data} + {@code next_page} stable」契约（游标已替代 offset，信封字段名不变）。
 */
@ExtendWith(MockitoExtension.class)
class AgentSessionControllerTest {

    @Mock
    private AgentRuntimeCommandService commandService;
    @Mock
    private AgentRuntimeQueryService queryService;

    @InjectMocks
    private AgentSessionController controller;

    @Test
    void should_returnSessionListEnvelope_when_listSessions_given_filtersAndCursor() {
        // given
        ListSessionsQuery query = new ListSessionsQuery("demo-user", "agent-a",
                List.of(AgentSessionStatus.IDLE), null, 20);
        AgentSession session = sessionWithId(1L, "s-1");
        when(queryService.listSessions(query))
                .thenReturn(new AgentRuntimeQueryService.SessionPage(List.of(session), "cursor-1"));

        // when
        ApiResponse<SessionListResponse> response =
                controller.listSessions("agent-a", List.of("IDLE"), 20, null);

        // then
        assertTrue(response.success());
        assertEquals(1, response.data().data().size());
        assertEquals("cursor-1", response.data().next_page());
    }

    @Test
    void should_delegateFiltersAndCursor_when_listSessions_given_queryParams() {
        // given
        ListSessionsQuery query = new ListSessionsQuery("demo-user", "agent-a",
                List.of(AgentSessionStatus.RUNNING), null, 50);
        when(queryService.listSessions(query))
                .thenReturn(new AgentRuntimeQueryService.SessionPage(List.of(), null));

        // when
        controller.listSessions("agent-a", List.of("RUNNING"), 50, null);

        // then
        verify(queryService).listSessions(query);
    }

    @Test
    void should_returnEmptyNextPage_when_listSessions_given_lastPage() {
        // given
        ListSessionsQuery query = new ListSessionsQuery("demo-user", null, List.of(), null, 20);
        when(queryService.listSessions(query))
                .thenReturn(new AgentRuntimeQueryService.SessionPage(List.of(), null));

        // when
        ApiResponse<SessionListResponse> response =
                controller.listSessions(null, null, null, null);

        // then
        assertTrue(response.success());
        assertEquals(0, response.data().data().size());
        assertEquals(null, response.data().next_page());
    }

    private AgentSession sessionWithId(Long id, String sessionId) {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-22T10:00:00+08:00");
        return AgentSession.restore(id, sessionId, "demo-user", "default", "agent-a", "1.0.0",
                AgentSessionStatus.IDLE, "{}", null, null, now, now, now, null, null);
    }
}