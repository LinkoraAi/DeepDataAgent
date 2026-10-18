<script setup lang="ts">
import { computed } from 'vue';

/**
 * JSON 文本编辑器（Raw 视图/自定义工具 Schema/前置操作 body）。
 * v-model 输出字符串；提供格式校验状态与"格式化"按钮。
 */
const props = defineProps<{
  modelValue: string;
  placeholder?: string;
  rows?: number;
  /** 是否要求合法 JSON（显示错误条） */
  validate?: boolean;
}>();

const emit = defineEmits<{ 'update:modelValue': [string] }>();

const error = computed(() => {
  if (!props.validate || !props.modelValue) {
    return '';
  }
  try {
    JSON.parse(props.modelValue);
    return '';
  } catch (e) {
    return e instanceof Error ? e.message : 'JSON 格式错误';
  }
});

function onInput(value: string): void {
  emit('update:modelValue', value);
}

function format(): void {
  try {
    emit('update:modelValue', JSON.stringify(JSON.parse(props.modelValue || '{}'), null, 2));
  } catch {
    // 非法 JSON 不做处理，错误条已提示
  }
}
</script>

<template>
  <div>
    <t-textarea
      :model-value="modelValue"
      :placeholder="placeholder || '{}'"
      :autosize="{ minRows: rows || 6 }"
      class="dda-json-editor"
      @update:model-value="onInput"
    />
    <div class="dda-json-editor__bar">
      <t-button size="extra-small" variant="text" @click="format">格式化</t-button>
      <span v-if="error" class="dda-json-editor__error">JSON 非法：{{ error }}</span>
    </div>
  </div>
</template>

<style scoped>
.dda-json-editor :deep(textarea) {
  font-family: 'JetBrains Mono', Consolas, 'Courier New', monospace;
  font-size: 12px;
}

.dda-json-editor__bar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 4px;
}

.dda-json-editor__error {
  font-size: 12px;
  color: var(--td-error-color, #d54941);
}
</style>
