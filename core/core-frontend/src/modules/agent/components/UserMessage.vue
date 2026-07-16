<template>
  <div class="user-message">
    <div class="user-message__content">
      <div class="user-message__text">{{ message.content }}</div>
    </div>
    <div class="user-message__footer">
      <span class="user-message__role">你</span>
      <span class="user-message__time">{{ formatTime(message.timestamp) }}</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import type { ChatMessage } from '../types';

interface Props {
  message: ChatMessage;
}

defineProps<Props>();

function formatTime(timestamp: number): string {
  const date = new Date(timestamp);
  const hours = date.getHours().toString().padStart(2, '0');
  const minutes = date.getMinutes().toString().padStart(2, '0');
  return `${hours}:${minutes}`;
}
</script>

<style scoped lang="less">
.user-message {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 4px;
  max-width: 70%;
  margin-left: auto;

  &__content {
    padding: 12px 16px;
    background: var(--td-brand-color);
    color: white;
    border-radius: 12px 12px 4px 12px;
    line-height: 1.6;
    word-break: break-word;
  }

  &__text {
    font-size: 14px;
  }

  &__footer {
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: 11px;
    color: var(--td-text-color-placeholder);
  }

  &__role {
    font-weight: 500;
  }
}
</style>
