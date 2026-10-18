<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { MessagePlugin } from 'tdesign-vue-next';
import type { PrimaryTableCol } from 'tdesign-vue-next';
import EmptyGuide from '@/shared/components/EmptyGuide.vue';
import FormSection from '@/shared/components/FormSection.vue';
import KvEditor from '@/shared/components/KvEditor.vue';
import { LAYER } from '@/shared/constants/layers';
import { errorMessage, useAsync } from '@/shared/composables/useAsync';
import type { CursorPage } from '@/shared/types/api';
import { listEnvironments } from '@/modules/environment/api';
import type { EnvironmentWire } from '@/modules/environment/api';
import { listFiles } from '@/modules/assetfile/api';
import type { FileWire } from '@/modules/assetfile/api';
import { createSession, listActiveAgentsLite, sendUserMessage } from '../api/sessions';
import type { AgentLite } from '../api/sessions';
import type { ChatEventWire, CreateSessionPayload, SessionWire } from '../types';
import {
  buildCreatePayload,
  createEmptySessionForm,
  defaultMountPath,
  estimateResourceBytes,
  formatBytes,
  validateMountPath,
  validateResourceQuota,
  validateSessionCreateForm,
} from '../mappers';
import type { SessionCreateForm } from '../mappers';

/**
 * 新建会话对话框。
 * <p>字段与门禁对齐后端 CreateSessionRequest 与创建会话 spec：智能体必填（仅 active 可选）、
 * 运行环境必填（仅 {@code config.type === 'cloud'}，self_hosted 后端 400 故不呈现）、
 * 标题可选、环境变量键值对（值全字符串提交，后端不回显）、挂载文件（默认挂载路径
 * {@code mounts/{file_id}}，自定义必须 mounts/ 前缀，总量 ≤500MB）、初始消息可选。</p>
 * <p>提交编排：创建会话 → 有初始消息时补发 user.message 事件（失败仅提示、保留会话）→
 * 提示成功并跳转工作区。后端 400 / 409 直显 message 并保留对话框。</p>
 */
const emit = defineEmits<{
  /** 会话创建成功（携带后端返回的会话对象） */
  created: [session: SessionWire];
}>();

/** 对话框显隐（v-model:visible）。 */
const visible = defineModel<boolean>('visible', { required: true });

/** 文件列表每页条数。 */
const FILE_PAGE_SIZE = 20;

/** 仅 cloud 执行平面可选（对齐后端创建会话门禁）。 */
const CLOUD_ENVIRONMENT_TYPE = 'cloud';

const router = useRouter();

/** 创建表单（reactive 承载嵌套的资源草稿与环境变量）。 */
const form = reactive<SessionCreateForm>(createEmptySessionForm());

/** 活跃智能体列表。 */
const agents = ref<AgentLite[]>([]);
/** 运行环境列表（后端全量，展示前按 cloud 过滤）。 */
const environments = ref<EnvironmentWire[]>([]);
/** 可选文件列表。 */
const files = ref<FileWire[]>([]);
/** 文件列表下一页游标（null = 已到末页）。 */
const filesCursor = ref<string | null>(null);

/** 字段级校验提示（前端拦截）。 */
const fieldError = ref('');
/** 后端错误提示（400/409 直显并保留对话框）。 */
const serverError = ref('');

const agentsTask = useAsync<AgentLite[]>();
const envTask = useAsync<CursorPage<EnvironmentWire>>();
const filesTask = useAsync<void>();
const submitTask = useAsync<SessionWire>();
const sendTask = useAsync<ChatEventWire[]>();

/** 智能体下拉加载中（超时/失败后必然归位，不再无限转圈）。 */
const agentsLoading = agentsTask.loading;
/** 智能体加载失败原因（内联错误态 + 重试入口）。 */
const agentsError = agentsTask.error;

/** 下拉选项：智能体（名称缺省回落 ID，便于识别脏数据）。 */
const agentOptions = computed(() => agents.value.map((item) => ({ label: item.name || item.id, value: item.id })));

/** 下拉选项：仅 cloud 类型环境可选。 */
const cloudOptions = computed(() =>
  environments.value
    .filter((item) => item.config?.type === CLOUD_ENVIRONMENT_TYPE)
    .map((item) => ({ label: item.name || item.id, value: item.id })),
);

/** 环境列表加载中（用于区分"空态"与"尚未加载"）。 */
const envLoading = envTask.loading;
/** 环境列表加载失败原因（失败态优先于空态展示，避免误报"无可用环境"）。 */
const envError = envTask.error;

