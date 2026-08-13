import { describe, it, expect, beforeEach, afterEach, beforeAll } from 'vitest';
import { setActivePinia, createPinia } from 'pinia';
import { useAnalysisStore } from '../stores/analysis';
import { useSessionStateManager } from '../composables/useSessionStateManager';
import type { AgentEvent } from '../types';

/**
 * 会话状态管理器测试
 * <p>验证三个核心需求：
 * 1. 不同会话数据严格隔离（包括分析中的会话）
 * 2. 事件按接收顺序生成有序 contentItems（报告不再特殊化为独立区块）
 * 3. 工具调用入参/结果实时累积与收敛</p>
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

  describe('会话数据隔离（需求 1）', () => {
    it('后台会话事件不应污染当前会话的 analysisStore', () => {
      // given：当前会话 A 正在分析，已有思考内容
      analysisStore.startAnalysis('A');
      analysisStore.appendThinkingDelta('A 的第一轮思考');

      // 保存 A 的当前状态
      ssm.saveState('A');

      // when：后台会话 B 的事件到达（当前会话仍是 A）
      const toolResultBuffers = new Map<string, string>();
      const toolCallInputBuffers = new Map<string, string>();
      ssm.updateState('B', { type: 'THINKING_BLOCK_DELTA', delta: 'B 的思考' } as AgentEvent, 'A', toolResultBuffers, toolCallInputBuffers);

      // then：处理完 B 的事件后，analysisStore 应恢复为 A 的状态
      ssm.saveState('A');
      ssm.restoreState('A');
      expect(analysisStore.state.contentItems).toHaveLength(1);
      expect(analysisStore.state.contentItems[0].content).toBe('A 的第一轮思考');
      expect(analysisStore.analysisSessionId).toBe('A');
    });

    it('多个后台会话事件互不干扰，各自状态独立保存', () => {
      // given：当前会话 A 分析中
      analysisStore.startAnalysis('A');
      analysisStore.appendThinkingDelta('A 思考');

      // when：后台会话 B 收到事件
      const toolResultBuffers = new Map<string, string>();
      const toolCallInputBuffers = new Map<string, string>();
      ssm.updateState('B', { type: 'THINKING_BLOCK_DELTA', delta: 'B 思考' } as AgentEvent, 'A', toolResultBuffers, toolCallInputBuffers);
      // 后台会话 C 收到事件
      ssm.updateState('C', { type: 'THINKING_BLOCK_DELTA', delta: 'C 思考' } as AgentEvent, 'A', toolResultBuffers, toolCallInputBuffers);

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
      analysisStore.appendThinkingDelta('A 的最终思考');
      analysisStore.completeThinking();
      analysisStore.appendReportDelta('A 的报告');
      analysisStore.completeAnalysis();
      ssm.saveState('A');

      // 会话 B 开始分析
      analysisStore.reset();
      analysisStore.startAnalysis('B');
      analysisStore.appendThinkingDelta('B 的思考');

      // when：切换到会话 A
      ssm.restoreState('A');

      // then：analysisStore 恢复为 A 的状态，不包含 B 的内容
      expect(analysisStore.analysisSessionId).toBe('A');
      expect(analysisStore.state.contentItems).toHaveLength(2);
      expect(analysisStore.state.contentItems[0].content).toBe('A 的最终思考');
      expect(analysisStore.state.contentItems[1].content).toBe('A 的报告');
      expect(analysisStore.state.contentItems[0].content).not.toContain('B');
    });
  });

  describe('事件 → 有序内容流（需求 2）', () => {
    /** 为指定会话依次处理事件（当前会话即该会话） */
    function dispatch(sessionId: string, events: AgentEvent[], buffers?: { result: Map<string, string>; input: Map<string, string> }) {
      const toolResultBuffers = buffers?.result ?? new Map<string, string>();
      const toolCallInputBuffers = buffers?.input ?? new Map<string, string>();
      for (const event of events) {
        ssm.updateState(sessionId, event, sessionId, toolResultBuffers, toolCallInputBuffers);
      }
    }

    it('思考增量按接收顺序实时追加到进行中思考项', () => {
      // given
      analysisStore.startAnalysis('A');

      // when：思考增量事件依次到达
      dispatch('A', [
        { type: 'THINKING_BLOCK_DELTA', delta: '第一段' } as AgentEvent,
        { type: 'THINKING_BLOCK_DELTA', delta: '第二段' } as AgentEvent,
      ]);

      // then：单个思考内容项累积全部增量
      expect(analysisStore.state.contentItems).toHaveLength(1);
      expect(analysisStore.state.contentItems[0].type).toBe('thinking');
      expect(analysisStore.state.contentItems[0].content).toBe('第一段第二段');
      expect(analysisStore.state.contentItems[0].status).toBe('in_progress');
    });

    it('THINKING_BLOCK_END 应收敛思考项为 completed', () => {
      // given
      analysisStore.startAnalysis('A');

      // when
      dispatch('A', [
        { type: 'THINKING_BLOCK_DELTA', delta: '思考' } as AgentEvent,
        { type: 'THINKING_BLOCK_END' } as AgentEvent,
      ]);

      // then
      expect(analysisStore.state.contentItems[0].status).toBe('completed');
      expect(analysisStore.state.contentItems[0].endTime).toBeDefined();
    });

    it('报告文本增量作为内容流普通项展示，不特殊化为独立区块（移除报告特殊化）', () => {
      // given
      analysisStore.startAnalysis('A');

      // when：先思考、后报告文本增量
      dispatch('A', [
        { type: 'THINKING_BLOCK_DELTA', delta: '思考过程' } as AgentEvent,
        { type: 'THINKING_BLOCK_END' } as AgentEvent,
        { type: 'TEXT_BLOCK_DELTA', delta: '分析报告第一段' } as AgentEvent,
        { type: 'TEXT_BLOCK_DELTA', delta: '第二段' } as AgentEvent,
      ]);

      // then：报告内容作为内容流中的普通项按序追加在思考项之后
      expect(analysisStore.state.contentItems.map(i => i.type)).toEqual(['thinking', 'report']);
      const reportItem = analysisStore.state.contentItems[1];
      expect(reportItem.content).toBe('分析报告第一段第二段');
      expect(reportItem.seq).toBe(1);
      // 派生字段同步维护（兼容保留）
      expect(analysisStore.state.analysisReport).toBe('分析报告第一段第二段');
    });

    it('AGENT_RESULT 应用权威最终文本收敛报告项', () => {
      // given
      analysisStore.startAnalysis('A');

      // when
      dispatch('A', [
        { type: 'TEXT_BLOCK_DELTA', delta: '流式内容' } as AgentEvent,
        { type: 'AGENT_RESULT', result: { textContent: '权威全文' } } as AgentEvent,
      ]);

      // then
      const reportItem = analysisStore.state.contentItems[0];
      expect(reportItem.type).toBe('report');
      expect(reportItem.status).toBe('completed');
      expect(reportItem.content).toBe('权威全文');
      expect(analysisStore.state.analysisReport).toBe('权威全文');
    });

    it('完整事件流生成严格按序的内容流（思考→工具调用→工具结果→思考→报告）', () => {
      // given
      analysisStore.startAnalysis('A');
      const buffers = { result: new Map<string, string>(), input: new Map<string, string>() };

      // when：模拟完整 ReAct 流程
      dispatch('A', [
        { type: 'THINKING_BLOCK_DELTA', delta: '需要查询数据' } as AgentEvent,
        { type: 'THINKING_BLOCK_END' } as AgentEvent,
        { type: 'TOOL_CALL_START', toolCallName: 'retrieve_schema', toolCallId: 'tc1' } as AgentEvent,
        { type: 'TOOL_CALL_END', toolCallName: 'retrieve_schema', toolCallId: 'tc1' } as AgentEvent,
        { type: 'TOOL_RESULT_TEXT_DELTA', toolCallId: 'tc1', delta: 'schema' } as AgentEvent,
        { type: 'TOOL_RESULT_END', toolCallName: 'retrieve_schema', toolCallId: 'tc1' } as AgentEvent,
        { type: 'THINKING_BLOCK_DELTA', delta: '生成 SQL' } as AgentEvent,
        { type: 'THINKING_BLOCK_END' } as AgentEvent,
        { type: 'TEXT_BLOCK_DELTA', delta: '分析完成' } as AgentEvent,
        { type: 'AGENT_RESULT', result: { textContent: '分析完成' } } as AgentEvent,
      ], buffers);

      // then：内容流严格按事件顺序排列（调用与结果为两个独立内容项）
      expect(analysisStore.state.contentItems.map(i => i.type)).toEqual([
        'thinking', 'tool_call', 'tool_result', 'thinking', 'report',
      ]);
      for (let i = 0; i < analysisStore.state.contentItems.length; i++) {
        expect(analysisStore.state.contentItems[i].seq).toBe(i);
      }
      const toolCallItem = analysisStore.state.contentItems[1];
      expect(toolCallItem.status).toBe('completed');
      expect(toolCallItem.result).toBeUndefined();
      const toolResultItem = analysisStore.state.contentItems[2];
      expect(toolResultItem.type).toBe('tool_result');
      expect(toolResultItem.status).toBe('completed');
      expect(toolResultItem.result).toBe('schema');
    });

    it('AGENT_END 应触发 completeAnalysis（收敛进行中项）并保存状态', () => {
      // given
      analysisStore.startAnalysis('A');
      let completed = false;

      // when
      dispatch('A', [
        { type: 'THINKING_BLOCK_DELTA', delta: '思考' } as AgentEvent,
      ]);
      ssm.updateState('A', { type: 'AGENT_END' } as AgentEvent, 'A',
        new Map(), new Map(), () => { completed = true; });

      // then：分析结束，进行中项收敛，回调触发
      expect(analysisStore.state.isAnalyzing).toBe(false);
      expect(analysisStore.state.contentItems[0].status).toBe('completed');
      expect(completed).toBe(true);
    });
  });

  describe('工具入参/结果累积与收敛（需求 3）', () => {
    it('TOOL_CALL_DELTA 实时累积入参到进行中工具项，TOOL_CALL_END 清理缓冲区', () => {
      // given
      analysisStore.startAnalysis('A');
      const toolResultBuffers = new Map<string, string>();
      const toolCallInputBuffers = new Map<string, string>();

      // when：TOOL_CALL_START 建立进行中工具项，随后 DELTA 累积入参
      ssm.updateState('A', { type: 'TOOL_CALL_START', toolCallName: 'generate_sql', toolCallId: 'tc1' } as any, 'A', toolResultBuffers, toolCallInputBuffers);
      ssm.updateState('A', { type: 'TOOL_CALL_DELTA', toolCallId: 'tc1', delta: '{"sql":"SEL' } as any, 'A', toolResultBuffers, toolCallInputBuffers);
      ssm.updateState('A', { type: 'TOOL_CALL_DELTA', toolCallId: 'tc1', delta: 'ECT 1"}' } as any, 'A', toolResultBuffers, toolCallInputBuffers);
      ssm.updateState('A', { type: 'TOOL_CALL_END', toolCallId: 'tc1', toolCallName: 'generate_sql' } as any, 'A', toolResultBuffers, toolCallInputBuffers);

      // then：工具项入参被完整累积，且缓冲区已清理
      const toolItem = analysisStore.state.contentItems[0];
      expect(toolItem.toolName).toBe('generate_sql');
      expect(toolItem.input).toBe('{"sql":"SELECT 1"}');
      expect(toolCallInputBuffers.has('tc1')).toBe(false);
    });

    it('TOOL_RESULT_TEXT_DELTA 惰性创建独立结果项累积结果，TOOL_RESULT_END 收敛为 completed', () => {
      // given
      analysisStore.startAnalysis('A');
      const toolResultBuffers = new Map<string, string>();
      const toolCallInputBuffers = new Map<string, string>();

      // when：结果增量实时到达后结束
      ssm.updateState('A', { type: 'TOOL_CALL_START', toolCallName: 'execute_sql', toolCallId: 'tc1' } as any, 'A', toolResultBuffers, toolCallInputBuffers);
      ssm.updateState('A', { type: 'TOOL_RESULT_TEXT_DELTA', toolCallId: 'tc1', delta: '{"data":[' } as any, 'A', toolResultBuffers, toolCallInputBuffers);
      ssm.updateState('A', { type: 'TOOL_RESULT_TEXT_DELTA', toolCallId: 'tc1', delta: '1]}' } as any, 'A', toolResultBuffers, toolCallInputBuffers);
      ssm.updateState('A', { type: 'TOOL_RESULT_END', toolCallName: 'execute_sql', toolCallId: 'tc1' } as any, 'A', toolResultBuffers, toolCallInputBuffers);

      // then：结果为独立内容项实时累积并收敛，调用项不携带结果
      expect(analysisStore.state.contentItems).toHaveLength(2);
      const toolCallItem = analysisStore.state.contentItems[0];
      expect(toolCallItem.type).toBe('tool_call');
      expect(toolCallItem.result).toBeUndefined();
      const toolResultItem = analysisStore.state.contentItems[1];
      expect(toolResultItem.type).toBe('tool_result');
      expect(toolResultItem.toolName).toBe('execute_sql');
      expect(toolResultItem.result).toBe('{"data":[1]}');
      expect(toolResultItem.status).toBe('completed');
    });

    it('不同 toolCallId 的入参互不污染', () => {
      // given
      analysisStore.startAnalysis('A');
      const toolResultBuffers = new Map<string, string>();
      const toolCallInputBuffers = new Map<string, string>();

      // when：两个不同 toolCallId 的入参增量交错到达
      ssm.updateState('A', { type: 'TOOL_CALL_START', toolCallName: 't1', toolCallId: 'tc1' } as any, 'A', toolResultBuffers, toolCallInputBuffers);
      ssm.updateState('A', { type: 'TOOL_CALL_START', toolCallName: 't2', toolCallId: 'tc2' } as any, 'A', toolResultBuffers, toolCallInputBuffers);
      ssm.updateState('A', { type: 'TOOL_CALL_DELTA', toolCallId: 'tc1', delta: 'A' } as any, 'A', toolResultBuffers, toolCallInputBuffers);
      ssm.updateState('A', { type: 'TOOL_CALL_DELTA', toolCallId: 'tc2', delta: 'B' } as any, 'A', toolResultBuffers, toolCallInputBuffers);
      ssm.updateState('A', { type: 'TOOL_CALL_END', toolCallId: 'tc1', toolCallName: 't1' } as any, 'A', toolResultBuffers, toolCallInputBuffers);
      ssm.updateState('A', { type: 'TOOL_CALL_END', toolCallId: 'tc2', toolCallName: 't2' } as any, 'A', toolResultBuffers, toolCallInputBuffers);

      // then：各自累积到对应工具项
      const toolItems = analysisStore.state.contentItems;
      expect(toolItems).toHaveLength(2);
      expect(toolItems[0].input).toBe('A');
      expect(toolItems[1].input).toBe('B');
    });
  });
});
