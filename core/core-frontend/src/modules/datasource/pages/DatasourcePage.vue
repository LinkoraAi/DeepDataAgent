<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { MessagePlugin } from 'tdesign-vue-next';
import {
  deleteDatasource,
  disableDatasource,
  enableDatasource,
  getDatasource,
  listDatasourceTypes,
  listDatasources,
  syncDatasource,
  testSavedDatasource,
} from '@/modules/datasource/api/datasource';
import type { DatasourceResponse, DatasourceTypeResponse } from '@/modules/datasource/api/datasource';
import type { PrimaryTableCol } from 'tdesign-vue-next';
import { errorMessage } from '@/shared/composables/useAsync';
import { usePagePagination } from '@/shared/composables/usePagination';
import { confirmDangerous } from '@/shared/composables/useConfirm';
import { DATASOURCE_STATUS } from '@/shared/enums/dict';
import PageHeader from '@/shared/components/PageHeader.vue';
import StatusTag from '@/shared/components/StatusTag.vue';
import EmptyGuide from '@/shared/components/EmptyGuide.vue';
import RowActions from '@/shared/components/RowActions.vue';
import type { RowActionItem } from '@/shared/components/RowActions.vue';
import JdbcFormDrawer from '../components/JdbcFormDrawer.vue';
import ApiSourceDrawer from '../components/ApiSourceDrawer.vue';
import DatasourceDetailDrawer from '../components/DatasourceDetailDrawer.vue';

/**
 * 数据源管理页：筛选 + 全宽表格列表 + 右侧详情抽屉。
 * <p>列表行提供「详情」主入口（右侧抽屉展示）与「更多」行内动作（测试 / 同步 / 启用禁用 / 删除）；
 * 编辑入口收敛在详情抽屉内。启用 / 禁用 / 同步 / 删除的 400/409 后端 message 直显。
 * 密码任何位置不回显。</p>
 */
const types = ref<DatasourceTypeResponse[]>([]);
const keyword = ref('');
const filterType = ref('');
const filterStatus = ref('');
const list = ref<DatasourceResponse[]>([]);
const listLoading = ref(false);
const pager = usePagePagination<DatasourceResponse>(20);

/** 详情抽屉状态与当前数据源 */
const detailVisible = ref(false);
const selected = ref<DatasourceResponse | null>(null);

/** 新建 / 编辑抽屉状态（页面级，新建与抽屉内编辑共用） */
const jdbcDrawer = reactive<{ visible: boolean; datasource: DatasourceResponse | null }>({ visible: false, datasource: null });
const apiSourceDrawer = reactive<{ visible: boolean; datasource: DatasourceResponse | null }>({ visible: false, datasource: null });

/** 列表行动作进行中的连接ID（动作期间禁用该行更多菜单的重复触发） */
const pendingRowId = ref<number | null>(null);

const TYPE_OPTIONS = [
  { value: '', label: '全部类型' },
  { value: 'JDBC', label: 'JDBC' },
  { value: 'API', label: 'API' },
];
const STATUS_OPTIONS = [
  { value: '', label: '全部状态' },
  { value: 'ENABLED', label: '已启用' },
  { value: 'DISABLED', label: '已禁用' },
];

/** 表格列定义（操作列固定右侧，插槽名与 colKey 一致） */
const columns: PrimaryTableCol<DatasourceResponse>[] = [
  { colKey: 'name', title: '数据源名称', minWidth: 220 },
  { colKey: 'type', title: '类型', width: 170 },
  { colKey: 'description', title: '描述', minWidth: 200, ellipsis: true },
  { colKey: 'createdAt', title: '创建时间', width: 180 },
  { colKey: 'operate', title: '操作', width: 170, fixed: 'right' },
];

/** ISO 时间格式化（非法值原样回显） */
function fmtTime(iso: string | null | undefined): string {
  if (!iso) {
    return '-';
  }
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString('zh-CN', { hour12: false });
}

async function loadTypes(): Promise<void> {
  try {
    types.value = await listDatasourceTypes();
  } catch (e) {
    MessagePlugin.error(errorMessage(e));
  }
}

async function loadList(): Promise<void> {
  listLoading.value = true;
  try {
    const resp = await listDatasources({
      keyword: keyword.value || undefined,
      type: filterType.value || undefined,
      status: filterStatus.value || undefined,
      ...pager.params(),
    });
    list.value = pager.apply(resp);
  } catch (e) {
    MessagePlugin.error(errorMessage(e));
    list.value = [];
  } finally {
    listLoading.value = false;
  }
}

function applyFilter(): void {
  pager.reset();
  void loadList();
}

function onPageChange(ctx: { current: number; pageSize: number }): void {
  pager.page.value = ctx.current;
  pager.size.value = ctx.pageSize;
  void loadList();
}

