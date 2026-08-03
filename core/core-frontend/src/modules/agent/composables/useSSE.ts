/**
 * SSE composable for data analysis streaming
 * <p>通过 SessionStateManager 路由事件，支持多会话独立状态管理。
 * 每个会话拥有独立的回调注册和工具结果缓冲区，确保会话间数据严格隔离。</p>
 */
import { analyzeStream, type DataAnalysisRequest } from '@/shared/api/analysisApi';
import type { AgentEvent } from '../types';
import { useAnalysisStore } from '../stores/analysis';
import { useSessionStore } from '../stores/session';
import { useSSEConnection } from './useSSEConnection';
import { useSessionStateManager } from './useSessionStateManager';

/**
 * SSE composable for data analysis streaming
 * <p>核心设计：每个会话（sessionId）拥有独立的回调注册和工具结果缓冲区，
 * 支持多会话并行分析而互不干扰。</p>
 */
export function useSSE() {
  const analysisStore = useAnalysisStore();
  const sessionStore = useSessionStore();
  const { registerCallbacks, getClientId } = useSSEConnection();
  const sessionStateManager = useSessionStateManager();

  // 按会话存储回调：分析完成或出错时通知外部写入消息
  // 使用 Map 支持多个后台会话同时注册各自的回调
  const sessionCallbacks = new Map<string, {
    onComplete: (sessionId: string) => void;
    onError: (sessionId: string, message: string) => void;
  }>();

  /**
   * 按会话存储的工具结果缓冲区
   * <p>每个会话拥有独立的缓冲区，按 toolCallId 累积 TOOL_RESULT_TEXT_DELTA 内容，
   * 防止不同会话的工具结果互相污染。</p>
   */
  const sessionToolResultBuffers = new Map<string, Map<string, string>>();

  /**
   * 按会话存储的取消注册函数
   */
  const sessionUnregisterFns = new Map<string, () => void>();

  /**
   * 设置指定会话的完成/出错回调
   * <p>回调按 sessionId 隔离存储，支持多会话并行注册。</p>
   *
   * @param sessionId 会话 ID
   * @param onComplete 分析完成回调
   * @param onError 出错回调
   */
  function setCallbacks(
    sessionId: string,
    onComplete: (sessionId: string) => void,
    onError: (sessionId: string, message: string) => void
  ) {
    sessionCallbacks.set(sessionId, { onComplete, onError });
  }

  /**
   * 启动分析（通过全局 SSE 连接接收事件，通过 SessionStateManager 路由）
   * <p>每个会话独立注册回调和工具结果缓冲区，不干扰其他会话的分析。</p>
   *
   * @param request 数据分析请求
   */
  async function startAnalysis(request: DataAnalysisRequest) {
    // 初始化该会话的工具结果缓冲区
    sessionToolResultBuffers.set(request.sessionId, new Map());

    // 重置 analysisStore 并设置当前分析的 sessionId（用于实时渲染当前会话的分析状态）
    analysisStore.startAnalysis(request.sessionId);

    // 为该会话注册独立的事件回调
    const unregister = registerCallbacks(request.sessionId, {
      onEvent: (_sessionId: string, event: AgentEvent) => {
        handleAgentEvent(_sessionId, event);
      },
      onError: (sessionId: string, error: Error) => {
        const callbacks = sessionCallbacks.get(sessionId);
        if (callbacks) {
          callbacks.onError(sessionId, error.message);
        }
        cleanupSession(sessionId);
      },
      onComplete: (sessionId: string) => {
        const callbacks = sessionCallbacks.get(sessionId);
        if (callbacks) {
          callbacks.onComplete(sessionId);
        }
        cleanupSession(sessionId);
      }
    });
    sessionUnregisterFns.set(request.sessionId, unregister);

    // 调用后端 API 启动分析
    try {
      await analyzeStream(request);
      console.log('[SSE] Analysis started for sessionId:', request.sessionId);
    } catch (error) {
      console.error('[SSE] Failed to start analysis:', error);
      const callbacks = sessionCallbacks.get(request.sessionId);
      if (callbacks) {
        callbacks.onError(request.sessionId, error instanceof Error ? error.message : '启动分析失败');
      }
      cleanupSession(request.sessionId);
    }
  }

  /**
   * 清理指定会话的回调和缓冲区资源
   *
   * @param sessionId 会话 ID
   */
  function cleanupSession(sessionId: string) {
    const unregister = sessionUnregisterFns.get(sessionId);
    if (unregister) {
      unregister();
      sessionUnregisterFns.delete(sessionId);
    }
    sessionCallbacks.delete(sessionId);
    sessionToolResultBuffers.delete(sessionId);
  }

  /**
   * Handle AgentEvent from AgentScope 2.0
   * <p>通过 SessionStateManager 路由事件，支持多会话独立状态管理。
   * 事件根据 sessionId 路由到对应会话的处理逻辑，不影响其他会话。</p>
   *
   * @param sessionId 事件所属会话 ID
   * @param event AgentEvent 事件
   */
  function handleAgentEvent(sessionId: string, event: AgentEvent) {
    if (!event || !event.type) {
      console.warn('[SSE Handler] Received invalid event:', event);
      return;
    }

    // 获取该会话的工具结果缓冲区
    const toolResultBuffers = sessionToolResultBuffers.get(sessionId);
    if (!toolResultBuffers) {
      console.warn('[SSE Handler] No tool result buffer for sessionId:', sessionId);
      return;
    }

    // 使用 SessionStateManager 路由事件
    sessionStateManager.updateState(
      sessionId,
      event,
      sessionStore.currentSessionId,
      toolResultBuffers,
      (sid: string) => {
        // 分析完成回调
        analysisStore.completeAnalysis();
        const callbacks = sessionCallbacks.get(sid);
        if (callbacks) {
          callbacks.onComplete(sid);
        }
        cleanupSession(sid);
      },
      (sid: string, message: string) => {
        // 错误回调
        analysisStore.setError(message);
        const callbacks = sessionCallbacks.get(sid);
        if (callbacks) {
          callbacks.onError(sid, message);
        }
        cleanupSession(sid);
      }
    );
  }

  /**
   * 停止指定会话的分析
   * <p>清理指定会话的回调资源，不中断全局 SSE 连接。
   * 用户手动停止时触发完成回调，保留已输出的部分内容。</p>
   *
   * @param sessionId 要停止的会话 ID
   * @param preserveContent 是否保留已输出的部分内容（默认 true，用户手动停止时使用）
   */
  function stopAnalysis(sessionId: string, preserveContent: boolean = true) {
    if (preserveContent) {
      analysisStore.completeAnalysis();
      sessionStateManager.saveState(sessionId);
      const callbacks = sessionCallbacks.get(sessionId);
      if (callbacks) {
        callbacks.onComplete(sessionId);
      }
    }
    cleanupSession(sessionId);
  }

  /**
   * 重连后重新关联 clientId
   * <p>SSE 连接重连后 clientId 会变更，此方法重新提交分析请求让后端更新 clientId 映射。
   * 不修改前端状态（不停止分析、不创建新消息）。</p>
   *
   * @param request 原始分析请求（使用新的 clientId）
   */
  async function reconnectClient(request: DataAnalysisRequest) {
    if (!request || !request.sessionId) {
      console.warn('[SSE] Cannot reconnect client: invalid request');
      return;
    }
    console.log('[SSE] Reconnecting client for sessionId:', request.sessionId, 'new clientId:', request.clientId);
    try {
      await analyzeStream(request);
      console.log('[SSE] Client reconnected successfully for sessionId:', request.sessionId);
    } catch (error) {
      console.error('[SSE] Failed to reconnect client for sessionId:', request.sessionId, error);
    }
  }

  return {
    startAnalysis,
    stopAnalysis,
    setCallbacks,
    reconnectClient,
  };
}
