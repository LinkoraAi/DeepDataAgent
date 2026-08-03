<template>
  <div class="timeline-thinking-item">
    <div class="timeline-thinking-item__content">
      <div ref="contentRef" class="timeline-thinking-item__text">
        {{ displayContent }}
        <span v-if="item.isStreaming" class="streaming-cursor"></span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onUnmounted } from 'vue';
import type { ThinkingTimelineItem } from '../../types';

/**
 * 思考项组件 — 仅展示思考内容与流式光标
 * <p>header 信息（💭 图标与"思考"标题）由父级 TimelineRound 统一展示。
 * 本组件使用 requestAnimationFrame 实现流式内容的平滑渲染。</p>
 */
interface Props {
  /** 思考时间线项 */
  item: ThinkingTimelineItem;
  /** 是否为最后一项（保留兼容，当前未使用） */
  isLast?: boolean;
}

const props = defineProps<Props>();

/** 内容 DOM 引用（保留用于未来扩展） */
const contentRef = ref<HTMLElement | null>(null);

/** requestAnimationFrame ID（用于清理） */
let animationFrameId: number | null = null;

/** 缓冲内容（用于平滑渲染） */
const bufferedContent = ref('');

/** 计算属性：展示内容（优先使用缓冲内容） */
const displayContent = computed(() => {
  return bufferedContent.value || props.item.content;
});

/** 使用 requestAnimationFrame 更新缓冲内容 */
function updateBufferedContent() {
  bufferedContent.value = props.item.content;
  if (props.item.isStreaming) {
    animationFrameId = requestAnimationFrame(updateBufferedContent);
  } else {
    animationFrameId = null;
  }
}

/** 监听内容变化 */
watch(
  () => props.item.content,
  () => {
    if (props.item.isStreaming && !animationFrameId) {
      animationFrameId = requestAnimationFrame(updateBufferedContent);
    }
  },
  { immediate: true }
);

/** 监听流式状态变化 */
watch(
  () => props.item.isStreaming,
  (isStreaming) => {
    if (!isStreaming && animationFrameId) {
      cancelAnimationFrame(animationFrameId);
      animationFrameId = null;
    } else if (isStreaming && !animationFrameId) {
      animationFrameId = requestAnimationFrame(updateBufferedContent);
    }
  }
);

/** 组件卸载时清理动画帧 */
onUnmounted(() => {
  if (animationFrameId) {
    cancelAnimationFrame(animationFrameId);
  }
});
</script>

<style scoped lang="less">
.timeline-thinking-item {
  display: flex;
  flex-direction: column;
  gap: 8px;

  &__content {
    padding: 8px 12px;
    font-size: 14px;
    line-height: 1.6;
  }

  &__text {
    color: var(--td-text-color-primary);
    white-space: pre-wrap;
    word-break: break-word;
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
    0%, 50% { opacity: 1; }
    51%, 100% { opacity: 0; }
  }
}
</style>
