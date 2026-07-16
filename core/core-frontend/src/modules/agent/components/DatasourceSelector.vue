<template>
  <div class="datasource-selector">
    <t-popup v-model="popupVisible" placement="top-start" trigger="click" :overlay-style="{ width: '240px' }">
      <div class="datasource-selector__trigger" :class="{ 'datasource-selector__trigger--empty': !currentDatasource }">
        <t-icon name="server" size="14px" class="datasource-selector__icon" />
        <span class="datasource-selector__name">{{ displayName }}</span>
        <span v-if="currentDatasource" class="datasource-selector__type">{{ currentDatasource.type }}</span>
        <t-icon name="chevron-down" size="14px" class="datasource-selector__arrow" />
      </div>
      <template #content>
        <div class="datasource-selector__popup">
          <div class="datasource-selector__popup-header">选择数据源</div>
          <div class="datasource-selector__popup-list">
            <div
              v-for="ds in datasourceStore.enabledDatasources"
              :key="ds.id"
              :class="['datasource-selector__option', { 'datasource-selector__option--active': ds.id === datasourceStore.currentDatasourceId }]"
              @click="handleSelect(ds.id)"
            >
              <div class="datasource-selector__option-info">
                <span class="datasource-selector__option-name">{{ ds.name }}</span>
                <span class="datasource-selector__option-meta">{{ ds.type }} · {{ ds.host || '本地' }}</span>
              </div>
              <t-icon v-if="ds.id === datasourceStore.currentDatasourceId" name="check" size="16px" class="datasource-selector__option-check" />
            </div>
            <div v-if="datasourceStore.enabledDatasources.length === 0" class="datasource-selector__popup-empty">
              暂无可用数据源
            </div>
          </div>
        </div>
      </template>
    </t-popup>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useDatasourceStore } from '../stores/datasource';

const datasourceStore = useDatasourceStore();
const popupVisible = ref(false);

/** 当前选中的数据源对象 */
const currentDatasource = computed(() => {
  return datasourceStore.enabledDatasources.find(
    (ds) => ds.id === datasourceStore.currentDatasourceId
  );
});

/** 显示名称 */
const displayName = computed(() => {
  if (!currentDatasource.value) {
    return '选择数据源';
  }
  return currentDatasource.value.name;
});

/** 选择数据源 */
function handleSelect(id: number) {
  datasourceStore.setCurrentDatasource(id);
  popupVisible.value = false;
}

onMounted(() => {
  if (datasourceStore.enabledDatasources.length === 0) {
    datasourceStore.loadEnabled();
  }
});
</script>

<style scoped lang="less">
.datasource-selector {
  &__trigger {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    padding: 4px 10px;
    border-radius: 6px;
    cursor: pointer;
    font-size: 12px;
    background: rgba(15, 23, 42, 0.04);
    color: #475569;
    transition: all 0.15s;

    &:hover {
      background: rgba(15, 23, 42, 0.08);
    }

    &--empty {
      background: transparent;
      border: 1px dashed rgba(15, 23, 42, 0.2);
      color: #94a3b8;
    }
  }

  &__icon {
    color: #64748b;
  }

  &__name {
    font-weight: 500;
    max-width: 140px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__type {
    font-size: 10px;
    padding: 1px 5px;
    border-radius: 3px;
    background: rgba(0, 82, 217, 0.08);
    color: #0052d9;
    font-weight: 500;
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

  &__option-meta {
    font-size: 11px;
    color: #94a3b8;
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
}
</style>
