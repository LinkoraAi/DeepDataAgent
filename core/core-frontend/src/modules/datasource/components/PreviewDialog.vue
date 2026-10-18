<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { previewTableData } from '@/modules/datasource/api/metadata';
import { errorMessage } from '@/shared/composables/useAsync';
import { PREVIEW_LIMITS } from '@/modules/datasource/mappers';
import { LAYER } from '@/shared/constants/layers';
import EmptyGuide from '@/shared/components/EmptyGuide.vue';

/**
 * 表数据只读预览弹窗：10/50/100 行数切换；空结果空态；禁用数据源直接拦截并提示（后端对禁用源返回 409）。
 *
 * 层级：默认 {@link LAYER.dialogNested}（本弹窗恒由某个宿主弹层间接打开，需高于宿主）；
 * 宿主可按自身深度传入 zIndex 覆盖。TDesign 默认所有弹窗同为 2500，不抬高会被宿主盖住。
 */
const props = withDefaults(
  defineProps<{
    /** 是否可见（v-model:visible） */
    visible: boolean;
    /** 连接 id */
    connectionId: number;
    /** 表名（JDBC 表名或 API 表名） */
    tableName: string;
    /** 数据源是否启用（禁用态不发请求，直接提示） */
    enabled: boolean;
    /** 弹层层级（默认高于宿主弹窗一层） */
    zIndex?: number;
  }>(),
  { zIndex: LAYER.dialogNested },
);

const emit = defineEmits<{ 'update:visible': [boolean] }>();

const limit = ref<number>(PREVIEW_LIMITS[0]);
const rows = ref<Record<string, unknown>[]>([]);
const loading = ref(false);
const error = ref('');

/** 预览列：取所有行键并集（保持首次出现顺序）。 */
const columns = computed(() => {
  const keys: string[] = [];
  for (const row of rows.value) {
    for (const key of Object.keys(row)) {
      if (!keys.includes(key)) {
        keys.push(key);
      }
    }
  }
  return keys.map((colKey) => ({ colKey, title: colKey, ellipsis: true, cell: 'previewValue' }));
});

/** 单元格值格式化为可展示字符串（对象/数组转 JSON）。 */
function formatCell(value: unknown): string {
  if (value === null || value === undefined) {
    return '';
  }
  if (typeof value === 'object') {
    try {
      return JSON.stringify(value);
    } catch {
      return String(value);
    }
  }
  return String(value);
}

async function load(): Promise<void> {
  error.value = '';
  if (!props.enabled) {
    error.value = '数据源未启用，无法预览数据';
    rows.value = [];
    return;
  }
  loading.value = true;
  try {
    rows.value = await previewTableData(props.connectionId, props.tableName, limit.value);
  } catch (e) {
    error.value = errorMessage(e);
    rows.value = [];
  } finally {
    loading.value = false;
  }
}

watch(
  () => props.visible,
  (open) => {
    if (open) {
      limit.value = PREVIEW_LIMITS[0];
      void load();
    }
  },
);

watch(limit, () => {
  if (props.visible) {
    void load();
  }
});

function onLimitChange(v: number | string | boolean): void {
  limit.value = Number(v);
}

function onClose(): void {
  emit('update:visible', false);
}
</script>

<template>
  <t-dialog
    :visible="visible"
    :header="`数据预览：${tableName}`"
    width="720px"
    :footer="false"
    placement="center"
    attach="body"
    :z-index="zIndex"
    @update:visible="(v: boolean) => emit('update:visible', v)"
    @close="onClose"
  >
    <div class="dda-preview__toolbar">
      <span class="dda-preview__label">行数</span>
      <t-radio-group :value="limit" variant="default-filled" size="small" @change="onLimitChange">
        <t-radio-button v-for="n in PREVIEW_LIMITS" :key="n" :value="n">{{ n }}</t-radio-button>
      </t-radio-group>
    </div>
    <t-alert v-if="error" theme="warning" :message="error" class="dda-preview__alert" />
    <div v-else-if="!loading && rows.length === 0">
      <EmptyGuide description="没有可预览的数据" />
    </div>
    <t-table
      v-else
      :data="rows"
      :columns="columns"
      :loading="loading"
      :row-key="columns[0]?.colKey ?? undefined"
      size="small"
      hover
      max-height="420"
      table-layout="fixed"
    >
      <template #previewValue="{ row, col }">
        {{ formatCell(row[col.colKey]) }}
      </template>
    </t-table>
  </t-dialog>
</template>

<style scoped>
.dda-preview__toolbar {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 12px;
}

.dda-preview__label {
  font-size: 13px;
  color: var(--td-text-color-secondary, rgba(0, 0, 0, 0.6));
}

.dda-preview__alert {
  margin-bottom: 8px;
}
</style>
