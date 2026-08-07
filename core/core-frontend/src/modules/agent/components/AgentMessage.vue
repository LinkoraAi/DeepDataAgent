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

        <!-- 正常状态：流式渐进显示，内容块按到达顺序渲染 -->
        <template v-else>
          <!-- 统一时间线：分析中和分析完成后均显示 -->
          <TimelineSection
            v-if="hasTimeline"
            :rounds="message.analysisState!.rounds"
            :is-analyzing="isAnalyzing"
            :analysis-start-time="message.analysisState?.analysisStartTime ?? null"
            :analysis-end-time="message.analysisState?.analysisEndTime ?? null"
          />

          <!-- 分析报告（主内容，分析中及分析完成后均显示，流式渲染） -->
          <ReportSection
            v-if="hasReport"
            :report="message.analysisState!.analysisReport!"
            :is-analyzing="isAnalyzing"
          />

          <!-- 分析完成后才显示的区块 -->
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
import TimelineSection from './cards/TimelineSection.vue';

import ChartSection from './cards/ChartSection.vue';
import DataTableSection from './cards/DataTableSection.vue';
import ReportSection from './cards/ReportSection.vue';
import SuggestionsSection from './cards/SuggestionsSection.vue';

/**
 * Agent 消息组件 — 单列流式消息卡片
 * <p>从 Tab 布局重构为单列卡片，通过渐进式披露实现：
 * 分析中所有区块默认展开，分析完成后思考+工具自动折叠。</p>
 * <p>采用 ReAct 轮次时间线：将思考与工具调用按"思考 → 工具调用"的轮次分组展示，
 * 体现 ReAct 循环的因果关系。</p>
 * <p><b>报告渲染顺序：</b>分析报告在分析中及分析完成后均以 ReportSection 流式渲染，
 * 确保用户实时看到最终结论。</p>
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

/** 是否有 ReAct 轮次数据 */
const hasTimeline = computed(() => {
  return Boolean(props.message.analysisState?.rounds && props.message.analysisState.rounds.length > 0);
});

/** 是否有分析报告（分析中及分析完成后均显示，流式渲染） */
const hasReport = computed(() => {
  return Boolean(props.message.analysisState?.analysisReport);
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
