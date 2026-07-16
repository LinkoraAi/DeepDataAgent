<template>
  <BaseCard
    :title="`图表 (${chartType || '未知'})`"
    icon="📈"
    :default-expanded="true"
  >
    <div class="chart-card__content">
      <v-chart
        v-if="chartConfig"
        :option="chartConfig"
        autoresize
        style="height: 400px"
      />
      <div v-else class="chart-card__empty">
        暂无图表数据
      </div>
    </div>
  </BaseCard>
</template>

<script setup lang="ts">
import VChart from 'vue-echarts';
import BaseCard from './BaseCard.vue';

interface Props {
  chartConfig: any;
  chartType: string | null;
}

defineProps<Props>();
</script>

<style scoped lang="less">
.chart-card {
  &__content {
    min-height: 400px;
  }

  &__empty {
    display: flex;
    align-items: center;
    justify-content: center;
    height: 400px;
    color: var(--td-text-color-secondary);
    font-size: 13px;
  }
}
</style>
