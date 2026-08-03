import { describe, it, expect, beforeEach, afterEach, beforeAll } from 'vitest';
import { setActivePinia, createPinia } from 'pinia';
import { useAnalysisStore } from '../stores/analysis';
import { useSessionStateManager } from '../composables/useSessionStateManager';
import type { AgentEvent } from '../types';

/**
 * 会话状态管理器测试
 * <p>验证两个核心需求：
 * 1. 不同会话数据严格隔离（包括分析中的会话）
 * 2. 分析中的会话多轮对话可正常渲染（状态保存/恢复不丢失已有轮次）</p>
 * <p>注意：useSessionStateManager 是模块级单例，首次调用时内部捕获 analysisStore 引用。
 * 因此整个测试文件必须使用同一个 Pinia 实例，避免单例内部 store 与测试断言用的 store 不一致。</p>
 */
describe('useSessionStateManager', () => {
  let analysisStore: ReturnType<typeof useAnalysisStore>;
  let ssm: ReturnType<typeof useSessionStateManager>;

  beforeAll(() => {
    setActivePinia(createPinia());
    analysisStore = useAnalysisStore();
    ssm = useSessionStateManager();
  });

  beforeEach(() => {
    // 重置分析状态，避免测试间相互污染
    analysisStore.reset();
  });

  afterEach(() => {
    // 清理单例中保存的会话状态，避免测试间相互污染
    for (const id of ['A', 'B', 'C']) {
      ssm.cleanupState(id);
    }
    analysisStore.reset();
  });

  /** 构造一个 thinking 轮次事件 */
  function thinkingEvent(roundSeq: number): AgentEvent {
    return {
      type: 'THINKING_BLOCK_START',
      sessionId: '' as any,
    } as AgentEvent;
  }

  describe('会话数据隔离（需求 1）', () => {
    it('后台会话事件不应污染当前会话的 analysisStore', () => {
      // given：当前会话 A 正在分析，已有第一轮思考
      analysisStore.startAnalysis('A');
      analysisStore.startNewRound();
      analysisStore.appendThinkingToCurrentRound('A 的第一轮思考');

      // 保存 A 的当前状态
      ssm.saveState('A');

      // when：后台会话 B 的事件到达（当前会话仍是 A）
      const toolResultBuffers = new Map<string, string>();
      ssm.updateState('B', thinkingEvent(1), 'A', toolResultBuffers);

      // then：处理完 B 的事件后，analysisStore 应恢复为 A 的状态
      // 通过 saveState 后再 restoreState 校验 A 的状态未被 B 覆盖
      ssm.saveState('A');
      ssm.restoreState('A');
      expect(analysisStore.state.rounds).toHaveLength(1);
      expect(analysisStore.state.rounds[0].thinking.content).toBe('A 的第一轮思考');
      expect(analysisStore.analysisSessionId).toBe('A');
    });

    it('多个后台会话事件互不干扰，各自状态独立保存', () => {
      // given：当前会话 A 分析中
      analysisStore.startAnalysis('A');
      analysisStore.startNewRound();
      analysisStore.appendThinkingToCurrentRound('A 思考');

      // when：后台会话 B 收到事件
      const toolResultBuffers = new Map<string, string>();
      ssm.updateState('B', thinkingEvent(1), 'A', toolResultBuffers);
      // 后台会话 C 收到事件
      ssm.updateState('C', thinkingEvent(1), 'A', toolResultBuffers);

      // then：A 与 B、C 的状态各自独立保存
      const stateA = ssm.getSavedState('A');
      const stateB = ssm.getSavedState('B');
      const stateC = ssm.getSavedState('C');
      expect(stateA).toBeDefined();
      expect(stateB).toBeDefined();
      expect(stateC).toBeDefined();
      // 三个会话状态互不共享同一对象引用
      expect(stateA).not.toBe(stateB);
      expect(stateB).not.toBe(stateC);
    });

    it('切换会话后恢复目标会话状态，不残留当前会话内容', () => {
      // given：会话 A 分析完成，保存状态
      analysisStore.startAnalysis('A');
      analysisStore.startNewRound();
      analysisStore.appendThinkingToCurrentRound('A 的最终思考');
      analysisStore.finalizeCurrentRoundThinking();
      analysisStore.completeAnalysis();
      ssm.saveState('A');

      // 会话 B 开始分析
      analysisStore.reset();
      analysisStore.startAnalysis('B');
      analysisStore.startNewRound();
      analysisStore.appendThinkingToCurrentRound('B 的思考');

      // when：切换到会话 A
      ssm.restoreState('A');

      // then：analysisStore 恢复为 A 的状态，不包含 B 的内容
      expect(analysisStore.analysisSessionId).toBe('A');
      expect(analysisStore.state.rounds).toHaveLength(1);
      expect(analysisStore.state.rounds[0].thinking.content).toBe('A 的最终思考');
      expect(analysisStore.state.rounds[0].thinking.content).not.toContain('B');
    });
  });

  describe('多轮对话状态保存/恢复（需求 2）', () => {
    it('分析中的会话保存多轮数据后恢复，历史轮次不丢失', () => {
      // given：会话 A 进行中，已有一轮完成的思考 + 一轮进行中的思考
      analysisStore.startAnalysis('A');
      // 第一轮（已完成）
      analysisStore.startNewRound();
      analysisStore.appendThinkingToCurrentRound('第一轮思考');
      analysisStore.finalizeCurrentRoundThinking();
      analysisStore.addToolCallToCurrentRound('retrieve_schema', '{}');
      analysisStore.updateToolCallInCurrentRound('retrieve_schema', 'schema', true);
      // 第二轮（进行中）
      analysisStore.appendThinkingToCurrentRound('第二轮思考');
      analysisStore.finalizeCurrentRoundThinking();
      analysisStore.addToolCallToCurrentRound('execute_sql', 'SELECT 1');

      // when：保存会话 A 状态（模拟切换会话），再恢复
      ssm.saveState('A');
      analysisStore.reset();
      ssm.restoreState('A');

      // then：恢复后应包含两轮完整的对话数据，且仍在分析中
      expect(analysisStore.state.isAnalyzing).toBe(true);
      expect(analysisStore.state.rounds).toHaveLength(2);
      expect(analysisStore.state.rounds[0].thinking.content).toBe('第一轮思考');
      expect(analysisStore.state.rounds[0].toolCalls[0].toolName).toBe('retrieve_schema');
      expect(analysisStore.state.rounds[0].toolCalls[0].status).toBe('success');
      expect(analysisStore.state.rounds[1].thinking.content).toBe('第二轮思考');
      expect(analysisStore.state.rounds[1].toolCalls[0].toolName).toBe('execute_sql');
      expect(analysisStore.state.rounds[1].toolCalls[0].status).toBe('running');
    });

    it('恢复后的状态与保存的状态共享同一套数据，不相互污染', () => {
      // given：会话 A 保存了第一轮数据
      analysisStore.startAnalysis('A');
      analysisStore.startNewRound();
      analysisStore.appendThinkingToCurrentRound('第一轮思考');
      analysisStore.finalizeCurrentRoundThinking();
      ssm.saveState('A');

      // when：恢复 A 后追加第二轮思考
      analysisStore.reset();
      ssm.restoreState('A');
      analysisStore.appendThinkingToCurrentRound('第二轮思考');

      // then：重新保存并恢复后，两个轮次都存在
      ssm.saveState('A');
      analysisStore.reset();
      ssm.restoreState('A');
      expect(analysisStore.state.rounds).toHaveLength(2);
      expect(analysisStore.state.rounds[0].thinking.content).toBe('第一轮思考');
      expect(analysisStore.state.rounds[1].thinking.content).toBe('第二轮思考');
    });
  });
});