<script setup lang="ts">
/**
 * 空态引导：图标 + 文案 + 可选行动区（如"去创建环境"）。
 * 用于列表无数据、详情未选中、加载失败等场景，避免大片留白带来的方向感缺失。
 */
defineProps<{
  /** 空态说明文案 */
  description: string;
  /** 可选空态图标（TDesign 图标名） */
  icon?: string;
}>();
</script>

<template>
  <div class="dda-empty">
    <span class="dda-empty__icon">
      <t-icon :name="icon || 'info-circle'" size="24px" />
    </span>
    <p class="dda-empty__text">{{ description }}</p>
    <div v-if="$slots.action" class="dda-empty__action">
      <slot name="action" />
    </div>
  </div>
</template>

<style scoped>
.dda-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  padding: 52px 20px;
  text-align: center;
}

.dda-empty__icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 52px;
  height: 52px;
  margin-bottom: 6px;
  color: var(--td-brand-color-5);
  background: var(--td-brand-color-1);
  border-radius: 16px;
}

.dda-empty__text {
  max-width: 460px;
  margin: 0;
  font-size: 13px;
  color: var(--td-text-color-secondary);
}

.dda-empty__action {
  margin-top: 12px;
}
</style>