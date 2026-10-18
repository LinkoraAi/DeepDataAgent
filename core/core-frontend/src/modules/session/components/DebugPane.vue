<script setup lang="ts">
import { computed, ref } from 'vue';
import EmptyGuide from '@/shared/components/EmptyGuide.vue';
import { clockTime } from '@/shared/utils/time';
import { useWorkspaceStore } from '../stores/workspace';
import type { TimelineItem } from '../chat/streamingReducer';
import { collectEventTypes } from '../chat/workspaceLogic';

/**
 * 调试视图：按时间线逐帧展示事件类型标签、等宽 JSON 摘要与本地时区时刻，
 * 点击行展开完整 JSON（前缀 simple 格式化，仅 JSON.stringify 缩进）。
 * <p>含 span.model_request_start/end 等计量事件（对话视图不渲染，此处全量可见）。
 * 类型过滤下拉取自当前时间线现集去重。与对话视图共享同一份 store 时间线。</p>
 */

const store = useWorkspaceStore();

/** 单行 JSON 摘要截断上限（超出以省略号收尾，悬浮 title 展示全文）。 */
const SUMMARY_MAX = 160;

/** 类型过滤（空 = 全部）。 */
const filterType = ref<string>('');

/** 展开的条目 id 集合。 */
const expanded = ref<Set<string>>(new Set());

/** 过滤下拉选项（现集去重）。 */
const typeOptions = computed(() => [
  { label: '全部类型', value: '' },
  ...collectEventTypes(store.timeline).map((type) => ({ label: type, value: type })),
]);

/** 过滤后的时间线。 */
const rows = computed<TimelineItem[]>(() => {
  if (!filterType.value) {
    return store.timeline;
  }
  return store.timeline.filter((item) => item.type === filterType.value);
});

/** 切换展开。 */
function toggle(id: string): void {
  const next = new Set(expanded.value);
  if (next.has(id)) {
    next.delete(id);
  } else {
    next.add(id);
  }
  expanded.value = next;
}

/** 条目时刻 HH:mm:ss（processed_at；缺失回落流式占位标记）。 */
function timeOf(item: TimelineItem): string {
  const raw = item.data.processed_at;
  if (typeof raw !== 'string' || !raw) {
    return item.streaming ? '（流式中）' : '-';
  }
  return clockTime(raw);
}

/** 原始 JSON 文本。 */
function jsonOf(item: TimelineItem): string {
  try {
    return JSON.stringify(item.data, null, 2);
  } catch {
    return '{}';
  }
}

/** 单行 JSON 摘要全文（悬浮 title 用）。 */
function summaryOf(item: TimelineItem): string {
  try {
    return JSON.stringify(item.data) ?? '';
  } catch {
    return '';
  }
}

/** 单行 JSON 摘要展示文本（超长截断）。 */
function summaryText(item: TimelineItem): string {
  const text = summaryOf(item);
  return text.length > SUMMARY_MAX ? `${text.slice(0, SUMMARY_MAX)}…` : text;
}
</script>

<template>
  <div class="debug-pane">
    <div class="debug-pane__toolbar">
      <t-select v-model="filterType" :options="typeOptions" placeholder="事件类型过滤" clearable style="width: 240px" />
      <span class="debug-pane__count">共 {{ store.timeline.length }} 帧，当前 {{ rows.length }} 帧</span>
    </div>

    <EmptyGuide v-if="rows.length === 0" description="无匹配事件帧" />

    <div v-for="item in rows" :key="item.id" class="debug-pane__row">
      <div class="debug-pane__line" @click="toggle(item.id)">
        <span class="dda-chip dda-chip--event">{{ item.type || '(未知)' }}</span>
        <span v-if="item.streaming" class="debug-pane__streaming">流式</span>
        <span class="debug-pane__summary" :title="summaryOf(item)">{{ summaryText(item) }}</span>
        <time class="debug-pane__time">{{ timeOf(item) }}</time>
        <t-link theme="primary" hover="color">{{ expanded.has(item.id) ? '收起' : '展开 JSON' }}</t-link>
      </div>
      <pre v-if="expanded.has(item.id)" class="debug-pane__json">{{ jsonOf(item) }}</pre>
    </div>
  </div>
</template>

<style scoped>
.debug-pane {
  height: 100%;
  padding: 12px 16px;
  overflow-y: auto;
}

.debug-pane__toolbar {
  position: sticky;
  top: 0;
  z-index: 1;
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 8px;
  padding-bottom: 4px;
  background: var(--td-bg-color-container);
}

.debug-pane__count {
  font-size: 12px;
  color: var(--td-text-color-secondary);
}

.debug-pane__row {
  border-bottom: 1px solid var(--td-border-level-1-color);
}

.debug-pane__line {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 7px 0;
  cursor: pointer;
}

.debug-pane__line:hover {
  background: var(--td-bg-color-container-hover);
}

/* 事件类型标签等宽 + 限宽省略 */
.debug-pane__line .dda-chip {
  max-width: 260px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.debug-pane__summary {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  font-family: 'JetBrains Mono', Consolas, 'Courier New', monospace;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--td-text-color-secondary);
}

.debug-pane__time {
  flex: none;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
  color: var(--td-text-color-placeholder);
}

.debug-pane__streaming {
  flex: none;
  font-size: 12px;
  color: var(--td-warning-color);
}

.debug-pane__json {
  margin: 4px 0 8px;
  padding: 8px;
  max-height: 320px;
  overflow: auto;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
  background: var(--td-bg-color-secondarycontainer);
  border-radius: 6px;
}
</style>
