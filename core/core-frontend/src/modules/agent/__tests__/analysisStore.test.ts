import { describe, it, expect, beforeEach } from 'vitest';
import { setActivePinia, createPinia } from 'pinia';
import { useAnalysisStore } from '../stores/analysis';

/**
 * AnalysisStore - 统一内容流模型测试
 * <p>验证基于有序 contentItems 的内容流操作：思考/工具调用/报告按接收时序追加、
 * 进行中状态收敛、快照导出与导入（contentSeq 续接）。</p>
 */
describe('AnalysisStore - 统一内容流模型', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
  });

  describe('基础生命周期', () => {
    it('startAnalysis 应设置 isAnalyzing=true 并记录开始时间', () => {
      const store = useAnalysisStore();
      store.startAnalysis();

      expect(store.state.isAnalyzing).toBe(true);
      expect(store.state.analysisStartTime).not.toBeNull();
      expect(store.state.contentItems).toEqual([]);
      expect(store.state.contentSeq).toBe(0);
    });

    it('completeAnalysis 应设置 isAnalyzing=false、记录结束时间并收敛进行中项', () => {
      const store = useAnalysisStore();
      store.startAnalysis();
      store.appendThinkingDelta('思考内容');
      store.appendReportDelta('报告内容');

      store.completeAnalysis();

      expect(store.state.isAnalyzing).toBe(false);
      expect(store.state.analysisEndTime).not.toBeNull();
      for (const item of store.state.contentItems) {
        expect(item.status).toBe('completed');
        expect(item.endTime).toBeDefined();
      }
    });

    it('reset 应清空所有状态', () => {
      const store = useAnalysisStore();
      store.startAnalysis();
      store.appendThinkingDelta('思考');

      store.reset();

      expect(store.state.contentItems).toEqual([]);
      expect(store.state.contentSeq).toBe(0);
      expect(store.state.isAnalyzing).toBe(false);
      expect(store.state.analysisReport).toBeNull();
    });
  });

  describe('内容项追加 - pushContentItem/findInProgressItem', () => {
    it('pushContentItem 应创建内容项并自增 seq', () => {
      const store = useAnalysisStore();
      store.startAnalysis();

      const item = store.pushContentItem('thinking', 'in_progress', { content: '' });
      const item2 = store.pushContentItem('tool_call', 'in_progress', { toolName: 'test_tool' });

      expect(store.state.contentItems).toHaveLength(2);
      expect(item.seq).toBe(0);
      expect(item2.seq).toBe(1);
      expect(store.state.contentSeq).toBe(2);
    });

    it('findInProgressItem 应返回最后一个进行中指定类型项', () => {
      const store = useAnalysisStore();
      store.startAnalysis();
      store.appendThinkingDelta('第一段');
      store.completeThinking();
      store.appendThinkingDelta('第二段');

      const item = store.findInProgressItem('thinking');

      expect(item).toBeDefined();
      expect(item!.content).toBe('第二段');
    });

    it('无进行中指定类型项时 findInProgressItem 返回 undefined', () => {
      const store = useAnalysisStore();
      store.startAnalysis();
      store.appendThinkingDelta('第一段');
      store.completeThinking();

      expect(store.findInProgressItem('thinking')).toBeUndefined();
    });
  });

  describe('思考内容 - appendThinkingDelta/completeThinking', () => {
    it('无进行中思考项时自动创建并追加', () => {
      const store = useAnalysisStore();
      store.startAnalysis();

      store.appendThinkingDelta('第一次思考');

      expect(store.state.contentItems).toHaveLength(1);
      expect(store.state.contentItems[0].type).toBe('thinking');
      expect(store.state.contentItems[0].status).toBe('in_progress');
      expect(store.state.contentItems[0].content).toBe('第一次思考');
    });

    it('有进行中思考项时原地追加', () => {
      const store = useAnalysisStore();
      store.startAnalysis();
      store.appendThinkingDelta('第一段');

      store.appendThinkingDelta('第二段');

      expect(store.state.contentItems).toHaveLength(1);
      expect(store.state.contentItems[0].content).toBe('第一段第二段');
    });

    it('空 delta 应被忽略', () => {
      const store = useAnalysisStore();
      store.startAnalysis();

      store.appendThinkingDelta('');

      expect(store.state.contentItems).toHaveLength(0);
    });

    it('completeThinking 应收敛进行中思考项为 completed 并记录 endTime', () => {
      const store = useAnalysisStore();
      store.startAnalysis();
      store.appendThinkingDelta('思考');

      store.completeThinking();

      const item = store.state.contentItems[0];
      expect(item.status).toBe('completed');
      expect(item.endTime).toBeDefined();
    });
  });

  describe('工具调用与结果 - addToolCallItem/appendToolInput/appendToolResult/completeToolCall/completeToolResult', () => {
    it('addToolCallItem 应创建进行中工具调用项', () => {
      const store = useAnalysisStore();
      store.startAnalysis();

      store.addToolCallItem('retrieve_schema', '{"datasource": 1}');

      const item = store.state.contentItems[0];
      expect(item.type).toBe('tool_call');
      expect(item.toolName).toBe('retrieve_schema');
      expect(item.input).toBe('{"datasource": 1}');
      expect(item.status).toBe('in_progress');
    });

    it('appendToolInput 应实时追加到进行中工具项', () => {
      const store = useAnalysisStore();
      store.startAnalysis();
      store.addToolCallItem('generate_sql');

      store.appendToolInput('{"sql":"SEL');
      store.appendToolInput('ECT 1"}');

      expect(store.state.contentItems[0].input).toBe('{"sql":"SELECT 1"}');
    });

    it('appendToolResult 应惰性创建独立结果项并实时追加结果（与调用项拆分）', () => {
      const store = useAnalysisStore();
      store.startAnalysis();
      store.addToolCallItem('execute_sql', undefined, 'tc-1');

      store.appendToolResult('{"data":[', 'tc-1');
      store.appendToolResult('1]}', 'tc-1');

      // 工具调用项与工具结果项为两个独立内容项，结果仅写入结果项
      expect(store.state.contentItems).toHaveLength(2);
      expect(store.state.contentItems[0].type).toBe('tool_call');
      expect(store.state.contentItems[0].result).toBeUndefined();
      const resultItem = store.state.contentItems[1];
      expect(resultItem.type).toBe('tool_result');
      expect(resultItem.toolName).toBe('execute_sql'); // 工具名继承自同 toolCallId 调用项
      expect(resultItem.result).toBe('{"data":[1]}');
    });

    it('completeToolCall 成功时收敛为 completed（不写入结果）', () => {
      const store = useAnalysisStore();
      store.startAnalysis();
      store.addToolCallItem('execute_sql');

      store.completeToolCall(true);

      const item = store.state.contentItems[0];
      expect(item.status).toBe('completed');
      expect(item.result).toBeUndefined();
      expect(item.endTime).toBeDefined();
    });

    it('completeToolCall 失败时收敛为 failed', () => {
      const store = useAnalysisStore();
      store.startAnalysis();
      store.addToolCallItem('execute_sql');

      store.completeToolCall(false);

      expect(store.state.contentItems[0].status).toBe('failed');
    });

    it('completeToolResult 应收敛进行中结果项并写入完整结果', () => {
      const store = useAnalysisStore();
      store.startAnalysis();
      store.addToolCallItem('execute_sql', undefined, 'tc-1');
      store.appendToolResult('部分', 'tc-1');

      store.completeToolResult('完整结果', true);

      const item = store.state.contentItems[1];
      expect(item.type).toBe('tool_result');
      expect(item.status).toBe('completed');
      expect(item.result).toBe('完整结果');
      expect(item.endTime).toBeDefined();
    });

    it('completeToolResult 失败时收敛为 failed 并保留实时累积内容', () => {
      const store = useAnalysisStore();
      store.startAnalysis();
      store.addToolCallItem('execute_sql', undefined, 'tc-1');
      store.appendToolResult('结果', 'tc-1');

      store.completeToolResult(undefined, false);

      const item = store.state.contentItems[1];
      expect(item.type).toBe('tool_result');
      expect(item.status).toBe('failed');
      expect(item.result).toBe('结果');
    });
  });

  describe('报告内容 - appendReportDelta/completeReport', () => {
    it('appendReportDelta 应创建报告项并同步 analysisReport', () => {
      const store = useAnalysisStore();
      store.startAnalysis();

      store.appendReportDelta('第一段');

      expect(store.state.contentItems).toHaveLength(1);
      expect(store.state.contentItems[0].type).toBe('report');
      expect(store.state.contentItems[0].content).toBe('第一段');
      expect(store.state.analysisReport).toBe('第一段');
    });

    it('appendReportDelta 连续追加应累积内容', () => {
      const store = useAnalysisStore();
      store.startAnalysis();
      store.appendReportDelta('第一段');

      store.appendReportDelta('第二段');

      expect(store.state.contentItems[0].content).toBe('第一段第二段');
      expect(store.state.analysisReport).toBe('第一段第二段');
    });

    it('completeReport 应使用权威最终文本覆盖流式内容', () => {
      const store = useAnalysisStore();
      store.startAnalysis();
      store.appendReportDelta('流式内容');

      store.completeReport('权威全文');

      const item = store.state.contentItems[0];
      expect(item.status).toBe('completed');
      expect(item.content).toBe('权威全文');
      expect(store.state.analysisReport).toBe('权威全文');
    });

    it('无进行中报告项但携带最终文本时应兜底创建完成态报告项', () => {
      const store = useAnalysisStore();
      store.startAnalysis();

      store.completeReport('兜底全文');

      expect(store.state.contentItems).toHaveLength(1);
      expect(store.state.contentItems[0].status).toBe('completed');
      expect(store.state.contentItems[0].content).toBe('兜底全文');
    });
  });

  describe('完整事件流场景', () => {
    it('思考→工具调用→工具结果→思考→报告 按事件顺序生成有序内容流', () => {
      const store = useAnalysisStore();
      store.startAnalysis();

      store.appendThinkingDelta('需要查询数据');
      store.completeThinking();
      store.addToolCallItem('retrieve_schema', '{}', 'tc-1');
      store.appendToolResult('schema', 'tc-1');
      store.completeToolResult('schema', true);
      store.completeToolCall(true);
      store.appendThinkingDelta('生成 SQL');
      store.completeThinking();
      store.appendReportDelta('分析结果：');
      store.appendReportDelta('完成');
      store.completeReport('分析结果：完成');

      expect(store.state.contentItems.map(i => i.type)).toEqual([
        'thinking', 'tool_call', 'tool_result', 'thinking', 'report',
      ]);
      // 每个内容项的 seq 严格递增，保证渲染顺序
      for (let i = 0; i < store.state.contentItems.length; i++) {
        expect(store.state.contentItems[i].seq).toBe(i);
      }
    });
  });

  describe('createSnapshot/importSnapshot', () => {
    it('createSnapshot 应导出 contentItems 深拷贝', () => {
      const store = useAnalysisStore();
      store.startAnalysis();
      store.appendThinkingDelta('思考');
      store.addToolCallItem('tool');

      const snapshot = store.createSnapshot();

      expect(snapshot.contentItems).toHaveLength(2);
      snapshot.contentItems[0].content = '修改后';
      expect(store.state.contentItems[0].content).toBe('思考');
    });

    it('importSnapshot 应从最大 seq 续接 contentSeq', () => {
      const store = useAnalysisStore();
      store.startAnalysis();
      store.appendThinkingDelta('思考');
      const snapshot = store.createSnapshot();
      expect(snapshot.contentItems[0].seq).toBe(0);

      // 模拟从快照导入（contentSeq 应为 1，后续新项 seq 从 1 开始）
      store.importSnapshot(snapshot);
      store.appendReportDelta('新报告');

      expect(store.state.contentItems).toHaveLength(2);
      expect(store.state.contentItems[1].seq).toBe(1);
    });
  });
});
