<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { MessagePlugin } from 'tdesign-vue-next';
import EmptyGuide from '@/shared/components/EmptyGuide.vue';
import PageHeader from '@/shared/components/PageHeader.vue';
import StatusTag from '@/shared/components/StatusTag.vue';
import RowActions from '@/shared/components/RowActions.vue';
import type { RowActionItem } from '@/shared/components/RowActions.vue';
import { errorMessage, useAsync } from '@/shared/composables/useAsync';
import { usePagePagination } from '@/shared/composables/usePagination';
import { confirmDangerous } from '@/shared/composables/useConfirm';
import { MODEL_PROFILE_STATUS, MODEL_TYPES } from '@/shared/enums/dict';
import type { PaginatedResp } from '@/shared/types/api';
import { deleteModelProfile, disableModelProfile, enableModelProfile, listModelProfiles } from '../api';
import type { ModelProfileResponse } from '../api';
import { modelTypeLabel } from '../mappers';
import ModelFormDrawer from '../components/ModelFormDrawer.vue';

/**
 * 模型配置列表页：后端页码分页（keyword/status/page/size）+ 关键字搜索、类型与状态筛选。
 * 类型筛选说明：后端列表无 modelType 查询参数，故类型过滤作用于当前页数据（管理端量级可接受）。
 */
const rows = ref<ModelProfileResponse[]>([]);
const keyword = ref('');
const modelType = ref<number | ''>('');
const status = ref('');
const drawerVisible = ref(false);
const editing = ref<ModelProfileResponse | null>(null);

const pager = usePagePagination<ModelProfileResponse>(20);
const page = pager.page;
const size = pager.size;
const total = pager.total;

const listTask = useAsync<PaginatedResp<ModelProfileResponse>>();
const actionTask = useAsync<void>();

/** 列表加载中（顶层 ref，模板自动解包） */
const loading = listTask.loading;
/** 行操作中 */
const acting = actionTask.loading;

/** 状态筛选选项（ENABLED/DISABLED 大写枚举名） */
const statusOptions = Object.entries(MODEL_PROFILE_STATUS).map(([value, item]) => ({ label: item.label, value }));

/** 类型筛选选项（值空串表示全部；后端无 modelType 查询参数，见顶部说明） */
const typeOptions: Array<{ label: string; value: number | '' }> = [
  { label: '全部类型', value: '' },
  ...MODEL_TYPES.map((item) => ({ label: item.label, value: item.value })),
];

/** 当前页按类型过滤后的展示行 */
const visibleRows = computed<ModelProfileResponse[]>(() =>
  modelType.value === '' ? rows.value : rows.value.filter((item) => item.modelType === modelType.value),
);

/** 时间文本收敛到分钟（后端为 ISO-8601 带时区串）。 */
function toMinute(value: string | null | undefined): string {
  if (!value) {
    return '-';
  }
  return value.slice(0, 16).replace('T', ' ');
}

const columns = [
  { colKey: 'displayName', title: '显示名称', width: 150, ellipsis: true },
  { colKey: 'apiFormat', title: 'API 格式', width: 110 },
  { colKey: 'modelName', title: '模型名称', width: 160, ellipsis: true },
  { colKey: 'apiEndpointUrl', title: 'API 端点', width: 220, ellipsis: true },
  { colKey: 'modelType', title: '类型', width: 90, cell: 'modelType' },
  { colKey: 'credentialConfigured', title: '凭证', width: 90, cell: 'credential' },
  { colKey: 'status', title: '状态', width: 100, cell: 'status' },
  { colKey: 'updatedAt', title: '更新时间', width: 150, cell: 'updatedAt' },
  { colKey: 'action', title: '操作', width: 130, cell: 'action' },
];

async function load(): Promise<void> {
  const resp = await listTask.run(() =>
    listModelProfiles({
      keyword: keyword.value.trim() || undefined,
      status: status.value || undefined,
      page: page.value,
      size: size.value,
    }),
  );
  if (resp) {
    rows.value = pager.apply(resp);
  }
}

/** 筛选条件变更后回到第一页重查 */
function reloadFromFirstPage(): Promise<void> {
  pager.reset();
  return load();
}

function onSearch(): Promise<void> {
  return reloadFromFirstPage();
}

function onFilterChange(): Promise<void> {
  return reloadFromFirstPage();
}

function onPageChange(context: { current: number; pageSize: number }): void {
  page.value = context.current;
  size.value = context.pageSize;
  void load();
}

function onCreate(): void {
  editing.value = null;
  drawerVisible.value = true;
}

function onEdit(row: ModelProfileResponse): void {
  editing.value = row;
  drawerVisible.value = true;
}

function onDrawerSaved(): void {
  void load();
}

/** 统一执行行操作：失败直显后端 message（删除被引用时为 409 冲突原因）。 */
async function runAction(action: () => Promise<void>, successText: string): Promise<void> {
  await actionTask.run(action);
  if (actionTask.error.value) {
    MessagePlugin.error(errorMessage(actionTask.error.value));
    return;
  }
  MessagePlugin.success(successText);
  void load();
}

