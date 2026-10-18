// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/shared/types/api';
import { AUTH_STORAGE_KEYS, get, getAuthToken, request, requestEnvelope, setAuthSession, buildQuery, TIMEOUT_ERROR_TYPE } from './http';

/** 构造 fetch 响应 mock */
function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}

describe('http 客户端', () => {
  beforeEach(() => {
    localStorage.clear();
    vi.stubGlobal('fetch', vi.fn());
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('should 解包 ApiResponse.data_when 成功 200', async () => {
    vi.mocked(fetch).mockResolvedValue(jsonResponse(200, { success: true, code: '200', message: 'ok', data: { id: 1 } }));
    const data = await request<{ id: number }>('/api/v1/test');
    expect(data).toEqual({ id: 1 });
  });

  it('should 返回完整包装_when_requestEnvelope 且识别 202 受理', async () => {
    vi.mocked(fetch).mockResolvedValue(
      jsonResponse(200, { success: true, code: '202', message: '删除中，清退任务已受理', data: null }),
    );
    const wrapper = await requestEnvelope<unknown>('/api/v1/knowledge-base/knowledge-bases/1');
    expect(wrapper.code).toBe('202');
  });

  it('should 抛 ApiError 并携带信封消息_when 非 2xx', async () => {
    // Response 体一次性：每次调用生成新实例
    vi.mocked(fetch).mockImplementation(async () =>
      jsonResponse(409, { error: { type: 'conflict_error', message: '名称已被使用' }, request_id: 'r1', type: 'error' }),
    );
    await expect(request('/api/v1/test')).rejects.toThrow(ApiError);
    vi.mocked(fetch).mockImplementation(async () =>
      jsonResponse(409, { error: { type: 'conflict_error', message: '名称已被使用' }, request_id: 'r1', type: 'error' }),
    );
    await expect(request('/api/v1/test')).rejects.toMatchObject({ status: 409, type: 'conflict_error', message: '名称已被使用' });
  });

  it('should 注入 Bearer 头_when 已登录', async () => {
    setAuthSession('tok-1', JSON.stringify({ userId: 1, email: 'a@b.c' }));
    vi.mocked(fetch).mockResolvedValue(jsonResponse(200, { success: true, code: '200', message: '', data: null }));
    await request('/api/v1/test');
    const init = vi.mocked(fetch).mock.calls[0][1] as RequestInit;
    const headers = init.headers as Headers;
    expect(headers.get('Authorization')).toBe('Bearer tok-1');
  });

  it('should 清理会话并抛错_when 非认证端点收到 401', async () => {
    setAuthSession('tok-1', '{}');
    vi.mocked(fetch).mockResolvedValue(
      jsonResponse(401, { error: { type: 'authentication_error', message: 'token 已过期' }, request_id: '', type: 'error' }),
    );
    await expect(request('/api/v1/cloud/agents')).rejects.toMatchObject({ status: 401 });
    expect(getAuthToken()).toBeNull();
  });

  it('should 不清理会话_when 认证端点自身 401（密码错误）', async () => {
    setAuthSession('tok-1', '{}');
    vi.mocked(fetch).mockResolvedValue(
      jsonResponse(401, { error: { type: 'authentication_error', message: '邮箱或密码错误' }, request_id: '', type: 'error' }),
    );
    await expect(request('/api/auth/login')).rejects.toMatchObject({ status: 401 });
    expect(localStorage.getItem(AUTH_STORAGE_KEYS.token)).toBe('tok-1');
  });

  it('should 安全返回 undefined_when 204 空体', async () => {
    vi.mocked(fetch).mockResolvedValue(new Response(null, { status: 204 }));
    await expect(request<void>('/api/v1/cloud/skills/1')).resolves.toBeUndefined();
  });

  it('should 跳过空值_when buildQuery', () => {
    const q = buildQuery({ keyword: 'a', page: 1, size: undefined, empty: '' });
    expect(q).toBe('?keyword=a&page=1');
  });

  it('should 抛超时 ApiError且 status=0_when 请求超过超时时间仍未返回', async () => {
    // given：fetch 永不 resolve，仅在收到 abort 信号时 reject（模拟后端挂死、连接不返回）
    vi.useFakeTimers();
    try {
      vi.mocked(fetch).mockImplementation(
        (_input, init) =>
          new Promise<Response>((_resolve, reject) => {
            const signal = (init as RequestInit | undefined)?.signal;
            signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')));
          }),
      );

      // when：以 30s 超时发起请求，并把 rejection 立即捕获，避免 unhandled rejection
      const settled = get('/api/v1/cloud/agents', undefined, 30_000).catch((e: unknown) => e);
      await vi.advanceTimersByTimeAsync(30_000);

      // then：超时被归一为 status=0 的超时 ApiError（而非静默挂死）
      const err = await settled;
      expect(err).toBeInstanceOf(ApiError);
      expect(err).toMatchObject({ status: 0, type: TIMEOUT_ERROR_TYPE });
      expect((err as ApiError).message).toContain('请求超时');
    } finally {
      vi.useRealTimers();
    }
  });

  it('should 原样抛出且不标记为超时_when fetch 直接以网络错误 reject', async () => {
    // given：网络层直接失败（如 DNS/连接被拒绝）
    const networkError = new TypeError('Failed to fetch');
    vi.mocked(fetch).mockRejectedValue(networkError);

    // when / then：原始错误原样抛出，不被吞掉也不被误判为超时
    await expect(get('/api/v1/cloud/agents')).rejects.toBe(networkError);
  });
});
