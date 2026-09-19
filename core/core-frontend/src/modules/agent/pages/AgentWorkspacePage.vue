<template>
  <div class="agent-chat">
    <aside class="agent-chat__side">
      <t-card title="Agent 配置" :bordered="true" class="side-card">
        <div class="agent-config">
          <t-select
            v-model="selectedAgentId"
            :options="agentOptions"
            placeholder="选择 Agent"
            :clearable="false"
            @change="handleAgentChange"
          />
          <t-select
            v-model="selectedEnvironmentId"
            :options="environmentOptions"
            placeholder="默认运行环境（新建会话用）"
          />
        </div>
      </t-card>

      <t-card title="会话" :bordered="true" class="side-card">
        <template #actions>
          <t-button size="small" theme="primary" variant="text" @click="openCreateDialog">新建</t-button>
        </template>
        <ul v-if="sessions.length" class="session-list">
          <li
            v-for="item in sessions"
            :key="item.id"
            class="session-item"
            :class="{ 'session-item--active': item.id === currentSessionId }"
            @click="selectSession(item.id)"
          >
            <div class="session-item__head">
              <span class="session-item__title">{{ item.title || item.id }}</span>
              <t-tag size="small" variant="light" :theme="statusTheme(item.status)">{{ statusLabel(item.status) }}</t-tag>
            </div>
            <p class="session-item__meta">{{ formatTime(item.created_at) }}</p>
          </li>
        </ul>
        <p v-else class="muted">暂无会话，点击「新建」创建</p>
        <div v-if="currentSession && !isArchived" class="session-actions">
          <t-popconfirm content="归档后会话只读，确认归档？" @confirm="handleArchive">
            <t-button size="small" variant="text">归档</t-button>
          </t-popconfirm>
          <t-popconfirm content="删除后会话与事件流一并清理且不可恢复，确认删除？" @confirm="handleDelete">
            <t-button size="small" variant="text" theme="danger">删除</t-button>
          </t-popconfirm>
        </div>
      </t-card>

      <t-card title="挂载资源" :bordered="true" class="side-card">
        <p v-if="!currentSession" class="muted">请先选择会话</p>
        <template v-else>
          <ul v-if="fileResources.length" class="resource-list">
            <li v-for="res in fileResources" :key="res.id || res.file_id" class="resource-item">
              <span class="resource-item__file">{{ res.file_id }}</span>
              <span class="resource-item__path">{{ res.mount_path || `mounts/${res.file_id}` }}</span>
            </li>
          </ul>
          <p v-else class="muted">无挂载文件</p>
          <p class="muted resource-hint">文件挂载后在沙箱 /workspace/mounts/ 下只读可见，不可写入</p>
          <div v-if="canAppendResources" class="resource-append">
            <input ref="resourceFileInput" type="file" hidden @change="handleResourceFilePicked" />
            <t-button size="small" variant="outline" block :loading="appendingResource" @click="pickResourceFile">
              上传并挂载文件
            </t-button>
          </div>
          <p v-if="resourceError" class="side-error">{{ resourceError }}</p>
        </template>
      </t-card>
    </aside>

    <section class="agent-chat__main">
      <div class="agent-chat__header">
        <template v-if="currentSession">
          <span class="agent-chat__session-id">{{ currentSessionId }}</span>
          <t-tag size="small" :theme="statusTheme(sessionStatus)" variant="light">{{ statusLabel(sessionStatus) }}</t-tag>
          <t-tag v-if="threadStatus" size="small" theme="default" variant="outline">
            主线程：{{ statusLabel(threadStatus) }}
          </t-tag>
          <t-tag v-if="isArchived" size="small" theme="default" variant="light">已归档</t-tag>
        </template>
        <span v-else class="muted">未选择会话</span>
      </div>

      <div ref="messageScroller" class="agent-chat__messages">
        <div v-if="loadingHistory" class="agent-chat__loading">
          <t-loading size="small" />
        </div>
        <div
          v-for="message in messages"
          :key="message.id"
          class="message-row"
          :class="`message-row--${message.role}`"
        >
          <div v-if="message.role === 'system'" class="message-system">{{ message.content }}</div>
          <div v-else class="message-bubble">
            <div v-if="message.thinking && message.role === 'assistant'" class="message-thinking">
              <t-tag theme="warning" variant="light" size="small">推理</t-tag>
              <p>{{ message.thinking }}</p>
            </div>
            <div v-if="message.toolTraces.length" class="message-tools">
              <div v-for="(trace, index) in message.toolTraces" :key="index" class="message-tools__item">
                <t-tag theme="success" variant="light" size="small">工具</t-tag>
                <span>{{ trace }}</span>
              </div>
            </div>
            <div v-if="message.images.length" class="message-images">
              <img v-for="(src, index) in message.images" :key="index" :src="src" class="message-image" alt="消息图片" />
            </div>
            <p v-if="message.content" class="message-content">{{ message.content }}</p>
          </div>
        </div>
      </div>

      <!-- HITL 工具执行确认条：由未应答的 agent.tool_use 事件驱动（锚点为该事件公开 evt_ ID） -->
      <div v-if="pendingConfirm && isRunning" class="agent-chat__confirm">
        <t-tag theme="warning" variant="light">等待确认</t-tag>
        <span class="agent-chat__confirm-text">
          是否允许执行工具「{{ pendingConfirm.name }}」？拒绝后执行将按被拒结果继续推进。
        </span>
        <t-button size="small" theme="primary" :loading="confirming" @click="handleConfirm(true)">
          允许
        </t-button>
        <t-button size="small" variant="outline" theme="danger" :loading="confirming" @click="handleConfirm(false)">
          拒绝
        </t-button>
      </div>

      <div class="agent-chat__input">
        <t-textarea
          v-model="draft"
          :autosize="{ minRows: 1, maxRows: 4 }"
          :placeholder="isRunning ? '执行中，暂不可发送消息（可中断）' : '输入消息，Enter 发送 / Shift+Enter 换行'"
          :disabled="sending || !currentSessionId || isTerminal || isRunning"
          @keydown.enter.exact.prevent="handleSend"
        />
        <t-button
          v-if="isRunning"
          theme="warning"
          variant="outline"
          :loading="cancelling"
          @click="handleInterrupt"
        >
          中断
        </t-button>
        <t-button
          theme="primary"
          :loading="sending"
          :disabled="!draft.trim() || !currentSessionId || isTerminal || isRunning"
          @click="handleSend"
        >
          发送
        </t-button>
      </div>
      <p v-if="error" class="agent-chat__footer-error">{{ error }}</p>
    </section>

    <!-- 新建会话对话框（Agent + 环境 + 标题 + 挂载文件 + Vault） -->
    <t-dialog
      v-model:visible="createVisible"
      header="新建会话"
      width="560px"
      :confirm-btn="{ content: '创建', loading: creating }"
      :cancel-btn="{}"
      @confirm="handleCreateSession"
    >
      <t-form :data="createForm" layout="vertical" label-align="left">
        <t-form-item label="会话标题">
          <t-input v-model="createForm.title" placeholder="可选，便于在会话列表中识别" />
        </t-form-item>
        <t-form-item label="运行环境">
          <t-select v-model="createForm.environmentId" :options="environmentOptions" placeholder="选择运行环境" />
        </t-form-item>
        <t-form-item label="挂载文件">
          <div class="dialog-files">
            <input ref="dialogFileInput" type="file" multiple hidden @change="handleDialogFilesPicked" />
            <t-button size="small" variant="outline" :loading="uploadingFiles" @click="pickDialogFiles">
              选择文件并上传
            </t-button>
            <ul v-if="stagedFiles.length" class="staged-list">
              <li v-for="(item, index) in stagedFiles" :key="item.id" class="staged-item">
                <div class="staged-item__body">
                  <div class="staged-item__head">
                    <span>{{ item.filename }}</span>
                    <t-button size="small" variant="text" theme="danger" @click="removeStagedFile(index)">
                      移除
                    </t-button>
                  </div>
                  <t-input
                    v-model="mountPaths[item.id]"
                    size="small"
                    class="staged-item__path"
                    placeholder="挂载路径（留空缺省 mounts/&lt;file_id&gt;；自定义须 mounts/ 前缀相对路径，沙箱内只读）"
                  />
                </div>
              </li>
            </ul>
          </div>
        </t-form-item>
        <t-form-item label="Vault 凭证">
          <t-select
            v-model="createForm.vaultIds"
            :options="vaultOptions"
            multiple
            filterable
            clearable
            placeholder="选择注入会话的凭证 Vault（可选）"
          />
        </t-form-item>
      </t-form>
      <p v-if="createError" class="dialog-error">{{ createError }}</p>
    </t-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue';
