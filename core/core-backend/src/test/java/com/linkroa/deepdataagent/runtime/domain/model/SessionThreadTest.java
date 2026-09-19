package com.linkroa.deepdataagent.runtime.domain.model;

import com.linkroa.deepdataagent.runtime.domain.model.enums.AgentSessionStatus;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SessionThread} 领域模型不变量单测（sthr_ 前缀、归属会话、状态镜像、快照收敛）。
 * <p>线程不使用 {@code archived} 状态取值（归档是会话侧的 {@code archived_at} 正交维度），
 * 本模型只提供 {@link SessionThread#withStatus} 状态镜像派生。</p>
 */
class SessionThreadTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-08-22T10:00:00+08:00");

    @Test
    void should_throwIllegal_when_construct_given_missingPrefixThreadId() {
        // given & when & then（非 sthr_ 前缀直接拒绝）
        assertThrows(IllegalArgumentException.class, () -> new SessionThread(
                null, "thr_1", "sess_1", null, "{}", AgentSessionStatus.IDLE,
                null, NOW, NOW, null, null));
    }

    @Test
    void should_throwIllegal_when_construct_given_blankSessionId() {
        // given & when & then
        assertThrows(IllegalArgumentException.class, () -> new SessionThread(
                null, "sthr_1", " ", null, "{}", AgentSessionStatus.IDLE,
                null, NOW, NOW, null, null));
    }

    @Test
    void should_throwIllegal_when_construct_given_nullStatus() {
        // given & when & then
        assertThrows(IllegalArgumentException.class, () -> new SessionThread(
                null, "sthr_1", "sess_1", null, "{}", null,
                null, NOW, NOW, null, null));
    }

    @Test
    void should_acceptArchivedAtWithAnyStatus_when_construct_given_orthogonalArchive() {
        // given & when（归档是正交时间戳列：可与 terminated 等任意状态组合，不再有 archived 状态取值）
        SessionThread thread = new SessionThread(null, "sthr_1", "sess_1", "sthr_main", "{}",
                AgentSessionStatus.TERMINATED, NOW, NOW, NOW, null, null);

        // then
        assertEquals(AgentSessionStatus.TERMINATED, thread.status());
        assertEquals(NOW, thread.archivedAt());
    }

    @Test
    void should_defaultEmptyObject_when_construct_given_blankAgent() {
        // given（快照空白收敛为空 JSON 对象文本）
        // when
        SessionThread thread = new SessionThread(null, "sthr_1", "sess_1", null, " ",
                AgentSessionStatus.IDLE, null, NOW, NOW, null, null);

        // then
        assertEquals("{}", thread.agent());
    }

    @Test
    void should_createIdleMainThread_when_createMain_given_sessionAndSnapshot() {
        // given & when
        SessionThread main = SessionThread.createMain("sess_1", "{\"id\":\"agent-a\"}");

        // then（sthr_ 前缀 + parent=null 即主线程 + idle）
        assertTrue(main.threadId().startsWith("sthr_"));
        assertEquals("sess_1", main.sessionId());
        assertNull(main.parentThreadId());
        assertTrue(main.isMainThread());
        assertEquals(AgentSessionStatus.IDLE, main.status());
        assertNull(main.archivedAt());
    }

    @Test
    void should_beChildThread_when_isMainThread_given_parentThreadId() {
        // given
        SessionThread child = SessionThread.restore(1L, "sthr_child", "sess_1", "sthr_main", "{}",
                AgentSessionStatus.IDLE, null, NOW, NOW, null, null);

        // when / then
        assertFalse(child.isMainThread());
    }

    @Test
    void should_mirrorStatusAndPreserveArchivedAt_when_withStatus_given_archivedThread() {
        // given（已归档子线程，状态镜像派生应只改 status、不动归档时间戳）
        SessionThread archived = SessionThread.restore(1L, "sthr_child", "sess_1", "sthr_main", "{}",
                AgentSessionStatus.IDLE, NOW, NOW, NOW, null, null);

        // when
        SessionThread mirrored = archived.withStatus(AgentSessionStatus.TERMINATED);

        // then（身份字段与 archivedAt 原样保留，updatedAt 刷新不早于原值）
        assertEquals(AgentSessionStatus.TERMINATED, mirrored.status());
        assertEquals(NOW, mirrored.archivedAt());
        assertEquals("sthr_child", mirrored.threadId());
        assertEquals("sess_1", mirrored.sessionId());
        assertFalse(mirrored.updatedAt().isBefore(archived.updatedAt()));
    }

    @Test
    void should_throwIllegal_when_withStatus_given_nullStatus() {
        // given（线程状态不可为空——镜像到未知态应被不变量拒绝）
        SessionThread thread = SessionThread.restore(1L, "sthr_child", "sess_1", "sthr_main", "{}",
                AgentSessionStatus.IDLE, null, NOW, NOW, null, null);

        // when / then
        assertThrows(IllegalArgumentException.class, () -> thread.withStatus(null));
    }
}