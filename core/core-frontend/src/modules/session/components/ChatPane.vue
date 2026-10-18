<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue';
import EmptyGuide from '@/shared/components/EmptyGuide.vue';
import { clockTime } from '@/shared/utils/time';
import ToolApprovalCard from './ToolApprovalCard.vue';
import { useWorkspaceStore } from '../stores/workspace';
import type { TimelineItem } from '../chat/streamingReducer';
import type { ContentBlock } from '../types';
import {
  aggregateContentText,
  AGENT_MESSAGE_TYPE,
  AGENT_THINKING_TYPE,
  ARTIFACT_DELIVERED_TYPE,
  errorTextOf,
  indexToolConfirmations,
  indexToolResults,
  isAwaitingApproval,
  messageTextOf,
  resolveToolPolicies,
  SESSION_ERROR_TYPE,
  TOOL_USE_TYPES,
  USER_MESSAGE_TYPE,
} from '../chat/workspaceLogic';

/**
 * 对话视图：消费 store 时间线按 design 决策 6 的映射渲染。
 * <p>user.message→右气泡（text + image 缩略）；agent.message→左气泡（streaming 逐字 + 光标，
 * buffered 渲染 content[].text 全文）；agent.thinking→折叠"思考过程"；工具调用→工具卡片
 * （结果按 tool_use_id 回填、is_error 标红、HITL 待审批展示允许/拒绝）；session.error→红条。
 * 状态变化由顶部条驱动（本视图不重复渲染状态事件）。新帧且用户位于底部时自动滚底。</p>
 */

const store = useWorkspaceStore();

/** 滚动容器引用。 */
const scroller = ref<HTMLElement | null>(null);

/** 工具名 → 权限策略（会话冻结快照解析）。 */
const policies = computed(() => resolveToolPolicies(store.session?.agent));

/** tool_use_id → 结果条目索引。 */
const resultIndex = computed(() => indexToolResults(store.timeline));

/** tool_use_id → 确认回显条目索引。 */
const confirmIndex = computed(() => indexToolConfirmations(store.timeline));

/** 需要气泡/卡片渲染的条目（过滤掉纯状态与计量事件——由顶部条与调试视图承载）。 */
const renderable = computed<TimelineItem[]>(() =>
  store.timeline.filter((item) => !isNoiseType(item.type)),
);

/** 是否为对话视图不渲染的类型（状态事件、计量 span）。 */
function isNoiseType(type: string): boolean {
  return (
    type.startsWith('session.status_') ||
    type.startsWith('session.thread_') ||
    type.startsWith('span.') ||
    type === 'session.updated' ||
    type === 'session.deleted' ||
    type === ''
  );
}

/** 是否贴近底部（决定自动滚底）。 */
function isNearBottom(): boolean {
  const el = scroller.value;
  if (!el) {
    return true;
  }
  return el.scrollHeight - el.scrollTop - el.clientHeight < 120;
}

/** 滚动到底部。 */
async function scrollToBottom(force = false): Promise<void> {
  await nextTick();
  const el = scroller.value;
  if (el && (force || isNearBottom())) {
    el.scrollTop = el.scrollHeight;
  }
}

/** 末条流式文本长度（逐字输出时随帧增长，驱动平滑滚底）。 */
const lastStreamingLength = computed(() => {
  const items = store.timeline;
  const last = items[items.length - 1];
  return last && last.streaming && typeof last.data.text === 'string' ? last.data.text.length : 0;
});

/** 时间线增长或流式文本增量时自动滚底（仅当用户已在底部）。 */
watch([() => store.timeline.length, lastStreamingLength], () => {
  void scrollToBottom();
});

onMounted(() => {
  void scrollToBottom(true);
});

/** 提交审批（允许）。 */
function onApprove(toolUseId: string): void {
  void store.confirmTool(toolUseId, true);
}

/** 提交审批（拒绝 + 原因）。 */
function onDeny(toolUseId: string, reason: string): void {
  void store.confirmTool(toolUseId, false, reason);
}

/** 取条目的工具名（供卡片 key 与展示）。 */
function cardKey(item: TimelineItem): string {
  return item.id;
}

/** content 块数组（user/agent 消息可能带图片块）。 */
function contentBlocks(item: TimelineItem): ContentBlock[] {
  return Array.isArray(item.data.content) ? (item.data.content as ContentBlock[]) : [];
}

/** 是否文本块。 */
function isTextBlock(block: ContentBlock): boolean {
  return block.type === 'text';
}

