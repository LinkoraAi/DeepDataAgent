import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { mount } from '@vue/test-utils';
import { setActivePinia, createPinia } from 'pinia';
import AgentMessage from '../components/AgentMessage.vue';
import ContentStream from '../components/cards/ContentStream.vue';
import type { ChatMessage, AnalysisSnapshot, ContentItem } from '../types';

vi.mock('../composables/useAnalysisProgress', () => ({
  useAnalysisProgress: () => ({
    phaseLabel: '正在思考...',
    elapsedSeconds: 5,
    currentPhase: 'thinking',
  }),
}));

vi.mock('@/shared/utils/copy', () => ({
  copyToClipboard: vi.fn(() => Promise.resolve(true)),
}));

vi.mock('@/shared/utils/markdown', () => ({
  renderMarkdown: vi.fn((text: string) => `<p>${text}</p>`),
}));

vi.mock('vue-echarts', () => ({
  default: { name: 'VChart', template: '<div class="v-chart-stub" />' },
}));

vi.mock('highlight.js/lib/core', () => ({
  default: {
    highlight: (code: string) => ({ value: code }),
    registerLanguage: vi.fn(),
  },
}));

vi.mock('highlight.js/lib/languages/sql', () => ({ default: {} }));

vi.mock('dompurify', () => ({
  default: { sanitize: (html: string) => html },
}));

vi.mock('tdesign-icons-vue-next', () => ({
  CopyIcon: { template: '<span />' },
  RefreshIcon: { template: '<span />' },
  StarIcon: { template: '<span />' },
  ThumbUpIcon: { template: '<span />' },
  ThumbDownIcon: { template: '<span />' },
}));

const tdesignStubs = {
  't-avatar': { template: '<div class="t-avatar-stub" />' },
  't-button': {
    template: '<button @click="$emit(\'click\')"><slot name="icon" /><slot /></button>',
    emits: ['click'],
  },
  't-icon': { template: '<i />' },
  't-loading': { template: '<div />' },
  't-tag': { template: '<span><slot /></span>' },
  't-dialog': { template: '<div class="t-dialog-stub"><slot /></div>' },
};

/** 构造统一内容流项 */
function createContentItem(overrides?: Partial<ContentItem>): ContentItem {
  return {
    id: 'thinking-1',
    seq: 0,
    type: 'thinking',
    status: 'completed',
    content: '思考内容',
    startTime: Date.now() - 1000,
    endTime: Date.now(),
    ...overrides,
  };
}

function createMockMessage(overrides?: Partial<AnalysisSnapshot>): ChatMessage {
  return {
    id: 'test-msg',
    role: 'agent',
    content: '',
    timestamp: new Date(2026, 6, 23, 14, 30).getTime(),
    analysisState: {
      contentItems: [],
      currentSQL: null,
      queryData: [],
      chartConfig: null,
      chartType: null,
      analysisReport: null,
      searchResults: null,
      isEmptyResult: false,
      errorMessage: null,
      analysisStartTime: null,
      analysisEndTime: null,
      suggestions: [],
      ...overrides,
    },
  };
}

