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
import { computed, onMounted, watch } from 'vue';
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
const { submitQuestion, retryAnalysis, stopAnalysis, resumeAllRunningSessions } = useDataAnalysis();

/**
 * 监听会话切换：切换会话时不中断 SSE 分析，让分析在后台继续执行
 * <p>关键修复：使用全局 SSE 连接管理器后，切换会话不再需要断开 SSE 连接。
 * 分析会在后台继续执行，事件通过 sessionId 路由到正确的会话。</p>
 */
watch(
  () => sessionStore.currentSessionId,
  async (newId, oldId) => {
    console.debug('[ChatPanel] watch currentSessionId:', { newId, oldId });

    // 同一会话不重复处理
    if (newId === oldId) return;

    // 不再调用 stopAnalysis，让分析在后台继续执行

    // 加载新会话的历史消息
    // 优先从缓存恢复（正在分析中的会话消息尚未持久化，后端拉取会覆盖本地状态）
    if (newId) {
      const cachedMessages = sessionStore.sessionMessagesMap.get(newId);
      if (cachedMessages && cachedMessages.length > 0) {
        // 缓存命中：直接使用缓存，不从后端加载（避免覆盖分析中的本地消息）
        sessionStore.chatMessages = [...cachedMessages];
        console.debug('[ChatPanel] restored messages from cache', {
          sessionId: newId,
          count: cachedMessages.length
        });
      } else {
        // 缓存未命中：从后端加载历史消息
        await sessionStore.loadMessages(newId);
      }
    }
  }
);

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
  if (sessionStore.currentSessionId) {
    stopAnalysis(sessionStore.currentSessionId);
  }
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
  // 加载完会话后恢复运行中会话的分析订阅（先回放消息，再通过 SSE 续流）
  // 消息回放由 watch(currentSessionId) 在 loadSessions 设置 currentSessionId 后触发
  await resumeAllRunningSessions();
  // 注：会话切换/加载由 watch(currentSessionId) 统一处理
  // onMounted 中不再调用，避免与 watch 竞态
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
