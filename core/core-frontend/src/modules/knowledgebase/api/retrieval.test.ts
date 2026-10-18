// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { TIMEOUT_ERROR_TYPE } from '@/shared/api/http';
import { ApiError } from '@/shared/types/api';
import { retrieveAnswer } from './retrieval';

/**
 * 作答端点超时口径。
 * <p>后端答案端点是 Stage 0~5 的整条同步链路，常规返回耗时 30~45s，
 * 故前端为其单独放宽到 600s；本测试钉住该取值，防止被改回常规默认 30s
 * （回退后行为表现为「问答失败：请求超时」）。</p>
 */
describe('retrieveAnswer 超时口径', () => {
  beforeEach(() => {
    localStorage.clear();
    vi.stubGlobal('fetch', vi.fn());
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('should 按600s超时_when 作答接口长时间未返回', async () => {
    // given：fetch 永不 resolve，仅在收到 abort 信号时 reject（模拟后端作答耗时挂住连接）
    vi.useFakeTimers();
    try {
      vi.mocked(fetch).mockImplementation(
        (_input, init) =>
          new Promise<Response>((_resolve, reject) => {
            const signal = (init as RequestInit | undefined)?.signal;
            signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')));
          }),
      );

      // when：发起作答请求；先推进到常规默认超时点 30s，再推进到 600s
      const settled = retrieveAnswer({ kbId: 1, query: '营收对比' }).catch((e: unknown) => e);
      await vi.advanceTimersByTimeAsync(30_000);
      await vi.advanceTimersByTimeAsync(570_000);

      // then：超时按 600s 口径归一（若沿用常规默认 30s，提示文案将是 30s 而非 600s）
      const err = (await settled) as ApiError;
      expect(err).toBeInstanceOf(ApiError);
      expect(err).toMatchObject({ status: 0, type: TIMEOUT_ERROR_TYPE });
      expect(err.message).toContain('600s');
    } finally {
      vi.useRealTimers();
    }
  });
});
