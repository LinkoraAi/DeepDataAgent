import { defineStore } from 'pinia';
import { ref, computed } from 'vue';
import type { ModelTemplate, ModelConfig } from '../types';
import * as modelApi from '@/shared/api/modelApi';

export const useModelStore = defineStore('model', () => {
  const templates = ref<ModelTemplate[]>([]);
  const configs = ref<ModelConfig[]>([]);
  const loading = ref(false);
  const selectedConfigId = ref<number | null>(null);

  const defaultModel = computed(() => {
    return configs.value.find(c => c.isDefault) || null;
  });

  const selectedModel = computed(() => {
    if (!selectedConfigId.value) return null;
    return configs.value.find(c => c.id === selectedConfigId.value) || null;
  });

  /**
   * Set selected model config
   */
  function setSelectedConfig(id: number) {
    selectedConfigId.value = id;
  }

  /**
   * Load templates
   */
  async function loadTemplates() {
    try {
      templates.value = await modelApi.listTemplates();
    } catch (err) {
      console.error('Failed to load templates:', err);
    }
  }

  /**
   * Load configs
   */
  async function loadConfigs() {
    loading.value = true;
    try {
      configs.value = await modelApi.listConfigs();
      // Auto-select default model if none selected
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
   * Load all (templates + configs)
   */
  async function loadAll() {
    await Promise.all([loadTemplates(), loadConfigs()]);
  }

  return {
    templates,
    configs,
    loading,
    defaultModel,
    selectedConfigId,
    selectedModel,
    setSelectedConfig,
    loadTemplates,
    loadConfigs,
    loadAll,
  };
});