import {
  CHAT_EVENT_TYPES,
  SESSION_STATUS_PREFIX,
  THREAD_STATUS_PREFIX,
  appendSessionResources,
  archiveSession,
  cancelSession,
  createSession,
  deleteSession,
  getSession,
  listEvents,
  listSessions,
  openEventStream,
  sendUserMessage,
  sendUserToolConfirmation,
  type EventContentBlock,
  type EventEnvelope,
  type EventStreamHandle,
  type SessionDto,
  type SessionErrorDetail,
  type SessionResourceInput,
  type SessionStatus,
  type StopReason,
} from '../api/agent';
import { listAgents, type AgentDto } from '../api/agents';
import { isSelfHosted, listEnvironments, type EnvironmentDto } from '../api/environments';
import { listVaults, type VaultDto } from '../api/vaults';
import { fileDownloadUrl, uploadFile, type FileDto } from '../api/files';

/** UI 消息模型（system 角色承载状态提示 / 错误提示等非对话事件）。 */
interface UiMessage {
  id: string;
  role: 'user' | 'assistant' | 'system';
  content: string;
  thinking: string;
  toolTraces: string[];
  /** 图片内容块渲染地址（base64 内联 / 外部 HTTPS / 文件内容直链） */
  images: string[];
}

/** 待确认工具现场（锚点 = 待确认 agent.tool_use 事件的公开 evt_ ID）。 */
interface PendingToolUse {
  toolUseId: string;
  name: string;
}

