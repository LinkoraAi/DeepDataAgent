<template>
  <PageSection title="定时部署" description="将 Agent 版本固化到运行环境并按 cron 调度自动开会话执行；支持手动运行、部分更新（merge-patch）、暂停 / 恢复、归档与运行记录查询。">
    <t-card :bordered="true">
      <div class="deployment-toolbar">
        <t-button theme="primary" @click="openCreateDialog">新建部署</t-button>
        <t-select v-model="statusFilter" :options="statusOptions" placeholder="状态过滤" clearable class="toolbar-status" @change="search" />
        <t-checkbox v-model="includeArchived" @change="search">包含归档</t-checkbox>
        <t-button variant="outline" @click="reload()">刷新</t-button>
      </div>

      <t-table
        :data="deployments"
        :columns="columns"
        row-key="deployment_id"
        :loading="loading"
        :pagination="false"
        :hover="true"
      >
        <template #agent="{ row }">
          <span>{{ row.agent_id }}</span>
          <t-tag size="small" variant="light" class="agent-version-tag">v{{ row.agent_version }}</t-tag>
        </template>
        <template #schedule="{ row }">
          <span v-if="row.schedule">{{ row.schedule.cron }}</span>
          <span v-else class="text-muted">无定时</span>
        </template>
        <template #status="{ row }">
          <t-tag :theme="statusTheme(row)" variant="light">{{ statusLabel(row) }}</t-tag>
          <div v-if="row.paused_reason" class="paused-reason">{{ row.paused_reason }}</div>
        </template>
        <template #nextRun="{ row }">
          <div v-if="row.next_run_at">{{ row.next_run_at }}</div>
          <div v-else class="text-muted">-</div>
          <div v-if="row.upcoming_runs_at?.length" class="upcoming-runs">
            未来：{{ row.upcoming_runs_at.slice(0, 3).join('、') }}
          </div>
        </template>
        <template #lastRun="{ row }">
          <div v-if="row.last_run_at">{{ row.last_run_at }}</div>
          <div v-else class="text-muted">-</div>
          <div v-if="row.last_status" class="upcoming-runs">上次：{{ row.last_status }}</div>
        </template>
        <template #op="{ row }">
          <div class="op-cell">
            <t-button size="small" variant="text" theme="primary" @click="openEditDialog(row)">编辑</t-button>
            <t-button size="small" variant="text" @click="openRunsDrawer(row)">运行记录</t-button>
            <template v-if="!row.archived_at">
              <t-button v-if="row.status === 'active'" size="small" variant="text" theme="primary" @click="openRunDialog(row)">运行</t-button>
              <t-button v-if="row.status === 'active'" size="small" variant="text" theme="warning" @click="openPauseDialog(row)">暂停</t-button>
              <t-button v-if="row.status === 'paused'" size="small" variant="text" theme="success" @click="handleUnpause(row)">恢复</t-button>
              <t-popconfirm content="归档后停止一切触发且不可恢复，确认归档？" @confirm="handleArchive(row)">
                <t-button size="small" variant="text" theme="danger">归档</t-button>
              </t-popconfirm>
            </template>
            <span v-if="row.archived_at" class="text-muted">已归档</span>
          </div>
        </template>
      </t-table>
      <div v-if="hasMore" class="load-more">
        <t-button variant="text" theme="primary" :loading="loading" @click="loadMore">加载更多</t-button>
      </div>
    </t-card>

    <!-- 新建部署对话框 -->
    <t-dialog
      v-model:visible="createVisible"
      header="新建定时部署"
      width="560px"
      :confirm-btn="{ content: '创建', loading: creating }"
      :cancel-btn="{}"
      @confirm="handleCreate"
    >
      <t-form :data="createForm" layout="vertical" label-align="left">
        <t-form-item label="部署名称">
          <t-input v-model="createForm.name" placeholder="部署显示名" />
        </t-form-item>
        <t-form-item label="描述（可空）">
          <t-input v-model="createForm.description" placeholder="部署用途说明" />
        </t-form-item>
        <t-form-item label="Agent">
          <t-select v-model="createForm.agentId" :options="agentOptions" placeholder="选择 Agent" filterable />
        </t-form-item>
        <t-form-item label="Agent 版本（可空）">
          <t-input-number v-model="createForm.agentVersion" :min="1" theme="column" placeholder="留空 = 固化当前激活版本" />
        </t-form-item>
        <t-form-item label="运行环境">
          <t-select v-model="createForm.environmentId" :options="environmentOptions" placeholder="选择 cloud 环境" />
        </t-form-item>
        <t-form-item label="调度 Cron（可空 = 无定时，仅手动 / webhook 触发）">
          <t-input v-model="createForm.cron" placeholder="如 0 0 9 * * *（6 段 cron）" />
        </t-form-item>
        <t-form-item label="时区（有 cron 时必填）">
          <t-input v-model="createForm.timezone" placeholder="如 Asia/Shanghai" />
        </t-form-item>
        <t-form-item label="开通 webhook 免 JWT 触发令牌">
          <t-switch v-model="createForm.webhook" />
        </t-form-item>
      </t-form>
      <p v-if="createError" class="dialog-error">{{ createError }}</p>
      <p v-if="createdWebhookToken" class="dialog-success">
        已开通 webhook：POST /api/v1/cloud/webhook/deployments/{{ createdWebhookToken }}/trigger
      </p>
    </t-dialog>

    <!-- 编辑部署对话框（merge-patch：提供才提交，清空调度 = 显式置 null） -->
    <t-dialog
      v-model:visible="editVisible"
      :header="`编辑部署（${editTarget?.name || editTarget?.deployment_id || ''}）`"
      width="560px"
      :confirm-btn="{ content: '保存', loading: editing }"
      :cancel-btn="{}"
      @confirm="handleEdit"
    >
      <t-form :data="editForm" layout="vertical" label-align="left">
        <t-form-item label="部署名称">
          <t-input v-model="editForm.name" placeholder="部署显示名" />
        </t-form-item>
        <t-form-item label="描述（清空 = 移除描述）">
          <t-input v-model="editForm.description" placeholder="部署用途说明" />
        </t-form-item>
        <t-form-item label="清空调度（转为仅手动 / webhook 触发）">
          <t-switch v-model="editForm.clearSchedule" />
        </t-form-item>
        <template v-if="!editForm.clearSchedule">
          <t-form-item label="调度 Cron（留空 = 不修改调度）">
            <t-input v-model="editForm.cron" placeholder="如 0 0 9 * * *（6 段 cron）" />
          </t-form-item>
          <t-form-item label="时区（修改 cron 时必填）">
            <t-input v-model="editForm.timezone" placeholder="如 Asia/Shanghai" />
          </t-form-item>
        </template>
      </t-form>
      <p v-if="editError" class="dialog-error">{{ editError }}</p>
    </t-dialog>

    <!-- 手动运行对话框（input 为首条用户消息，可空） -->
    <t-dialog
      v-model:visible="runVisible"
      :header="`手动运行（${runTarget?.name || runTarget?.deployment_id || ''}）`"
      width="520px"
      :confirm-btn="{ content: '运行', loading: running }"
      :cancel-btn="{}"
      @confirm="handleRun"
    >
      <t-form :data="runForm" layout="vertical" label-align="left">
        <t-form-item label="首条用户消息（可空 = 使用部署初始事件）">
          <t-textarea v-model="runForm.input" :autosize="{ minRows: 2, maxRows: 6 }" placeholder="运行时注入的用户输入文本" />
        </t-form-item>
      </t-form>
      <p v-if="runError" class="dialog-error">{{ runError }}</p>
      <p v-if="runResult" class="dialog-success">{{ runResult }}</p>
    </t-dialog>

    <!-- 暂停对话框（reason 可空） -->
    <t-dialog
      v-model:visible="pauseVisible"
      :header="`暂停部署（${pauseTarget?.name || pauseTarget?.deployment_id || ''}）`"
      width="480px"
      :confirm-btn="{ content: '暂停', loading: pausing }"
      :cancel-btn="{}"
      @confirm="handlePause"
    >
      <t-form :data="pauseForm" layout="vertical" label-align="left">
        <t-form-item label="暂停原因（可空，展示在列表）">
          <t-input v-model="pauseForm.reason" placeholder="如 暂停维护" />
        </t-form-item>
      </t-form>
      <p v-if="pauseError" class="dialog-error">{{ pauseError }}</p>
    </t-dialog>

    <!-- 运行记录抽屉（游标分页，触发时间降序） -->
    <t-drawer
      v-model:visible="runsVisible"
      :header="`运行记录（${runsTarget?.name || runsTarget?.deployment_id || ''}）`"
      size="760px"
      :footer="false"
    >
      <t-table
        :data="runs"
        :columns="runColumns"
        row-key="id"
        :loading="runsLoading"
        :pagination="false"
        :hover="true"
        size="small"
      >
        <template #trigger="{ row }">
          <t-tag size="small" variant="light">{{ triggerLabel(row.trigger) }}</t-tag>
        </template>
        <template #runStatus="{ row }">
          <t-tag size="small" :theme="runStatusTheme(row.status)" variant="light">{{ runStatusLabel(row.status) }}</t-tag>
        </template>
        <template #finished="{ row }">
          <span v-if="row.finished_at">{{ row.finished_at }}</span>
          <span v-else class="text-muted">-</span>
        </template>
        <template #session="{ row }">
          <span v-if="row.session_id" class="session-id">{{ row.session_id }}</span>
          <span v-else class="text-muted">-</span>
        </template>
      </t-table>
      <div v-if="runsHasMore" class="load-more">
        <t-button variant="text" theme="primary" :loading="runsLoading" @click="loadMoreRuns">加载更多</t-button>
      </div>
    </t-drawer>
  </PageSection>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { MessagePlugin } from 'tdesign-vue-next';
