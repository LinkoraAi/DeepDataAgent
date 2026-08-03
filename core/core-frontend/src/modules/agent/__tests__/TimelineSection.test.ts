import { describe, it, expect } from 'vitest';
import { mount } from '@vue/test-utils';
import { nextTick } from 'vue';
import TimelineSection from '../components/cards/TimelineSection.vue';
import type { ReActRound } from '../types';

describe('TimelineSection - ReAct 轮次渲染', () => {
  const mockRound: ReActRound = {
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
    toolCalls: [
      {
        id: 'tool-1',
        type: 'tool_call',
        toolName: 'execute_sql',
        status: 'success',
        timestamp: Date.now(),
        startTime: Date.now() - 500,
        endTime: Date.now(),
        input: '{"sql": "SELECT 1"}',
        result: '{"data": [1]}',
      },
    ],
    isActive: false,
    isCollapsed: true,
  };

  const baseProps = {
    rounds: [mockRound],
    isAnalyzing: false,
    analysisStartTime: Date.now() - 5000,
    analysisEndTime: Date.now(),
    report: null,
  };

  const childStubs = {
    TimelineRound: { template: '<div class="timeline-round-stub" />' },
    't-loading': { template: '<div class="t-loading-stub" />' },
  };

  it('应渲染时间线头部标题', () => {
    const wrapper = mount(TimelineSection, {
      props: baseProps,
      global: { stubs: childStubs },
    });

    expect(wrapper.find('.timeline-section__header').exists()).toBe(true);
    expect(wrapper.text()).toContain('🧠 分析过程');
  });

  it('应展示总耗时', () => {
    const wrapper = mount(TimelineSection, {
      props: baseProps,
      global: { stubs: childStubs },
    });

    const meta = wrapper.find('.timeline-section__meta');
    expect(meta.exists()).toBe(true);
    expect(meta.text()).toContain('5.0');
  });

  it('应渲染轮次列表', () => {
    const wrapper = mount(TimelineSection, {
      props: baseProps,
      global: { stubs: childStubs },
    });

    expect(wrapper.find('.timeline-section__rounds').exists()).toBe(true);
    expect(wrapper.findAll('.timeline-round-stub')).toHaveLength(1);
  });

  it('多个轮次间应渲染弱分隔线', () => {
    const twoRoundsProps = {
      ...baseProps,
      rounds: [mockRound, { ...mockRound, id: 'round-2' }],
    };

    const wrapper = mount(TimelineSection, {
      props: twoRoundsProps,
      global: { stubs: childStubs },
    });

    // 两个轮次间应有 1 个分隔线
    expect(wrapper.findAll('.timeline-section__separator')).toHaveLength(1);
  });

  it('分析中应默认展开内容', () => {
    const wrapper = mount(TimelineSection, {
      props: { ...baseProps, isAnalyzing: true },
      global: { stubs: childStubs },
    });

    expect(wrapper.find('.timeline-section__collapsible-wrapper--expanded').exists()).toBe(true);
  });

  it('分析结束后应默认折叠内容', () => {
    const wrapper = mount(TimelineSection, {
      props: { ...baseProps, isAnalyzing: false },
      global: { stubs: childStubs },
    });

    expect(wrapper.find('.timeline-section__collapsible-wrapper--expanded').exists()).toBe(false);
  });

  it('点击头部应切换展开/折叠状态', async () => {
    const wrapper = mount(TimelineSection, {
      props: { ...baseProps, isAnalyzing: true },
      global: { stubs: childStubs },
    });
    await nextTick();

    // 分析中默认展开
    expect(wrapper.find('.timeline-section__collapsible-wrapper--expanded').exists()).toBe(true);

    // 点击头部折叠
    await wrapper.find('.timeline-section__header').trigger('click');
    await nextTick();
    expect(wrapper.find('.timeline-section__collapsible-wrapper--expanded').exists()).toBe(false);

    // 再次点击展开
    await wrapper.find('.timeline-section__header').trigger('click');
    await nextTick();
    expect(wrapper.find('.timeline-section__collapsible-wrapper--expanded').exists()).toBe(true);
  });

  it('rounds 为空时应显示空状态提示', () => {
    const wrapper = mount(TimelineSection, {
      props: { ...baseProps, rounds: [] },
      global: { stubs: childStubs },
    });

    expect(wrapper.find('.timeline-section__empty').exists()).toBe(true);
    expect(wrapper.text()).toContain('暂无分析过程');
  });
});