// —— 选择器数据 ——
const agents = ref<AgentDto[]>([]);
const environments = ref<EnvironmentDto[]>([]);
const vaults = ref<VaultDto[]>([]);
const vaultsLoaded = ref(false);
const selectedAgentId = ref('');
const selectedEnvironmentId = ref('');

// —— 会话与事件流 ——
const sessions = ref<SessionDto[]>([]);
const currentSessionId = ref('');
const sessionStatus = ref<string>('');
const threadStatus = ref<string>('');
const messages = ref<UiMessage[]>([]);
const seenEventIds = ref<Set<string>>(new Set());
/** 未应答的工具调用（键 = 运行时 tool_use_id，值含确认锚点 evt_ ID）：HITL 确认条的唯一驱动源。 */
const pendingToolUses = ref<Map<string, PendingToolUse>>(new Map());
let eventSource: EventStreamHandle | null = null;

// —— 输入与提示 ——
const draft = ref('');
const sending = ref(false);
const cancelling = ref(false);
const loadingHistory = ref(false);
const error = ref('');
const messageScroller = ref<HTMLElement | null>(null);

// —— 挂载资源（会话进行中追加）——
const resourceFileInput = ref<HTMLInputElement | null>(null);
const appendingResource = ref(false);
const resourceError = ref('');

// —— 新建会话对话框 ——
const createVisible = ref(false);
const creating = ref(false);
const createError = ref('');
const createForm = reactive({ title: '', environmentId: '', vaultIds: [] as string[] });
const dialogFileInput = ref<HTMLInputElement | null>(null);
const uploadingFiles = ref(false);
const stagedFiles = ref<FileDto[]>([]);
/** 暂存文件的自定义挂载路径（键 = 文件 id，值空串 = 提交时省略、走后端缺省 mounts/<file_id>）。 */
const mountPaths = reactive<Record<string, string>>({});

/**
 * mount_path 前端预校验（契约：mounts/ 前缀工作区相对路径）：
 * 空串放行（走缺省），非法返回可读原因、避免用户只能等后端 400。
 */
function validateMountPathInput(path: string | undefined): string | null {
  const value = (path ?? '').trim();
  if (!value) {
    return null;
  }
  if (value.startsWith('/')) {
    return '挂载路径必须为工作区相对路径（不得以 / 开头）';
  }
  if (value.includes('\\')) {
    return '挂载路径不得包含反斜杠';
  }
  if (!value.startsWith('mounts/')) {
    return '挂载路径必须位于 mounts/ 目录下';
  }
  if (value === 'mounts' || value.split('/').some((seg) => seg === '' || seg === '.' || seg === '..')) {
    return '挂载路径不得等于 mounts 本身、不得含空段或 . / .. 段';
  }
  return null;
}

/** 移除暂存文件并清理其挂载路径输入。 */
function removeStagedFile(index: number): void {
  const [removed] = stagedFiles.value.splice(index, 1);
  if (removed) {
    delete mountPaths[removed.id];
  }
}

const agentOptions = computed(() =>
  agents.value.map((agent) => ({ label: agent.name, value: agent.id })),
);

/** 环境下拉项：self_hosted 类型执行面未支持（Session 装配显式 400），置灰不可选。 */
const environmentOptions = computed(() =>
  environments.value.map((env) => ({
    label: isSelfHosted(env.config?.type)
      ? `${env.name}（暂不可执行）`
      : `${env.name} · ${env.config?.type ?? 'cloud'}`,
    value: env.id,
    disabled: isSelfHosted(env.config?.type),
  })),
);

const vaultOptions = computed(() =>
  vaults.value
    .filter((vault) => !vault.archived_at)
    .map((vault) => ({ label: vault.display_name || vault.id, value: vault.id })),
);

const currentSession = computed(
  () => sessions.value.find((item) => item.id === currentSessionId.value) ?? null,
);

const fileResources = computed(() =>
  (currentSession.value?.resources ?? []).filter((res) => res.type === 'file'),
);

/** 归档为 archived_at 正交维度（不是 status 取值）。 */
const isArchived = computed(() => Boolean(currentSession.value?.archived_at));
/** 终态（terminated）与归档：不可再投递事件、不可追加挂载。 */
const isTerminal = computed(() => sessionStatus.value === 'terminated');
/** 存在活跃 turn（含内部等待确认 / 取消相位，对外恒 running）：发送被 409 拒绝。 */
const isRunning = computed(() => sessionStatus.value === 'running');
const canAppendResources = computed(() => Boolean(currentSession.value) && !isTerminal.value && !isArchived.value);

/** HITL 确认现场：最早一条未应答的待确认工具调用（同批多调用按序逐个裁决）。 */
const pendingConfirm = computed<PendingToolUse | null>(() => {
  for (const item of pendingToolUses.value.values()) {
    return item;
  }
  return null;
});
const confirming = ref(false);

// ==================== 状态词汇（对外四态小写，与后端 AgentSessionStatus 对齐） ====================

const STATUS_LABELS: Record<string, string> = {
  idle: '空闲',
  running: '执行中',
  rescheduling: '重新调度中',
  terminated: '已终止',
};

function statusLabel(status: string): string {
  return STATUS_LABELS[status] || status;
}

