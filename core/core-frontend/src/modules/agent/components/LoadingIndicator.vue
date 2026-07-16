<template>
  <div class="loading-indicator">
    <div class="loading-indicator__content">
      <t-loading size="small" />
      <span class="loading-indicator__label">{{ phaseLabel }}</span>
      <span class="loading-indicator__time">{{ formatTime(elapsedSeconds) }}</span>
    </div>
    <div class="loading-indicator__dots">
      <span v-for="i in 5" :key="i" :class="['dot', { 'dot--active': i <= activeDots }]" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import type { AnalysisPhase } from '../composables/useAnalysisProgress';

const props = defineProps<{
  phaseLabel: string;
  elapsedSeconds: number;
  currentPhase: AnalysisPhase;
}>();

const phaseOrder: AnalysisPhase[] = [
  'connecting',
  'thinking',
  'executing_tools',
  'executing_sql',
  'generating_chart',
  'generating_report',
];

const activeDots = computed(() => {
  const idx = phaseOrder.indexOf(props.currentPhase);
  return idx >= 0 ? idx + 1 : 1;
});

function formatTime(seconds: number): string {
  if (seconds < 60) return `${seconds}s`;
  const m = Math.floor(seconds / 60);
  const s = seconds % 60;
  return `${m}m ${s}s`;
}
</script>

<style scoped lang="less">
.loading-indicator {
  padding: 12px 16px;
  background: var(--td-bg-color-secondarycontainer);
  border-radius: 8px;
  margin-top: 8px;

  &__content {
    display: flex;
    align-items: center;
    gap: 8px;
  }

  &__label {
    font-size: 13px;
    color: var(--td-text-color-primary);
  }

  &__time {
    font-size: 12px;
    color: var(--td-text-color-secondary);
    margin-left: auto;
  }

  &__dots {
    display: flex;
    gap: 4px;
    margin-top: 8px;

    .dot {
      width: 6px;
      height: 6px;
      border-radius: 50%;
      background: var(--td-bg-color-component-disabled);
      transition: background 0.3s;

      &--active {
        background: var(--td-brand-color);
      }
    }
  }
}
</style>
