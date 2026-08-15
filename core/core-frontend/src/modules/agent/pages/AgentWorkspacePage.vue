<template>
  <div class="agent-chat">
    <aside class="agent-chat__side">
      <t-card title="会话" :bordered="true" class="side-card">
        <template #actions>
          <t-button size="small" theme="primary" variant="text" @click="ensureSession">新建</t-button>
        </template>
        <div v-if="session" class="session-info">
          <p class="session-info__id">{{ session.sessionId }}</p>
          <t-tag :theme="sessionTagTheme" variant="light">{{ session.status }}</t-tag>
        </div>
        <p v-else class="muted">加载中…</p>
      </t-card>
      <t-card title="历史轮次" :bordered="true" class="side-card">
        <ul v-if="rounds.length" class="round-list">
          <li v-for="round in rounds" :key="round.roundId" class="round-item">
            <span class="round-item__num">#{{ round.roundNumber }}</span>
            <span class="round-item__status">{{ round.status }}</span>
          </li>
        </ul>
        <p v-else class="muted">暂无轮次</p>
      </t-card>
    </aside>

    <section class="agent-chat__main">
      <div class="agent-chat__messages" ref="messageScroller">
        <div v-if="loadingHistory" class="agent-chat__loading">
          <t-loading size="small" />
        </div>
        <div
          v-for="message in messages"
          :key="message.id"
          class="message-row"
          :class="message.role === 'user' ? 'message-row--user' : 'message-row--assistant'"
        >
          <div class="message-bubble">
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
            <p class="message-content">{{ message.content }}</p>
            <p v-if="message.error" class="message-error">{{ message.error }}</p>
          </div>
        </div>
      </div>

      <div class="agent-chat__input">
        <t-textarea
          v-model="draft"
          :autosize="{ minRows: 1, maxRows: 4 }"
          placeholder="输入消息，Enter 发送 / Shift+Enter 换行"
          :disabled="sending"
          @keydown.enter.exact.prevent="handleSend"
        />
        <t-button theme="primary" :loading="sending" :disabled="!draft.trim()" @click="handleSend">
          发送
        </t-button>
      </div>
      <p v-if="error" class="agent-chat__footer-error">{{ error }}</p>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import {
  createSession,
  listRounds,
  listSessions,
  parseChatEvent,
  parseContentBlocks,
  roundEvents,
  sendMessageStream,
  type ChatEventDto,
} from '../api/agent';

/** UI 消息模型。 */
interface UiMessage {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  thinking: string;
  toolTraces: string[];
  error?: string;
}

const sessionId = ref('');
const sessionStatus = ref('');
const rounds = ref<{ roundId: string; roundNumber: number; status: string }[]>([]);
const messages = ref<UiMessage[]>([]);
const draft = ref('');
const sending = ref(false);
const loadingHistory = ref(false);
const error = ref('');
const messageScroller = ref<HTMLElement | null>(null);

const session = computed(() =>
  sessionId.value ? { sessionId: sessionId.value, status: sessionStatus.value } : null,
);
const sessionTagTheme = computed<'success' | 'warning' | 'danger'>(() =>
  sessionStatus.value === 'IDLE' ? 'success' : sessionStatus.value === 'RUNNING' ? 'warning' : 'danger',
);

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

function lastAssistant(): UiMessage {
  const last = messages.value[messages.value.length - 1];
  if (last && last.role === 'assistant' && last.error === undefined) {
    return last;
  }
  const created = { id: newId(), role: 'assistant' as const, content: '', thinking: '', toolTraces: [] };
  pushMessage(created);
  return created;
}

/** 确保存在可用会话（demo 身份自动创建）。 */
async function ensureSession(): Promise<void> {
  const page = await listSessions();
  const existing = page.list.find((item) => item.status !== 'TERMINATED');
  if (existing) {
    sessionId.value = existing.sessionId;
    sessionStatus.value = existing.status;
  } else {
    const created = await createSession();
    sessionId.value = created.sessionId;
    sessionStatus.value = created.status;
  }
}

/** 回放最近一轮历史事件，重建最后一条助手消息。 */
async function loadHistory(): Promise<void> {
  if (!sessionId.value) {
    return;
  }
  loadingHistory.value = true;
  try {
    const history = await listRounds(sessionId.value);
    rounds.value = history.map((round) => ({
      roundId: round.roundId,
      roundNumber: round.roundNumber,
      status: round.status,
    }));
    const latest = history[history.length - 1];
    if (!latest) {
      return;
    }
    const events = await roundEvents(sessionId.value, latest.roundId);
    for (const dto of events) {
      await applyEvent(dto);
    }
    // 历史回放补充用户输入消息（仅首条，避免渲染重复）
    await replayUserMessages(events, latest.input);
    loadingHistory.value = false;
    scrollToBottom();
  } catch (e) {
    error.value = `历史加载失败: ${(e as Error).message}`;
    loadingHistory.value = false;
  }
}

/** 按用户输入在前端重建 user 气泡（后端 chat_event 首版不落用户消息）。 */
async function replayUserMessages(events: ChatEventDto[], fallbackInput: string): Promise<void> {
  const hasUserBubble = messages.value.some((m) => m.role === 'user');
  if (hasUserBubble || !fallbackInput) {
    return;
  }
  const runStart = events.find((dto) => dto.eventType === 'run_start');
  if (runStart) {
    pushMessage({ id: newId(), role: 'user', content: fallbackInput, thinking: '', toolTraces: [] });
  }
}

