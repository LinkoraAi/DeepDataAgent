<template>
  <div class="timeline-round">
    <!-- 折叠状态：摘要行 -->
    <div v-if="isCollapsed" class="timeline-round__summary" @click="toggleCollapse">
      <span class="timeline-round__icon">💭</span>
      <span class="timeline-round__label">思考</span>
      <span class="timeline-round__arrow">→</span>
      <template v-for="(tool, idx) in round.toolCalls" :key="tool.id">
        <span class="timeline-round__tool-icon">🔧</span>
        <span class="timeline-round__tool-name">{{ tool.toolName }}</span>
        <span class="timeline-round__tool-status" :class="`timeline-round__tool-status--${tool.status}`">
          <template v-if="tool.status === 'success'">✓</template>
          <template v-else-if="tool.status === 'error'">✗</template>
          <template v-else>⋯</template>
        </span>
        <span v-if="tool.endTime" class="timeline-round__tool-duration">
          ({{ formatDuration(tool.startTime, tool.endTime) }})
        </span>
        <span v-if="idx < round.toolCalls.length - 1" class="timeline-round__tool-sep">+</span>
      </template>
      <span v-if="round.toolCalls.length === 0" class="timeline-round__no-tool">(无工具调用)</span>
      <button class="timeline-round__toggle" tabindex="-1" @click.stop="toggleCollapse">展开</button>
    </div>

    <!-- 展开状态：完整内容（平滑过渡，保持组件状态） -->
    <div v-show="!isCollapsed" class="timeline-round__expanded">
      <div class="timeline-round__header" @click="toggleCollapse">
        <span class="timeline-round__icon">💭</span>
        <span class="timeline-round__label">思考</span>
        <button class="timeline-round__toggle" tabindex="-1" @click.stop="toggleCollapse">折叠</button>
      </div>

      <!-- 思考内容（最大高度限制 + 滚动） -->
      <div
        v-if="round.thinking.content || round.thinking.isStreaming"
        ref="thinkingScrollRef"
        class="timeline-round__thinking"
        @scroll="handleScroll"
      >
        <TimelineThinkingItem :item="round.thinking" :is-last="false" />
      </div>

      <!-- 工具调用组（缩进 + 连接线） -->
      <div v-if="round.toolCalls.length > 0" class="timeline-round__tools">
        <div v-for="tool in round.toolCalls" :key="tool.id" class="timeline-round__tool">
          <div class="timeline-round__tool-connector">└─</div>
          <div class="timeline-round__tool-content">
            <TimelineToolCallItem :item="tool" :is-last="false" />
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch, nextTick } from 'vue';
import type { ReActRound } from '../../types';
import { calculateDuration } from '../../composables/useTimelineItem';
import TimelineThinkingItem from './TimelineThinkingItem.vue';
import TimelineToolCallItem from './TimelineToolCallItem.vue';

/**
 * 单个 ReAct 轮次渲染组件
 * <p>将一次"思考 + 后续工具调用"作为视觉单元展示。
 * 分析中默认展开（限制最大高度并滚动到底部），完成后默认折叠为摘要行，
 * 用户可点击展开按钮回看完整内容。</p>
 */
interface Props {
  /** 当前轮次对象 */
  round: ReActRound;
  /** 是否正在分析中（控制自动折叠行为） */
  isAnalyzing: boolean;
}

const props = defineProps<Props>();

/**
 * 当前折叠状态（本地管理，与 round.isCollapsed 双向同步）
 * <p>关键修复：初始值由 round.isActive 决定，而非 round.isCollapsed 快照。
 * 切换会话回来后 TimelineRound 重新挂载，watch round.isActive 不会立即触发，
 * 若沿用保存时的 isCollapsed 快照（完成轮次可能为 false），已完成轮次会错误展开。
 * 改为「active 展开 / inactive 折叠」，让分析中已完成的思考折叠、未完成的展开。</p>
 */
const isCollapsed = ref(!props.round.isActive);

/** 思考内容滚动容器引用 */
const thinkingScrollRef = ref<HTMLElement | null>(null);

/** 用户是否手动上滚（用于智能滚动控制） */
const isUserScrolledUp = ref(false);

/**
 * 切换折叠/展开状态
 */
function toggleCollapse() {
  isCollapsed.value = !isCollapsed.value;
  if (!isCollapsed.value) {
    // 展开后，若处于流式状态，滚动到底部
    if (props.round.isActive) {
      nextTick(() => scrollToBottom());
    }
  }
}

/**
 * 滚动思考内容到底部
 */
function scrollToBottom() {
  const el = thinkingScrollRef.value;
  if (!el) return;
  el.scrollTop = el.scrollHeight;
}

/**
 * 处理滚动事件：用户上滚时暂停自动滚动，滚回底部时恢复
 */
function handleScroll() {
  const el = thinkingScrollRef.value;
  if (!el) return;
  // 距底部超过阈值视为用户上滚
  const threshold = 30;
  isUserScrolledUp.value = el.scrollTop + el.clientHeight < el.scrollHeight - threshold;
}

