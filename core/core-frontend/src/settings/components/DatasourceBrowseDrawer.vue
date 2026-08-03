<template>
  <t-drawer
    v-model:visible="drawerVisible"
    :header="drawerTitle"
    :width="600"
    :footer="false"
    @close="handleClose"
  >
    <div class="browse-drawer">
      <!-- 搜索框 -->
      <div class="browse-drawer__search">
        <t-input
          v-model="tableSearchKeyword"
          placeholder="搜索表名..."
          clearable
        >
          <template #prefix-icon>
            <t-icon name="search" />
          </template>
        </t-input>
      </div>

      <!-- 表列表 -->
      <t-loading :loading="loadingTables" text="加载中...">
        <div v-if="filteredTables.length === 0 && !loadingTables" class="browse-drawer__empty">
          <t-empty description="该数据源暂无数据表" />
        </div>
        <div v-else class="browse-drawer__table-list">
          <div
            v-for="table in filteredTables"
            :key="table.id"
            class="table-item"
          >
            <div class="table-item__info">
              <div class="table-item__name">{{ table.tableName }}</div>
              <div class="table-item__comment">{{ table.tableComment || table.description || '暂无注释' }}</div>
            </div>
            <div class="table-item__actions">
              <t-tooltip content="查看字段结构">
                <t-button theme="default" variant="text" shape="square" size="small" @click="handleShowColumns(table)">
                  <t-icon name="server" />
                </t-button>
              </t-tooltip>
              <t-tooltip content="预览数据">
                <t-button theme="default" variant="text" shape="square" size="small" @click="handleShowPreview(table)">
                  <t-icon name="view-module" />
                </t-button>
              </t-tooltip>
            </div>
          </div>
        </div>
      </t-loading>
    </div>
  </t-drawer>

  <!-- 字段结构 Modal -->
  <t-dialog
    v-model:visible="columnsModalVisible"
    :header="columnsModalTitle"
    :width="800"
    :footer="false"
    @close="handleColumnsModalClose"
  >
    <div class="columns-modal">
      <t-loading :loading="loadingColumns" text="加载中...">
        <div v-if="columns.length === 0 && !loadingColumns" class="columns-modal__empty">
          <t-empty description="该表暂无字段信息" />
        </div>
        <table v-else class="columns-table">
          <thead>
            <tr>
              <th>列名</th>
              <th>数据类型</th>
              <th>注释</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="col in columns" :key="col.id">
              <td class="columns-table__name">{{ col.columnName }}</td>
              <td class="columns-table__type">{{ col.dataType }}</td>
              <td class="columns-table__comment">{{ col.columnComment || col.columnCustomComment || '-' }}</td>
            </tr>
          </tbody>
        </table>
      </t-loading>
    </div>
  </t-dialog>

  <!-- 数据预览 Modal -->
  <t-dialog
    v-model:visible="previewModalVisible"
    :header="previewModalTitle"
    :width="1200"
    :footer="false"
    @close="handlePreviewModalClose"
  >
    <div class="preview-modal">
      <t-loading :loading="loadingPreview" text="加载中...">
        <div v-if="previewData.length === 0 && !loadingPreview" class="preview-modal__empty">
          <t-empty description="该表暂无数据" />
        </div>
        <DataPreview v-else :data="previewData" />
      </t-loading>
    </div>
  </t-dialog>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue';
import { MessagePlugin } from 'tdesign-vue-next';
import {
  listTables,
  listColumns,
  previewTableData,
  type TableResponse,
  type ColumnInfoResponse,
} from '@/shared/api/datasourceApi';
import DataPreview from '@/modules/agent/components/DataPreview.vue';

const props = defineProps<{
  visible: boolean;
  datasourceId?: number;
  datasourceName?: string;
  datasourceType?: string;
}>();

const emit = defineEmits<{
  'update:visible': [value: boolean];
}>();

const drawerVisible = ref(props.visible);

/** Drawer 标题 */
const drawerTitle = computed(() => {
  return props.datasourceName ? `数据浏览 - ${props.datasourceName}` : '数据浏览';
});

/** 表列表搜索关键字 */
const tableSearchKeyword = ref('');

/** 表列表数据 */
const tables = ref<TableResponse[]>([]);
const loadingTables = ref(false);

/** 字段结构 Modal 状态 */
const columnsModalVisible = ref(false);
const columnsModalTitle = ref('');
const columns = ref<ColumnInfoResponse[]>([]);
const loadingColumns = ref(false);

/** 数据预览 Modal 状态 */
const previewModalVisible = ref(false);
const previewModalTitle = ref('');
const previewData = ref<Record<string, any>[]>([]);
const loadingPreview = ref(false);

/** 过滤后的表列表 */
const filteredTables = computed(() => {
  if (!tableSearchKeyword.value) {
    return tables.value;
  }
  const keyword = tableSearchKeyword.value.toLowerCase();
  return tables.value.filter((t) =>
    t.tableName.toLowerCase().includes(keyword)
  );
});