/** 打开详情抽屉：先拉取最新详情，避免展示列表快照中的旧字段 */
async function openDetail(row: DatasourceResponse): Promise<void> {
  try {
    selected.value = await getDatasource(row.id);
    detailVisible.value = true;
  } catch (e) {
    MessagePlugin.error(errorMessage(e));
  }
}

function onRowClick(ctx: { row: DatasourceResponse }): void {
  if (ctx.row) {
    void openDetail(ctx.row);
  }
}

// ==================== 行内动作 ====================
async function onTest(row: DatasourceResponse): Promise<void> {
  if (pendingRowId.value !== null) {
    return;
  }
  pendingRowId.value = row.id;
  try {
    const res = await testSavedDatasource(row.id);
    if (res.success) {
      MessagePlugin.success(res.message || '连接成功');
    } else {
      MessagePlugin.error(res.message || '连接失败');
    }
  } catch (e) {
    MessagePlugin.error(errorMessage(e));
  } finally {
    pendingRowId.value = null;
  }
}

/** 行内启用 / 禁用 / 同步：成功后刷新列表与已打开的详情 */
async function runRowAction(row: DatasourceResponse, action: 'enable' | 'disable' | 'sync'): Promise<void> {
  if (pendingRowId.value !== null) {
    return;
  }
  pendingRowId.value = row.id;
  try {
    if (action === 'enable') {
      await enableDatasource(row.id);
      MessagePlugin.success('已启用');
    } else if (action === 'disable') {
      await disableDatasource(row.id);
      MessagePlugin.success('已禁用');
    } else {
      await syncDatasource(row.id);
      MessagePlugin.success('同步完成');
    }
    await loadList();
    if (selected.value?.id === row.id) {
      selected.value = await getDatasource(row.id);
    }
  } catch (e) {
    MessagePlugin.error(errorMessage(e));
  } finally {
    pendingRowId.value = null;
  }
}

async function onDelete(row: DatasourceResponse): Promise<void> {
  const confirmed = await confirmDangerous({
    header: `删除数据源：${row.name}`,
    body: '删除将级联清理其元数据与 API 表配置，操作不可撤销。',
    theme: 'danger',
    confirmText: '确认删除',
  });
  if (!confirmed) {
    return;
  }
  try {
    await deleteDatasource(row.id);
    MessagePlugin.success('已删除');
    if (selected.value?.id === row.id) {
      selected.value = null;
      detailVisible.value = false;
    }
    await loadList();
  } catch (e) {
    // 非禁用态删除 409 message 直显
    MessagePlugin.error(errorMessage(e));
  }
}

/**
 * 行内动作项：状态相关操作收敛进「更多」下拉（编辑统一进详情抽屉）。
 * DISABLED 态可启用/删除；ENABLED 态可同步/禁用。
 */
function rowActions(row: DatasourceResponse): RowActionItem[] {
  const items: RowActionItem[] = [
    { key: 'test', label: '测试', theme: 'primary', tip: '测试该数据源连接是否可用' },
  ];
  if (row.status === 'DISABLED') {
    items.push(
      { key: 'enable', label: '启用', theme: 'success', tip: '启用该数据源（启用后可同步元数据）' },
      { key: 'delete', label: '删除', theme: 'danger', tip: '删除数据源并级联清理其元数据' },
    );
    return items;
  }
  items.push(
    { key: 'sync', label: '同步', tip: '同步该数据源的元数据' },
    { key: 'disable', label: '禁用', theme: 'warning', tip: '禁用该数据源' },
  );
  return items;
}

/** 行内动作分发。 */
function onRowAction(row: DatasourceResponse, key: string): void {
  if (key === 'test') {
    void onTest(row);
    return;
  }
  if (key === 'delete') {
    void onDelete(row);
    return;
  }
  void runRowAction(row, key as 'enable' | 'disable' | 'sync');
}

// ==================== 新建 / 编辑 ====================
function openCreate(): void {
  jdbcDrawer.datasource = null;
  jdbcDrawer.visible = true;
}

function onJdbcSelectApi(): void {
  jdbcDrawer.visible = false;
  apiSourceDrawer.datasource = null;
  apiSourceDrawer.visible = true;
}

/** 详情抽屉点击编辑：按类型打开对应表单抽屉。 */
function openEditFromDetail(ds: DatasourceResponse): void {
  if (ds.type === 'API') {
    apiSourceDrawer.datasource = ds;
    apiSourceDrawer.visible = true;
  } else {
    jdbcDrawer.datasource = ds;
    jdbcDrawer.visible = true;
  }
}

