<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { MessagePlugin } from 'tdesign-vue-next';
import type { PrimaryTableCol } from 'tdesign-vue-next';
import EmptyGuide from '@/shared/components/EmptyGuide.vue';
import PageHeader from '@/shared/components/PageHeader.vue';
import StatusTag from '@/shared/components/StatusTag.vue';
import { confirmDangerous } from '@/shared/composables/useConfirm';
import { errorMessage, useAsync } from '@/shared/composables/useAsync';
import { useCursorPages } from '@/shared/composables/usePagination';
import { SESSION_STATUS } from '@/shared/enums/dict';
import type { CursorPage } from '@/shared/types/api';
import { compactTime } from '@/shared/utils/time';
import SessionCreateDialog from '../components/SessionCreateDialog.vue';
import { archiveSession, deleteSession, listActiveAgentsLite, listSessions } from '../api/sessions';
import type { AgentLite } from '../api/sessions';
import type { SessionWire } from '../types';
import { truncateSessionId } from '../mappers';
import type { SessionListFilter } from '../mappers';

/**
 * 会话列表页。
 * <p>筛选区（智能体 / 状态多选 / 创建时间范围 / 含已归档）+ 游标分页表格（"加载更多"语义，
 * 后端创建时间降序）；列：会话 ID（截断展示）、名称（空值显示"未命名会话"）、状态、智能体名、创建时间。</p>
 * <p>行操作：查看（跳工作区）、归档（二次确认，归档会中断运行中的执行）、删除（危险操作二次确认，
 * 逻辑删除会话与其事件流）。失败直显后端 message（如归档/删除的 404/409 原因）。</p>
 */

/** 每页条数（后端 limit 上限 100）。 */
const PAGE_SIZE = 20;

const router = useRouter();

/** 状态多选下拉选项（idle/running/rescheduling/terminated）。 */
const statusOptions = Object.entries(SESSION_STATUS).map(([value, item]) => ({ label: item.label, value }));

/** 智能体筛选下拉选项。 */
const agents = ref<AgentLite[]>([]);
const agentOptions = computed(() => agents.value.map((item) => ({ label: item.name || item.id, value: item.id })));

const agentId = ref<string | undefined>(undefined);
const statuses = ref<string[]>([]);
const includeArchived = ref(false);
/** 创建时间范围（yyyy-MM-dd 两元素；提交前由 mappers 补全为 RFC 3339 边界）。 */
const timeRange = ref<string[]>([]);

/** 会话搜索关键字（纯展示层过滤：仅对已加载列表按 ID / 名称 / 智能体名做本地匹配）。 */
const keyword = ref('');

const createVisible = ref(false);

const cursor = useCursorPages<SessionWire>(PAGE_SIZE);
/** 累积列表（顶层 ref，模板自动解包）。 */
const items = cursor.items;
/** 是否还有下一页。 */
const hasMore = cursor.hasMore;

const listTask = useAsync<CursorPage<SessionWire>>();
const actionTask = useAsync<void>();
const agentsTask = useAsync<AgentLite[]>();

/** 列表加载中。 */
const loading = listTask.loading;
/** 行操作中。 */
const acting = actionTask.loading;
/** 智能体筛选加载中（下拉 loading，超时/失败后必然归位）。 */
const agentsLoading = agentsTask.loading;
/** 智能体筛选加载失败原因（工具条错误提示 + 重试入口）。 */
const agentsError = agentsTask.error;

const columns: PrimaryTableCol[] = [
  { title: 'ID', colKey: 'id', width: 160, cell: 'id' },
  { title: '名称', colKey: 'title', minWidth: 180, ellipsis: true, cell: 'title' },
  { title: '状态', colKey: 'status', width: 150, cell: 'status' },
  { title: '智能体', colKey: 'agent', width: 150, ellipsis: true, cell: 'agent' },
  { title: '创建时间', colKey: 'created_at', width: 170, cell: 'createdAt' },
  { title: '操作', colKey: 'action', width: 170, cell: 'action' },
];

/** 当前筛选条件（由 UI 态收敛为 mappers 输入）。 */
function currentFilter(): SessionListFilter {
  return {
    agentId: agentId.value,
    statuses: statuses.value,
    includeArchived: includeArchived.value,
    timeRange: timeRange.value,
  };
}

/** 拉取一页（append=true 为追加加载）。 */
async function fetchPage(append: boolean): Promise<void> {
  const raw = cursor.nextParams();
  const resp = await listTask.run(() =>
    listSessions(currentFilter(), {
      limit: Number(raw.limit),
      afterId: raw.after_id === undefined ? undefined : String(raw.after_id),
    }),
  );
  if (resp) {
    cursor.apply(resp, append);
  }
  if (listTask.error.value) {
    MessagePlugin.error(errorMessage(listTask.error.value));
  }
}

/** 重载首页（筛选变化 / 归档 / 删除 / 新建后回到最新数据）。 */
async function reload(): Promise<void> {
  cursor.reset();
  await fetchPage(false);
}

