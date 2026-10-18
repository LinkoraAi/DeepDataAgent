<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { MessagePlugin } from 'tdesign-vue-next';
import type { PrimaryTableCol } from 'tdesign-vue-next';
import { useRouter } from 'vue-router';
import StatusTag from '@/shared/components/StatusTag.vue';
import EmptyGuide from '@/shared/components/EmptyGuide.vue';
import RowMenu from '@/shared/components/RowMenu.vue';
import type { RowMenuItem } from '@/shared/components/RowMenu.vue';
import { relativeTime } from '@/shared/utils/time';
import { errorMessage } from '@/shared/composables/useAsync';
import { useCursorPages } from '@/shared/composables/usePagination';
import { confirmDangerous } from '@/shared/composables/useConfirm';
import { AGENT_STATUS } from '@/shared/enums/dict';
import {
  activateVersion,
  archiveAgent,
  deleteAgent,
  getAgent,
  listAgentVersions,
} from '../api/agents';
import type { AgentToolWire, AgentWire, ListAgentVersionsParams } from '../api/agents';
import { agentStatus, isAgentArchived, modelColumnText } from '../mappers';
import AgentFormDrawer from '../components/AgentFormDrawer.vue';

/**
 * 智能体版本管理页（发布号降序游标分页）。
 * <p>头部展示返回入口、名称、当前生效版本与归档标记；版本表提供
 * 查看快照（只读）/ 激活（回滚）/ 以此版本为底发新版。</p>
 * <p>页操作：发布新版本（打开配置抽屉）、归档（不可逆）、删除（连同全部版本）。
 * 版本游标为「版本号字符串」，优先取响应 next_page，回落后用末条版本号。</p>
 */
const props = defineProps<{ agentId: string }>();

const router = useRouter();

/** 每页条数。 */
const PAGE_SIZE = 20;

/** 智能体对象（定义属性 + 当前生效版本配置）。 */
const agent = ref<AgentWire | null>(null);
/** 智能体对象加载中。 */
const agentLoading = ref(false);
/** 行操作中。 */
const acting = ref(false);

const pager = useCursorPages<AgentWire>(PAGE_SIZE);
const versions = pager.items;
const hasMore = pager.hasMore;

/** 配置抽屉（发版）。 */
const drawer = reactive<{ visible: boolean; base: AgentWire | null }>({ visible: false, base: null });

/** 快照只读抽屉。 */
const snapshot = reactive<{ visible: boolean; agent: AgentWire | null }>({ visible: false, agent: null });

const columns: PrimaryTableCol[] = [
  { title: '版本', colKey: 'version', width: 90 },
  { title: '名称', colKey: 'name', minWidth: 150, ellipsis: true },
  { title: '模型', colKey: 'model', width: 140, ellipsis: true },
  { title: '工具', colKey: 'tools', width: 90 },
  { title: '技能', colKey: 'skills', width: 90 },
  { title: '创建时间', colKey: 'created_at', width: 165 },
  { title: '操作', colKey: 'action', width: 150 },
];

/** 当前生效版本号（缺省 0 表示尚无版本）。 */
const activeVersion = computed(() => agent.value?.version ?? 0);

/** 是否已归档。 */
const archived = computed(() => isAgentArchived(agent.value ?? { archived_at: null }));

/** 表格加载中。 */
const tableLoading = computed(() => pager.loading.value || acting.value);

/**
 * 版本行操作项（⋮ 菜单）：查看快照 / 激活（回滚）/ 以此发版。
 * 生效版本不可再激活；已归档不可激活或发版。
 */
function versionActions(row: AgentWire): RowMenuItem[] {
  return [
    { key: 'snapshot', label: '查看快照', icon: 'layers' },
    {
      key: 'activate',
      label: '激活',
      icon: 'refresh',
      disabled: row.version === activeVersion.value || archived.value,
    },
    { key: 'publish', label: '以此发版', icon: 'edit', disabled: archived.value },
  ];
}

