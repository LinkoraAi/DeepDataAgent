<template>
  <t-card class="model-card" :bordered="true">
    <template #header>
      <div class="model-card__header">
        <div class="model-card__title">
          <t-tag v-if="config.isDefault" theme="warning" size="small">默认</t-tag>
          <span>{{ config.name }}</span>
        </div>
      </div>
    </template>

    <div class="model-card__content">
      <div class="model-card__info">
        <div class="model-card__info-item">
          <span class="label">提供商:</span>
          <span class="value">{{ config.provider }}</span>
        </div>
        <div class="model-card__info-item">
          <span class="label">模型:</span>
          <span class="value">{{ config.modelName }}</span>
        </div>
        <div class="model-card__info-item">
          <span class="label">API Key:</span>
          <span class="value">{{ config.apiKeyMasked }}</span>
        </div>
        <div class="model-card__info-item">
          <span class="label">温度:</span>
          <span class="value">{{ config.temperature }}</span>
        </div>
      </div>

      <div class="model-card__actions">
        <t-button 
          theme="default" 
          variant="outline" 
          size="small"
          :loading="testing"
          @click="handleTest"
        >
          测试连接
        </t-button>
        <t-button 
          theme="default" 
          variant="outline" 
          size="small"
          @click="handleEdit"
        >
          编辑
        </t-button>
        <t-button 
          v-if="!config.isDefault"
          theme="primary" 
          variant="outline" 
          size="small"
          @click="handleSetDefault"
        >
          设为默认
        </t-button>
        <t-button 
          theme="danger" 
          variant="outline" 
          size="small"
          @click="handleDelete"
        >
          删除
        </t-button>
      </div>
    </div>

    <div v-if="testResult" class="model-card__test-result">
      <t-alert 
        :theme="testResult.available ? 'success' : 'error'" 
        :message="testResult.message"
      >
        <template #operation>
          <span v-if="testResult.available">响应时间: {{ testResult.responseTime }}ms</span>
        </template>
      </t-alert>
    </div>
  </t-card>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import { MessagePlugin, DialogPlugin } from 'tdesign-vue-next';
import type { ModelConfig, TestConnectionResult } from '../types';
import * as modelApi from '@/shared/api/modelApi';

const props = defineProps<{
  config: ModelConfig;
}>();

const emit = defineEmits<{
  edit: [config: ModelConfig];
  refresh: [];
}>();

const testing = ref(false);
const testResult = ref<TestConnectionResult | null>(null);

/**
 * Test connection
 */
async function handleTest() {
  testing.value = true;
  testResult.value = null;
  try {
    const result = await modelApi.testConnection(props.config.id);
    testResult.value = result;
    if (result.available) {
      MessagePlugin.success('连接测试成功');
    } else {
      MessagePlugin.error(result.message || '连接测试失败');
    }
  } catch (err: any) {
    console.error('Test connection failed:', err);
    testResult.value = {
      available: false,
      message: err.message || '连接测试失败',
      responseTime: 0,
    };
    MessagePlugin.error(err.message || '连接测试失败');
  } finally {
    testing.value = false;
  }
}

/**
 * Edit config
 */
function handleEdit() {
  emit('edit', props.config);
}

/**
 * Set as default
 */
async function handleSetDefault() {
  try {
    await modelApi.setDefaultModel(props.config.id);
    MessagePlugin.success('设置默认模型成功');
    emit('refresh');
  } catch (err) {
    console.error('Set default failed:', err);
  }
}

/**
 * Delete config
 */
function handleDelete() {
  const dialog = DialogPlugin.confirm({
    header: '确认删除',
    body: `确定要删除模型配置 "${props.config.name}" 吗？`,
    confirmBtn: '删除',
    cancelBtn: '取消',
    onConfirm: async () => {
      try {
        await modelApi.deleteConfig(props.config.id);
        MessagePlugin.success('删除成功');
        dialog.destroy();
        emit('refresh');
      } catch (err) {
        console.error('Delete failed:', err);
      }
    },
    onClose: () => {
      dialog.destroy();
    },
  });
}
</script>

<style scoped lang="less">
.model-card {
  &__header {
    display: flex;
    align-items: center;
    justify-content: space-between;
  }

  &__title {
    display: flex;
    align-items: center;
    gap: 8px;
    font-weight: 600;
    font-size: 16px;
  }

  &__content {
    display: flex;
    flex-direction: column;
    gap: 16px;
  }

  &__info {
    display: grid;
    grid-template-columns: repeat(2, 1fr);
    gap: 8px;

    &-item {
      display: flex;
      gap: 8px;

      .label {
        color: var(--td-text-color-secondary);
        min-width: 60px;
      }

      .value {
        color: var(--td-text-color-primary);
      }
    }
  }

  &__actions {
    display: flex;
    gap: 8px;
    flex-wrap: wrap;
  }

  &__test-result {
    margin-top: 12px;
  }
}
</style>
