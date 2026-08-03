import { defineStore } from 'pinia';
import { ref, computed } from 'vue';
import type { ModelProvider, ModelConfig } from '../types';
import * as modelApi from '@/shared/api/modelApi';

export const useModelStore = defineStore('model', () => {
  /** 服务商列表 */
  const providers = ref<ModelProvider[]>([]);
  /** 用户配置列表 */
  const configs = ref<ModelConfig[]>([]);
  /** 加载状态 */
  const loading = ref(false);
  /** 当前选中的配置 ID */
  const selectedConfigId = ref<number | null>(null);

  /** 默认模型 */
  const defaultModel = computed(() => {
    return configs.value.find(c => c.isDefault) || null;
  });

  /** 当前选中的模型 */
  const selectedModel = computed(() => {
    if (!selectedConfigId.value) return null;
    return configs.value.find(c => c.id === selectedConfigId.value) || null;
  });

  /**
   * 设置当前选中的配置
   */
  function setSelectedConfig(id: number) {
    selectedConfigId.value = id;
  }

  /**
   * 加载服务商列表
   */
  async function loadProviders() {
    try {
      providers.value = await modelApi.fetchProviders();
    } catch (err) {
      console.error('Failed to load providers:', err);
    }
  }

  /**
   * 加载配置列表
   */
  async function loadConfigs() {
    loading.value = true;
    try {
      configs.value = await modelApi.listConfigs();
      // 自动选择默认模型
      if (!selectedConfigId.value && configs.value.length > 0) {
        const defaultConfig = configs.value.find(c => c.isDefault);
        if (defaultConfig) {
          selectedConfigId.value = defaultConfig.id;
        } else {
          selectedConfigId.value = configs.value[0].id;
        }
      }
    } catch (err) {
      console.error('Failed to load configs:', err);
    } finally {
      loading.value = false;
    }
  }

  /**
   * 加载所有数据（服务商 + 配置）
   */
  async function loadAll() {
    await Promise.all([loadProviders(), loadConfigs()]);
  }

  return {
    providers,
    configs,
    loading,
    defaultModel,
    selectedConfigId,
    selectedModel,
    setSelectedConfig,
    loadProviders,
    loadConfigs,
    loadAll,
  };
});
