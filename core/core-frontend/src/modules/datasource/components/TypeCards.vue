<script setup lang="ts">
import { computed } from 'vue';
import type { DatasourceTypeResponse } from '@/modules/datasource/api/datasource';
import { subTypeOptions } from '@/modules/datasource/mappers';

/**
 * 数据源类型选择卡（4 张：MySQL / PostgreSQL / ClickHouse / API，按 OLTP/OLAP/API 分组）。
 * 选中通过 v-model 传出 subType；点击同一卡不再重复触发 select。
 */
const props = defineProps<{
  /** 后端 /types 返回的类型清单；空或未提供时回落静态 */
  types?: DatasourceTypeResponse[];
  /** 当前选中的 subType */
  modelValue?: string | null;
}>();

const emit = defineEmits<{
  'update:modelValue': [string];
  select: [DatasourceTypeResponse];
}>();

/** 分类展示名。 */
const CATEGORY_LABELS: Record<string, string> = {
  OLTP: 'OLTP · 事务型',
  OLAP: 'OLAP · 分析型',
  API: 'API · 接口型',
};

const grouped = computed<Array<{ category: string; label: string; items: DatasourceTypeResponse[] }>>(() => {
  const list = subTypeOptions(props.types);
  const order = ['OLTP', 'OLAP', 'API'];
  const map = new Map<string, DatasourceTypeResponse[]>();
  for (const t of list) {
    const arr = map.get(t.category) ?? [];
    arr.push(t);
    map.set(t.category, arr);
  }
  return order
    .filter((c) => map.has(c))
    .map((c) => ({ category: c, label: CATEGORY_LABELS[c] ?? c, items: map.get(c) ?? [] }));
});

function onPick(item: DatasourceTypeResponse): void {
  if (item.subType === props.modelValue) {
    return;
  }
  emit('update:modelValue', item.subType);
  emit('select', item);
}
</script>

<template>
  <div class="dda-type-cards">
    <div v-for="group in grouped" :key="group.category" class="dda-type-cards__group">
      <div class="dda-type-cards__group-title">{{ group.label }}</div>
      <div class="dda-type-cards__row">
        <button
          v-for="item in group.items"
          :key="item.subType"
          type="button"
          class="dda-type-card"
          :class="{ 'is-active': item.subType === modelValue }"
          @click="onPick(item)"
        >
          <div class="dda-type-card__name">{{ item.name }}</div>
          <div v-if="item.defaultPort" class="dda-type-card__port">默认端口 {{ item.defaultPort }}</div>
          <div v-else class="dda-type-card__port">接口型 · 无端口</div>
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.dda-type-cards__group {
  margin-bottom: 16px;
}

.dda-type-cards__group-title {
  margin-bottom: 8px;
  font-size: 12px;
  color: var(--td-text-color-secondary, rgba(0, 0, 0, 0.6));
}

.dda-type-cards__row {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}

.dda-type-card {
  min-width: 160px;
  padding: 12px 16px;
  text-align: left;
  cursor: pointer;
  background: var(--td-bg-color-container, #fff);
  border: 1px solid var(--td-component-border, #e7e7e7);
  border-radius: 6px;
  transition: all 0.15s;
}

.dda-type-card:hover {
  border-color: var(--td-brand-color);
}

.dda-type-card.is-active {
  border-color: var(--td-brand-color);
  box-shadow: 0 0 0 2px var(--td-brand-color-focus);
}

.dda-type-card__name {
  font-size: 14px;
  font-weight: 600;
}

.dda-type-card__port {
  margin-top: 4px;
  font-size: 12px;
  color: var(--td-text-color-secondary, rgba(0, 0, 0, 0.6));
}
</style>
