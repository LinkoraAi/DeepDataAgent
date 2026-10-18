<script setup lang="ts">
import { computed, ref } from 'vue';
import type { TimelineItem } from '../chat/streamingReducer';
import { aggregateContentText, CONFIRM_ALLOW, CONFIRM_DENY } from '../chat/workspaceLogic';

/**
 * 工具调用卡片（agent.tool_use / mcp / custom）：展示工具名、入参 JSON 折叠、结果回填。
 * <p>HITL：当该工具策略为 always_ask、会话 running 且尚无结果 / 未裁决时，展示"允许 / 拒绝"
 * （拒绝可附原因）→ 由父级提交 user.tool_confirmation；已裁决则回显"已允许 / 已拒绝（原因）"。
 * 结果帧按 tool_use_id 回填，is_error 标红。</p>
 */

const props = defineProps<{
  /** 工具调用事件条目 */
  item: TimelineItem;
  /** 该工具的权限策略（会话快照解析；空 = 未配置） */
  policy?: string;
  /** 配对的工具结果条目（无则 null） */
  result?: TimelineItem | null;
  /** 确认回显条目（用户已裁决；无则 null） */
  confirmation?: TimelineItem | null;
  /** 是否待审批（父级用纯函数判定后下发） */
  awaiting?: boolean;
  /** 提交中（禁用按钮防连点） */
  submitting?: boolean;
}>();

const emit = defineEmits<{
  /** 允许该工具调用 */
  approve: [toolUseId: string];
  /** 拒绝该工具调用（附原因） */
  deny: [toolUseId: string, reason: string];
}>();

/** 拒绝原因输入面板显隐。 */
const denyOpen = ref(false);
/** 拒绝原因文本。 */
const denyReason = ref('');

/** 工具名（缺失回落占位）。 */
const toolName = computed(() => (typeof props.item.data.name === 'string' ? props.item.data.name : '(未知工具)'));

/** tool_use_id（缺失则无法审批）。 */
const toolUseId = computed(() => (typeof props.item.data.tool_use_id === 'string' ? props.item.data.tool_use_id : ''));

/** 入参格式化 JSON 文本。 */
const inputJson = computed(() => {
  try {
    return JSON.stringify(props.item.data.input ?? {}, null, 2);
  } catch {
    return String(props.item.data.input);
  }
});

/** 结果条目解析出的可读文本（output / content 兼容）与是否失败。 */
const resultText = computed<{ text: string; isError: boolean }>(() => {
  const result = props.result;
  if (!result) {
    return { text: '', isError: false };
  }
  const output = typeof result.data.output === 'string'
    ? result.data.output
    : aggregateContentText(result.data.content);
  const isError = result.data.is_error === true || String(result.data.state ?? '').toLowerCase() === 'error';
  return { text: output, isError };
});

/** 已裁决词汇（allow/deny），未裁决为空串。 */
const decided = computed<string>(() => {
  const raw = props.confirmation;
  if (!raw) {
    return '';
  }
  const value = typeof raw.data.result === 'string' ? raw.data.result : '';
  return value === CONFIRM_ALLOW ? 'allow' : value === CONFIRM_DENY ? 'deny' : '';
});

/** 已拒绝时回显的原因。 */
const denyMessage = computed<string>(() => {
  const raw = props.confirmation?.data.deny_message;
  return typeof raw === 'string' ? raw : '';
});

function onApprove(): void {
  if (toolUseId.value) {
    emit('approve', toolUseId.value);
  }
}

/** 打开拒绝面板（首次点击展开输入框，第二次提交）。 */
function onRequestDeny(): void {
  if (!toolUseId.value) {
    return;
  }
  if (!denyOpen.value) {
    denyOpen.value = true;
    return;
  }
  emit('deny', toolUseId.value, denyReason.value.trim());
  denyOpen.value = false;
  denyReason.value = '';
}
</script>

<template>
  <div class="tool-card" :class="{ 'tool-card--error': resultText.isError }">
    <div class="tool-card__head">
      <span class="tool-card__name" :title="toolName">{{ toolName }}</span>
      <t-tag v-if="policy === 'always_ask'" theme="warning" variant="light" size="small">需审批</t-tag>
      <t-tag v-if="item.streaming" theme="default" variant="light" size="small">进行中</t-tag>
    </div>

    <t-collapse v-if="inputJson && inputJson !== '{}'" :default-expand-all="false" class="tool-card__input">
      <t-collapse-panel header="入参" :value="inputJson">
        <pre class="tool-card__json">{{ inputJson }}</pre>
      </t-collapse-panel>
    </t-collapse>

    <div v-if="result" class="tool-card__result" :class="{ 'tool-card__result--error': resultText.isError }">
      <div class="tool-card__result-label">
        {{ resultText.isError ? '执行失败' : '执行结果' }}
      </div>
      <pre class="tool-card__json">{{ resultText.text || '（无输出）' }}</pre>
    </div>

    <div class="tool-card__actions">
      <template v-if="awaiting">
        <t-space direction="vertical" size="small" style="width: 100%">
          <t-space size="small">
            <t-button theme="success" size="small" :loading="submitting" @click="onApprove">允许</t-button>
            <t-button theme="danger" size="small" variant="outline" :loading="submitting" @click="onRequestDeny">
              {{ denyOpen ? '确认拒绝' : '拒绝' }}
            </t-button>
          </t-space>
          <t-textarea
            v-if="denyOpen"
            v-model="denyReason"
            size="small"
            :autosize="{ minRows: 2, maxRows: 4 }"
            placeholder="拒绝原因（可选，仅拒绝时随 user.tool_confirmation 提交）"
          />
        </t-space>
      </template>
      <template v-else-if="decided === 'allow'">
        <t-tag theme="success" variant="light" size="small">已允许</t-tag>
      </template>
      <template v-else-if="decided === 'deny'">
        <t-tag theme="danger" variant="light" size="small">已拒绝</t-tag>
        <span v-if="denyMessage" class="tool-card__deny-msg">{{ denyMessage }}</span>
      </template>
    </div>
  </div>
</template>

<style scoped>
.tool-card {
  max-width: 640px;
  margin: 2px 0;
  padding: 8px 10px;
  background: var(--td-bg-color-secondarycontainer);
  border: 1px solid var(--td-component-border);
  border-radius: var(--td-radius-default);
}

.tool-card--error {
  border-color: var(--td-error-color);
}

.tool-card__head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.tool-card__name {
  font-family: 'JetBrains Mono', Consolas, 'Courier New', monospace;
  font-size: 13px;
  font-weight: 600;
  word-break: break-all;
}

.tool-card__input {
  margin-top: 6px;
}

.tool-card__json {
  max-height: 240px;
  margin: 0;
  overflow: auto;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
}

.tool-card__result {
  margin-top: 8px;
  padding: 8px;
  background: var(--td-bg-color-container);
  border-radius: 6px;
}

.tool-card__result--error .tool-card__json {
  color: var(--td-error-color);
}

.tool-card__result-label {
  margin-bottom: 4px;
  font-size: 12px;
  color: var(--td-text-color-secondary);
}

.tool-card__actions {
  margin-top: 8px;
}

.tool-card__deny-msg {
  margin-left: 8px;
  font-size: 12px;
  color: var(--td-text-color-secondary);
}
</style>
