package com.linkroa.deepdataagent.runtime.domain.model;

import com.linkroa.deepdataagent.runtime.domain.model.enums.RoundStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link ExecutionRound} 领域模型不变量单测。
 */
class ExecutionRoundTest {

    @Test
    void should_createRunningRound_when_create_given_validInputs() {
        // given
        String sessionId = "s-1";

        // when
        ExecutionRound round = ExecutionRound.create(sessionId, "run-1", 1, "你好，帮我分析数据");

        // then
        assertNotNull(round.roundId());
        assertEquals(sessionId, round.sessionId());
        assertEquals("run-1", round.runId());
        assertEquals(1, round.roundNumber());
        assertEquals(RoundStatus.RUNNING, round.status());
        assertEquals("你好，帮我分析数据", round.input());
        assertNull(round.output());
        assertNull(round.replayedFromRoundId());
        assertNotNull(round.createdAt());
    }

    @Test
    void should_createReplayedRound_when_createReplayed_given_sourceRoundId() {
        // given
        String sourceRoundId = "round-0";

        // when
        ExecutionRound round = ExecutionRound.createReplayed("s-1", "run-2", 2, "再分析一次", sourceRoundId);

        // then
        assertEquals(RoundStatus.RUNNING, round.status());
        assertEquals(sourceRoundId, round.replayedFromRoundId());
        assertEquals(2, round.roundNumber());
    }

    @Test
    void should_throw_when_construct_given_blankRoundId() {
        // given
        ExecutionRound factory = ExecutionRound.create("s-1", "run-1", 1, "input");

        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> new ExecutionRound(factory.id(), "", factory.sessionId(), factory.runId(),
                        factory.roundNumber(), factory.input(), factory.output(), factory.status(),
                        factory.replayedFromRoundId(), factory.createdAt(), factory.updatedAt(),
                        factory.createdBy(), factory.updatedBy()));
    }

    @Test
    void should_throw_when_construct_given_blankSessionId() {
        // given
        ExecutionRound factory = ExecutionRound.create("s-1", "run-1", 1, "input");

        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> new ExecutionRound(factory.id(), factory.roundId(), " ", factory.runId(),
                        factory.roundNumber(), factory.input(), factory.output(), factory.status(),
                        factory.replayedFromRoundId(), factory.createdAt(), factory.updatedAt(),
                        factory.createdBy(), factory.updatedBy()));
    }

    @Test
    void should_throw_when_construct_given_zeroRoundNumber() {
        // given
        ExecutionRound factory = ExecutionRound.create("s-1", "run-1", 1, "input");

        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> new ExecutionRound(factory.id(), factory.roundId(), factory.sessionId(), factory.runId(),
                        0, factory.input(), factory.output(), factory.status(),
                        factory.replayedFromRoundId(), factory.createdAt(), factory.updatedAt(),
                        factory.createdBy(), factory.updatedBy()));
    }

    @Test
    void should_throw_when_construct_given_nullInput() {
        // given
        ExecutionRound factory = ExecutionRound.create("s-1", "run-1", 1, "input");

        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> new ExecutionRound(factory.id(), factory.roundId(), factory.sessionId(), factory.runId(),
                        factory.roundNumber(), null, factory.output(), factory.status(),
                        factory.replayedFromRoundId(), factory.createdAt(), factory.updatedAt(),
                        factory.createdBy(), factory.updatedBy()));
    }

    @Test
    void should_throw_when_construct_given_nullStatus() {
        // given
        ExecutionRound factory = ExecutionRound.create("s-1", "run-1", 1, "input");

        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> new ExecutionRound(factory.id(), factory.roundId(), factory.sessionId(), factory.runId(),
                        factory.roundNumber(), factory.input(), factory.output(), null,
                        factory.replayedFromRoundId(), factory.createdAt(), factory.updatedAt(),
                        factory.createdBy(), factory.updatedBy()));
    }

    @Test
    void should_completeRound_when_complete_given_finalStatusAndOutput() {
        // given
        ExecutionRound round = ExecutionRound.create("s-1", "run-1", 1, "input");

        // when
        ExecutionRound completed = round.complete("这是最终答案", RoundStatus.COMPLETED);

        // then
        assertEquals(RoundStatus.COMPLETED, completed.status());
        assertEquals("这是最终答案", completed.output());
        assertEquals(round.roundId(), completed.roundId());
        assertNotNull(completed.updatedAt());
    }

    @Test
    void should_restoreRound_when_restore_given_fullFields() {
        // given
        ExecutionRound origin = ExecutionRound.create("s-1", "run-1", 1, "input");
        ExecutionRound completed = origin.complete("out", RoundStatus.COMPLETED);

        // when
        ExecutionRound restored = ExecutionRound.restore(
                10L, completed.roundId(), completed.sessionId(), completed.runId(), completed.roundNumber(),
                completed.input(), completed.output(), completed.status(), completed.replayedFromRoundId(),
                completed.createdAt(), completed.updatedAt(), completed.createdBy(), completed.updatedBy());

        // then
        assertEquals(10L, restored.id());
        assertEquals(completed.output(), restored.output());
        assertEquals(RoundStatus.COMPLETED, restored.status());
    }
}