import type { PrimaryTableCol } from 'tdesign-vue-next';
import PageSection from '@/shared/components/PageSection.vue';
import { listAgents, type AgentDto } from '../api/agents';
import { listEnvironments, isSelfHosted, type EnvironmentDto } from '../api/environments';
import {
  archiveDeployment,
  createDeployment,
  listDeploymentRuns,
  listDeployments,
  pauseDeployment,
  runDeployment,
  unpauseDeployment,
  updateDeployment,
  type DeploymentDto,
  type DeploymentRunDto,
  type UpdateDeploymentPatch,
} from '../api/deployments';

const columns: PrimaryTableCol<DeploymentDto>[] = [
  { colKey: 'name', title: '名称', width: 150, ellipsis: true },
  { colKey: 'agent', title: 'Agent / 版本', width: 240, ellipsis: true },
  { colKey: 'schedule', title: 'Cron', width: 120, ellipsis: true },
  { colKey: 'status', title: '状态', width: 110 },
  { colKey: 'nextRun', title: '下次到期', width: 190 },
  { colKey: 'lastRun', title: '上次执行', width: 190 },
  { colKey: 'op', title: '操作', width: 280 },
];

const runColumns: PrimaryTableCol<DeploymentRunDto>[] = [
  { colKey: 'trigger', title: '触发方式', width: 90 },
  { colKey: 'runStatus', title: '状态', width: 90 },
  { colKey: 'started_at', title: '开始时间', width: 180, ellipsis: true },
  { colKey: 'finished', title: '结束时间', width: 180, ellipsis: true },
  { colKey: 'session', title: '会话', ellipsis: true },
];

