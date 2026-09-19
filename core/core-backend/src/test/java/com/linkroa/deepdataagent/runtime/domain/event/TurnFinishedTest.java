package com.linkroa.deepdataagent.runtime.domain.event;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link TurnFinished} 领域事件不变量单测（fix-runtime-layering 5.1）：
 * sessionId / outcome 必填，triggerType 可空（非调度触发由监听器忽略）。
 */
class TurnFinishedTest {

    @Test
    void should_holdFields_when_construct_given_scheduledTrigger() {
        // given / when
        TurnFinished event = new TurnFinished("sess_1", "cron", "succeeded");

        // then
        assertEquals("sess_1", event.sessionId());
        assertEquals("cron", event.triggerType());
        assertEquals("succeeded", event.outcome());
    }

    @Test
    void should_allowNullTriggerType_when_construct_given_plainSessionTerminal() {
        // given / when（非调度触发：打标为空，监听器据此忽略）
        TurnFinished event = new TurnFinished("sess_1", null, "failed");

        // then
        assertNull(event.triggerType());
    }

    @Test
    void should_rejectBlankSessionId_when_construct_given_blankSessionId() {
        // given / when / then
        assertThrows(IllegalArgumentException.class,
                () -> new TurnFinished(" ", "cron", "succeeded"));
    }

    @Test
    void should_rejectBlankOutcome_when_construct_given_blankOutcome() {
        // given / when / then
        assertThrows(IllegalArgumentException.class,
                () -> new TurnFinished("sess_1", "cron", ""));
    }
}
