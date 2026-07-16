<template>
  <div class="error-display">
    <div class="error-display__header">
      <t-icon :name="errorIcon" class="error-display__icon" />
      <span class="error-display__title">分析失败</span>
    </div>
    <div class="error-display__message">{{ error.message }}</div>
    <div v-if="error.suggestion" class="error-display__suggestion">
      <span class="error-display__suggestion-label">💡 建议：</span>
      {{ error.suggestion }}
    </div>
    <div v-if="error.retryable" class="error-display__actions">
      <t-button theme="primary" variant="outline" size="small" @click="$emit('retry')">
        <template #icon><refresh-icon /></template>
        重试
      </t-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { RefreshIcon } from 'tdesign-icons-vue-next';
import type { AppError } from '../utils/errorHandler';
import { getErrorIcon } from '../utils/errorHandler';

const props = defineProps<{
  error: AppError;
}>();

defineEmits<{
  (e: 'retry'): void;
}>();

const errorIcon = computed(() => getErrorIcon(props.error.type));
</script>

<style scoped lang="less">
.error-display {
  padding: 16px;
  background: var(--td-error-color-light);
  border: 1px solid var(--td-error-color);
  border-radius: 8px;
  margin-top: 8px;

  &__header {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 8px;
  }

  &__icon {
    color: var(--td-error-color);
    font-size: 18px;
  }

  &__title {
    font-weight: 600;
    color: var(--td-error-color);
  }

  &__message {
    font-size: 14px;
    color: var(--td-text-color-primary);
    margin-bottom: 8px;
    line-height: 1.5;
  }

  &__suggestion {
    font-size: 13px;
    color: var(--td-text-color-secondary);
    padding: 8px;
    background: rgba(255, 255, 255, 0.5);
    border-radius: 4px;
    margin-bottom: 12px;

    &-label {
      font-weight: 500;
    }
  }

  &__actions {
    display: flex;
    gap: 8px;
  }
}
</style>
