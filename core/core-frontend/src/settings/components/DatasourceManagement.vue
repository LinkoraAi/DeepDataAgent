<template>
  <div class="datasource-management">
    <div class="datasource-management__toolbar">
      <div class="datasource-management__filters">
        <t-input
          v-model="searchKeyword"
          placeholder="搜索数据源..."
          clearable
          @change="handleSearch"
        >
          <template #prefix-icon>
            <t-icon name="search" />
          </template>
        </t-input>
        <t-select v-model="filterType" placeholder="全部类型" @change="handleFilterChange">
          <t-option value="" label="全部类型" />
          <t-option value="MYSQL" label="MySQL" />
          <t-option value="CLICKHOUSE" label="ClickHouse" />
          <t-option value="API" label="API" />
        </t-select>
        <t-select v-model="filterStatus" placeholder="全部状态" @change="handleFilterChange">
          <t-option value="" label="全部状态" />
          <t-option value="ENABLED" label="已启用" />
          <t-option value="DISABLED" label="已禁用" />
        </t-select>
      </div>
      <t-button theme="primary" @click="handleAdd">
        <t-icon name="add" />
        <span>添加数据源</span>
      </t-button>
    </div>

    <t-loading :loading="datasourceStore.loading" text="加载中...">
      <div v-if="datasourceStore.datasources.length === 0" class="datasource-management__empty">
        <t-empty description="暂无数据源，请先添加数据源" />
      </div>

      <div v-else class="datasource-management__grid">
        <div
          v-for="datasource in datasourceStore.datasources"
          :key="datasource.id"
          class="datasource-card"
          :class="{ disabled: datasource.status === 'DISABLED' }"
        >
          <div class="datasource-card__header">
            <div class="datasource-card__title">
              <div class="datasource-card__icon" :class="getTypeClass(datasource.type)">
                {{ getTypeIcon(datasource.type) }}
              </div>
              <div class="datasource-card__info">
                <div class="datasource-card__name">{{ datasource.name }}</div>
                <div class="datasource-card__type">{{ datasource.type }}</div>
              </div>
            </div>
            <t-tag :theme="datasource.status === 'ENABLED' ? 'success' : 'default'" size="small">
              {{ datasource.status === 'ENABLED' ? '已启用' : '已禁用' }}
            </t-tag>
          </div>

          <div class="datasource-card__content">
            <div class="datasource-card__connection">
              <span v-if="datasource.host && datasource.port">
                {{ datasource.host }}:{{ datasource.port }}
              </span>
              <span v-if="datasource.database"> · {{ datasource.database }}</span>
            </div>
          </div>

          <div class="datasource-card__actions">
            <t-button
              theme="default"
              variant="outline"
              size="small"
              :loading="testingId === datasource.id"
              @click="handleTestConnection(datasource)"
            >
              测试连接
            </t-button>
            <t-button theme="default" variant="outline" size="small" @click="handleBrowse(datasource)">
              浏览数据
            </t-button>
            <t-button theme="default" variant="outline" size="small" @click="handleEdit(datasource)">
              编辑
            </t-button>
            <t-button
              v-if="datasource.status === 'ENABLED'"
              theme="default"
              variant="outline"
              size="small"
              @click="handleDisable(datasource)"
            >
              禁用
            </t-button>
            <t-button
              v-else
              theme="primary"
              variant="outline"
              size="small"
              @click="handleEnable(datasource)"
            >
              启用
            </t-button>
            <t-button theme="danger" variant="outline" size="small" @click="handleDelete(datasource)">
              删除
            </t-button>
          </div>
        </div>
      </div>
    </t-loading>

    <DatasourceFormDialog
      v-model:visible="dialogVisible"
      :edit-datasource="editingDatasource"
      @success="handleRefresh"
    />

    <DatasourceBrowseDrawer
      v-model:visible="browseVisible"
      :datasource-id="browsingDatasource?.id"
      :datasource-name="browsingDatasource?.name"
      :datasource-type="browsingDatasource?.type"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { MessagePlugin, DialogPlugin } from 'tdesign-vue-next';
import type { DatasourceConnection } from '@/modules/agent/types';
import { useDatasourceStore } from '@/modules/agent/stores/datasource';
import DatasourceFormDialog from './DatasourceFormDialog.vue';
import DatasourceBrowseDrawer from './DatasourceBrowseDrawer.vue';

const datasourceStore = useDatasourceStore();

const searchKeyword = ref('');
const filterType = ref('');
const filterStatus = ref('');
const dialogVisible = ref(false);
const editingDatasource = ref<DatasourceConnection | null>(null);
const testingId = ref<number | null>(null);
const browseVisible = ref(false);
const browsingDatasource = ref<DatasourceConnection | null>(null);

let searchTimer: ReturnType<typeof setTimeout> | null = null;

function handleSearch() {
  if (searchTimer) {
    clearTimeout(searchTimer);
  }
  searchTimer = setTimeout(() => {
    loadDatasources();
  }, 300);
}

function handleFilterChange() {
  loadDatasources();
}