function statusTheme(status: string): 'success' | 'primary' | 'warning' | 'danger' | 'default' {
  switch (status) {
    case 'idle':
      return 'success';
    case 'running':
      return 'primary';
    case 'rescheduling':
      return 'warning';
    case 'terminated':
      return 'danger';
    default:
      return 'default';
  }
}

/** 会话是否已关闭（终止或归档）：默认不自动激活。 */
function isSessionClosed(item: SessionDto): boolean {
  return item.status === 'terminated' || Boolean(item.archived_at);
}

// ==================== 基础工具 ====================

let messageCounter = 0;
function newId(): string {
  messageCounter += 1;
  return `${Date.now()}-${messageCounter}`;
}

function scrollToBottom(): void {
  requestAnimationFrame(() => {
    if (messageScroller.value) {
      messageScroller.value.scrollTop = messageScroller.value.scrollHeight;
    }
  });
}

function pushMessage(message: UiMessage): void {
  messages.value.push(message);
  scrollToBottom();
}

function pushSystem(text: string): void {
  pushMessage({ id: newId(), role: 'system', content: text, thinking: '', toolTraces: [], images: [] });
}

/** 复用尾部助手气泡，必要时新建（思考 / 正文 / 工具轨迹 / 交付产物聚合展示）。 */
function lastAssistant(): UiMessage {
  const last = messages.value[messages.value.length - 1];
  if (last && last.role === 'assistant') {
    return last;
  }
  const created: UiMessage = { id: newId(), role: 'assistant', content: '', thinking: '', toolTraces: [], images: [] };
  pushMessage(created);
  return created;
}

function truncate(text: string, max: number): string {
  return text.length <= max ? text : `${text.slice(0, max)}…`;
}

function formatTime(value: string): string {
  return value ? value.replace('T', ' ').slice(0, 19) : '';
}

/** 以字符串安全读取扁平事件顶层字段（类型自有键，未知类型收敛为空串）。 */
function attrString(env: EventEnvelope, key: string): string {
  const value = env[key];
  return typeof value === 'string' ? value : '';
}

/** 提取事件 content 文本块拼接纯文本（非文本块跳过）。 */
function contentText(env: EventEnvelope): string {
  const blocks = env.content;
  if (!Array.isArray(blocks)) {
    return '';
  }
  return (blocks as EventContentBlock[])
    .filter((block) => block.type === 'text' && typeof block.text === 'string')
    .map((block) => block.text as string)
    .join('');
}

/** 提取事件 content 图片块渲染地址（base64 内联 / 外部 HTTPS / 已就绪 file_id 内容直链）。 */
function contentImages(env: EventEnvelope): string[] {
  const blocks = env.content;
  if (!Array.isArray(blocks)) {
    return [];
  }
  const urls: string[] = [];
  for (const block of blocks as EventContentBlock[]) {
    if (block.type !== 'image' || !block.source) {
      continue;
    }
    const source = block.source;
    if (source.type === 'url' && source.url) {
      urls.push(source.url);
    } else if (source.type === 'base64' && source.data) {
      urls.push(`data:${source.media_type ?? 'image/png'};base64,${source.data}`);
    } else if (source.type === 'file' && source.file_id) {
      urls.push(fileDownloadUrl(source.file_id));
    }
  }
  return urls;
}

/** 读取对象形态 stop_reason（值域 end_turn / interrupted / error / max_iterations，容忍新增值）。 */
function readStopReason(env: EventEnvelope): StopReason | null {
  const value = env.stop_reason;
  if (!value || typeof value !== 'object') {
    return null;
  }
  const type = (value as Record<string, unknown>).type;
  return typeof type === 'string' ? { type } : null;
}

/** 读取 session.error 的类型自有字段（error.type / message / retry_status / error_code?）。 */
function readError(env: EventEnvelope): SessionErrorDetail | null {
  const value = env.error;
  if (!value || typeof value !== 'object') {
    return null;
  }
  const detail = value as Record<string, unknown>;
  const retryStatus = detail.retry_status as Record<string, unknown> | undefined;
  return {
    type: typeof detail.type === 'string' ? detail.type : 'unknown_error',
    message: typeof detail.message === 'string' ? detail.message : '',
    retry_status: { type: typeof retryStatus?.type === 'string' ? retryStatus.type : 'terminal' },
    error_code: typeof detail.error_code === 'string' ? detail.error_code : undefined,
  };
}

// ==================== 事件信封 → UI ====================

/**
 * 应用单条扁平事件（SSE 流与 POST /events JSON 回显共用同一入口）。
 * <p>按事件 id 幂等去重：入站事件先经回显渲染，流上重复投递直接跳过；
 * 未登记的 span.* / session.updated 等本期不渲染，由 default 分支静默收敛。</p>
 */
