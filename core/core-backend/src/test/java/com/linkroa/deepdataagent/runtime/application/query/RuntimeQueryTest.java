package com.linkroa.deepdataagent.runtime.application.query;

import com.linkroa.deepdataagent.runtime.domain.model.enums.AgentSessionStatus;
import com.linkroa.deepdataagent.shared.result.CursorPageParams;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 应用层查询对象不变量单测（ListSessionsQuery / ReplayQuery）。
 */
class RuntimeQueryTest {

    @Test
    void should_buildQuery_when_construct_given_validInputs() {
        // when
        ListSessionsQuery query = new ListSessionsQuery("u-1", "agent-a", null, null, null,
                List.of(AgentSessionStatus.IDLE), false, null, null, null, null, false,
                new CursorPageParams(20, null, null));

        // then
        assertEquals("u-1", query.userId());
        assertEquals("agent-a", query.agentId());
        assertEquals(List.of(AgentSessionStatus.IDLE), query.statuses());
        assertEquals(20, query.cursor().limit());
    }

    @Test
    void should_buildQueryWithDefaults_when_construct_given_noFilters() {
        // when
        ListSessionsQuery query = new ListSessionsQuery("u-1", null, null, null, null, null, false,
                null, null, null, null, false, new CursorPageParams(20, null, null));

        // then（statuses null 收敛为空列表）
        assertEquals(List.of(), query.statuses());
        assertEquals(false, query.includeArchived());
    }

    @Test
    void should_throw_when_construct_given_blankUserId() {
        // when & then
        assertThrows(IllegalArgumentException.class, () -> new ListSessionsQuery(" ", null, null, null,
                null, null, false, null, null, null, null, false, new CursorPageParams(20, null, null)));
    }

    @Test
    void should_throw_when_construct_given_limitOverUpperBound() {
        // when & then（Cursor 约定 limit 上限 100）
        assertThrows(IllegalArgumentException.class,
                () -> new CursorPageParams(101, null, null));
    }

    @Test
    void should_throw_when_construct_given_zeroLimit() {
        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> new CursorPageParams(0, null, null));
    }

    // ===== ReplayQuery =====

    @Test
    void should_buildReplayQuery_when_construct_given_validInputs() {
        // when
        ReplayQuery query = ReplayQuery.of("s-1", 5L);

        // then
        assertEquals("s-1", query.sessionId());
        assertEquals(5L, query.afterSequenceNum());
        assertEquals(List.of(), query.types());
    }

    @Test
    void should_keepTypesFilter_when_construct_given_types() {
        // when
        ReplayQuery query = new ReplayQuery("s-1", 2L, List.of("agent.message", "session.status_idle"));

        // then
        assertEquals(List.of("agent.message", "session.status_idle"), query.types());
    }

    @Test
    void should_normalizeNullTypes_when_construct_given_nullTypes() {
        // when（null types 收敛为空列表）
        ReplayQuery query = new ReplayQuery("s-1", 2L, null);

        // then
        assertEquals(List.of(), query.types());
    }

    @Test
    void should_throw_when_construct_given_blankSessionId() {
        // when & then
        assertThrows(IllegalArgumentException.class, () -> ReplayQuery.of("", 0L));
    }

    @Test
    void should_throw_when_construct_given_negativeSequenceNum() {
        // when & then
        assertThrows(IllegalArgumentException.class, () -> ReplayQuery.of("s-1", -1L));
    }

    @Test
    void should_passLimit_when_construct_given_positiveLimit() {
        // when
        ReplayQuery query = new ReplayQuery("s-1", 5L, List.of(), 100);

        // then
        assertEquals(100, query.limit());
    }

    @Test
    void should_normalizeNullLimit_when_construct_given_nullLimit() {
        // when（null 表示不限量，SSE 全量回放场景）
        ReplayQuery query = new ReplayQuery("s-1", 5L, List.of("agent.message"));

        // then
        assertEquals(null, query.limit());
    }

    @Test
    void should_throw_when_construct_given_nonPositiveLimit() {
        // when & then（限量分页必须为正整数）
        assertThrows(IllegalArgumentException.class,
                () -> new ReplayQuery("s-1", 5L, List.of(), 0));
    }

    // ===== depth: 全构造器等参数正常 =====

    @Test
    void should_buildQuery_when_construct_given_boundarySize() {
        // when（limit 上边界 100）
        ListSessionsQuery query = new ListSessionsQuery("u-1", null, null, null, null, null, false,
                null, null, null, null, false, new CursorPageParams(100, null, null));

        // then
        assertEquals(100, query.cursor().limit());
    }
}