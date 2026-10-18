<script setup lang="ts">
import { computed } from 'vue';
import JsonEditor from '@/shared/components/JsonEditor.vue';
import { MAX_TOOL_ENTRIES, createEmptyCustomTool } from '../mappers';
import type { CustomToolDraft, CustomToolErrors } from '../mappers';

/**
 * 自定义工具编辑器（可增删卡片：name / description / input_schema）。
 * <p>校验口径对齐后端 AgentTool custom 约束：名称必填且不得与内置工具重名、不得占用保留名 advisor、
 * 不得含 mcp__ 前缀或保留分隔符 __；描述必填；input_schema 必须是 type=object 的 JSON Schema。
 * 错误文案由上层 validateDraft 经 errors 传入（提交尝试后展示）。</p>
 */
const props = defineProps<{
  /** 自定义工具草稿列表（v-model） */
  modelValue: CustomToolDraft[];
  /** 字段级错误（数组下标 → 错误） */
  errors?: Record<number, CustomToolErrors>;
}>();

const emit = defineEmits<{
  'update:modelValue': [value: CustomToolDraft[]];
}>();

/** 是否已达工具条目总量上限（后端 tools ≤128）。 */
const isMaxed = computed(() => props.modelValue.length >= MAX_TOOL_ENTRIES);

/** 整体回写（不就地修改 props）。 */
function write(next: CustomToolDraft[]): void {
  emit('update:modelValue', next);
}

/** 新增一张卡片（默认给出 object 骨架 Schema）。 */
function onAdd(): void {
  if (props.modelValue.length >= MAX_TOOL_ENTRIES) {
    return;
  }
  write([...props.modelValue, createEmptyCustomTool()]);
}

/** 删除指定卡片。 */
function onRemove(index: number): void {
  write(props.modelValue.filter((_item, itemIndex) => itemIndex !== index));
}

/** 局部更新指定卡片字段。 */
function patch(index: number, changes: Partial<CustomToolDraft>): void {
  write(props.modelValue.map((item, itemIndex) => (itemIndex === index ? { ...item, ...changes } : item)));
}

/** 取指定卡片的字段错误文案。 */
function errorOf(index: number, field: keyof CustomToolErrors): string | undefined {
  return props.errors?.[index]?.[field];
}
</script>

<template>
  <div class="custom-tool">
    <p v-if="!modelValue.length" class="custom-tool__empty">
      暂无自定义工具。自定义工具以 JSON Schema 描述入参，会话中由模型按名称调用（不与内置工具重名）。
    </p>
    <div v-for="(tool, index) in modelValue" :key="index" class="custom-tool__card">
      <div class="custom-tool__card-head">
        <span class="custom-tool__index">自定义工具 {{ index + 1 }}</span>
        <t-button size="extra-small" variant="text" theme="danger" @click="onRemove(index)">删除</t-button>
      </div>
      <t-form-item label="名称" :status="errorOf(index, 'name') ? 'error' : undefined" :tips="errorOf(index, 'name')">
        <t-input
          :value="tool.name"
          placeholder="如 query_orders（不得含 __，不得用 mcp__ 前缀或 advisor）"
          clearable
          @change="(value: string) => patch(index, { name: value })"
        />
      </t-form-item>
      <t-form-item label="描述" :status="errorOf(index, 'description') ? 'error' : undefined" :tips="errorOf(index, 'description')">
        <t-textarea
          :value="tool.description"
          placeholder="供模型理解用途的能力说明"
          :autosize="{ minRows: 2, maxRows: 4 }"
          @change="(value: string) => patch(index, { description: value })"
        />
      </t-form-item>
      <t-form-item label="输入 Schema" :status="errorOf(index, 'inputSchema') ? 'error' : undefined" :tips="errorOf(index, 'inputSchema')">
        <JsonEditor
          :model-value="tool.inputSchemaJson"
          :rows="6"
          validate
          placeholder='{"type":"object","properties":{"sql":{"type":"string"}},"required":["sql"]}'
          @update:model-value="(value: string) => patch(index, { inputSchemaJson: value })"
        />
      </t-form-item>
    </div>
    <t-button variant="dashed" size="small" block :disabled="modelValue.length >= MAX_TOOL_ENTRIES" @click="onAdd">
      + 添加自定义工具{{ isMaxed ? `（已达 ${MAX_TOOL_ENTRIES} 条上限）` : '' }}
    </t-button>
  </div>
</template>

<style scoped>
.custom-tool__empty {
  margin: 0 0 8px;
  font-size: 12px;
  color: var(--td-text-color-secondary, rgba(0, 0, 0, 0.6));
}

.custom-tool__card {
  margin-bottom: 12px;
  padding: 12px;
  border: 1px dashed var(--td-component-border, #e7e7e7);
  border-radius: 6px;
}

.custom-tool__card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.custom-tool__index {
  font-size: 13px;
  font-weight: 600;
  color: var(--td-text-color-primary, rgba(0, 0, 0, 0.9));
}
</style>