/** 应用单条聊天事件到 UI（new 事件模型：payload 为 content-blocks 结构，事件类型对齐后端 ChatEventType）。 */
async function applyEvent(dto: ChatEventDto): Promise<void> {
  const payload = parsePayload(dto.payload);
  switch (dto.eventType) {
    case 'run_start':
      // 应用层合成：含 round_id / run_id；会话置 RUNNING
      sessionStatus.value = 'RUNNING';
      scrollToBottom();
      break;
    case 'thinking':
      appendBlockTexts(payload, 'thinking', (text) => {
        lastAssistant().thinking += text;
      });
      scrollToBottom();
      break;
    case 'message':
      appendBlockTexts(payload, 'text', (text) => {
        lastAssistant().content += text;
      });
      scrollToBottom();
      break;
    case 'tool_call': {
      // tool_call 块：name + input 入参（SDK TOOL_CALL_* 聚合后一次性发出）
      const block = firstBlock(payload, 'tool_call');
      if (block?.name) {
        const args = block.input && Object.keys(block.input).length ? ` ${JSON.stringify(block.input)}` : '';
        lastAssistant().toolTraces.push(`调用「${block.name}」${args}`);
      }
      scrollToBottom();
      break;
    }
    case 'tool_call_output': {
      // tool_result 块：output（head+tail 截断）+ truncated 标记
      const block = firstBlock(payload, 'tool_result');
      const output = block?.output?.trim();
      if (output) {
        lastAssistant().toolTraces.push(`结果: ${truncate(output, 200)}${block?.truncated ? '…(已截断)' : ''}`);
      }
      scrollToBottom();
      break;
    }
    case 'run_error':
    case 'error':
      lastAssistant().error = strField(payload, 'message') || 'Agent 执行失败';
      scrollToBottom();
      break;
    case 'run_end':
      // SDK 终态（stop_reason）：会话状态由随后的 session_status 统一更新
      scrollToBottom();
      break;
    case 'session_status':
      sessionStatus.value = strField(payload, 'status') || sessionStatus.value;
      scrollToBottom();
      break;
    case 'agent_progress':
    case 'exceed_max_iters':
      // 预留：进度占位 / 迭代上限，首版不渲染进度 UI
      scrollToBottom();
      break;
    default:
      break;
  }
}

/** 取 payload 中首个指定类型的 content-block。 */
function firstBlock(
  payload: Record<string, unknown>,
  type: string,
): ReturnType<typeof parseContentBlocks>[number] | undefined {
  return parseContentBlocks(payload).find((block) => block.type === type);
}

/** 将 payload 中指定类型的文本块增量追加到目标。 */
function appendBlockTexts(
  payload: Record<string, unknown>,
  type: string,
  append: (text: string) => void,
): void {
  for (const block of parseContentBlocks(payload)) {
    if (block.type === type && block.text) {
      append(block.text);
    }
  }
}

function parsePayload(raw: string): Record<string, unknown> {
  try {
    const parsed = JSON.parse(raw);
    return typeof parsed === 'object' && parsed !== null ? (parsed as Record<string, unknown>) : {};
  } catch {
    return {};
  }
}

/** 以字符串安全读取 payload 字段（未知类型收敛）。 */
function strField(payload: Record<string, unknown>, key: string): string {
  const value = payload[key];
  return typeof value === 'string' ? value : '';
}

function truncate(text: string, max: number): string {
  return text.length <= max ? text : `${text.slice(0, max)}…`;
}

async function handleSend(): Promise<void> {
  const content = draft.value.trim();
  if (!content || !sessionId.value || sending.value) {
    return;
  }
  draft.value = '';
  error.value = '';
  pushMessage({ id: newId(), role: 'user', content, thinking: '', toolTraces: [] });
  // 预置助手消息容器，等待首条事件
  lastAssistant();

  sending.value = true;
  sessionStatus.value = 'RUNNING';
  try {
    await sendMessageStream(sessionId.value, content, (line) => {
      // 统一事件入口：run_start / session_status等由 applyEvent 按事件类型处理
      const dto = parseChatEvent(line.data);
      void applyEvent(dto);
    });
    if (sessionStatus.value === 'RUNNING') {
      sessionStatus.value = 'IDLE';
    }
    await refreshRounds();
  } catch (e) {
    const message = (e as Error).message;
    error.value = `发送失败: ${message}`;
    lastAssistant().error = message;
    sessionStatus.value = 'IDLE';
  } finally {
    sending.value = false;
    scrollToBottom();
  }
}

async function refreshRounds(): Promise<void> {
  if (!sessionId.value) {
    return;
  }
  const history = await listRounds(sessionId.value);
  rounds.value = history.map((round) => ({
    roundId: round.roundId,
    roundNumber: round.roundNumber,
    status: round.status,
  }));
}

onMounted(async () => {
  try {
    await ensureSession();
    await loadHistory();
  } catch (e) {
    error.value = `初始化失败: ${(e as Error).message}`;
  }
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
  width: 280px;
  flex-shrink: 0;
}

.side-card {
  display: flex;
  flex-direction: column;
}

.session-info__id {
  word-break: break-all;
  font-size: 12px;
  color: var(--td-text-color-secondary);
  margin-bottom: 8px;
}

.round-list {
  list-style: none;
  margin: 0;
  padding: 0;
}

.round-item {
  display: flex;
  justify-content: space-between;
  padding: 6px 0;
  border-bottom: 1px solid var(--td-component-stroke);
  font-size: 13px;
}

.round-item__num {
  font-weight: 500;
}

.round-item__status {
  color: var(--td-text-color-secondary);
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

.message-error {
  margin: 8px 0 0;
  color: var(--td-error-color);
  font-size: 13px;
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

.muted {
  color: var(--td-text-color-placeholder);
  font-size: 13px;
}
</style>