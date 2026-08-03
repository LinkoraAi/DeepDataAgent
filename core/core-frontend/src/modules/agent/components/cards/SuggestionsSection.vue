<template>
  <div v-if="props.suggestions.length > 0" class="suggestions-section agent-section">
    <div class="suggestions-section__header">
      <span class="suggestions-section__icon">💡</span>
      <span class="suggestions-section__title">你可能还想了解：</span>
    </div>
    <div class="suggestions-section__list">
      <button
        v-for="(suggestion, index) in props.suggestions"
        :key="index"
        class="suggestion-chip"
        @click="handleSelect(suggestion)"
      >
        {{ suggestion.text }}
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import type { Suggestion } from '../../types';
import { useUiStore } from '@/shared/stores/ui';

/**
 * 建议追问区块组件 — 分析完成后显示建议的后续问题
 * <p>点击建议按钮后，通过 uiStore.setSuggestion 填充输入框并发送。</p>
 */
interface Props {
  /** 建议追问列表 */
  suggestions: Suggestion[];
}

const props = defineProps<Props>();

const emit = defineEmits<{
  (e: 'select', payload: Suggestion): void;
}>();

const uiStore = useUiStore();

/** 处理建议点击 — emit select 并填充输入框 */
function handleSelect(suggestion: Suggestion) {
  emit('select', suggestion);
  uiStore.setSuggestion(suggestion.text);
}
</script>

<style scoped lang="less">
.suggestions-section {
  &__header {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 8px;
  }

  &__icon {
    font-size: 16px;
  }

  &__title {
    font-weight: 600;
    font-size: 14px;
    color: var(--td-text-color-primary);
  }

  &__list {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
  }
}

.suggestion-chip {
  padding: 6px 14px;
  border-radius: 16px;
  border: 1px solid var(--td-brand-color);
  background: var(--td-brand-color-light);
  color: var(--td-brand-color);
  font-size: 13px;
  cursor: pointer;
  transition: all 0.2s;

  &:hover {
    background: var(--td-brand-color);
    color: white;
  }
}
</style>