/** 挂载路径逐行错误（fileId → 文案）。 */
const mountErrors = computed<Record<string, string>>(() => {
  const out: Record<string, string> = {};
  for (const item of form.resources) {
    const message = validateMountPath(item.mountPath);
    if (message) {
      out[item.fileId] = message;
    }
  }
  return out;
});

/** 挂载总量（字节）。 */
const totalBytes = computed(() => estimateResourceBytes(form.resources));

/** 配额超限文案（空串 = 通过）。 */
const quotaError = computed(() => validateResourceQuota(form.resources));

/** 必填项是否齐备（缺失时禁用"开始会话"）。 */
const canSubmit = computed(() => Boolean(form.agentId) && Boolean(form.environmentId));

/** 提交中（创建会话或补发初始消息任一进行中）；必须取 .value，否则拿到 Ref 对象恒为真值导致按钮永久 loading。 */
const submitting = computed(() => submitTask.loading.value || sendTask.loading.value);

/** 是否还有更多文件可加载。 */
const hasMoreFiles = computed(() => Boolean(filesCursor.value));

/** 挂载文件表格加载中（超时/失败后必然归位）。 */
const filesLoading = filesTask.loading;
/** 挂载文件表格加载失败原因（内联错误态 + 重试入口）。 */
const filesError = filesTask.error;

const fileColumns: PrimaryTableCol[] = [
  { title: '选择', colKey: 'pick', width: 70, cell: 'pick' },
  { title: '文件名', colKey: 'filename', minWidth: 200, ellipsis: true },
  { title: '大小', colKey: 'size_bytes', width: 100, cell: 'size' },
  { title: '状态', colKey: 'status', width: 90, cell: 'status' },
];

/** 打开时加载三份下拉/表格数据源（每次打开刷新，避免新环境/文件不可见）。 */
watch(visible, (opened) => {
  if (opened) {
    void loadAgents();
    void loadEnvironments();
    void loadFiles(false);
  }
});

/** 加载活跃智能体（失败经 agentsError 内联展示，可重试）。 */
async function loadAgents(): Promise<void> {
  const list = await agentsTask.run(() => listActiveAgentsLite());
  agents.value = list ?? [];
}

/** 加载运行环境（过滤在计算属性内做；失败经 envError 内联展示，可重试）。 */
async function loadEnvironments(): Promise<void> {
  const page = await envTask.run(() => listEnvironments({ limit: 100 }));
  environments.value = page?.data ?? [];
}

/** 加载一页文件（append=true 沿后端 nextCursor 续拉；失败经 filesError 内联展示，可重试）。 */
async function loadFiles(append: boolean): Promise<void> {
  await filesTask.run(async () => {
    const resp = await listFiles({
      cursor: append ? filesCursor.value ?? undefined : undefined,
      limit: FILE_PAGE_SIZE,
    });
    const list = resp.data ?? [];
    files.value = append ? [...files.value, ...list] : list;
    filesCursor.value = resp.nextCursor ?? null;
  });
}

/** 判断文件是否已勾选挂载。 */
function isPicked(fileId: string): boolean {
  return form.resources.some((item) => item.fileId === fileId);
}

/** 勾选 / 取消勾选文件（勾选即以缺省挂载路径 mounts/{file_id} 入草稿，可再手工改写）。 */
function toggleFile(file: FileWire): void {
  const index = form.resources.findIndex((item) => item.fileId === file.id);
  if (index >= 0) {
    form.resources.splice(index, 1);
    return;
  }
  form.resources.push({
    fileId: file.id,
    filename: file.filename,
    sizeBytes: file.size_bytes,
    mountPath: defaultMountPath(file.id),
  });
}

/** 移除一条挂载草稿。 */
function removeResource(fileId: string): void {
  const index = form.resources.findIndex((item) => item.fileId === fileId);
  if (index >= 0) {
    form.resources.splice(index, 1);
  }
}

/** 载荷装配（装配器内部含完整校验，异常转为可展示文案）。 */
function buildPayload(target: SessionCreateForm): { payload: CreateSessionPayload | null; error: string } {
  try {
    return { payload: buildCreatePayload(target), error: '' };
  } catch (e) {
    return { payload: null, error: errorMessage(e) };
  }
}

/** 关闭后复位表单与提示（数据源保留，下次打开刷新）。 */
function resetState(): void {
  Object.assign(form, createEmptySessionForm());
  fieldError.value = '';
  serverError.value = '';
}

