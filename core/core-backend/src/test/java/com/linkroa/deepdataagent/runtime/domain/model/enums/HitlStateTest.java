package com.linkroa.deepdataagent.runtime.domain.model.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HitlState} HITL 正交子态状态机单测：NONE ⇄ WAITING_CONFIRM。
 */
class HitlStateTest {

    @Test
    void should_allowNoneToWaitingConfirm_when_canTransitionTo_given_none() {
        // when & then（暂停：NONE 仅允许迁往 WAITING_CONFIRM）
        assertTrue(HitlState.NONE.canTransitionTo(HitlState.WAITING_CONFIRM));
        assertFalse(HitlState.NONE.canTransitionTo(HitlState.NONE));
    }

    @Test
    void should_allowWaitingConfirmToNone_when_canTransitionTo_given_waitingConfirm() {
        // when & then（恢复 / 拒绝：WAITING_CONFIRM 仅允许迁回 NONE）
        assertTrue(HitlState.WAITING_CONFIRM.canTransitionTo(HitlState.NONE));
        assertFalse(HitlState.WAITING_CONFIRM.canTransitionTo(HitlState.WAITING_CONFIRM));
    }

    @Test
    void should_throwOnIllegalTransition_when_validateTransition_given_noneToNone() {
        // when & then（非法转换必须报错）
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> HitlState.NONE.validateTransition(HitlState.NONE));
        assertTrue(ex.getMessage().contains("NONE"));
    }
}