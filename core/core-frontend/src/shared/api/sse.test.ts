// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest';

/** 底层 SSE 库 Mock（本测试只验证传输参数与重连策略） */
const fetchEventSourceMock = vi.fn();

vi.mock('@microsoft/fetch-event-source', () => ({
  fetchEventSource: (...args: unknown[]) => fetchEventSourceMock(...args),
}));

import { openSseStream } from './sse';

/** 底层初始化参数形状（仅取本测试断言用到的字段） */
interface CapturedInit {
  method: string;
  body?: string;
  headers: Record<string, string>;
  signal: AbortSignal;
  onclose?: () => void;
  onerror?: (error: unknown) => void;
  onopen?: (response: Response) => Promise<void>;
}

/**
 * SSE 客户端传输层单元测试。
 * <p>覆盖新增能力：① 默认仍为 GET（会话事件流行为不变）；② POST 时自动补充 JSON 内容类型；
 * ③ {@code autoReconnect=false} 时连接关闭即 abort、不再自动重连；④ 网络错误在关闭重连时
 * 上报并以抛错终止库的重试。</p>
 */
describe('openSseStream', () => {
  beforeEach(() => {
    fetchEventSourceMock.mockReset();
    fetchEventSourceMock.mockResolvedValue(undefined);
    localStorage.clear();
  });

  /**
   * 打开流并取回底层初始化参数。
   * @param options 额外选项
   * @returns 捕获到的初始化参数
   */
  function open(options: Partial<Parameters<typeof openSseStream>[1]> = {}): CapturedInit {
    openSseStream('/api/v1/example', {
      onFrame: vi.fn(),
      onError: vi.fn(),
      ...options,
    });
    return fetchEventSourceMock.mock.calls[0][1] as CapturedInit;
  }

  it('should_useGetWithoutContentType_when_methodNotSpecified', () => {
    // given // when
    const init = open();

    // then
    expect(init.method).toBe('GET');
    expect(init.headers['Content-Type']).toBeUndefined();
    expect(init.body).toBeUndefined();
  });

  it('should_sendPostBodyWithJsonContentType_when_methodIsPost', () => {
    // given // when
    const init = open({ method: 'POST', body: JSON.stringify({ kbId: 1 }) });

    // then
    expect(init.method).toBe('POST');
    expect(init.body).toBe('{"kbId":1}');
    expect(init.headers['Content-Type']).toBe('application/json');
  });

  it('should_abortOnClose_when_autoReconnectDisabled', () => {
    // given
    const init = open({ autoReconnect: false });

    // when
    init.onclose?.();

    // then：一次性流在服务端正常结束后立即终止连接，阻止库重连
    expect(init.signal.aborted).toBe(true);
  });

  it('should_notAbortOnClose_when_autoReconnectEnabledByDefault', () => {
    // given
    const init = open();

    // when
    init.onclose?.();

    // then：会话事件流保留库的默认重连行为
    expect(init.signal.aborted).toBe(false);
  });

  it('should_reportAndThrow_when_autoReconnectDisabledAndNetworkErrorOccurs', () => {
    // given
    const onError = vi.fn();
    const init = open({ autoReconnect: false, onError });

    // when & then：上报一次并抛错以终止库的自动重试
    expect(() => init.onerror?.(new Error('网络中断'))).toThrow('网络中断');
    expect(onError).toHaveBeenCalledTimes(1);
  });

  it('should_onlyReport_when_autoReconnectEnabledAndNetworkErrorOccurs', () => {
    // given
    const onError = vi.fn();
    const init = open({ onError });

    // when：不抛错，让库按默认退避重连
    expect(() => init.onerror?.(new Error('网络中断'))).not.toThrow();

    // then
    expect(onError).toHaveBeenCalledWith(new Error('网络中断'));
  });
});