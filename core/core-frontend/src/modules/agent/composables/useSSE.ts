/**
 * SSE composable for data analysis streaming
 * <p>通过 SessionStateManager 路由事件，支持多会话独立状态管理。
 * 每个会话拥有独立的回调注册和工具结果缓冲区，确保会话间数据严格隔离。</p>
 */
import { analyzeStream, stopAnalysis as apiStopAnalysis, type DataAnalysisRequest } from '@/shared/api/analysisApi';
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
   * 按会话存储的工具入参缓冲区
   * <p>每个会话拥有独立的缓冲区，按 toolCallId 累积 TOOL_CALL_DELTA 内容，
   * 防止不同会话的入参互相污染。</p>
   */
  const sessionToolCallInputBuffers = new Map<string, Map<string, string>>();

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
   * 为指定会话建立前端事件链路
   * <p>初始化工具结果/入参缓冲区、设置分析状态、注册该会话的 SSE 事件回调。
   * 由 {@link startAnalysis} 与 {@link resumeRunningSession} 共用：
   * 首次分析时调用以建立链路，刷新恢复续流时调用以重建被清空的链路，
   * 且不重新提交分析请求。</p>
   *
   * @param sessionId 会话 ID
   */
  function bindSessionHandlers(sessionId: string) {
    // 初始化该会话的工具调用入参/结果缓冲区
    sessionToolCallInputBuffers.set(sessionId, new Map());
    sessionToolResultBuffers.set(sessionId, new Map());

    // 仅当该会话是当前选中会话时才重置全局分析状态（含 reset）。
    // 后台会话（sessionId !== currentSessionId）只初始化缓冲区与回调，不触碰全局 state，
    // 避免清空当前显示会话的实时内容，导致"来回切换只有一个会话能渲染"。
    if (sessionId === sessionStore.currentSessionId) {
      analysisStore.startAnalysis(sessionId);
    }

    // 标记该会话运行中（侧边栏显示转圈）
    sessionStore.markSessionRunning(sessionId, true);

    // 为该会话注册独立的事件回调
    const unregister = registerCallbacks(sessionId, {
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
      },
    });
    sessionUnregisterFns.set(sessionId, unregister);
  }

  /**
   * 启动分析（通过全局 SSE 连接接收事件，通过 SessionStateManager 路由）
   * <p>每个会话独立注册回调和工具结果缓冲区，不干扰其他会话的分析。</p>
   *
   * @param request 数据分析请求
   */
  async function startAnalysis(request: DataAnalysisRequest) {
    // 为会话建立事件链路（工具缓冲区、分析状态、回调注册）
    bindSessionHandlers(request.sessionId);

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
    // 分析结束/停止：清除该会话的运行状态，让侧边栏转圈停止
    sessionStore.markSessionRunning(sessionId, false);
    const unregister = sessionUnregisterFns.get(sessionId);
    if (unregister) {
      unregister();
      sessionUnregisterFns.delete(sessionId);
    }
    sessionCallbacks.delete(sessionId);
    sessionToolResultBuffers.delete(sessionId);
    sessionToolCallInputBuffers.delete(sessionId);
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

    // 获取该会话的工具结果缓冲区与工具入参缓冲区
    const toolResultBuffers = sessionToolResultBuffers.get(sessionId);
    const toolCallInputBuffers = sessionToolCallInputBuffers.get(sessionId);
    if (!toolResultBuffers || !toolCallInputBuffers) {
      console.warn('[SSE Handler] No tool buffers for sessionId:', sessionId);
      return;
    }

    // 诊断日志：确认实时事件是否到达前端（过滤高频 delta，避免刷屏）
    if (!['TEXT_BLOCK_DELTA', 'THINKING_BLOCK_DELTA', 'TOOL_CALL_DELTA', 'TOOL_RESULT_TEXT_DELTA'].includes(event.type)) {
      console.log('[SSE][diag] event:', sessionId, event.type, '| current:', sessionStore.currentSessionId);
    }

    // 使用 SessionStateManager 路由事件
    sessionStateManager.updateState(
      sessionId,
      event,
      sessionStore.currentSessionId,
      toolResultBuffers,
      toolCallInputBuffers,
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

    // 诊断日志：关键事件处理后确认 store 渲染状态（排查「事件到达但 UI 不渲染」）
    if (['AGENT_START', 'AGENT_END', 'ERROR', 'EXCEED_MAX_ITERS', 'AGENT_RESULT'].includes(event.type)) {
      console.log('[SSE][diag] store:', {
        type: event.type,
        contentItems: analysisStore.state.contentItems.length,
        report: analysisStore.state.analysisReport ? 'yes' : 'no',
        isAnalyzing: analysisStore.state.isAnalyzing,
        analysisSessionId: analysisStore.analysisSessionId,
        current: sessionStore.currentSessionId,
      });
    }
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

    // 通知后端真正取消该会话的分析（fire-and-forget，不阻塞本地清理）
    try {
      apiStopAnalysis(sessionId);
    } catch (error) {
      console.error('[SSE] Failed to stop analysis on backend:', error);
    }
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

  /**
   * 恢复运行中会话的分析订阅
   * <p>刷新页面后重建该会话的前端事件链路（工具缓冲区、分析状态、回调注册），
   * 再调用分析接口（携带 resumeOnly）将新的 clientId 重新绑定到仍在运行中的会话，
   * 使其分析事件通过当前 SSE 连接续流；后端同时回放断线期间累积的事件。
   * 不重新提交分析请求，会话未在运行中时后端返回 404，仅警告不报错。</p>
   *
   * @param request 会话恢复请求（包含 sessionId 与新的 clientId）
   */
  async function resumeRunningSession(request: { sessionId: string; clientId: string }) {
    // 重建前端事件链路，接住后端投递到新连接的实时事件
    bindSessionHandlers(request.sessionId);
    try {
      await analyzeStream({
        sessionId: request.sessionId,
        clientId: request.clientId,
        resumeOnly: true,
      });
      console.log('[SSE] Resumed subscription for sessionId:', request.sessionId);
    } catch (error) {
      // 会话未在运行中时后端返回 404，仅警告不影响其他恢复流程
      console.warn('[SSE] Failed to resume subscription for sessionId:', request.sessionId, error);
    }
  }

  return {
    startAnalysis,
    stopAnalysis,
    setCallbacks,
    reconnectClient,
    resumeRunningSession,
  };
}
