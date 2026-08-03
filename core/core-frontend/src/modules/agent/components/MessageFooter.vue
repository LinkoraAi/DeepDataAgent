<template>
  <div class="message-footer">
    <div class="message-footer__actions">
      <t-button
        theme="default"
        variant="text"
        size="small"
        @click="handleCopy"
      >
        <template #icon><copy-icon /></template>
        复制
      </t-button>
      <t-button
        theme="default"
        variant="text"
        size="small"
        @click="$emit('retry')"
      >
        <template #icon><refresh-icon /></template>
        重新生成
      </t-button>
      <t-button
        theme="default"
        variant="text"
        size="small"
        :class="{ 'message-footer__btn--active': isFavorited }"
        @click="toggleFavorite"
      >
        <template #icon><star-icon /></template>
        {{ isFavorited ? '已收藏' : '收藏' }}
      </t-button>
      <div class="message-footer__divider" />
      <t-button
        theme="default"
        variant="text"
        size="small"
        :class="{ 'message-footer__btn--active': feedbackType === 'like' }"
        @click="handleFeedback('like')"
      >
        <template #icon><thumb-up-icon /></template>
      </t-button>
      <t-button
        theme="default"
        variant="text"
        size="small"
        :class="{ 'message-footer__btn--active': feedbackType === 'dislike' }"
        @click="handleFeedback('dislike')"
      >
        <template #icon><thumb-down-icon /></template>
      </t-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import { CopyIcon, RefreshIcon, StarIcon, ThumbUpIcon, ThumbDownIcon } from 'tdesign-icons-vue-next';
import type { ChatMessage } from '../types';
import { copyToClipboard } from '@/shared/utils/copy';

/**
 * 消息底部操作栏组件 — 提供复制、重新生成、收藏、反馈操作
 */
interface Props {
  /** 当前消息对象 */
  message: ChatMessage;
}

const props = defineProps<Props>();

const emit = defineEmits<{
  (e: 'retry'): void;
  (e: 'feedback', payload: { type: 'like' | 'dislike' }): void;
}>();

/** 收藏状态（MVP 级别，本地状态） */
const isFavorited = ref(false);

/** 反馈类型 */
const feedbackType = ref<'like' | 'dislike' | null>(null);

/** 复制分析报告到剪贴板 */
async function handleCopy() {
  const report = props.message.analysisState?.analysisReport || props.message.content;
  if (report) {
    await copyToClipboard(report);
  }
}

/** 切换收藏状态 */
function toggleFavorite() {
  isFavorited.value = !isFavorited.value;
}

/** 处理反馈 */
function handleFeedback(type: 'like' | 'dislike') {
  feedbackType.value = feedbackType.value === type ? null : type;
  emit('feedback', { type });
}
</script>

<style scoped lang="less">
.message-footer {
  border-top: 1px solid var(--td-border-level-1-color);
  padding: 8px 16px;

  &__actions {
    display: flex;
    align-items: center;
    gap: 4px;
  }

  &__divider {
    width: 1px;
    height: 16px;
    background: var(--td-border-level-2-color);
    margin: 0 4px;
  }

  &__btn--active {
    color: var(--td-brand-color);
  }
}
</style>
