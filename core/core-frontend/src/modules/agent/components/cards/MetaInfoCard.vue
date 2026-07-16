<template>
  <BaseCard title="元信息" icon="ℹ️" :default-expanded="false">
    <div class="meta-info-card__content">
      <div class="meta-info-item">
        <span class="meta-info-item__label">数据源：</span>
        <span class="meta-info-item__value">{{ metaInfo.datasource }}</span>
      </div>
      <div class="meta-info-item">
        <span class="meta-info-item__label">模型：</span>
        <span class="meta-info-item__value">{{ metaInfo.model }}</span>
      </div>
      <div class="meta-info-item">
        <span class="meta-info-item__label">查询时间：</span>
        <span class="meta-info-item__value">{{ metaInfo.queryTime }}</span>
      </div>
      <div class="meta-info-item">
        <span class="meta-info-item__label">耗时：</span>
        <span class="meta-info-item__value">{{ metaInfo.duration }}</span>
      </div>
      <div class="meta-info-item">
        <span class="meta-info-item__label">结果行数：</span>
        <span class="meta-info-item__value">{{ metaInfo.rowCount }} 行</span>
      </div>
    </div>
  </BaseCard>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { useDatasourceStore } from '../../stores/datasource';
import { useModelStore } from '@/modules/model/stores/model';
import { formatDateTime } from '@/shared/utils/format';
import BaseCard from './BaseCard.vue';

interface Props {
  startTime: number | null;
  endTime: number | null;
  rowCount: number;
}

const props = defineProps<Props>();

const datasourceStore = useDatasourceStore();
const modelStore = useModelStore();

const metaInfo = computed(() => {
  const ds = datasourceStore.datasources.find(d => d.id === datasourceStore.currentDatasourceId);
  const model = modelStore.configs.find(m => m.id === modelStore.selectedConfigId);
  const duration = props.startTime && props.endTime
    ? ((props.endTime - props.startTime) / 1000).toFixed(1)
    : null;
  return {
    datasource: ds?.name || '未知',
    model: model?.name || model?.modelName || '未知',
    queryTime: props.startTime ? formatDateTime(new Date(props.startTime)) : '未知',
    duration: duration ? `${duration}s` : '未知',
    rowCount: props.rowCount,
  };
});
</script>

<style scoped lang="less">
.meta-info-card {
  &__content {
    display: flex;
    flex-wrap: wrap;
    gap: 16px;
  }
}

.meta-info-item {
  display: flex;
  gap: 4px;
  font-size: 13px;

  &__label {
    color: var(--td-text-color-secondary);
  }

  &__value {
    color: var(--td-text-color-primary);
    font-weight: 500;
  }
}
</style>