/**
 * 提交编排：校验 → 创建会话 → 可选补发初始消息 → 跳转工作区。
 * <p>初始消息发送失败仅提示（会话已存在，保留并进入工作区后可重发）；
 * 创建失败（400/409）保留对话框直显后端原因。</p>
 */
async function submit(): Promise<void> {
  fieldError.value = '';
  serverError.value = '';
  const errors = validateSessionCreateForm(form);
  const firstError = errors.agentId ?? errors.environmentId ?? errors.resources ?? errors.envVars;
  if (firstError) {
    fieldError.value = firstError;
    return;
  }
  const built = buildPayload(form);
  if (!built.payload) {
    fieldError.value = built.error;
    return;
  }
  const payload = built.payload;
  const created = await submitTask.run(() => createSession(payload));
  if (!created) {
    serverError.value = errorMessage(submitTask.error.value ?? '会话创建失败');
    return;
  }
  const text = form.initialMessage.trim();
  if (text) {
    const sent = await sendTask.run(() => sendUserMessage(created.id, text));
    if (!sent) {
      MessagePlugin.warning(`会话已创建，但初始消息发送失败：${errorMessage(sendTask.error.value)}`);
    }
  }
  MessagePlugin.success('会话已创建');
  emit('created', created);
  visible.value = false;
  resetState();
  router.push(`/sessions/${created.id}`);
}
</script>

<template>
  <t-dialog
    v-model:visible="visible"
    header="新建会话"
    :width="880"
    :close-on-overlay-click="false"
    destroy-on-close
    placement="center"
    attach="body"
    :z-index="LAYER.dialog"
    @closed="resetState"
  >
    <FormSection title="智能体与运行环境" hint="智能体绑定激活版本快照；运行环境仅 cloud 类型可选（self_hosted 不可执行）">
      <div class="session-create__stack">
        <div class="session-create__field">
          <label class="session-create__label">智能体（必填）</label>
          <t-select
            v-model="form.agentId"
            :options="agentOptions"
            :loading="agentsLoading"
            placeholder="选择活跃智能体"
            filterable
            clearable
          />
          <t-alert
            v-if="agentsError"
            theme="error"
            class="session-create__inline-alert"
            :message="`智能体加载失败：${errorMessage(agentsError)}`"
          >
            <template #operation>
              <t-button size="small" variant="outline" :loading="agentsLoading" @click="loadAgents">重试</t-button>
            </template>
          </t-alert>
        </div>
        <div class="session-create__field">
          <label class="session-create__label">运行环境（必填）</label>
          <t-select
            v-if="cloudOptions.length || envLoading"
            v-model="form.environmentId"
            :options="cloudOptions"
            :loading="envLoading"
            placeholder="选择 cloud 环境"
            filterable
            clearable
          />
          <t-alert
            v-else-if="envError"
            theme="error"
            class="session-create__inline-alert"
            :message="`运行环境加载失败：${errorMessage(envError)}`"
          >
            <template #operation>
              <t-button size="small" variant="outline" :loading="envLoading" @click="loadEnvironments">重试</t-button>
            </template>
          </t-alert>
          <EmptyGuide v-else description="没有可用于新建会话的 cloud 运行环境，请先创建一个云端环境">
            <template #action>
              <router-link to="/environments">
                <t-button theme="primary" size="small" variant="outline">去创建环境</t-button>
              </router-link>
            </template>
          </EmptyGuide>
        </div>
        <div class="session-create__field">
          <label class="session-create__label">会话标题（可选）</label>
          <t-input v-model="form.title" placeholder="留空则在工作区显示「未命名会话」" maxlength="120" clearable />
        </div>
      </div>
    </FormSection>

    <FormSection title="会话环境变量" hint="仅写入会话，后端在任何响应与 Tab 中均不回显键名与值；值一律按字符串提交">
      <KvEditor
        v-model="form.envVars"
        key-placeholder="变量名（字母/下划线开头）"
        value-placeholder="值（字符串）"
        :max-rows="64"
      />
    </FormSection>

    <FormSection title="挂载文件" hint="从会话文件库勾选；挂载路径必须为 mounts/ 前缀的工作区相对路径，缺省 mounts/{file_id}；挂载总量 ≤500MB">
      <div class="dda-table session-create__files">
        <t-table
          row-key="id"
          :data="files"
          :columns="fileColumns"
          :loading="filesLoading"
          size="small"
          max-height="260"
        >
          <template #pick="{ row }">
            <t-checkbox
              :checked="isPicked(row.id)"
              :disabled="row.status !== 'ready'"
              @change="toggleFile(row)"
            />
          </template>
          <template #size="{ row }">{{ formatBytes(row.size_bytes) }}</template>
          <template #status="{ row }">
            <t-tag :theme="row.status === 'ready' ? 'success' : 'warning'" variant="light" size="small">
              {{ row.status === 'ready' ? '就绪' : row.status }}
            </t-tag>
          </template>
          <template #empty>
            <EmptyGuide v-if="!filesError" description="文件库暂无可挂载的文本文件，可先在「会话文件」页上传" />
          </template>
        </t-table>
      </div>
      <t-alert
        v-if="filesError"
        theme="error"
        class="session-create__inline-alert"
        :message="`挂载文件加载失败：${errorMessage(filesError)}`"
      >
        <template #operation>
          <t-button size="small" variant="outline" :loading="filesLoading" @click="loadFiles(false)">重试</t-button>
        </template>
      </t-alert>
      <div v-if="hasMoreFiles" class="session-create__more">
        <t-button variant="text" theme="primary" :loading="filesLoading" @click="loadFiles(true)">
          加载更多文件
        </t-button>
      </div>

      <div v-if="form.resources.length" class="session-create__mounts">
        <div v-for="item in form.resources" :key="item.fileId" class="session-create__mount-row">
          <span class="session-create__mount-name" :title="item.fileId">{{ item.filename || item.fileId }}</span>
          <t-input v-model="item.mountPath" placeholder="mounts/…" class="session-create__mount-path" />
          <t-link theme="danger" hover="color" @click="removeResource(item.fileId)">移除</t-link>
        </div>
        <p v-if="Object.keys(mountErrors).length" class="session-create__error">
          {{ Object.values(mountErrors)[0] }}
        </p>
        <p class="dda-notice session-create__total">
          <t-icon name="info-circle" size="16px" />
          <span>已选 {{ form.resources.length }} 个文件，合计 {{ formatBytes(totalBytes) }}（上限 500 MB）</span>
        </p>
        <p v-if="quotaError" class="session-create__error">{{ quotaError }}</p>
      </div>
      <p v-else class="session-create__empty dda-faint">未配置资源，从上方文件库勾选要挂载的文件</p>
    </FormSection>

    <FormSection title="初始消息" hint="后端创建接口不接收消息：会话创建成功后由前端补发一条 user.message 事件">
      <t-textarea
        v-model="form.initialMessage"
        placeholder="可选：会话创建后立即发出的第一条用户消息"
        :autosize="{ minRows: 3, maxRows: 8 }"
      />
    </FormSection>

    <p v-if="fieldError" class="session-create__error">{{ fieldError }}</p>
    <t-alert v-if="serverError" theme="error" :message="serverError" class="session-create__alert" />

    <template #footer>
      <div class="session-create__footer">
        <t-space>
          <t-button variant="outline" @click="visible = false">取消</t-button>
          <t-button theme="primary" :disabled="!canSubmit || submitting" :loading="submitting" @click="submit">
            开始会话
          </t-button>
        </t-space>
      </div>
    </template>
  </t-dialog>