/** 取文本块正文（非文本块空串）。 */
function blockText(block: ContentBlock): string {
  return block.type === 'text' ? block.text : '';
}

/** 把 image 源解析为可展示的 data URL（base64→data URL；url→原址；file→空）。 */
function imageSrcOf(block: ContentBlock): string {
  if (block.type !== 'image') {
    return '';
  }
  const source = block.source;
  if (source.type === 'base64') {
    return `data:${source.media_type};base64,${source.data}`;
  }
  if (source.type === 'url') {
    return source.url;
  }
  return '';
}

/** 文本消息（streaming/buffered 兼容）。 */
function textOf(item: TimelineItem): string {
  return messageTextOf(item);
}

/** 聚合正文（buffered content[].text）。 */
function fullTextOf(item: TimelineItem): string {
  return aggregateContentText(item.data.content);
}

/** 错误条文案。 */
function errorOf(item: TimelineItem): string {
  return errorTextOf(item);
}

/** 是否工具调用类型。 */
function isToolUse(item: TimelineItem): boolean {
  return TOOL_USE_TYPES.includes(item.type);
}

/** 工具调用是否待审批。 */
function toolAwaiting(item: TimelineItem): boolean {
  const toolUseId = typeof item.data.tool_use_id === 'string' ? item.data.tool_use_id : '';
  const name = typeof item.data.name === 'string' ? item.data.name : '';
  return isAwaitingApproval({
    policy: policies.value[name],
    status: store.status,
    hasResult: toolUseId ? resultIndex.value.has(toolUseId) : false,
    hasConfirmation: toolUseId ? confirmIndex.value.has(toolUseId) : false,
  });
}

/** 工具调用配对结果条目。 */
function toolResult(item: TimelineItem): TimelineItem | null {
  const toolUseId = typeof item.data.tool_use_id === 'string' ? item.data.tool_use_id : '';
  return toolUseId ? resultIndex.value.get(toolUseId) ?? null : null;
}

/** 工具调用确认回显条目。 */
function toolConfirmation(item: TimelineItem): TimelineItem | null {
  const toolUseId = typeof item.data.tool_use_id === 'string' ? item.data.tool_use_id : '';
  return toolUseId ? confirmIndex.value.get(toolUseId) ?? null : null;
}

/** 工具权限策略。 */
function toolPolicy(item: TimelineItem): string | undefined {
  const name = typeof item.data.name === 'string' ? item.data.name : '';
  return policies.value[name];
}

/** 产物名 / 大小。 */
function artifactName(item: TimelineItem): string {
  return typeof item.data.original_filename === 'string' ? item.data.original_filename : '产物文件';
}

function artifactSize(item: TimelineItem): number {
  return typeof item.data.size === 'number' ? item.data.size : 0;
}

/** 条目时间（processed_at → HH:mm:ss；缺失/非法回落 '-'）。 */
function timeOf(item: TimelineItem): string {
  const raw = item.data.processed_at;
  return clockTime(typeof raw === 'string' ? raw : null);
}

/** 类型常量导出给模板。 */
const T = {
  USER: USER_MESSAGE_TYPE,
  AGENT: AGENT_MESSAGE_TYPE,
  THINKING: AGENT_THINKING_TYPE,
  ERROR: SESSION_ERROR_TYPE,
  ARTIFACT: ARTIFACT_DELIVERED_TYPE,
};
</script>

