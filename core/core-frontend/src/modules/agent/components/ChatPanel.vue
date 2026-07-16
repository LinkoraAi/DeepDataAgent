<template>
  <div class="chat-panel">
    <!-- 欢迎屏幕（无消息时显示） -->
    <WelcomeScreen
      v-if="sessionStore.chatMessages.length === 0 && !analysisStore.state.isAnalyzing"
    />

    <!-- 消息列表 -->
    <MessageList
      @retry="handleRetry"
    />

    <!-- 输入面板 -->
    <InputPanel
      :is-analyzing="analysisStore.state.isAnalyzing"
      :can-submit="canSubmit"
      @submit="handleSubmit"
      @stop="handleStop"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted } from 'vue';
import { MessagePlugin } from 'tdesign-vue-next';
import { useSessionStore } from '../stores/session';
import { useDatasourceStore } from '../stores/datasource';
import { useModelStore } from '@/modules/model/stores/model';
import { useAnalysisStore } from '../stores/analysis';
import { useDataAnalysis } from '../composables/useDataAnalysis';
import MessageList from './MessageList.vue';
import InputPanel from './InputPanel.vue';
import WelcomeScreen from './WelcomeScreen.vue';

const sessionStore = useSessionStore();
const datasourceStore = useDatasourceStore();
const modelStore = useModelStore();
const analysisStore = useAnalysisStore();
const { submitQuestion, retryAnalysis, stopAnalysis } = useDataAnalysis();

/** 是否可以提交 */
const canSubmit = computed(() => {
  return Boolean(
    datasourceStore.currentDatasourceId &&
    modelStore.selectedConfigId &&
    !analysisStore.state.isAnalyzing
  );
});

/** 提交问题 */
async function handleSubmit(question: string, enableWebSearch: boolean) {
  if (!canSubmit.value) {
    return;
  }

  try {
    await submitQuestion(question, enableWebSearch);
  } catch (err: any) {
    MessagePlugin.error(err.message || '提交失败');
  }
}

/** 停止分析 */
function handleStop() {
  stopAnalysis();
}

/** 重试分析 */
async function handleRetry() {
  try {
    await retryAnalysis();
  } catch (err: any) {
    MessagePlugin.error(err.message || '重试失败');
  }
}

onMounted(async () => {
  await Promise.all([
    sessionStore.loadSessions(),
    datasourceStore.loadEnabled(),
    modelStore.loadConfigs(),
  ]);
});
</script>

<style scoped>
.chat-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: #ffffff;
}
</style>