function applyEnvelope(env: EventEnvelope): void {
  if (!env.id || seenEventIds.value.has(env.id)) {
    return;
  }
  seenEventIds.value.add(env.id);

  const type = env.type;

  // 主线程状态镜像（不改变对外会话状态，仅驱动线程标签）
  if (type.startsWith(THREAD_STATUS_PREFIX)) {
    threadStatus.value = type.slice(THREAD_STATUS_PREFIX.length);
    return;
  }

  if (type.startsWith(SESSION_STATUS_PREFIX)) {
    const status = type.slice(SESSION_STATUS_PREFIX.length);
    sessionStatus.value = status;
    const item = sessions.value.find((session) => session.id === currentSessionId.value);
    if (item) {
      item.status = status as SessionStatus;
    }
    if (status !== 'running') {
      // 收轮：等待现场随本轮终止一并失效（interrupted / error / end_turn 均不再可确认）
      pendingToolUses.value = new Map();
      const stopReason = readStopReason(env);
      if (stopReason?.type === 'interrupted') {
        pushSystem('本轮执行已中断');
      } else if (stopReason?.type === 'max_iterations') {
        pushSystem('本轮执行已达迭代上限');
      }
    }
    scrollToBottom();
    return;
  }

  const toolUseId = attrString(env, 'tool_use_id');
  switch (type) {
    case CHAT_EVENT_TYPES.USER_MESSAGE:
      pushMessage({
        id: env.id,
        role: 'user',
        content: contentText(env),
        thinking: '',
        toolTraces: [],
        images: contentImages(env),
      });
      break;
    case CHAT_EVENT_TYPES.AGENT_MESSAGE:
      lastAssistant().content += contentText(env);
      scrollToBottom();
      break;
    case CHAT_EVENT_TYPES.AGENT_THINKING:
      // thinking 文本不进 content（非消息块），经顶层展开键 text 承载
      lastAssistant().thinking += attrString(env, 'text');
      scrollToBottom();
      break;
    case CHAT_EVENT_TYPES.AGENT_TOOL_USE:
    case CHAT_EVENT_TYPES.AGENT_MCP_TOOL_USE: {
      const name = attrString(env, 'name') || '工具';
      const input = env.input;
      let argsText = '';
      if (input && typeof input === 'object') {
        const json = JSON.stringify(input);
        if (json && json !== '{}') {
          argsText = ` ${truncate(json, 200)}`;
        }
      }
      lastAssistant().toolTraces.push(`调用「${name}」${argsText}`);
      // 未应答的工具调用即为 HITL 确认现场（锚点 = 本事件公开 evt_ ID）
      if (toolUseId) {
        const next = new Map(pendingToolUses.value);
        next.set(toolUseId, { toolUseId: env.id, name });
        pendingToolUses.value = next;
      }
      scrollToBottom();
      break;
    }
    case CHAT_EVENT_TYPES.AGENT_CUSTOM_TOOL_USE: {
      // 自定义工具等待 user.custom_tool_result（本期执行面未上线），不并入人工确认现场
      const name = attrString(env, 'name') || '自定义工具';
      lastAssistant().toolTraces.push(`调用「${name}」（等待客户端结果，本期未支持）`);
      scrollToBottom();
      break;
    }
    case CHAT_EVENT_TYPES.AGENT_TOOL_RESULT:
    case CHAT_EVENT_TYPES.AGENT_MCP_TOOL_RESULT: {
      const name = attrString(env, 'name') || '工具';
      const state = attrString(env, 'state') || 'success';
      const output = attrString(env, 'output').trim();
      const truncatedMark = env.truncated === true ? '…(已截断)' : '';
      lastAssistant().toolTraces.push(`「${name}」结果(${state}): ${truncate(output, 200)}${truncatedMark}`);
      if (toolUseId && pendingToolUses.value.has(toolUseId)) {
        const next = new Map(pendingToolUses.value);
        next.delete(toolUseId);
        pendingToolUses.value = next;
      }
      scrollToBottom();
      break;
    }
    case CHAT_EVENT_TYPES.AGENT_ARTIFACT_DELIVERED: {
      const filename = attrString(env, 'original_filename') || attrString(env, 'file_id');
      const size = typeof env.size === 'number' ? ` (${env.size} 字节)` : '';
      lastAssistant().toolTraces.push(`交付文件「${filename}」${size}`);
      scrollToBottom();
      break;
    }
    case CHAT_EVENT_TYPES.SESSION_ERROR: {
      const detail = readError(env);
      const code = detail?.error_code ? ` [${detail.error_code}]` : '';
      const retry = detail ? `（重试状态：${detail.retry_status.type}）` : '';
      pushSystem(`执行错误：${detail?.message || '未知错误'}${code}${retry}`);
      break;
    }
    default:
      // span.model_request_* / session.updated / session.deleted 等本期不渲染
      break;
  }
}

// ==================== 数据加载 ====================

/** 加载 Agent 列表并默认选中首个。 */
async function loadAgents(): Promise<void> {
  const page = await listAgents({ limit: 100 });
  agents.value = page.data;
  const first = agents.value[0];
  if (first) {
    selectedAgentId.value = first.id;
  } else {
    error.value = '暂无可用 Agent，请先在「Agent 管理」创建并发布版本';
  }
}

/** 加载环境列表并默认选中首个可执行环境。 */
async function loadEnvironments(): Promise<void> {
  const page = await listEnvironments({ limit: 100 });
  environments.value = page.data;
  const selectable = environments.value.find((env) => !isSelfHosted(env.config?.type));
  selectedEnvironmentId.value = selectable?.id ?? '';
}