const deployments = ref<DeploymentDto[]>([]);
const loading = ref(false);
// 游标分页状态（不提供 total）：hasMore 控制「加载更多」，lastId 为向后翻页游标
const hasMore = ref(false);
const lastId = ref<string | null>(null);
const statusFilter = ref<string>('');
const includeArchived = ref(false);

const statusOptions = [
  { label: '运行中', value: 'active' },
  { label: '已暂停', value: 'paused' },
];

const agentOptions = ref<{ label: string; value: string }[]>([]);
const environmentOptions = ref<{ label: string; value: string }[]>([]);

// —— 新建对话框 ——
const createVisible = ref(false);
const creating = ref(false);
const createError = ref('');
const createdWebhookToken = ref('');
const createForm = reactive({
  name: '',
  description: '',
  agentId: '',
  agentVersion: undefined as number | undefined,
  environmentId: '',
  cron: '',
  timezone: '',
  webhook: false,
});

// —— 编辑对话框（merge-patch） ——
const editVisible = ref(false);
const editTarget = ref<DeploymentDto | null>(null);
const editing = ref(false);
const editError = ref('');
const editForm = reactive({
  name: '',
  description: '',
  cron: '',
  timezone: '',
  clearSchedule: false,
});

// —— 手动运行对话框 ——
const runVisible = ref(false);
const runTarget = ref<DeploymentDto | null>(null);
const running = ref(false);
const runError = ref('');
const runResult = ref('');
const runForm = reactive({ input: '' });

