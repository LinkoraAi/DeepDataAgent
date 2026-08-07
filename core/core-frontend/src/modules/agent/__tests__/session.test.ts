import { describe, it, expect } from 'vitest';
import { buildAnalysisStateFromMessages } from '../stores/session';
import type { Message, AnalysisSnapshot } from '../types';

/**
 * session.ts 历史回放单元测试
 * <p>验证合并后的「单条工具消息」回放：直接从同一条消息读取工具名（toolCalls）、
 * 入参（content）与结果（toolResult），一次成型，不再依赖跨消息配对。</p>
 */
describe('buildAnalysisStateFromMessages', () => {
  /** 构造一条合并后的工具消息（携带工具名、入参、结果） */
  function mergedToolMessage(id: number, content: string, toolResult: string): Message {
    return {
      id,
      sessionId: 'session-1',
      dialogueId: 100,
      role: 'tool',
      content,
      toolCalls: 'generate_sql',
      toolResult,
      createdAt: new Date(1700000000000 + id * 1000).toISOString(),
    };
  }

  it('从单条工具消息同时重建入参与结果', () => {
    // given：思考 + 一条合并后的工具消息 + 一条助手消息（同轮次）
    const messages: Message[] = [
      {
        id: 1,
        sessionId: 'session-1',
        dialogueId: 100,
        role: 'thinking',
        content: '根据需求生成 SQL',
        createdAt: new Date(1700000001000).toISOString(),
      },
      mergedToolMessage(2, '{"sql":"SELECT 1"}', '{"sql":"SELECT 1","data":[],"isEmptyResult":false}'),
      {
        id: 3,
        sessionId: 'session-1',
        dialogueId: 100,
        role: 'assistant',
        content: '分析完成',
        createdAt: new Date(1700000003000).toISOString(),
      },
    ];

    // when
    const snapshot = buildAnalysisStateFromMessages(messages);

    // then：工具项同时携带入参与结果，状态为 success
    expect(snapshot).toBeDefined();
    const rounds = (snapshot as AnalysisSnapshot).rounds;
    expect(rounds).toHaveLength(1);
    const toolCall = rounds[0].toolCalls[0];
    expect(toolCall.toolName).toBe('generate_sql');
    expect(toolCall.input).toBe('{"sql":"SELECT 1"}');
    expect(toolCall.result).toContain('isEmptyResult');
    expect(toolCall.status).toBe('success');
  });

  it('无结果的工具消息重建为 running 状态', () => {
    // given：思考 + 只有入参、无结果（分析中断）的工具消息
    const messages: Message[] = [
      {
        id: 1,
        sessionId: 'session-1',
        dialogueId: 100,
        role: 'thinking',
        content: '根据需求生成 SQL',
        createdAt: new Date(1700000001000).toISOString(),
      },
      {
        id: 2,
        sessionId: 'session-1',
        dialogueId: 100,
        role: 'tool',
        content: '{"sql":"SELECT 1"}',
        toolCalls: 'generate_sql',
        createdAt: new Date(1700000002000).toISOString(),
      },
    ];

    // when
    const snapshot = buildAnalysisStateFromMessages(messages);

    // then：工具项有入参、无结果，状态为 running
    const toolCall = (snapshot as AnalysisSnapshot).rounds[0].toolCalls[0];
    expect(toolCall.toolName).toBe('generate_sql');
    expect(toolCall.input).toBe('{"sql":"SELECT 1"}');
    expect(toolCall.result).toBeUndefined();
    expect(toolCall.status).toBe('running');
  });

  it('空消息列表返回 undefined', () => {
    // when
    const snapshot = buildAnalysisStateFromMessages([]);

    // then
    expect(snapshot).toBeUndefined();
  });
});