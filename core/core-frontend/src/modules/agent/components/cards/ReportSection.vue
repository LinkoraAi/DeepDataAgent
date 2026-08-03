<template>
  <div ref="reportRootRef" class="report-section agent-section">
    <div class="report-section__header">
      <span class="report-section__icon">📝</span>
      <span class="report-section__title">分析报告</span>
      <t-loading v-if="props.isAnalyzing" size="small" class="report-section__loading" />
    </div>
    <div class="report-section__content">
      <div v-if="props.report" class="report-section__report" v-html="renderedHtml"></div>
      <span v-if="props.isAnalyzing" class="streaming-cursor"></span>
      <div v-else-if="!props.report" class="report-section__empty">
        暂无分析报告
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch, onUnmounted, nextTick } from 'vue';
import { renderMarkdown } from '@/shared/utils/markdown';

/**
 * 分析报告区块组件 — 支持流式 markdown 渲染
 * <p>使用 requestAnimationFrame 节流渲染频率，避免高频 delta 更新导致卡顿。</p>
 * <p>报告内容更新后自动滚动到报告区域，确保用户看到最新输出。</p>
 * <p>分析中显示加载状态，分析完成后显示报告内容。</p>
 */
interface Props {
  /** 分析报告内容（markdown 格式） */
  report: string;
  /** 是否正在分析中（控制流式光标和加载状态显示） */
  isAnalyzing?: boolean;
}

const props = defineProps<Props>();

/** 报告根元素引用 */
const reportRootRef = ref<HTMLElement | null>(null);

/** 渲染后的 HTML 内容 */
const renderedHtml = ref('');
let rafId: number | null = null;

/** 滚动到报告区域（仅分析中平滑跟随，历史回放不触发滚动动画） */
function scrollToReport() {
  nextTick(() => {
    if (reportRootRef.value && typeof reportRootRef.value.scrollIntoView === 'function') {
      reportRootRef.value.scrollIntoView({ behavior: 'smooth', block: 'end' });
    }
  });
}

/** 监听 report 变化，使用 rAF 节流重新渲染 markdown */
watch(
  () => props.report,
  (newReport) => {
    if (rafId !== null) cancelAnimationFrame(rafId);
    rafId = requestAnimationFrame(() => {
      renderedHtml.value = newReport ? renderMarkdown(newReport) : '';
      rafId = null;
      // 仅分析中的流式渲染需要平滑跟随滚动；历史回放（挂载、非分析状态）不滚动
      if (props.isAnalyzing) {
        scrollToReport();
      }
    });
  },
  { immediate: true }
);

onUnmounted(() => {
  if (rafId !== null) cancelAnimationFrame(rafId);
});
</script>

<style scoped lang="less">
.report-section {
  &__header {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 8px;
  }

  &__icon {
    font-size: 16px;
  }

  &__title {
    font-weight: 600;
    font-size: 14px;
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

    // 表格样式
    :deep(table) {
      width: 100%;
      border-collapse: collapse;
      margin: 12px 0;
      font-size: 14px;

      th, td {
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

  &__empty {
    display: flex;
    align-items: center;
    justify-content: center;
    height: 100px;
    color: var(--td-text-color-secondary);
    font-size: 13px;
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
