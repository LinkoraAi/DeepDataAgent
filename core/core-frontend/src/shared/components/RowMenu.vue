<script setup lang="ts">
import { computed, h } from 'vue';
import type { DropdownOption } from 'tdesign-vue-next';
import { Icon as TIcon } from 'tdesign-icons-vue-next';

/**
 * 行操作「⋮」菜单（对齐 Cloud Agents 控制台）：收起态只露一个省略号按钮，
 * 展开后逐项显示图标 + 名称，危险操作（删除类）以红色呈现并以分隔线区隔。
 *
 * 实现说明：TDesign 的 t-dropdown 不渲染声明式子菜单，必须通过 options 传入，
 * 因此此处把菜单项装配为 DropdownOption（content 以渲染函数输出图标 + 文案）。
 */
export interface RowMenuItem {
  /** 动作标识（select 事件回传） */
  key: string;
  /** 动作名称 */
  label: string;
  /** TDesign 图标名 */
  icon?: string;
  /** 危险操作（红色 + 分隔线） */
  destructive?: boolean;
  /** 是否禁用 */
  disabled?: boolean;
}

const props = withDefaults(defineProps<{ items: RowMenuItem[]; tip?: string }>(), { tip: '更多操作' });

const emit = defineEmits<{ (e: 'select', key: string): void }>();

/** 菜单项：危险项用 error 主题 + 分隔线，其余为默认主题。 */
const options = computed<DropdownOption[]>(() =>
  props.items.map((item, index) => ({
    value: item.key,
    disabled: item.disabled,
    // 首个危险项前插入分隔线，形成「常规操作 / 危险操作」的视觉分区
    divider: Boolean(item.destructive) && index > 0 && !props.items[index - 1].destructive,
    theme: item.destructive ? 'error' : 'default',
    content: () =>
      h('span', { class: 'dda-rowmenu__item' }, [
        item.icon ? h(TIcon, { name: item.icon, size: '16px' }) : null,
        h('span', [item.label]),
      ]),
  })),
);

/** 下拉点击分发。 */
function onSelect(data: { value?: unknown }): void {
  emit('select', String(data?.value ?? ''));
}
</script>

<template>
  <t-dropdown :options="options" trigger="click" placement="bottom-right" @click="onSelect">
    <t-tooltip :content="tip">
      <t-button variant="text" shape="square" size="small" :aria-label="tip">
        <t-icon name="more" />
      </t-button>
    </t-tooltip>
  </t-dropdown>
</template>

<style scoped>
.dda-rowmenu__item {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
</style>