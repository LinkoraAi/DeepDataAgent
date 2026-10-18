<script setup lang="ts">
import { computed } from 'vue';
import type { DropdownOption } from 'tdesign-vue-next';

/**
 * 行/卡片操作区：主操作内联展示，其余操作收进"更多"下拉。
 * <p>设计意图：按钮"按需出现"——默认只露出最关键的一个动作，
 * 其余动作隐藏在下拉里，展开时才显示各自的名称，避免一长串按钮造成的视觉噪音。</p>
 */

/** 单个操作项定义 */
export interface RowActionItem {
  /** 动作标识（select 事件回传） */
  key: string;
  /** 动作名称（使用时展示） */
  label: string;
  /** 文字语义色，默认 default */
  theme?: 'default' | 'primary' | 'success' | 'warning' | 'danger';
  /** 是否禁用 */
  disabled?: boolean;
  /** 悬浮说明（补充按钮的具体作用） */
  tip?: string;
}

const props = withDefaults(
  defineProps<{
    /** 操作项列表（按顺序：前 maxInline 个内联，其余进"更多"） */
    actions: RowActionItem[];
    /** 内联展示的主操作数量 */
    maxInline?: number;
    /** "更多"触发器的悬浮说明 */
    moreTip?: string;
  }>(),
  { maxInline: 1, moreTip: '更多操作' },
);

const emit = defineEmits<{ (e: 'select', key: string): void }>();

/** 内联展示的主操作 */
const inlineActions = computed(() => props.actions.slice(0, props.maxInline));
/** 收进下拉的次级操作 */
const moreActions = computed(() => props.actions.slice(props.maxInline));

/** 下拉选项：content 即操作名称（展开时显示名称与作用） */
const moreOptions = computed<DropdownOption[]>(() =>
  moreActions.value.map((action) => ({
    content: action.label,
    value: action.key,
    disabled: action.disabled,
  })),
);

function select(key: string): void {
  emit('select', key);
}

function onMore(item: DropdownOption): void {
  select(String(item.value ?? ''));
}
</script>

<template>
  <span class="row-actions">
    <template v-for="action in inlineActions" :key="action.key">
      <t-tooltip :content="action.tip" :disabled="!action.tip">
        <t-button
          variant="text"
          size="small"
          :theme="action.theme || 'default'"
          :disabled="action.disabled"
          @click="select(action.key)"
        >
          {{ action.label }}
        </t-button>
      </t-tooltip>
    </template>

    <t-dropdown v-if="moreActions.length" :options="moreOptions" trigger="click" @click="onMore">
      <t-tooltip :content="moreTip">
        <t-button variant="text" shape="square" size="small" :aria-label="moreTip">
          <t-icon name="ellipsis" />
        </t-button>
      </t-tooltip>
    </t-dropdown>
  </span>
</template>

<style scoped>
.row-actions {
  display: inline-flex;
  align-items: center;
  gap: 2px;
}
</style>