/** 连接保存（新建/编辑）回调：刷新列表并打开 / 刷新详情抽屉。 */
async function onConnectionSaved(saved: DatasourceResponse): Promise<void> {
  await loadList();
  selected.value = saved;
  detailVisible.value = true;
}

/** 详情抽屉内启用 / 禁用 / 同步成功：列表刷新（抽屉内部已更新自身副本）。 */
async function onDrawerChanged(fresh: DatasourceResponse): Promise<void> {
  selected.value = fresh;
  await loadList();
}

/** 详情抽屉内删除成功：列表已由抽屉关闭，刷新列表即可。 */
async function onDrawerDeleted(): Promise<void> {
  selected.value = null;
  await loadList();
}

onMounted(async () => {
  await loadTypes();
  await loadList();
});
</script>

<template>
  <div class="dda-page">
    <PageHeader
      title="数据源管理"
      subtitle="MySQL / ClickHouse / PostgreSQL / API 四类数据源的连接、元数据与预览"
      icon="server"
    >
      <template #actions>
        <t-button theme="primary" @click="openCreate">
          <t-icon name="add" />
          新建数据源
        </t-button>
      </template>
    </PageHeader>

    <div class="dda-section ds-panel">
      <div class="dda-toolbar ds-filters">
        <t-input v-model="keyword" class="dda-toolbar__grow" placeholder="按名称搜索" clearable @enter="applyFilter">
          <template #prefix-icon><t-icon name="search" /></template>
        </t-input>
        <t-select v-model="filterType" :options="TYPE_OPTIONS" style="width: 118px" @change="applyFilter" />
        <t-select v-model="filterStatus" :options="STATUS_OPTIONS" style="width: 118px" @change="applyFilter" />
      </div>

      <EmptyGuide
        v-if="!listLoading && list.length === 0"
        description="暂无数据源，点击右上角「新建数据源」开始"
        icon="server"
      />
      <t-table
        v-else
        row-key="id"
        :data="list"
        :columns="columns"
        :loading="listLoading"
        :stripe="false"
        :hover="true"
        size="medium"
        @row-click="onRowClick"
      >
        <template #name="{ row }">
          <div class="ds-cell-name">
            <span class="ds-cell-name__text dda-ellipsis">{{ row.name }}</span>
            <StatusTag :code="row.status" :dict="DATASOURCE_STATUS" />
          </div>
        </template>
        <template #type="{ row }">
          <span class="dda-muted">
            {{ row.type }}<template v-if="row.subType"> · {{ row.subType }}</template>
          </span>
        </template>
        <template #description="{ row }">
          <span class="dda-muted">{{ row.description || '-' }}</span>
        </template>
        <template #createdAt="{ row }">
          <span class="dda-muted">{{ fmtTime(row.createdAt) }}</span>
        </template>
        <template #operate="{ row }">
          <div class="ds-cell-actions" @click.stop>
            <t-button variant="text" theme="primary" size="small" @click="openDetail(row)">详情</t-button>
            <RowActions :actions="rowActions(row)" @select="(key: string) => onRowAction(row, key)" />
          </div>
        </template>
      </t-table>

      <div class="ds-pager">
        <t-pagination
          :current="pager.page.value"
          :page-size="pager.size.value"
          :total="pager.total.value"
          :page-size-options="[20, 50, 100]"
          :show-page-size="true"
          @change="onPageChange"
        />
      </div>
    </div>

    <!-- 详情右侧抽屉（内含 API 数据表 / 浏览表 / 预览等嵌套层） -->
    <DatasourceDetailDrawer
      v-model:visible="detailVisible"
      :datasource="selected"
      :types="types"
      @edit="openEditFromDetail"
      @changed="onDrawerChanged"
      @deleted="onDrawerDeleted"
    />

    <!-- 新建 / 编辑抽屉 -->
    <JdbcFormDrawer
      v-model:visible="jdbcDrawer.visible"
      :datasource="jdbcDrawer.datasource"
      :types="types"
      @select-api="onJdbcSelectApi"
      @saved="onConnectionSaved"
    />
    <ApiSourceDrawer
      v-model:visible="apiSourceDrawer.visible"
      :datasource="apiSourceDrawer.datasource"
      @saved="onConnectionSaved"
    />
  </div>
</template>

<style scoped>
.ds-panel {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.ds-filters {
  margin-bottom: 0;
}

.ds-cell-name {
  display: flex;
  gap: 8px;
  align-items: center;
  min-width: 0;
}

.ds-cell-name__text {
  font-weight: 600;
  color: var(--td-text-color-primary);
}

.ds-cell-actions {
  display: flex;
  gap: 2px;
  align-items: center;
}

.ds-pager {
  display: flex;
  justify-content: flex-end;
}
</style>
