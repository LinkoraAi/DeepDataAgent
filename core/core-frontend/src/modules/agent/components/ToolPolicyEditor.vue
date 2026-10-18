<script setup lang="ts">
import { computed } from 'vue';
import { BUILTIN_TOOLS, TOOL_PERMISSION_POLICIES } from '@/shared/enums/dict';

/**
 * 内置工具勾选与权限策略编辑器（11 个内置工具白名单）。
 * <p>enabled_tools 语义对齐后端：勾选的工具写入白名单；<b>一个都不勾 = 不落 enabled_tools
 * = 平台全量基座暴露</b>（界面有显式提示）。每个已勾选工具行内提供权限策略下拉
 * （always_allow / always_ask / always_deny），选中值写入 configs。</p>
 * <p>本组件不含 browser_toolset / mcp_toolset / multiagent 任何入口（提交即后端 400）。</p>
 */
const props = defineProps<{
  /** 已勾选的内置工具名集合 */
  enabledTools: string[];
  /** 工具名 → 权限策略（未配置的工具不在其中） */
  policies: Record<string, string>;
}>();

const emit = defineEmits<{
  'update:enabledTools': [value: string[]];
  'update:policies': [value: Record<string, string>];
}>();

/** 策略下拉选项（字典单一真相源）。 */
const policyOptions = TOOL_PERMISSION_POLICIES.map((item) => ({ label: item.label, value: item.value }));

/** 勾选集合（Set 便于逐行判定，模板自动解包）。 */
const checkedSet = computed(() => new Set(props.enabledTools));

/** 是否已全选内置工具。 */
const isAllChecked = computed(() => BUILTIN_TOOLS.every((tool) => checkedSet.value.has(tool)));

/** 是否一个都没勾（= 平台全量语义，需要提示）。 */
const isNoneChecked = computed(() => props.enabledTools.length === 0);

/** 该工具是否已勾选。 */
function isEnabled(tool: string): boolean {
  return checkedSet.value.has(tool);
}

/** 勾选/取消勾选单个工具（取消时一并清除其策略，避免残留提交）。 */
function onToggle(tool: string, next: boolean): void {
  const list = props.enabledTools.filter((item) => item !== tool);
  if (next) {
    list.push(tool);
    emit('update:enabledTools', list);
    return;
  }
  emit('update:enabledTools', list);
  const policies = { ...props.policies };
  delete policies[tool];
  emit('update:policies', policies);
}

/** 写入/清除单工具权限策略（空值 = 用平台默认，不落 configs 条目）。 */
function onPolicyChange(tool: string, value: string): void {
  const policies = { ...props.policies };
  if (value) {
    policies[tool] = value;
  } else {
    delete policies[tool];
  }
  emit('update:policies', policies);
}

/** 全选 / 清空快捷操作。 */
function toggleAll(): void {
  emit('update:enabledTools', isAllChecked.value ? [] : [...BUILTIN_TOOLS]);
}

/** 取工具当前策略（未配置返回空串，下拉展示占位）。 */
function policyOf(tool: string): string {
  return props.policies[tool] ?? '';
}
</script>

<template>
  <div class="tool-policy">
    <div class="tool-policy__head">
      <span class="tool-policy__count">已勾选 {{ enabledTools.length }} / {{ BUILTIN_TOOLS.length }}</span>
      <t-button size="extra-small" variant="text" theme="primary" @click="toggleAll">
        {{ isAllChecked ? '全部清空' : '全部勾选' }}
      </t-button>
    </div>
    <p v-if="isNoneChecked" class="tool-policy__hint">
      未勾选任何工具：不提交 enabled_tools 白名单，运行时按平台<b>全量内置工具基座</b>暴露。
    </p>
    <ul class="tool-policy__list">
      <li v-for="tool in BUILTIN_TOOLS" :key="tool" class="tool-policy__row">
        <t-checkbox
          :checked="isEnabled(tool)"
          :label="tool"
          class="tool-policy__check"
          @change="(value: boolean) => onToggle(tool, value)"
        />
        <t-select
          v-if="isEnabled(tool)"
          :model-value="policyOf(tool)"
          :options="policyOptions"
          placeholder="平台默认策略"
          size="small"
          clearable
          class="tool-policy__select"
          @update:model-value="(value: string) => onPolicyChange(tool, value)"
        />
        <span v-else class="tool-policy__muted">不可用（未勾选）</span>
      </li>
    </ul>
  </div>
</template>

<style scoped>
.tool-policy__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 4px;
}

.tool-policy__count {
  font-size: 12px;
  color: var(--td-text-color-secondary, rgba(0, 0, 0, 0.6));
}

.tool-policy__hint {
  margin: 0 0 8px;
  padding: 6px 8px;
  font-size: 12px;
  color: var(--td-text-color-secondary, rgba(0, 0, 0, 0.6));
  background: var(--td-bg-color-container-hover, #f3f3f3);
  border-radius: 4px;
}

.tool-policy__list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.tool-policy__row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  padding: 2px 0;
}

.tool-policy__check {
  flex: 0 0 200px;
}

.tool-policy__select {
  flex: 1 1 auto;
  min-width: 200px;
}

.tool-policy__muted {
  font-size: 12px;
  color: var(--td-text-color-disabled, rgba(0, 0, 0, 0.26));
}
</style>