async function loadDatasources() {
  await datasourceStore.loadAll({
    keyword: searchKeyword.value || undefined,
    type: filterType.value || undefined,
    status: filterStatus.value || undefined,
  });
}

function handleAdd() {
  editingDatasource.value = null;
  dialogVisible.value = true;
}

function handleEdit(datasource: DatasourceConnection) {
  editingDatasource.value = datasource;
  dialogVisible.value = true;
}

/** 打开数据浏览 Drawer */
function handleBrowse(datasource: DatasourceConnection) {
  browsingDatasource.value = datasource;
  browseVisible.value = true;
}

async function handleTestConnection(datasource: DatasourceConnection) {
  testingId.value = datasource.id;
  try {
    // 只传 id，让后端用已有配置测试，避免构造不完整的 jdbcConfig
    await datasourceStore.testConnection({
      id: datasource.id,
      name: datasource.name,
      type: datasource.type,
      subType: datasource.subType,
    });
    MessagePlugin.success('连接测试成功');
  } catch (err: any) {
    console.error('Test connection failed:', err);
    MessagePlugin.error(err.message || '连接测试失败');
  } finally {
    testingId.value = null;
  }
}

async function handleEnable(datasource: DatasourceConnection) {
  try {
    await datasourceStore.enableDatasource(datasource.id);
    await loadDatasources();
    MessagePlugin.success('启用成功');
  } catch (err: any) {
    console.error('Enable failed:', err);
    MessagePlugin.error(err.message || '启用失败');
  }
}

async function handleDisable(datasource: DatasourceConnection) {
  try {
    await datasourceStore.disableDatasource(datasource.id);
    await loadDatasources();
    MessagePlugin.success('禁用成功');
  } catch (err: any) {
    console.error('Disable failed:', err);
    MessagePlugin.error(err.message || '禁用失败');
  }
}

function handleDelete(datasource: DatasourceConnection) {
  const dialog = DialogPlugin.confirm({
    header: '确认删除',
    body: `确定要删除数据源 "${datasource.name}" 吗？`,
    confirmBtn: '删除',
    cancelBtn: '取消',
    onConfirm: async () => {
      try {
        await datasourceStore.deleteDatasource(datasource.id);
        await loadDatasources();
        MessagePlugin.success('删除成功');
        dialog.destroy();
      } catch (err: any) {
        console.error('Delete failed:', err);
        MessagePlugin.error(err.message || '删除失败');
      }
    },
    onClose: () => {
      dialog.destroy();
    },
  });
}

function handleRefresh() {
  loadDatasources();
}

function getTypeIcon(type: string): string {
  const icons: Record<string, string> = {
    MYSQL: 'M',
    CLICKHOUSE: 'C',
    API: 'A',
  };
  return icons[type] || '?';
}

function getTypeClass(type: string): string {
  const classes: Record<string, string> = {
    MYSQL: 'mysql',
    CLICKHOUSE: 'clickhouse',
    API: 'api',
  };
  return classes[type] || '';
}

onMounted(() => {
  loadDatasources();
});
</script>

<style scoped lang="less">
.datasource-management {
  &__toolbar {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 20px;
  }

  &__filters {
    display: flex;
    gap: 12px;
    align-items: center;

    .t-input {
      width: 240px;
    }

    .t-select {
      width: 140px;
    }
  }

  &__empty {
    padding: 80px 0;
  }

  &__grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(360px, 1fr));
    gap: 16px;
  }
}

.datasource-card {
  border: 1px solid rgba(15, 23, 42, 0.12);
  border-radius: 10px;
  padding: 20px;
  background: #ffffff;
  transition: all 0.15s;

  &:hover {
    box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
  }

  &.disabled {
    opacity: 0.7;
    background: #fafafa;
  }

  &__header {
    display: flex;
    justify-content: space-between;
    align-items: start;
    margin-bottom: 12px;
  }

  &__title {
    display: flex;
    align-items: center;
    gap: 10px;
  }

  &__icon {
    width: 36px;
    height: 36px;
    border-radius: 8px;
    display: flex;
    align-items: center;
    justify-content: center;
    font-weight: 600;
    font-size: 14px;

    &.mysql {
      background: rgba(0, 82, 217, 0.08);
      color: #0052d9;
    }

    &.clickhouse {
      background: rgba(255, 153, 0, 0.08);
      color: #ff9900;
    }

    &.api {
      background: rgba(168, 85, 247, 0.08);
      color: #a855f7;
    }
  }

  &__info {
    display: flex;
    flex-direction: column;
    gap: 2px;
  }

  &__name {
    font-weight: 600;
    font-size: 15px;
    color: #0f172a;
  }

  &__type {
    font-size: 12px;
    color: #94a3b8;
  }

  &__content {
    margin-bottom: 12px;
  }

  &__connection {
    font-size: 13px;
    color: #64748b;
    line-height: 1.5;
  }

  &__actions {
    display: flex;
    gap: 8px;
    border-top: 1px solid #f1f5f9;
    padding-top: 12px;
  }
}
</style>
