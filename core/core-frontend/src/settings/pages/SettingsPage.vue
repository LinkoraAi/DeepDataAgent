<template>
  <div class="settings-page">
    <div class="settings-page__header">
      <button class="settings-page__back-btn" @click="goBack" title="返回">
        <t-icon name="arrow-left" size="18px" />
      </button>
      <h1 class="settings-page__title">设置</h1>
    </div>

    <div class="settings-page__tabs">
      <button
        v-for="tab in tabs"
        :key="tab.value"
        class="settings-page__tab"
        :class="{ active: activeTab === tab.value }"
        @click="handleTabChange(tab.value)"
      >
        {{ tab.label }}
      </button>
    </div>

    <div class="settings-page__content">
      <DatasourceManagement v-if="activeTab === 'datasource'" />
      <ModelManagement v-if="activeTab === 'model'" />
      <MemoryPlaceholder v-if="activeTab === 'memory'" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, watch } from 'vue';
import { useRouter, useRoute } from 'vue-router';
import DatasourceManagement from '../components/DatasourceManagement.vue';
import ModelManagement from '../components/ModelManagement.vue';
import MemoryPlaceholder from '../components/MemoryPlaceholder.vue';

const router = useRouter();
const route = useRoute();

const tabs = [
  { value: 'datasource', label: '数据源' },
  { value: 'model', label: '模型' },
  { value: 'memory', label: '记忆' },
];

const activeTab = ref('datasource');

function handleTabChange(tab: string) {
  activeTab.value = tab;
  router.replace({ query: { ...route.query, tab } });
}

function goBack() {
  router.push('/agent');
}

onMounted(() => {
  const tab = route.query.tab as string;
  if (tab && tabs.some(t => t.value === tab)) {
    activeTab.value = tab;
  }
});

watch(
  () => route.query.tab,
  (newTab) => {
    if (newTab && typeof newTab === 'string' && tabs.some(t => t.value === newTab)) {
      activeTab.value = newTab;
    }
  }
);
</script>

<style scoped lang="less">
.settings-page {
  max-width: 1200px;
  margin: 0 auto;
  padding: 24px;
  height: calc(100vh - 48px);
  overflow-y: auto;

  &__header {
    display: flex;
    align-items: center;
    gap: 12px;
    margin-bottom: 24px;
  }

  &__back-btn {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 36px;
    height: 36px;
    border: 1px solid rgba(15, 23, 42, 0.12);
    border-radius: 8px;
    background: #ffffff;
    cursor: pointer;
    color: #475569;
    transition: all 0.15s;

    &:hover {
      background: rgba(15, 23, 42, 0.04);
    }
  }

  &__title {
    font-size: 20px;
    font-weight: 600;
    margin: 0;
    color: #0f172a;
  }

  &__tabs {
    display: flex;
    gap: 32px;
    border-bottom: 2px solid #e2e8f0;
    margin-bottom: 24px;
  }

  &__tab {
    padding: 12px 0;
    border: none;
    background: transparent;
    color: #64748b;
    font-size: 14px;
    font-weight: 500;
    cursor: pointer;
    border-bottom: 2px solid transparent;
    margin-bottom: -2px;
    transition: all 0.15s;

    &:hover {
      color: #0052d9;
    }

    &.active {
      color: #0052d9;
      border-bottom-color: #0052d9;
    }
  }

  &__content {
    min-height: 400px;
  }
}
</style>