/** 按当前 Agent 过滤刷新会话列表。 */
async function refreshSessions(): Promise<void> {
  if (!selectedAgentId.value) {
    sessions.value = [];
    return;
  }
  const result = await listSessions({ agentId: selectedAgentId.value, limit: 50 });
  sessions.value = result.data;
}

function upsertSession(detail: SessionDto): void {
  const index = sessions.value.findIndex((item) => item.id === detail.id);
  if (index >= 0) {
    sessions.value.splice(index, 1, detail);
  } else {
    sessions.value.unshift(detail);
  }
}

// ==================== 会话切换与事件流生命周期 ====================

function closeStream(): void {
  eventSource?.close();
  eventSource = null;
}

/** 激活会话：重置渲染态 → 拉详情 → 回放历史 → 打开实时事件流。 */
async function activateSession(sessionId: string): Promise<void> {
  closeStream();
  currentSessionId.value = sessionId;
  messages.value = [];
  seenEventIds.value = new Set();
  pendingToolUses.value = new Map();
  threadStatus.value = '';
  error.value = '';
  resourceError.value = '';
  try {
    const detail = await getSession(sessionId);
    upsertSession(detail);
    sessionStatus.value = detail.status;
    await loadHistory(sessionId);
  } catch (e) {
    error.value = `会话加载失败: ${(e as Error).message}`;
    return;
  }
  eventSource = openEventStream(
    sessionId,
    applyEnvelope,
    (message) => {
      error.value = message;
    },
    // 增量帧为连接级协商（值域仅 agent.message / agent.thinking）；本期渲染以 buffered 完整事件为准
    { eventDeltas: ['agent.message'] },
  );
}

async function selectSession(sessionId: string): Promise<void> {
  if (!sessionId || sessionId === currentSessionId.value) {
    return;
  }
  await activateSession(sessionId);
}

/** 事件历史回放（evt_ ID 游标翻页，上限 10 页防御超长会话）。 */
async function loadHistory(sessionId: string): Promise<void> {
  loadingHistory.value = true;
  try {
    let afterId: string | undefined;
    for (let page = 0; page < 10; page += 1) {
      const result = await listEvents(sessionId, { afterId, limit: 100 });
      for (const envelope of result.data) {
        applyEnvelope(envelope);
      }
      if (!result.has_more || !result.last_id) {
        break;
      }
      afterId = result.last_id;
    }
    scrollToBottom();
  } catch (e) {
    error.value = `历史加载失败: ${(e as Error).message}`;
  } finally {
    loadingHistory.value = false;
  }
}

/** Agent 切换：关闭流并重置会话上下文，重新加载会话列表。 */
function handleAgentChange(): void {
  closeStream();
  currentSessionId.value = '';
  sessionStatus.value = '';
  threadStatus.value = '';
  messages.value = [];
  seenEventIds.value = new Set();
  pendingToolUses.value = new Map();
  error.value = '';
  void refreshSessions().then(() => {
    const first = sessions.value.find((item) => !isSessionClosed(item));
    if (first) {
      void activateSession(first.id);
    }
  }).catch((e: unknown) => {
    error.value = `会话列表刷新失败: ${(e as Error).message}`;
  });
}

// ==================== 消息投递 ====================

async function handleSend(): Promise<void> {
  const content = draft.value.trim();
  if (!content || !currentSessionId.value || sending.value) {
    return;
  }
  draft.value = '';
  error.value = '';
  sending.value = true;
  try {
    // POST /events 返回已落库扁平事件回显，立即渲染；SSE 流重复投递按 id 去重
    const echo = await sendUserMessage(currentSessionId.value, content);
    for (const envelope of echo) {
      applyEnvelope(envelope);
    }
  } catch (e) {
    error.value = `发送失败: ${(e as Error).message}`;
  } finally {
    sending.value = false;
    scrollToBottom();
  }
}

/** 中断 / 取消当前执行：cancel 端点（活跃 turn 202、idle/terminated 幂等 200，响应体相同）。 */
async function handleInterrupt(): Promise<void> {
  if (!currentSessionId.value || cancelling.value) {
    return;
  }
  cancelling.value = true;
  error.value = '';
  try {
    await cancelSession(currentSessionId.value);
    // 取消过程零状态事件，本轮经收场事件收敛回 idle（stop_reason=interrupted），此处不改本地状态
    pushSystem('已请求取消本轮执行');
  } catch (e) {
    error.value = `中断失败: ${(e as Error).message}`;
  } finally {
    cancelling.value = false;
  }
}

/** HITL 确认 / 拒绝：经 /events 发 user.tool_confirmation（锚点 = 待确认工具事件公开 evt_ ID）。 */
async function handleConfirm(allowed: boolean): Promise<void> {
  const pending = pendingConfirm.value;
  if (!currentSessionId.value || !pending || confirming.value) {
    return;
  }
  confirming.value = true;
  error.value = '';
  try {
    const echo = await sendUserToolConfirmation(
      currentSessionId.value,
      pending.toolUseId,
      allowed ? 'allow' : 'deny',
    );
    for (const envelope of echo) {
      applyEnvelope(envelope);
    }
    // 续跑不产生状态迁移事件（对外恒 running），本地先行清理该确认现场
    const next = new Map(pendingToolUses.value);
    for (const [key, item] of next) {
      if (item.toolUseId === pending.toolUseId) {
        next.delete(key);
      }
    }
    pendingToolUses.value = next;
  } catch (e) {
    error.value = `${allowed ? '确认' : '拒绝'}失败: ${(e as Error).message}`;
  } finally {
    confirming.value = false;
  }
}

