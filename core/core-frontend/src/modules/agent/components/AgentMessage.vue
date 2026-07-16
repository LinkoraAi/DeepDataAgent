<template>
  <div class="agent-message">
    <div class="agent-message__header">
      <div class="agent-message__avatar">
        <t-avatar>🤖</t-avatar>
      </div>
      <div class="agent-message__info">
        <div class="agent-message__role">Agent</div>
        <div class="agent-message__time">{{ formatTime(message.timestamp) }}</div>
      </div>
    </div>
    <div class="agent-message__content">
      <!-- 分析中状态 -->
      <div v-if="isAnalyzing" class="agent-message__analyzing">
        <LoadingIndicator
          :phase-label="phaseLabel"
          :elapsed-seconds="elapsedSeconds"
          :current-phase="currentPhase"
        />
      </div>

      <!-- 错误状态 -->
      <div v-else-if="message.analysisState?.errorMessage && classifiedError" class="agent-message__error">
        <ErrorDisplay :error="classifiedError" @retry="$emit('retry')" />
      </div>

      <!-- 分析结果卡片 -->
      <div v-else-if="message.analysisState" class="agent-message__cards">
        <ThinkingCard
          v-if="message.analysisState.thinkingSteps.length > 0"
          :thinking-steps="message.analysisState.thinkingSteps"
        />
        <ToolCallsCard
          v-if="message.analysisState.toolCalls.length > 0"
          :tool-calls="message.analysisState.toolCalls"
        />
        <SearchResultsCard
          v-if="message.analysisState.searchResults && message.analysisState.searchResults.length > 0"
          :results="message.analysisState.searchResults"
        />
        <SqlCard
          v-if="message.analysisState.currentSQL"
          :sql="message.analysisState.currentSQL"
        />
        <ChartCard
          v-if="message.analysisState.chartConfig"
          :chart-config="message.analysisState.chartConfig"
          :chart-type="message.analysisState.chartType"
        />
        <DataPreviewCard
          v-if="message.analysisState.queryData.length > 0"
          :data="message.analysisState.queryData"
        />
        <AnalysisCard
          v-if="message.analysisState.analysisReport"
          :report="message.analysisState.analysisReport"
        />
        <MetaInfoCard
          :start-time="message.analysisState.analysisStartTime"
          :end-time="message.analysisState.analysisEndTime"
          :row-count="message.analysisState.queryData.length"
        />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import type { ChatMessage } from '../types';
import { useAnalysisStore } from '../stores/analysis';
import { classifyError } from '../utils/errorHandler';
import { useAnalysisProgress } from '../composables/useAnalysisProgress';
import LoadingIndicator from './LoadingIndicator.vue';
import ErrorDisplay from './ErrorDisplay.vue';
import ThinkingCard from './cards/ThinkingCard.vue';
import ToolCallsCard from './cards/ToolCallsCard.vue';
import SearchResultsCard from './cards/SearchResultsCard.vue';
import SqlCard from './cards/SqlCard.vue';
import ChartCard from './cards/ChartCard.vue';
import DataPreviewCard from './cards/DataPreviewCard.vue';
import AnalysisCard from './cards/AnalysisCard.vue';
import MetaInfoCard from './cards/MetaInfoCard.vue';

interface Props {
  message: ChatMessage;
}

const props = defineProps<Props>();
defineEmits<{
  (e: 'retry'): void;
}>();

const analysisStore = useAnalysisStore();
const { phaseLabel, elapsedSeconds, currentPhase } = useAnalysisProgress();

const isAnalyzing = computed(() => {
  return analysisStore.state.isAnalyzing;
});

const classifiedError = computed(() => {
  if (!props.message.analysisState?.errorMessage) return null;
  return classifyError(props.message.analysisState.errorMessage);
});

function formatTime(timestamp: number): string {
  const date = new Date(timestamp);
  const hours = date.getHours().toString().padStart(2, '0');
  const minutes = date.getMinutes().toString().padStart(2, '0');
  return `${hours}:${minutes}`;
}
</script>

<style scoped lang="less">
.agent-message {
  display: flex;
  flex-direction: column;
  gap: 12px;
  max-width: 85%;

  &__header {
    display: flex;
    align-items: center;
    gap: 12px;
  }

  &__avatar {
    flex-shrink: 0;
  }

  &__info {
    display: flex;
    flex-direction: column;
    gap: 2px;
  }

  &__role {
    font-size: 14px;
    font-weight: 600;
    color: var(--td-text-color-primary);
  }

  &__time {
    font-size: 11px;
    color: var(--td-text-color-placeholder);
  }

  &__content {
    margin-left: 44px;
  }

  &__analyzing {
    padding: 16px;
    background: var(--td-bg-color-secondarycontainer);
    border-radius: 8px;
  }

  &__cards {
    display: flex;
    flex-direction: column;
    gap: 16px;
  }

  &__error {
    padding: 16px;
    background: var(--td-bg-color-secondarycontainer);
    border-radius: 8px;
  }
}
</style>
