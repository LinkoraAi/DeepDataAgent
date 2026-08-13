<template>
  <div class="content-item" :class="`content-item--${item.type}`">
    <!-- 卡片头部：前置标签 + 折叠摘要 + 展开/折叠按钮 -->
    <div class="content-item__header" @click="toggleExpand">
      <span class="content-item__badge">{{ badgeText }}</span>

      <!-- 工具调用/工具结果展开态：展示工具名 -->
      <span
        v-if="(item.type === 'tool_call' || item.type === 'tool_result') && !isCollapsed"
        class="content-item__tool-name"
      >
        {{ item.toolName || '工具调用' }}
      </span>

      <!-- 折叠状态：摘要行 -->
      <span v-if="isCollapsed" class="content-item__summary">
        <template v-if="item.type === 'thinking'">
          {{ thinkingSummary }}
        </template>
        <template v-else-if="item.type === 'tool_call' || item.type === 'tool_result'">
          <span class="content-item__tool-name">{{ item.toolName || '工具调用' }}</span>
          <span class="content-item__tool-status" :class="`content-item__tool-status--${effectiveStatus}`">
            {{ statusIcon }}
          </span>
          <span v-if="effectiveEndTime" class="content-item__meta">
            ({{ calculateDuration(item.startTime, effectiveEndTime) }})
          </span>
        </template>
      </span>

      <t-loading v-if="effectiveStatus === 'in_progress'" size="small" class="content-item__loading" />

      <!-- 思考/工具项支持折叠；报告作为最终结论始终展开 -->
      <button
        v-if="item.type !== 'report'"
        class="content-item__toggle"
        tabindex="-1"
        @click.stop="toggleExpand"
      >
        {{ isCollapsed ? '展开' : '折叠' }}
      </button>
    </div>

    <!-- 展开状态：完整内容 -->
    <div v-show="!isCollapsed" class="content-item__body">
      <template v-if="item.type === 'thinking'">
        <div class="content-item__text">
          {{ item.content }}
          <span v-if="isStreaming" class="streaming-cursor"></span>
        </div>
      </template>

      <template v-else-if="item.type === 'tool_call'">
        <!-- 合并模式（D3）：调用或结果任一失败 → 失败态，展开显示错误 -->
        <div v-if="effectiveStatus === 'failed'" class="content-item__failure">
          <span class="content-item__tool-status content-item__tool-status--failed">✗</span>
          <span>执行失败</span>
        </div>
        <!-- 入参在上（入参流式时显示光标） -->
        <div v-if="item.input" class="content-item__section">
          <div class="content-item__label">输入参数</div>
          <pre class="content-item__code">{{ formattedInput }}<span v-if="isStreaming && item.status === 'in_progress'" class="streaming-cursor"></span></pre>
        </div>
        <!-- 结果在下：合并模式优先取结果项（同 toolCallId），否则回退调用项自身 result -->
        <div v-if="resultText" class="content-item__section">
          <div class="content-item__label">执行结果</div>
          <pre class="content-item__code">{{ resultText }}<span v-if="isStreaming" class="streaming-cursor"></span></pre>
        </div>
        <div v-else-if="isMerged" class="content-item__empty">暂无返回内容</div>
        <!-- 中断态（D3）：调用完成、无结果项/无结果 → 有入参、无结果，不被误认为正常调用 -->
        <div v-else-if="item.status === 'completed'" class="content-item__empty">有入参、无结果</div>
      </template>

      <template v-else-if="item.type === 'tool_result'">
        <div v-if="item.status === 'failed'" class="content-item__failure">
          <span class="content-item__tool-status content-item__tool-status--failed">✗</span>
          <span>执行失败</span>
        </div>
        <div v-if="item.result" class="content-item__section">
          <div class="content-item__label">返回结果</div>
          <pre class="content-item__code">{{ formattedResult }}<span v-if="isStreaming" class="streaming-cursor"></span></pre>
        </div>
        <div v-if="!item.result" class="content-item__empty">暂无返回内容</div>
      </template>

      <template v-else-if="item.type === 'report'">
        <div v-if="item.content" class="content-item__markdown" v-html="renderedHtml"></div>
        <span v-if="isStreaming" class="streaming-cursor"></span>
        <div v-else-if="!item.content" class="content-item__empty">暂无内容</div>
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onUnmounted } from 'vue';
import type { ContentItem } from '../../types';
import { calculateDuration, formatJson, safeJsonParse } from '../../composables/useTimelineItem';
import { renderMarkdown } from '@/shared/utils/markdown';