/** 版本行操作分发。 */
function onVersionAction(row: AgentWire, key: string): void {
  if (key === 'activate') {
    void onActivate(row);
    return;
  }
  if (key === 'publish') {
    openPublish(row);
    return;
  }
  void openSnapshot(row);
}

/** 拉取智能体对象（头部信息 + 当前版本口径）。 */
async function loadAgent(): Promise<void> {
  agentLoading.value = true;
  try {
    agent.value = await getAgent(props.agentId);
  } catch (e) {
    MessagePlugin.error(errorMessage(e));
  } finally {
    agentLoading.value = false;
  }
}

/** 组装版本页请求参数（游标取 next_page，回落末条版本号）。 */
function versionParams(append: boolean): ListAgentVersionsParams {
  if (!append) {
    return { limit: PAGE_SIZE };
  }
  const raw = pager.nextParams();
  return { limit: Number(raw.limit), after_id: raw.after_id === undefined ? undefined : String(raw.after_id) };
}

/** 拉取一页版本历史。 */
async function fetchPage(append: boolean): Promise<void> {
  pager.loading.value = true;
  try {
    const resp = await listAgentVersions(props.agentId, versionParams(append));
    pager.apply(resp, append);
  } catch (e) {
    MessagePlugin.error(errorMessage(e));
  } finally {
    pager.loading.value = false;
  }
}

/** 重载首屏（激活/发版/归档后回到最新数据）。 */
async function reload(): Promise<void> {
  await loadAgent();
  pager.reset();
  await fetchPage(false);
}

/** 回列表页。 */
function backToList(): void {
  void router.push({ name: 'agents' });
}

/** 打开快照只读抽屉（按版本号取该版本快照，与 Agent 对象同形）。 */
async function openSnapshot(row: AgentWire): Promise<void> {
  try {
    snapshot.agent = await getAgent(props.agentId, row.version);
    snapshot.visible = true;
  } catch (e) {
    MessagePlugin.error(errorMessage(e));
  }
}

/** 打开发版抽屉（base 为空表示以当前生效配置为底）。 */
function openPublish(base: AgentWire | null): void {
  if (!agent.value) {
    MessagePlugin.warning('智能体信息尚未加载，请稍后重试');
    return;
  }
  drawer.base = base;
  drawer.visible = true;
}

/** 激活 / 回滚版本。 */
async function onActivate(row: AgentWire): Promise<void> {
  const confirmed = await confirmDangerous({
    header: `激活版本 v${row.version}`,
    body: `当前生效版本为 v${activeVersion.value}；激活 v${row.version} 后，省略版本号装配的新会话与调度任务将改用该版本快照（已运行会话不受影响）。`,
    theme: 'warning',
    confirmText: '激活',
  });
  if (!confirmed) {
    return;
  }
  await runAction(() => activateVersion(props.agentId, row.version), `已激活版本 v${row.version}`);
}

/** 归档智能体（不可逆）。 */
async function onArchive(): Promise<void> {
  const name = agent.value?.name ?? '';
  const confirmed = await confirmDangerous({
    header: '归档智能体',
    body: `归档「${name}」不可逆（界面不提供取消归档），归档后不能再发版、改属性或激活版本；已发布版本仍可供既有会话装配。`,
    theme: 'warning',
    confirmText: '归档',
  });
  if (!confirmed) {
    return;
  }
  await runAction(() => archiveAgent(props.agentId), '智能体已归档');
}

/** 删除智能体及全部版本后回列表。 */
async function onDelete(): Promise<void> {
  const name = agent.value?.name ?? '';
  const confirmed = await confirmDangerous({
    header: '删除智能体',
    body: `即将删除「${name}」及其全部版本快照，删除后不可恢复。`,
  });
  if (!confirmed) {
    return;
  }
  acting.value = true;
  try {
    await deleteAgent(props.agentId);
    MessagePlugin.success('智能体已删除');
    void router.push({ name: 'agents' });
  } catch (e) {
    MessagePlugin.error(errorMessage(e));
    acting.value = false;
  }
}

