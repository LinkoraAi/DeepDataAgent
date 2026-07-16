<template>
  <div class="session-sidebar">
    <div class="session-sidebar__header">
      <button class="session-sidebar__new-btn" @click="handleNewSession">
        <t-icon name="add" size="16px" />
        <span>新建对话</span>
      </button>
    </div>

    <t-loading :loading="sessionStore.loading" text="加载中...">
      <div v-if="sessionStore.sessions.length === 0" class="session-sidebar__empty">
        <t-empty description="暂无会话" size="small" />
      </div>

      <div v-else class="session-sidebar__list">
        <div
          v-for="session in sessionStore.sessions"
          :key="session.id"
          :class="['session-item', { 'session-item--active': session.id === sessionStore.currentSessionId }]"
          @click="handleSelectSession(session.id)"
        >
          <div class="session-item__content">
            <div class="session-item__title">{{ session.title }}</div>
            <div class="session-item__meta">
              <span>{{ formatRelativeTime(session.lastMessageAt || session.createdAt) }}</span>
              <span>·</span>
              <span>{{ session.messageCount }} 条</span>
            </div>
          </div>
          <button
            class="session-item__close"
            @click.stop="handleCloseSession(session.id)"
            title="删除会话"
          >
            <t-icon name="close" size="14px" />
          </button>
        </div>
      </div>
    </t-loading>
  </div>
</template>

<script setup lang="ts">
import { MessagePlugin } from 'tdesign-vue-next';
import { useSessionStore } from '../stores/session';
import { useDatasourceStore } from '../stores/datasource';
import { useModelStore } from '@/modules/model/stores/model';
import { formatRelativeTime } from '@/shared/utils/format';

const sessionStore = useSessionStore();
const datasourceStore = useDatasourceStore();
const modelStore = useModelStore();

/** 新建会话 */
async function handleNewSession() {
  if (!datasourceStore.currentDatasourceId) {
    MessagePlugin.warning('请先选择数据源');
    return;
  }
  if (!modelStore.selectedConfigId) {
    MessagePlugin.warning('请先选择模型');
    return;
  }

  try {
    await sessionStore.createSession(
      datasourceStore.currentDatasourceId,
      modelStore.selectedConfigId
    );
    MessagePlugin.success('创建会话成功');
  } catch (err) {
    console.error('Create session failed:', err);
  }
}

/** 切换会话 */
async function handleSelectSession(sessionId: string) {
  await sessionStore.switchSession(sessionId);
}

/** 关闭会话 */
async function handleCloseSession(sessionId: string) {
  try {
    await sessionStore.closeSession(sessionId);
    MessagePlugin.success('关闭会话成功');
  } catch (err) {
    console.error('Close session failed:', err);
  }
}
</script>

<style scoped lang="less">
.session-sidebar {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: #fafbfc;

  &__header {
    padding: 12px;
    border-bottom: 1px solid rgba(15, 23, 42, 0.06);
  }

  &__new-btn {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 6px;
    width: 100%;
    padding: 10px 16px;
    border: 1px solid rgba(15, 23, 42, 0.12);
    border-radius: 8px;
    background: #ffffff;
    cursor: pointer;
    font-size: 13px;
    font-weight: 500;
    color: #334155;
    transition: all 0.15s;

    &:hover {
      background: rgba(15, 23, 42, 0.03);
      border-color: rgba(15, 23, 42, 0.2);
    }
  }

  &__empty {
    padding: 40px 16px;
  }

  &__list {
    flex: 1;
    overflow-y: auto;
    padding: 8px;
  }
}

.session-item {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 10px 10px;
  border-radius: 8px;
  cursor: pointer;
  transition: background-color 0.15s;
  position: relative;

  &:hover {
    background: rgba(15, 23, 42, 0.04);

    .session-item__close {
      opacity: 1;
    }
  }

  &--active {
    background: rgba(0, 82, 217, 0.06);

    .session-item__title {
      color: #0052d9;
    }

    &::before {
      content: '';
      position: absolute;
      left: 0;
      top: 50%;
      transform: translateY(-50%);
      width: 3px;
      height: 60%;
      background: #0052d9;
      border-radius: 0 2px 2px 0;
    }
  }

  &__content {
    flex: 1;
    min-width: 0;
  }

  &__title {
    font-size: 13px;
    font-weight: 500;
    color: #0f172a;
    margin-bottom: 3px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__meta {
    display: flex;
    align-items: center;
    gap: 4px;
    font-size: 11px;
    color: #94a3b8;
  }

  &__close {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 24px;
    height: 24px;
    border: none;
    background: transparent;
    border-radius: 4px;
    cursor: pointer;
    color: #94a3b8;
    opacity: 0;
    transition: all 0.15s;
    flex-shrink: 0;

    &:hover {
      background: rgba(15, 23, 42, 0.08);
      color: #ef4444;
    }
  }
}
</style>
