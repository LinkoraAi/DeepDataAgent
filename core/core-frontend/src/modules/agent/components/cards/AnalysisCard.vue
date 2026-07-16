<template>
  <BaseCard title="分析报告" icon="📝" :default-expanded="true">
    <div class="analysis-card__content">
      <div v-if="report" class="analysis-card__report" v-html="renderedReport"></div>
      <div v-else class="analysis-card__empty">
        暂无分析报告
      </div>
    </div>
  </BaseCard>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { renderMarkdown } from '@/shared/utils/markdown';
import BaseCard from './BaseCard.vue';

interface Props {
  report: string;
}

const props = defineProps<Props>();

const renderedReport = computed(() => {
  return props.report ? renderMarkdown(props.report) : '';
});
</script>

<style scoped lang="less">
.analysis-card {
  &__content {
    line-height: 1.8;
  }

  &__report {
    :deep(h1),
    :deep(h2),
    :deep(h3) {
      margin-top: 16px;
      margin-bottom: 8px;
      font-weight: 600;
    }

    :deep(p) {
      margin-bottom: 8px;
    }

    :deep(ul),
    :deep(ol) {
      padding-left: 24px;
      margin-bottom: 8px;
    }

    :deep(code) {
      background: var(--td-bg-color-secondarycontainer);
      padding: 2px 6px;
      border-radius: 4px;
      font-family: 'Courier New', monospace;
      font-size: 13px;
    }

    :deep(pre) {
      background: var(--td-bg-color-secondarycontainer);
      padding: 16px;
      border-radius: 6px;
      overflow-x: auto;
      margin: 12px 0;

      code {
        background: transparent;
        padding: 0;
      }
    }
  }

  &__empty {
    display: flex;
    align-items: center;
    justify-content: center;
    height: 200px;
    color: var(--td-text-color-secondary);
    font-size: 13px;
  }
}
</style>