<template>
  <div ref="scroller" class="chat-pane">
    <EmptyGuide
      v-if="renderable.length === 0"
      :description="store.initializing ? '正在加载会话事件…' : '暂无对话事件，发送一条消息开始会话'"
    />

    <!-- 行式布局：角色标签 + 正文 + 右对齐时间（无气泡） -->
    <div v-for="item in renderable" :key="cardKey(item)" class="dda-chat-row">
      <!-- 用户消息 -->
      <template v-if="item.type === T.USER">
        <span class="dda-chip dda-chip--user">User</span>
        <div class="dda-chat-row__body">
          <div v-for="(block, bi) in contentBlocks(item)" :key="bi">
            <p v-if="isTextBlock(block)" class="dda-chat-row__text">{{ blockText(block) }}</p>
            <img
              v-else-if="imageSrcOf(block)"
              class="dda-chat-row__image"
              :src="imageSrcOf(block)"
              alt="用户图片"
            />
          </div>
        </div>
        <time class="dda-chat-row__time">{{ timeOf(item) }}</time>
      </template>

      <!-- 智能体消息（流式逐字 + 光标 / buffered 全文） -->
      <template v-else-if="item.type === T.AGENT">
        <span class="dda-chip dda-chip--agent">Agent</span>
        <div class="dda-chat-row__body">
          <span class="dda-chat-row__text">{{ item.streaming ? textOf(item) : fullTextOf(item) }}</span>
          <span v-if="item.streaming" class="dda-chat-row__cursor">▋</span>
        </div>
        <time class="dda-chat-row__time">{{ timeOf(item) }}</time>
      </template>

      <!-- 思考过程（折叠） -->
      <template v-else-if="item.type === T.THINKING">
        <span class="dda-chip dda-chip--system">Thinking</span>
        <div class="dda-chat-row__body">
          <t-collapse>
            <t-collapse-panel header="思考过程">
              <pre class="dda-chat-row__text">{{ item.streaming ? textOf(item) : '（思考阶段，未输出正文）' }}</pre>
            </t-collapse-panel>
          </t-collapse>
        </div>
        <time class="dda-chat-row__time">{{ timeOf(item) }}</time>
      </template>

      <!-- 工具调用（含结果回填与 HITL 审批） -->
      <template v-else-if="isToolUse(item)">
        <span class="dda-chip dda-chip--tool">Tool</span>
        <div class="dda-chat-row__body">
          <ToolApprovalCard
            :item="item"
            :policy="toolPolicy(item)"
            :result="toolResult(item)"
            :confirmation="toolConfirmation(item)"
            :awaiting="toolAwaiting(item)"
            @approve="onApprove"
            @deny="onDeny"
          />
        </div>
        <time class="dda-chat-row__time">{{ timeOf(item) }}</time>
      </template>

      <!-- 产物交付卡片 -->
      <template v-else-if="item.type === T.ARTIFACT">
        <span class="dda-chip dda-chip--system">Artifact</span>
        <div class="dda-chat-row__body">
          <div class="dda-chat-row__artifact">
            <t-tag theme="success" variant="light" size="small">产物交付</t-tag>
            <span class="dda-chat-row__artifact-name">{{ artifactName(item) }}</span>
            <span class="dda-chat-row__artifact-size">{{ artifactSize(item) }} bytes</span>
          </div>
        </div>
        <time class="dda-chat-row__time">{{ timeOf(item) }}</time>
      </template>

      <!-- 会话错误：红条 -->
      <template v-else-if="item.type === T.ERROR">
        <span class="dda-chip dda-chip--system">Error</span>
        <div class="dda-chat-row__body">
          <t-alert theme="error" :message="errorOf(item)" class="dda-chat-row__error" />
        </div>
        <time class="dda-chat-row__time">{{ timeOf(item) }}</time>
      </template>

      <!-- 兜底：未知业务事件（等宽事件类型标签） -->
      <template v-else>
        <span class="dda-chip dda-chip--event">{{ item.type || '(未知)' }}</span>
        <div class="dda-chat-row__body" />
        <time class="dda-chat-row__time">{{ timeOf(item) }}</time>
      </template>
    </div>
  </div>
</template>

<style scoped>
.chat-pane {
  display: flex;
  flex-direction: column;
  gap: 2px;
  height: 100%;
  padding: 12px 16px;
  overflow-y: auto;
}

/* 角色标签等宽对齐，便于纵向扫读 */
.chat-pane .dda-chip {
  justify-content: center;
  min-width: 62px;
  padding-top: 2px;
}

.dda-chat-row {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 8px 0;
  border-bottom: 1px solid var(--td-border-level-1-color);
}

.dda-chat-row:last-child {
  border-bottom: none;
}

.dda-chat-row__body {
  flex: 1;
  min-width: 0;
  line-height: 1.6;
  word-break: break-word;
}

.dda-chat-row__text {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
}

.dda-chat-row__time {
  flex: none;
  padding-top: 2px;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
  color: var(--td-text-color-placeholder);
}

.dda-chat-row__cursor {
  display: inline-block;
  margin-left: 2px;
  color: var(--td-brand-color);
  animation: chat-blink 1s steps(2, start) infinite;
}

@keyframes chat-blink {
  to {
    visibility: hidden;
  }
}

.dda-chat-row__image {
  display: block;
  max-width: 200px;
  max-height: 200px;
  margin-top: 6px;
  border-radius: 6px;
}

.dda-chat-row__artifact {
  display: flex;
  align-items: center;
  gap: 8px;
}

.dda-chat-row__artifact-name {
  font-weight: 600;
}

.dda-chat-row__artifact-size {
  font-size: 12px;
  color: var(--td-text-color-secondary);
}

.dda-chat-row__error {
  max-width: 640px;
}
</style>
