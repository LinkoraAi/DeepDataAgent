package com.linkroa.deepdataagent.runtime.domain.model.runstate;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ConfirmCandidateBatch} 单测（原 {@code AgentRunStateTest} 的候选批次定位断言原样平移；
 * 错配即拒绝的行为变更属 task 3 D19，随该阶段在应用层与组件测试中一并落地）。
 */
class ConfirmCandidateBatchTest {

    @Test
    void should_followBatchOrderAndSkipUnknown_when_confirmBatch_given_mixedIds() {
        // given：两个候选按到达顺序登记（LinkedHashMap 保序）
        ConfirmCandidateBatch state = new ConfirmCandidateBatch();
        state.rememberConfirmCandidate("tc-1", "search", "{}", "evt-1");
        state.rememberConfirmCandidate("tc-2", "calculator", "{}", "evt-2");

        // when：信号批次乱序且含未登记 id
        List<ConfirmCandidateBatch.ConfirmCandidate> batch = state.confirmBatch(List.of("tc-2", "unknown", "tc-1"));

        // then：按信号批次顺序命中、未登记 id 跳过
        assertEquals(2, batch.size());
        assertEquals("tc-2", batch.get(0).toolCallId());
        assertEquals("tc-1", batch.get(1).toolCallId());
    }

    @Test
    void should_returnEmptyBatch_when_confirmBatch_given_noIdMatched() {
        // given：候选已登记但信号批次 id 全部未命中（D19 对称分支①：授权错配）
        ConfirmCandidateBatch state = new ConfirmCandidateBatch();
        state.rememberConfirmCandidate("tc-1", "search", "{}", "evt-1");
        state.rememberConfirmCandidate("tc-2", "calculator", "{}", "evt-2");

        // when：D19 后不再兜底取最近候选
        List<ConfirmCandidateBatch.ConfirmCandidate> batch = state.confirmBatch(List.of("unknown"));

        // then：无命中返回空批，交由应用层拒绝挂起（不落静默错配的等待项）
        assertTrue(batch.isEmpty());
    }

    @Test
    void should_returnEmptyBatch_when_confirmBatch_given_noCandidateRegistered() {
        // given：本轮候选登记为空（D19 对称分支②：含信号未携带任何 id / null 批次入参）
        ConfirmCandidateBatch state = new ConfirmCandidateBatch();

        // when & then：空候选表任何批次定位结果均为空
        assertTrue(state.confirmBatch(List.of("unknown")).isEmpty());
        assertTrue(state.confirmBatch(null).isEmpty());
        assertTrue(state.confirmBatch(List.of()).isEmpty());
    }

    @Test
    void should_alignOnlyRegisteredCandidates_when_confirmBatch_given_partialMatch() {
        // given：批次携带已登记与未登记 id（D19 部分命中）
        ConfirmCandidateBatch state = new ConfirmCandidateBatch();
        state.rememberConfirmCandidate("tc-1", "search", "{}", "evt-1");

        // when
        List<ConfirmCandidateBatch.ConfirmCandidate> batch =
                state.confirmBatch(List.of("tc-1", "ghost"));

        // then：仅装配已对齐候选，不引入未登记候选，等待以完整信号照常确立
        assertEquals(1, batch.size());
        assertEquals("tc-1", batch.get(0).toolCallId());
        assertEquals("evt-1", batch.get(0).toolEventId());
    }

    @Test
    void should_exposeRegisteredIdsAndCount_when_registeredIds_given_candidateOrder() {
        // given
        ConfirmCandidateBatch state = new ConfirmCandidateBatch();
        state.rememberConfirmCandidate("tc-1", "search", "{}", "evt-1");
        state.rememberConfirmCandidate("tc-2", "calculator", "{}", "evt-2");

        // when & then（D19 错配诊断视图：登记数与保序 id 集合）
        assertEquals(2, state.registeredCount());
        assertEquals(List.of("tc-1", "tc-2"), List.copyOf(state.registeredIds()));
    }
}
