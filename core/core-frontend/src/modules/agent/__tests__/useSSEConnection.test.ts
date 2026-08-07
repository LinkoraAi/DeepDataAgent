import { describe, it, expect, beforeEach, vi } from 'vitest';
import { fetchEventSource } from '@microsoft/fetch-event-source';
import { useSSEConnection } from '../composables/useSSEConnection';
import type { AgentEvent } from '../types';

/**
 * 模拟 fetchEventSource，捕获 onmessage 回调并自动返回 CONNECTED 事件，
 * 使 connect() 的 Promise 得以 resolve，从而在当前测试中驱动 onmessage 分发。
 */
vi.mock('@microsoft/fetch-event-source', () => {
  const fetchEventSourceMock = vi.fn((_url: string, options: any) => {
    (fetchEventSourceMock as any).__options = options;
    // 模拟服务端首帧 CONNECTED，使 connect() 的 Promise resolve（拿到 clientId）
    if (options.onmessage) {
      options.onmessage({ event: 'CONNECTED', data: JSON.stringify({ clientId: 'client-1' }) });
    }
    return Promise.resolve();
  });
  return { fetchEventSource: fetchEventSourceMock };
});

/**
 * SSE 连接管理器 STATE_REPLAY 事件处理测试
 * <p>验证刷新恢复（resume）时，后端一次性推送的 STATE_REPLAY 事件能被正确识别，
 * 并按事件列表顺序逐个分发到对应会话的 onEvent 回调，重建断线期间的分析状态。</p>
 */
describe('SSEConnectionManager STATE_REPLAY', () => {
  const sse = useSSEConnection();

  beforeEach(async () => {
    // 重置单例状态并建立连接，确保每次测试拿到干净的连接与 onmessage 回调
    sse.disconnect();
    await sse.connect();
  });

  it('应将 STATE_REPLAY 事件携带的事件列表逐个分发到会话回调', () => {
    // given：为会话注册回调，并取得被捕获的 onmessage 处理器
    const received: Array<{ sessionId: string; event: AgentEvent }> = [];
    sse.registerCallbacks('session-1', {
      onEvent: (sessionId, event) => received.push({ sessionId, event }),
    });
    const options = (fetchEventSource as any).__options;
    const events = [
      { type: 'THINKING_BLOCK_START' },
      { type: 'TOOL_CALL_START', toolCallName: 'generate_sql' },
    ] as AgentEvent[];

    // when：模拟服务端推送 STATE_REPLAY
    options.onmessage({
      event: 'STATE_REPLAY',
      data: JSON.stringify({ sessionId: 'session-1', events }),
    });

    // then：两个事件按顺序逐一分发到会话回调
    expect(received).toHaveLength(2);
    expect(received[0].sessionId).toBe('session-1');
    expect(received[0].event.type).toBe('THINKING_BLOCK_START');
    expect(received[1].sessionId).toBe('session-1');
    expect(received[1].event.type).toBe('TOOL_CALL_START');
  });

  it('会话未注册回调时忽略 STATE_REPLAY，不抛异常', () => {
    // given：仅有 onmessage，未注册任何会话回调
    const options = (fetchEventSource as any).__options;

    // when & then：对未注册会话的 STATE_REPLAY 应静默忽略，不抛异常
    expect(() => {
      options.onmessage({
        event: 'STATE_REPLAY',
        data: JSON.stringify({ sessionId: 'unknown-session', events: [] }),
      });
    }).not.toThrow();
  });
});