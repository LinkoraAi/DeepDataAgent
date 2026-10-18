<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { MessagePlugin } from 'tdesign-vue-next';
import EmptyGuide from '@/shared/components/EmptyGuide.vue';
import RowMenu from '@/shared/components/RowMenu.vue';
import type { RowMenuItem } from '@/shared/components/RowMenu.vue';
import { errorMessage, useAsync } from '@/shared/composables/useAsync';
import { useCursorPages } from '@/shared/composables/usePagination';
import { confirmDangerous } from '@/shared/composables/useConfirm';
import { relativeTime } from '@/shared/utils/time';
import type { CursorPage } from '@/shared/types/api';
import { archiveEnvironment, deleteEnvironment, listEnvironments } from '../api';
import type { EnvironmentWire, ListEnvironmentParams } from '../api';
import { environmentTypeLabel, isArchived, packagesSummary } from '../mappers';
import EnvironmentFormDrawer from '../components/EnvironmentFormDrawer.vue';

/**
 * 运行环境列表页：游标分页"加载更多"（后端 created_at 降序）。
 * 行操作（⋮）：编辑 / 归档 / 删除；归档与删除均二次确认，被会话引用时直显后端 409 原因。
 */
const drawerVisible = ref(false);
const editing = ref<EnvironmentWire | null>(null);

const cursor = useCursorPages<EnvironmentWire>(20);
/** 累积列表（顶层 ref，模板自动解包） */
const items = cursor.items;
/** 是否还有下一页 */
const hasMore = cursor.hasMore;

const listTask = useAsync<CursorPage<EnvironmentWire>>();
const actionTask = useAsync<void>();

/** 列表加载中 */
const loading = listTask.loading;
/** 行操作中 */
const acting = actionTask.loading;

const columns = [
  { colKey: 'name', title: '名称', width: 170, ellipsis: true },
  { colKey: 'type', title: '类型', width: 130, cell: 'type' },
  { colKey: 'packages', title: '预装依赖', width: 250, ellipsis: true, cell: 'packages' },
  { colKey: 'setupScript', title: 'setup 脚本', width: 110, cell: 'setupScript' },
  { colKey: 'archived', title: '状态', width: 100, cell: 'archived' },
  { colKey: 'created_at', title: '创建时间', width: 150, cell: 'createdAt' },
  { colKey: 'action', title: '操作', width: 80, cell: 'action' },
];

/** 端点请求参数（由通用游标参数收敛，limit 数值、after_id 字符串）。 */
function pageParams(): ListEnvironmentParams {
  const raw = cursor.nextParams();
  return {
    limit: Number(raw.limit),
    after_id: raw.after_id === undefined ? undefined : String(raw.after_id),
  };
}

/** 拉取一页（append=true 为追加加载）。 */
async function fetchPage(append: boolean): Promise<void> {
  const resp = await listTask.run(() => listEnvironments(pageParams()));
  if (resp) {
    cursor.apply(resp, append);
  }
}

/** 重载首页（新建/编辑/归档/删除后回到最新数据）。 */
async function reload(): Promise<void> {
  cursor.reset();
  await fetchPage(false);
}

function onLoadMore(): Promise<void> {
  return fetchPage(true);
}

function onCreate(): void {
  editing.value = null;
  drawerVisible.value = true;
}

function onEdit(row: EnvironmentWire): void {
  editing.value = row;
  drawerVisible.value = true;
}

/** 统一执行行操作：失败直显后端 message（被会话引用时为 409 冲突原因）。 */
async function runAction(action: () => Promise<void>, successText: string): Promise<void> {
  await actionTask.run(action);
  if (actionTask.error.value) {
    MessagePlugin.error(errorMessage(actionTask.error.value));
    return;
  }
  MessagePlugin.success(successText);
  await reload();
}

async function onArchive(row: EnvironmentWire): Promise<void> {
  const confirmed = await confirmDangerous({
    header: '归档运行环境',
    body: `归档「${row.name}」后不可逆，且新会话不能再引用该环境；仍被未删除会话引用时将归档失败。`,
    theme: 'warning',
    confirmText: '归档',
  });
  if (!confirmed) {
    return;
  }
  await runAction(async () => {
    await archiveEnvironment(row.id);
  }, '运行环境已归档');
}

