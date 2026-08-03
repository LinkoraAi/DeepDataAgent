import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount } from '@vue/test-utils';
import ChartSection from '../components/cards/ChartSection.vue';

const { mockGetDataURL } = vi.hoisted(() => ({
  mockGetDataURL: vi.fn(() => 'data:image/png;base64,mock-chart'),
}));

vi.mock('vue-echarts', () => ({
  default: {
    name: 'VChart',
    template: '<div class="v-chart-stub" />',
    methods: {
      getDataURL: mockGetDataURL,
    },
  },
}));

vi.mock('tdesign-vue-next', () => ({
  MessagePlugin: {
    success: vi.fn(),
    error: vi.fn(),
    warning: vi.fn(),
    info: vi.fn(),
  },
  Dialog: { template: '<div class="t-dialog-stub"><slot /></div>' },
}));

const mockChartConfig = {
  series: [
    {
      type: 'bar',
      data: [
        { value: 10, name: 'A' },
        { value: 25, name: 'B' },
        { value: 15, name: 'C' },
      ],
    },
  ],
  xAxis: { data: ['A', 'B', 'C'] },
  yAxis: {},
};

const mockLinearChartConfig = {
  series: [
    {
      type: 'bar',
      data: [
        { value: 10, name: 'A' },
        { value: 20, name: 'B' },
        { value: 30, name: 'C' },
      ],
    },
  ],
  xAxis: { data: ['A', 'B', 'C'] },
  yAxis: {},
};

const globalStubs = {
  stubs: {
    't-dialog': { template: '<div class="t-dialog-stub"><slot /></div>' },
  },
};

describe('ChartSection', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('should_displayChartTitle_when_mounted_given_validConfig', () => {
    // given
    const props = { chartConfig: mockChartConfig, chartType: 'bar' };

    // when
    const wrapper = mount(ChartSection, { props, global: globalStubs });

    // then
    expect(wrapper.text()).toContain('数据图表');
  });

  it('should_displayEmptyMessage_when_mounted_given_nullConfig', () => {
    // given
    const props = { chartConfig: null, chartType: null };

    // when
    const wrapper = mount(ChartSection, { props, global: globalStubs });

    // then
    expect(wrapper.text()).toContain('暂无图表数据');
  });

  it('should_renderTypeButtons_when_mounted_given_validConfig', () => {
    // given
    const props = { chartConfig: mockChartConfig, chartType: 'bar' };

    // when
    const wrapper = mount(ChartSection, { props, global: globalStubs });

    // then
    expect(wrapper.text()).toContain('柱状图');
    expect(wrapper.text()).toContain('折线图');
    expect(wrapper.text()).toContain('饼图');
  });

  it('should_renderDownloadAndFullscreenButtons_when_mounted_given_validConfig', () => {
    // given
    const props = { chartConfig: mockChartConfig, chartType: 'bar' };

    // when
    const wrapper = mount(ChartSection, { props, global: globalStubs });

    // then
    expect(wrapper.text()).toContain('下载 PNG');
    expect(wrapper.text()).toContain('全屏');
  });

  it('should_switchActiveType_when_clickingLineChartButton_given_barChartConfig', async () => {
    // given
    const props = { chartConfig: mockChartConfig, chartType: 'bar' };
    const wrapper = mount(ChartSection, { props, global: globalStubs });
    const barBtn = wrapper.findAll('button').find((b) => b.text() === '柱状图')!;
    const lineBtn = wrapper.findAll('button').find((b) => b.text() === '折线图')!;

    // 初始状态：柱状图按钮高亮
    expect(barBtn.classes()).toContain('active');
    expect(lineBtn.classes()).not.toContain('active');

    // when
    await lineBtn.trigger('click');

    // then
    expect(barBtn.classes()).not.toContain('active');
    expect(lineBtn.classes()).toContain('active');
  });

  it('should_callGetDataURL_when_clickingDownloadButton_given_chartLoaded', async () => {
    // given — getDataURL 返回 data URL 字符串，不经过 URL.createObjectURL
    const clickSpy = vi.fn();
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(clickSpy);

    const props = { chartConfig: mockChartConfig, chartType: 'bar' };
    const wrapper = mount(ChartSection, { props, global: globalStubs });
    const downloadBtn = wrapper.findAll('button').find((b) => b.text() === '下载 PNG')!;

    // when
    await downloadBtn.trigger('click');

    // then — getDataURL 被调用，参数包含 pixelRatio: 2
    expect(mockGetDataURL).toHaveBeenCalledWith({
      pixelRatio: 2,
      backgroundColor: '#fff',
    });
    // anchor.click 被调用，说明下载链路完成
    expect(clickSpy).toHaveBeenCalled();

    // 清理
    vi.restoreAllMocks();
  });

  it('should_displayEmptyState_when_mounted_given_linearGrowthData', () => {
    const props = { chartConfig: mockLinearChartConfig, chartType: 'bar' };
    const wrapper = mount(ChartSection, { props, global: globalStubs });

    expect(wrapper.text()).toContain('数据图表');
    expect(wrapper.text()).toContain('暂无图表数据');
  });
});
