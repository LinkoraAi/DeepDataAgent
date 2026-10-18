<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { MessagePlugin } from 'tdesign-vue-next';
import StatusTag from '@/shared/components/StatusTag.vue';
import { confirmDangerous } from '@/shared/composables/useConfirm';
import { LAYER } from '@/shared/constants/layers';
import { SESSION_STATUS } from '@/shared/enums/dict';
import { absoluteTime, compactCount } from '@/shared/utils/time';
import { useWorkspaceStore } from '../stores/workspace';
import ChatPane from '../components/ChatPane.vue';
import ChatComposer from '../components/ChatComposer.vue';
import DebugPane from '../components/DebugPane.vue';
import AgentSnapshotTab from '../components/AgentSnapshotTab.vue';
import EnvironmentTab from '../components/EnvironmentTab.vue';
import SessionFilesTab from '../components/SessionFilesTab.vue';

/**
 * 会话工作区页（全项目最复杂单页，对应任务组 9）。
 * <p>顶部元信息条（返回列表 / 可编辑标题 / 状态标签 / In·Out token 聚合 / 会话 ID 截断+复制 /
 * 智能体名 / 创建时间 / 中断·取消·归档·删除操作）+ Tab（事件[对话/调试切换，默认]、智能体、环境、文件）。
 * 进入即 store.load（会话详情 → 历史回放 → SSE 订阅），卸载 onBeforeUnmount teardown（关闭流、不取消任务）。
 * SSE 连接异常时顶部黄条提示"连接中断，重连中"。</p>
 */

const props = defineProps<{ sessionId: string }>();

const router = useRouter();
const store = useWorkspaceStore();

/** 事件 Tab 内的子视图（chat=对话 / debug=调试）。 */
const eventView = ref<'chat' | 'debug'>('chat');

/** 当前激活主 Tab。 */
const activeTab = ref('events');

/** 标题编辑态显隐。 */
const titleEditVisible = ref(false);
/** 标题编辑草稿。 */
const titleDraft = ref('');

/** 会话展示标题。 */
const displayTitle = computed(() => store.session?.title || '未命名会话');

/** 智能体名（快照缺名回落 ID）。 */
const agentName = computed(() => {
  const agent = store.session?.agent;
  if (!agent) {
    return '-';
  }
  return agent.name || agent.id || '-';
});

/** 是否已归档（顶部标签额外提示）。 */
const archived = computed(() => Boolean(store.session?.archived_at));

/** 输入区是否禁用（运行中或只读）。 */
const composerDisabled = computed(() => store.running || store.readOnly);

/** 复制会话 ID。 */
async function copySessionId(): Promise<void> {
  const id = store.sessionId;
  if (!id) {
    return;
  }
  try {
    await navigator.clipboard.writeText(id);
    MessagePlugin.success('会话 ID 已复制');
  } catch {
    MessagePlugin.warning('复制失败，请手动选择 ID');
  }
}

/** 打开标题编辑。 */
function openTitleEdit(): void {
  titleDraft.value = store.session?.title ?? '';
  titleEditVisible.value = true;
}

/** 提交标题修改。 */
async function submitTitle(): Promise<void> {
  const ok = await store.rename(titleDraft.value.trim());
  if (ok) {
    MessagePlugin.success('标题已更新');
    titleEditVisible.value = false;
  }
}

/** 中断当前轮次。 */
async function onInterrupt(): Promise<void> {
  const ok = await store.interrupt();
  if (ok) {
    MessagePlugin.info('中断请求已发送');
  }
}

/** 取消当前执行（200/202 文案由 store 处理）。 */
function onCancel(): void {
  void store.cancel();
}

/** 归档会话（二次确认；成功后同步只读态）。 */
async function onArchive(): Promise<void> {
  const confirmed = await confirmDangerous({
    header: '归档会话',
    body: '归档后该会话不可再发送消息，运行中的执行会被中断，且归档不可逆。',
    theme: 'warning',
    confirmText: '归档',
  });
  if (!confirmed) {
    return;
  }
  const archivedSession = await store.archive();
  if (archivedSession) {
    MessagePlugin.success('会话已归档');
  }
}

/** 删除会话（危险二次确认；成功跳列表）。 */
async function onDelete(): Promise<void> {
  const confirmed = await confirmDangerous({
    header: '删除会话',
    body: '即将删除该会话及其全部历史事件，删除后不可恢复；仍活跃的执行会先被取消。',
  });
  if (!confirmed) {
    return;
  }
  const ok = await store.remove();
  if (ok) {
    MessagePlugin.success('会话已删除');
    await router.push('/sessions');
  }
}

/** 返回会话列表。 */
function backToList(): void {
  void router.push('/sessions');
}

