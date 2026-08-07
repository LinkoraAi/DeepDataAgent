import { describe, it, expect, beforeEach, vi } from 'vitest';
import { useSSE } from '../composables/useSSE';
import { analyzeStream } from '@/shared/api/analysisApi';

/**
 * 模拟分析 API，捕获 analyzeStream 的调用参数
 */
vi.mock('@/shared/api/analysisApi', () => ({
  analyzeStream: vi.fn(),
  stopAnalysis: vi.fn(),
}));

/**
 * 模拟分析 store
 */
vi.mock('../stores/analysis', () => ({
  useAnalysisStore: () => ({
    startAnalysis: vi.fn(),
    completeAnalysis: vi.fn(),
    setError: vi.fn(),
    createSnapshot: vi.fn(),
  }),
}));

/**
 * 模拟会话 store
 */
vi.mock('../stores/session', () => ({
  useSessionStore: () => ({
    currentSessionId: 'session-1',
    markSessionRunning: vi.fn(),
  }),
}));

/**
 * 模拟全局 SSE 连接管理器
 */
vi.mock('./useSSEConnection', () => ({
  useSSEConnection: () => ({
    registerCallbacks: vi.fn(() => () => {}),
    getClientId: vi.fn(() => 'client-1'),
  }),
}));

/**
 * 模拟会话状态管理器
 */
vi.mock('./useSessionStateManager', () => ({
  useSessionStateManager: () => ({
    updateState: vi.fn(),
  }),
}));

/**
 * useSSE resumeRunningSession 测试
 * <p>验证刷新恢复续流时，resumeRunningSession 通过分析接口（携带 resumeOnly: true）
 * 重新绑定 clientId，且不依赖 resume 专用端点。</p>
 */
describe('useSSE resumeRunningSession', () => {
  const sse = useSSE();

  beforeEach(() => {
    (analyzeStream as ReturnType<typeof vi.fn>).mockClear();
  });

  it('应调用分析接口并携带 resumeOnly: true', async () => {
    // given：恢复请求包含会话 ID 与新 clientId
    const request = { sessionId: 'session-1', clientId: 'client-new' };
    (analyzeStream as ReturnType<typeof vi.fn>).mockResolvedValue({ sessionId: 'session-1', message: '' });

    // when
    await sse.resumeRunningSession(request);

    // then：调用 /analyze 且携带 resumeOnly，不再调用独立 resume 端点
    expect(analyzeStream).toHaveBeenCalledWith({
      sessionId: 'session-1',
      clientId: 'client-new',
      resumeOnly: true,
    });
  });

  it('会话不在运行中（后端 404）时仅警告不抛异常', async () => {
    // given：后端返回 404 语义的错误
    const request = { sessionId: 'session-1', clientId: 'client-new' };
    (analyzeStream as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('Request failed with status code 404'));

    // when & then：resumeRunningSession 不应向上抛异常
    await expect(sse.resumeRunningSession(request)).resolves.toBeUndefined();
  });
});
