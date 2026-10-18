<script setup lang="ts">
/**
 * 表单分区容器：带标题与说明的卡片式分组。
 * 用于抽屉/表单内把字段按语义切块，降低长表单的认知负担。
 */
defineProps<{
  /** 分区标题 */
  title: string;
  /** 分区说明（可选） */
  hint?: string;
}>();
</script>

<template>
  <section class="dda-form-section">
    <header class="dda-form-section__head">
      <h4 class="dda-form-section__title">{{ title }}</h4>
      <p v-if="hint" class="dda-form-section__hint">{{ hint }}</p>
    </header>
    <slot />
  </section>
</template>

<style scoped>
.dda-form-section {
  margin-bottom: 16px;
  padding: 16px;
  background: var(--td-bg-color-secondarycontainer);
  border: 1px solid var(--dda-card-border);
  border-radius: var(--td-radius-medium);
}

.dda-form-section__head {
  margin-bottom: 12px;
}

.dda-form-section__title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--td-text-color-primary);
}

.dda-form-section__title::before {
  display: inline-block;
  width: 3px;
  height: 14px;
  background: var(--td-brand-color);
  border-radius: 2px;
  content: '';
}

.dda-form-section__hint {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--td-text-color-secondary);
}
</style>