function onToggleStatus(row: ModelProfileResponse): Promise<void> {
  const enabling = row.status !== 'ENABLED';
  return runAction(
    () => (enabling ? enableModelProfile(row.profileId) : disableModelProfile(row.profileId)),
    enabling ? '已启用' : '已禁用',
  );
}

async function onDelete(row: ModelProfileResponse): Promise<void> {
  const confirmed = await confirmDangerous({
    header: '删除模型配置',
    body: `即将删除「${row.displayName}」（${row.modelName}），删除后不可恢复；仍被智能体版本引用时将删除失败。`,
  });
  if (!confirmed) {
    return;
  }
  await runAction(() => deleteModelProfile(row.profileId), '模型配置已删除');
}

/**
 * 行操作项：常用「编辑」内联，启用/禁用与删除收进「更多」下拉。
 * 启用/禁用名称随当前状态切换，语义与原有按钮一致。
 */
function rowActions(row: ModelProfileResponse): RowActionItem[] {
  const enabling = row.status !== 'ENABLED';
  return [
    { key: 'edit', label: '编辑', theme: 'primary', tip: '修改该模型配置' },
    {
      key: 'toggle',
      label: enabling ? '启用' : '禁用',
      theme: enabling ? 'success' : 'warning',
      tip: enabling ? '启用该模型配置' : '停用该模型配置',
    },
    { key: 'delete', label: '删除', theme: 'danger', tip: '删除后不可恢复，被智能体版本引用时将失败' },
  ];
}

/** 行操作分发：复用既有处理函数，语义与行为不变。 */
function onRowAction(row: ModelProfileResponse, key: string): void {
  if (key === 'edit') {
    onEdit(row);
    return;
  }
  if (key === 'toggle') {
    void onToggleStatus(row);
    return;
  }
  if (key === 'delete') {
    void onDelete(row);
  }
}

onMounted(() => {
  void load();
});
</script>

<template>
  <div class="dda-page">
    <PageHeader
      title="模型配置"
      subtitle="对话与嵌入模型的接入凭证统一在此维护；凭证加密落库，界面不回显"
      icon="layers"
    >
      <template #actions>
        <t-button theme="primary" @click="onCreate">
          <t-icon name="add" />
          新建模型配置
        </t-button>
      </template>
    </PageHeader>

    <div class="dda-toolbar">
      <t-input
        v-model="keyword"
        class="dda-toolbar__grow"
        placeholder="按显示名称搜索"
        clearable
        @enter="onSearch"
      >
        <template #prefix-icon><t-icon name="search" /></template>
      </t-input>
      <t-select v-model="modelType" :options="typeOptions" style="width: 160px" @change="onFilterChange" />
      <t-select
        v-model="status"
        :options="statusOptions"
        placeholder="全部状态"
        clearable
        style="width: 160px"
        @change="onFilterChange"
      />
      <t-button theme="primary" variant="outline" :loading="loading" @click="onSearch">
        <t-icon name="search" />
        查询
      </t-button>
    </div>

    <div class="dda-section model-section">
      <t-table
        row-key="profileId"
        :data="visibleRows"
        :columns="columns"
        :loading="loading || acting"
        hover
        size="medium"
        table-layout="fixed"
        max-height="max(340px, calc(100vh - 340px))"
      >
        <template #modelType="{ row }">{{ modelTypeLabel(row.modelType) }}</template>
        <template #credential="{ row }">
          <t-tag :theme="row.credentialConfigured ? 'success' : 'default'" variant="light" size="small">
            {{ row.credentialConfigured ? '已配置' : '未配置' }}
          </t-tag>
        </template>
        <template #status="{ row }">
          <StatusTag :code="row.status" :dict="MODEL_PROFILE_STATUS" />
        </template>
        <template #updatedAt="{ row }">{{ toMinute(row.updatedAt) }}</template>
        <template #action="{ row }">
          <RowActions :actions="rowActions(row)" @select="(key: string) => onRowAction(row, key)" />
        </template>
        <template #empty>
          <EmptyGuide
            description="还没有模型配置，先创建对话模型与嵌入模型，智能体与知识库才能引用"
            icon="layers"
          >
            <template #action>
              <t-button theme="primary" variant="outline" size="small" @click="onCreate">新建模型配置</t-button>
            </template>
          </EmptyGuide>
        </template>
      </t-table>

      <div class="model-pager">
        <t-pagination
          :current="page"
          :page-size="size"
          :total="total"
          :page-size-options="[10, 20, 50, 100]"
          :show-jumper="false"
          @change="onPageChange"
        />
      </div>
    </div>

    <ModelFormDrawer v-model:visible="drawerVisible" :profile="editing" @saved="onDrawerSaved" />
  </div>
</template>

<style scoped>
.model-section {
  padding: 0;
  overflow: hidden;
}

.model-pager {
  display: flex;
  justify-content: flex-end;
  padding: 14px 16px;
  border-top: 1px solid var(--dda-card-border);
}
</style>
