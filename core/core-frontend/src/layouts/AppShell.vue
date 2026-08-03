<template>
  <div class="app-shell">
    <header class="app-shell__header">
      <div class="app-shell__header-left">
        <button class="app-shell__menu-btn" @click="uiStore.toggleSidebar" title="切换侧栏">
          <t-icon name="view-list" size="20px" />
        </button>
        <h1 class="app-shell__brand">DeepDataAgent</h1>
      </div>
      <div class="app-shell__header-right">
        <DatasourceSelector v-if="isAgentPage" :disabled="analysisStore.state.isAnalyzing" />
        <ModelSelector v-if="isAgentPage" />
        <button class="app-shell__icon-btn" @click="goToSettings" title="设置">
          <t-icon name="setting" size="20px" />
        </button>
      </div>
    </header>
    <main class="app-shell__content">
      <RouterView />
    </main>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { RouterView, useRoute, useRouter } from 'vue-router';
import { useUiStore } from '@/shared/stores/ui';
import ModelSelector from '@/modules/agent/components/ModelSelector.vue';
import DatasourceSelector from '@/modules/agent/components/DatasourceSelector.vue';
import { useAnalysisStore } from '@/modules/agent/stores/analysis';

const uiStore = useUiStore();
const analysisStore = useAnalysisStore();
const route = useRoute();
const router = useRouter();

/** 当前是否在 Agent 工作台页面 */
const isAgentPage = computed(() => route.name === 'agent');

/** 跳转到设置页 */
function goToSettings() {
  router.push('/settings');
}
</script>

<style scoped>
.app-shell {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
  background: #ffffff;
}

.app-shell__header-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.app-shell__content {
  flex: 1;
  overflow: hidden;
}
</style>
