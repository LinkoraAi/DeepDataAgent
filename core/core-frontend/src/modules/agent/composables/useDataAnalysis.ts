import { ref, computed } from 'vue';
import { useSessionStore } from '../stores/session';
import { useDatasourceStore } from '../stores/datasource';
import { useModelStore } from '@/modules/model/stores/model';
import { useAnalysisStore } from '../stores/analysis';
import { useSSE } from './useSSE';
import { useSSEConnection } from './useSSEConnection';
import { useSessionStateManager } from './useSessionStateManager';
import { validateAnalysisInput } from '../utils/validators';
import type { ChatMessage, AnalysisSnapshot } from '../types';

/**
 * Data analysis composable
 */
export function useDataAnalysis() {
  const sessionStore = useSessionStore();
  const datasourceStore = useDatasourceStore();
  const modelStore = useModelStore();
  const analysisStore = useAnalysisStore();
  const { startAnalysis, stopAnalysis, setCallbacks, reconnectClient, resumeRunningSession } = useSSE();
  const { connect: connectSSE, getClientId, setOnReconnect, setOnAutoReconnect } = useSSEConnection();
  const sessionStateManager = useSessionStateManager();

  const userQuestion = ref('');
  const lastQuestion = ref('');
  /** 最近一次分析请求，用于 SSE 重连后重新提交 */
  const lastRequest = ref<{ sessionId: string; modelConfigId: number; connectionId: string; userQuestion: string; enableWebSearch: boolean } | null>(null);

  /**
   * 获取指定会话的分析状态快照
   * <p>当前会话直接从 analysisStore 获取；后台会话从 SessionStateManager 的已保存状态获取，
   * 避免后台会话完成时 analysisStore 已被恢复为当前会话状态导致快照错误。</p>
   *
   * @param sessionId 会话 ID
   * @returns 分析状态快照
   */
  function getSnapshotForSession(sessionId: string): AnalysisSnapshot {
    if (sessionId === sessionStore.currentSessionId) {
      return analysisStore.createSnapshot();
    }
    const savedState = sessionStateManager.getSavedState(sessionId);
    if (savedState) {
      return {
        isAnalyzing: savedState.isAnalyzing,
        contentItems: savedState.contentItems.map(item => ({ ...item })),
        currentSQL: savedState.currentSQL,
        queryData: [...savedState.queryData],
        chartConfig: savedState.chartConfig,
        chartType: savedState.chartType,
        analysisReport: savedState.analysisReport,
        searchResults: savedState.searchResults ? [...savedState.searchResults] : null,
        isEmptyResult: savedState.isEmptyResult,
        errorMessage: savedState.errorMessage,
        analysisStartTime: savedState.analysisStartTime,
        analysisEndTime: savedState.analysisEndTime,
        suggestions: [...savedState.suggestions],
      };
    }
    return analysisStore.createSnapshot();
  }

  // 注册 SSE 重连回调，在连接断开重连后重新提交分析请求以更新 clientId
  setOnReconnect(() => {
    const request = lastRequest.value;
    if (!request) {
      console.log('[DataAnalysis] No pending analysis request to re-submit after reconnect');
      return;
    }
    const newClientId = getClientId();
    if (!newClientId) {
      console.warn('[DataAnalysis] Cannot re-submit: no clientId available');
      return;
    }
    console.log('[DataAnalysis] SSE reconnected, re-submitting analysis request for sessionId:', request.sessionId);
    reconnectClient({
      sessionId: request.sessionId,
      modelConfigId: request.modelConfigId,
      connectionId: request.connectionId,
      userQuestion: request.userQuestion,
      enableWebSearch: request.enableWebSearch,
      clientId: newClientId,
    });
  });

  // 注册 SSE 自动重连成功回调：连接断开后自动重连取得新 clientId 时，
  // 重新恢复所有运行中会话的订阅（重新绑定 clientId + 回放断线期间事件）。
  // 与 setOnReconnect 不同：该路径不依赖 lastRequest，适用于页面刷新后
  // 无待提交请求但仍有运行中会话（后台分析）的场景，避免映射停留在死连接。
  setOnAutoReconnect(() => {
    console.log('[DataAnalysis] SSE auto-reconnected, resuming running sessions...');
    resumeAllRunningSessions();
  });

  /**
   * Submit question
   */
  async function submitQuestion(questionText?: string, enableWebSearch: boolean = false) {
    if (questionText !== undefined) {
      userQuestion.value = questionText;
    }

    const validation = validateAnalysisInput(
      userQuestion.value,
      datasourceStore.currentDatasourceId,
      modelStore.selectedConfigId
    );

    if (!validation.valid) {
      throw new Error(validation.message);
    }

    lastQuestion.value = userQuestion.value;
    analysisStore.currentUserQuestion = userQuestion.value;

    // Create session if not exists
    if (!sessionStore.currentSessionId) {
      await sessionStore.createSession(
        datasourceStore.currentDatasourceId!,
        modelStore.selectedConfigId!,
        userQuestion.value
      );
    }

    // 存储当前会话的上次问题（用于重试功能）
    sessionStore.setSessionLastQuestion(sessionStore.currentSessionId!, userQuestion.value);

    // 添加用户消息到当前会话和缓存（在会话创建后）
    const userMsg: ChatMessage = {
      id: `user-${Date.now()}`,
      role: 'user',
      content: userQuestion.value,
      timestamp: Date.now(),
    };
    sessionStore.addMessageToSession(sessionStore.currentSessionId!, userMsg);

    // 确保 SSE 连接已建立
    const clientId = getClientId();
    if (!clientId) {
      try {
        await connectSSE();
      } catch (error) {
        console.error('[DataAnalysis] Failed to establish SSE connection:', error);
        throw new Error('无法建立 SSE 连接，请刷新页面重试');
      }
    }

    // 按会话注册回调，闭包捕获本次分析的用户问题文本
    // 关键修复：通过闭包捕获 userQuestionText，避免后台会话完成时读取到错误的全局 currentUserQuestion
    const capturedQuestion = userQuestion.value;
    setCallbacks(
      sessionStore.currentSessionId!,
      // onComplete: 分析完成后添加 agent 消息
      (sessionId: string) => {
        const snapshot = getSnapshotForSession(sessionId);
        const agentMsg: ChatMessage = {
          id: `agent-${Date.now()}`,
          role: 'agent',
          content: capturedQuestion,
          timestamp: Date.now(),
          analysisState: snapshot,
        };
        sessionStore.addMessageToSession(sessionId, agentMsg);
        // 分析完成后清理 lastRequest
        if (lastRequest.value?.sessionId === sessionId) {
          lastRequest.value = null;
        }
      },
      // onError: 出错时也添加带错误状态的 agent 消息
      (sessionId: string, errorMessage: string) => {
        const snapshot = getSnapshotForSession(sessionId);
        const agentMsg: ChatMessage = {
          id: `agent-${Date.now()}`,
          role: 'agent',
          content: capturedQuestion,
          timestamp: Date.now(),
          analysisState: snapshot,
        };
        sessionStore.addMessageToSession(sessionId, agentMsg);
        // 出错后清理 lastRequest
        if (lastRequest.value?.sessionId === sessionId) {
          lastRequest.value = null;
        }
      }
    );

    // Start analysis
    lastRequest.value = {
      sessionId: sessionStore.currentSessionId!,
      modelConfigId: modelStore.selectedConfigId!,
      connectionId: datasourceStore.currentDatasourceId!.toString(),
      userQuestion: userQuestion.value,
      enableWebSearch,
    };
    startAnalysis({
      sessionId: sessionStore.currentSessionId!,
      modelConfigId: modelStore.selectedConfigId!,
      connectionId: datasourceStore.currentDatasourceId!.toString(),
      userQuestion: userQuestion.value,
      enableWebSearch,
      clientId: getClientId()!,
    });

    userQuestion.value = '';
  }

  /**
   * Retry last analysis
   */
  async function retryAnalysis(enableWebSearch: boolean = false) {
    // 从 sessionStore 获取当前会话的上次问题
    const currentSessionId = sessionStore.currentSessionId;
    let questionToRetry = currentSessionId
      ? sessionStore.getSessionLastQuestion(currentSessionId)
      : lastQuestion.value;

    // 兜底：从当前会话消息历史中获取最后一个用户问题
    // 解决 sessionLastQuestionMap 内存丢失（如页面刷新）或 lastQuestion 被清空后无法重试的问题
    if (!questionToRetry && currentSessionId) {
      const lastUserMessage = sessionStore.chatMessages
        .slice()
        .reverse()
        .find((m) => m.role === 'user');
      if (lastUserMessage) {
        questionToRetry = lastUserMessage.content;
      }
    }

    if (!questionToRetry) {
      throw new Error('没有可重试的分析');
    }

    analysisStore.reset();

    const userMsg: ChatMessage = {
      id: `user-${Date.now()}`,
      role: 'user',
      content: questionToRetry,
      timestamp: Date.now(),
    };
    sessionStore.addLocalChatMessage(userMsg);

    if (!sessionStore.currentSessionId) {
      await sessionStore.createSession(
        datasourceStore.currentDatasourceId!,
        modelStore.selectedConfigId!,
        questionToRetry
      );
    }

    // 确保 SSE 连接已建立
    const clientId = getClientId();
    if (!clientId) {
      try {
        await connectSSE();
      } catch (error) {
        console.error('[DataAnalysis] Failed to establish SSE connection:', error);
        throw new Error('无法建立 SSE 连接，请刷新页面重试');
      }
    }

    // 按会话注册回调，闭包捕获本次重试的问题文本
    const capturedQuestion = questionToRetry;
    setCallbacks(
      sessionStore.currentSessionId!,
      (sessionId: string) => {
        const snapshot = getSnapshotForSession(sessionId);
        const agentMsg: ChatMessage = {
          id: `agent-${Date.now()}`,
          role: 'agent',
          content: capturedQuestion,
          timestamp: Date.now(),
          analysisState: snapshot,
        };
        sessionStore.addMessageToSession(sessionId, agentMsg);
      },
      (sessionId: string, errorMessage: string) => {
        const snapshot = getSnapshotForSession(sessionId);
        const agentMsg: ChatMessage = {
          id: `agent-${Date.now()}`,
          role: 'agent',
          content: capturedQuestion,
          timestamp: Date.now(),
          analysisState: snapshot,
        };
        sessionStore.addMessageToSession(sessionId, agentMsg);
      }
    );

    lastRequest.value = {
      sessionId: sessionStore.currentSessionId!,
      modelConfigId: modelStore.selectedConfigId!,
      connectionId: datasourceStore.currentDatasourceId!.toString(),
      userQuestion: questionToRetry,
      enableWebSearch,
    };
    startAnalysis({
      sessionId: sessionStore.currentSessionId!,
      modelConfigId: modelStore.selectedConfigId!,
      connectionId: datasourceStore.currentDatasourceId!.toString(),
      userQuestion: questionToRetry,
      enableWebSearch,
      clientId: getClientId()!,
    });
  }

  /**
   * Reset selection
   */
  async function resetSelection(): Promise<void> {
    datasourceStore.setCurrentDatasource(null as any);
    modelStore.setSelectedConfig(null as any);
    userQuestion.value = '';
  }

  /**
   * 刷新页面后恢复所有运行中会话的分析订阅
   * <p>先确保 SSE 连接已建立并获取 clientId，再遍历 sessionStore.runningSessions，
   * 为每个会话注册完成/出错回调并逐个调用 resumeRunningSession，将新 clientId
   * 重新绑定到仍在运行中的会话。整体用 try/catch 包裹，失败仅打印错误，不影响后续流程。</p>
   */
  async function resumeAllRunningSessions() {
    try {
      // 诊断日志：确认刷新后会话对齐（排查「SSE 有输出但渲染停止」）
      console.log('[DataAnalysis][diag] resume start:', {
        currentSessionId: sessionStore.currentSessionId,
        runningSessions: sessionStore.runningSessions,
        firstSession: sessionStore.sessions[0]?.id,
        sessionsCount: sessionStore.sessions.length,
      });
      // 确保当前视图对齐到运行中的会话：若当前选中会话不是运行中会话，
      // 则切换到第一个运行中会话，使该会话的事件走 updateState 的「当前分支」直接渲染。
      // 否则事件会走后台分支（只存 stateMap 不写 chatMessages），导致刷新后消息停止渲染。
      if (
        sessionStore.runningSessions.length > 0 &&
        (!sessionStore.currentSessionId ||
          !sessionStore.runningSessions.includes(sessionStore.currentSessionId))
      ) {
        sessionStore.currentSessionId = sessionStore.runningSessions[0];
      }

      // 确保 SSE 已连接，获取 clientId
      let clientId = getClientId();
      if (!clientId) {
        await connectSSE();
        clientId = getClientId();
      }
      if (!clientId) {
        console.warn('[DataAnalysis] Cannot resume sessions: no clientId available');
        return;
      }
      // 遍历运行中会话逐个恢复订阅
      for (const sessionId of sessionStore.runningSessions) {
        // 该会话最近一次的用户问题（用于完成后打包 agent 消息），从已加载消息或缓存中取
        const capturedQuestion = getLastUserQuestion(sessionId);

        // 若当前会话的 chatMessages 缺少本次提问的 user 消息，则补充为配对锚点。
        // 否则 groupedMessages 无法把「本次提问 → 分析过程/结果」配对，刷新后内容不渲染。
        if (sessionId === sessionStore.currentSessionId && capturedQuestion) {
          const hasQuestion = sessionStore.chatMessages.some(
            m => m.role === 'user' && m.content === capturedQuestion
          );
          if (!hasQuestion) {
            sessionStore.addMessageToSession(sessionId, {
              id: `user-recovered-${sessionId}`,
              role: 'user',
              content: capturedQuestion,
              timestamp: Date.now(),
            });
          }
        }
        setCallbacks(
          sessionId,
          (sid: string) => {
            const snapshot = getSnapshotForSession(sid);
            // 诊断日志：确认 AGENT_END 打包是否执行、快照是否有内容、消息是否入列
            console.log('[SSE][diag] pack agent:', {
              sid,
              contentItems: snapshot?.contentItems?.length,
              hasReport: !!snapshot?.analysisReport,
              current: sessionStore.currentSessionId,
              chatLenBefore: sessionStore.chatMessages.length,
            });
            const agentMsg: ChatMessage = {
              id: `agent-${Date.now()}`,
              role: 'agent',
              content: capturedQuestion,
              timestamp: Date.now(),
              analysisState: snapshot,
            };
            sessionStore.addMessageToSession(sid, agentMsg);
          },
          (sid: string, errorMessage: string) => {
            const snapshot = getSnapshotForSession(sid);
            const agentMsg: ChatMessage = {
              id: `agent-${Date.now()}`,
              role: 'agent',
              content: capturedQuestion,
              timestamp: Date.now(),
              analysisState: snapshot,
            };
            sessionStore.addMessageToSession(sid, agentMsg);
          }
        );
        await resumeRunningSession({ sessionId, clientId });
      }
    } catch (error) {
      console.error('[DataAnalysis] Failed to resume running sessions:', error);
    }
  }

  /**
   * 获取指定会话最近一次用户提问
   * <p>优先从已加载的会话消息中取最后一条 role=user 的内容（刷新后消息已从后端回放），
   * 兜底使用 sessionStore 缓存的上次问题。</p>
   *
   * @param sessionId 会话 ID
   * @returns 最近一次用户提问文本
   */
  function getLastUserQuestion(sessionId: string): string {
    // 优先取「本次分析」缓存的问题（分析发起时记录），
    // 避免刷新后 sessionMessagesMap 只有历史旧问题而取到上一次提问。
    const cachedQuestion = sessionStore.getSessionLastQuestion(sessionId);
    if (cachedQuestion) {
      return cachedQuestion;
    }
    const messages = sessionStore.sessionMessagesMap.get(sessionId);
    if (messages && messages.length > 0) {
      for (let i = messages.length - 1; i >= 0; i--) {
        if (messages[i].role === 'user') {
          return messages[i].content;
        }
      }
    }
    return '';
  }

  return {
    userQuestion,
    isAnalyzing: computed(() => analysisStore.state.isAnalyzing),
    submitQuestion,
    retryAnalysis,
    stopAnalysis,
    resumeAllRunningSessions,
    resetSelection,
  };
}
