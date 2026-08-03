<template>
  <div v-if="hasChartContent" class="chart-section agent-section">
    <div class="chart-section__header">
      <span class="chart-section__icon">📈</span>
      <span class="chart-section__title">数据图表</span>
    </div>
    <div class="chart-section__content">
      <v-chart
        v-if="currentChartConfig && shouldDisplay"
        ref="chartRef"
        :option="currentChartConfig"
        autoresize
        style="height: 400px"
      />
      <div v-else class="chart-section__empty">
        暂无图表数据
      </div>
    </div>
    <div class="chart-toolbar">
      <div class="chart-toolbar__types">
        <button
          v-for="type in chartTypes"
          :key="type.value"
          :class="['chart-toolbar__btn', { active: currentChartType === type.value }]"
          :disabled="!isTypeCompatible(type.value)"
          @click="switchChartType(type.value)"
        >
          {{ type.label }}
        </button>
      </div>
      <div class="chart-toolbar__actions">
        <button class="chart-toolbar__btn" @click="downloadChart">下载 PNG</button>
        <button class="chart-toolbar__btn" @click="showFullscreen = true">全屏</button>
      </div>
    </div>

    <!-- 全屏对话框 -->
    <t-dialog
      v-model:visible="showFullscreen"
      header="图表全屏查看"
      width="90%"
      :footer="false"
    >
      <v-chart
        v-if="currentChartConfig"
        :option="currentChartConfig"
        autoresize
        style="height: 70vh"
      />
    </t-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue';
import VChart from 'vue-echarts';
import { use } from 'echarts/core';
import { CanvasRenderer } from 'echarts/renderers';
import { BarChart, LineChart, PieChart } from 'echarts/charts';
import {
  TitleComponent,
  TooltipComponent,
  GridComponent,
  DatasetComponent,
  TransformComponent,
  LegendComponent,
} from 'echarts/components';
import { MessagePlugin } from 'tdesign-vue-next';
import { hasChartValue } from '../../utils/validators';

// 注册 ECharts 组件
use([
  CanvasRenderer,
  BarChart,
  LineChart,
  PieChart,
  TitleComponent,
  TooltipComponent,
  GridComponent,
  DatasetComponent,
  TransformComponent,
  LegendComponent,
]);

/**
 * 图表区块组件 — 支持类型切换、PNG 下载、全屏查看
 */
interface Props {
  /** ECharts 配置对象 */
  chartConfig: any;
  /** 图表类型 */
  chartType: string | null;
}

const props = defineProps<Props>();

const chartRef = ref<any>(null);
const showFullscreen = ref(false);

/** 将后端大写图表类型转换为 ECharts 小写类型 */
function normalizeChartType(type: string | null): string {
  if (!type) return 'bar';
  const lower = type.toLowerCase();
  return lower === 'table' ? 'bar' : lower;
}

const currentChartType = ref(normalizeChartType(props.chartType));

/** 可选图表类型 */
const chartTypes = [
  { value: 'bar', label: '柱状图' },
  { value: 'line', label: '折线图' },
  { value: 'pie', label: '饼图' },
];

/** 当前图表配置（根据类型切换修改 series.type） */
const currentChartConfig = computed(() => {
  if (!props.chartConfig) return null;
  
  // 防御性检查：确保 chartConfig 是对象而非字符串
  let config: any;
  if (typeof props.chartConfig === 'string') {
    try {
      config = JSON.parse(props.chartConfig);
    } catch {
      console.warn('[ChartSection] chartConfig is invalid JSON string');
      return null;
    }
  } else {
    config = JSON.parse(JSON.stringify(props.chartConfig));
  }
  
  if (config.series && Array.isArray(config.series)) {
    config.series.forEach((s: any) => {
      s.type = currentChartType.value;
    });
  }
  return config;
});

/** 判断图表是否具有直观价值，避免无意义展示 */
const shouldDisplay = computed(() => {
  return hasChartValue(currentChartConfig.value, props.chartType);
});

/** 判断是否应该展示图表区块（始终展示，内部区分图表/空状态） */
const hasChartContent = computed(() => true);

/** 检查图表类型是否与数据兼容 */
function isTypeCompatible(type: string): boolean {
  if (!props.chartConfig) return false;
  // 饼图需要单个维度数据
  if (type === 'pie') {
    return props.chartConfig.series?.[0]?.data?.length <= 20;
  }
  return true;
}

/** 切换图表类型 */
function switchChartType(type: string) {
  if (!isTypeCompatible(type)) return;
  currentChartType.value = type;
}

/** 下载图表为 PNG */
function downloadChart() {
  if (!chartRef.value) {
    MessagePlugin.warning('图表未加载完成');
    return;
  }
  try {
    const chart = chartRef.value.chart || chartRef.value;
    const url = chart.getDataURL({
      pixelRatio: 2,
      backgroundColor: '#fff',
    });
    const link = document.createElement('a');
    link.href = url;
    link.download = `chart-${new Date().toISOString().slice(0, 10)}.png`;
    link.click();
    MessagePlugin.success('图表已下载');
  } catch (err) {
    console.error('Download chart failed:', err);
    MessagePlugin.error('下载失败');
  }
}

/** 监听原始 chartType 变化 */
watch(
  () => props.chartType,
  (newType) => {
    if (newType) {
      currentChartType.value = normalizeChartType(newType);
    }
  }
);
</script>

<style scoped lang="less">
.chart-section {
  &__header {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 8px;
  }

  &__icon {
    font-size: 16px;
  }

  &__title {
    font-weight: 600;
    font-size: 14px;
    color: var(--td-text-color-primary);
  }

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

.chart-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  padding: 8px 0;

  &__types,
  &__actions {
    display: flex;
    gap: 4px;
  }

  &__btn {
    padding: 4px 12px;
    border-radius: 6px;
    border: 1px solid var(--td-border-level-2-color);
    background: transparent;
    font-size: 12px;
    cursor: pointer;

    &.active {
      background: var(--td-brand-color);
      color: white;
      border-color: var(--td-brand-color);
    }

    &:disabled {
      opacity: 0.4;
      cursor: not-allowed;
    }
  }
}
</style>
