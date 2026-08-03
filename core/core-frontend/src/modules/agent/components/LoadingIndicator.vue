<template>
  <div class="loading-indicator">
    <t-loading size="small" />
    <span class="loading-indicator__label">分析中...</span>
  </div>
</template>

<script setup lang="ts">
/**
 * 加载指示器组件 — 分析中转圈等待
 * <p>仅在分析进行中显示，不展示任何中间状态细节。</p>
 */
</script>

<style scoped lang="less">
.loading-indicator {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 16px;
  background: var(--td-bg-color-secondarycontainer);
  border-radius: 8px;

  &__label {
    font-size: 13px;
    color: var(--td-text-color-secondary);
  }
}
</style>