describe('AgentMessage - 统一内容流渲染', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
  });

  it('应渲染消息头部', () => {
    const message = createMockMessage();
    const wrapper = mount(AgentMessage, {
      props: { message, isAnalyzing: false },
      global: { stubs: tdesignStubs },
    });

    expect(wrapper.text()).toContain('DeepDataAgent');
  });

  it('有错误信息应显示 ErrorDisplay', () => {
    const message = createMockMessage({
      errorMessage: '数据库连接失败',
    });
    const wrapper = mount(AgentMessage, {
      props: { message, isAnalyzing: false },
      global: { stubs: tdesignStubs },
    });

    expect(wrapper.text()).toContain('数据库连接失败');
  });

  it('有 contentItems 数据应显示统一内容流 ContentStream', () => {
    const message = createMockMessage({
      contentItems: [createContentItem()],
    });
    const wrapper = mount(AgentMessage, {
      props: { message, isAnalyzing: false },
      global: { stubs: tdesignStubs },
    });

    expect(wrapper.findComponent(ContentStream).exists()).toBe(true);
  });

  it('无 contentItems 数据不应显示 ContentStream', () => {
    const message = createMockMessage({ contentItems: [] });
    const wrapper = mount(AgentMessage, {
      props: { message, isAnalyzing: false },
      global: { stubs: tdesignStubs },
    });

    expect(wrapper.findComponent(ContentStream).exists()).toBe(false);
  });

  it('分析中 contentItems 有内容应显示 ContentStream（流式实时渲染）', () => {
    const message = createMockMessage({
      contentItems: [createContentItem({ status: 'in_progress' })],
    });
    const wrapper = mount(AgentMessage, {
      props: { message, isAnalyzing: true },
      global: { stubs: tdesignStubs },
    });

    expect(wrapper.findComponent(ContentStream).exists()).toBe(true);
  });

  it('分析完成后应显示 MessageFooter', () => {
    const message = createMockMessage({
      contentItems: [createContentItem({ type: 'report', content: '测试报告' })],
      analysisReport: '测试报告',
    });
    const wrapper = mount(AgentMessage, {
      props: { message, isAnalyzing: false },
      global: { stubs: tdesignStubs },
    });

    expect(wrapper.text()).toContain('复制');
    expect(wrapper.text()).toContain('重新生成');
  });

  it('分析中不应显示 MessageFooter', () => {
    const message = createMockMessage();
    const wrapper = mount(AgentMessage, {
      props: { message, isAnalyzing: true },
      global: { stubs: tdesignStubs },
    });

    expect(wrapper.text()).not.toContain('重新生成');
  });

  it('有建议追问且分析完成后应显示 SuggestionsSection（独立派生区块）', () => {
    const message = createMockMessage({
      contentItems: [createContentItem({ type: 'report', content: '测试' })],
      suggestions: [{ text: '深入分析', type: 'drill' }],
    });
    const wrapper = mount(AgentMessage, {
      props: { message, isAnalyzing: false },
      global: { stubs: tdesignStubs },
    });

    expect(wrapper.text()).toContain('深入分析');
  });

  it('分析中不应显示建议追问', () => {
    const message = createMockMessage({
      suggestions: [{ text: '深入分析', type: 'drill' }],
    });
    const wrapper = mount(AgentMessage, {
      props: { message, isAnalyzing: true },
      global: { stubs: tdesignStubs },
    });

    expect(wrapper.text()).not.toContain('你可能还想了解');
  });
});

describe('AgentMessage - 派生区块独立展示', () => {
  it('图表作为独立派生区块展示在内容流之外', () => {
    const message = createMockMessage({
      contentItems: [createContentItem({ type: 'report', content: '分析报告' })],
      analysisReport: '分析报告',
      chartConfig: {
        title: '销售额',
        series: [{ type: 'bar', data: [10, 20, 5] }],
      },
      chartType: 'bar',
    });
    const wrapper = mount(AgentMessage, {
      props: { message, isAnalyzing: false },
      global: {
        stubs: {
          ...tdesignStubs,
          ChartSection: { template: '<div class="chart-stub" />' },
        },
      },
    });

    // 图表区块与内容流并存（独立区块不并入内容流）
    expect(wrapper.findComponent(ContentStream).exists()).toBe(true);
    expect(wrapper.find('.chart-stub').exists()).toBe(true);
  });

  it('有搜索结果应显示 SearchResultsCard（独立派生区块）', () => {
    const message = createMockMessage({
      contentItems: [createContentItem({ type: 'report', content: '分析报告' })],
      searchResults: [{ title: '搜索标题', url: 'https://example.com', snippet: '摘要' }],
    });
    const wrapper = mount(AgentMessage, {
      props: { message, isAnalyzing: false },
      global: {
        stubs: {
          ...tdesignStubs,
          SearchResultsCard: { template: '<div class="search-stub">搜索标题</div>' },
        },
      },
    });

    expect(wrapper.find('.search-stub').exists()).toBe(true);
  });

  it('有查询数据应显示 DataTableSection（独立派生区块）', () => {
    const message = createMockMessage({
      contentItems: [createContentItem({ type: 'report', content: '分析报告' })],
      queryData: [{ month: '1月', value: 100 }],
    });
    const wrapper = mount(AgentMessage, {
      props: { message, isAnalyzing: false },
      global: {
        stubs: {
          ...tdesignStubs,
          DataTableSection: { template: '<div class="data-stub" />' },
        },
      },
    });

    expect(wrapper.find('.data-stub').exists()).toBe(true);
  });
});