</template>

<style scoped>
/* 字段纵向堆叠、大间距（对齐 Cloud Agents 控制台弹窗形态） */
.session-create__stack {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.session-create__field {
  min-width: 0;
}

/* 小号加粗字段标签 */
.session-create__label {
  display: block;
  margin-bottom: 6px;
  font-size: 13px;
  font-weight: 600;
  color: var(--td-text-color-primary);
}

/* 挂载文件选择表：复用全局 .dda-table 表头/悬浮样式 */
.session-create__files {
  border: 1px solid var(--dda-card-border);
  border-radius: var(--td-radius-medium);
  overflow: hidden;
}

.session-create__more {
  margin-top: 8px;
  text-align: center;
}

.session-create__mounts {
  margin-top: 12px;
}

.session-create__mount-row {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 8px;
}

.session-create__mount-name {
  flex: 0 0 240px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.session-create__mount-path {
  flex: 1 1 auto;
}

.session-create__total {
  margin: 8px 0 0;
}

.session-create__empty {
  margin: 12px 0 0;
  font-size: 12px;
  text-align: center;
}

.session-create__error {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--td-error-color);
}

.session-create__alert {
  margin-top: 12px;
}

/* 智能体/环境/文件加载失败的内联错误条（含重试按钮） */
.session-create__inline-alert {
  margin-top: 8px;
}

/* 页脚右对齐 */
.session-create__footer {
  display: flex;
  justify-content: flex-end;
}
</style>