/**
 * 格式化耗时（秒）
 */
function formatDuration(start: number, end: number): string {
  return calculateDuration(start, end);
}

// 监听思考内容变化，流式中自动滚动到底部
watch(
  () => props.round.thinking.content,
  () => {
    if (!isCollapsed.value && props.round.isActive && !isUserScrolledUp.value) {
      nextTick(() => scrollToBottom());
    }
  }
);

// 监听 isActive 变化：分析中自动展开，完成后自动折叠
watch(
  () => props.round.isActive,
  (active) => {
    if (active) {
      isCollapsed.value = false;
      isUserScrolledUp.value = false;
      nextTick(() => scrollToBottom());
    } else {
      // 完成后自动折叠（用户可手动展开回看）
      isCollapsed.value = true;
    }
  }
);

// 监听 isAnalyzing 变化：分析结束时确保折叠
watch(
  () => props.isAnalyzing,
  (analyzing) => {
    if (!analyzing && !props.round.isActive) {
      isCollapsed.value = true;
    }
  }
);
</script>

<style scoped lang="less">
.timeline-round {
  &__summary {
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 6px 8px;
    cursor: pointer;
    font-size: 13px;
    color: var(--td-text-color-secondary);
    border-radius: 4px;
    transition: background 0.2s cubic-bezier(0.4, 0, 0.2, 1);

    &:hover {
      background: var(--td-bg-color-container-hover);
    }
  }

  &__icon {
    font-size: 14px;
  }

  &__label {
    font-weight: 500;
    color: var(--td-text-color-primary);
  }

  &__arrow {
    color: var(--td-text-color-placeholder);
  }

  &__tool-icon {
    font-size: 13px;
  }

  &__tool-name {
    color: var(--td-text-color-primary);
    font-family: 'Courier New', monospace;
    font-size: 12px;
  }

  &__tool-status {
    font-size: 12px;

    &--success {
      color: var(--td-success-color);
    }

    &--error {
      color: var(--td-error-color);
    }

    &--running {
      color: var(--td-text-color-placeholder);
    }
  }

  &__tool-duration {
    font-size: 11px;
    color: var(--td-text-color-placeholder);
  }

  &__tool-sep {
    color: var(--td-text-color-placeholder);
    margin: 0 2px;
  }

  &__no-tool {
    color: var(--td-text-color-placeholder);
    font-style: italic;
    font-size: 12px;
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
    transition: all 0.2s cubic-bezier(0.4, 0, 0.2, 1);

    &:hover {
      color: var(--td-brand-color);
      border-color: var(--td-brand-color);
    }
  }

  &__expanded {
    display: flex;
    flex-direction: column;
    gap: 8px;
    animation: timeline-round-expand 0.3s cubic-bezier(0.4, 0, 0.2, 1);
  }

  &__header {
    display: flex;
    align-items: center;
    gap: 6px;
    cursor: pointer;
    font-size: 13px;
    color: var(--td-text-color-secondary);
  }

  // 展开/折叠动画
  @keyframes timeline-round-expand {
    from {
      opacity: 0;
      transform: translateY(-6px) scale(0.98);
    }
    to {
      opacity: 1;
      transform: translateY(0) scale(1);
    }
  }

  // Transition 动画：进入/离开时平滑过渡
  .timeline-round-enter-active,
  .timeline-round-leave-active {
    transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
    transform-origin: top;
  }

  .timeline-round-enter-from,
  .timeline-round-leave-to {
    opacity: 0;
    transform: translateY(-8px) scale(0.98);
  }

  .timeline-round-enter-to,
  .timeline-round-leave-from {
    opacity: 1;
    transform: translateY(0) scale(1);
  }

  &__thinking {
    // 流式中限制最大高度并滚动展示最新内容；完成后回看时同样保持限制
    max-height: 200px;
    overflow-y: auto;
    padding: 10px 14px;
    background: var(--td-bg-color-secondarycontainer);
    border-radius: 6px;
    font-size: 14px;
    line-height: 1.6;
    transition: max-height 0.3s cubic-bezier(0.4, 0, 0.2, 1);

    // 自定义滚动条样式
    &::-webkit-scrollbar {
      width: 6px;
    }

    &::-webkit-scrollbar-thumb {
      background: var(--td-bg-color-component);
      border-radius: 3px;
    }

    &::-webkit-scrollbar-track {
      background: transparent;
    }
  }

  &__tools {
    display: flex;
    flex-direction: column;
    gap: 8px;
    margin-left: 12px;
  }

  &__tool {
    display: flex;
    gap: 4px;
  }

  &__tool-connector {
    color: var(--td-text-color-placeholder);
    font-family: monospace;
    font-size: 13px;
    line-height: 1.6;
    user-select: none;
  }

  &__tool-content {
    flex: 1;
    min-width: 0;
  }
}
</style>