// ==================== 会话生命周期操作 ====================

async function handleArchive(): Promise<void> {
  if (!currentSessionId.value) {
    return;
  }
  try {
    const updated = await archiveSession(currentSessionId.value);
    upsertSession(updated);
    sessionStatus.value = updated.status;
  } catch (e) {
    error.value = `归档失败: ${(e as Error).message}`;
  }
}

async function handleDelete(): Promise<void> {
  if (!currentSessionId.value) {
    return;
  }
  try {
    await deleteSession(currentSessionId.value);
    // 删除即物理清理：关闭事件流、移出会话列表并清空当前会话上下文
    closeStream();
    sessions.value = sessions.value.filter((session) => session.id !== currentSessionId.value);
    currentSessionId.value = '';
    sessionStatus.value = '';
    threadStatus.value = '';
    messages.value = [];
    seenEventIds.value = new Set();
    pendingToolUses.value = new Map();
  } catch (e) {
    error.value = `删除失败: ${(e as Error).message}`;
  }
}

// ==================== 新建会话对话框 ====================

async function openCreateDialog(): Promise<void> {
  createError.value = '';
  createForm.title = '';
  createForm.vaultIds = [];
  stagedFiles.value = [];
  for (const key of Object.keys(mountPaths)) {
    delete mountPaths[key];
  }
  // 默认环境可执行则沿用，否则回退首个可执行环境
  const selectable = environments.value.filter((env) => !isSelfHosted(env.config?.type));
  createForm.environmentId =
    selectable.find((env) => env.id === selectedEnvironmentId.value)?.id ??
    selectable[0]?.id ??
    '';
  createVisible.value = true;
  if (!vaultsLoaded.value) {
    try {
      const page = await listVaults({ limit: 100 });
      vaults.value = page.data;
      vaultsLoaded.value = true;
    } catch {
      // Vault 列表加载失败不阻塞建会话（可不选凭证）
    }
  }
}

function pickDialogFiles(): void {
  dialogFileInput.value?.click();
}

/** 对话框内选择文件：逐个上传（purpose=session_resource）后暂存，创建时随 resources 提交。 */
async function handleDialogFilesPicked(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement;
  const files = Array.from(input.files ?? []);
  input.value = '';
  if (!files.length) {
    return;
  }
  uploadingFiles.value = true;
  createError.value = '';
  try {
    for (const file of files) {
      stagedFiles.value.push(await uploadFile(file, 'session_resource'));
    }
  } catch (e) {
    createError.value = `文件上传失败: ${(e as Error).message}`;
  } finally {
    uploadingFiles.value = false;
  }
}

async function handleCreateSession(): Promise<void> {
  if (!selectedAgentId.value) {
    createError.value = '请先选择 Agent';
    return;
  }
  if (!createForm.environmentId) {
    createError.value = '请选择运行环境';
    return;
  }
  // 自定义挂载路径前端预校验（先于请求，非法即给可读原因）
  const resources: SessionResourceInput[] = [];
  for (const file of stagedFiles.value) {
    const invalid = validateMountPathInput(mountPaths[file.id]);
    if (invalid) {
      createError.value = `「${file.filename}」的挂载路径非法: ${invalid}`;
      return;
    }
    const custom = (mountPaths[file.id] ?? '').trim();
    resources.push(custom ? { type: 'file', file_id: file.id, mount_path: custom }
      : { type: 'file', file_id: file.id });
  }
  creating.value = true;
  createError.value = '';
  try {
    const created = await createSession({
      agent: selectedAgentId.value,
      environment_id: createForm.environmentId,
      title: createForm.title.trim() || null,
      resources: resources.length ? resources : null,
      vault_ids: createForm.vaultIds.length ? createForm.vaultIds : null,
    });
    createVisible.value = false;
    selectedEnvironmentId.value = createForm.environmentId;
    upsertSession(created);
    await activateSession(created.id);
  } catch (e) {
    createError.value = `创建会话失败: ${(e as Error).message}`;
  } finally {
    creating.value = false;
  }
}

// ==================== 会话进行中追加挂载 ====================

function pickResourceFile(): void {
  resourceFileInput.value?.click();
}

async function handleResourceFilePicked(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0] ?? null;
  input.value = '';
  if (!file || !currentSessionId.value) {
    return;
  }
  appendingResource.value = true;
  resourceError.value = '';
  try {
    const uploaded = await uploadFile(file, 'session_resource');
    await appendSessionResources(currentSessionId.value, [{ type: 'file', file_id: uploaded.id }]);
    const detail = await getSession(currentSessionId.value);
    upsertSession(detail);
  } catch (e) {
    resourceError.value = `挂载失败: ${(e as Error).message}`;
  } finally {
    appendingResource.value = false;
  }
}

// ==================== 生命周期 ====================

