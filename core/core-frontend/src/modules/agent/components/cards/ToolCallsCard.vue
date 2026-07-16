<template>
  <BaseCard title="工具调用" icon="🔧" :default-expanded="true">
    <div class="tool-calls-card__list">
      <div
        v-for="tool in toolCalls"
        :key="tool.name"
        class="tool-item"
      >
        <div class="tool-item__info">
          <span class="tool-item__status" :class="`tool-item__status--${tool.status}`">
            {{ getStatusIcon(tool.status) }}
          </span>
          <span class="tool-item__name">{{ tool.name }}</span>
        </div>
        <span v-if="tool.endTime" class="tool-item__time">
          {{ formatDuration(tool.startTime, tool.endTime) }}
        </span>
      </div>
      <div v-if="toolCalls.length === 0" class="tool-calls-card__empty">
        暂无工具调用
      </div>
    </div>
  </BaseCard>
</template>

<script setup lang="ts">
import type { ToolCallItem } from '../../types';
import BaseCard from './BaseCard.vue';

interface Props {
  toolCalls: ToolCallItem[];
}

defineProps<Props>();

function getStatusIcon(status: string): string {
  switch (status) {
    case 'running':
      return '⏳';
    case 'success':
      return '✅';
    case 'error':
      return '❌';
    default:
      return '⚪';
  }
}

function formatDuration(startTime: number, endTime: number): string {
  const duration = (endTime - startTime) / 1000;
  return `${duration.toFixed(2)}s`;
}
</script>

<style scoped lang="less">
.tool-calls-card {
  &__list {
    display: flex;
    flex-direction: column;
    gap: 8px;
  }

  &__empty {
    color: var(--td-text-color-secondary);
    font-size: 13px;
    text-align: center;
    padding: 16px;
  }
}

.tool-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 12px;
  background: var(--td-bg-color-secondarycontainer);
  border-radius: 6px;
  transition: all 0.3s ease-in-out;

  &__info {
    display: flex;
    align-items: center;
    gap: 8px;
  }

  &__status {
    font-size: 16px;
    animation: pulse 0.5s ease-in-out;

    &--running {
      animation: spin 1s linear infinite;
    }
  }

  &__name {
    font-size: 13px;
    color: var(--td-text-color-primary);
    font-weight: 500;
  }

  &__time {
    font-size: 12px;
    color: var(--td-text-color-secondary);
    font-family: 'Courier New', monospace;
  }
}

@keyframes pulse {
  0%, 100% {
    transform: scale(1);
  }
  50% {
    transform: scale(1.2);
  }
}

@keyframes spin {
  from {
    transform: rotate(0deg);
  }
  to {
    transform: rotate(360deg);
  }
}
</style>