/** 统一执行页/行操作（失败直显后端原因）。 */
async function runAction(action: () => Promise<unknown>, successText: string): Promise<void> {
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

/** 工具条目摘要（内置白名单数 + custom 数）。 */
function toolsSummary(tools: AgentToolWire[] | undefined): string {
  const list = tools ?? [];
  if (!list.length) {
    return '—';
  }
  const custom = list.filter((item) => item.type === 'custom').length;
  const builtin = list.length - custom;
  return `内置 ${builtin} / 自定义 ${custom}`;
}

/** 技能数量摘要。 */
function skillsCount(skills: AgentWire['skills'] | undefined): string {
  return String(skills?.length ?? 0);
}

/** 快照 JSON 文本（只读展示）。 */
function toPrettyJson(value: unknown): string {
  return JSON.stringify(value ?? [], null, 2);
}

/** ID 复制。 */
async function onCopyId(): Promise<void> {
  const id = agent.value?.id;
  if (!id) {
    return;
  }
  try {
    await navigator.clipboard.writeText(id);
    MessagePlugin.success('已复制智能体 ID');
  } catch {
    MessagePlugin.info(`剪贴板不可用，ID：${id}`);
  }
}

onMounted(() => {
  void reload();
});
</script>

<template>
  <div class="dda-page">
    <header class="versions-head">
      <div class="versions-head__text">
        <h2 class="dda-title">{{ agent?.name || '版本管理' }}</h2>
        <p class="dda-subtitle">版本快照不可变：激活即回滚口径，发版即新增快照</p>
      </div>
      <t-button variant="outline" @click="backToList">
        <t-icon name="chevron-left" />
        返回列表
      </t-button>
    </header>

    <div class="versions-meta">
      <div class="dda-metabar">
        <span class="dda-metabar__item">
          <span class="versions-meta__label">智能体 ID</span>
          <span class="dda-id versions-meta__id dda-ellipsis" :title="agent?.id ?? ''">{{ agent?.id ?? '—' }}</span>
          <t-tooltip content="复制智能体 ID">
            <t-button variant="text" shape="square" size="extra-small" aria-label="复制智能体 ID" @click="onCopyId">
              <t-icon name="copy" />
            </t-button>
          </t-tooltip>
        </span>
        <span class="dda-metabar__item">
          <span class="versions-meta__label">当前生效版本</span>
          <span>v{{ activeVersion || '—' }}</span>
        </span>
        <span class="dda-metabar__item">
          <span class="versions-meta__label">状态</span>
          <StatusTag :code="agent ? agentStatus(agent) : ''" :dict="AGENT_STATUS" />
        </span>
      </div>
      <div class="versions-meta__actions">
        <t-button theme="primary" :disabled="archived" @click="openPublish(null)">
          <t-icon name="add" />
          发布新版本
        </t-button>
        <t-button variant="outline" theme="warning" :disabled="archived" @click="onArchive">归档</t-button>
        <t-button variant="outline" theme="danger" @click="onDelete">删除</t-button>
      </div>
    </div>
    <p v-if="archived" class="versions-archived">
      该智能体已归档：不可再发版、改属性或激活版本，历史版本仍可被既有会话装配。
    </p>

    <div class="dda-table">
      <t-table
        row-key="version"
        :data="versions"
        :columns="columns"
        :loading="tableLoading"
        hover
        size="small"
        table-layout="fixed"
        max-height="max(320px, calc(100vh - 320px))"
      >
        <template #version="{ row }">
          <span class="versions-cell">
            <span>v{{ row.version }}</span>
            <t-tag v-if="row.version === activeVersion" theme="success" variant="light" size="small">生效中</t-tag>
          </span>
        </template>
        <template #model="{ row }">{{ modelColumnText(row.model) }}</template>
        <template #tools="{ row }">{{ toolsSummary(row.tools) }}</template>
        <template #skills="{ row }">{{ skillsCount(row.skills) }}</template>
        <template #created_at="{ row }">{{ relativeTime(row.created_at) }}</template>
        <template #action="{ row }">
          <RowMenu :items="versionActions(row)" @select="(key: string) => onVersionAction(row, key)" />
        </template>
        <template #empty>
          <EmptyGuide description="暂无版本记录（加载中或该智能体版本台账为空）。" icon="layers" />
        </template>
      </t-table>
    </div>

    <div class="versions-more">
      <t-button v-if="hasMore" variant="text" theme="primary" :loading="tableLoading" @click="fetchPage(true)">
        加载更多
      </t-button>
      <span v-else-if="versions.length" class="versions-more__text dda-faint">
        已加载 {{ versions.length }} 个版本（游标分页无总数）
      </span>
    </div>

    <AgentFormDrawer
      v-model:visible="drawer.visible"
      mode="publish"
      :agent="agent"
      :base="drawer.base"
      @saved="reload"
    />

    <t-drawer
      v-model:visible="snapshot.visible"
      :header="`版本快照 v${snapshot.agent?.version ?? ''}`"
      size="720px"
      placement="right"
      :footer="false"
    >
      <template v-if="snapshot.agent">
        <t-descriptions :column="2" size="small" bordered layout="vertical">
          <t-descriptions-item label="名称">{{ snapshot.agent.name }}</t-descriptions-item>
          <t-descriptions-item label="模型">{{ modelColumnText(snapshot.agent.model) }}</t-descriptions-item>
          <t-descriptions-item label="创建时间">{{ relativeTime(snapshot.agent.created_at) }}</t-descriptions-item>
          <t-descriptions-item label="描述">{{ snapshot.agent.description || '—' }}</t-descriptions-item>
        </t-descriptions>

        <h4 class="versions-block-title">系统提示词（只读）</h4>
        <pre class="dda-mono versions-code">{{ snapshot.agent.system || '—' }}</pre>

        <h4 class="versions-block-title">工具配方（{{ toolsSummary(snapshot.agent.tools) }}）</h4>
        <pre class="dda-mono versions-code versions-code--json">{{ toPrettyJson(snapshot.agent.tools) }}</pre>

        <h4 class="versions-block-title">技能绑定（{{ skillsCount(snapshot.agent.skills) }}）</h4>
        <pre class="dda-mono versions-code versions-code--json">{{ toPrettyJson(snapshot.agent.skills) }}</pre>

        <h4 class="versions-block-title">元数据</h4>
        <pre class="dda-mono versions-code versions-code--json">{{ toPrettyJson(snapshot.agent.metadata) }}</pre>
      </template>
      <template v-else>
        <EmptyGuide description="快照加载中或加载失败，请关闭后重试。" icon="layers" />
      </template>
    </t-drawer>
  </div>
</template>

<style scoped>
.versions-head {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}

.versions-head__text {
  min-width: 0;
}

.versions-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding-bottom: 14px;
  margin-bottom: 16px;
  border-bottom: 1px solid var(--td-component-border);
}

.versions-meta__label {
  color: var(--td-text-color-placeholder);
}

.versions-meta__id {
  max-width: 320px;
}

.versions-meta__actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.versions-archived {
  margin: 0 0 16px;
  font-size: 12px;
  color: var(--td-warning-color);
}

.versions-cell {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.versions-more {
  display: flex;
  justify-content: center;
  margin-top: 14px;
}

.versions-more__text {
  font-size: 12px;
}

.versions-block-title {
  margin: 18px 0 6px;
  font-size: 14px;
  font-weight: 600;
  color: var(--td-text-color-primary);
}

.versions-code {
  max-height: 260px;
  margin: 0;
  overflow: auto;
  padding: 12px;
  background: var(--td-bg-color-secondarycontainer);
  border: 1px solid var(--dda-card-border);
  border-radius: var(--td-radius-medium);
}

.versions-code--json {
  max-height: 220px;
}
</style>