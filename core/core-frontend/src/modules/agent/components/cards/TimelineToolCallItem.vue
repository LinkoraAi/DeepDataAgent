<template>
  <div class="timeline-tool-call-item">
    <!-- 工具详情（输入参数 + 执行结果） -->
    <div v-if="item.input || item.result" class="timeline-tool-call-item__details">
      <div v-if="item.input" class="timeline-tool-call-item__section">
        <div class="timeline-tool-call-item__label">输入参数</div>
        <pre class="timeline-tool-call-item__code">{{ formattedInput }}</pre>
      </div>

      <div v-if="item.result" class="timeline-tool-call-item__section">
        <div class="timeline-tool-call-item__label">执行结果</div>
        <pre class="timeline-tool-call-item__code">{{ formattedResult }}</pre>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import type { ToolCallTimelineItem } from '../../types';
import { formatJson } from '../../composables/useTimelineItem';

/**
 * 工具调用项组件 — 仅展示输入参数与执行结果详情
 * <p>header 信息（工具名、状态、耗时）由父级 TimelineRound 的摘要行统一展示。
 * 本组件负责详情区域的渲染，添加缩进与连接线体现归属当前轮次。</p>
 */
interface Props {
  /** 工具调用时间线项 */
  item: ToolCallTimelineItem;
  /** 是否为最后一项（保留兼容，当前未使用） */
  isLast?: boolean;
}

const props = defineProps<Props>();

/** 格式化输入参数 JSON */
const formattedInput = computed(() => {
  if (!props.item.input) return '';
  try {
    return formatJson(JSON.parse(props.item.input));
  } catch {
    return props.item.input;
  }
});

/** 格式化执行结果 JSON */
const formattedResult = computed(() => {
  if (!props.item.result) return '';
  try {
    return formatJson(JSON.parse(props.item.result));
  } catch {
    return props.item.result;
  }
});
</script>

<style scoped lang="less">
.timeline-tool-call-item {
  display: flex;
  flex-direction: column;
  gap: 8px;

  &__details {
    display: flex;
    flex-direction: column;
    gap: 8px;
    padding: 8px 12px;
    background: var(--td-bg-color-secondarycontainer);
    border-radius: 6px;
    max-height: 120px;
    overflow-y: auto;

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

  &__section {
    display: flex;
    flex-direction: column;
    gap: 4px;
  }

  &__label {
    font-size: 12px;
    font-weight: 500;
    color: var(--td-text-color-secondary);
  }

  &__code {
    margin: 0;
    padding: 8px;
    background: var(--td-bg-color-container);
    border-radius: 4px;
    font-family: 'Courier New', monospace;
    font-size: 12px;
    line-height: 1.5;
    color: var(--td-text-color-primary);
    overflow-x: auto;
    white-space: pre-wrap;
    word-break: break-word;
  }
}
</style>