/**
 * 统一内容流项组件 — 思考/工具调用/工具结果/报告共用同一容器与视觉样式
 * <p>仅通过前置标签区分类型，不再使用差异化卡片。展开/折叠由 status 双向驱动：
 * 思考/工具 in_progress 自动展开（流式光标），completed 自动折叠为摘要，
 * failed 保持展开显示错误详情；历史回放（非分析中）默认折叠。用户可手动切换。</p>
 * <p>合并模式（D2/D3）：传入同 toolCallId 的 resultItem 时，调用项与结果项作为单个
 * 展示单元渲染（入参在上、结果在下、统一折叠），状态由二者综合派生。</p>
 */
interface Props {
  /** 内容流项 */
  item: ContentItem;
  /** 合并模式结果项（ContentStream 按 toolCallId 配对传入；缺省为独立渲染） */
  resultItem?: ContentItem;
  /** 是否正在分析中（false 表示历史回放：默认折叠） */
  isAnalyzing: boolean;
}

const props = defineProps<Props>();

/** 是否折叠（由 status 与 isAnalyzing 双向驱动，用户手动切换临时覆盖） */
const isCollapsed = ref(false);

/** 合并模式：是否携带同 toolCallId 的结果项 */
const isMerged = computed(() => !!props.resultItem);

/**
 * 综合状态（D3）——合并模式下由调用项与结果项派生：
 * 调用或结果任一 failed → failed；任一 in_progress → in_progress（结果流式光标）；
 * 其余（调用/结果均 completed，或独立项）→ 该状态本身。
 */
const effectiveStatus = computed(() => {
  const callStatus = props.item.status;
  const resultStatus = props.resultItem?.status;
  if (callStatus === 'failed' || resultStatus === 'failed') return 'failed';
  if (callStatus === 'in_progress' || resultStatus === 'in_progress') return 'in_progress';
  return 'completed';
});

/** 综合结束时间：合并模式取结果项结束时间（结果收敛即整个调用单元结束） */
const effectiveEndTime = computed(() => props.resultItem?.endTime ?? props.item.endTime);

/** 合并模式结果文本：优先取结果项，否则回退调用项自身 result（老数据兼容） */
const resultText = computed(() => {
  const raw = props.resultItem?.result ?? props.item.result ?? '';
  if (!raw) return '';
  const parsed = safeJsonParse(raw);
  return parsed ? formatJson(parsed) : raw;
});

/** 前置标签文案 */
const badgeText = computed(() => {
  if (props.item.type === 'thinking') return '💭 思考';
  if (props.item.type === 'tool_call') return '🔧 工具调用';
  if (props.item.type === 'tool_result') return '📥 工具结果';
  return '📝 分析报告';
});

/** 是否处于流式输出（综合状态进行中且实时分析中，历史回放不显示光标） */
const isStreaming = computed(() => {
  return effectiveStatus.value === 'in_progress' && props.isAnalyzing;
});

/** 思考折叠摘要：类型 + 内容预览（截断） */
const thinkingSummary = computed(() => {
  const content = props.item.content || '';
  return content.length > 30 ? `${content.slice(0, 30)}…` : (content || '(空)');
});

/** 工具状态图标（综合状态） */
const statusIcon = computed(() => {
  if (effectiveStatus.value === 'failed') return '✗';
  if (effectiveStatus.value === 'completed') return '✓';
  return '⋯';
});

/** 格式化输入参数 JSON */
const formattedInput = computed(() => {
  if (!props.item.input) return '';
  const parsed = safeJsonParse(props.item.input);
  return parsed ? formatJson(parsed) : props.item.input;
});

