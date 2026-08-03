<template>
  <div class="error-display agent-section">
    <div class="error-display__header">
      <t-icon :name="errorIcon" class="error-display__icon" size="20px" />
      <span class="error-display__title">分析失败</span>
      <t-tag v-if="errorTypeLabel" theme="danger" variant="light" size="small">{{ errorTypeLabel }}</t-tag>
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
import type { AppError, ErrorType } from '../utils/errorHandler';
import { getErrorIcon } from '../utils/errorHandler';

/**
 * 错误展示组件 — 与卡片设计统一，显示错误分类标签和修复建议
 */
const props = defineProps<{
  error: AppError;
}>();

defineEmits<{
  (e: 'retry'): void;
}>();

const errorIcon = computed(() => getErrorIcon(props.error.type));

/** 错误类型中文标签 */
const errorTypeLabel = computed(() => {
  const labels: Record<ErrorType, string> = {
    validation: '参数错误',
    connection: '连接错误',
    timeout: '超时',
    tool_execution: '执行错误',
    model_error: '模型错误',
    data_error: '数据错误',
    unknown: '未知错误',
  };
  return labels[props.error.type] || '';
});
</script>

<style scoped lang="less">
.error-display {
  padding: 16px;
  background: var(--td-error-color-light);
  border-radius: 8px;

  &__header {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 10px;
    padding-bottom: 8px;
    border-bottom: 1px solid var(--td-error-color);
  }

  &__icon {
    color: var(--td-error-color);
  }

  &__title {
    font-weight: 600;
    color: var(--td-error-color);
    flex: 1;
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
    padding: 8px 12px;
    background: rgba(255, 255, 255, 0.5);
    border-radius: 4px;
    margin-bottom: 12px;
    line-height: 1.5;

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
