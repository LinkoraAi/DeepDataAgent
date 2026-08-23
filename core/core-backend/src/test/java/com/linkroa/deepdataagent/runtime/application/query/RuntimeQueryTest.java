package com.linkroa.deepdataagent.runtime.application.query;

import com.linkroa.deepdataagent.runtime.domain.model.enums.AgentSessionStatus;
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
        ListSessionsQuery query = new ListSessionsQuery("u-1", "agent-a",
                List.of(AgentSessionStatus.IDLE), null, 20);

        // then
        assertEquals("u-1", query.userId());
        assertEquals("agent-a", query.agentId());
        assertEquals(List.of(AgentSessionStatus.IDLE), query.statuses());
        assertEquals(20, query.size());
    }

    @Test
    void should_buildQueryWithDefaults_when_construct_given_noFilters() {
        // when
        ListSessionsQuery query = new ListSessionsQuery("u-1", null, null, null, 20);

        // then
        assertEquals(List.of(), query.statuses());
    }

    @Test
    void should_throw_when_construct_given_blankUserId() {
        // when & then
        assertThrows(IllegalArgumentException.class, () -> new ListSessionsQuery(" ", null, null, null, 20));
    }

    @Test
    void should_throw_when_construct_given_sizeOverLimit() {
        // when & then
        assertThrows(IllegalArgumentException.class, () -> new ListSessionsQuery("u-1", null, null, null, 101));
    }

    @Test
    void should_throw_when_construct_given_zeroSize() {
        // when & then
        assertThrows(IllegalArgumentException.class, () -> new ListSessionsQuery("u-1", null, null, null, 0));
    }

    // ===== ReplayQuery =====

    @Test
    void should_buildReplayQuery_when_construct_given_validInputs() {
        // when
        ReplayQuery query = new ReplayQuery("s-1", 5L);

        // then
        assertEquals("s-1", query.sessionId());
        assertEquals(5L, query.afterSequenceNum());
    }

    @Test
    void should_throw_when_construct_given_blankSessionId() {
        // when & then
        assertThrows(IllegalArgumentException.class, () -> new ReplayQuery("", 0L));
    }

    @Test
    void should_throw_when_construct_given_negativeSequenceNum() {
        // when & then
        assertThrows(IllegalArgumentException.class, () -> new ReplayQuery("s-1", -1L));
    }

    // ===== depth: 全构造器等参数正常 =====

    @Test
    void should_buildQuery_when_construct_given_boundarySize() {
        // when
        ListSessionsQuery query = new ListSessionsQuery("u-1", null, null, null, 100);

        // then
        assertEquals(100, query.size());
    }
}