/** 格式化执行结果 JSON */
const formattedResult = computed(() => {
  if (!props.item.result) return '';
  const parsed = safeJsonParse(props.item.result);
  return parsed ? formatJson(parsed) : props.item.result;
});

/** 报告渲染后的 HTML 内容（rAF 节流，避免高频 delta 更新导致 markdown 渲染卡顿） */
const renderedHtml = ref('');
let rafId: number | null = null;

/** 监听报告内容变化，节流渲染 markdown */
watch(
  () => props.item.content,
  (content) => {
    if (props.item.type !== 'report') return;
    if (rafId !== null) cancelAnimationFrame(rafId);
    rafId = requestAnimationFrame(() => {
      renderedHtml.value = content ? renderMarkdown(content) : '';
      rafId = null;
    });
  },
  { immediate: true }
);

/** 依据当前状态应用自动展开/折叠 */
function applyAutoState() {
  // 报告作为最终结论始终展开（不参与折叠）
  if (props.item.type === 'report') {
    isCollapsed.value = false;
    return;
  }
  if (!props.isAnalyzing) {
    // 历史回放：统一默认折叠（含 IN_PROGRESS 合并展示的数据）
    isCollapsed.value = true;
    return;
  }
  if (effectiveStatus.value === 'in_progress' || effectiveStatus.value === 'failed') {
    isCollapsed.value = false;
  } else {
    isCollapsed.value = true;
  }
}

/** 综合状态变化时自动跟随展开/折叠（覆盖调用项与结果项的状态变更） */
watch(() => effectiveStatus.value, () => {
  applyAutoState();
}, { immediate: true });

/** 分析结束（isAnalyzing 变 false）时统一折叠 */
watch(() => props.isAnalyzing, applyAutoState);

/** 手动切换展开/折叠 */
function toggleExpand() {
  isCollapsed.value = !isCollapsed.value;
}

/** 组件卸载时清理动画帧 */
onUnmounted(() => {
  if (rafId !== null) cancelAnimationFrame(rafId);
});
</script>

