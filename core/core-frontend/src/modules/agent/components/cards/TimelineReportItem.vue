<template>
  <div class="timeline-report-item">
    <div class="timeline-report-item__header">
      <span class="timeline-report-item__icon">📝</span>
      <t-loading v-if="item.isStreaming" size="small" class="timeline-report-item__loading" />
    </div>
    <div class="timeline-report-item__content">
      <div v-if="item.content" class="timeline-report-item__report" v-html="renderedHtml"></div>
      <span v-if="item.isStreaming" class="streaming-cursor"></span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch, onUnmounted } from 'vue';
import type { ReportTimelineItem } from '../../types';
import { renderMarkdown } from '@/shared/utils/markdown';

/**
 * 报告项组件 — 渲染时间线内的分析报告
 * <p>使用 requestAnimationFrame 实现 Markdown 流式渲染的平滑更新。</p>
 */
interface Props {
  /** 报告时间线项 */
  item: ReportTimelineItem;
}

const props = defineProps<Props>();

/** 渲染后的 HTML */
const renderedHtml = ref('');
let animationFrameId: number | null = null;

/** 使用 requestAnimationFrame 更新渲染内容 */
function updateRenderedHtml() {
  renderedHtml.value = props.item.content ? renderMarkdown(props.item.content) : '';
  if (props.item.isStreaming) {
    animationFrameId = requestAnimationFrame(updateRenderedHtml);
  } else {
    animationFrameId = null;
  }
}

/** 监听内容变化 */
watch(
  () => props.item.content,
  () => {
    if (props.item.isStreaming && !animationFrameId) {
      animationFrameId = requestAnimationFrame(updateRenderedHtml);
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
      animationFrameId = requestAnimationFrame(updateRenderedHtml);
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
.timeline-report-item {
  display: flex;
  flex-direction: column;
  gap: 8px;

  &__header {
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: 13px;
    color: var(--td-text-color-secondary);
  }

  &__icon {
    font-size: 14px;
  }

  &__label {
    font-weight: 500;
    color: var(--td-text-color-primary);
  }

  &__loading {
    margin-left: 8px;
    display: inline-flex;
    align-items: center;
  }

  &__content {
    line-height: 1.8;
  }

  &__report {
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