function onLoadMore(): Promise<void> {
  return fetchPage(true);
}

/** 智能体筛选变化。 */
function onAgentChange(value: unknown): Promise<void> {
  const raw = value === undefined || value === null ? '' : String(value);
  agentId.value = raw || undefined;
  return reload();
}

/** 状态多选变化。 */
function onStatusesChange(value: unknown): Promise<void> {
  statuses.value = Array.isArray(value) ? value.map((item) => String(item)) : [];
  return reload();
}

/** 含已归档开关变化。 */
function onArchivedChange(value: unknown): Promise<void> {
  includeArchived.value = Boolean(value);
  return reload();
}

/** 创建时间范围变化。 */
function onTimeRangeChange(value: unknown): Promise<void> {
  timeRange.value = Array.isArray(value) ? value.map((item) => String(item ?? '')).filter(Boolean) : [];
  return reload();
}

/** 重置全部筛选并重载。 */
function resetFilters(): Promise<void> {
  agentId.value = undefined;
  statuses.value = [];
  includeArchived.value = false;
  timeRange.value = [];
  return reload();
}

/** 跳转会话工作区（工作区由任务组 9 实现）。 */
function onView(row: SessionWire): void {
  router.push(`/sessions/${row.id}`);
}

/** 统一执行行操作：失败直显后端 message。 */
async function runAction(action: () => Promise<void>, successText: string): Promise<void> {
  await actionTask.run(action);
  if (actionTask.error.value) {
    MessagePlugin.error(errorMessage(actionTask.error.value));
    return;
  }
  MessagePlugin.success(successText);
  await reload();
}

/** 归档会话（写 archived_at 并中断运行中的执行）。 */
async function onArchive(row: SessionWire): Promise<void> {
  const confirmed = await confirmDangerous({
    header: '归档会话',
    body: `归档「${row.title || '未命名会话'}」后该会话不可再发送消息，运行中的执行会被中断，且归档不可逆。`,
    theme: 'warning',
    confirmText: '归档',
  });
  if (!confirmed) {
    return;
  }
  await runAction(async () => {
    await archiveSession(row.id);
  }, '会话已归档');
}

/** 删除会话（逻辑删除：会话与其历史事件流一并清理）。 */
async function onDelete(row: SessionWire): Promise<void> {
  const confirmed = await confirmDangerous({
    header: '删除会话',
    body: `即将删除「${row.title || '未命名会话'}」及其全部历史事件，删除后不可恢复；仍活跃的执行会先被取消。`,
  });
  if (!confirmed) {
    return;
  }
  await runAction(async () => {
    await deleteSession(row.id);
  }, '会话已删除');
}

