<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { MessagePlugin } from 'tdesign-vue-next';
import type { PrimaryTableCol } from 'tdesign-vue-next';
import StatusTag from '@/shared/components/StatusTag.vue';
import EmptyGuide from '@/shared/components/EmptyGuide.vue';
import RowMenu from '@/shared/components/RowMenu.vue';
import type { RowMenuItem } from '@/shared/components/RowMenu.vue';
import { relativeTime } from '@/shared/utils/time';
import { errorMessage } from '@/shared/composables/useAsync';
import { useCursorPages } from '@/shared/composables/usePagination';
import { confirmDangerous } from '@/shared/composables/useConfirm';
import { LAYER } from '@/shared/constants/layers';
import { AGENT_STATUS } from '@/shared/enums/dict';
import { useRouter } from 'vue-router';
import {
  archiveAgent,
  deleteAgent,
  getAgent,
  listAgents,
  updateAgentDefinition,
} from '../api/agents';
import type { AgentWire, ListAgentsParams, UpdateAgentDefinitionPayload } from '../api/agents';
import { agentStatus, isAgentArchived, modelColumnText } from '../mappers';
import AgentFormDrawer from '../components/AgentFormDrawer.vue';

/**
 * 智能体列表页（游标分页，创建时间降序）。
 * <p>能力：关键字（名称模糊或完整 ID）搜索、状态筛选（默认 active，可切 archived）、加载更多翻页；
 * 行菜单提供 编辑配置（发版）/ 改属性 / 版本管理 / 归档 / 删除。</p>
 * <p>ID 检索口径：后端 keyword 仅按名称模糊匹配，故关键字带 agent_ 前缀时改走详情端点精确取单条，
 * 避免用户搜完整 ID 得到空列表。</p>
 * <p>"改属性"走 POST /agents/{id}（name/description + version 乐观锁），提交前 MUST 确认"将生成新版本快照"，
 * 409 时提示刷新重试。</p>
 */

const router = useRouter();

/** 每页条数。 */
const PAGE_SIZE = 20;

/** 状态筛选选项（后端 status 仅接受 active / archived）。 */
const statusOptions = [
  { label: '活跃', value: 'active' },
  { label: '已归档', value: 'archived' },
];

const keyword = ref('');
const status = ref('active');

const pager = useCursorPages<AgentWire>(PAGE_SIZE);
const rows = pager.items;
const hasMore = pager.hasMore;
const loading = ref(false);
const acting = ref(false);

/** 配置抽屉状态（mode + 目标智能体）。 */
const drawer = reactive<{ visible: boolean; mode: 'create' | 'publish'; agent: AgentWire | null }>({
  visible: false,
  mode: 'create',
  agent: null,
});

/** 改属性对话框状态（version 取当行当前生效版本号，作 OCC 基线）。 */
const propertyDialog = reactive<{ visible: boolean; agent: AgentWire | null; name: string; description: string; version: number }>({
  visible: false,
  agent: null,
  name: '',
  description: '',
  version: 0,
});

const columns: PrimaryTableCol[] = [
  { title: 'ID', colKey: 'id', width: 180 },
  { title: '名称', colKey: 'name', minWidth: 160, ellipsis: true },
  { title: '模型', colKey: 'model', width: 130, ellipsis: true },
  { title: '当前版本', colKey: 'version', width: 90 },
  { title: '状态', colKey: 'status', width: 90 },
  { title: '创建时间', colKey: 'created_at', width: 165 },
  { title: '更新时间', colKey: 'updated_at', width: 165 },
  { title: '操作', colKey: 'action', width: 130 },
];

/**
 * 行操作项（⋮ 菜单）：编辑配置（发版）/ 改属性 / 版本管理 / 归档 / 删除。
 * 归档智能体不可发版/改属性/再归档（后端一律 409）；删除为危险操作，单独标红。
 */
