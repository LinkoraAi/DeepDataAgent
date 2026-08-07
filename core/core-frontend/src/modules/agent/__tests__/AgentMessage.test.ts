import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { mount } from '@vue/test-utils';
import { nextTick } from 'vue';
import { setActivePinia, createPinia } from 'pinia';
import AgentMessage from '../components/AgentMessage.vue';
import ReportSection from '../components/cards/ReportSection.vue';
import type { ChatMessage, AnalysisSnapshot, ReActRound } from '../types';

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

/** 创建模拟轮次 */
function createMockRound(overrides?: Partial<ReActRound>): ReActRound {
  return {
    id: 'round-1',
    startTime: Date.now() - 1000,
    endTime: Date.now(),
    thinking: {
      id: 'thinking-1',
      type: 'thinking',
      timestamp: Date.now() - 1000,
      content: '思考内容',
      isStreaming: false,
    },
    toolCalls: [],
    isActive: false,
    isCollapsed: true,
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
      rounds: [],
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

describe('AgentMessage - ReAct 轮次渲染', () => {
  // Mock requestAnimationFrame 以便 ReportSection 的 rAF 节流渲染可在测试中同步触发
  const originalRAF = globalThis.requestAnimationFrame;
  const originalCAF = globalThis.cancelAnimationFrame;
  let rafCallbacks: FrameRequestCallback[] = [];

  beforeEach(() => {
    setActivePinia(createPinia());
    rafCallbacks = [];
    // 队列化 rAF 回调，避免同步执行导致 TimelineThinkingItem 递归
    globalThis.requestAnimationFrame = vi.fn((cb: FrameRequestCallback) => {
      rafCallbacks.push(cb);
      return rafCallbacks.length;
    });
    globalThis.cancelAnimationFrame = vi.fn(() => {
      rafCallbacks = [];
    });
  });

  afterEach(() => {
    globalThis.requestAnimationFrame = originalRAF;
    globalThis.cancelAnimationFrame = originalCAF;
    rafCallbacks = [];
  });

  /** 触发所有已队列化的 rAF 回调（用于 ReportSection 渲染） */
  function flushRAF() {
    const cbs = [...rafCallbacks];
    rafCallbacks = [];
    cbs.forEach(cb => cb(0));
  }

  it('应渲染消息头部', () => {
    const message = createMockMessage();
    const wrapper = mount(AgentMessage, {
      props: { message, isAnalyzing: false },
      global: { stubs: tdesignStubs },
    });

    expect(wrapper.text()).toContain('DeepDataAgent');
  });

  it('分析中应在 TimelineSection 内显示 LoadingIndicator', () => {
    const message = createMockMessage({
      rounds: [createMockRound()],
    });
    const wrapper = mount(AgentMessage, {
      props: { message, isAnalyzing: true },
      global: { stubs: tdesignStubs },
    });

    const timelineSection = wrapper.findComponent({ name: 'TimelineSection' });
    expect(timelineSection.exists()).toBe(true);
    expect(wrapper.html()).toContain('timeline-section__loading');
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

  it('有分析报告应显示 ReportSection', () => {
    const message = createMockMessage({
      analysisReport: '## 分析结果\n本月销售额增长 15%。',
    });
    const wrapper = mount(AgentMessage, {
      props: { message, isAnalyzing: false },
      global: { stubs: tdesignStubs },
    });

    expect(wrapper.findComponent(ReportSection).exists()).toBe(true);
  });

  it('有 rounds 数据应显示 TimelineSection', () => {
    const message = createMockMessage({
      rounds: [createMockRound()],
    });
    const wrapper = mount(AgentMessage, {
      props: { message, isAnalyzing: false },
      global: { stubs: tdesignStubs },
    });

    expect(wrapper.findComponent({ name: 'TimelineSection' }).exists()).toBe(true);
  });

  it('无 rounds 数据不应显示 TimelineSection', () => {
    const message = createMockMessage({ rounds: [] });
    const wrapper = mount(AgentMessage, {
      props: { message, isAnalyzing: false },
      global: { stubs: tdesignStubs },
    });

    expect(wrapper.findComponent({ name: 'TimelineSection' }).exists()).toBe(false);
  });

  describe('hasReport 实时逻辑', () => {
    it('分析中且思考流式中应显示报告（流式渲染）', () => {
      const message = createMockMessage({
        analysisReport: '报告内容',
        rounds: [createMockRound({
          thinking: {
            id: 'thinking-1',
            type: 'thinking',
            timestamp: Date.now(),
            content: '思考中',
            isStreaming: true, // 流式中
          },
          isActive: true,
        })],
      });
      const wrapper = mount(AgentMessage, {
        props: { message, isAnalyzing: true },
        global: { stubs: tdesignStubs },
      });

      // 分析中实时显示报告
      expect(wrapper.findComponent(ReportSection).exists()).toBe(true);
    });

    it('分析中且有 running 工具应显示报告（流式渲染）', () => {
      const message = createMockMessage({
        analysisReport: '报告内容',
        rounds: [createMockRound({
          thinking: {
            id: 'thinking-1',
            type: 'thinking',
            timestamp: Date.now(),
            content: '思考完成',
            isStreaming: false,
          },
          toolCalls: [{
            id: 'tool-1',
            type: 'tool_call',
            toolName: 'execute_sql',
            status: 'running', // 运行中
            timestamp: Date.now(),
            startTime: Date.now(),
          }],
          isActive: true,
        })],
      });
      const wrapper = mount(AgentMessage, {
        props: { message, isAnalyzing: true },
        global: { stubs: tdesignStubs },
      });

      expect(wrapper.findComponent(ReportSection).exists()).toBe(true);
    });

    it('分析中即使所有轮次完成也应显示报告（流式渲染）', () => {
      const message = createMockMessage({
        analysisReport: '报告内容',
        rounds: [createMockRound({
          thinking: {
            id: 'thinking-1',
            type: 'thinking',
            timestamp: Date.now(),
            content: '思考完成',
            isStreaming: false,
          },
          toolCalls: [{
            id: 'tool-1',
            type: 'tool_call',
            toolName: 'execute_sql',
            status: 'success',
            timestamp: Date.now(),
            startTime: Date.now(),
            endTime: Date.now(),
          }],
          isActive: false,
        })],
      });
      const wrapper = mount(AgentMessage, {
        props: { message, isAnalyzing: true },
        global: { stubs: tdesignStubs },
      });

      // 分析中（isAnalyzing=true）实时显示报告
      expect(wrapper.findComponent(ReportSection).exists()).toBe(true);
    });

    it('分析完成后应始终显示报告（有内容时）', async () => {
      const message = createMockMessage({
        analysisReport: '报告内容',
        rounds: [createMockRound()],
      });
      const wrapper = mount(AgentMessage, {
        props: { message, isAnalyzing: false },
        global: { stubs: tdesignStubs },
      });

      // 触发 ReportSection 内 rAF 节流渲染，使 markdown 内容同步写入 DOM
      flushRAF();
      await nextTick();

      expect(wrapper.text()).toContain('报告内容');
    });
  });

  it('分析完成后应显示 MessageFooter', () => {
    const message = createMockMessage({
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

  it('有建议追问且分析完成后应显示 SuggestionsSection', () => {
    const message = createMockMessage({
      analysisReport: '测试',
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

describe('AgentMessage - 报告与图表渲染顺序', () => {
  it('分析完成后报告应渲染在图表之前（报告为主内容）', () => {
    const message = createMockMessage({
      analysisReport: '## 分析结果\n本月销售额增长 15%。',
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
          ReportSection: { template: '<div class="report-stub" />' },
          ChartSection: { template: '<div class="chart-stub" />' },
        },
      },
    });

    const reportEl = wrapper.find('.report-stub').element;
    const chartEl = wrapper.find('.chart-stub').element;
    // 报告节点应位于图表节点之前（DOM 顺序）
    expect(reportEl.compareDocumentPosition(chartEl) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });
});
