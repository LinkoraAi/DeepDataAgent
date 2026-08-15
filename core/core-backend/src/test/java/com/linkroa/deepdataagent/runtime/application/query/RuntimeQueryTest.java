package com.linkroa.deepdataagent.runtime.application.query;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 应用层查询对象不变量单测（ListSessionsQuery / ReplayQuery）。
 */
class RuntimeQueryTest {

    @Test
    void should_buildQuery_when_construct_given_validInputs() {
        // when
        ListSessionsQuery query = new ListSessionsQuery("u-1", 2, 20);

        // then
        assertEquals("u-1", query.userId());
        assertEquals(2, query.page());
        assertEquals(20, query.size());
    }

    @Test
    void should_throw_when_construct_given_blankUserId() {
        // when & then
        assertThrows(IllegalArgumentException.class, () -> new ListSessionsQuery(" ", 1, 20));
    }

    @Test
    void should_throw_when_construct_given_zeroPage() {
        // when & then
        assertThrows(IllegalArgumentException.class, () -> new ListSessionsQuery("u-1", 0, 20));
    }

    @Test
    void should_throw_when_construct_given_sizeOverLimit() {
        // when & then
        assertThrows(IllegalArgumentException.class, () -> new ListSessionsQuery("u-1", 1, 101));
    }

    @Test
    void should_throw_when_construct_given_zeroSize() {
        // when & then
        assertThrows(IllegalArgumentException.class, () -> new ListSessionsQuery("u-1", 1, 0));
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
        ListSessionsQuery query = new ListSessionsQuery("u-1", 1, 100);

        // then
        assertEquals(100, query.size());
    }
}