// —— 暂停对话框 ——
const pauseVisible = ref(false);
const pauseTarget = ref<DeploymentDto | null>(null);
const pausing = ref(false);
const pauseError = ref('');
const pauseForm = reactive({ reason: '' });

// —— 运行记录抽屉 ——
const runsVisible = ref(false);
const runsTarget = ref<DeploymentDto | null>(null);
const runs = ref<DeploymentRunDto[]>([]);
const runsLoading = ref(false);
const runsHasMore = ref(false);
const runsLastId = ref<string | null>(null);

function statusLabel(row: DeploymentDto): string {
  if (row.archived_at) return '已归档';
  if (row.status === 'active') return '运行中';
  if (row.status === 'paused') return '已暂停';
  return row.status;
}

function statusTheme(row: DeploymentDto): 'success' | 'warning' | 'default' {
  if (row.archived_at) return 'default';
  if (row.status === 'active') return 'success';
  if (row.status === 'paused') return 'warning';
  return 'default';
}

function triggerLabel(trigger: string): string {
  if (trigger === 'cron') return '定时';
  if (trigger === 'manual') return '手动';
  if (trigger === 'webhook') return 'Webhook';
  return trigger;
}

function runStatusLabel(status: string): string {
  if (status === 'running') return '执行中';
  if (status === 'succeeded') return '成功';
  if (status === 'failed') return '失败';
  if (status === 'terminated') return '已终止';
  return status;
}

function runStatusTheme(status: string): 'success' | 'warning' | 'danger' | 'default' {
  if (status === 'succeeded') return 'success';
  if (status === 'running') return 'warning';
  if (status === 'failed' || status === 'terminated') return 'danger';
  return 'default';
}

/** 加载调度器列表（游标分页：append = 向后翻页追加，否则重载首页；失败统一 warning 提示，不外抛污染调用方动作语义）。 */
async function reload(append = false): Promise<void> {
  loading.value = true;
  try {
    const page = await listDeployments({
      status: statusFilter.value || undefined,
      includeArchived: includeArchived.value || undefined,
      limit: 20,
      afterId: append ? lastId.value ?? undefined : undefined,
    });
    deployments.value = append ? [...deployments.value, ...page.data] : page.data;
    hasMore.value = page.has_more;
    lastId.value = page.last_id;
  } catch (e) {
    MessagePlugin.warning(`列表刷新失败: ${(e as Error).message}`);
  } finally {
    loading.value = false;
  }
}

/** 过滤条件变更后回首页重新加载。 */
function search(): void {
  void reload();
}

/** 加载更多（向后翻页）。 */
function loadMore(): void {
  void reload(true);
}

/** 下拉选项游标翻页安全上限：5 页 × 100 条（防游标漂移导致无界回补）。 */
const OPTION_PAGE_SIZE = 100;
const OPTION_MAX_PAGES = 5;

/** Agent 列表游标续拉全集（after_id=last_id 循环，直至 has_more=false / last_id 空或达安全上限）。 */
async function fetchAllAgents(): Promise<AgentDto[]> {
  const items: AgentDto[] = [];
  let afterId: string | undefined;
  for (let page = 0; page < OPTION_MAX_PAGES; page += 1) {
    const result = await listAgents({ limit: OPTION_PAGE_SIZE, afterId });
    items.push(...result.data);
    if (!result.has_more || !result.last_id) {
      break;
    }
    afterId = result.last_id;
  }
  return items;
}

