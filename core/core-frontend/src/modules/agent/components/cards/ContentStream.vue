<template>
  <div class="content-stream agent-section">
    <div v-if="displayUnits.length === 0" class="content-stream__empty">
      暂无内容
    </div>
    <div v-else class="content-stream__items">
      <ContentItem
        v-for="unit in displayUnits"
        :key="unit.key"
        :item="unit.item"
        :result-item="unit.resultItem"
        :is-analyzing="isAnalyzing"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import type { ContentItem as ContentItemType } from '../../types';
import ContentItem from './ContentItem.vue';

/**
 * 统一内容流容器组件 — 按 seq 时序渲染对话内全部内容项
 * <p>思考、工具调用、报告统一在此容器内按接收顺序依次展示。
 * 展示层派生（D2）：工具调用项与其工具结果项按 `toolCallId` 合并为单个展示单元
 * （以调用项位置为锚），用户可明确每个结果归属；无配对标识或孤儿结果独立渲染，
 * 不做猜测配对（D4）。底层 contentItems 仍为「每事件一项」模型，合并仅发生在展示层。</p>
 */
interface Props {
  /** 统一内容流项（按接收时序追加） */
  items: ContentItemType[];
  /** 是否正在分析中（false 表示历史回放：默认折叠） */
  isAnalyzing: boolean;
}

const props = defineProps<Props>();

/**
 * 展示单元：合并单元（调用项 + 可选结果项）或独立项
 */
interface DisplayUnit {
  /** 渲染 key：合并单元用调用项 id，独立项用原 id（5.3） */
  key: string;
  /** 主项（合并单元为 tool_call 调用项；独立项可为任意类型） */
  item: ContentItemType;
  /** 合并模式结果项（同 toolCallId 的 tool_result 项） */
  resultItem?: ContentItemType;
}

/**
 * 派生展示列表（D2）
 * <p>按 seq 升序遍历：tool_result 项命中已输出调用的 toolCallId 时合并进调用单元
 * （结果跟随调用位置，而非到达位置）；无匹配（孤儿）或无 toolCallId 的项独立渲染。</p>
 */
const displayUnits = computed<DisplayUnit[]>(() => {
  const sorted = [...props.items].sort((a, b) => a.seq - b.seq);
  const units: DisplayUnit[] = [];
  /** toolCallId -> 已输出的调用单元（以调用项为锚） */
  const callIndex = new Map<string, DisplayUnit>();

  for (const item of sorted) {
    // 结果项：优先按 toolCallId 合并进对应调用单元
    if (item.type === 'tool_result' && item.toolCallId) {
      const call = callIndex.get(item.toolCallId);
      if (call) {
        call.resultItem = item;
        continue;
      }
      // 孤儿结果（有 toolCallId 但调用项缺失，异常路径）：独立渲染，不报错
      units.push({ key: item.id, item });
      continue;
    }

    // 调用项与其余类型项：独立渲染；调用项登记 toolCallId 供后续结果合并
    const unit: DisplayUnit = { key: item.id, item };
    units.push(unit);
    if (item.type === 'tool_call' && item.toolCallId) {
      callIndex.set(item.toolCallId, unit);
    }
  }
  return units;
});
</script>

<style scoped lang="less">
.content-stream {
  display: flex;
  flex-direction: column;
  min-width: 0;

  &__items {
    display: flex;
    flex-direction: column;
    gap: 10px;
  }

  &__empty {
    color: var(--td-text-color-placeholder);
    font-size: 13px;
    text-align: center;
    padding: 20px 0;
  }
}
</style>
