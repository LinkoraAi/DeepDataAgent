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
  const { startAnalysis, stopAnalysis, setCallbacks, reconnectClient } = useSSE();
  const { connect: connectSSE, getClientId, setOnReconnect } = useSSEConnection();
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
        rounds: savedState.rounds.map(round => ({
          ...round,
          thinking: { ...round.thinking },
          toolCalls: round.toolCalls.map(t => ({ ...t })),
        })),
        isTimelineExpanded: savedState.isTimelineExpanded,
        currentSQL: savedState.currentSQL,
        queryData: [...savedState.queryData],
        chartConfig: savedState.chartConfig,
        chartType: savedState.chartType,
        analysisReport: savedState.analysisReport,
        report: savedState.report ? { ...savedState.report } : null,
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

  return {
    userQuestion,
    isAnalyzing: computed(() => analysisStore.state.isAnalyzing),
    submitQuestion,
    retryAnalysis,
    stopAnalysis,
    resetSelection,
  };
}
