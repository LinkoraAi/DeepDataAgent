<template>
  <div class="data-table-section agent-section">
    <div class="data-table-section__header">
      <span class="data-table-section__icon">📋</span>
      <span class="data-table-section__title">数据明细 ({{ props.data.length }}行)</span>
    </div>
    <div class="data-table-section__content">
      <DataPreview :data="props.data" />
    </div>
  </div>
</template>

<script setup lang="ts">
import DataPreview from '../DataPreview.vue';

/**
 * 数据表格区块组件 — 包装现有 DataPreview 组件
 * <p>复用 DataPreview 的 CSV 导出、分页、搜索、排序功能。</p>
 */
interface Props {
  /** 查询结果数据 */
  data: Record<string, any>[];
}

const props = defineProps<Props>();
</script>

<style scoped lang="less">
.data-table-section {
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
}
</style>
