<template>
  <div class="agent-message">
    <div class="agent-message__card">
      <!-- 消息头部 -->
      <MessageHeader
        :timestamp="message.timestamp"
        :analysis-start-time="message.analysisState?.analysisStartTime"
        :analysis-end-time="message.analysisState?.analysisEndTime"
        :is-analyzing="isAnalyzing"
      />

      <!-- 消息内容区 -->
      <div class="agent-message__body">
        <!-- 错误状态：替换整个 Body -->
        <template v-if="hasError && classifiedError">
          <ErrorDisplay :error="classifiedError" @retry="$emit('retry')" />
        </template>

        <!-- 正常状态：统一内容流按接收时序渲染，派生区块独立展示 -->
        <template v-else>
          <!-- 统一内容流：思考/工具调用/报告按 seq 时序依次展示 -->
          <ContentStream
            v-if="hasContent"
            :items="message.analysisState!.contentItems"
            :is-analyzing="isAnalyzing"
          />

          <!-- 分析完成后才显示的派生区块 -->
          <template v-if="!isAnalyzing">
            <!-- 图表（紧随报告，交互增强） -->
            <ChartSection
              v-if="hasChart"
              :chart-config="message.analysisState!.chartConfig"
              :chart-type="message.analysisState!.chartType"
            />

            <!-- 搜索结果（保留独立展示） -->
            <SearchResultsCard
              v-if="hasSearchResults"
              :results="message.analysisState!.searchResults!"
            />

            <!-- 数据表格（导出 + 分页） -->
            <DataTableSection
              v-if="hasData"
              :data="message.analysisState!.queryData"
            />

            <!-- 建议追问（仅分析完成后显示，点击由 SuggestionsSection 内部处理） -->
            <SuggestionsSection
              v-if="hasSuggestions"
              :suggestions="message.analysisState!.suggestions"
            />
          </template>
        </template>
      </div>

      <!-- 消息底部操作栏（分析完成后显示，反馈状态由 MessageFooter 内部管理） -->
      <MessageFooter
        v-if="!isAnalyzing && !hasError"
        :message="message"
        @retry="$emit('retry')"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import type { ChatMessage } from '../types';
import { classifyError } from '../utils/errorHandler';
import { hasChartValue } from '../utils/validators';
import MessageHeader from './MessageHeader.vue';
import MessageFooter from './MessageFooter.vue';
import ErrorDisplay from './ErrorDisplay.vue';
import SearchResultsCard from './cards/SearchResultsCard.vue';
import ContentStream from './cards/ContentStream.vue';

import ChartSection from './cards/ChartSection.vue';
import DataTableSection from './cards/DataTableSection.vue';
import SuggestionsSection from './cards/SuggestionsSection.vue';

/**
 * Agent 消息组件 — 单列流式消息卡片
 * <p>基于流式消息接收顺序的统一内容流渲染：思考、工具调用、报告严格按接收时序
 * 依次展示，不按特殊事件类型剥离分析报告；图表/数据/搜索/建议作为独立派生区块展示。</p>
 * <p><b>子组件自治：</b>建议追问点击由 SuggestionsSection 内部通过 uiStore.setSuggestion 处理，
 * 反馈状态由 MessageFooter 内部管理，本组件不再透传空回调。</p>
 */
interface Props {
  /** 当前消息对象 */
  message: ChatMessage;
  /** 是否正在分析中（通过 prop 传递，避免全局 store 状态污染） */
  isAnalyzing?: boolean;
}

const props = withDefaults(defineProps<Props>(), {
  isAnalyzing: false,
});

defineEmits<{
  (e: 'retry'): void;
}>();

/** 错误分类 */
const classifiedError = computed(() => {
  if (!props.message.analysisState?.errorMessage) return null;
  return classifyError(props.message.analysisState.errorMessage);
});

/** 是否有错误 */
const hasError = computed(() => {
  return !props.isAnalyzing && Boolean(props.message.analysisState?.errorMessage) && classifiedError.value !== null;
});

/** 是否有搜索结果 */
const hasSearchResults = computed(() => {
  return Boolean(props.message.analysisState?.searchResults && props.message.analysisState.searchResults.length > 0);
});

/** 是否有统一内容流内容（分析中及分析完成后均显示） */
const hasContent = computed(() => {
  return Boolean(props.message.analysisState?.contentItems && props.message.analysisState.contentItems.length > 0);
});

/** 是否有图表（确保 chartConfig 有效、包含可渲染数据，且具备直观展示价值）
 * <p>展示前会执行图表价值判断：避免展示数据量过少/过多、单一值、线性增长或表格类配置。</p>
 */
const hasChart = computed(() => {
  const chartConfig = props.message.analysisState?.chartConfig;
  if (!chartConfig) {
    console.debug('[hasChart] chartConfig is null/undefined');
    return false;
  }

  const chartType = props.message.analysisState?.chartType || null;
  if (!hasChartValue(chartConfig, chartType)) {
    console.debug('[hasChart] chartConfig exists but has no intuitive value');
    return false;
  }

  return true;
});

/** 是否有数据 */
const hasData = computed(() => {
  return Boolean(props.message.analysisState && (props.message.analysisState.queryData?.length ?? 0) > 0);
});

/** 是否有建议追问 */
const hasSuggestions = computed(() => {
  return Boolean(props.message.analysisState?.suggestions && (props.message.analysisState.suggestions.length ?? 0) > 0);
});
</script>

<style scoped lang="less">
.agent-message {
  display: flex;
  gap: 12px;
  max-width: 85%;

  &__card {
    flex: 1;
    background: var(--td-bg-color-container);
    border: 1px solid var(--td-border-level-1-color);
    border-radius: 12px;
    box-shadow: 0 1px 3px rgba(0, 0, 0, 0.06);
    overflow: hidden;
    transition: box-shadow 0.2s;

    &:hover {
      box-shadow: 0 4px 12px rgba(0, 0, 0, 0.08);
    }
  }

  &__body {
    padding: 16px;
    display: flex;
    flex-direction: column;
    gap: 16px;
  }
}

.agent-section {
  min-width: 0;
}
</style>
