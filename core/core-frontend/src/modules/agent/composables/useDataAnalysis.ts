import { ref } from 'vue';
import { useSessionStore } from '../stores/session';
import { useDatasourceStore } from '../stores/datasource';
import { useModelStore } from '@/modules/model/stores/model';
import { useAnalysisStore } from '../stores/analysis';
import { useSSE } from './useSSE';
import { validateAnalysisInput } from '../utils/validators';
import type { ChatMessage } from '../types';

/**
 * Data analysis composable
 */
export function useDataAnalysis() {
  const sessionStore = useSessionStore();
  const datasourceStore = useDatasourceStore();
  const modelStore = useModelStore();
  const analysisStore = useAnalysisStore();
  const { startAnalysis, stopAnalysis, setCallbacks } = useSSE();

  const userQuestion = ref('');
  const lastQuestion = ref('');

  // 注册 SSE 完成和出错回调，将 agent 消息写入 sessionStore
  setCallbacks(
    // onComplete: 分析完成后添加 agent 消息
    () => {
      const snapshot = analysisStore.createSnapshot();
      const agentMsg: ChatMessage = {
        id: `agent-${Date.now()}`,
        role: 'agent',
        content: analysisStore.currentUserQuestion,
        timestamp: Date.now(),
        analysisState: snapshot,
      };
      sessionStore.addLocalChatMessage(agentMsg);
    },
    // onError: 出错时也添加带错误状态的 agent 消息
    (errorMessage: string) => {
      const snapshot = analysisStore.createSnapshot();
      const agentMsg: ChatMessage = {
        id: `agent-${Date.now()}`,
        role: 'agent',
        content: analysisStore.currentUserQuestion,
        timestamp: Date.now(),
        analysisState: snapshot,
      };
      sessionStore.addLocalChatMessage(agentMsg);
    }
  );

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

    // 添加用户消息到 sessionStore
    const userMsg: ChatMessage = {
      id: `user-${Date.now()}`,
      role: 'user',
      content: userQuestion.value,
      timestamp: Date.now(),
    };
    sessionStore.addLocalChatMessage(userMsg);

    // Create session if not exists
    if (!sessionStore.currentSessionId) {
      await sessionStore.createSession(
        datasourceStore.currentDatasourceId!,
        modelStore.selectedConfigId!
      );
    }

    // Start analysis
    await startAnalysis({
      sessionId: sessionStore.currentSessionId!,
      modelConfigId: modelStore.selectedConfigId!,
      connectionId: datasourceStore.currentDatasourceId!.toString(),
      userQuestion: userQuestion.value,
      enableWebSearch,
    });

    userQuestion.value = '';
  }

  /**
   * Retry last analysis
   */
  async function retryAnalysis(enableWebSearch: boolean = false) {
    if (!lastQuestion.value) {
      throw new Error('没有可重试的分析');
    }

    analysisStore.reset();

    const userMsg: ChatMessage = {
      id: `user-${Date.now()}`,
      role: 'user',
      content: lastQuestion.value,
      timestamp: Date.now(),
    };
    sessionStore.addLocalChatMessage(userMsg);

    if (!sessionStore.currentSessionId) {
      await sessionStore.createSession(
        datasourceStore.currentDatasourceId!,
        modelStore.selectedConfigId!
      );
    }

    await startAnalysis({
      sessionId: sessionStore.currentSessionId!,
      modelConfigId: modelStore.selectedConfigId!,
      connectionId: datasourceStore.currentDatasourceId!.toString(),
      userQuestion: lastQuestion.value,
      enableWebSearch,
    });
  }

  /**
   * Reset selection
   */
  function resetSelection() {
    datasourceStore.setCurrentDatasource(null as any);
    modelStore.setSelectedConfig(null as any);
    userQuestion.value = '';
  }

  return {
    userQuestion,
    isAnalyzing: analysisStore.state.isAnalyzing,
    submitQuestion,
    retryAnalysis,
    stopAnalysis,
    resetSelection,
  };
}
