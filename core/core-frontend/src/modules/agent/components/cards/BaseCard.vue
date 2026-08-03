<template>
  <div class="base-card">
    <div class="base-card__header" :class="{ 'base-card__header--clickable': collapsible }" @click="collapsible && toggleCollapse()">
      <div class="base-card__title-section">
        <span class="base-card__icon">{{ icon }}</span>
        <span class="base-card__title">{{ title }}</span>
        <span v-if="status" class="base-card__status">{{ status }}</span>
      </div>
      <t-icon v-if="collapsible" :name="isExpanded ? 'chevron-up' : 'chevron-down'" class="base-card__arrow" />
    </div>
    <div v-show="!collapsible || isExpanded" class="base-card__content">
      <slot></slot>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue';

interface Props {
  title: string;
  icon?: string;
  status?: string;
  defaultExpanded?: boolean;
  /** 是否可折叠，false 时始终展开且不可折叠 */
  collapsible?: boolean;
}

const props = withDefaults(defineProps<Props>(), {
  icon: '📊',
  defaultExpanded: true,
  collapsible: true,
});

const isExpanded = ref(props.defaultExpanded);

function toggleCollapse() {
  isExpanded.value = !isExpanded.value;
}
</script>

<style scoped lang="less">
.base-card {
  border: 1px solid var(--td-border-level-1-color);
  border-radius: 8px;
  background: var(--td-bg-color-container);
  overflow: hidden;

  &__header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 12px 16px;
    background: var(--td-bg-color-secondarycontainer);
    cursor: pointer;
    user-select: none;
    transition: background-color 0.2s;

    &:hover {
      background: var(--td-bg-color-secondarycontainer-hover);
    }
  }

  &__title-section {
    display: flex;
    align-items: center;
    gap: 8px;
  }

  &__icon {
    font-size: 16px;
  }

  &__title {
    font-weight: 600;
    font-size: 14px;
    color: var(--td-text-color-primary);
  }

  &__status {
    font-size: 12px;
    color: var(--td-text-color-secondary);
    margin-left: 8px;
  }

  &__arrow {
    color: var(--td-text-color-secondary);
    transition: transform 0.3s ease-in-out;
  }

  &__content {
    padding: 16px;
    animation: fadeIn 0.3s ease-in-out;
  }
}

@keyframes fadeIn {
  from {
    opacity: 0;
  }
  to {
    opacity: 1;
  }
}
</style>
