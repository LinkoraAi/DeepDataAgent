<template>
  <div class="session-sidebar">
    <div class="session-sidebar__header">
      <button class="session-sidebar__new-btn" @click="handleNewSession">
        <t-icon name="add" size="16px" />
        <span>新建对话</span>
      </button>
    </div>

    <!-- 加载状态：使用 storeToRefs + computed 确保 v-if 响应式 -->
    <div v-if="isLoading" class="session-sidebar__loading">
      <t-loading size="small" text="加载中..." />
    </div>

    <div v-else-if="sessionsCount === 0" class="session-sidebar__empty">
      <t-empty description="暂无会话" size="small" />
    </div>

    <div v-else class="session-sidebar__list" @scroll="handleScroll">
        <div
          v-for="session in sessions"
          :key="session.id"
          :class="['session-item', { 'session-item--active': session.id === sessionStore.currentSessionId }]"
          @click="handleSelectSession(session.id)"
        >
          <div class="session-item__content">
            <div class="session-item__title">{{ session.title }}</div>
            <div class="session-item__meta">
              <span>{{ formatRelativeTime(session.lastMessageAt || session.createdAt) }}</span>
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

        <!-- 滚动加载更多指示器 -->
        <div v-if="isLoadingMore" class="session-sidebar__loading-more">
          <t-loading size="small" />
        </div>
        <div v-else-if="!hasMore && sessions.length > 0" class="session-sidebar__no-more">
          已加载全部会话
        </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { storeToRefs } from 'pinia';
import { DialogPlugin, MessagePlugin } from 'tdesign-vue-next';
import { useSessionStore } from '../stores/session';
import { useDatasourceStore } from '../stores/datasource';
import { useModelStore } from '@/modules/model/stores/model';
import { useAnalysisStore } from '../stores/analysis';
import { formatRelativeTime } from '@/shared/utils/format';

const sessionStore = useSessionStore();
const datasourceStore = useDatasourceStore();
const modelStore = useModelStore();
const analysisStore = useAnalysisStore();

/**
 * 使用 storeToRefs 提取响应式状态
 * <p>Pinia setup store 的状态通过 storeToRefs 提取后在模板中保持响应式。</p>
 */
const { loading, sessions, hasMore, isLoadingMore } = storeToRefs(sessionStore);
const isLoading = computed(() => loading.value);
const sessionsCount = computed(() => sessions.value.length);

/** 滚动加载更多会话 */
function handleScroll(event: Event) {
  const container = event.currentTarget as HTMLElement;
  const scrollTop = container.scrollTop;
  const clientHeight = container.clientHeight;
  const scrollHeight = container.scrollHeight;
  const remaining = scrollHeight - scrollTop - clientHeight;

  // 剩余可滚动距离小于 50px 且还有更多数据且未在加载中时触发
  if (hasMore.value && !isLoadingMore.value && remaining < 50) {
    sessionStore.loadMore();
  }
}

/** 新建会话（懒创建模式：仅清空前端状态，首次发消息时才创建后端会话） */
function handleNewSession() {
  if (!datasourceStore.currentDatasourceId) {
    MessagePlugin.warning('请先选择数据源');
    return;
  }
  if (!modelStore.selectedConfigId) {
    MessagePlugin.warning('请先选择模型');
    return;
  }
  // 懒创建：仅清空当前状态，不调用后端 API
  // 会话将在用户首次发送消息时创建（useDataAnalysis.submitQuestion 已有此逻辑）
  analysisStore.reset();
  sessionStore.clearCurrentSession();
}

/** 切换会话 */
async function handleSelectSession(sessionId: string) {
  console.debug('[SessionSidebar] handleSelectSession:', sessionId, 'current:', sessionStore.currentSessionId);
  await sessionStore.switchSession(sessionId);
}

/** 关闭会话（带二次确认弹窗） */
function handleCloseSession(sessionId: string) {
  const confirmDialog = DialogPlugin.confirm({
    header: '删除会话',
    body: '确定要删除这个会话吗？删除后将无法恢复。',
    confirmBtn: { content: '删除', theme: 'danger' },
    cancelBtn: '取消',
    onConfirm: async () => {
      confirmDialog.destroy();
      try {
        await sessionStore.closeSession(sessionId);
        MessagePlugin.success('删除会话成功');
        // 关闭会话后重置分页并重新加载列表
        await sessionStore.loadSessions();
      } catch (err) {
        MessagePlugin.error('删除会话失败');
        console.error('Close session failed:', err);
      }
    },
  });
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

  &__loading {
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 40px 16px;
  }

  &__list {
    flex: 1;
    overflow-y: auto;
    padding: 8px;
  }

  &__loading-more {
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 12px 16px;
  }

  &__no-more {
    text-align: center;
    padding: 12px 16px;
    font-size: 12px;
    color: #94a3b8;
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
    opacity: 0.3;
    transition: all 0.15s;
    flex-shrink: 0;

    &:hover {
      background: rgba(15, 23, 42, 0.08);
      color: #ef4444;
      opacity: 1;
    }
  }
}
</style>
