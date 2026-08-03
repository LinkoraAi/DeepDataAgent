import { describe, it, expect, beforeEach } from 'vitest';
import { setActivePinia, createPinia } from 'pinia';
import { useAnalysisStore } from '../stores/analysis';

describe('AnalysisStore - ReAct 轮次模型', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
  });

  describe('基础生命周期', () => {
    it('startAnalysis 应设置 isAnalyzing=true 并记录开始时间', () => {
      const store = useAnalysisStore();
      store.startAnalysis();

      expect(store.state.isAnalyzing).toBe(true);
      expect(store.state.analysisStartTime).not.toBeNull();
      expect(store.state.rounds).toEqual([]);
      expect(store.state.currentRoundId).toBeNull();
    });

    it('completeAnalysis 应设置 isAnalyzing=false、记录结束时间、清理 currentRoundId', () => {
      const store = useAnalysisStore();
      store.startAnalysis();
      store.appendThinkingToCurrentRound('思考内容');
      store.finalizeCurrentRoundThinking();

      store.completeAnalysis();

      expect(store.state.isAnalyzing).toBe(false);
      expect(store.state.analysisEndTime).not.toBeNull();
      expect(store.state.currentRoundId).toBeNull();
    });

    it('completeAnalysis 应将所有轮次标记为 isActive=false、isCollapsed=true', () => {
      const store = useAnalysisStore();
      store.startAnalysis();
      store.appendThinkingToCurrentRound('思考1');
      store.finalizeCurrentRoundThinking();

      store.completeAnalysis();

      for (const round of store.state.rounds) {
        expect(round.isActive).toBe(false);
        expect(round.isCollapsed).toBe(true);
      }
    });

    it('reset 应清空所有状态', () => {
      const store = useAnalysisStore();
      store.startAnalysis();
      store.appendThinkingToCurrentRound('思考');

      store.reset();

      expect(store.state.rounds).toEqual([]);
      expect(store.state.currentRoundId).toBeNull();
      expect(store.state.isAnalyzing).toBe(false);
      expect(store.state.analysisReport).toBeNull();
    });
  });

  describe('轮次管理 - startNewRound', () => {
    it('应创建新轮次并设为 currentRoundId', () => {
      const store = useAnalysisStore();
      store.startAnalysis();

      const round = store.startNewRound();

      expect(store.state.rounds).toHaveLength(1);
      expect(round.id).toBe(store.state.currentRoundId);
      expect(round.thinking.isStreaming).toBe(true);
      expect(round.thinking.content).toBe('');
      expect(round.toolCalls).toEqual([]);
      expect(round.isActive).toBe(true);
    });

    it('已有 active 轮次时再 startNewRound 应强制结束旧轮次', () => {
      const store = useAnalysisStore();
      store.startAnalysis();
      const round1 = store.startNewRound();
      store.appendThinkingToCurrentRound('思考1');

      // 旧轮次仍有 active 工具时开启新轮次
      store.addToolCallToCurrentRound('test_tool');
      const round2 = store.startNewRound();

      expect(store.state.rounds).toHaveLength(2);
      expect(round1.id).not.toBe(round2.id);
      expect(round1.isActive).toBe(false); // 旧轮次被强制结束
      expect(round1.endTime).toBeDefined();
      expect(store.state.currentRoundId).toBe(round2.id);
    });
  });

  describe('轮次管理 - appendThinkingToCurrentRound', () => {
    it('无当前轮次时应自动创建新轮次', () => {
      const store = useAnalysisStore();
      store.startAnalysis();

      store.appendThinkingToCurrentRound('第一次思考');

      expect(store.state.rounds).toHaveLength(1);
      expect(store.state.rounds[0].thinking.content).toBe('第一次思考');
      expect(store.state.rounds[0].thinking.isStreaming).toBe(true);
    });

    it('当前轮次思考流式中应追加到 content', () => {
      const store = useAnalysisStore();
      store.startAnalysis();
      store.appendThinkingToCurrentRound('第一段');

      store.appendThinkingToCurrentRound('第二段');

      expect(store.state.rounds).toHaveLength(1);
      expect(store.state.rounds[0].thinking.content).toBe('第一段第二段');
    });

    it('当前轮次思考已结束时再追加应创建新轮次', () => {
      const store = useAnalysisStore();
      store.startAnalysis();
      store.appendThinkingToCurrentRound('第一轮思考');
      store.finalizeCurrentRoundThinking();

      store.appendThinkingToCurrentRound('第二轮思考');

      expect(store.state.rounds).toHaveLength(2);
      expect(store.state.rounds[1].thinking.content).toBe('第二轮思考');
    });

    it('空 delta 应被忽略', () => {
      const store = useAnalysisStore();
      store.startAnalysis();

      store.appendThinkingToCurrentRound('');

      expect(store.state.rounds).toHaveLength(0);
    });
  });

  describe('轮次管理 - finalizeCurrentRoundThinking', () => {
    it('应标记当前轮次思考为完成，保留 currentRoundId', () => {
      const store = useAnalysisStore();
      store.startAnalysis();
      store.appendThinkingToCurrentRound('思考');
      const roundId = store.state.currentRoundId;

      store.finalizeCurrentRoundThinking();

      expect(store.state.rounds[0].thinking.isStreaming).toBe(false);
      expect(store.state.rounds[0].isActive).toBe(false); // 无 running 工具，isActive 变 false
      expect(store.state.currentRoundId).toBe(roundId); // 保留等待工具
    });
  });

  describe('轮次管理 - addToolCallToCurrentRound', () => {
    it('应在当前轮次新增 running 工具', () => {
      const store = useAnalysisStore();
      store.startAnalysis();
      store.appendThinkingToCurrentRound('思考');
      store.finalizeCurrentRoundThinking();

      store.addToolCallToCurrentRound('retrieve_schema', '{"datasource": 1}');

      expect(store.state.rounds[0].toolCalls).toHaveLength(1);
      expect(store.state.rounds[0].toolCalls[0].toolName).toBe('retrieve_schema');
      expect(store.state.rounds[0].toolCalls[0].status).toBe('running');
      expect(store.state.rounds[0].isActive).toBe(true); // 有 running 工具
    });

    it('无当前轮次时应创建空 thinking 兜底轮次', () => {
      const store = useAnalysisStore();
      store.startAnalysis();

      store.addToolCallToCurrentRound('test_tool');

      expect(store.state.rounds).toHaveLength(1);
      expect(store.state.rounds[0].thinking.content).toBe('');
      expect(store.state.rounds[0].thinking.isStreaming).toBe(false); // 兜底轮次非流式
      expect(store.state.rounds[0].toolCalls).toHaveLength(1);
    });

    it('重复同名 running 工具（input 为空）应更新 input 而非新增', () => {
      const store = useAnalysisStore();
      store.startAnalysis();
      store.appendThinkingToCurrentRound('思考');
      store.addToolCallToCurrentRound('test_tool'); // 无 input

      store.addToolCallToCurrentRound('test_tool', '{"param": 1}');

      expect(store.state.rounds[0].toolCalls).toHaveLength(1);
      expect(store.state.rounds[0].toolCalls[0].input).toBe('{"param": 1}');
    });
  });

  describe('轮次管理 - updateToolCallInCurrentRound', () => {
    it('应更新当前轮次最后一个同名 running 工具的状态', () => {
      const store = useAnalysisStore();
      store.startAnalysis();
      store.appendThinkingToCurrentRound('思考');
      // 模拟真实 SSE 流程：thinking_end 后再调用工具
      store.finalizeCurrentRoundThinking();
      store.addToolCallToCurrentRound('retrieve_schema', '{}');

      store.updateToolCallInCurrentRound('retrieve_schema', '表结构结果', true);

      const tool = store.state.rounds[0].toolCalls[0];
      expect(tool.status).toBe('success');
      expect(tool.result).toBe('表结构结果');
      expect(tool.endTime).toBeDefined();
      // 思考已结束且无 running 工具，轮次应处于非激活状态
      expect(store.state.rounds[0].isActive).toBe(false);
    });

    it('失败结果应将 status 设为 error', () => {
      const store = useAnalysisStore();
      store.startAnalysis();
      store.appendThinkingToCurrentRound('思考');
      store.addToolCallToCurrentRound('test_tool');

      store.updateToolCallInCurrentRound('test_tool', '执行失败', false);

      expect(store.state.rounds[0].toolCalls[0].status).toBe('error');
    });

    it('无当前轮次时应安全返回不报错', () => {
      const store = useAnalysisStore();
      store.startAnalysis();

      expect(() => {
        store.updateToolCallInCurrentRound('test_tool', '结果');
      }).not.toThrow();
    });
  });

  describe('轮次管理 - forceCompleteCurrentRound', () => {
    it('应标记 endTime 并清空 currentRoundId', () => {
      const store = useAnalysisStore();
      store.startAnalysis();
      store.appendThinkingToCurrentRound('思考');
      store.addToolCallToCurrentRound('running_tool');

      store.forceCompleteCurrentRound();

      expect(store.state.rounds[0].endTime).toBeDefined();
      expect(store.state.rounds[0].isActive).toBe(false);
      expect(store.state.currentRoundId).toBeNull();
    });

    it('不修改内部工具状态（保留 running 作为历史记录）', () => {
      const store = useAnalysisStore();
      store.startAnalysis();
      store.appendThinkingToCurrentRound('思考');
      store.addToolCallToCurrentRound('running_tool');

      store.forceCompleteCurrentRound();

      expect(store.state.rounds[0].toolCalls[0].status).toBe('running');
    });
  });

  describe('跨轮次场景', () => {
    it('完整 ReAct 流程：思考→工具→思考→工具→完成', () => {
      const store = useAnalysisStore();
      store.startAnalysis();

      // 第一轮
      store.appendThinkingToCurrentRound('需要查询数据');
      store.finalizeCurrentRoundThinking();
      store.addToolCallToCurrentRound('retrieve_schema', '{}');
      store.updateToolCallInCurrentRound('retrieve_schema', 'schema', true);

      // 第二轮
      store.appendThinkingToCurrentRound('生成 SQL');
      store.finalizeCurrentRoundThinking();
      store.addToolCallToCurrentRound('execute_sql', 'SELECT 1');
      store.updateToolCallInCurrentRound('execute_sql', 'data', true);

      store.completeAnalysis();

      expect(store.state.rounds).toHaveLength(2);
      expect(store.state.rounds[0].thinking.content).toBe('需要查询数据');
      expect(store.state.rounds[0].toolCalls[0].toolName).toBe('retrieve_schema');
      expect(store.state.rounds[1].thinking.content).toBe('生成 SQL');
      expect(store.state.rounds[1].toolCalls[0].toolName).toBe('execute_sql');

      // 所有轮次完成后应折叠
      for (const round of store.state.rounds) {
        expect(round.isActive).toBe(false);
        expect(round.isCollapsed).toBe(true);
      }
    });

    it('下一轮 thinking 到达但当前轮次有 active 工具时应强制结束旧轮次', () => {
      const store = useAnalysisStore();
      store.startAnalysis();

      // 第一轮：思考后工具未完成
      store.appendThinkingToCurrentRound('第一轮思考');
      store.finalizeCurrentRoundThinking();
      store.addToolCallToCurrentRound('long_running_tool');

      // 第二轮 thinking 到达
      store.appendThinkingToCurrentRound('第二轮思考');

      expect(store.state.rounds).toHaveLength(2);
      expect(store.state.rounds[0].isActive).toBe(false); // 被强制结束
      expect(store.state.rounds[0].endTime).toBeDefined();
      expect(store.state.rounds[0].toolCalls[0].status).toBe('running'); // 保留 running 状态
      expect(store.state.rounds[1].thinking.content).toBe('第二轮思考');
    });
  });

  describe('createSnapshot', () => {
    it('应导出 rounds 的深拷贝', () => {
      const store = useAnalysisStore();
      store.startAnalysis();
      store.appendThinkingToCurrentRound('思考');
      store.addToolCallToCurrentRound('tool');

      const snapshot = store.createSnapshot();

      expect(snapshot.rounds).toHaveLength(1);
      expect(snapshot.rounds[0].thinking.content).toBe('思考');
      // 修改 snapshot 不应影响原状态
      snapshot.rounds[0].thinking.content = '修改后';
      expect(store.state.rounds[0].thinking.content).toBe('思考');
    });

    it('应包含其他分析字段', () => {
      const store = useAnalysisStore();
      store.startAnalysis();
      store.setSQL('SELECT 1');
      store.setAnalysisReport('报告内容', true);

      const snapshot = store.createSnapshot();

      expect(snapshot.currentSQL).toBe('SELECT 1');
      expect(snapshot.analysisReport).toBe('报告内容');
    });
  });

  describe('setAnalysisReport', () => {
    it('isComplete=true 应覆盖报告', () => {
      const store = useAnalysisStore();
      store.setAnalysisReport('增量1', false);
      store.setAnalysisReport('完整报告', true);

      expect(store.state.analysisReport).toBe('完整报告');
    });

    it('isComplete=false 应追加报告', () => {
      const store = useAnalysisStore();
      store.setAnalysisReport('第一段', false);
      store.setAnalysisReport('第二段', false);

      expect(store.state.analysisReport).toBe('第一段第二段');
    });
  });
});