/** 监听 visible 变化 */
watch(
  () => props.visible,
  (val) => {
    drawerVisible.value = val;
    if (val && props.datasourceId && props.datasourceType) {
      loadTables();
    }
  }
);

/** 同步 drawerVisible 到 props */
watch(drawerVisible, (val) => {
  emit('update:visible', val);
});

/** 加载表列表 */
async function loadTables() {
  if (!props.datasourceId || !props.datasourceType) return;
  loadingTables.value = true;
  try {
    const type = props.datasourceType === 'API' ? 'API' : 'JDBC';
    const result = await listTables(props.datasourceId, type);
    tables.value = result.list || [];
  } catch (err: any) {
    console.error('Failed to load tables:', err);
    MessagePlugin.error(err.message || '加载表列表失败');
    tables.value = [];
  } finally {
    loadingTables.value = false;
  }
}

/** 打开字段结构 Modal */
async function handleShowColumns(table: TableResponse) {
  columnsModalTitle.value = `字段结构 - ${table.tableName}`;
  columnsModalVisible.value = true;
  loadingColumns.value = true;
  try {
    const type = table.type === 'API' ? 'API' : 'JDBC';
    if (type === 'API') {
      const result = await listColumns(undefined, table.id, 'API');
      columns.value = result || [];
    } else {
      const result = await listColumns(table.id, undefined, 'JDBC');
      columns.value = result || [];
    }
  } catch (err: any) {
    console.error('Failed to load columns:', err);
    MessagePlugin.error(err.message || '加载字段信息失败');
    columns.value = [];
  } finally {
    loadingColumns.value = false;
  }
}

/** 打开数据预览 Modal */
async function handleShowPreview(table: TableResponse) {
  if (!props.datasourceId) return;
  previewModalTitle.value = `数据预览 - ${table.tableName} (前100行)`;
  previewModalVisible.value = true;
  loadingPreview.value = true;
  try {
    const type = table.type === 'API' ? 'API' : 'JDBC';
    const data = await previewTableData(props.datasourceId, table.tableName, type, 100);
    previewData.value = data || [];
  } catch (err: any) {
    console.error('Failed to preview data:', err);
    MessagePlugin.error(err.message || '数据预览失败');
    previewData.value = [];
  } finally {
    loadingPreview.value = false;
  }
}

/** 关闭字段结构 Modal */
function handleColumnsModalClose() {
  columns.value = [];
}

/** 关闭数据预览 Modal */
function handlePreviewModalClose() {
  previewData.value = [];
}

/** 关闭 Drawer */
function handleClose() {
  drawerVisible.value = false;
  tableSearchKeyword.value = '';
  tables.value = [];
}
</script>

<style scoped lang="less">
.browse-drawer {
  display: flex;
  flex-direction: column;
  height: 100%;

  &__search {
    margin-bottom: 16px;

    .t-input {
      width: 100%;
    }
  }

  &__empty {
    padding: 60px 0;
  }

  &__table-list {
    display: flex;
    flex-direction: column;
    gap: 4px;
  }
}

.table-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  border-radius: 8px;
  transition: all 0.15s;
  border: 1px solid transparent;

  &:hover {
    background: rgba(15, 23, 42, 0.03);
    border-color: rgba(15, 23, 42, 0.08);
  }

  &__info {
    flex: 1;
    display: flex;
    flex-direction: column;
    gap: 4px;
  }

  &__name {
    font-size: 14px;
    font-weight: 500;
    color: #0f172a;
  }

  &__comment {
    font-size: 12px;
    color: #94a3b8;
  }

  &__actions {
    display: flex;
    gap: 4px;
    flex-shrink: 0;
    opacity: 0;
    transition: opacity 0.2s;
  }

  &:hover &__actions {
    opacity: 1;
  }
}

.columns-modal {
  max-width: 90vw;

  &__empty {
    padding: 40px 0;
  }
}

.columns-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;

  th,
  td {
    padding: 10px 12px;
    text-align: left;
    border-bottom: 1px solid #f1f5f9;
  }

  th {
    font-weight: 600;
    color: #64748b;
    background: #f8fafc;
    font-size: 12px;
    text-transform: uppercase;
    letter-spacing: 0.03em;
  }

  td {
    color: #334155;
  }

  &__name {
    font-weight: 500;
    font-family: 'SF Mono', Monaco, Consolas, monospace;
    font-size: 12px;
  }

  &__type {
    color: #0052d9;
    font-family: 'SF Mono', Monaco, Consolas, monospace;
    font-size: 12px;
  }

  &__comment {
    color: #94a3b8;
  }
}

.preview-modal {
  &__empty {
    padding: 40px 0;
  }
}
</style>