async function onDelete(row: EnvironmentWire): Promise<void> {
  const confirmed = await confirmDangerous({
    header: '删除运行环境',
    body: `即将删除「${row.name}」，删除后不可恢复；仍被会话或调度器引用时将删除失败。`,
  });
  if (!confirmed) {
    return;
  }
  await runAction(() => deleteEnvironment(row.id), '运行环境已删除');
}

/**
 * 行菜单项（⋮）：编辑 / 归档 / 删除。
 * 已归档的行禁用「归档」，与原「仅未归档可归档」的入口约束一致。
 */
function rowActions(row: EnvironmentWire): RowMenuItem[] {
  return [
    { key: 'edit', label: '编辑', icon: 'edit' },
    { key: 'archive', label: '归档', icon: 'folder', disabled: isArchived(row) },
    { key: 'delete', label: '删除', icon: 'delete', destructive: true },
  ];
}

/** 行操作分发：复用既有处理函数，语义与行为不变。 */
function onRowAction(row: EnvironmentWire, key: string): void {
  if (key === 'edit') {
    onEdit(row);
    return;
  }
  if (key === 'archive') {
    void onArchive(row);
    return;
  }
  if (key === 'delete') {
    void onDelete(row);
  }
}

onMounted(() => {
  void reload();
});
</script>

<template>
  <div class="dda-page">
    <header class="env-head">
      <div class="env-head__text">
        <h2 class="dda-title">运行环境</h2>
        <p class="dda-subtitle">会话沙箱的依赖与准备脚本；self_hosted 环境不可用于新建会话</p>
      </div>
      <t-button theme="primary" @click="onCreate">
        <t-icon name="add" />
        新建环境
      </t-button>
    </header>

    <div class="dda-table">
      <t-table
        row-key="id"
        :data="items"
        :columns="columns"
        :loading="loading || acting"
        hover
        size="small"
        table-layout="fixed"
        max-height="max(320px, calc(100vh - 320px))"
      >
        <template #type="{ row }">{{ environmentTypeLabel(row.config?.type) }}</template>
        <template #packages="{ row }">
          <span :class="packagesSummary(row) ? '' : 'dda-faint'" class="dda-ellipsis">{{ packagesSummary(row) || '—' }}</span>
        </template>
        <template #setupScript="{ row }">
          <t-tag :theme="row.config?.setup_script ? 'primary' : 'default'" variant="light" size="small">
            {{ row.config?.setup_script ? '有脚本' : '无脚本' }}
          </t-tag>
        </template>
        <template #archived="{ row }">
          <t-tag :theme="isArchived(row) ? 'default' : 'success'" variant="light" size="small">
            {{ isArchived(row) ? '已归档' : '可用' }}
          </t-tag>
        </template>
        <template #createdAt="{ row }">
          <span :title="row.created_at">{{ relativeTime(row.created_at) }}</span>
        </template>
        <template #action="{ row }">
          <RowMenu :items="rowActions(row)" @select="(key: string) => onRowAction(row, key)" />
        </template>
        <template #empty>
          <EmptyGuide description="还没有运行环境，创建会话时需要先有一个 cloud 类型环境" icon="desktop">
            <template #action>
              <t-button theme="primary" variant="outline" size="small" @click="onCreate">新建环境</t-button>
            </template>
          </EmptyGuide>
        </template>
      </t-table>
    </div>

    <div class="env-more">
      <t-button v-if="hasMore" variant="text" theme="primary" :loading="loading" @click="onLoadMore">加载更多</t-button>
      <p v-else-if="items.length" class="env-more__text dda-faint">已加载全部 {{ items.length }} 条</p>
    </div>

    <EnvironmentFormDrawer v-model:visible="drawerVisible" :environment="editing" @saved="reload" />
  </div>
</template>

<style scoped>
.env-head {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 20px;
}

.env-head__text {
  min-width: 0;
}

.env-more {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  margin-top: 14px;
}

.env-more__text {
  margin: 0;
  font-size: 12px;
}
</style>