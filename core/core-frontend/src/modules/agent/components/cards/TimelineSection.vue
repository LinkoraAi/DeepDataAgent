<template>
  <div class="timeline-section agent-section">
    <!-- 时间线头部：显示标题、耗时、加载状态、折叠按钮 -->
    <div class="timeline-section__header" @click="toggleExpand">
      <span class="timeline-section__icon" :class="{ 'timeline-section__icon--expanded': isExpanded }">
        ▶
      </span>
      <span>🧠 分析过程</span>
      <span v-if="totalDuration" class="timeline-section__meta">
        ({{ totalDuration }}s)
      </span>
      <t-loading v-if="isAnalyzing" size="small" class="timeline-section__loading" />
    </div>

    <!-- 可折叠的时间线内容区域（使用 grid 动画实现平滑的高度过渡） -->
    <div
      class="timeline-section__collapsible-wrapper"
      :class="{ 'timeline-section__collapsible-wrapper--expanded': isExpanded }"
    >
      <div class="timeline-section__content">
        <div v-if="rounds.length === 0" class="timeline-section__empty">
          暂无分析过程
        </div>
        <div v-else class="timeline-section__rounds">
          <template v-for="(round, index) in rounds" :key="round.id">
            <!-- 轮次间弱分隔线（非首个轮次前） -->
            <div v-if="index > 0" class="timeline-section__separator"></div>
            <TimelineRound :round="round" :is-analyzing="isAnalyzing" />
          </template>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue';
import type { ReActRound } from '../../types';
import { calculateDuration } from '../../composables/useTimelineItem';
import TimelineRound from './TimelineRound.vue';

/**
 * 时间线区块组件 — 按 ReAct 轮次统一展示分析过程
 * <p>将 Agent 的思考与工具调用按"思考 → 工具调用"的轮次分组展示，
 * 体现 ReAct 循环的因果关系。支持整体折叠/展开，
 * 每个轮次内部也支持独立折叠（由 TimelineRound 管理）。</p>
 * <p>展开/折叠状态完全由本组件内部管理，避免父组件 one-way prop 导致点击无效。</p>
 * <p>分析结束后自动折叠，分析进行中默认展开。</p>
 */
interface Props {
  /** ReAct 轮次数组 */
  rounds: ReActRound[];
  /** 是否分析中 */
  isAnalyzing: boolean;
  /** 分析开始时间戳 */
  analysisStartTime: number | null;
  /** 分析结束时间戳 */
  analysisEndTime: number | null;
}

const props = defineProps<Props>();

/**
 * 本地展开状态：由 isAnalyzing 双向驱动。
 * <p>关键修复：watch 必须同步处理"展开"与"折叠"两个方向。
 * 仅处理折叠会导致：切到历史会话（isAnalyzing=false）折叠后，切回分析中的会话（isAnalyzing=true）时，
 * 由于组件被复用（v-for key 为 round-index），isExpanded 保持折叠状态无法展开。
 * 改为「分析中展开 / 完成后折叠」，保证思考未结束时切回一定展开。</p>
 */
const isExpanded = ref(props.isAnalyzing);

/** 分析过程展开/折叠跟随 isAnalyzing 状态 */
watch(
  () => props.isAnalyzing,
  (analyzing) => {
    isExpanded.value = analyzing;
  },
  { immediate: true }
);

/** 计算总耗时（秒） */
const totalDuration = computed(() => {
  if (!props.analysisStartTime || !props.analysisEndTime) return null;
  return calculateDuration(props.analysisStartTime, props.analysisEndTime);
});

/** 切换整体展开/折叠 */
function toggleExpand() {
  isExpanded.value = !isExpanded.value;
}
</script>

<style scoped lang="less">
.timeline-section {
  &__header {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 8px 12px;
    cursor: pointer;
    color: var(--td-text-color-secondary);
    font-size: 13px;
    border-radius: 6px;
    transition: background 0.2s cubic-bezier(0.4, 0, 0.2, 1);
    user-select: none;

    &:hover {
      background: var(--td-bg-color-container-hover);
    }
  }

  &__icon {
    font-size: 10px;
    transition: transform 0.3s cubic-bezier(0.4, 0, 0.2, 1);
    display: inline-block;

    &--expanded {
      transform: rotate(90deg);
    }
  }

  &__meta {
    color: var(--td-text-color-placeholder);
    margin-left: auto;
  }

  &__loading {
    margin-left: 8px;
    display: inline-flex;
    align-items: center;
  }

  &__collapsible-wrapper {
    display: grid;
    grid-template-rows: 0fr;
    transition: grid-template-rows 0.45s cubic-bezier(0.25, 0.8, 0.25, 1),
                opacity 0.35s cubic-bezier(0.25, 0.8, 0.25, 1);
    opacity: 0;

    &--expanded {
      grid-template-rows: 1fr;
      opacity: 1;
    }
  }

  &__content {
    overflow: hidden;
    background: var(--td-bg-color-page);
    border-left: 3px solid var(--td-brand-color);
    padding: 12px 16px;
    margin-top: 8px;
    border-radius: 0 6px 6px 0;
  }

  &__empty {
    color: var(--td-text-color-placeholder);
    font-size: 13px;
    text-align: center;
    padding: 20px 0;
  }

  &__rounds {
    display: flex;
    flex-direction: column;
    gap: 8px;
  }

  // 轮次间弱分隔线（细虚线）
  &__separator {
    border-top: 1px dashed var(--td-border-level-1-color);
    margin: 4px 0;
  }
}
</style>