<style scoped lang="less">
.content-item {
  display: flex;
  flex-direction: column;
  background: var(--td-bg-color-container);
  border: 1px solid var(--td-border-level-1-color);
  border-radius: 8px;
  overflow: hidden;
  transition: border-color 0.2s;

  &:hover {
    border-color: var(--td-border-level-2-color);
  }

  &__header {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 8px 12px;
    cursor: pointer;
    user-select: none;
    background: var(--td-bg-color-container);

    &:hover {
      background: var(--td-bg-color-container-hover);
    }
  }

  &__badge {
    font-size: 12px;
    font-weight: 500;
    color: var(--td-text-color-secondary);
    white-space: nowrap;
  }

  &__summary {
    flex: 1;
    min-width: 0;
    display: flex;
    align-items: center;
    gap: 6px;
    font-size: 13px;
    color: var(--td-text-color-primary);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__tool-name {
    font-family: 'Courier New', monospace;
    font-size: 12px;
    color: var(--td-text-color-primary);
  }

  &__tool-status {
    font-size: 12px;

    &--completed {
      color: var(--td-success-color);
    }

    &--failed {
      color: var(--td-error-color);
    }

    &--in_progress {
      color: var(--td-text-color-placeholder);
    }
  }

  &__meta {
    font-size: 11px;
    color: var(--td-text-color-placeholder);
  }

  &__loading {
    margin-left: auto;
    display: inline-flex;
    align-items: center;
  }

  &__toggle {
    margin-left: auto;
    padding: 2px 8px;
    border: 1px solid var(--td-border-level-1-color);
    background: transparent;
    border-radius: 4px;
    font-size: 11px;
    color: var(--td-text-color-secondary);
    cursor: pointer;
    transition: all 0.2s;

    &:hover {
      color: var(--td-brand-color);
      border-color: var(--td-brand-color);
    }
  }

  &__body {
    padding: 10px 12px;
    border-top: 1px dashed var(--td-border-level-1-color);
    font-size: 14px;
    line-height: 1.6;
    animation: content-item-expand 0.25s cubic-bezier(0.4, 0, 0.2, 1);
  }

  &__text {
    color: var(--td-text-color-primary);
    white-space: pre-wrap;
    word-break: break-word;
    color: var(--td-text-color-secondary);
  }

  &__section {
    display: flex;
    flex-direction: column;
    gap: 4px;

    & + & {
      margin-top: 8px;
    }
  }

  &__failure {
    display: flex;
    align-items: center;
    gap: 6px;
    margin-bottom: 8px;
    padding: 6px 10px;
    font-size: 12px;
    color: var(--td-error-color);
    background: var(--td-error-color-container, rgba(255, 60, 60, 0.08));
    border-radius: 4px;
  }

  &__label {
    font-size: 12px;
    font-weight: 500;
    color: var(--td-text-color-secondary);
  }

  &__code {
    margin: 0;
    padding: 8px;
    background: var(--td-bg-color-secondarycontainer);
    border-radius: 4px;
    font-family: 'Courier New', monospace;
    font-size: 12px;
    line-height: 1.5;
    color: var(--td-text-color-primary);
    overflow-x: auto;
    white-space: pre-wrap;
    word-break: break-word;
  }

  &__empty {
    color: var(--td-text-color-placeholder);
    font-size: 13px;
    padding: 8px 0;
  }

  &__markdown {
    :deep(h1),
    :deep(h2),
    :deep(h3) {
      margin-top: 16px;
      margin-bottom: 8px;
      font-weight: 600;
    }

    :deep(p) {
      margin-bottom: 8px;
    }

    :deep(ul),
    :deep(ol) {
      padding-left: 24px;
      margin-bottom: 8px;
    }

    :deep(code) {
      background: var(--td-bg-color-secondarycontainer);
      padding: 2px 6px;
      border-radius: 4px;
      font-family: 'Courier New', monospace;
      font-size: 13px;
    }

    :deep(pre) {
      background: var(--td-bg-color-secondarycontainer);
      padding: 16px;
      border-radius: 6px;
      overflow-x: auto;
      margin: 12px 0;

      code {
        background: transparent;
        padding: 0;
      }
    }

    :deep(table) {
      width: 100%;
      border-collapse: collapse;
      margin: 12px 0;
      font-size: 14px;

      th,
      td {
        padding: 10px 14px;
        text-align: left;
        border: 1px solid var(--td-border-level-1-color, #e7e7e7);
      }

      th {
        background: var(--td-bg-color-secondarycontainer, #f5f5f5);
        font-weight: 600;
        color: var(--td-text-color-primary);
      }

      tbody tr:nth-child(even) {
        background: var(--td-bg-color-container, #fafafa);
      }

      tbody tr:hover {
        background: var(--td-bg-color-hover, #f0f0f0);
      }
    }

    :deep(blockquote) {
      border-left: 4px solid var(--td-brand-color);
      padding: 8px 16px;
      margin: 12px 0;
      color: var(--td-text-color-secondary);
      background: var(--td-bg-color-secondarycontainer);
      border-radius: 0 4px 4px 0;
    }

    :deep(hr) {
      border: none;
      border-top: 1px solid var(--td-border-level-1-color, #e7e7e7);
      margin: 16px 0;
    }

    :deep(a) {
      color: var(--td-brand-color);
      text-decoration: none;

      &:hover {
        text-decoration: underline;
      }
    }

    :deep(img) {
      max-width: 100%;
      height: auto;
      border-radius: 4px;
    }
  }

  @keyframes content-item-expand {
    from {
      opacity: 0;
      transform: translateY(-4px);
    }
    to {
      opacity: 1;
      transform: translateY(0);
    }
  }
}

.streaming-cursor {
  display: inline-block;
  width: 2px;
  height: 1em;
  background: var(--td-brand-color);
  margin-left: 2px;
  vertical-align: text-bottom;
  animation: blink 1s step-end infinite;

  @keyframes blink {
    0%,
    50% {
      opacity: 1;
    }
    51%,
    100% {
      opacity: 0;
    }
  }
}
</style>
