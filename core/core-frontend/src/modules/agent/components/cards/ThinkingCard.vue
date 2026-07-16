<template>
  <BaseCard title="思维过程" icon="💭" :default-expanded="false">
    <div class="thinking-card__content">
      <div
        v-for="(step, index) in thinkingSteps"
        :key="index"
        class="thinking-card__step"
      >
        {{ step }}
      </div>
      <div v-if="thinkingSteps.length === 0" class="thinking-card__empty">
        暂无思维过程
      </div>
    </div>
  </BaseCard>
</template>

<script setup lang="ts">
import BaseCard from './BaseCard.vue';

interface Props {
  thinkingSteps: string[];
}

defineProps<Props>();
</script>

<style scoped lang="less">
.thinking-card {
  &__content {
    display: flex;
    flex-direction: column;
    gap: 12px;
  }

  &__step {
    font-size: 13px;
    line-height: 1.6;
    color: var(--td-text-color-secondary);
    padding: 8px 12px;
    background: var(--td-bg-color-secondarycontainer);
    border-radius: 6px;
    white-space: pre-wrap;
    word-break: break-word;
    animation: fadeIn 0.3s ease-in-out;
  }

  &__empty {
    color: var(--td-text-color-secondary);
    font-size: 13px;
    text-align: center;
    padding: 16px;
  }
}

@keyframes fadeIn {
  from {
    opacity: 0;
    transform: translateY(-4px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}
</style>
