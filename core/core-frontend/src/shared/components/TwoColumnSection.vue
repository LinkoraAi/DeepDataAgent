<script setup lang="ts">
/**
 * 双栏分区表单（对齐 Cloud Agents 控制台「创建智能体」）：
 * 左侧为分区标题 + 说明，右侧为字段/内容区，分区之间以细分割线分隔。
 * 用于把长表单切成语义清晰、可扫读的段落，避免单列字段堆叠。
 */
defineProps<{
  /** 分区标题 */
  title: string;
  /** 分区说明（可选，左侧标题下方的小字） */
  description?: string;
  /** 分区右上角附加信息（如「已选 0 / 50」） */
  meta?: string;
}>();
</script>

<template>
  <section class="dda-twocol">
    <aside class="dda-twocol__aside">
      <h3 class="dda-twocol__title">{{ title }}</h3>
      <p v-if="description" class="dda-twocol__desc">{{ description }}</p>
      <slot name="aside" />
      <p v-if="meta" class="dda-twocol__meta">{{ meta }}</p>
    </aside>
    <div class="dda-twocol__main">
      <slot />
    </div>
  </section>
</template>

<style scoped>
.dda-twocol {
  display: grid;
  grid-template-columns: 200px minmax(0, 1fr);
  gap: 24px;
  padding: 20px 0;
  border-bottom: 1px solid var(--td-component-border);
}

.dda-twocol:last-child {
  border-bottom: none;
}

.dda-twocol__title {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--td-text-color-primary);
}

.dda-twocol__desc {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--td-text-color-secondary);
}

.dda-twocol__meta {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--td-text-color-placeholder);
}

.dda-twocol__main {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-width: 0;
}

/* 窄屏（抽屉/移动端）回落为单列 */
@media (max-width: 900px) {
  .dda-twocol {
    grid-template-columns: minmax(0, 1fr);
    gap: 12px;
  }
}
</style>