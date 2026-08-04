import { defineStore } from 'pinia';
import { ref, computed } from 'vue';
import type { DatasourceConnection } from '@/modules/agent/types';
import * as datasourceApi from '@/shared/api/datasourceApi';

export const useDatasourceStore = defineStore('datasource', () => {
  // 全量数据源列表（设置页用）
  const datasources = ref<DatasourceConnection[]>([]);
  const currentDatasourceId = ref<number | null>(null);
  const loading = ref(false);
  const total = ref(0);

  // 仅 ENABLED 状态的数据源（Agent 页用）
  const enabledDatasources = computed(() =>
    datasources.value.filter((ds) => ds.status === 'ENABLED')
  );

  /**
   * 加载仅 ENABLED 状态的数据源（Agent 页面用）
   */
  async function loadEnabled() {
    try {
      const result = await datasourceApi.listDatasources(1, 100, 'ENABLED');
      // 合并到 datasources：用 ENABLED 查询结果更新已有条目或添加新条目
      const enabledIds = new Set(result.list.map((ds) => ds.id));
      const nonEnabled = datasources.value.filter((ds) => !enabledIds.has(ds.id));
      datasources.value = [...nonEnabled, ...result.list];
      if (!currentDatasourceId.value && result.list.length > 0) {
        currentDatasourceId.value = result.list[0].id;
      }
    } catch (err) {
      console.error('Failed to load enabled datasources:', err);
    }
  }

  /**
   * 加载全量数据源（设置页用），支持分页和过滤
   */
  async function loadAll(params?: {
    page?: number;
    size?: number;
    keyword?: string;
    type?: string;
    status?: string;
  }) {
    loading.value = true;
    try {
      const result = await datasourceApi.listDatasources(
        params?.page || 1,
        params?.size || 100,
        params?.status,
        params?.type,
        params?.keyword
      );
      datasources.value = result.list;
      total.value = result.total;
    } catch (err) {
      console.error('Failed to load datasources:', err);
      throw err;
    } finally {
      loading.value = false;
    }
  }

  /**
   * 兼容旧接口：Agent 页面调用 loadDatasources 等同于 loadEnabled
   */
  async function loadDatasources() {
    return loadEnabled();
  }

  function setCurrentDatasource(id: number) {
    currentDatasourceId.value = id;
  }

  /**
   * 刷新 enabled 列表（CRUD/enable/disable 操作后调用）
   */
  async function refreshEnabled() {
    try {
      const result = await datasourceApi.listDatasources(1, 100, 'ENABLED');
      const enabledIds = new Set(result.list.map((ds) => ds.id));
      const nonEnabled = datasources.value.filter((ds) => !enabledIds.has(ds.id));
      datasources.value = [...nonEnabled, ...result.list];
      // 如果当前选中的数据源不再是 ENABLED，重新选择
      if (currentDatasourceId.value && !enabledIds.has(currentDatasourceId.value)) {
        currentDatasourceId.value = result.list.length > 0 ? result.list[0].id : null;
      }
    } catch (err) {
      console.error('Failed to refresh enabled datasources:', err);
    }
  }

  // CRUD 操作

  async function createDatasource(params: Parameters<typeof datasourceApi.createDatasource>[0]) {
    await datasourceApi.createDatasource(params);
    await refreshEnabled();
  }

  async function updateDatasource(params: Parameters<typeof datasourceApi.updateDatasource>[0]) {
    await datasourceApi.updateDatasource(params);
    await refreshEnabled();
  }

  async function deleteDatasource(id: number) {
    await datasourceApi.deleteDatasource(id);
    await refreshEnabled();
  }

  async function testConnection(params: Parameters<typeof datasourceApi.testConnection>[0]) {
    return datasourceApi.testConnection(params);
  }

  async function enableDatasource(id: number) {
    await datasourceApi.enableDatasource(id);
    await refreshEnabled();
  }

  async function disableDatasource(id: number) {
    await datasourceApi.disableDatasource(id);
    await refreshEnabled();
  }

  async function syncDatasource(id: number) {
    await datasourceApi.syncDatasource(id);
  }

  return {
    datasources,
    enabledDatasources,
    currentDatasourceId,
    loading,
    total,
    loadEnabled,
    loadAll,
    loadDatasources,
    setCurrentDatasource,
    createDatasource,
    updateDatasource,
    deleteDatasource,
    testConnection,
    enableDatasource,
    disableDatasource,
    syncDatasource,
  };
});