/** 环境列表游标续拉全集（游标语义同上）。 */
async function fetchAllEnvironments(): Promise<EnvironmentDto[]> {
  const items: EnvironmentDto[] = [];
  let afterId: string | undefined;
  for (let page = 0; page < OPTION_MAX_PAGES; page += 1) {
    const result = await listEnvironments({ limit: OPTION_PAGE_SIZE, afterId });
    items.push(...result.data);
    if (!result.has_more || !result.last_id) {
      break;
    }
    afterId = result.last_id;
  }
  return items;
}

/** 加载 Agent / cloud 环境下拉选项（游标续拉至多 500 条；filterable 为本地过滤，不受翻页影响）。 */
async function loadOptions(): Promise<void> {
  const [agentItems, envItems] = await Promise.all([fetchAllAgents(), fetchAllEnvironments()]);
  agentOptions.value = agentItems
    .filter((agent) => !agent.archived_at)
    .map((agent) => ({ label: `${agent.name}（${agent.id}）`, value: agent.id }));
  environmentOptions.value = envItems
    .filter((env) => !isSelfHosted(env.config?.type))
    .map((env) => ({ label: env.name, value: env.id }));
}

function openCreateDialog(): void {
  createForm.name = '';
  createForm.description = '';
  createForm.agentId = '';
  createForm.agentVersion = undefined;
  createForm.environmentId = '';
  createForm.cron = '';
  createForm.timezone = '';
  createForm.webhook = false;
  createError.value = '';
  createdWebhookToken.value = '';
  createVisible.value = true;
}

async function handleCreate(): Promise<void> {
  if (!createForm.name.trim() || !createForm.agentId || !createForm.environmentId) {
    createError.value = '名称、Agent 与运行环境为必填项';
    return;
  }
  if (createForm.cron.trim() && !createForm.timezone.trim()) {
    createError.value = '配置 cron 时必须填写 IANA 时区';
    return;
  }
  creating.value = true;
  createError.value = '';
  let creationSucceeded = false;
  try {
    const created = await createDeployment({
      name: createForm.name.trim(),
      description: createForm.description.trim() || null,
      agent_id: createForm.agentId,
      agent_version: createForm.agentVersion ?? null,
      environment_id: createForm.environmentId,
      schedule: createForm.cron.trim()
        ? { cron: createForm.cron.trim(), timezone: createForm.timezone.trim() }
        : null,
      webhook: createForm.webhook,
    });
    createdWebhookToken.value = created.webhook_token ?? '';
    if (!created.webhook_token) {
      createVisible.value = false;
    }
    creationSucceeded = true;
  } catch (e) {
    // 创建失败才回显到对话框（列表刷新失败经 reload 的 warning 单独提示，不误报为创建失败）
    createError.value = (e as Error).message;
  } finally {
    creating.value = false;
  }
  if (creationSucceeded) {
    await reload();
  }
}

function openEditDialog(row: DeploymentDto): void {
  editTarget.value = row;
  editForm.name = row.name;
  editForm.description = row.description ?? '';
  editForm.cron = row.schedule?.cron ?? '';
  editForm.timezone = row.schedule?.timezone ?? '';
  editForm.clearSchedule = !row.schedule;
  editError.value = '';
  editVisible.value = true;
}

async function handleEdit(): Promise<void> {
  if (!editTarget.value) {
    return;
  }
  if (!editForm.name.trim()) {
    editError.value = '名称为必填项，不可清空';
    return;
  }
  if (!editForm.clearSchedule && editForm.cron.trim() && !editForm.timezone.trim()) {
    editError.value = '修改 cron 时必须填写 IANA 时区';
    return;
  }
  editing.value = true;
  editError.value = '';
  try {
    // merge-patch 三态：名称 / 描述总是提交（描述留空 = 显式 null 清空）；
    // 调度按开关与输入裁决（清空调度 = 显式 null，cron 留空且未开清空 = 不提交该键）
    const patch: UpdateDeploymentPatch = {
      name: editForm.name.trim(),
      description: editForm.description.trim() || null,
    };
    if (editForm.clearSchedule) {
      patch.schedule = null;
    } else if (editForm.cron.trim()) {
      patch.schedule = { cron: editForm.cron.trim(), timezone: editForm.timezone.trim() };
    }
    await updateDeployment(editTarget.value.deployment_id, patch);
    editVisible.value = false;
    await reload();
  } catch (e) {
    editError.value = (e as Error).message;
  } finally {
    editing.value = false;
  }
}