/** 发送消息（对话输入区）。 */
async function onSend(text: string, images: Parameters<typeof store.sendText>[1]): Promise<void> {
  await store.sendText(text, images);
}

onMounted(() => {
  void store.load(props.sessionId);
});

onBeforeUnmount(() => {
  store.teardown();
});
</script>

<template>
  <div class="workspace">
    <!-- SSE 连接异常顶部黄条（连接恢复后自动消失） -->
    <t-alert
      v-if="store.streamError"
      theme="warning"
      :message="`连接中断，正在重连：${store.streamError}`"
      class="workspace__banner"
    />

    <!-- 工作区顶部：返回 + 会话 ID 主标题 + 操作组 -->
    <header class="workspace__head dda-section">
      <div class="workspace__head-main">
        <div class="workspace__id-row">
          <t-button
            variant="text"
            shape="square"
            class="workspace__back"
            aria-label="返回"
            title="返回会话列表"
            @click="backToList"
          >
            <t-icon name="arrow-left" />
          </t-button>
          <h1 class="workspace__id dda-id" :title="store.sessionId">{{ store.sessionId || '—' }}</h1>
        </div>

        <div class="workspace__title-row">
          <span class="workspace__name dda-subtitle">{{ displayTitle }}</span>
          <t-button variant="text" theme="primary" size="small" :disabled="store.readOnly" @click="openTitleEdit">
            <t-icon name="edit-1" />
            编辑标题
          </t-button>
          <t-tag v-if="archived" theme="default" variant="light" size="small">已归档</t-tag>
        </div>

        <!-- 元信息条 -->
        <div class="dda-metabar workspace__metabar">
          <span class="dda-metabar__item">
            <StatusTag :code="store.status" :dict="SESSION_STATUS" />
          </span>
          <span class="dda-metabar__item">
            <span class="workspace__meta-key">↓ In</span>
            <span class="workspace__meta-val">{{ compactCount(store.tokenUsage.in) }}</span>
          </span>
          <span class="dda-metabar__item">
            <span class="workspace__meta-key">↑ Out</span>
            <span class="workspace__meta-val">{{ compactCount(store.tokenUsage.out) }}</span>
          </span>
          <span class="dda-metabar__item">
            <span class="workspace__meta-key">ID:</span>
            <t-link theme="default" hover="color" :title="store.sessionId" @click="copySessionId">
              <span class="workspace__meta-id dda-id">{{ store.sessionId }}</span>
            </t-link>
          </span>
          <span class="dda-metabar__item">
            <span class="workspace__meta-key">智能体:</span>
            <span>{{ agentName }}</span>
          </span>
          <span class="dda-metabar__item">
            <span class="workspace__meta-key">创建时间:</span>
            <span>{{ absoluteTime(store.session?.created_at) }}</span>
          </span>
        </div>
      </div>

      <div class="workspace__actions">
        <t-button v-if="store.running" theme="warning" variant="outline" size="small" @click="onInterrupt">中断</t-button>
        <t-button v-if="store.running" theme="danger" variant="outline" size="small" @click="onCancel">取消</t-button>
        <t-button variant="outline" size="small" :disabled="archived || store.readOnly" @click="onArchive">
          <t-icon name="layers" />
          归档
        </t-button>
        <t-button theme="danger" variant="outline" size="small" @click="onDelete">
          <t-icon name="delete" />
          删除
        </t-button>
      </div>
    </header>

    <!-- 胶囊页签（沿用 activeTab 语义） -->
    <div class="dda-pill-tabs workspace__tabs">
      <button
        type="button"
        class="dda-pill-tab"
        :class="{ 'is-active': activeTab === 'events' }"
        @click="activeTab = 'events'"
      >
        事件
      </button>
      <button
        type="button"
        class="dda-pill-tab"
        :class="{ 'is-active': activeTab === 'agent' }"
        @click="activeTab = 'agent'"
      >
        智能体
      </button>
      <button
        type="button"
        class="dda-pill-tab"
        :class="{ 'is-active': activeTab === 'environment' }"
        @click="activeTab = 'environment'"
      >
        环境
      </button>
      <button
        type="button"
        class="dda-pill-tab"
        :class="{ 'is-active': activeTab === 'files' }"
        @click="activeTab = 'files'"
      >
        文件
      </button>
    </div>

    <!-- 面板区：v-show 保持四面板常驻挂载，等价原 t-tab-panel 默认（非懒加载）语义 -->
    <div class="workspace__body">
      <div v-show="activeTab === 'events'" class="workspace__panel">
        <div class="workspace__events">
          <div class="workspace__event-toolbar">
            <div class="workspace__view-switch">
              <button
                type="button"
                class="workspace__view-btn"
                :class="{ 'is-active': eventView === 'chat' }"
                @click="eventView = 'chat'"
              >
                <t-icon name="chat-bubble" />
                对话视图
              </button>
              <button
                type="button"
                class="workspace__view-btn"
                :class="{ 'is-active': eventView === 'debug' }"
                @click="eventView = 'debug'"
              >
                <t-icon name="code" />
                调试视图
              </button>
            </div>
            <div class="workspace__event-tools">
              <t-tag :theme="store.connected ? 'success' : 'default'" variant="light" size="small">
                {{ store.connected ? '实时连接' : '未连接' }}
              </t-tag>
            </div>
          </div>

          <div class="dda-panel workspace__event-body">
            <ChatPane v-if="eventView === 'chat'" />
            <DebugPane v-else />
          </div>

          <ChatComposer :disabled="composerDisabled" :sending="store.sending" @send="onSend" />
        </div>
      </div>

      <div v-show="activeTab === 'agent'" class="workspace__panel">
        <AgentSnapshotTab :session="store.session" />
      </div>

      <div v-show="activeTab === 'environment'" class="workspace__panel">
        <EnvironmentTab :environment-id="store.session?.environment_id ?? ''" />
      </div>

      <div v-show="activeTab === 'files'" class="workspace__panel">
        <!-- MUST 传路由 prop 而非 store.sessionId：v-show 下本 Tab 随页面立即挂载，
             其 onMounted 早于父 onMounted 的 store.load 填值，取 store 副本会发
             /sessions//resources 畸形请求（404）；路由参数在 setup 期同步可用 -->
        <SessionFilesTab :session-id="props.sessionId" :read-only="store.readOnly" />
      </div>
    </div>

    <!-- 标题编辑对话框（保留原有文案与行为） -->
    <t-dialog
      v-model:visible="titleEditVisible"
      header="编辑会话标题"
      :width="420"
      placement="center"
      attach="body"
      :z-index="LAYER.dialog"
      @confirm="submitTitle"
    >
      <t-input v-model="titleDraft" placeholder="会话标题（留空表示未命名）" maxlength="120" clearable />
    </t-dialog>
  </div>