function rowActions(agent: AgentWire): RowMenuItem[] {
  const archived = isAgentArchived(agent);
  return [
    { key: 'publish', label: '编辑配置（发版）', icon: 'edit', disabled: archived },
    { key: 'property', label: '改属性', icon: 'setting', disabled: archived },
    { key: 'versions', label: '版本管理', icon: 'layers' },
    { key: 'archive', label: '归档', icon: 'folder-off', disabled: archived },
    { key: 'delete', label: '删除', icon: 'delete', destructive: true },
  ];
}

/** 组装本页请求参数（首页不带游标；ID 关键字走详情端点分支）。 */
function pageParams(append: boolean): ListAgentsParams {
  const trimmed = keyword.value.trim();
  const base: ListAgentsParams = { status: status.value, keyword: trimmed || undefined };
  if (!append) {
    return { ...base, limit: PAGE_SIZE };
  }
  return { ...base, ...pager.nextParams() };
}

/** 按完整 ID 精确检索单条（后端 keyword 仅匹配名称，ID 检索经详情端点）。 */
async function loadByAgentId(agentId: string, statusFilter: string): Promise<AgentWire[]> {
  const agent = await getAgent(agentId);
  return agentStatus(agent) === statusFilter ? [agent] : [];
}

/** 拉取一页并写入游标分页状态。 */
async function fetchPage(append: boolean): Promise<void> {
  loading.value = true;
  try {
    const trimmed = keyword.value.trim();
    if (!append && trimmed.startsWith('agent_')) {
      const single = await loadByAgentId(trimmed, status.value);
      pager.apply({ data: single, first_id: single[0]?.id ?? null, last_id: single[0]?.id ?? null, has_more: false }, false);
      if (!single.length) {
        MessagePlugin.warning('该 ID 的智能体不在当前状态筛选内');
      }
      return;
    }
    const resp = await listAgents(pageParams(append));
    pager.apply(resp, append);
  } catch (e) {
    MessagePlugin.error(errorMessage(e));
  } finally {
    loading.value = false;
  }
}

/** 重载首页（筛选变化、增删改后回到最新数据）。 */
async function reload(): Promise<void> {
  pager.reset();
  await fetchPage(false);
}

/** 复制业务 ID（剪贴板不可用时回落提示手工复制）。 */
async function onCopyId(agent: AgentWire): Promise<void> {
  try {
    await navigator.clipboard.writeText(agent.id);
    MessagePlugin.success('已复制 ID');
  } catch {
    MessagePlugin.info(`剪贴板不可用，ID：${agent.id}`);
  }
}

/** 打开版本管理页。 */
function openVersions(agent: AgentWire): void {
  void router.push({ name: 'agent-versions', params: { agentId: agent.id } });
}

/** 打开配置抽屉（create=新建；publish=以当前生效配置为底发版）。 */
function openDrawer(mode: 'create' | 'publish', agent: AgentWire | null): void {
  drawer.mode = mode;
  drawer.agent = agent;
  drawer.visible = true;
}

/** 行操作分发。 */
async function onRowAction(agent: AgentWire, key: string): Promise<void> {
  if (key === 'publish') {
    openDrawer('publish', agent);
    return;
  }
  if (key === 'property') {
    openPropertyDialog(agent);
    return;
  }
  if (key === 'versions') {
    openVersions(agent);
    return;
  }
  if (key === 'archive') {
    await onArchive(agent);
    return;
  }
  if (key === 'delete') {
    await onDelete(agent);
  }
}

/** 打开改属性对话框（回填当前名称/描述与乐观锁版本）。 */
function openPropertyDialog(agent: AgentWire): void {
  propertyDialog.agent = agent;
  propertyDialog.name = agent.name ?? '';
  propertyDialog.description = agent.description ?? '';
  propertyDialog.version = agent.version;
  propertyDialog.visible = true;
}