function openRunDialog(row: DeploymentDto): void {
  runTarget.value = row;
  runForm.input = '';
  runError.value = '';
  runResult.value = '';
  runVisible.value = true;
}

async function handleRun(): Promise<void> {
  if (!runTarget.value) {
    return;
  }
  running.value = true;
  runError.value = '';
  try {
    const result = await runDeployment(runTarget.value.deployment_id, runForm.input.trim() || null);
    runResult.value = `已运行：run=${result.run_id}，会话=${result.session_id}`;
    await reload();
  } catch (e) {
    runError.value = (e as Error).message;
  } finally {
    running.value = false;
  }
}

/** 打开运行记录抽屉并重载首页。 */
function openRunsDrawer(row: DeploymentDto): void {
  runsTarget.value = row;
  runs.value = [];
  runsHasMore.value = false;
  runsLastId.value = null;
  runsVisible.value = true;
  void loadRuns();
}

/** 加载运行记录（游标分页：append = 向后翻页追加）。 */
async function loadRuns(append = false): Promise<void> {
  if (!runsTarget.value) {
    return;
  }
  runsLoading.value = true;
  try {
    const page = await listDeploymentRuns(runsTarget.value.deployment_id, {
      limit: 20,
      afterId: append ? runsLastId.value ?? undefined : undefined,
    });
    runs.value = append ? [...runs.value, ...page.data] : page.data;
    runsHasMore.value = page.has_more;
    runsLastId.value = page.last_id;
  } catch (e) {
    runs.value = append ? runs.value : [];
    runsHasMore.value = false;
    window.alert((e as Error).message);
  } finally {
    runsLoading.value = false;
  }
}

function loadMoreRuns(): void {
  void loadRuns(true);
}

function openPauseDialog(row: DeploymentDto): void {
  pauseTarget.value = row;
  pauseForm.reason = '';
  pauseError.value = '';
  pauseVisible.value = true;
}

async function handlePause(): Promise<void> {
  if (!pauseTarget.value) {
    return;
  }
  pausing.value = true;
  pauseError.value = '';
  try {
    await pauseDeployment(pauseTarget.value.deployment_id, pauseForm.reason.trim() || null);
    pauseVisible.value = false;
    await reload();
  } catch (e) {
    pauseError.value = (e as Error).message;
  } finally {
    pausing.value = false;
  }
}

async function handleUnpause(row: DeploymentDto): Promise<void> {
  try {
    await unpauseDeployment(row.deployment_id);
  } catch (e) {
    MessagePlugin.error(`恢复失败: ${(e as Error).message}`);
    return;
  }
  await reload();
}

async function handleArchive(row: DeploymentDto): Promise<void> {
  try {
    await archiveDeployment(row.deployment_id);
  } catch (e) {
    MessagePlugin.error(`归档失败: ${(e as Error).message}`);
    return;
  }
  await reload();
}

onMounted(() => {
  void reload();
  void loadOptions();
});
</script>

<style scoped>
.deployment-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}

.toolbar-status {
  width: 140px;
}

.load-more {
  display: flex;
  justify-content: center;
  margin-top: 8px;
}

.op-cell {
  display: flex;
  align-items: center;
  gap: 4px;
}

.agent-version-tag {
  margin-left: 8px;
}

.session-id {
  font-family: monospace;
  font-size: 12px;
}

.paused-reason,
.upcoming-runs,
.text-muted {
  color: var(--td-text-color-secondary);
  font-size: 12px;
}

.dialog-error {
  margin: 8px 0 0;
  color: var(--td-error-color);
  font-size: 13px;
}

.dialog-success {
  margin: 8px 0 0;
  color: var(--td-success-color);
  font-size: 13px;
  word-break: break-all;
}
</style>
