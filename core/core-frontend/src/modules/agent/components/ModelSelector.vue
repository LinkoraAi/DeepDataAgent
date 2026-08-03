<template>
  <div class="model-selector">
    <t-popup v-model="popupVisible" placement="bottom" trigger="click" :overlay-style="{ width: '240px' }">
      <div class="model-selector__trigger" :class="{ 'model-selector__trigger--empty': !currentModel }">
        <t-icon name="chat" size="14px" class="model-selector__icon" />
        <span class="model-selector__name">{{ displayName }}</span>
        <t-icon name="chevron-down" size="14px" class="model-selector__arrow" />
      </div>
      <template #content>
        <div class="model-selector__popup">
          <div class="model-selector__popup-header">选择模型</div>
          <div class="model-selector__popup-list">
            <div
              v-for="model in modelStore.configs"
              :key="model.id"
              :class="['model-selector__option', { 'model-selector__option--active': model.id === modelStore.selectedConfigId }]"
              @click="handleSelect(model.id)"
            >
              <div class="model-selector__option-info">
                <span class="model-selector__option-name">{{ model.modelKey }}</span>
                <span class="model-selector__option-provider">{{ model.providerKey }}</span>
              </div>
              <t-icon v-if="model.isDefault" name="star-filled" size="14px" class="model-selector__option-default" />
              <t-icon v-if="model.id === modelStore.selectedConfigId" name="check" size="16px" class="model-selector__option-check" />
            </div>
            <div v-if="modelStore.configs.length === 0" class="model-selector__popup-empty">
              暂无可用模型，请前往配置
            </div>
          </div>
          <div class="model-selector__popup-footer" @click="goToConfig">
            <t-icon name="setting" size="14px" />
            <span>模型配置</span>
          </div>
        </div>
      </template>
    </t-popup>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { useModelStore } from '@/modules/model/stores/model';

const modelStore = useModelStore();
const router = useRouter();
const popupVisible = ref(false);

/** 当前选中的模型对象 */
const currentModel = computed(() => modelStore.selectedModel);

/** 显示名称：未选择时显示占位文本 */
const displayName = computed(() => {
  if (!currentModel.value) {
    return '选择模型';
  }
  return `${currentModel.value.providerKey}/${currentModel.value.modelKey}`;
});

/** 选择模型 */
function handleSelect(id: number) {
  modelStore.setSelectedConfig(id);
  popupVisible.value = false;
}

/** 跳转到模型配置页 */
function goToConfig() {
  popupVisible.value = false;
  router.push('/models');
}

onMounted(() => {
  if (modelStore.configs.length === 0) {
    modelStore.loadConfigs();
  }
});
</script>

<style scoped lang="less">
.model-selector {
  &__trigger {
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 6px 12px;
    border-radius: 8px;
    cursor: pointer;
    font-size: 13px;
    color: #334155;
    transition: background-color 0.15s;

    &:hover {
      background: rgba(15, 23, 42, 0.05);
    }

    &--empty {
      color: #94a3b8;
    }
  }

  &__icon {
    color: #64748b;
  }

  &__name {
    font-weight: 500;
    max-width: 120px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__arrow {
    color: #94a3b8;
  }

  &__popup {
    width: 240px;
    padding: 4px;
  }

  &__popup-header {
    padding: 8px 12px;
    font-size: 12px;
    font-weight: 600;
    color: #94a3b8;
    text-transform: uppercase;
    letter-spacing: 0.05em;
  }

  &__popup-list {
    max-height: 280px;
    overflow-y: auto;
  }

  &__option {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 10px 12px;
    border-radius: 6px;
    cursor: pointer;
    transition: background-color 0.15s;

    &:hover {
      background: rgba(15, 23, 42, 0.04);
    }

    &--active {
      background: rgba(0, 82, 217, 0.06);
    }
  }

  &__option-info {
    flex: 1;
    display: flex;
    flex-direction: column;
    gap: 2px;
  }

  &__option-name {
    font-size: 13px;
    font-weight: 500;
    color: #0f172a;
  }

  &__option-provider {
    font-size: 11px;
    color: #94a3b8;
  }

  &__option-default {
    color: #f59e0b;
  }

  &__option-check {
    color: #0052d9;
  }

  &__popup-empty {
    padding: 24px 12px;
    text-align: center;
    font-size: 13px;
    color: #94a3b8;
  }

  &__popup-footer {
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 10px 12px;
    border-top: 1px solid rgba(15, 23, 42, 0.06);
    font-size: 13px;
    color: #64748b;
    cursor: pointer;
    margin-top: 4px;

    &:hover {
      color: #0052d9;
    }
  }
}
</style>
