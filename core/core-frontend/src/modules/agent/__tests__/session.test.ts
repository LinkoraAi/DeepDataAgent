import { describe, it, expect } from 'vitest';
import { buildAnalysisStateFromMessages } from '../stores/session';
import type { Message, AnalysisSnapshot } from '../types';

/**
 * session.ts 历史回放单元测试
 * <p>验证 buildAnalysisStateFromMessages 按消息 id 时序重建统一内容流 contentItems：
 * 按 msg.type 分派（THINKING→思考项、TOOL_CALL→调用项、TOOL_RESULT→独立结果项并提取派生字段、
 * MESSAGE 且非 user→报告项），IN_PROGRESS/FAILED 映射对应状态，无 type 数据跳过。</p>
 */
describe('buildAnalysisStateFromMessages', () => {
  /** 构造消息 */
  function message(overrides: Partial<Message> & { id: number; type: Message['type'] }): Message {
    return {
      sessionId: 'session-1',
      dialogueId: 100,
      role: 'assistant',
      content: '',
      createdAt: new Date(1700000000000 + overrides.id * 1000).toISOString(),
      ...overrides,
    };
  }

  it('按消息 id 时序重建 思考→工具调用→工具结果→报告 内容流', () => {
    // given：乱序输入（id 决定时序），工具调用与结果已拆分为独立消息
    const messages: Message[] = [
      message({ id: 4, type: 'MESSAGE', role: 'assistant', content: '分析完成' }),
      message({ id: 3, type: 'TOOL_RESULT', role: 'tool', toolCalls: 'generate_sql', toolResult: '```sql\nSELECT 1\n```' }),
      message({ id: 2, type: 'TOOL_CALL', role: 'tool', content: '{"sql":"SELECT 1"}', toolCalls: 'generate_sql' }),
      message({ id: 1, type: 'THINKING', content: '根据需求生成 SQL' }),
    ];

    // when
    const snapshot = buildAnalysisStateFromMessages(messages);

    // then：内容流按 id（时序）顺序排列
    expect(snapshot).toBeDefined();
    const items = (snapshot as AnalysisSnapshot).contentItems;
    expect(items.map(i => i.type)).toEqual(['thinking', 'tool_call', 'tool_result', 'report']);
    expect(items.map(i => i.seq)).toEqual([0, 1, 2, 3]);
    expect(items[0].content).toBe('根据需求生成 SQL');
    expect(items[0].status).toBe('completed');
    // 工具调用项仅承载工具名与入参，不再携带结果
    expect(items[1].toolName).toBe('generate_sql');
    expect(items[1].input).toBe('{"sql":"SELECT 1"}');
    expect(items[1].result).toBeUndefined();
    expect(items[1].status).toBe('completed');
    // 工具结果项独立承载结果
    expect(items[2].toolName).toBe('generate_sql');
    expect(items[2].result).toContain('SELECT 1');
    expect(items[2].status).toBe('completed');
    // 派生字段：TOOL_RESULT 触发 applyToolResultToState（generate_sql 提取 SQL）
    expect(snapshot!.currentSQL).toBe('SELECT 1');
    // 报告项内容
    expect(items[3].content).toBe('分析完成');
    expect(snapshot!.analysisReport).toBe('分析完成');
  });

  it('IN_PROGRESS 消息重建为进行中内容项并合并展示部分内容', () => {
    // given：分析中断遗留的 IN_PROGRESS 思考与工具消息
    const messages: Message[] = [
      message({ id: 1, type: 'THINKING', content: '正在思考前半', status: 'IN_PROGRESS' }),
      message({ id: 2, type: 'TOOL_CALL', role: 'tool', content: '{"sql":"SEL', toolCalls: 'generate_sql', status: 'IN_PROGRESS' }),
    ];

    // when
    const snapshot = buildAnalysisStateFromMessages(messages);

    // then：进行中消息以进行中视觉呈现（合并展示其部分内容）
    const items = (snapshot as AnalysisSnapshot).contentItems;
    expect(items).toHaveLength(2);
    expect(items[0].type).toBe('thinking');
    expect(items[0].status).toBe('in_progress');
    expect(items[0].content).toBe('正在思考前半');
    expect(items[0].endTime).toBeUndefined();
    expect(items[1].type).toBe('tool_call');
    expect(items[1].status).toBe('in_progress');
    expect(items[1].input).toBe('{"sql":"SEL');
    expect(items[1].result).toBeUndefined();
    expect(items[1].endTime).toBeUndefined();
  });

  it('FAILED 工具结果消息重建为失败状态结果项', () => {
    // given：工具执行失败的结果消息（独立 TOOL_RESULT）
    const messages: Message[] = [
      message({ id: 1, type: 'TOOL_RESULT', role: 'tool', toolCalls: 'execute_sql', toolResult: '数据库连接失败', status: 'FAILED' }),
    ];

    // when
    const snapshot = buildAnalysisStateFromMessages(messages);

    // then
    const item = (snapshot as AnalysisSnapshot).contentItems[0];
    expect(item.type).toBe('tool_result');
    expect(item.status).toBe('failed');
    expect(item.toolName).toBe('execute_sql');
    expect(item.result).toBe('数据库连接失败');
  });

  it('无 toolCalls 的工具消息不生成内容项（跳过无效数据）', () => {
    // given：仅有 thinking 与无工具名的 tool 消息
    const messages: Message[] = [
      message({ id: 1, type: 'THINKING', content: '思考' }),
      message({ id: 2, type: 'TOOL_CALL', role: 'tool', content: '无工具名' }),
    ];

    // when
    const snapshot = buildAnalysisStateFromMessages(messages);

    // then：仅思考项生成
    const items = (snapshot as AnalysisSnapshot).contentItems;
    expect(items).toHaveLength(1);
    expect(items[0].type).toBe('thinking');
  });

  it('用户消息（role=user，type=MESSAGE）不生成报告项', () => {
    // given：同一轮次包含用户问题与助手最终报告（type 均为 MESSAGE）
    const messages: Message[] = [
      message({ id: 1, type: 'MESSAGE', role: 'user', content: '分析一下销售数据' }),
      message({ id: 2, type: 'MESSAGE', role: 'assistant', content: '分析完成' }),
    ];

    // when
    const snapshot = buildAnalysisStateFromMessages(messages);

    // then：仅助手 MESSAGE 生成报告项
    const items = (snapshot as AnalysisSnapshot).contentItems;
    expect(items).toHaveLength(1);
    expect(items[0].type).toBe('report');
    expect(items[0].content).toBe('分析完成');
  });

  it('无 type 的消息（老数据）跳过，不参与回放内容流', () => {
    // given：一条带 type 的思考消息与一条无 type 的消息
    const legacy = message({ id: 2, type: 'THINKING', content: '残留' });
    legacy.type = undefined;
    const messages: Message[] = [
      message({ id: 1, type: 'THINKING', content: '思考' }),
      legacy,
    ];

    // when
    const snapshot = buildAnalysisStateFromMessages(messages);

    // then：仅带 type 的消息生成内容项
    const items = (snapshot as AnalysisSnapshot).contentItems;
    expect(items).toHaveLength(1);
    expect(items[0].content).toBe('思考');
  });

  it('TOOL_CALL / TOOL_RESULT 消息重建内容项时透传 toolCallId（回放配对依据）', () => {
    // given：同一次调用的调用与结果消息携带相同 toolCallId（并行场景结果乱序也可靠 ID 配对）
    const messages: Message[] = [
      message({ id: 2, type: 'TOOL_RESULT', role: 'tool', toolCalls: 'execute_sql', toolResult: '{"data":[]}', toolCallId: 'call-1' }),
      message({ id: 1, type: 'TOOL_CALL', role: 'tool', content: '{"sql":"SELECT 1"}', toolCalls: 'execute_sql', toolCallId: 'call-1' }),
    ];

    // when
    const snapshot = buildAnalysisStateFromMessages(messages);

    // then：调用项与结果项均携带相同 toolCallId，供展示层合并配对
    const items = (snapshot as AnalysisSnapshot).contentItems;
    expect(items).toHaveLength(2);
    expect(items[0].type).toBe('tool_call');
    expect(items[0].toolCallId).toBe('call-1');
    expect(items[1].type).toBe('tool_result');
    expect(items[1].toolCallId).toBe('call-1');
  });

  it('空消息列表返回 undefined', () => {
    // when
    const snapshot = buildAnalysisStateFromMessages([]);

    // then
    expect(snapshot).toBeUndefined();
  });
});