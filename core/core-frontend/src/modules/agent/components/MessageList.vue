<template>
  <div ref="messageListRef" class="message-list" @scroll="handleScroll">
    <!-- 消息列表 -->
    <div class="message-list__content">
      <template v-for="message in sessionStore.chatMessages" :key="message.id">
        <UserMessage v-if="message.role === 'user'" :message="message" />
        <AgentMessage v-else :message="message" @retry="handleRetry" />
      </template>

      <!-- 当前分析中的实时消息 -->
      <AgentMessage
        v-if="analysisStore.state.isAnalyzing && currentAnalysisMessage"
        :message="currentAnalysisMessage"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, nextTick } from 'vue';
import { useSessionStore } from '../stores/session';
import { useAnalysisStore } from '../stores/analysis';
import type { ChatMessage } from '../types';
import UserMessage from './UserMessage.vue';
import AgentMessage from './AgentMessage.vue';

const emit = defineEmits<{
  (e: 'retry'): void;
}>();

const sessionStore = useSessionStore();
const analysisStore = useAnalysisStore();
const messageListRef = ref<HTMLElement>();
const shouldAutoScroll = ref(true);

/** 当前分析中的实时消息（用于显示正在进行的分析） */
const currentAnalysisMessage = computed<ChatMessage | null>(() => {
  if (!analysisStore.state.isAnalyzing) return null;

  return {
    id: 'current-analysis',
    role: 'agent',
    content: '',
    timestamp: Date.now(),
    analysisState: {
      thinkingSteps: [...analysisStore.state.thinkingSteps],
      toolCalls: [...analysisStore.state.toolCalls],
      searchResults: [...(analysisStore.state.searchResults || [])],
      currentSQL: analysisStore.state.currentSQL,
      queryData: [...analysisStore.state.queryData],
      chartConfig: analysisStore.state.chartConfig,
      chartType: analysisStore.state.chartType,
      analysisReport: analysisStore.state.analysisReport,
      isEmptyResult: analysisStore.state.isEmptyResult,
      errorMessage: analysisStore.state.errorMessage,
      analysisStartTime: analysisStore.state.analysisStartTime,
      analysisEndTime: null,
    },
  };
});

/** 滚动事件处理 */
function handleScroll() {
  if (!messageListRef.value) return;
  const { scrollTop, scrollHeight, clientHeight } = messageListRef.value;
  shouldAutoScroll.value = scrollHeight - scrollTop - clientHeight < 50;
}

/** 滚动到底部 */
function scrollToBottom() {
  if (!shouldAutoScroll.value || !messageListRef.value) return;
  nextTick(() => {
    if (messageListRef.value) {
      messageListRef.value.scrollTop = messageListRef.value.scrollHeight;
    }
  });
}

function handleRetry() {
  emit('retry');
}

// 监听消息数量变化自动滚动
watch(
  () => sessionStore.chatMessages.length,
  () => {
    scrollToBottom();
  }
);

// 监听分析状态变化自动滚动
watch(
  () => analysisStore.state.thinkingSteps.length,
  () => {
    scrollToBottom();
  }
);

watch(
  () => analysisStore.state.toolCalls.length,
  () => {
    scrollToBottom();
  }
);
</script>

<style scoped lang="less">
.message-list {
  flex: 1;
  overflow-y: auto;
  padding: 16px 0;

  &__content {
    display: flex;
    flex-direction: column;
    gap: 20px;
    max-width: 768px;
    margin: 0 auto;
    padding: 0 16px;
  }
}
</style>
