<template>
  <div class="message-header">
    <div class="message-header__avatar">
      <t-avatar size="36px" style="background: linear-gradient(135deg, var(--td-brand-color), var(--td-brand-color-focus)); color: white;">AI</t-avatar>
    </div>
    <div class="message-header__info">
      <span class="message-header__name">DeepDataAgent</span>
      <span class="message-header__meta">
        <span class="message-header__time">{{ formatTime(props.timestamp) }}</span>
        <span v-if="durationText && !props.isAnalyzing" class="message-header__duration">· {{ durationText }}</span>
        <span v-if="props.isAnalyzing" class="message-header__analyzing">· 分析中</span>
      </span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';

/**
 * 消息头部组件 — 显示头像、角色名、时间戳和分析耗时
 */
interface Props {
  /** 消息时间戳 */
  timestamp: number;
  /** 分析开始时间戳 */
  analysisStartTime?: number | null;
  /** 分析结束时间戳 */
  analysisEndTime?: number | null;
  /** 是否正在分析中 */
  isAnalyzing?: boolean;
}

const props = defineProps<Props>();

/** 分析耗时（秒），仅在分析完成且有起止时间时计算 */
const duration = computed(() => {
  if (!props.analysisStartTime || !props.analysisEndTime) return null;
  return Math.round((props.analysisEndTime - props.analysisStartTime) / 1000);
});

/** 耗时显示文本 */
const durationText = computed(() => {
  if (duration.value === null) return '';
  if (duration.value < 60) return `耗时 ${duration.value}s`;
  const m = Math.floor(duration.value / 60);
  const s = duration.value % 60;
  return `耗时 ${m}m ${s}s`;
});

/** 格式化时间为 HH:MM */
function formatTime(timestamp: number): string {
  const date = new Date(timestamp);
  const hours = date.getHours().toString().padStart(2, '0');
  const minutes = date.getMinutes().toString().padStart(2, '0');
  return `${hours}:${minutes}`;
}
</script>

<style scoped lang="less">
.message-header {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  border-bottom: 1px solid var(--td-border-level-1-color);

  &__avatar {
    flex-shrink: 0;
  }

  &__info {
    display: flex;
    flex-direction: column;
    gap: 2px;
  }

  &__name {
    font-size: 14px;
    font-weight: 600;
    color: var(--td-text-color-primary);
  }

  &__meta {
    display: flex;
    align-items: center;
    gap: 4px;
    font-size: 11px;
    color: var(--td-text-color-placeholder);
  }

  &__analyzing {
    color: var(--td-brand-color);
  }
}
</style>
