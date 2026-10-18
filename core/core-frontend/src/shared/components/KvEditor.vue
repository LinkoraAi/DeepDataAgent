<script setup lang="ts">
import { ref, watch } from 'vue';

/**
 * 键值对编辑器（API 请求头/查询参数/环境变量通用）。
 * v-model 为 Record<string,string>；内部以行数组编辑，输出时丢弃空键。
 */
interface KvRow {
  key: string;
  value: string;
}

const props = defineProps<{
  modelValue: Record<string, string>;
  keyPlaceholder?: string;
  valuePlaceholder?: string;
  maxRows?: number;
}>();

const emit = defineEmits<{ 'update:modelValue': [Record<string, string>] }>();

const rows = ref<KvRow[]>(toRows(props.modelValue));

watch(
  () => props.modelValue,
  (next) => {
    // 外部整体替换时同步（避免与自身输出回环）
    if (JSON.stringify(toRows(props.modelValue)) !== JSON.stringify(rows.value)) {
      rows.value = toRows(next);
    }
  },
);

watch(
  rows,
  (next) => {
    const out: Record<string, string> = {};
    for (const row of next) {
      if (row.key.trim()) {
        out[row.key.trim()] = row.value;
      }
    }
    emit('update:modelValue', out);
  },
  { deep: true },
);

function toRows(map: Record<string, string> | undefined): KvRow[] {
  return Object.entries(map ?? {}).map(([key, value]) => ({ key, value: String(value ?? '') }));
}

function addRow(): void {
  if (props.maxRows && rows.value.length >= props.maxRows) {
    return;
  }
  rows.value.push({ key: '', value: '' });
}

function removeRow(index: number): void {
  rows.value.splice(index, 1);
}
</script>

<template>
  <div class="dda-kv">
    <div v-for="(row, index) in rows" :key="index" class="dda-kv__row">
      <t-input v-model="row.key" :placeholder="keyPlaceholder || '键'" class="dda-kv__key" />
      <t-input v-model="row.value" :placeholder="valuePlaceholder || '值'" class="dda-kv__value" />
      <t-button variant="text" shape="square" theme="danger" @click="removeRow(index)">✕</t-button>
    </div>
    <t-button variant="dashed" size="small" block :disabled="maxRows !== undefined && rows.length >= maxRows" @click="addRow">
      + 添加
    </t-button>
  </div>
</template>

<style scoped>
.dda-kv__row {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
}

.dda-kv__key {
  flex: 0 0 40%;
}

.dda-kv__value {
  flex: 1 1 auto;
}
</style>