</template>

<style scoped>
.workspace {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  padding: 12px 16px;
  gap: 10px;
}

.workspace__banner {
  flex: none;
}

/* ---------- 顶部三段：ID 主标题 / 元信息 / 操作组 ---------- */
.workspace__head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  flex: none;
  flex-wrap: wrap;
}

.workspace__head-main {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
}

.workspace__id-row {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.workspace__back {
  flex: none;
}

.workspace__id {
  margin: 0;
  overflow: hidden;
  font-size: 22px;
  font-weight: 600;
  line-height: 1.3;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--td-text-color-primary);
}

.workspace__title-row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.workspace__name {
  margin: 0;
}

.workspace__metabar {
  gap: 16px;
}

.workspace__meta-key {
  color: var(--td-text-color-secondary);
}

.workspace__meta-val {
  font-variant-numeric: tabular-nums;
  color: var(--td-text-color-primary);
}

.workspace__meta-id {
  display: inline-block;
  max-width: 320px;
  overflow: hidden;
  text-overflow: ellipsis;
  vertical-align: bottom;
  white-space: nowrap;
}

.workspace__actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

/* ---------- 面板区 ---------- */
.workspace__tabs {
  flex: none;
}

.workspace__body {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
}

.workspace__panel {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
}

/* ---------- 事件页 ---------- */
.workspace__events {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 460px;
}

.workspace__event-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex: none;
  padding: 8px 0;
}

.workspace__view-switch {
  display: inline-flex;
  gap: 8px;
}

.workspace__view-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 5px 12px;
  font-size: 13px;
  color: var(--td-text-color-secondary);
  cursor: pointer;
  background: var(--td-bg-color-container);
  border: 1px solid var(--td-component-border);
  border-radius: var(--td-radius-default);
  transition: all 0.15s ease;
}

.workspace__view-btn:hover {
  color: var(--td-text-color-primary);
  border-color: var(--td-brand-color-3);
}

.workspace__view-btn.is-active {
  font-weight: 500;
  color: var(--td-brand-color);
  background: var(--td-brand-color-light);
  border-color: var(--td-brand-color-3);
}

.workspace__event-tools {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.workspace__event-body {
  flex: 1;
  min-height: 0;
}

/* 对话 / 调试面板填满事件区并各自滚动（避免容器级二次滚动） */
.workspace__event-body :deep(.chat-pane),
.workspace__event-body :deep(.debug-pane) {
  flex: 1;
  min-height: 0;
}
</style>
