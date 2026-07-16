<template>
  <div class="model-management">
    <div class="model-management__toolbar">
      <t-button theme="primary" @click="handleAdd">
        <t-icon name="add" />
        <span>添加模型</span>
      </t-button>
    </div>

    <t-loading :loading="modelStore.loading" text="加载中...">
      <div v-if="modelStore.configs.length === 0" class="model-management__empty">
        <t-empty description="暂无模型配置，请先添加模型" />
      </div>

      <div v-else class="model-management__grid">
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
      :templates="modelStore.templates"
      :edit-config="editingConfig"
      @success="loadData"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useModelStore } from '@/modules/model/stores/model';
import type { ModelConfig } from '@/modules/model/types';
import ModelCard from '@/modules/model/components/ModelCard.vue';
import ModelFormDialog from '@/modules/model/components/ModelFormDialog.vue';

const modelStore = useModelStore();

const dialogVisible = ref(false);
const editingConfig = ref<ModelConfig | null>(null);

const sortedConfigs = computed(() => {
  return [...modelStore.configs].sort((a, b) => {
    if (a.isDefault && !b.isDefault) return -1;
    if (!a.isDefault && b.isDefault) return 1;
    return 0;
  });
});

function handleAdd() {
  editingConfig.value = null;
  dialogVisible.value = true;
}

function handleEdit(config: ModelConfig) {
  editingConfig.value = config;
  dialogVisible.value = true;
}

async function loadData() {
  await modelStore.loadAll();
}

onMounted(() => {
  loadData();
});
</script>

<style scoped lang="less">
.model-management {
  &__toolbar {
    display: flex;
    justify-content: flex-end;
    margin-bottom: 20px;
  }

  &__empty {
    padding: 80px 0;
  }

  &__grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(380px, 1fr));
    gap: 16px;
  }
}
</style>
