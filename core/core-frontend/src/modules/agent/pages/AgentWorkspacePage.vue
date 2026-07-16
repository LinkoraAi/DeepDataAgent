<template>
  <div class="agent-workspace">
    <Transition name="sidebar-slide">
      <aside v-show="!uiStore.sidebarCollapsed" class="agent-workspace__sidebar">
        <SessionSidebar />
      </aside>
    </Transition>
    <div class="agent-workspace__main">
      <ChatPanel />
    </div>
  </div>
</template>

<script setup lang="ts">
import { useUiStore } from '@/shared/stores/ui';
import SessionSidebar from '../components/SessionSidebar.vue';
import ChatPanel from '../components/ChatPanel.vue';

const uiStore = useUiStore();
</script>

<style scoped>
.agent-workspace {
  display: flex;
  height: calc(100vh - 48px);
  background: #ffffff;
}

.agent-workspace__sidebar {
  width: 260px;
  flex-shrink: 0;
  border-right: 1px solid rgba(15, 23, 42, 0.06);
  overflow: hidden;
}

.agent-workspace__main {
  flex: 1;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
</style>