/** 提交改属性：确认"将生成新版本快照"→ POST /agents/{id}（409 提示刷新）。 */
async function onSubmitProperty(): Promise<void> {
  const agent = propertyDialog.agent;
  if (!agent) {
    return;
  }
  const name = propertyDialog.name.trim();
  if (!name) {
    MessagePlugin.warning('请输入智能体名称');
    return;
  }
  if (name.length > 256) {
    MessagePlugin.warning('名称不能超过 256 个字符');
    return;
  }
  const description = propertyDialog.description.trim();
  if (description.length > 2048) {
    MessagePlugin.warning('描述不能超过 2048 个字符');
    return;
  }
  const confirmed = await confirmDangerous({
    header: '改属性将生成新版本',
    body: `更新「${agent.name}」的名称/描述会携带版本 v${propertyDialog.version} 作为乐观锁，`
      + '并同步生成新版本快照（配置内容整体复制最新快照）。',
    theme: 'warning',
    confirmText: '提交',
  });
  if (!confirmed) {
    return;
  }
  acting.value = true;
  try {
    const payload: UpdateAgentDefinitionPayload = {
      version: propertyDialog.version,
      name,
    };
    if (description) {
      payload.description = description;
    }
    await updateAgentDefinition(agent.id, payload);
    MessagePlugin.success('属性已更新（已生成新版本快照）');
    propertyDialog.visible = false;
    await reload();
  } catch (e) {
    MessagePlugin.error(errorMessage(e));
    // 409 版本不匹配：刷新列表取最新版本号，避免用户反复撞锁
    if (isConflict(e)) {
      await reload();
    }
  } finally {
    acting.value = false;
  }
}

/** 是否为 409 冲突（ApiError 结构宽松判定，避免跨模块类型耦合）。 */
function isConflict(e: unknown): boolean {
  return typeof e === 'object' && e !== null && (e as { status?: number }).status === 409;
}

/** 归档（不可逆，无取消归档入口）。 */
async function onArchive(agent: AgentWire): Promise<void> {
  const confirmed = await confirmDangerous({
    header: '归档智能体',
    body: `归档「${agent.name}」不可逆（界面不提供取消归档），归档后不能再发版或改属性；`
      + '已发布版本仍可供既有会话装配。',
    theme: 'warning',
    confirmText: '归档',
  });
  if (!confirmed) {
    return;
  }
  await runRowAction(() => archiveAgent(agent.id), '智能体已归档');
}

/** 删除（逻辑删除定义与全部版本）。 */
async function onDelete(agent: AgentWire): Promise<void> {
  const confirmed = await confirmDangerous({
    header: '删除智能体',
    body: `即将删除「${agent.name}」及其全部版本，删除后不可恢复。`,
  });
  if (!confirmed) {
    return;
  }
  await runRowAction(() => deleteAgent(agent.id), '智能体已删除');
}

/** 统一执行行操作并处理错误与刷新。 */
async function runRowAction(action: () => Promise<void>, successText: string): Promise<void> {
  acting.value = true;
  try {
    await action();
    MessagePlugin.success(successText);
    await reload();
  } catch (e) {
    MessagePlugin.error(errorMessage(e));
  } finally {
    acting.value = false;
  }
}

/** ID 截断展示（保留前 12 位，其余省略）。 */
function shortId(id: string): string {
  return id.length > 12 ? `${id.slice(0, 12)}…` : id;
}

/** 表格加载中（列表或行操作任一进行中）。 */
const tableLoading = computed(() => loading.value || acting.value);

onMounted(() => {
  void reload();
});
</script>

