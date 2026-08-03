<template>
  <div class="datasource-selector">
    <t-popup v-model="popupVisible" placement="bottom-start" trigger="click" :overlay-style="{ width: '240px' }" :disabled="disabled">
      <div class="datasource-selector__trigger" :class="{ 'datasource-selector__trigger--empty': !currentDatasource, 'datasource-selector__trigger--disabled': disabled }">
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
          <div v-if="datasourceStore.enabledDatasources.length === 0" class="datasource-selector__popup-footer" @click="goToDatasourceConfig">
            <t-icon name="setting" size="14px" />
            <span>数据源管理</span>
          </div>
        </div>
      </template>
    </t-popup>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { useDatasourceStore } from '../stores/datasource';

const props = withDefaults(defineProps<{
  disabled?: boolean;
}>(), {
  disabled: false,
});

const datasourceStore = useDatasourceStore();
const router = useRouter();
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

/** 跳转到数据源管理页 */
function goToDatasourceConfig() {
  popupVisible.value = false;
  router.push('/settings?tab=datasource');
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
    padding: 6px 12px;
    border-radius: 8px;
    cursor: pointer;
    font-size: 13px;
    color: #334155;
    transition: background-color 0.15s;

    &:hover {
      background: rgba(15, 23, 42, 0.06);
    }

    &--empty {
      color: #94a3b8;
    }

    &--disabled {
      opacity: 0.5;
      cursor: not-allowed;
      pointer-events: none;

      &:hover {
        background: transparent;
      }
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
