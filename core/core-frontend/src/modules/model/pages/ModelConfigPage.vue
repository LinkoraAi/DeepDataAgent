<template>
  <div class="model-config-page">
    <div class="model-config-page__header">
      <div class="model-config-page__header-left">
        <button class="model-config-page__back-btn" @click="goBack" title="返回">
          <t-icon name="arrow-left" size="18px" />
        </button>
        <h2 class="model-config-page__title">模型配置</h2>
      </div>
      <button class="model-config-page__add-btn" @click="handleAdd">
        <t-icon name="add" size="16px" />
        <span>添加模型</span>
      </button>
    </div>

    <t-loading :loading="modelStore.loading" text="加载中...">
      <div v-if="modelStore.configs.length === 0" class="model-config-page__empty">
        <t-empty description="暂无模型配置，请先添加模型" />
      </div>

      <div v-else class="model-config-page__list">
        <ModelCard
          v-for="config in sortedConfigs"
          :key="config.id"
          :config="config"
          @edit="handleEdit"
          @refresh="loadData"
        />
      </div>
    </t-loading>

    <ModelFormDialog
      v-model:visible="dialogVisible"
      :edit-config="editingConfig"
      @success="loadData"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { useModelStore } from '../stores/model';
import type { ModelConfig } from '../types';
import ModelCard from '../components/ModelCard.vue';
import ModelFormDialog from '../components/ModelFormDialog.vue';

const modelStore = useModelStore();
const router = useRouter();

const dialogVisible = ref(false);
const editingConfig = ref<ModelConfig | null>(null);

/** 排序后的配置列表（默认模型优先） */
const sortedConfigs = computed(() => {
  return [...modelStore.configs].sort((a, b) => {
    if (a.isDefault && !b.isDefault) return -1;
    if (!a.isDefault && b.isDefault) return 1;
    return 0;
  });
});

/** 返回上一页 */
function goBack() {
  router.push('/agent');
}

/** 添加模型 */
function handleAdd() {
  editingConfig.value = null;
  dialogVisible.value = true;
}

/** 编辑模型 */
function handleEdit(config: ModelConfig) {
  editingConfig.value = config;
  dialogVisible.value = true;
}

/** 加载数据 */
async function loadData() {
  await modelStore.loadAll();
}

onMounted(() => {
  loadData();
});
</script>

<style scoped lang="less">
.model-config-page {
  padding: 24px;
  max-width: 1200px;
  margin: 0 auto;
  height: calc(100vh - 48px);
  overflow-y: auto;

  &__header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 24px;
  }

  &__header-left {
    display: flex;
    align-items: center;
    gap: 12px;
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

  &__add-btn {
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 8px 16px;
    border: none;
    border-radius: 8px;
    background: #0052d9;
    color: #ffffff;
    cursor: pointer;
    font-size: 13px;
    font-weight: 500;
    transition: background-color 0.15s;

    &:hover {
      background: #0040b0;
    }
  }

  &__empty {
    padding: 80px 0;
  }

  &__list {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(380px, 1fr));
    gap: 16px;
  }
}
</style>
