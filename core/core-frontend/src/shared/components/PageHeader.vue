<script setup lang="ts">
/**
 * 页面标题区：可选图标徽标 + 标题 + 副标题，右侧为操作插槽。
 * 统一各业务页的"我是谁 / 能做什么"信息层级。
 */
defineProps<{
  /** 页面标题 */
  title: string;
  /** 副标题（一句话说明本页用途） */
  subtitle?: string;
  /** 可选标题图标（TDesign 图标名），渲染为品牌色徽标 */
  icon?: string;
}>();
</script>

<template>
  <div class="dda-page-header">
    <div class="dda-page-header__main">
      <span v-if="icon" class="dda-page-header__icon">
        <t-icon :name="icon" size="20px" />
      </span>
      <div class="dda-page-header__text">
        <h2 class="dda-page-header__title">{{ title }}</h2>
        <p v-if="subtitle" class="dda-page-header__subtitle">{{ subtitle }}</p>
      </div>
    </div>
    <div class="dda-page-header__actions">
      <slot name="actions" />
    </div>
  </div>
</template>

<style scoped>
.dda-page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 20px;
}

.dda-page-header__main {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.dda-page-header__icon {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  color: var(--td-brand-color);
  background: var(--td-brand-color-light);
  border-radius: 12px;
}

.dda-page-header__text {
  min-width: 0;
}

.dda-page-header__title {
  margin: 0;
  font-size: 20px;
  font-weight: 700;
  line-height: 1.3;
  color: var(--td-text-color-primary);
}

.dda-page-header__subtitle {
  margin: 3px 0 0;
  font-size: 13px;
  color: var(--td-text-color-secondary);
}

.dda-page-header__actions {
  display: flex;
  flex: none;
  gap: 8px;
  align-items: center;
}
</style>