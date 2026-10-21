// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest';

/** SSE 底层打开函数 Mock（本测试只验证答案端点的事件分发契约） */
const openSseStreamMock = vi.fn();

vi.mock('@/shared/api/sse', () => ({
  openSseStream: (...args: unknown[]) => openSseStreamMock(...args),
}));

import { streamAnswer } from './retrieval';
import type { RawSseFrame, SseOptions } from '@/shared/api/sse';

/**
 * 答案端点流式客户端契约。
 * <p>验证三件事：① 以 POST + 请求体发起且显式关闭自动重连（一次性流，重连会重复触发
 * 服务端检索与模型调用）；② 四类事件的字段分派正确；③ 缓存命中（无增量）与
 * 错误终结均能被上层正确识别。</p>
 */
describe('streamAnswer', () => {
  beforeEach(() => {
    openSseStreamMock.mockReset();
    localStorage.clear();
    openSseStreamMock.mockReturnValue({ close: vi.fn() });
  });

  /**
   * 发起请求并返回底层捕获到的选项。
   * @param handlers 回调集合（缺省为空实现）
   * @returns 底层 SseOptions
   */
  function start(handlers: Partial<Parameters<typeof streamAnswer>[1]> = {}): SseOptions {
    streamAnswer({ kbId: 7, query: '营收对比' }, {
      onDelta: handlers.onDelta ?? vi.fn(),
      onCompleted: handlers.onCompleted ?? vi.fn(),
      onError: handlers.onError ?? vi.fn(),
      onStarted: handlers.onStarted,
    });
    return openSseStreamMock.mock.calls[0][1] as SseOptions;
  }

  /**
   * 构造一帧。
   * @param event 事件名
   * @param payload data JSON 对象
   * @returns 原始帧
   */
  function frame(event: string, payload: Record<string, unknown>): RawSseFrame {
    return { event, id: '', data: JSON.stringify(payload) };
  }

  it('should use POST body and disable reconnect_when_streamAnswer_given_anyPayload', () => {
    // given // when
    const options = start();

    // then
    expect(openSseStreamMock.mock.calls[0][0]).toBe('/api/v1/rag/retrievals/answer');
    expect(options.method).toBe('POST');
    expect(options.autoReconnect).toBe(false);
    expect(JSON.parse(options.body ?? '{}')).toEqual({ kbId: 7, query: '营收对比' });
  });

  it('should_dispatchDeltasInOrderAndCompleteWithFullAnswer_when_framesArrive', () => {
    // given
    const deltas: string[] = [];
    const completed = vi.fn();
    const options = start({ onDelta: (delta) => deltas.push(delta), onCompleted: completed });

    // when
    options.onFrame(frame('answer.started', { type: 'answer.started' }));
    options.onFrame(frame('answer.delta', { type: 'answer.delta', delta: '根据' }));
    options.onFrame(frame('answer.delta', { type: 'answer.delta', delta: '规则' }));
    options.onFrame(frame('answer.completed', {
      type: 'answer.completed', answer: '根据规则',
    }));

    // then
    expect(deltas).toEqual(['根据', '规则']);
    expect(completed).toHaveBeenCalledWith('根据规则');
  });

  it('should_completeWithFullAnswerWithoutDelta_when_cacheHit', () => {
    // given
    const onDelta = vi.fn();
    const completed = vi.fn();
    const options = start({ onDelta, onCompleted: completed });

    // when
    options.onFrame(frame('answer.completed', {
      type: 'answer.completed', answer: '缓存答案',
    }));

    // then：缓存命中没有增量，仍能用终结帧全文展示
    expect(onDelta).not.toHaveBeenCalled();
    expect(completed).toHaveBeenCalledWith('缓存答案');
  });

  it('should_surfaceMessage_when_errorEventArrives', () => {
    // given
    const onError = vi.fn();
    const options = start({ onError });

    // when
    options.onFrame(frame('answer.error', {
      type: 'answer.error', code: 'internal_error', message: '模型不可用',
    }));

    // then
    expect(onError).toHaveBeenCalledWith('模型不可用');
  });

  it('should_surfaceConnectionError_when_transportFails', () => {
    // given
    const onError = vi.fn();
    const options = start({ onError });

    // when
    options.onError(new Error('SSE 连接失败(404)'));

    // then
    expect(onError).toHaveBeenCalledWith('SSE 连接失败(404)');
  });

  it('should_ignoreMalformedFrame_when_dataIsNotJson', () => {
    // given
    const onDelta = vi.fn();
    const onCompleted = vi.fn();
    const onError = vi.fn();
    const options = start({ onDelta, onCompleted, onError });

    // when
    options.onFrame({ event: 'answer.delta', id: '', data: '不是 JSON' });

    // then：坏帧静默丢弃，不误报错误
    expect(onDelta).not.toHaveBeenCalled();
    expect(onCompleted).not.toHaveBeenCalled();
    expect(onError).not.toHaveBeenCalled();
  });
});