package com.linkroa.deepdataagent.runtime.domain.service;

import com.linkroa.deepdataagent.runtime.domain.model.enums.TurnTerminalKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TurnResult} 值对象不变量与收流分类单测（终态决策输入侧）。
 * <p>分类真值表按现状分支顺序独立重述：进行中的取消 &gt; HITL 挂起驻留 &gt; 迭代上限 &gt; 正常结束。</p>
 */
class TurnResultTest {

    @Test
    void should_carryNoMessage_when_completed_given_factoryMethod() {
        // given / when
        TurnResult result = TurnResult.completed();

        // then
        assertEquals(TurnTerminalKind.COMPLETED, result.kind());
        assertNull(result.errorMessage());
    }

    @ParameterizedTest
    @CsvSource({
            "true, false, false, INTERRUPTED",
            "true, true, true, INTERRUPTED",
            "false, true, true, HITL_SUSPENDED",
            "false, false, true, MAX_ITERATIONS",
            "false, false, false, COMPLETED"
    })
    void should_classifyByPriorityOrder_when_classifyStreamClose_given_flagCombinations(
            boolean cancelRequested, boolean confirmationPending, boolean exceededMaxIters,
            TurnTerminalKind expectedKind) {
        // given / when
        TurnResult result = TurnResult.classifyStreamClose(cancelRequested, confirmationPending, exceededMaxIters);

        // then：取消优先于上限（竞态时按中断语义收敛回 idle，不落 terminated）；
        // 挂起驻留优先于上限（HITL 场景不进终态收尾）
        assertEquals(expectedKind, result.kind());
    }

    @Test
    void should_carrySanitizedMessage_when_executionError_given_message() {
        // given / when
        TurnResult result = TurnResult.executionError("上游 500");

        // then
        assertEquals(TurnTerminalKind.EXECUTION_ERROR, result.kind());
        assertEquals("上游 500", result.errorMessage());
    }

    @Test
    void should_throwIllegalArgument_when_constructor_given_errorWithoutMessage() {
        // given / when / then：出错终态必须携带错误信息（否则 session.error 事件无载荷）
        assertThrows(IllegalArgumentException.class, () -> TurnResult.executionError(null));
        assertThrows(IllegalArgumentException.class, () -> TurnResult.executionError("  "));
        assertThrows(IllegalArgumentException.class,
                () -> new TurnResult(TurnTerminalKind.EXECUTION_ERROR, ""));
    }

    @Test
    void should_throwIllegalArgument_when_constructor_given_nullKind() {
        // given / when / then
        assertThrows(IllegalArgumentException.class, () -> new TurnResult(null, null));
    }

    @Test
    void should_allowMessagelessNonErrorKinds_when_constructor_given_extraMessageIgnored() {
        // given：非出错种类允许不携带错误信息
        // when
        TurnResult interrupted = TurnResult.interrupted();
        TurnResult maxIterations = TurnResult.maxIterations();
        TurnResult suspended = TurnResult.hitlSuspended();

        // then
        assertEquals(TurnTerminalKind.INTERRUPTED, interrupted.kind());
        assertEquals(TurnTerminalKind.MAX_ITERATIONS, maxIterations.kind());
        assertTrue(suspended.errorMessage() == null);
    }
}