/** 行操作分发（与既有 onView / onArchive / onDelete 行为一致）。 */
function onRowAction(row: SessionWire, key: string): void {
  if (key === 'view') {
    onView(row);
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

/** 加载智能体筛选选项（失败不阻断列表）。 */
async function loadAgents(): Promise<void> {
  const list = await agentsTask.run(() => listActiveAgentsLite());
  agents.value = list ?? [];
}

/** 会话所属智能体展示名（快照缺名回落 ID）。 */
function agentName(row: SessionWire): string {
  return row.agent?.name || row.agent?.id || '-';
}

/** 展示层过滤：按搜索关键字对已加载列表做本地匹配（不改动任何服务端筛选与分页语义）。 */
const visibleItems = computed<SessionWire[]>(() => {
  const text = keyword.value.trim().toLowerCase();
  if (!text) {
    return items.value;
  }
  return items.value.filter((row) => {
    const fields = [row.id, row.title ?? '', agentName(row)];
    return fields.some((field) => field.toLowerCase().includes(text));
  });
});

onMounted(() => {
  void loadAgents();
  void reload();
});
</script>

<template>
  <div class="sessions-page dda-page">
    <PageHeader
      title="会话"
      subtitle="按创建时间倒序游标加载；归档中断执行且不可再发送消息，删除为逻辑删除"
      icon="chat-bubble"
    >
      <template #actions>
        <t-button theme="primary" @click="createVisible = true">
          <t-icon name="add" />
          新建会话
        </t-button>
      </template>
    </PageHeader>

    <div class="dda-section sessions-page__body">
      <div class="dda-toolbar">
        <t-date-range-picker
          :value="timeRange"
          class="sessions-page__filter"
          clearable
          placeholder="创建时间范围"
          style="width: 240px"
          @change="onTimeRangeChange"
        />
        <t-select
          :value="agentId"
          :options="agentOptions"
          class="sessions-page__filter"
          :loading="agentsLoading"
          placeholder="智能体（默认全部）"
          filterable
          clearable
          style="width: 200px"
          @change="onAgentChange"
        />
        <t-select
          :value="statuses"
          :options="statusOptions"
          class="sessions-page__filter"
          multiple
          :min-collapsed-num="1"
          placeholder="状态（可多选）"
          clearable
          style="width: 220px"
          @change="onStatusesChange"
        />
        <t-input v-model="keyword" class="dda-toolbar__grow sessions-page__search" placeholder="搜索会话" clearable>
          <template #prefix-icon><t-icon name="search" /></template>
        </t-input>
        <t-space size="small" align="center">
          <t-switch :value="includeArchived" @change="onArchivedChange" />
          <span class="sessions-page__switch-label dda-muted">含已归档</span>
        </t-space>
        <t-button variant="outline" :loading="loading" @click="reload">
          <t-icon name="refresh" />
          刷新
        </t-button>
        <t-button variant="text" theme="primary" @click="resetFilters">
          <t-icon name="rollback" />
          重置
        </t-button>
      </div>

      <t-alert
        v-if="agentsError"
        theme="error"
        class="sessions-page__alert"
        :message="`智能体筛选加载失败：${errorMessage(agentsError)}`"
      >
        <template #operation>
          <t-button size="small" variant="outline" :loading="agentsLoading" @click="loadAgents">重试</t-button>
        </template>
      </t-alert>

      <div class="dda-table sessions-page__table">
        <t-table
          row-key="id"
          :data="visibleItems"
          :columns="columns"
          :loading="loading || acting"
          hover
          size="medium"
          table-layout="fixed"
          max-height="max(340px, calc(100vh - 340px))"
        >
          <template #id="{ row }">
            <t-link
              theme="default"
              hover="color"
              class="dda-id sessions-page__id"
              :title="row.id"
              :aria-label="`打开会话 ${row.id}`"
              @click="onView(row)"
            >
              {{ truncateSessionId(row.id) }}
            </t-link>
          </template>
          <template #title="{ row }">
            <span v-if="row.title">{{ row.title }}</span>
            <span v-else class="dda-faint">未命名会话</span>
          </template>
          <template #status="{ row }">
            <t-space size="small" align="center">
              <StatusTag :code="row.status" :dict="SESSION_STATUS" />
              <t-tag v-if="row.archived_at" theme="default" variant="light" size="small">已归档</t-tag>
            </t-space>
          </template>
          <template #agent="{ row }">
            <span :class="agentName(row) === '-' ? 'dda-faint' : ''">{{ agentName(row) }}</span>
          </template>
          <template #createdAt="{ row }">{{ compactTime(row.created_at) }}</template>
          <template #action="{ row }">
            <span class="sessions-page__actions">
              <t-link theme="primary" hover="color" @click="onRowAction(row, 'view')">查看</t-link>
              <t-link v-if="!row.archived_at" theme="warning" hover="color" @click="onRowAction(row, 'archive')">
                归档
              </t-link>
              <t-link theme="danger" hover="color" @click="onRowAction(row, 'delete')">删除</t-link>
            </span>
          </template>
          <template #empty>
            <EmptyGuide
              icon="chat-bubble"
              :description="
                keyword.trim()
                  ? '没有匹配的会话，试试更换关键字或调整筛选条件'
                  : '暂无会话。会话需要一个 cloud 运行环境与一个活跃智能体，创建后即可进入工作区对话'
              "
            >
              <template #action>
                <t-button theme="primary" size="small" @click="createVisible = true">新建会话</t-button>
              </template>
            </EmptyGuide>
          </template>
        </t-table>

        <div class="sessions-page__more">
          <t-button v-if="hasMore" theme="primary" variant="outline" :loading="loading" @click="onLoadMore">
            加载更多
          </t-button>
          <span v-else-if="items.length" class="dda-faint sessions-page__more-text">
            已加载 {{ items.length }} 条（游标分页无总数）
          </span>
        </div>
      </div>
    </div>

    <SessionCreateDialog v-model:visible="createVisible" @created="reload" />
  </div>
</template>

<style scoped>
.sessions-page__body {
  padding: 18px;
}

/* 智能体筛选加载失败的提示条：与下方表格留出间距 */
.sessions-page__alert {
  margin-top: 12px;
}

/* 工具条筛选控件：胶囊感圆角 */
.sessions-page__filter :deep(.t-input) {
  border-radius: var(--td-radius-round);
}

.sessions-page__search :deep(.t-input) {
  border-radius: var(--td-radius-round);
}

.sessions-page__switch-label {
  font-size: 13px;
}

/* 表格卡片：复用全局 .dda-table 表头/悬浮样式，外层补卡片描边与投影 */
.sessions-page__table {
  padding: 0;
  overflow: hidden;
  background: var(--td-bg-color-container);
  border: 1px solid var(--dda-card-border);
  border-radius: var(--td-radius-large);
  box-shadow: var(--dda-card-shadow);
}

.sessions-page__id {
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  vertical-align: bottom;
  white-space: nowrap;
}

.sessions-page__actions {
  display: inline-flex;
  gap: 12px;
  align-items: center;
}

.sessions-page__more {
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: center;
  padding: 14px 16px;
  border-top: 1px solid var(--dda-card-border);
}

.sessions-page__more-text {
  font-size: 12px;
}
</style>