onMounted(async () => {
  try {
    await Promise.all([loadAgents(), loadEnvironments()]);
    await refreshSessions();
    const first = sessions.value.find((item) => !isSessionClosed(item));
    if (first) {
      await activateSession(first.id);
    }
  } catch (e) {
    error.value = `初始化失败: ${(e as Error).message}`;
  }
});

onBeforeUnmount(() => {
  closeStream();
});
</script>

<style scoped>
.agent-chat {
  display: flex;
  gap: 16px;
  height: calc(100vh - 32px);
  padding: 16px;
  box-sizing: border-box;
}

.agent-chat__side {
  display: flex;
  flex-direction: column;
  gap: 16px;
  width: 300px;
  flex-shrink: 0;
  overflow-y: auto;
}

.side-card {
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
}

.agent-config {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.session-list {
  list-style: none;
  margin: 0;
  padding: 0;
}

.session-item {
  padding: 8px;
  border-radius: 8px;
  cursor: pointer;
  border: 1px solid transparent;
}

.session-item:hover {
  background: var(--td-bg-color-container-hover);
}

.session-item--active {
  background: var(--td-brand-color-light);
  border-color: var(--td-brand-color);
}

.session-item__head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
}

.session-item__title {
  font-size: 13px;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.session-item__meta {
  margin: 2px 0 0;
  font-size: 12px;
  color: var(--td-text-color-secondary);
  word-break: break-all;
}

.session-actions {
  display: flex;
  gap: 4px;
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px solid var(--td-component-stroke);
}

.resource-list {
  list-style: none;
  margin: 0 0 8px;
  padding: 0;
}

.resource-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 6px 0;
  border-bottom: 1px solid var(--td-component-stroke);
  font-size: 12px;
}

.resource-item__file {
  word-break: break-all;
}

.resource-item__path {
  color: var(--td-text-color-secondary);
}

.resource-append {
  margin-top: 4px;
}

.side-error {
  margin: 8px 0 0;
  color: var(--td-error-color);
  font-size: 12px;
}

.agent-chat__main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  border: 1px solid var(--td-component-stroke);
  border-radius: 12px;
  background: var(--td-bg-color-container);
  overflow: hidden;
}

.agent-chat__header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 16px;
  border-bottom: 1px solid var(--td-component-stroke);
}

.agent-chat__session-id {
  font-size: 12px;
  color: var(--td-text-color-secondary);
  word-break: break-all;
}

.agent-chat__messages {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
  box-sizing: border-box;
}

.agent-chat__loading {
  display: flex;
  justify-content: center;
  padding: 24px;
}

.message-row {
  display: flex;
  margin-bottom: 16px;
}

.message-row--user {
  justify-content: flex-end;
}

.message-row--assistant {
  justify-content: flex-start;
}

.message-row--system {
  justify-content: center;
}

.message-system {
  font-size: 12px;
  color: var(--td-text-color-secondary);
  background: var(--td-bg-color-component);
  border-radius: 8px;
  padding: 4px 12px;
}

.message-bubble {
  max-width: 70%;
  padding: 10px 14px;
  border-radius: 12px;
  background: var(--td-bg-color-component);
  font-size: 14px;
  line-height: 1.6;
}

.message-row--user .message-bubble {
  background: var(--td-brand-color-light);
}

.message-content {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
}

.message-images {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 8px;
}

.message-image {
  max-width: 240px;
  max-height: 240px;
  border-radius: 8px;
  object-fit: contain;
}

.message-thinking,
.message-tools {
  margin-bottom: 8px;
}

.message-thinking p {
  margin: 6px 0 0;
  font-size: 13px;
  color: var(--td-text-color-secondary);
  white-space: pre-wrap;
}

.message-tools__item {
  display: flex;
  align-items: baseline;
  gap: 6px;
  font-size: 13px;
  color: var(--td-text-color-secondary);
  margin-top: 4px;
}

.agent-chat__confirm {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 16px;
  border-top: 1px solid var(--td-component-stroke);
  background: var(--td-warning-color-1);
}

.agent-chat__confirm-text {
  flex: 1;
  font-size: 13px;
  color: var(--td-text-color-primary);
}

.agent-chat__input {
  display: flex;
  gap: 8px;
  align-items: flex-end;
  padding: 12px 16px;
  border-top: 1px solid var(--td-component-stroke);
}

.agent-chat__input :deep(.t-textarea__inner) {
  resize: none;
}

.agent-chat__footer-error {
  margin: 0;
  padding: 6px 16px 10px;
  color: var(--td-error-color);
  font-size: 13px;
}

.dialog-files {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 6px;
  align-items: flex-start;
}

.staged-list {
  list-style: none;
  margin: 0;
  padding: 0;
  width: 100%;
}

.staged-item {
  display: flex;
  font-size: 13px;
  padding: 2px 0;
}

.staged-item__body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.staged-item__head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.staged-item__path {
  width: 100%;
}

.resource-hint {
  margin: 6px 0 0;
  font-size: 12px;
}

.dialog-error {
  margin: 8px 0 0;
  color: var(--td-error-color);
  font-size: 13px;
}

.muted {
  color: var(--td-text-color-placeholder);
  font-size: 13px;
}
</style>