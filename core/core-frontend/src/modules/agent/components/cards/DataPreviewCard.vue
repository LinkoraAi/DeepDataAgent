<template>
  <BaseCard
    :title="`查询结果 (${data.length} 行)`"
    icon="📋"
    :default-expanded="true"
  >
    <div class="data-preview-card__content">
      <DataPreview v-if="data.length > 0" :data="data" />
      <div v-else class="data-preview-card__empty">
        暂无查询结果
      </div>
    </div>
  </BaseCard>
</template>

<script setup lang="ts">
import DataPreview from '../DataPreview.vue';
import BaseCard from './BaseCard.vue';

interface Props {
  data: Record<string, any>[];
}

defineProps<Props>();
</script>

<style scoped lang="less">
.data-preview-card {
  &__content {
    overflow-x: auto;
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