<template>
  <div class="dda-page">
    <header class="agents-head">
      <div class="agents-head__text">
        <h2 class="dda-title">智能体</h2>
        <p class="dda-subtitle">配置即版本：每次改配置或改属性都会生成不可变版本快照，激活版本决定新会话装配口径</p>
      </div>
      <t-button theme="primary" @click="openDrawer('create', null)">
        <t-icon name="add" />
        新建智能体
      </t-button>
    </header>

    <div class="dda-toolbar">
      <t-input
        v-model="keyword"
        class="dda-toolbar__grow"
        placeholder="按名称搜索，或粘贴完整 agent_ ID 精确查找"
        clearable
        @enter="reload"
        @clear="reload"
      >
        <template #prefix-icon><t-icon name="search" /></template>
      </t-input>
      <t-select v-model="status" :options="statusOptions" style="width: 150px" @change="reload" />
      <t-button class="agents-refresh" variant="outline" :loading="loading" @click="reload">
        <t-icon name="refresh" />
        刷新
      </t-button>
    </div>

    <div class="dda-table">
      <t-table
        row-key="id"
        :data="rows"
        :columns="columns"
        :loading="tableLoading"
        hover
        size="small"
        table-layout="fixed"
        max-height="max(320px, calc(100vh - 320px))"
      >
        <template #id="{ row }">
          <span class="agents-id">
            <span class="dda-id dda-ellipsis agents-id__text" :title="row.id">{{ shortId(row.id) }}</span>
            <t-tooltip content="复制完整 ID">
              <t-button variant="text" shape="square" size="extra-small" aria-label="复制完整 ID" @click="onCopyId(row)">
                <t-icon name="copy" />
              </t-button>
            </t-tooltip>
          </span>
        </template>
        <template #name="{ row }">
          <t-link theme="primary" hover="color" @click="openVersions(row)">{{ row.name || '—' }}</t-link>
        </template>
        <template #model="{ row }">{{ modelColumnText(row.model) }}</template>
        <template #version="{ row }">v{{ row.version }}</template>
        <template #status="{ row }">
          <StatusTag :code="agentStatus(row)" :dict="AGENT_STATUS" />
        </template>
        <template #created_at="{ row }">{{ relativeTime(row.created_at) }}</template>
        <template #updated_at="{ row }">{{ relativeTime(row.updated_at) }}</template>
        <template #action="{ row }">
          <RowMenu :items="rowActions(row)" @select="(key: string) => onRowAction(row, key)" />
        </template>
        <template #empty>
          <EmptyGuide
            description="暂无智能体。智能体定义模型引用、系统提示词、工具权限策略与技能绑定，创建即版本 1。"
            icon="user"
          >
            <template #action>
              <t-button theme="primary" variant="outline" size="small" @click="openDrawer('create', null)">
                新建智能体
              </t-button>
            </template>
          </EmptyGuide>
        </template>
      </t-table>
    </div>

    <div class="agents-more">
      <t-button v-if="hasMore" variant="text" theme="primary" :loading="loading" @click="fetchPage(true)">
        加载更多
      </t-button>
      <p v-if="rows.length" class="agents-more__text dda-faint">
        已加载 {{ rows.length }} 条（游标分页无总数）
      </p>
    </div>

    <AgentFormDrawer v-model:visible="drawer.visible" :mode="drawer.mode" :agent="drawer.agent" @saved="reload" />

    <t-dialog
      v-model:visible="propertyDialog.visible"
      header="改属性（将生成新版本快照）"
      width="520px"
      :confirm-on-enter="true"
      :body="null"
      placement="center"
      attach="body"
      :z-index="LAYER.dialog"
      @confirm="onSubmitProperty"
    >
      <t-form layout="vertical">
        <t-form-item label="名称">
          <t-input v-model="propertyDialog.name" maxlength="256" placeholder="智能体名称" />
        </t-form-item>
        <t-form-item label="描述">
          <t-textarea v-model="propertyDialog.description" :autosize="{ minRows: 2, maxRows: 4 }" placeholder="用途说明（可选）" />
        </t-form-item>
        <t-form-item label="乐观锁版本">
          <t-input :value="`v${propertyDialog.version}`" disabled />
          <template #help>
            <span class="dda-faint">提交携带该版本号，若他人已发新版将返回 409（自动刷新重试）</span>
          </template>
        </t-form-item>
      </t-form>
    </t-dialog>
  </div>
</template>

<style scoped>
.agents-head {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 20px;
}

.agents-head__text {
  min-width: 0;
}

.agents-refresh {
  margin-left: auto;
}

.agents-id {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  min-width: 0;
  max-width: 100%;
}

.agents-id__text {
  min-width: 0;
}

.agents-more {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  margin-top: 14px;
}

.agents-more__text {
  margin: 0;
  font-size: 12px;
}
</style>